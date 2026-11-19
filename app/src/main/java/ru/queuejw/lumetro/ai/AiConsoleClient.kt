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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本地 AI Agent 的共享客户端：负责 HTTP 通信、轮询与状态缓存。
 * AiConsoleActivity 与九键面板共用同一份状态。
 */
object AiConsoleClient {

    private const val TAG = "AiConsole"
    private const val PORT = 8080
    private val DEFAULT_URLS = listOf(
        "http://127.0.0.1:$PORT",
        "http://192.168.31.25:$PORT"
    )
    private const val PREFS_NAME = "ai_console"
    private const val KEY_SERVER = "server_url"
    private const val POLL_INTERVAL_MS = 1000L
    private const val MAX_POLL_FAILURES = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appContext: Context? = null

    @Volatile
    private var configuredUrl: String? = null

    @Volatile
    private var activeUrl: String? = null

    @Volatile
    private var pollJob: Job? = null

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

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        configuredUrl = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER, null)
    }

    fun currentInputUrl(): String = configuredUrl ?: DEFAULT_URLS.first()

    fun serverLabel(): String = configuredUrl ?: activeUrl ?: DEFAULT_URLS.first()

    fun setServer(context: Context, raw: String): String? {
        val normalized = normalize(raw) ?: return null
        init(context)
        configuredUrl = normalized
        activeUrl = null
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SERVER, normalized)?.apply()
        return normalized
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun sendTask(text: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            val response = request("/api/run", "POST", JSONObject().put("text", text).toString())
            val status = response?.optString("status")
            when {
                status == "ok" || (response != null && status.isNullOrEmpty()) -> {
                    running = true
                    statusText = "AI 运行中"
                    notifyListeners()
                    startPolling()
                    onResult(true, "任务已启动")
                }
                status == "error" -> {
                    val msg = response?.optString("msg").orEmpty().ifBlank { "任务启动失败" }
                    onResult(false, msg)
                }
                else -> onResult(false, lastError ?: "无法连接本地 AI 服务")
            }
        }
    }

    fun checkServer(onResult: (Boolean, String) -> Unit) {
        scope.launch {
            val status = request("/api/status", "GET", null)
            if (status == null) {
                onResult(false, lastError ?: "连接失败")
            } else {
                onResult(true, if (status.optBoolean("running")) "AI 运行中" else "AI 空闲")
            }
        }
    }

    fun refreshReport() {
        scope.launch {
            val report = request("/api/report", "GET", null) ?: return@launch
            val newSummary = report.optString("summary")
            if (newSummary.isBlank()) return@launch
            summary = newSummary
            summaryTime = report.optString("time")
            notifyListeners()
        }
    }

    fun startPollingIfRunning() {
        scope.launch {
            val status = request("/api/status", "GET", null) ?: return@launch
            if (status.optBoolean("running")) {
                applyStatus(status)
                startPolling()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var failures = 0
            while (isActive) {
                val status = request("/api/status", "GET", null)
                if (status == null) {
                    failures++
                    if (failures >= MAX_POLL_FAILURES) {
                        running = false
                        statusText = "与本地服务断开"
                        notifyListeners()
                        break
                    }
                } else {
                    failures = 0
                    applyStatus(status)
                    if (!running) {
                        refreshReport()
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun applyStatus(status: JSONObject) {
        running = status.optBoolean("running")
        step = status.optString("current_step")
        logText = status.optString("log")
        statusText = if (running) "AI 运行中" else "AI 空闲"
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private suspend fun request(path: String, method: String, body: String?): JSONObject? =
        withContext(Dispatchers.IO) {
            val candidates = configuredUrl?.let { listOf(it) }
                ?: (activeUrl?.let { listOf(it) } ?: DEFAULT_URLS)
            for (base in candidates) {
                val result = executeRequest(base, path, method, body)
                if (result != null) {
                    activeUrl = base
                    return@withContext result
                }
            }
            null
        }

    private fun executeRequest(baseUrl: String, path: String, method: String, body: String?): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(baseUrl + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 2000
                readTimeout = 3000
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
                Log.w(TAG, "$method $baseUrl$path -> HTTP $code: $text")
                return null
            }
            if (text.isBlank()) return JSONObject()
            try {
                JSONObject(text)
            } catch (e: Exception) {
                lastError = "响应不是合法 JSON：${text.take(300)}"
                Log.e(TAG, "$method $baseUrl$path -> parse failed: $text", e)
                null
            }
        } catch (e: Exception) {
            lastError = "$baseUrl  ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "request failed: $method $baseUrl$path", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 统一成 scheme://host:port，丢掉多余的空格、路径和末尾斜杠 */
    private fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return try {
            val uri = android.net.Uri.parse(value)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "http"
            val port = if (uri.port > 0) uri.port else 80
            "$scheme://$host:$port"
        } catch (e: Exception) {
            null
        }
    }
}
