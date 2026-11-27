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
    /** 助手气泡的深度思考过程（后端 reasoning 为累积文本，直接覆盖不追加），UI 折叠展示 */
    var reasoning: String = "",
    /** 联网搜索来源（title/url），Web 端 sources 事件：默认折叠、标题可点击跳浏览器 */
    var sources: List<Pair<String, String>> = emptyList(),
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
    private const val KEY_MODE_DEFS = "mode_defs"
    private const val KEY_PROMPTS = "prompt_presets"
    private const val KEY_PROMPT_ACTIVE = "prompt_active"
    private const val KEY_INJECT_MODE = "prompt_inject_mode"
    private const val KEY_PROMPT_BIND = "prompt_bind"
    /** 服务端全局配置缓存（收藏/工作模式/提示词），带 rev 乐观锁 */
    private const val KEY_CONFIG_CACHE = "shared_config"
    /** /api/config 中的键，必须与 CodexWeb Web 端一致 */
    private const val SHARED_MODE_DEFS = "modeDefs"
    private const val SHARED_PROMPTS = "prompts"

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
    private val WORK_MODES_CLIENT = setOf("full", "lite", "chat", "custom", "device", "secretary")

    /** 与后端 SESSION_RE 一致：s-YYMMDD-HHMM-xxxx */
    private val SESSION_RE = Regex("^s-\\d{6}-\\d{4}-[0-9a-z]{4}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    /**
     * 自动朗读的唯一入口：本端提问对应的回复完成时回调一次。
     * 单个槽位而非列表：Activity 重建时旧 overlay 若未及时注销，列表会残留两个 reader，
     * 一次完成就被读两遍。新注册直接替换旧的，保证任意时刻只有一个朗读者。
     */
    @Volatile
    private var localReplyListener: ((String) -> Unit)? = null
    /**
     * 一次性朗读令牌：把「本次提问」和「它的回复完成」绑在同一个 job 上。
     * 后端会把 end 事件写进可重放的事件列表，SSE 重连时会再收到一次 end →
     * finishFromResult 可能被调用两次；只有令牌尚未消费时才朗读，确保一条只读一次。
     * Web 端对话不经过 sendTask，永远拿不到令牌，因此绝不会从 AI 窗念出来。
     */
    @Volatile
    private var pendingSpeakJob: String? = null
    private var historyPollJob: Job? = null
    /** 上次已同步的历史签名，仅用于避免无意义重建消息列表，与朗读无关 */
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

    /**
     * 当前会话累计用量：与网页端 usageTotals 同源，权威数据来自 /api/context 的
     * turn.usage；本端跑完一轮也先即时累加，400ms 后的历史刷新再以服务端值校准。
     */
    @Volatile
    var sessionUsageInput = 0L
        private set
    @Volatile
    var sessionUsageOutput = 0L
        private set
    @Volatile
    var sessionUsageCached = 0L
        private set
    @Volatile
    var sessionUsageTurns = 0
        private set

    /** 标题栏第二行：会话累计 token / 命中率 / 轮数，小字展示 */
    fun sessionUsageHeaderText(): String {
        if (sessionUsageTurns == 0) return "会话累计 · 等待数据"
        val rate = if (sessionUsageInput > 0) 100.0 * sessionUsageCached / sessionUsageInput else 0.0
        return "会话 Σ ${fmt(sessionUsageInput + sessionUsageOutput)} tok · 命中 " +
            "${String.format(Locale.getDefault(), "%.1f", rate)}% · ${sessionUsageTurns} 轮"
    }

    private fun setSessionUsage(input: Long, cached: Long, output: Long, turns: Int) {
        sessionUsageInput = input
        sessionUsageCached = cached
        sessionUsageOutput = output
        sessionUsageTurns = turns
    }

    private fun resetSessionUsage() = setSessionUsage(0, 0, 0, 0)

    private fun fmt(n: Long) = if (n >= 1000) "${String.format(Locale.getDefault(), "%.1f", n / 1000f)}k" else "$n"

    /** 本次运行的最终用量是否已被会话累计消费；SSE end 重放时避免重复累加 */
    @Volatile
    private var runUsageCommitted = true

    /** 重置本次运行用量 */
    fun resetUsage() {
        usageInput = 0; usageOutput = 0; usageCached = 0
        runUsageCommitted = false
    }

    /** 一轮结束时把最终用量并入会话累计，幂等：同一 job 只记一次 */
    private fun commitRunUsage() {
        if (runUsageCommitted) return
        runUsageCommitted = true
        // 没有任何 usage 事件（发送失败/被取消）就不占一轮，避免误报
        if (usageInput <= 0 && usageOutput <= 0) return
        sessionUsageInput += usageInput
        sessionUsageCached += usageCached
        sessionUsageOutput += usageOutput
        sessionUsageTurns += 1
    }

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
    /** 最近一次配置同步结果，供设置/提示词页显示"已同步/失败" */
    @Volatile
    private var configSyncMessage: String = "未同步"
    fun configSyncStatus(): String = configSyncMessage
    /** 串行化配置写入，避免连续编辑触发多个同 rev 的 POST 互相 409 */
    private val configWriteMutex = kotlinx.coroutines.sync.Mutex()

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
                configSyncMessage = "已同步 · rev $configRev"
                seedSharedConfigIfNeeded()
                onResult?.invoke(true, "rev $configRev")
            } else {
                configSyncMessage = "同步失败：${apiError()}"
                onResult?.invoke(false, apiError())
            }
        }
    }

    /**
     * 首次接入共享配置时，把本机已有的模式/预设作为种子写入服务端一次。
     * 服务端已有键时绝不覆盖——服务端是跨端唯一权威，本机只做离线兜底。
     */
    private fun seedSharedConfigIfNeeded() {
        val patch = JSONObject()
        if (!configObj.has(SHARED_MODE_DEFS)) {
            patch.put(SHARED_MODE_DEFS, JSONObject().apply {
                modeDefs().forEach { (k, v) -> put(k, v.toJson()) }
            })
        }
        if (!configObj.has(SHARED_PROMPTS)) {
            patch.put(SHARED_PROMPTS, promptPresetsJson())
        }
        if (patch.length() == 0) return
        saveConfigPatch(patch) { ok, msg ->
            configSyncMessage = if (ok) "已同步 · rev $configRev" else "同步失败：$msg"
        }
    }

    private fun applyConfig(r: JSONObject) {
        configRev = r.optInt("rev", 0)
        val c = r.optJSONObject("config")
        configObj = c ?: JSONObject()
        // 工作模式/提示词改走共享配置：远端 rev 变化时必须让下一读取重新解析，
        // 不能让本机缓存把 Web 端刚改的模式名/开关盖回去。
        modeDefsCache = null
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_CONFIG_CACHE, configObj.toString())?.apply()
        configListeners.forEach { runCatching { it() } }
    }

    /**
     * 写入全局配置的一个字段。409 时服务端带回最新 rev/config，
     * 把本次 patch 合并到最新 config 后重试，与 Web 端逻辑一致。
     */
    fun saveConfig(key: String, value: Any?, cb: ((Boolean, String) -> Unit)? = null) {
        saveConfigPatch(JSONObject().put(key, if (value == null) JSONObject.NULL else value), cb)
    }

    /** 原子写入一组字段（模式定义 + 预设一起写时避免两次 rev 竞争）。串行化防止连点自冲突。 */
    fun saveConfigPatch(patch: JSONObject, cb: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            configWriteMutex.withLock {
                for (attempt in 0 until 5) {
                    val body = JSONObject()
                        .put("base_rev", configRev)
                        .put("config", patch).toString()
                    val r = request("/api/config", "POST", body, captureErrorBody = true)
                    if (r == null) {
                        cb?.invoke(false, apiError())
                        return@withLock
                    }
                    if (r.optBoolean("conflict")) {
                        applyConfig(r)
                        continue // 用服务端最新 rev/config 重试
                    }
                    applyConfig(r)
                    configSyncMessage = "已同步 · rev $configRev"
                    cb?.invoke(true, "已同步（rev $configRev）")
                    return@withLock
                }
                cb?.invoke(false, "配置并发冲突，请重试")
            }
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
    // 对齐 CodexWeb v58：内部 key 固定（后端按它区分直连/技能编排/Codex），
    // 六个模式的名称、说明、推理强度、开关与携带系统词都可编辑，保存在本机 modeDefs。

    data class ModeCfg(
        val name: String, val desc: String, val effort: String,
        val think: Boolean, val search: Boolean, val prompt: Boolean,
        val promptId: String = ""
    ) {
        fun toJson() = JSONObject()
            .put("name", name).put("desc", desc).put("effort", effort)
            .put("think", think).put("search", search).put("prompt", prompt)
            .put("promptId", promptId)
    }

    val MODE_DEFAULTS = mapOf(
        "full"   to ModeCfg("全能", "全部工具 + 高强度推理", "high", true, true, true),
        "lite"   to ModeCfg("轻量", "基础工具 + 低推理", "low", false, false, true),
        "chat"   to ModeCfg("纯对话", "极简直连 API，零系统提示/零工具，最省 Token", "minimal", false, false, false),
        "device" to ModeCfg("手机控制", "从技能模块编排流程，不能自由操控手机", "minimal", false, false, false),
        "custom" to ModeCfg("自定义", "自定义选项", "medium", false, false, true),
        // 助手：跨会话私人助手，长期记忆 + 稳定人格；走直连 API，由后端注入编译画像。
        "secretary" to ModeCfg("助手", "跨会话私人助手，长期记忆 + 稳定人格", "low", false, false, false),
    )

    private var modeDefsCache: Map<String, ModeCfg>? = null

    fun currentMode(): String = currentMode

    private fun parseModeDefs(obj: JSONObject): Map<String, ModeCfg> {
        val defaults = MODE_DEFAULTS
        return defaults.mapValues { (key, base) ->
            val o = obj.optJSONObject(key) ?: return@mapValues base
            base.copy(
                name = o.optString("name", base.name),
                desc = o.optString("desc", base.desc),
                effort = o.optString("effort", base.effort),
                think = if (o.has("think")) o.optBoolean("think") else base.think,
                search = if (o.has("search")) o.optBoolean("search") else base.search,
                prompt = if (o.has("prompt")) o.optBoolean("prompt") else base.prompt,
                promptId = o.optString("promptId", base.promptId)
            )
        }
    }

    fun modeDefsToJson(): JSONObject = JSONObject().apply {
        modeDefs().forEach { (k, v) -> put(k, v.toJson()) }
    }

    /**
     * 六个模式的可编辑配置。优先级：服务端 /api/config.modeDefs > 本机缓存/prefs。
     * 服务端是跨端唯一权威——Web 端改了模式名/开关，启动器下一轮轮询即可看到。
     * 首次读取会把旧 customCfg / injectMode 迁移进来，避免升级后丢失自定义。
     */
    fun modeDefs(): Map<String, ModeCfg> {
        modeDefsCache?.let { return it }
        configObj.optJSONObject(SHARED_MODE_DEFS)?.let { server ->
            if (server.length() > 0) {
                val parsed = parseModeDefs(server)
                modeDefsCache = parsed
                return parsed
            }
        }
        val defaults = MODE_DEFAULTS
        val p = favoritesPrefs() ?: return defaults
        val raw = p.getString(KEY_MODE_DEFS, null)
        val result: Map<String, ModeCfg>
        if (raw == null) {
            val migrated = defaults.toMutableMap()
            p.getString(KEY_CUSTOM_CFG, null)?.let { old ->
                try {
                    val o = JSONObject(old)
                    val base = defaults["custom"]!!
                    migrated["custom"] = base.copy(
                        effort = o.optString("effort", base.effort),
                        think = o.optBoolean("think", base.think),
                        search = o.optBoolean("search", base.search),
                        prompt = o.optBoolean("prompt", base.prompt)
                    )
                } catch (_: Exception) {}
            }
            // 旧版全局注入迁移：把当前激活预设绑到所有开启系统词的模式
            val oldInject = p.getString(KEY_INJECT_MODE, "off") ?: "off"
            val active = p.getString(KEY_PROMPT_ACTIVE, null)
            if (oldInject != "off" && !active.isNullOrBlank()) {
                for ((k, v) in migrated) if (v.prompt) migrated[k] = v.copy(promptId = active)
            }
            result = migrated
            p.edit().putString(KEY_MODE_DEFS, JSONObject().apply {
                migrated.forEach { (k, v) -> put(k, v.toJson()) }
            }.toString()).apply()
        } else {
            result = try {
                parseModeDefs(JSONObject(raw))
            } catch (e: Exception) { defaults }
        }
        modeDefsCache = result
        return result
    }

    /** 保存单个模式配置：本机即时生效 + 写服务端共享配置（两端同步） */
    fun saveModeCfg(mode: String, cfg: ModeCfg, cb: ((Boolean, String) -> Unit)? = null) {
        if (mode !in MODE_DEFAULTS) return
        val p = favoritesPrefs() ?: return
        val all = modeDefs().toMutableMap()
        all[mode] = cfg
        // 乐观更新：本机 UI 立即响应，不等待网络
        modeDefsCache = all
        p.edit().putString(KEY_MODE_DEFS, JSONObject().apply {
            all.forEach { (k, v) -> put(k, v.toJson()) }
        }.toString()).apply()
        // 先写回本机 configObj，避免 saveConfig 返回前 configListener 用旧值把 UI 盖回去
        configObj.put(SHARED_MODE_DEFS, JSONObject().apply {
            all.forEach { (k, v) -> put(k, v.toJson()) }
        })
        notifyListeners()
        saveConfig(SHARED_MODE_DEFS, configObj.getJSONObject(SHARED_MODE_DEFS), cb)
    }

    /** 恢复出厂默认模式（不删除提示词预设），两端同步 */
    fun resetModeDefs(cb: ((Boolean, String) -> Unit)? = null) {
        modeDefsCache = MODE_DEFAULTS
        favoritesPrefs()?.edit()?.remove(KEY_MODE_DEFS)?.apply()
        val json = JSONObject().apply { MODE_DEFAULTS.forEach { (k, v) -> put(k, v.toJson()) } }
        configObj.put(SHARED_MODE_DEFS, json)
        notifyListeners()
        saveConfig(SHARED_MODE_DEFS, json, cb)
    }

    /** 兼容旧调用：自定义模式的配置 */
    fun customCfg(): ModeCfg = modeCfg("custom")

    /** 当前模式的基础配置（不含本会话快速开关），对齐 Web 端 modeCfg() */
    fun modeCfg(mode: String = currentMode): ModeCfg =
        modeDefs()[mode] ?: modeDefs()["custom"] ?: MODE_DEFAULTS["chat"]!!

    /** 与 Web 端 modeLabel() 一致：取当前可编辑的模式名 */
    fun modeLabel(mode: String): String =
        modeDefs()[mode]?.name?.ifBlank { null } ?: MODE_DEFAULTS[mode]?.name ?: mode

    /** 智能模式提醒策略：两端共用 /api/config.suggestMode */
    data class SuggestCfg(val policy: String = "cautious", val preferred: String = "auto")

    fun suggestCfg(): SuggestCfg {
        val o = configObj.optJSONObject("suggestMode") ?: return SuggestCfg()
        val policy = o.optString("policy", "cautious").let {
            if (it in setOf("off", "cautious", "eager")) it else "cautious"
        }
        val preferred = o.optString("preferred", "auto").let {
            if (it in MODE_DEFAULTS) it else "auto"
        }
        return SuggestCfg(policy, preferred)
    }

    fun saveSuggestCfg(cfg: SuggestCfg, cb: ((Boolean, String) -> Unit)? = null) {
        saveConfig("suggestMode", JSONObject()
            .put("policy", cfg.policy).put("preferred", cfg.preferred), cb)
    }

    /** 模式真实能力：cli 由内部 key 决定（不可编辑），search/think 由 modeDefs 决定 */
    fun modeCan(key: String, cap: String): Boolean = when (cap) {
        "cli" -> key == "full" || key == "lite" || key == "custom"
        "search" -> modeCfg(key).search
        "think" -> modeCfg(key).think
        else -> false
    }

    /** 与网页端 effectiveModeCfg() 对齐：本会话快速开关优先于模式预设 */
    fun effectiveCfg(): ModeCfg {
        val base = modeCfg()
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

    private fun parsePromptPresets(arr: JSONArray): List<PromptPreset> {
        val out = mutableListOf<PromptPreset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank()) out.add(PromptPreset(id, o.optString("name"), o.optString("text")))
        }
        return if (out.isEmpty()) DEFAULT_PROMPTS else out
    }

    fun promptPresetsJson(): JSONArray = JSONArray().apply {
        promptPresets().forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
    }

    /** 提示词预设：服务端 /api/config.prompts 优先，本机 prefs 离线兜底 */
    fun promptPresets(): List<PromptPreset> {
        configObj.optJSONArray(SHARED_PROMPTS)?.let { arr ->
            if (arr.length() > 0) return parsePromptPresets(arr)
        }
        val raw = favoritesPrefs()?.getString(KEY_PROMPTS, null) ?: return DEFAULT_PROMPTS
        return try {
            parsePromptPresets(JSONArray(raw))
        } catch (e: Exception) { DEFAULT_PROMPTS }
    }

    /** 保存预设：本机即时生效 + 写服务端共享配置（Web 端下一轮轮询可见） */
    fun savePromptPresets(list: List<PromptPreset>, cb: ((Boolean, String) -> Unit)? = null) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
        favoritesPrefs()?.edit()?.putString(KEY_PROMPTS, arr.toString())?.apply()
        configObj.put(SHARED_PROMPTS, arr)
        notifyListeners()
        saveConfig(SHARED_PROMPTS, arr, cb)
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

    /**
     * 与网页端 effectiveSystemPrompt() 一致：系统词由当前模式直接携带。
     * 所有模式（含纯对话）都支持，只有显式勾选注入并选了预设才生效。
     */
    fun effectiveSystemPrompt(): String {
        val cfg = modeCfg()
        if (!cfg.prompt || cfg.promptId.isBlank()) return ""
        return promptPresets().firstOrNull { it.id == cfg.promptId }?.text?.trim().orEmpty()
    }

    /** 当前模式携带的系统词预设名（提示用） */
    fun carriedPromptName(): String {
        val cfg = modeCfg()
        if (!cfg.prompt || cfg.promptId.isBlank()) return ""
        return promptPresets().firstOrNull { it.id == cfg.promptId }?.name.orEmpty()
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
            ) { cb?.invoke(true, modeLabel(mode)) }
        } else cb?.invoke(true, modeLabel(mode))
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

    /**
     * 设置面板的「联网搜索 / 深度思考」是模式级默认开关：写入当前工作模式的
     * modeDefs，影响此后新建的所有会话；同时清掉本会话的 quick 覆盖，避免旧
     * 覆盖把刚设好的默认值继续压住。顶栏 chip 仍走 toggleQuick（仅本会话）。
     */
    fun setModeDefault(kind: String, enabled: Boolean, cb: ((Boolean, String) -> Unit)? = null) {
        if (kind != "think" && kind != "search") { cb?.invoke(false, "未知开关"); return }
        val mode = currentMode
        val cur = modeCfg(mode)
        val updated = if (kind == "think") cur.copy(think = enabled) else cur.copy(search = enabled)
        val label = if (enabled) "已开启" else "已关闭"
        saveModeCfg(mode, updated) { ok, msg ->
            if (!ok) { cb?.invoke(false, msg); return@saveModeCfg }
            clearQuickOverride(kind) { _, _ -> cb?.invoke(true, "$label「${modeLabel(mode)}」模式默认（新会话同步）") }
        }
    }

    /** 清除本会话的快速覆盖，让模式默认立即生效；服务端用 null 表示清除 */
    fun clearQuickOverride(kind: String, cb: ((Boolean, String) -> Unit)? = null) {
        if (kind == "think") quickThink = null else if (kind == "search") quickSearch = null else return
        lastMetaSig = null
        val sid = sessionId
        if (!sid.isNullOrBlank()) {
            api("POST", "/api/sessions/meta",
                JSONObject().put("id", sid)
                    .put("meta", JSONObject().put(kind, JSONObject.NULL)).toString()
            ) { cb?.invoke(true, "已恢复模式默认") }
        } else cb?.invoke(true, "已恢复模式默认")
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

    /** 新建会话：解绑本地旧会话，让后端分配新 id，再拉空历史（对应 Web 侧栏「新建会话」） */
    fun newSession(cb: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            sessionId = null
            val id = ensureSession()
            if (id == null) {
                cb?.invoke(false, lastError ?: "创建会话失败")
                return@launch
            }
            synchronized(_messages) { _messages.clear() }
            lastSyncedSignature = ""
            lastHistorySig = null
            loadQuickOverrides(id)
            refreshHistory { ok, msg -> cb?.invoke(ok, msg) }
            notifyListeners()
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
        resetSessionUsage()
        resetUsage()
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
        resetSessionUsage()
        resetUsage()
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

    fun addLocalReplyListener(listener: (String) -> Unit) {
        // 直接替换：旧 Activity 未注销时不会残留第二个朗读者
        localReplyListener = listener
    }

    fun removeLocalReplyListener(listener: (String) -> Unit) {
        // 只清掉仍指向自己的注册，避免误删新 Activity 的监听
        if (localReplyListener === listener) localReplyListener = null
    }

    private fun messagesSignature(list: List<ChatMessage>): String =
        list.joinToString("\u0001") {
            // 把来源纳入签名：Web 对话正文可能先被一轮轮询同步，来源在下一轮才从
            // /api/context 还原出来。若签名只看正文，这一轮会被当成"无变化"跳过，
            // 来源折叠块就永远补不上。用 url 序列做指纹，稳定且不受顺序外因素影响。
            val srcFp = it.sources.joinToString(",") { (_, url) -> url }
            "${it.role}:${it.text}|s=$srcFp"
        }

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
            // 只有真正从 AI 窗发出的这条提问才登记朗读令牌。sendTask 是本端唯一发送入口，
            // Web 端对话走服务端 + 历史轮询，不经过这里，永远不会拿到令牌。
            pendingSpeakJob = job
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
                // 与 Web 端错误气泡一致带 ⚠️ 前缀，并给出可操作的排查提示
                val friendly = when {
                    Regex("unauthor|invalid.*key|401|403|authentication|api.?key|insufficient|余额|欠费", RegexOption.IGNORE_CASE)
                        .containsMatchIn(msg) -> "⚠️ API 认证/权限失败：$msg\n\n请检查 Key、Base URL 与账户状态。"
                    else -> "⚠️ $msg"
                }
                last.text = friendly
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
            // v68+ 后端 /api/history 直接在 assistant 消息上带 sources（与 Web /api/context
            // 同源）。这是跨端来源的权威路径：Web 端发起的对话、历史会话都能拿到，
            // 不再依赖客户端在 SSE 里恰好收到事件。
            val srcs = obj.optJSONArray("sources")?.let { parseSourceItems(it) }.orEmpty()
            list.add(ChatMessage(
                role, content, obj.optLong("ts", 0L),
                reasoning = obj.optString("reasoning"),
                sources = srcs,
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
            // 与 Web 端同源兜底：/api/history 不带 sources 时，从 /api/context 的
            // turn.events 按 turnId 还原。覆盖切会话/手动刷新场景，避免来源丢失。
            val ctx = try { sessionId?.let { loadContextData(it) } }
                catch (_: Exception) { null } ?: ContextSnapshot()
            // 会话累计以 /api/context 的 turn.usage 为权威值，切会话/刷新后即时校准
            setSessionUsage(ctx.input, ctx.cached, ctx.output, ctx.turns)
            if (ctx.sources.isNotEmpty()) {
                list.forEach { m ->
                    if (m.sources.isEmpty() && m.turnId.isNotBlank()) {
                        ctx.sources[m.turnId]?.let { m.sources = it }
                    }
                }
            }
            // 空历史不能覆盖本地乐观消息：新会话刚发一条时后端 turn 可能还没落库，
            // 若此时 clear() 会把用户刚发出的气泡清空，表现为「发送后什么都没有」。
            if (list.isEmpty() && _messages.isNotEmpty()) {
                notifyListeners()
                onResult?.invoke(false, "历史暂无记录，保留本地消息")
                return@launch
            }
            // 失败气泡还没落到服务端，覆盖时要保留，否则「重试」按钮会凭空消失
            // 同理：服务端 /api/history 不返回 reasoning，end 后 400ms 的权威刷新若直接覆盖，
            // 本轮刚产生的「已深度思考」折叠块会凭空消失。覆盖前按身份把本地思考过程存下来。
            // v68+ 后端 /api/history 已直接带 sources（权威来源），正常轮次无需合并。
            // 但本客户端刚发出的乐观消息在 turn 落库前可能带 SSE 来源而历史里还没有，
            // 覆盖后按身份并回去，避免这几百毫秒内来源折叠块闪一下就消失。
            val localSources = synchronized(_messages) {
                _messages.filter { it.sources.isNotEmpty() }
                    .associate { "${it.role}|${it.text.trim()}" to it.sources }
            }
            val (failedLocals, localReasoning, localProcess) = synchronized(_messages) {
                Triple(
                    _messages.filter { it.failed },
                    _messages.filter { it.reasoning.isNotBlank() }
                        .associate { "${it.role}|${it.text.trim()}" to it.reasoning },
                    // /api/history 不返回工具过程（联网搜索来源、命令输出都只在 SSE 里），
                    // 不并回去的话 end 后 400ms 的权威刷新会让「过程」折叠块凭空消失。
                    _messages.filter { it.process.isNotBlank() }
                        .associate { "${it.role}|${it.text.trim()}" to it.process }
                )
            }
            synchronized(_messages) {
                _messages.clear()
                _messages.addAll(list)
                _messages.addAll(failedLocals)
                // 本地乐观消息的正文与落库正文一致，用 角色+正文 匹配把思考过程并回去；
                // 结构签名里的 reasoning/process.isNotBlank() 因此保持稳定，不会触发整表重建。
                localReasoning.forEach { (key, reason) ->
                    _messages.firstOrNull { "${it.role}|${it.text.trim()}" == key }?.reasoning = reason
                }
                localProcess.forEach { (key, proc) ->
                    _messages.firstOrNull { "${it.role}|${it.text.trim()}" == key }?.process = proc
                }
                localSources.forEach { (key, srcs) ->
                    _messages.firstOrNull { "${it.role}|${it.text.trim()}" == key }?.sources = srcs
                }
            }
            syncSignatureFromLocal()
            notifyListeners()
            onResult?.invoke(true, "已加载 ${list.size} 条消息")
        }
    }

    /**
     * 轮询入口：配置（模式/预设/收藏）始终轻量同步，不受"消息轮询"开关影响；
     * 消息历史仅在开关开启时拉取。Activity 进入前台启动，切后台不取消。
     */
    fun startHistoryPolling() {
        if (historyPollJob?.isActive == true) return
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
                // 关闭消息轮询时用较长间隔仍保持设置跨端同步（1.5s -> 6s）
                delay(if (historyPollEnabled) historyPollIntervalMs else 6000L)

                // 全局配置（收藏/工作模式/提示词预设）：始终轮询，rev 变化才通知 UI
                request("/api/config", "GET", null)?.let { r ->
                    val rev = r.optInt("rev", -1)
                    if (rev >= 0 && rev != configRev) {
                        applyConfig(r)
                        configSyncMessage = "已同步 · rev $configRev"
                    }
                }

                if (!historyPollEnabled) continue
                val sid = sessionId ?: continue

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
                // 关键：Web 端对话产生的来源不在 /api/history 里（本地也从未收到那条 SSE）。
                // 与 Web 端 renderSessionHistory 同源，从 /api/context 的 turn.events 还原，
                // 按 turnId 精确补回，这样 Web 对话完成后的一轮轮询就能带上来源。
                val ctx = try { loadContextData(sid) } catch (_: Exception) { ContextSnapshot() }
                // Web 端新增轮次后，会话累计随历史一起校准
                setSessionUsage(ctx.input, ctx.cached, ctx.output, ctx.turns)
                if (ctx.sources.isNotEmpty()) {
                    serverList.forEach { m ->
                        if (m.sources.isEmpty() && m.turnId.isNotBlank()) {
                            ctx.sources[m.turnId]?.let { m.sources = it }
                        }
                    }
                }
                val failedLocals = synchronized(_messages) { _messages.filter { it.failed } }
                val merged = serverList + failedLocals
                val msgSig = messagesSignature(merged)
                if (msgSig == lastSyncedSignature) continue
                lastSyncedSignature = msgSig
                synchronized(_messages) {
                    _messages.clear()
                    _messages.addAll(merged)
                }
                // 历史轮询只负责同步展示，绝不触发朗读。朗读唯一入口是本端提问完成时
                // 的 finishFromResult；Web 端产生的回复无论正文怎么变都不从 AI 窗念出来。
                notifyListeners()
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
            // 新会话是空账本：清掉上一会话的累计用量，避免首屏显示旧数字
            resetSessionUsage()
            resetUsage()
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_SESSION, id)?.apply()
            id
        }
    }

    /**
     * 与 CodexWeb 前端 send() 一致的消息载荷（v58）：
     * - 系统词由当前模式直接携带，所有模式（含纯对话）都支持，显式勾选才注入
     * - chat/device/secretary 是直连模式：think/search 走后端真实能力（thinking + web_search），
     *   助手画像与日志/笔记提取由后端按 mode=secretary 自动完成，只记审计说明
     * - 其余模式把思考/联网/设备边界写进 prompt
     * - injections 只作审查记录，必须是对象（后端只保留 dict）
     */
    private fun chatPayload(sid: String, text: String): String {
        val cfg = effectiveCfg()
        val injections = JSONArray()
        var out = text
        val isPlain = currentMode == "chat" || currentMode == "device" || currentMode == "secretary"
        val thinkText = "请进行充分、逐步的深入分析，展示你的思考过程与依据。"
        val searchText = "请尽可能利用可用工具联网检索最新信息并注明来源，再作答。"
        fun audit(kind: String, detail: String) {
            injections.put(JSONObject().put("kind", kind).put("text", detail))
        }
        // 系统词前置：由模式自身携带，所有模式生效（纯对话默认不带，用户可显式指定）
        val sys = effectiveSystemPrompt()
        if (sys.isNotBlank()) {
            out = "[系统指令 / 角色设定]\n$sys\n\n[用户请求]\n$out"
            audit("系统词 / 角色设定", sys)
        }
        if (isPlain) {
            if (currentMode == "chat") {
                if (cfg.think) audit("深度思考", "已启用服务端深度思考（thinking 参数），模型先推理再作答。")
                if (cfg.search) audit("智能搜索", "已启用服务端联网搜索（deepseek_web_search 工具）。")
            }
        } else {
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
                // 后端下发的是「累积文本」（每帧都是完整思考），必须覆盖而非追加，
                // 否则每帧叠一遍会把同一段思考重复几十次，折叠块里全是乱码般重复。
                d.optString("text").takeIf { it.isNotBlank() }?.let { setReasoning(it) }
            }
            "command" -> {
                step = "执行命令"
                appendProcess("$ " + d.optString("command").take(120))
                d.optString("output").takeIf { it.isNotBlank() }?.let {
                    appendProcess(it.take(500))
                }
            }
            "sources" -> {
                // Web 端最新：结构化 citations 事件 {items:[{title,url}]}，UI 默认折叠、
                // 标题是可点击超链接。后端同时仍发旧版 tool("联网搜索来源", status 纯文本)
                // 供老客户端/审查页兼容——这里跳过它，避免同一批来源显示两遍。
                step = "联网搜索来源"
                setSources(d.optJSONArray("items"))
            }
            "tool" -> {
                val tool = d.optString("tool")
                // 新版后端同时发 sources 事件，纯文本镜像不再进对话过程，防止重复。
                // 老后端没有 sources 事件时，仍用这段文本保留来源可追溯性。
                if (tool == "联网搜索来源" && d.optString("status").contains(" — ")) {
                    step = "联网搜索来源"
                    return
                }
                step = if (tool.isBlank()) "调用工具" else "调用工具：$tool"
                if (tool.isNotBlank()) appendProcess("[工具] $tool")
                d.optString("status").takeIf { it.isNotBlank() }?.let {
                    appendProcess(it.take(2000))
                }
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
                // 传输层重连噪音不进对话，只留诊断日志（与 Web 端过滤口径一致）
                if (!Regex("reconnect|stream disconnected|error decoding response body|transport error|connection reset|temporarily unavailable", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                    hadError = true
                    lastError = msg
                    appendErrorBubble(msg)
                }
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

    /**
     * 深度思考文本：后端每帧都是「到目前为止的完整累积」，因此覆盖写入。
     * 与 Web 端 `reasoning` 事件 `rd.textContent = data.text` 口径一致。
     */
    private fun setReasoning(text: String) {
        synchronized(_messages) {
            val last = _messages.lastOrNull { it.role == "assistant" } ?: return
            last.reasoning = text.takeLast(8000)
        }
    }

    /** 解析 sources 事件的 items；按 url 去重、最多 12 条（与 Web 一致） */
    private fun parseSourceItems(items: org.json.JSONArray?): List<Pair<String, String>> {
        if (items == null || items.length() == 0) return emptyList()
        val seen = HashSet<String>()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank() || !seen.add(url)) continue
            val title = o.optString("title").ifBlank { url }
            list.add(title to url)
            if (list.size >= 12) break
        }
        return list
    }

    /** 联网搜索来源：后端一次性下发 {items:[{title,url}]} */
    private fun setSources(items: org.json.JSONArray?) {
        val list = parseSourceItems(items)
        if (list.isEmpty()) return
        synchronized(_messages) {
            val last = _messages.lastOrNull { it.role == "assistant" } ?: return
            last.sources = list
        }
    }

    /**
     * /api/context 一次拉取同时还原：每轮联网搜索来源（按 turnId 索引）与会话累计用量。
     *
     * 与 Web 端 renderSessionHistory / loadSessionHistory 完全同源：Web 端历史回放不走
     * /api/history，而是读审查记录 turn.events 里的 sources 事件和 turn.usage。Web 端
     * 发起的对话，启动器从未收到过那条 SSE，只有 /api/context 一定带 —— 所以这里按
     * turnId 还原来源，并按 turn.usage 累加会话 token，是跨端最可靠的权威路径。
     */
    private data class ContextSnapshot(
        val sources: Map<String, List<Pair<String, String>>> = emptyMap(),
        val input: Long = 0L,
        val cached: Long = 0L,
        val output: Long = 0L,
        val turns: Int = 0,
    )

    private suspend fun loadContextData(sid: String): ContextSnapshot {
        val r = request("/api/context?s=" + enc(sid), "GET", null) ?: return ContextSnapshot()
        val turns = r.optJSONArray("turns") ?: return ContextSnapshot()
        val out = mutableMapOf<String, List<Pair<String, String>>>()
        var inputSum = 0L
        var cachedSum = 0L
        var outputSum = 0L
        var turnCount = 0
        for (i in 0 until turns.length()) {
            val t = turns.optJSONObject(i) ?: continue
            // 会话累计：turn.usage 由后端 parse_usage 归一化，字段名与 Web 端一致
            turnCount++
            t.optJSONObject("usage")?.let { u ->
                fun pick(vararg keys: String): Long {
                    for (k in keys) {
                        val v = u.opt(k)
                        if (v is Number) return v.toLong()
                        if (v is String) v.toLongOrNull()?.let { return it }
                    }
                    return 0
                }
                inputSum += pick("input", "input_tokens", "prompt_tokens")
                cachedSum += pick("cached", "cached_input_tokens", "cached_tokens", "cache_read_input_tokens")
                outputSum += pick("output", "output_tokens", "completion_tokens")
            }
            val tid = t.optString("id")
            if (tid.isBlank()) continue
            val events = t.optJSONArray("events") ?: continue
            var items: List<Pair<String, String>> = emptyList()
            // 新版：结构化 sources {items:[{title,url}]}（Web 端现用）
            for (j in 0 until events.length()) {
                val e = events.optJSONObject(j) ?: continue
                if (e.optString("event") != "sources") continue
                items = parseSourceItems(e.optJSONObject("data")?.optJSONArray("items"))
                if (items.isNotEmpty()) break
            }
            // 旧记录：tool("联网搜索来源", "标题 — URL\n...")
            if (items.isEmpty()) {
                for (j in 0 until events.length()) {
                    val e = events.optJSONObject(j) ?: continue
                    if (e.optString("event") != "tool") continue
                    val d = e.optJSONObject("data") ?: continue
                    if (d.optString("tool") != "联网搜索来源") continue
                    val parsed = d.optString("status").split("\n").mapNotNull { line ->
                        val parts = line.split(" — ")
                        if (parts.size < 2) return@mapNotNull null
                        val url = parts.last().trim()
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            return@mapNotNull null
                        }
                        val title = parts.dropLast(1).joinToString(" — ").trim().ifBlank { url }
                        title to url
                    }
                    if (parsed.isNotEmpty()) { items = parsed; break }
                }
            }
            if (items.isNotEmpty()) out[tid] = items
        }
        return ContextSnapshot(out, inputSum, cachedSum, outputSum, turnCount)
    }

    /**
     * SSE 运行期错误：像 Web 端一样在对话流里插入一条独立的 ⚠️ 助手气泡，
     * 而不是只写进诊断日志——用户要能在对话里直接看到失败反馈。
     * 去重：同一条回复里同一错误只插一次，避免重连 chatter 刷屏。
     */
    private fun appendErrorBubble(msg: String) {
        synchronized(_messages) {
            val last = _messages.lastOrNull { it.role == "assistant" }
            if (last != null && last.text.startsWith("⚠️") && last.text.contains(msg)) return@synchronized
            // 标记 failed 才能在随后 400ms 的 refreshHistory 里被保留（合并逻辑只留 failed 本地气泡）；
            // 带上前一条用户原文，让气泡的「重试」按钮可一键重发。
            val userText = _messages.take(maxOf(0, _messages.size - 1))
                .lastOrNull { it.role == "user" }?.text?.trim().orEmpty()
            _messages.add(ChatMessage("assistant", "⚠️ $msg", failed = true, retryText = userText))
        }
        syncSignatureFromLocal()
        notifyListeners()
    }

    private fun finishFromResult(res: JSONObject?) {
        running = false
        // 本轮最终用量并入会话累计；end 重放时 runUsageCommitted 已置位，不会重复记账
        commitRunUsage()
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
        if (err.isNotBlank()) {
            appendLog("[结束] " + err.take(500))
            // 有些错误只在 end 的 error 字段里给（未单独发 error 事件），对话里也要有反馈
            appendErrorBubble(err.take(500))
        }
        step = ""
        // 本地流已完整播报，标记为已同步，轮询不会再重建消息列表
        syncSignatureFromLocal()
        // 自动朗读的唯一触发点。关键：后端把 end 写进可重放事件列表，SSE 重连会再收到
        // 一次 end，导致本函数对同一 job 被调用两次。这里用「令牌一次性消费」兜住：
        // 先取出并清空 pendingSpeakJob，只有非 null 才朗读，第二次调用令牌已空直接跳过。
        // 令牌只在 sendTask（AI 窗真实发送）里登记，Web 对话拿不到，天然不朗读。
        val speakNow = synchronized(this) {
            val j = pendingSpeakJob
            pendingSpeakJob = null
            j != null && exit == 0 && !hadError && !stopped
        }
        if (speakNow) {
            synchronized(_messages) {
                _messages.lastOrNull { it.role == "assistant" && !it.failed && it.text.isNotBlank() }?.text
            }?.let { finalText ->
                localReplyListener?.let { runCatching { it(finalText) } }
            }
        }
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
