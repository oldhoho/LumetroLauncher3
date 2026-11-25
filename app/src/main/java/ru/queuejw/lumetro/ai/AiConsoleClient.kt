package ru.queuejw.lumetro.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 一条会话消息。role: "user" / "assistant" */
data class ChatMessage(
    val role: String,
    var text: String,
    val ts: Long = System.currentTimeMillis(),
    var process: String = "",
    /** assistant 气泡：本次发送失败（连接断开/启动失败），UI 显示「重试」按钮 */
    var failed: Boolean = false,
    /** 失败气泡对应的用户原文，用于一键重试 */
    var retryText: String = "",
    /** 后端 /api/history(with_ids) 返回的稳定消息 id，收藏/定位用；本地乐观消息为空 */
    val id: String = "",
    /** 该消息所属的审查轮次 id，删除整轮时用 */
    val turnId: String = "",
)

/**
 * AI 控制台与九键面板共用的 CodexWeb 客户端。
 *
 * 对外接口（状态字段 + 方法）保持不变，调用方无需改动；内部对接 CodexWeb 后端：
 *   - 鉴权：凭证 URL 中的 cxkey / token 作为 X-CX-Key / X-CX-Token 请求头发送
 *   - 建会话：POST /api/sessions
 *   - 发任务：POST /api/chat  -> job
 *   - 进度流：GET /api/chat/stream?job=  (SSE，断线用 /api/chat/state 兜底)
 *   - 健康检查：GET /api/health
 *   - 最近总结：由流的 message 事件缓存到本地（CodexWeb 无 /api/report）
 */
object AiConsoleClient {

    private const val TAG = "AiConsole"
    private const val DEFAULT_PORT = 8123
    private val DEFAULT_URLS = listOf("http://127.0.0.1:$DEFAULT_PORT")

    private const val PREFS_NAME = "ai_console"
    private const val KEY_SERVER = "server_url"
    private const val KEY_SESSION = "session_id"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_SUMMARY_TIME = "summary_time"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HIDDEN_MESSAGES = "hidden_messages"
    private const val KEY_HISTORY_POLL = "history_poll_enabled"
    private const val KEY_POLL_INTERVAL = "history_poll_ms"
    private const val KEY_CONTEXT_RECORD = "context_record"
    private const val KEY_CUSTOM_CFG = "custom_mode_cfg"
    private const val KEY_PROMPTS = "prompt_presets"
    private const val KEY_PROMPT_ACTIVE = "prompt_active"
    private const val KEY_INJECT_MODE = "prompt_inject_mode"
    private const val KEY_PROMPT_BIND = "prompt_bind"
    /** 服务端全局配置缓存（收藏等），带 rev 乐观锁 */
    private const val KEY_CONFIG_CACHE = "shared_config"

    private const val MAX_STREAM_RETRY = 2
    /** Web 端发起的对话无法走本地 SSE，只能轮询会话历史来同步 */
    private const val DEFAULT_HISTORY_POLL_MS = 1500L

    /**
     * 客户端重试风暴去重窗口。后台被系统杀掉时，用户反复点发送会让同一条消息
     * 被多次提交；后端虽有 12s 去重，但跨窗口或换实例仍会漏过，每条都成为新
     * turn，纯对话模式再用 recent_history 全部重放 → 输入 token 雪球。
     * 客户端在更长的窗口内拦截重复提交，从源头掐断重试风暴。
     */
    private const val SEND_DEDUP_WINDOW_MS = 60_000L

    /** 与后端 WORK_MODES 对齐的合法模式集合 */
    private val WORK_MODES_CLIENT = setOf("full", "lite", "chat", "custom", "device")

    /** 与后端 SESSION_RE 一致：s-YYMMDD-HHMM-xxxx */
    private val SESSION_RE = Regex("^s-\\d{6}-\\d{4}-[0-9a-z]{4}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** Web 端新增的 assistant 消息（供自动朗读），只在真正新增时回调 */
    private val remoteAssistantListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var historyPollJob: Job? = null
    /** 上次已同步的历史签名，避免把本地刚播过的消息当成 Web 新消息重复朗读 */
    private var lastSyncedSignature = ""

    /** Web 同步轮询可由用户在「设置」里关闭或降频，默认开启（保持原有同步体验） */
    @Volatile
    private var historyPollEnabled: Boolean = true
    @Volatile
    private var historyPollIntervalMs: Long = DEFAULT_HISTORY_POLL_MS
    /** 轻量历史签名（/api/history/sig）+ 会话 meta 签名，避免每轮拉全量 */
    private var lastHistorySig: String? = null
    private var lastMetaSig: String? = null
    private var metaApplying = false

    /** 上下文审查记录开关，与 Web 端 cx.record 一致 */
    @Volatile
    private var contextRecord: Boolean = true

    /** 最近成功提交过的 (session,mode,text) -> 时间戳，用于拦截重复发送 */
    private val recentSends = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())

    /** 当前任务正在运行时，用户再次点发送属重复提交，直接忽略 */
    @Volatile
    private var submitInFlight = false
    private var appContext: Context? = null

    /** 保存的完整凭证 URL（含 ?cxkey=...），回显到输入框用 */
    @Volatile
    private var configuredUrl: String? = null

    /** scheme://host:port，所有 API 的拼接基线 */
    @Volatile
    private var apiBase: String? = null

    /** 从凭证 URL 解析出的鉴权请求头 */
    @Volatile
    private var authHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var sessionId: String? = null

    /**
     * 当前会话发送时使用的模式。默认 chat（纯对话）：启动器场景是快速问答，
     * 用 full 会加载工具/AGENTS.md 并可能在无设备控制权的沙箱里陷入反复探测
     * （正是日志里十几万 token 的来源）。切换已有会话时跟随该会话自身 mode。
     */
    @Volatile
    private var currentMode: String = "chat"

    /** 网页端「深度思考 / 智能搜索」的本会话快速覆盖；null 表示沿用模式预设 */
    @Volatile
    private var quickThink: Boolean? = null
    @Volatile
    private var quickSearch: Boolean? = null

    /** 随下一条消息一起提交的附件（网页端 composer 的 attachments） */
    data class PendingAttachment(val name: String, val data: String, val size: Int)
    private val pendingAttachments = java.util.Collections.synchronizedList(
        mutableListOf<PendingAttachment>()
    )

    @Volatile
    private var activeJob: String? = null

    @Volatile
    private var streamJob: Job? = null

    /** 本次任务是否收到过 error 事件；防止随后的 end 把错误态覆盖成空闲 */
    private var hadError = false

    @Volatile
    var running = false
        private set

    @Volatile
    var statusText = "AI 空闲"
        private set

    @Volatile
    var step = ""
        private set

    @Volatile
    var logText = ""
        private set

    @Volatile
    var summary = ""
        private set

    @Volatile
    var summaryTime = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 当前运行的实时用量（SSE usage 事件累积），供「运行控制与用量」页展示 */
    @Volatile
    var usageInput = 0L
        private set
    @Volatile
    var usageOutput = 0L
        private set
    @Volatile
    var usageCached = 0L
        private set

    fun usageSnapshot(): String = "输入 ${fmt(usageInput)} · 输出 ${fmt(usageOutput)} · 缓存 ${fmt(usageCached)}"

    /** 标题栏用：对齐网页端 chip 文案「↯ X tok · 命中 Y%」，命中率 = 缓存/输入 */
    fun usageHeaderText(): String {
        val rate = if (usageInput > 0) 100.0 * usageCached / usageInput else 0.0
        return "↯ ${fmt(usageInput + usageOutput)} tok · 命中 ${String.format(Locale.getDefault(), "%.1f", rate)}%"
    }

    private fun fmt(n: Long) = if (n >= 1000) "${String.format(Locale.getDefault(), "%.1f", n / 1000f)}k" else "$n"

    /** 重置本次运行用量 */
    fun resetUsage() { usageInput = 0; usageOutput = 0; usageCached = 0 }

    /** 当前会话消息列表（user/assistant），来自服务端 history 或本地乐观追加 */
    private val _messages = java.util.Collections.synchronizedList(mutableListOf<ChatMessage>())
    val messages: List<ChatMessage> get() = synchronized(_messages) { _messages.toList() }

    /**
     * 删除一条消息：
     * - 有 turnId（服务端已落库、/api/history 带 id）：调 /api/context/delete 真删整轮，
     *   两端同步消失，与 Web 审查页「删除本轮」一致。删除后面板由 refreshHistory 重建。
     * - 无 turnId（本地乐观消息/旧后端）：退回本地指纹隐藏，避免刷新后复活。
     */
    fun deleteMessage(index: Int, cb: ((Boolean, String) -> Unit)? = null) {
        val target = synchronized(_messages) {
            if (index in _messages.indices) _messages[index] else null
        }
        if (target == null) { cb?.invoke(false, "无法定位该消息"); return }
        val sid = sessionId
        if (target.turnId.isNotBlank() && !sid.isNullOrBlank()) {
            api("POST", "/api/context/delete", JSONObject()
                .put("session", sid).put("id", target.turnId).toString()
            ) { r ->
                if (r != null) {
                    lastHistorySig = null // 强制下一轮重新拉历史
                    refreshHistory()
                    cb?.invoke(true, "已删除该轮，Web 端同步")
                } else cb?.invoke(false, apiError())
            }
            return
        }
        // 本地隐藏兜底
        if (target.text.isNotBlank()) {
            val key = messageFingerprint(target.role, target.text)
            favoritesPrefs()?.let { prefs ->
                val set = prefs.getStringSet(KEY_HIDDEN_MESSAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
                set.add(key)
                prefs.edit().putStringSet(KEY_HIDDEN_MESSAGES, set).apply()
            }
        }
        synchronized(_messages) {
            if (index in _messages.indices) _messages.removeAt(index)
        }
        notifyListeners()
        cb?.invoke(true, "已删除本地消息")
    }

    /**
     * 修改一条用户消息并重新生成（对齐 Web 端 DeepSeek 式 edit）：
     * 先删掉该轮及其之后的所有对话（服务端自动备份），再用新文本走正常发送链路。
     * 删除失败则不发送，避免旧消息还在时又追加一条造成重复。
     */
    fun editAndSend(newText: String, turnId: String, cb: ((Boolean, String) -> Unit)? = null) {
        val sid = sessionId
        if (sid.isNullOrBlank() || turnId.isBlank()) {
            cb?.invoke(false, "这一轮尚未保存，无法修改")
            return
        }
        if (running || submitInFlight) {
            cb?.invoke(false, "已有任务在运行，请等待结束")
            return
        }
        api("POST", "/api/context/delete-from", JSONObject()
            .put("session", sid).put("id", turnId).toString()
        ) { r ->
            if (r == null) {
                cb?.invoke(false, apiError())
                return@api
            }
            lastHistorySig = null // 强制下一轮重新拉历史
            // 服务端已删除该轮及之后，本地同步移除，避免「唯一一轮被删后 history 为空、
            // refreshHistory 保留本地旧气泡」导致旧消息与重发消息并存。
            synchronized(_messages) {
                val cut = _messages.indexOfFirst { it.turnId == turnId }
                if (cut >= 0) {
                    // 同一轮里 user/assistant 都带 turnId；从第一条匹配处一直删到末尾
                    while (_messages.size > cut) _messages.removeAt(cut)
                }
            }
            notifyListeners()
            // 先刷新历史把被删轮次去掉，完成后再发送，避免两个异步请求竞态
            // 导致新追加的乐观气泡被随后返回的历史覆盖。
            refreshHistory { _, _ ->
                // 用新文本发送，force 绕过去重窗口（新文本本来也和旧的不同）
                sendTask(newText, force = true) { ok, msg ->
                    if (ok) cb?.invoke(true, "已修改并重新生成")
                    else cb?.invoke(false, msg)
                }
            }
        }
    }

    private fun messageFingerprint(role: String, text: String) = role + "\u0001" + text.trim()

    private fun hiddenMessageKeys(): Set<String> =
        favoritesPrefs()?.getStringSet(KEY_HIDDEN_MESSAGES, emptySet()) ?: emptySet()

    private fun favoritesPrefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 收藏一条消息文本；重复收藏同一段文本不会重复保存 */
    fun addFavorite(text: String): Boolean {
        val prefs = favoritesPrefs() ?: return false
        val set = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = set.add(text)
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
        return added
    }

    fun favoriteCount(): Int = favoritesPrefs()?.getStringSet(KEY_FAVORITES, emptySet())?.size ?: 0

    /** 列出全部收藏（本地 SharedPreferences，按文本排序稳定展示） */
    fun favorites(): List<String> =
        favoritesPrefs()?.getStringSet(KEY_FAVORITES, emptySet())?.toList()?.sorted() ?: emptyList()

    /** 移除一条收藏 */
    fun removeFavorite(text: String): Boolean {
        val prefs = favoritesPrefs() ?: return false
        val set = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val removed = set.remove(text)
        if (removed) prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
        return removed
    }

    // ------------------------------------------------------------ 全局共享配置
    /**
     * CodexWeb /api/config：带 rev 乐观锁的跨端配置（收藏等）。
     * 本地缓存只在离线时兜底展示；写入一律走服务端，409 时按服务端最新
     * rev/config 重试（与 Web 端 saveConfig 同样的合并策略），避免多端互相覆盖。
     */
    @Volatile
    private var configRev = 0
    private var configObj: JSONObject = JSONObject()
    private val configListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 收藏项：与 Web 端 config.favorites 结构一致 */
    data class FavoriteItem(
        val id: String, val sid: String, val role: String,
        val turnId: String, val text: String, val ts: Long
    )

    fun addConfigListener(listener: () -> Unit) { configListeners.addIfAbsent(listener) }
    fun removeConfigListener(listener: () -> Unit) { configListeners.remove(listener) }

    fun configRevision(): Int = configRev

    /** 服务端收藏列表（按 ts 倒序）；离线时回退到本地旧收藏 */
    fun favoriteItems(): List<FavoriteItem> {
        val arr = configObj.optJSONArray("favorites") ?: JSONArray()
        val list = mutableListOf<FavoriteItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(FavoriteItem(
                o.optString("id"), o.optString("sid"), o.optString("role"),
                o.optString("turnId"), o.optString("text"), o.optLong("ts")
            ))
        }
        if (list.isNotEmpty()) return list.sortedByDescending { it.ts }
        // 兼容旧版本地收藏：没有 id，用文本兜底，供收藏页仍能复制/移除
        return favorites().map {
            FavoriteItem("", "", "", "", it, 0L)
        }
    }

    fun isFavoriteId(id: String): Boolean {
        if (id.isBlank()) return false
        val arr = configObj.optJSONArray("favorites") ?: return false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("id") == id) return true
        }
        return false
    }

    /** 拉取全局配置（启动时、进入收藏页时调用） */
    fun loadConfig(onResult: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            val r = request("/api/config", "GET", null)
            if (r != null) {
                applyConfig(r)
                onResult?.invoke(true, "rev $configRev")
            } else {
                onResult?.invoke(false, apiError())
            }
        }
    }

    private fun applyConfig(r: JSONObject) {
        configRev = r.optInt("rev", 0)
        val c = r.optJSONObject("config")
        configObj = c ?: JSONObject()
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_CONFIG_CACHE, configObj.toString())?.apply()
        configListeners.forEach { runCatching { it() } }
    }

    /**
     * 写入全局配置的一个字段。409 时服务端带回最新 rev/config，
     * 把本次 patch 合并到最新 config 后重试，与 Web 端逻辑一致。
     */
    fun saveConfig(key: String, value: Any?, cb: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            for (attempt in 0 until 5) {
                val body = JSONObject()
                    .put("base_rev", configRev)
                    .put("config", JSONObject().apply {
                        put(key, if (value == null) JSONObject.NULL else value)
                    }).toString()
                val r = request("/api/config", "POST", body, captureErrorBody = true)
                if (r == null) {
                    cb?.invoke(false, apiError())
                    return@launch
                }
                if (r.optBoolean("conflict")) {
                    applyConfig(r)
                    continue // 用服务端最新 rev/config 重试
                }
                applyConfig(r)
                cb?.invoke(true, "已同步（rev $configRev）")
                return@launch
            }
            cb?.invoke(false, "配置并发冲突，请重试")
        }
    }

    /** 收藏/取消收藏一条带稳定 id 的消息，写服务端全局配置 */
    fun toggleFavoriteRemote(item: FavoriteItem, cb: ((Boolean, String) -> Unit)? = null) {
        val arr = configObj.optJSONArray("favorites") ?: JSONArray()
        val list = mutableListOf<JSONObject>()
        var exists = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == item.id && item.id.isNotBlank()) { exists = true; continue }
            list.add(o)
        }
        if (!exists) {
            list.add(JSONObject()
                .put("id", item.id).put("sid", item.sid).put("role", item.role)
                .put("turnId", item.turnId).put("text", item.text.take(240))
                .put("ts", if (item.ts > 0) item.ts else System.currentTimeMillis()))
        }
        // 乐观更新本地，UI 立即响应
        configObj.put("favorites", JSONArray(list))
        configListeners.forEach { runCatching { it() } }
        saveConfig("favorites", JSONArray(list), cb)
    }

    fun removeFavoriteRemote(id: String, cb: ((Boolean, String) -> Unit)? = null) {
        val arr = configObj.optJSONArray("favorites") ?: JSONArray()
        val list = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id && id.isNotBlank()) continue
            if (id.isBlank() && o.optString("text") == "") continue
            list.put(o)
        }
        configObj.put("favorites", list)
        configListeners.forEach { runCatching { it() } }
        saveConfig("favorites", list, cb)
    }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        configuredUrl = p.getString(KEY_SERVER, null)
        configuredUrl?.let { applyConfig(it) }
        sessionId = p.getString(KEY_SESSION, null)
        summary = p.getString(KEY_SUMMARY, "") ?: ""
        summaryTime = p.getString(KEY_SUMMARY_TIME, "") ?: ""
        historyPollEnabled = p.getBoolean(KEY_HISTORY_POLL, true)
        historyPollIntervalMs = p.getLong(KEY_POLL_INTERVAL, DEFAULT_HISTORY_POLL_MS)
            .coerceIn(1000L, 60_000L)
        contextRecord = p.getBoolean(KEY_CONTEXT_RECORD, true)
        // 全局配置先读离线缓存，联网后再由 loadConfig 覆盖
        val cachedConfig = p.getString(KEY_CONFIG_CACHE, null)
        if (!cachedConfig.isNullOrBlank()) {
            runCatching { configObj = JSONObject(cachedConfig) }
        }
        loadConfig()
    }

    // --------------------------------------------------- 性能 / 轮询设置
    /** 是否开启 Web 端消息同步轮询（设置页开关） */
    fun historyPollingEnabled(): Boolean = historyPollEnabled

    fun historyPollInterval(): Long = historyPollIntervalMs

    /**
     * 修改轮询策略并立即生效。intervalMs<=0 表示沿用当前间隔。
     * 关闭时立刻取消协程；开启时按新间隔重启（若当前已有会话）。
     */
    fun setHistoryPolling(enabled: Boolean, intervalMs: Long = 0L) {
        val nextInterval = if (intervalMs > 0) intervalMs.coerceIn(1000L, 60_000L) else historyPollIntervalMs
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(KEY_HISTORY_POLL, enabled)
            ?.putLong(KEY_POLL_INTERVAL, nextInterval)
            ?.apply()
        historyPollEnabled = enabled
        historyPollIntervalMs = nextInterval
        if (!enabled) {
            stopHistoryPolling()
        } else {
            // 换间隔也要重启协程，否则 delay 用的是旧值
            historyPollJob?.cancel()
            historyPollJob = null
            startPollingIfRunning()
        }
    }

    fun contextRecordEnabled(): Boolean = contextRecord

    fun setContextRecord(enabled: Boolean) {
        contextRecord = enabled
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(KEY_CONTEXT_RECORD, enabled)?.apply()
    }

    // --------------------------------------------------------------- public api
    // 供功能中心各模块（会话/上下文/设置/文件/技能/终端/设备…）调用。
    // 统一的鉴权、基址、错误记录都走已有的 request()；回调在主线程。

    /** 异步 JSON API，回调在主线程。body 为 JSON 字符串（GET 传 null）。 */
    fun api(method: String, path: String, body: String? = null, cb: (JSONObject?) -> Unit) {
        scope.launch { cb(request(path, method, body)) }
    }

    /** 协程内等待的 JSON API。 */
    suspend fun apiAwait(method: String, path: String, body: String? = null): JSONObject? =
        request(path, method, body)

    /**
     * 文件管理页上传：后端 /api/files/upload 接收原始字节流（不是 JSON），
     * 因此单独走一条带鉴权头的裸 HttpURLConnection。
     */
    fun uploadFile(path: String, bytes: ByteArray, cb: (Boolean, String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    val url = (apiBase ?: DEFAULT_URLS.first()) + path
                    connection = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 120_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/octet-stream")
                        authHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                    }
                    connection.outputStream.use { it.write(bytes) }
                    val code = connection.responseCode
                    val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code in 200..299) true to "已上传"
                    else {
                        lastError = "HTTP $code ${text.take(200)}"
                        false to (lastError ?: "上传失败")
                    }
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    false to (lastError ?: "上传失败")
                } finally {
                    connection?.disconnect()
                }
            }
            cb(result.first, result.second)
        }
    }

    /** request 的文字错误（供模块展示，避免各模块重复拼错误） */
    fun apiError(): String = lastError ?: "请求失败"

    /** 供文件管理等页面拼查询串使用 */
    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------ work modes
    // 与 CodexWeb MODE_DEFAULTS 一致。切模式时同步改 currentMode 并落盘到会话 meta。

    data class ModeCfg(val effort: String, val think: Boolean, val search: Boolean, val prompt: Boolean, val label: String)

    val MODE_DEFAULTS = mapOf(
        "full"   to ModeCfg("high",    true,  true,  true,  "全能模式 · 全部能力"),
        "lite"   to ModeCfg("low",     false, false, true,  "轻量模式 · 基础工具"),
        "chat"   to ModeCfg("minimal", false, false, false, "纯对话 · 极简直连"),
        "device" to ModeCfg("minimal", false, false, false, "手机控制 · 技能编排"),
        "custom" to ModeCfg("medium",  false, false, true,  "自定义模式"),
    )

    fun currentMode(): String = currentMode

    /** 自定义模式的四项配置（网页端 cx.customCfg，保存在本机） */
    fun customCfg(): ModeCfg {
        val base = MODE_DEFAULTS["custom"]!!
        val p = favoritesPrefs() ?: return base
        val raw = p.getString(KEY_CUSTOM_CFG, null) ?: return base
        return try {
            val o = JSONObject(raw)
            base.copy(
                effort = o.optString("effort", base.effort),
                think = o.optBoolean("think", base.think),
                search = o.optBoolean("search", base.search),
                prompt = o.optBoolean("prompt", base.prompt)
            )
        } catch (e: Exception) { base }
    }

    fun saveCustomCfg(cfg: ModeCfg) {
        favoritesPrefs()?.edit()?.putString(KEY_CUSTOM_CFG, JSONObject()
            .put("effort", cfg.effort).put("think", cfg.think)
            .put("search", cfg.search).put("prompt", cfg.prompt).toString())?.apply()
        notifyListeners()
    }

    /** 与网页端 effectiveModeCfg() 对齐：本会话快速开关优先于模式预设 */
    fun effectiveCfg(): ModeCfg {
        val base = if (currentMode == "custom") customCfg()
                   else (MODE_DEFAULTS[currentMode] ?: MODE_DEFAULTS["chat"]!!)
        return base.copy(
            think = quickThink ?: base.think,
            search = quickSearch ?: base.search
        )
    }

    // ------------------------------------------------------- 系统词预设库
    // 与 Web 端 localStorage 的 cx.prompts / cx.injectMode / cx.promptBind 同语义，
    // 启动器用 SharedPreferences 持久化；纯对话/手机控制模式仍不注入。

    data class PromptPreset(val id: String, val name: String, val text: String)

    val DEFAULT_PROMPTS = listOf(
        PromptPreset("code", "全栈工程助手",
            "你是一名资深全栈工程师。回答时先给结论，再给可运行的代码与必要的解释；代码要能直接复制使用，并说明关键取舍。"),
        PromptPreset("rev", "逆向分析助手",
            "你是移动应用安全与逆向工程师。分析时逐步说明原理、工具命令和结果含义；仅针对用户自己的应用或已授权目标。"),
        PromptPreset("net", "网络分析助手",
            "你是网络协议分析专家。分析流量时先给整体概览，再定位异常，最后给出可复现的过滤条件与命令。"),
        PromptPreset("sec", "安全审计助手",
            "你是应用安全审计员。请从输入校验、认证鉴权、传输与存储四个方面审查，并按严重程度排序给出修复建议。")
    )

    fun promptPresets(): List<PromptPreset> {
        val raw = favoritesPrefs()?.getString(KEY_PROMPTS, null) ?: return DEFAULT_PROMPTS
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<PromptPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(PromptPreset(o.optString("id"), o.optString("name"), o.optString("text")))
            }
            if (out.isEmpty()) DEFAULT_PROMPTS else out
        } catch (e: Exception) { DEFAULT_PROMPTS }
    }

    fun savePromptPresets(list: List<PromptPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
        favoritesPrefs()?.edit()?.putString(KEY_PROMPTS, arr.toString())?.apply()
    }

    fun activePromptId(): String =
        favoritesPrefs()?.getString(KEY_PROMPT_ACTIVE, promptPresets().first().id)
            ?: promptPresets().first().id

    fun setActivePromptId(id: String) {
        favoritesPrefs()?.edit()?.putString(KEY_PROMPT_ACTIVE, id)?.apply()
    }

    fun promptInjectMode(): String =
        favoritesPrefs()?.getString(KEY_INJECT_MODE, "off") ?: "off"

    fun setPromptInjectMode(mode: String) {
        if (mode !in setOf("off", "global", "session")) return
        favoritesPrefs()?.edit()?.putString(KEY_INJECT_MODE, mode)?.apply()
    }

    fun boundPromptId(sid: String?): String? {
        if (sid.isNullOrBlank()) return null
        val raw = favoritesPrefs()?.getString(KEY_PROMPT_BIND, null) ?: return null
        return try { JSONObject(raw).optString(sid).ifBlank { null } } catch (e: Exception) { null }
    }

    fun bindPromptToSession(sid: String?, promptId: String) {
        if (sid.isNullOrBlank()) return
        val prefs = favoritesPrefs() ?: return
        val map = try { JSONObject(prefs.getString(KEY_PROMPT_BIND, "{}") ?: "{}") }
                 catch (e: Exception) { JSONObject() }
        map.put(sid, promptId)
        prefs.edit().putString(KEY_PROMPT_BIND, map.toString()).apply()
        setPromptInjectMode("session")
    }

    /** 与网页端 effectiveSystemPrompt() 一致，受工作模式 prompt 总闸门控制 */
    fun effectiveSystemPrompt(): String {
        val cfg = if (currentMode == "custom") customCfg()
                  else (MODE_DEFAULTS[currentMode] ?: MODE_DEFAULTS["chat"]!!)
        if (!cfg.prompt) return ""
        val prompt = when (promptInjectMode()) {
            "session" -> promptPresets().firstOrNull { it.id == boundPromptId(sessionId) }
            "global" -> promptPresets().firstOrNull { it.id == activePromptId() }
            else -> null
        }
        return prompt?.text?.trim().orEmpty()
    }

    /** 切换工作模式；网页端切预设会清空快速覆盖，这里同步清，避免旧开关污染新预设 */
    fun setMode(mode: String, cb: ((Boolean, String) -> Unit)? = null) {
        if (mode !in MODE_DEFAULTS) { cb?.invoke(false, "未知模式"); return }
        currentMode = mode
        quickThink = null
        quickSearch = null
        // 本地写入后让下一轮 meta 轮询重建基线，避免把刚写的值当成远端改动重新套用
        lastMetaSig = null
        val sid = sessionId
        if (!sid.isNullOrBlank()) {
            val meta = JSONObject().put("mode", mode)
                .put("think", JSONObject.NULL).put("search", JSONObject.NULL)
            api("POST", "/api/sessions/meta",
                JSONObject().put("id", sid).put("meta", meta).toString()
            ) { cb?.invoke(true, MODE_DEFAULTS[mode]!!.label) }
        } else cb?.invoke(true, MODE_DEFAULTS[mode]!!.label)
    }

    /** 直接在本地设置模式（切会话时用，避免网络往返） */
    fun setModeLocal(mode: String) {
        if (mode in MODE_DEFAULTS) {
            currentMode = mode
            quickThink = null
            quickSearch = null
        }
    }

    /** 快速切换深度思考/智能搜索；写入会话 meta，与网页端同一份状态 */
    fun toggleQuick(kind: String, cb: ((Boolean, String) -> Unit)? = null) {
        if (kind != "think" && kind != "search") { cb?.invoke(false, "未知开关"); return }
        val next = if (kind == "think") !effectiveCfg().think else !effectiveCfg().search
        if (kind == "think") quickThink = next else quickSearch = next
        lastMetaSig = null // 本地写入，让轮询重建基线
        val label = if (next) "已开启" else "已关闭"
        val sid = sessionId
        if (!sid.isNullOrBlank()) {
            api("POST", "/api/sessions/meta",
                JSONObject().put("id", sid).put("meta", JSONObject().put(kind, next)).toString()
            ) { cb?.invoke(true, label) }
        } else cb?.invoke(true, "$label（仅本会话）")
    }

    /** 从会话列表恢复本会话的 think/search 快速覆盖 */
    private suspend fun loadQuickOverrides(sid: String?) {
        quickThink = null
        quickSearch = null
        if (sid.isNullOrBlank()) return
        val r = request("/api/sessions", "GET", null) ?: return
        val arr = r.optJSONArray("sessions") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != sid) continue
            if (o.has("think") && !o.isNull("think")) quickThink = o.optBoolean("think")
            if (o.has("search") && !o.isNull("search")) quickSearch = o.optBoolean("search")
            return
        }
    }

    fun currentInputUrl(): String = configuredUrl ?: DEFAULT_URLS.first()

    /** 当前会话 id（启动器自建、与 CodexWeb 网页端默认会话无关）；未建立返回 null */
    fun currentSessionId(): String? = sessionId

    /** 是否配置了带凭证的服务器地址 */
    fun hasServerConfigured(): Boolean = configuredUrl != null

    data class SessionItem(
        val id: String, val title: String, val mode: String, val mtime: Long,
        val think: Boolean? = null, val search: Boolean? = null,
        val group: String = "", val pinned: Boolean = false
    )

    /** 拉取 CodexWeb 会话列表（与网页端同一批会话） */
    fun loadSessions(onResult: (List<SessionItem>) -> Unit) {
        scope.launch {
            val r = request("/api/sessions", "GET", null)
            if (r == null) {
                onResult(emptyList())
                return@launch
            }
            val arr = r.optJSONArray("sessions") ?: JSONArray()
            val list = mutableListOf<SessionItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (!SESSION_RE.matches(id)) continue
                val title = o.optString("title").ifBlank { id }
                val think = if (o.has("think") && !o.isNull("think")) o.optBoolean("think") else null
                val search = if (o.has("search") && !o.isNull("search")) o.optBoolean("search") else null
                list.add(SessionItem(id, title, o.optString("mode"), o.optLong("mtime"),
                    think, search, o.optString("group"), o.optBoolean("pinned")))
            }
            onResult(list)
        }
    }

    /**
     * 进入会话页时调用：已有合法会话则直接拉历史；没有则自动建一个新会话再拉历史。
     * 这样用户打开 AI 窗就能立即看到内容，而不是空面板。
     */
    fun prepareSession(onResult: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            val sid = ensureSession()
            if (sid == null) {
                onResult?.invoke(false, lastError ?: "无法创建会话")
                return@launch
            }
            loadQuickOverrides(sid)
            refreshHistory(onResult)
        }
    }

    /** 切换到一个已有会话（网页端可见的同一个），并拉取其历史 */
    fun switchSession(sid: String, mode: String? = null, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!SESSION_RE.matches(sid)) {
            onResult?.invoke(false, "会话 id 无效")
            return
        }
        streamJob?.cancel()
        running = false
        submitInFlight = false
        activeJob = null
        hadError = false
        recentSends.clear()
        // 跟随该会话自身模式（网页端设置的 chat/full/... 原样沿用）
        currentMode = if (mode in WORK_MODES_CLIENT) mode!! else "chat"
        sessionId = sid
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SESSION, sid)?.apply()
        synchronized(_messages) { _messages.clear() }
        lastSyncedSignature = ""
        lastHistorySig = null
        lastMetaSig = null
        notifyListeners()
        scope.launch {
            loadQuickOverrides(sid)
            refreshHistory(onResult)
        }
    }

    /** 状态栏显示用，凭证脱敏：只显示基址 */
    fun serverLabel(): String {
        if (configuredUrl != null && apiBase == null) applyConfig(configuredUrl!!)
        val base = apiBase ?: DEFAULT_URLS.first()
        return if (authHeaders.isEmpty()) base else "$base · 已带凭证"
    }

    fun setServer(context: Context, raw: String): String? {
        val normalized = normalize(raw) ?: return null
        init(context)
        configuredUrl = normalized
        applyConfig(normalized)
        sessionId = null
        streamJob?.cancel()
        running = false
        submitInFlight = false
        activeJob = null
        currentMode = "chat"
        recentSends.clear()
        synchronized(_messages) { _messages.clear() }
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SERVER, normalized)?.remove(KEY_SESSION)?.apply()
        return normalized
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun addRemoteAssistantListener(listener: (String) -> Unit) {
        if (listener !in remoteAssistantListeners) remoteAssistantListeners.add(listener)
    }

    fun removeRemoteAssistantListener(listener: (String) -> Unit) {
        remoteAssistantListeners.remove(listener)
    }

    private fun messagesSignature(list: List<ChatMessage>): String =
        list.joinToString("\u0001") { "${it.role}:${it.text}" }

    /** 把当前本地消息标记为已同步，避免轮询把同一条再次当作 Web 新消息播报 */
    private fun syncSignatureFromLocal() {
        lastSyncedSignature = messagesSignature(synchronized(_messages) { _messages.toList() })
    }

    /** 添加一条随下一条消息发送的附件（与网页端 12 个 / 12MB 上限一致） */
    fun addAttachment(name: String, data: String, size: Int): Boolean {
        if (data.isBlank() || size <= 0) return false
        val current = synchronized(pendingAttachments) { pendingAttachments.toList() }
        if (current.size >= 12) return false
        if (current.sumOf { it.size } + size > 12 * 1024 * 1024) return false
        pendingAttachments.add(PendingAttachment(name, data, size))
        return true
    }

    fun removeAttachmentAt(index: Int) {
        synchronized(pendingAttachments) {
            if (index in pendingAttachments.indices) pendingAttachments.removeAt(index)
        }
    }

    fun clearAttachments() = synchronized(pendingAttachments) { pendingAttachments.clear() }

    fun attachmentCount(): Int = synchronized(pendingAttachments) { pendingAttachments.size }

    fun attachmentSummary(): String = synchronized(pendingAttachments) {
        pendingAttachments.joinToString("、") { "${it.name} · ${it.size / 1024}KB" }
    }

    private fun attachmentsJson(): JSONArray? = synchronized(pendingAttachments) {
        if (pendingAttachments.isEmpty()) return null
        val arr = JSONArray()
        pendingAttachments.forEach {
            arr.put(JSONObject().put("name", it.name).put("data", it.data))
        }
        arr
    }

    /** 去重指纹要包含附件，否则连续发送不同附件但同文案会被误判为重复 */
    private fun attachmentsKey(): String = synchronized(pendingAttachments) {
        pendingAttachments.joinToString(",") { "${it.name}:${it.size}" }
    }

    /** 发送一条会话消息：乐观追加 user 气泡与 assistant 占位气泡 */
    fun sendTask(text: String, force: Boolean = false, onResult: (Boolean, String) -> Unit) {
        val attachArr = attachmentsJson()
        if (text.isBlank() && attachArr == null) {
            onResult(false, "消息和附件不能都为空")
            return
        }
        // 网页端：只有附件时用短占位文，后端会再加附件头
        val wireText = text.ifBlank { "请处理附件：" + attachmentSummary() }
        // 任务还在跑时重复点发送：直接忽略，避免堆积成一堆 turn
        if (running || submitInFlight) {
            onResult(false, "上一条还在处理中，请稍候")
            return
        }
        // 同一会话 + 同一文本在去重窗口内已提交过：视为误触/重试风暴，拒绝。
        // 这层客户端去重比后端 12s 窗口更长，能兜住后台重启期间的反复重发。
        val now = System.currentTimeMillis()
        val cleanText = wireText.trim()
        val fp = (sessionId.orEmpty()) + "\u0001" + cleanText + "\u0001" + attachmentsKey()
        val duplicate = synchronized(recentSends) {
            val it = recentSends.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > SEND_DEDUP_WINDOW_MS) it.remove()
            }
            val dup = recentSends.containsKey(fp)
            if (!dup) recentSends[fp] = now
            dup
        }
        if (duplicate && !force) {
            onResult(false, "这条消息刚发送过，已忽略重复提交")
            return
        }
        if (duplicate && force) {
            // 修改重发：清掉旧指纹，改用新指纹，避免后续再次误判
            synchronized(recentSends) { recentSends.remove(fp) }
        }

        submitInFlight = true
        // 乐观追加 user 消息 + assistant 占位（主线程之外的调用也安全）
        synchronized(_messages) {
            _messages.add(ChatMessage("user", wireText))
            _messages.add(ChatMessage("assistant", ""))
        }
        notifyListeners()

        scope.launch {
            var sid = ensureSession()
            if (sid == null) {
                markSendingFailed(lastError ?: "无法创建会话")
                onResult(false, lastError ?: "无法创建会话")
                return@launch
            }
            var payload = chatPayload(sid, wireText)
            var response = request("/api/chat", "POST", payload)

            // 会话失效（换服务器 / 后端重启）时重建一次再试
            val err = lastError.orEmpty()
            if (response == null && (err.contains("404") || err.contains("unknown session")
                    || err.contains("bad session id"))) {
                sessionId = null
                sid = ensureSession()
                if (sid == null) {
                    markSendingFailed(lastError ?: "无法重建会话")
                    onResult(false, lastError ?: "无法重建会话")
                    return@launch
                }
                payload = chatPayload(sid, wireText)
                response = request("/api/chat", "POST", payload)
            }

            val job = response?.optString("job")
            if (job.isNullOrBlank()) {
                markSendingFailed(lastError ?: "任务启动失败（连接断开或服务未响应，可点重试）")
                onResult(false, lastError ?: "任务启动失败")
                return@launch
            }
            activeJob = job
            // POST 已被接受，附件归本次 turn；后续重试用气泡里的原文，不再重复带附件
            clearAttachments()
            // 从"提交中"切换到"运行中"：之后由 running 标志继续挡住重复提交
            submitInFlight = false
            running = true
            hadError = false
            resetUsage()
            statusText = "AI 运行中"
            step = "任务已提交"
            lastError = null
            notifyListeners()
            startStream(job)
            onResult(true, "任务已启动")
        }
    }

    /**
     * 标记本次发送失败：清空 in-flight、写入失败态到 assistant 占位气泡，
     * 并把用户原文存进气泡供「重试」按钮一键重发。
     */
    private fun markSendingFailed(msg: String) {
        submitInFlight = false
        running = false
        statusText = "发送失败"
        lastError = msg
        synchronized(_messages) {
            val last = _messages.lastOrNull { it.role == "assistant" }
            if (last != null) {
                // 从它前面最近的一条 user 气泡取回原文，供重试复用
                val userText = _messages.take(_messages.size - 1)
                    .lastOrNull { it.role == "user" }?.text?.trim().orEmpty()
                last.text = msg
                last.process = ""
                last.failed = true
                last.retryText = userText
            }
        }
        syncSignatureFromLocal()
        notifyListeners()
    }

    /**
     * 重试一条失败的消息。销毁旧的失败 user/assistant 气泡对，再用原文重新发送。
     * 只在用户主动点击时调用——后台断线绝不自动重发，从源头杜绝重试风暴。
     */
    fun retryFailed(onResult: (Boolean, String) -> Unit) {
        val failedMsg = synchronized(_messages) { _messages.lastOrNull { it.failed } }
        val text = failedMsg?.retryText.orEmpty()
        if (text.isBlank()) {
            onResult(false, "没有可重试的消息")
            return
        }
        if (running || submitInFlight) {
            onResult(false, "上一条还在处理中，请稍候")
            return
        }
        // 从去重表移除，否则 60s 窗口会把重试当成重复提交拦下
        val fp = (sessionId.orEmpty()) + "\u0001" + text.trim() + "\u0001" + attachmentsKey()
        synchronized(recentSends) { recentSends.remove(fp) }
        synchronized(_messages) {
            val idx = _messages.indexOfLast { it.failed }
            if (idx >= 0) {
                _messages.removeAt(idx)
                // 紧邻其前的 user 气泡一并移除，避免重发后留下重复原文
                val prev = idx - 1
                if (prev >= 0 && _messages[prev].role == "user") _messages.removeAt(prev)
            }
        }
        notifyListeners()
        sendTask(text, onResult = onResult)
    }

    /** 解析服务端历史（过滤本地已删除消息）；null 表示请求失败 */
    private fun parseHistoryMessages(r: JSONObject?): List<ChatMessage>? {
        if (r == null) return null
        val arr: JSONArray = r.optJSONArray("messages") ?: JSONArray()
        val hidden = hiddenMessageKeys()
        val list = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val role = obj.optString("role")
            if (role != "user" && role != "assistant") continue
            val content = obj.optString("content")
            if (content.isBlank()) continue
            // 过滤本地已删除的消息，避免刷新历史后复活
            if (messageFingerprint(role, content) in hidden) continue
            list.add(ChatMessage(
                role, content, obj.optLong("ts", 0L),
                id = obj.optString("id"), turnId = obj.optString("turnId")
            ))
        }
        return list
    }

    /** 拉取服务端历史并覆盖本地消息列表（多轮会话的唯一真相） */
    fun refreshHistory(onResult: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            val sid = sessionId
            if (sid.isNullOrBlank()) {
                onResult?.invoke(false, "尚无会话")
                return@launch
            }
            val r = request("/api/history?s=" + enc(sid), "GET", null)
            if (r == null) {
                onResult?.invoke(false, lastError ?: "历史加载失败")
                return@launch
            }
            val list = parseHistoryMessages(r) ?: emptyList()
            // 空历史不能覆盖本地乐观消息：新会话刚发一条时后端 turn 可能还没落库，
            // 若此时 clear() 会把用户刚发出的气泡清空，表现为「发送后什么都没有」。
            if (list.isEmpty() && _messages.isNotEmpty()) {
                notifyListeners()
                onResult?.invoke(false, "历史暂无记录，保留本地消息")
                return@launch
            }
            // 失败气泡还没落到服务端，覆盖时要保留，否则「重试」按钮会凭空消失
            val failedLocals = synchronized(_messages) { _messages.filter { it.failed } }
            synchronized(_messages) {
                _messages.clear()
                _messages.addAll(list)
                _messages.addAll(failedLocals)
            }
            syncSignatureFromLocal()
            notifyListeners()
            onResult?.invoke(true, "已加载 ${list.size} 条消息")
        }
    }

    /**
     * Web 端发起的对话不走启动器的 SSE，靠轮询会话历史同步。
     * Activity 进入前台时启动；切后台不取消，进程存活期间继续更新消息与朗读。
     */
    fun startHistoryPolling() {
        if (!historyPollEnabled) return
        if (historyPollJob?.isActive == true) return
        if (sessionId.isNullOrBlank()) return
        syncSignatureFromLocal()
        historyPollJob = scope.launch {
            // 先用当前服务端状态做基线，避免刚进入会话就把历史旧消息当成 Web 新消息朗读/套用
            sessionId?.let { sid0 ->
                request("/api/history?s=" + enc(sid0), "GET", null)?.let { r0 ->
                    parseHistoryMessages(r0)?.let { list0 ->
                        if (list0.isNotEmpty()) {
                            val failed = synchronized(_messages) { _messages.filter { it.failed } }
                            lastSyncedSignature = messagesSignature(list0 + failed)
                        }
                    }
                }
                request("/api/history/sig?s=" + enc(sid0), "GET", null)?.let {
                    lastHistorySig = it.optString("sig").ifBlank { null }
                }
                request("/api/sessions/meta?s=" + enc(sid0), "GET", null)?.let {
                    lastMetaSig = metaSignature(it.optJSONObject("meta"))
                }
            }
            while (isActive) {
                delay(historyPollIntervalMs)
                if (!historyPollEnabled) break
                val sid = sessionId ?: continue

                // 全局配置（收藏/注入模式等）：轻量 GET，rev 变化才通知 UI 刷新
                request("/api/config", "GET", null)?.let { r ->
                    val rev = r.optInt("rev", -1)
                    if (rev >= 0 && rev != configRev) applyConfig(r)
                }

                // 会话 meta（工作模式/快速开关/标题/置顶）：只套用到 UI，绝不回写，
                // 否则会和 Web 端形成来回覆盖的死循环。
                if (!running && !submitInFlight && !metaApplying) {
                    applyRemoteMeta(sid)
                }

                // 本地任务运行时流事件本身就是权威，历史轮询跳过，避免两套来源打架
                if (running || submitInFlight) continue
                // 先用轻量签名判断历史是否变化，未变化则省掉全量拉取
                val sigResp = request("/api/history/sig?s=" + enc(sid), "GET", null) ?: continue
                val histSig = sigResp.optString("sig").ifBlank { null }
                if (histSig != null && histSig == lastHistorySig) continue
                lastHistorySig = histSig
                val r = request("/api/history?s=" + enc(sid), "GET", null) ?: continue
                val serverList = parseHistoryMessages(r) ?: continue
                if (serverList.isEmpty()) continue
                val failedLocals = synchronized(_messages) { _messages.filter { it.failed } }
                val merged = serverList + failedLocals
                val msgSig = messagesSignature(merged)
                if (msgSig == lastSyncedSignature) continue
                val oldList = synchronized(_messages) { _messages.toList() }
                val oldAssistantTexts = oldList.filter { it.role == "assistant" }.map { it.text }.toSet()
                lastSyncedSignature = msgSig
                synchronized(_messages) {
                    _messages.clear()
                    _messages.addAll(merged)
                }
                notifyListeners()
                merged.lastOrNull {
                    it.role == "assistant" && it.text.isNotBlank() && !it.failed &&
                        it.text !in oldAssistantTexts
                }?.let { fresh ->
                    remoteAssistantListeners.forEach { runCatching { it(fresh.text) } }
                }
            }
        }
    }

    /** meta 签名：只包含会影响本地 UI 的字段 */
    private fun metaSignature(m: JSONObject?): String {
        if (m == null) return ""
        val think = if (m.has("think") && !m.isNull("think")) m.optBoolean("think").toString() else "null"
        val search = if (m.has("search") && !m.isNull("search")) m.optBoolean("search").toString() else "null"
        return listOf(
            m.optString("mode", "chat"), think, search,
            m.optString("title"), m.optBoolean("pinned").toString()
        ).joinToString("\u0001")
    }

    /** 应用 Web 端对会话 meta 的改动到本地；第一次读取只做基线 */
    private suspend fun applyRemoteMeta(sid: String) {
        val r = request("/api/sessions/meta?s=" + enc(sid), "GET", null) ?: return
        val m = r.optJSONObject("meta") ?: return
        val sig = metaSignature(m)
        if (sig == lastMetaSig) return
        if (lastMetaSig == null) { lastMetaSig = sig; return }
        lastMetaSig = sig
        metaApplying = true
        try {
            val wantMode = m.optString("mode", "chat")
            if (wantMode in WORK_MODES_CLIENT) currentMode = wantMode
            quickThink = if (m.has("think") && !m.isNull("think")) m.optBoolean("think") else null
            quickSearch = if (m.has("search") && !m.isNull("search")) m.optBoolean("search") else null
            notifyListeners()
        } finally {
            metaApplying = false
        }
    }

    fun stopHistoryPolling() {
        historyPollJob?.cancel()
        historyPollJob = null
    }

    fun checkServer(onResult: (Boolean, String) -> Unit) {
        scope.launch {
            val r = request("/api/health", "GET", null)
            if (r == null) {
                val e = lastError.orEmpty()
                val msg = if (e.contains("need_pin") || e.contains("401"))
                    "需要凭证：请在地址后加 ?cxkey=凭证 或 ?token=凭证"
                else lastError ?: "连接失败"
                onResult(false, msg)
                return@launch
            }
            if (r.optBoolean("need_pin")) {
                onResult(false, "需要凭证：请在地址后加 ?cxkey=凭证")
                return@launch
            }
            val ok = r.optBoolean("ok") && r.optBoolean("authenticated")
            if (ok) lastError = null
            val ready = r.optBoolean("codex")
            val ver = r.optString("version")
            val msg = when {
                !ok -> "鉴权失败"
                ready && ver.isNotBlank() -> "已连接 CodexWeb（$ver）"
                ready -> "已连接 CodexWeb"
                else -> "已连接 CodexWeb（后端未就绪）"
            }
            onResult(ok, msg)
        }
    }

    /** CodexWeb 无 /api/report，最近总结取本地缓存的最新一条 message */
    fun refreshReport() {
        scope.launch {
            val p = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?: return@launch
            val s = p.getString(KEY_SUMMARY, "") ?: ""
            val t = p.getString(KEY_SUMMARY_TIME, "") ?: ""
            if (s.isNotBlank()) {
                summary = s
                summaryTime = t
                notifyListeners()
            }
        }
    }

    /** onResume：启动 Web 端历史轮询；本地任务仍在运行则重连 SSE（后端会重放历史事件） */
    fun startPollingIfRunning() {
        startHistoryPolling()
        val job = activeJob
        if (running && !job.isNullOrBlank()) startStream(job)
    }

    // ------------------------------------------------------------------ session

    private val sessionMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun ensureSession(): String? {
        // 后端要求 session id 形如 s-260101-1234-abcd，残留的旧格式/非法 id 必须重建
        sessionId?.let { if (SESSION_RE.matches(it)) return it }
        // onResume 的 prepareSession 与 onWindowFocusChanged 的 prefill 发送可能
        // 并发进入，用互斥锁保证只创建一个会话，避免会话列表里出现重复空会话。
        return sessionMutex.withLock {
            sessionId?.let { if (SESSION_RE.matches(it)) return@withLock it }
            val r = request("/api/sessions", "POST", "{}") ?: return@withLock null
            val id = r.optString("id")
            if (!SESSION_RE.matches(id)) {
                lastError = "创建会话失败"
                return@withLock null
            }
            sessionId = id
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_SESSION, id)?.apply()
            id
        }
    }

    /**
     * 与 CodexWeb 前端 send() 一致的消息载荷：
     * - chat/device 不注入系统词；chat 的 think/search 走后端真实能力（thinking + web_search）
     * - 其余模式才把思考/联网/设备边界写进 prompt
     * - injections 只作审查记录，必须是对象（后端只保留 dict）
     */
    private fun chatPayload(sid: String, text: String): String {
        val cfg = effectiveCfg()
        val injections = JSONArray()
        var out = text
        val isPlain = currentMode == "chat" || currentMode == "device"
        val thinkText = "请进行充分、逐步的深入分析，展示你的思考过程与依据。"
        val searchText = "请尽可能利用可用工具联网检索最新信息并注明来源，再作答。"
        fun audit(kind: String, detail: String) {
            injections.put(JSONObject().put("kind", kind).put("text", detail))
        }
        if (isPlain) {
            if (currentMode == "chat") {
                if (cfg.think) audit("深度思考", "已启用服务端深度思考（thinking 参数），模型先推理再作答。")
                if (cfg.search) audit("智能搜索", "已启用服务端联网搜索（deepseek_web_search 工具）。")
            }
        } else {
            // 系统词总闸门 + 注入范围，与网页端 effectiveSystemPrompt() 对齐
            val sys = effectiveSystemPrompt()
            if (sys.isNotBlank()) {
                out = "[系统指令 / 角色设定]\n$sys\n\n[用户请求]\n$out"
                audit("系统词 / 角色设定", sys)
            }
            if (cfg.think) { out = "[推理要求] $thinkText\n\n$out"; audit("深度思考指令", thinkText) }
            if (cfg.search) { out = "[联网要求] $searchText\n\n$out"; audit("联网检索指令", searchText) }
            val deviceHint = "【本机能力说明】本会话不能直接控制手机：不要调用 cxdevice，也不要用 adb/input/am/pm 探测安卓系统。手机操作请使用「手机控制」模式。"
            out = "[设备能力边界] $deviceHint\n\n$out"
            audit("设备能力边界", deviceHint)
        }
        val payload = JSONObject()
            .put("session", sid)
            .put("prompt", out)
            .put("user_input", text)
            .put("mode", currentMode)
            .put("effort", cfg.effort)
            .put("injections", injections)
            .put("think", cfg.think)
            .put("search", cfg.search)
            .put("record", contextRecord)
        attachmentsJson()?.let { payload.put("attachments", it) }
        return payload.toString()
    }

    /** 停止当前任务（与设置页的停止按钮共用） */
    fun stopJob(cb: ((Boolean, String) -> Unit)? = null) {
        val job = activeJob
        if (job.isNullOrBlank()) { cb?.invoke(false, "没有运行中的任务"); return }
        api("POST", "/api/chat/stop", JSONObject().put("job", job).toString()) {
            running = false
            statusText = "已停止"
            notifyListeners()
            cb?.invoke(true, "已停止")
        }
    }

    /** 设备链紧急取消 */
    fun cancelDevice(cb: ((Boolean, String) -> Unit)? = null) {
        api("POST", "/api/device/cancel", "{}") { r ->
            cb?.invoke(r != null, "已取消 ${r?.optInt("cancelled", 0) ?: 0} 个设备任务")
        }
    }

    // ------------------------------------------------------------------ stream

    private fun startStream(jobId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            var retry = 0
            while (isActive && running) {
                val ended = streamOnce(jobId)
                if (ended || !isActive || !running) break

                // 流意外中断：用 state 判断任务是否其实已经结束
                val st = request("/api/chat/state?job=" + enc(jobId), "GET", null)
                if (st != null && !st.optBoolean("busy")) {
                    finishFromResult(st.optJSONObject("result"))
                    break
                }
                if (retry++ >= MAX_STREAM_RETRY) {
                    // 连接断开：直接判定本次发送失败并给出重试按钮，不再无限重连。
                    // 绝不自动重发 prompt——那正是 token 雪球的来源；重试只由用户点击触发。
                    markSendingFailed("连接中断，任务未完成（可点重试）")
                    break
                }
                delay(800L * retry)
            }
        }
    }

    /** 读取一次 SSE 流；返回是否收到了 end 事件 */
    private suspend fun streamOnce(jobId: String): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var gotEnd = false
        try {
            val url = (apiBase ?: DEFAULT_URLS.first()) + "/api/chat/stream?job=" + enc(jobId)
            connection = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 20000 // 服务端每 15s 发一次 ping
                setRequestProperty("Accept", "text/event-stream")
                authHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "HTTP $code ${text.take(200)}"
                return@withContext false
            }
            val reader = connection.inputStream.bufferedReader()
            var event = ""
            val data = StringBuilder()
            while (isActive) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (event.isNotBlank()) {
                            val payload = data.toString().trim()
                            val json = try {
                                if (payload.isNotBlank()) JSONObject(payload) else JSONObject()
                            } catch (e: Exception) {
                                JSONObject()
                            }
                            withContext(Dispatchers.Main) { dispatchEvent(event, json) }
                            if (event == "end") gotEnd = true
                            event = ""
                            data.setLength(0)
                            if (gotEnd) break
                        }
                    }
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                }
            }
        } catch (e: Exception) {
            if (isActive) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "stream failed", e)
            }
        } finally {
            connection?.disconnect()
        }
        gotEnd
    }

    /** 在主线程调用：把 SSE 事件映射到会话气泡与启动器状态 */
    private fun dispatchEvent(name: String, d: JSONObject) {
        when (name) {
            "start" -> step = "任务已启动"
            "stream" -> {
                // 后端 stream 事件携带的是「到目前为止的累积文本」（cumulative），
                // 必须整体替换气泡，不能追加——追加会把已有文本反复叠加，
                // 表现为文字疯狂复读、长度暴涨、窗口来回乱跳（曾误判为「两股文字流」）。
                d.optString("text").takeIf { it.isNotBlank() }?.let {
                    appendAssistant(it, replace = true)
                    step = "正在输出回复"
                }
            }
            "message" -> {
                // 完整文本：替换当前 assistant 气泡，避免与 stream 增量重复
                val t = d.optString("text")
                if (t.isNotBlank()) {
                    appendAssistant(t, replace = true)
                    step = "正在输出回复"
                    cacheSummary(t)
                }
            }
            "reasoning" -> {
                step = "思考中"
                d.optString("text").takeIf { it.isNotBlank() }?.let {
                    appendProcess("[思考] " + it.take(300))
                }
            }
            "command" -> {
                step = "执行命令"
                appendProcess("$ " + d.optString("command").take(120))
                d.optString("output").takeIf { it.isNotBlank() }?.let {
                    appendProcess(it.take(500))
                }
            }
            "tool" -> {
                val tool = d.optString("tool")
                step = if (tool.isBlank()) "调用工具" else "调用工具：$tool"
                if (tool.isNotBlank()) appendProcess("[工具] $tool")
            }
            "files" -> step = "修改文件"
            "todo" -> step = "更新任务清单"
            "usage" -> {
                // 兼容多种字段名：input/input_tokens/prompt_tokens 等
                fun pick(vararg keys: String): Long {
                    for (k in keys) {
                        val v = d.opt(k)
                        if (v is Number) return v.toLong()
                        if (v is String) v.toLongOrNull()?.let { return it }
                    }
                    return 0
                }
                usageInput = maxOf(usageInput, pick("input", "input_tokens", "prompt_tokens", "inputTokens"))
                usageOutput = maxOf(usageOutput, pick("output", "output_tokens", "completion_tokens", "outputTokens"))
                // 后端 SSE 实际下发的是 cached_input_tokens（见 codexd.py emit usage），
                // 之前只解析 cached/cached_tokens 导致命中率一直是 0。与网页端字段口径对齐。
                usageCached = maxOf(usageCached, pick(
                    "cached_input_tokens", "prompt_cache_hit_tokens",
                    "cached", "cached_tokens", "cache_read_input_tokens", "cachedTokens"
                ))
            }
            "log" -> d.optString("text").takeIf { it.isNotBlank() }?.let { appendLog(it) }
            "error" -> {
                val msg = d.optString("message").ifBlank { "任务出错" }
                hadError = true
                lastError = msg
                appendLog("[错误] $msg")
                appendProcess("[错误] $msg")
                statusText = "出错"
                // 不在这里结束：后端 error 之后必发 end，由 end 统一收尾
            }
            "end" -> {
                finishFromResult(d)
                return
            }
            // usage / turn / thread / hello / ping 不直接展示
        }
        notifyListeners()
    }

    /** 把文本内容写入最后一条 assistant 气泡；replace=true 时覆盖（用于 message 完整文本） */
    private fun appendAssistant(text: String, replace: Boolean) {
        synchronized(_messages) {
            val last = _messages.lastOrNull { it.role == "assistant" }
            if (last == null) {
                _messages.add(ChatMessage("assistant", text))
            } else if (replace) {
                last.text = text
            } else {
                last.text = (last.text + text).takeLast(20000)
            }
        }
    }

    /** 过程信息累积到最后一条 assistant 气泡的 process（折叠展示，不混进正文） */
    private fun appendProcess(text: String) {
        synchronized(_messages) {
            val last = _messages.lastOrNull { it.role == "assistant" } ?: return
            val prefix = if (last.process.isBlank()) "" else last.process + "\n"
            last.process = (prefix + text).takeLast(4000)
        }
    }

    private fun finishFromResult(res: JSONObject?) {
        running = false
        val exit = res?.optInt("exit", 0) ?: 0
        val stopped = res?.optBoolean("stopped") ?: false
        val err = res?.optString("error").orEmpty()
        statusText = when {
            hadError -> "出错"
            stopped -> "已停止"
            exit == 0 && err.isBlank() -> "AI 空闲"
            exit == 0 -> "运行结束"
            else -> "运行结束（退出码 $exit）"
        }
        if (err.isNotBlank()) appendLog("[结束] " + err.take(500))
        step = ""
        // 本地流已完整播报，标记为已同步，轮询不会把同一条当 Web 新消息再读一遍
        syncSignatureFromLocal()
        notifyListeners()
        // 后端 end 后再拉一次权威历史覆盖本地乐观消息，保证多轮对齐
        scope.launch {
            delay(400L)
            refreshHistory()
        }
    }

    private fun appendLog(text: String) {
        logText = (logText + (if (logText.isBlank()) "" else "\n") + text).takeLast(20000)
    }

    private fun cacheSummary(text: String) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        summary = text.take(2000)
        summaryTime = time
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SUMMARY, summary)?.putString(KEY_SUMMARY_TIME, time)?.apply()
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    // ------------------------------------------------------------------ http

    private suspend fun request(
        path: String,
        method: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        captureErrorBody: Boolean = false
    ): JSONObject? = withContext(Dispatchers.IO) {
        val base = apiBase ?: DEFAULT_URLS.first()
        executeRequest(base, path, method, body, authHeaders + extraHeaders, captureErrorBody)
    }

    private fun executeRequest(
        baseUrl: String,
        path: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        captureErrorBody: Boolean = false
    ): JSONObject? {
        var connection: HttpURLConnection? = null
        val url = baseUrl + path
        return try {
            connection = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 10000
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "HTTP $code ${text.take(300)}"
                Log.w(TAG, "$method $url -> HTTP $code (body ${text.length} chars)")
                // 乐观锁冲突的响应体带最新 rev/config，需要交给调用方重试
                if (captureErrorBody && text.isNotBlank()) {
                    try { return JSONObject(text) } catch (_: Exception) {}
                }
                return null
            }
            if (text.isBlank()) return JSONObject()
            try {
                JSONObject(text)
            } catch (e: Exception) {
                lastError = "响应不是合法 JSON：${text.take(300)}"
                Log.e(TAG, "$method $url -> parse failed (body ${text.length} chars)", e)
                null
            }
        } catch (e: Exception) {
            lastError = "$baseUrl  ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "request failed: $method $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 规范成 CodexWeb 可接受的地址，并保留凭证 query：
     *   http://host:8123/?cxkey=xxx
     * 不再丢弃 cxkey / token / pin，这是与旧客户端最关键的差别。
     */
    private fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return try {
            val uri = android.net.Uri.parse(value)
            val host = uri.host ?: return null
            val scheme = (uri.scheme ?: "http").lowercase(Locale.ROOT)
            val port = if (uri.port > 0) uri.port else DEFAULT_PORT
            val base = "$scheme://$host:$port/"
            // 只保留后端 launch_url() 实际签发的凭证：cxkey（可复用）或 token（一次性）。
            // pin 需走 POST /api/pin 换取 cookie，URL 里的 ?pin= 后端并不认，故不保留。
            val query = mutableListOf<String>()
            for (key in listOf("cxkey", "token")) {
                uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let {
                    query.add("$key=${URLEncoder.encode(it, "UTF-8")}")
                }
            }
            if (query.isEmpty()) base else base + "?" + query.joinToString("&")
        } catch (e: Exception) {
            null
        }
    }

    /** 从凭证 URL 解析出基址与鉴权头 */
    private fun applyConfig(url: String) {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return
        val scheme = (uri.scheme ?: "http").lowercase(Locale.ROOT)
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        apiBase = "$scheme://$host:$port"
        val headers = mutableMapOf<String, String>()
        uri.getQueryParameter("cxkey")?.takeIf { it.isNotBlank() }?.let { headers["X-CX-Key"] = it }
        uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { headers["X-CX-Token"] = it }
        authHeaders = headers
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
