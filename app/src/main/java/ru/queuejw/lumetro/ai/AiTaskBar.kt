package ru.queuejw.lumetro.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.math.roundToInt
import ru.queuejw.lumetro.components.freeform.StyleImageStore
import ru.queuejw.lumetro.components.freeform.WorkbenchStyle

/**
 * 九键上方的一行 AI 状态条：状态点 + 循环轮播文字。
 * 轮播内容依次为：AI 运行状态 / 错误信息（异常时）/ 系统提示 / 一言语录。
 * 点击整条展开会话浮层；输入与发送统一在会话页完成。
 */
class AiTaskBar(
    context: Context,
    private val style: WorkbenchStyle,
    private val onExpand: () -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onSendText: (String) -> Unit,
) : LinearLayout(context) {

    private val dot = View(context)
    private val preview = TextView(context)
    private val scroller: HorizontalScrollView

    // ---- 轮播 ----
    data class RotItem(val text: String, val isQuote: Boolean = false)

    private val handler = Handler(Looper.getMainLooper())
    private var rotateIndex = 0
    private var quoteText: String = DEFAULT_QUOTE // 一言语录，后台定时更新
    private var currentDelayMs = BASE_ROTATE_MS
    private val quoteRefreshMs = 5 * 60 * 1000L
    private val rotateRunnable = object : Runnable {
        override fun run() {
            rotateIndex++
            currentDelayMs = renderStatus()
            handler.postDelayed(this, currentDelayMs)
        }
    }

    /** 停留时长按文字长度自适应：字越多看得越久；一言额外给 3 倍 */
    private fun durationFor(item: RotItem): Long {
        val byLen = (BASE_ROTATE_MS + item.text.length * 90L)
            .coerceIn(BASE_ROTATE_MS, MAX_ROTATE_MS)
        return if (item.isQuote) (byLen * 3).coerceAtMost(MAX_QUOTE_MS) else byLen
    }
    private val quoteRunnable = object : Runnable {
        override fun run() {
            fetchQuote()
            handler.postDelayed(this, quoteRefreshMs)
        }
    }
    private val renderListener: () -> Unit = { renderStatus() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = StyleImageStore.background(context, StyleImageStore.AI_BAR, 100)
            ?: android.graphics.drawable.ColorDrawable(WorkbenchStyle.colorOf(style.aiBarColor))
        setPadding(dp(8), dp(4), dp(8), dp(4))
        minimumHeight = dp(38)
        isClickable = true
        setOnClickListener { onExpand() }

        dot.layoutParams = LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        addView(dot)

        // 文字用 HorizontalScrollView 包裹：过长可左右滑动查看，点击仍展开会话
        preview.apply {
            text = "点此进入 AI 对话"
            textSize = 12f
            setTextColor(Color.WHITE)
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onExpand() }
        }
        scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(preview, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            // 点右侧空白区也要能进入会话（否则只有点中文字才响应）
            isClickable = true
            setOnClickListener { onExpand() }
        }
        addView(scroller)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AiConsoleClient.init(context)
        AiConsoleClient.addListener(renderListener)
        currentDelayMs = renderStatus()
        handler.postDelayed(rotateRunnable, currentDelayMs)
        handler.postDelayed(quoteRunnable, 0) // 立即拉一次语录
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(rotateRunnable)
        handler.removeCallbacks(quoteRunnable)
        AiConsoleClient.removeListener(renderListener)
        super.onDetachedFromWindow()
    }

    /** 轮播内容：状态 / 错误（异常时）/ 系统提示 / 语录；空项自动跳过 */
    private fun rotatingItems(): List<RotItem> {
        val items = mutableListOf<RotItem>()
        val running = AiConsoleClient.running
        val error = AiConsoleClient.lastError
        val step = AiConsoleClient.step
        val last = AiConsoleClient.messages.lastOrNull { it.text.isNotBlank() }

        // 1) AI 运行状态
        items += RotItem(when {
            running -> "⟳ AI 运行中" + (if (step.isNotBlank()) " · ${step.replace('\n', ' ')}" else "…")
            !error.isNullOrBlank() -> "AI 空闲"
            else -> "AI 就绪 · 点击展开会话"
        })

        // 2) 错误信息（仅在有错时加入）
        if (!error.isNullOrBlank()) items += RotItem("⚠ 错误：${error.replace('\n', ' ')}")

        // 3) 系统提示（轮换）
        items += RotItem(SYSTEM_TIPS[rotateIndex % SYSTEM_TIPS.size])

        // 4) 一言语录（停留时长 3 倍）
        items += RotItem("❝ $quoteText", isQuote = true)

        // 5) 最后一条对话（作为补充，放最后）
        if (last != null && !running && error.isNullOrBlank()) {
            items += RotItem((if (last.role == "user") "我：" else "AI：") + last.text.replace('\n', ' '))
        }
        return items
    }

    /** 渲染当前项并返回它应停留的时长；状态变化触发时保持当前项不跳 */
    private fun renderStatus(): Long {
        val items = rotatingItems()
        var delay = currentDelayMs
        if (items.isNotEmpty()) {
            val idx = rotateIndex % items.size
            val item = items[idx]
            preview.text = item.text
            delay = durationFor(item)
        }
        scroller.post { scroller.fullScroll(Gravity.END) }

        val error = AiConsoleClient.lastError
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    AiConsoleClient.running -> Color.parseColor("#FF51C878")
                    !error.isNullOrBlank() -> Color.parseColor("#FFE06565")
                    else -> Color.parseColor("#FF7A7A7A")
                }
            )
        }
        return delay
    }

    /** 后台拉取一言（v1.hitokoto.cn 国内可达），失败保留旧值/内置兜底 */
    private fun fetchQuote() {
        Thread {
            val text = try {
                val conn = (URL(QUOTE_URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "GET"
                }
                try {
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val obj = JSONObject(body)
                        val q = obj.optString("hitokoto").trim()
                        val from = obj.optString("from").trim()
                        val who = obj.optString("from_who").trim()
                        if (q.isNotBlank()) {
                            buildString {
                                append(q)
                                val src = listOf(who, from).filter { it.isNotBlank() }
                                if (src.isNotEmpty()) append("  —— ").append(src.joinToString("·"))
                            }
                        } else null
                    } else null
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                null
            }
            if (!text.isNullOrBlank()) {
                quoteText = if (text.length > 90) text.take(90) + "…" else text
                handler.post { renderStatus() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 兼容旧调用：设置预览文字 */
    fun setText(text: String) {
        if (text.isNotEmpty()) preview.text = text
    }

    /** 兼容旧调用：本条已不是输入框，无 IME 焦点可释放 */
    fun releaseFocus() {}

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val QUOTE_URL = "https://v1.hitokoto.cn/?encode=json&c=d"
        private const val DEFAULT_QUOTE = "心有猛虎，细嗅蔷薇。"
        private const val BASE_ROTATE_MS = 4000L   // 短项基础停留
        private const val MAX_ROTATE_MS = 9000L    // 普通项最长停留
        private const val MAX_QUOTE_MS = 27000L    // 一言 3 倍，封顶
        private val SYSTEM_TIPS = listOf(
            "提示：点开会话可直接输入并发送",
            "提示：点击气泡可复制/收藏/朗读",
            "提示：工具箱可管理会话与模式",
            "提示：会话搜索支持全局正文检索",
        )
    }
}
