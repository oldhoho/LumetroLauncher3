package ru.queuejw.lumetro.ai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Base64
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 覆盖在九键面板之上的 AI 会话浮层。
 * 宿主把它作为最外层 FrameLayout 的最后一个子 View，调用 [show]/[hide] 切换。
 */
class AiChatOverlay(
    context: Context,
    private val onClose: (() -> Unit)? = null,
    /** 悬浮窗模式下需要本类动态管理窗口焦点/IME；Activity 模式由系统窗口管理，传 false */
    private val manageWindow: Boolean = true,
    /** 由宿主 Activity 注册文件选择器，避免 View 内部拿不到 onActivityResult */
    private val onPickFile: (() -> Unit)? = null,
) : FrameLayout(context) {

    private val headerTitle: TextView
    private val headerSub: TextView
    /** 标题栏第三行：当前会话累计 token / 命中率 / 轮数（比单轮 usage 行更小） */
    private val headerSubTotal: TextView
    /** 当前已显示标题对应的会话 id；变化时异步拉取会话标题回填，避免每个 token 都请求 */
    private var headerSessionId: String? = null
    /** 运行结束时后端 auto_title 会改会话标题，需要强制刷新一次 */
    private var headerWasRunning = false
    private lateinit var diagView: TextView
    private val closeBtn: TextView
    private val chatScroll: ScrollView
    private val chatList: LinearLayout
    private val inputBox: EditText
    private val sendBtn: TextView
    private val menuBtn: TextView
    /** 顶栏右上角的快速开关图标：联网搜索 / 深度思考 */
    private lateinit var quickSearchBtn: TextView
    private lateinit var quickThinkBtn: TextView
    private val settingsPanel: LinearLayout

    /** 侧边抽屉：对齐 Web 端窄屏的 off-canvas 侧栏，入口全部从这里进入 */
    private lateinit var drawer: LinearLayout
    private lateinit var drawerMask: View
    private lateinit var drawerSessionBox: LinearLayout
    /** 抽屉顶部内嵌的全局搜索框：输入即时查后端，结果直接替换下面的会话区 */
    private lateinit var drawerSearchInput: EditText
    private val drawerSearchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var drawerSearchRunnable: Runnable? = null
    private var drawerSearchSeq = 0
    /** 抽屉系统组里的自动朗读开关（settingsPanel 移除后的唯一入口） */
    private lateinit var drawerAutoItem: TextView
    /** 抽屉底部的账户余额行（DeepSeek /api/balance，走后端代理，刷新时更新） */
    private lateinit var drawerBalanceItem: TextView
    private var balanceBusy = false
    private var drawerOpen = false
    private var drawerWidth = 0

    /** 输入区上方的模式条，对齐 Web 端 .modeSeg */
    private lateinit var modeStrip: LinearLayout
    private val modeStripButtons = mutableListOf<TextView>()
    /** 设置面板改为对齐 Web 端的功能按钮 */
    private lateinit var featAuto: TextView
    private lateinit var featSearch: TextView
    private lateinit var featThink: TextView
    private lateinit var featMode: TextView
    private lateinit var featAttach: TextView
    private lateinit var featureView: TextView

    /** 自动朗读：仅朗读「本端提问完成时」的回复，开关状态存本机 */
    private var autoSpeak = false
    private var autoTts: TextToSpeech? = null
    private var pendingAutoText: String? = null
    // 朗读只有唯一入口（AiConsoleClient.finishFromResult → localReplyListener），
    // 调用方已经保证「一条本端回复只回调一次」，不再需要任何正文指纹去重。
    // Web 端对话、历史刷新、切会话、回前台都不朗读。

    /**
     * 气泡正文：把 Markdown 标记渲染成富文本样式，用户消息保持原文（避免把用户
     * 输入的 `**` 误当格式）。空占位维持原有行为。
     */
    private fun renderBubbleText(text: String, role: String): CharSequence =
        if (text.isBlank()) (if (role == "user") "" else "…")
        else if (role == "user") text
        else MarkdownText.render(text)

    /**
     * 手指正按在气泡上。气泡要可长按选字/复制，必须保持 focusable；
     * 但 ACTION_DOWN 焦点转移会让输入框失焦，若此刻收键盘会触发布局跳变、
     * 导致 ACTION_UP 点不中（"要点两下"）。因此触摸期间暂不收键盘，
     * 由气泡点击里的 dismissKeyboard 在 UP 之后统一收起。
     */
    private var bubbleTouching = false

    private var messagesSnapshot: List<ChatMessage> = emptyList()
    /** 当前已渲染的会话 id，切换会话时强制整表重建 */
    private var renderedSid: String? = null

    /** 点击气泡后内嵌在气泡下方的操作卡（跟随会话列表滚动，重绘时按 index 恢复） */
    private var inlineActionView: View? = null
    private var pendingAction: Pair<Int, ChatMessage>? = null
    /** 复用同一个 sheet 实例，保持「朗读中/已收藏」状态不被流式重绘重置 */
    private var actionSheet: MessageActionSheet? = null
    /** 当前操作卡绑定的消息（index+text）；换消息时必须换绑定，否则操作会打到旧消息上 */
    private var actionSheetKey: Pair<Int, String>? = null

    /**
     * 气泡正文：短按弹操作卡、长按选字，两者都在这里协调，且绝不 setOnTouchListener。
     *
     * 为什么不用 OnClickListener：setTextIsSelectable(true) 让 TextView 在 touch mode
     * 下可聚焦，第一次点击会被系统消费成「获取焦点」，click 根本不触发 —— 表现为
     * 「点一下没反应，点第二下才弹卡」。
     * 为什么不用 setOnTouchListener：它会覆盖 TextView 内部负责长按选字/复制菜单的
     * 监听器 —— 表现为「长按失效」。
     * dispatchTouchEvent 在内部处理之前，只观察、返回 super 不消费事件：短按自己判定，
     * 长按/滑动照常交给 TextView 和 ScrollView。
     */
    private class BubbleBody(context: android.content.Context) : TextView(context) {
        var onBubbleTouch: ((Boolean) -> Unit)? = null
        var onSingleTap: (() -> Unit)? = null

        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longPressFired = false
        private val longPressCheck = Runnable { longPressFired = true }
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onBubbleTouch?.invoke(true)
                    downX = ev.x
                    downY = ev.y
                    moved = false
                    longPressFired = false
                    removeCallbacks(longPressCheck)
                    postDelayed(longPressCheck,
                        android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(ev.x - downX) > touchSlop ||
                        kotlin.math.abs(ev.y - downY) > touchSlop) {
                        moved = true
                        removeCallbacks(longPressCheck)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    onBubbleTouch?.invoke(false)
                    removeCallbacks(longPressCheck)
                    // 短按且未滑动、未触发长按才弹卡；长按已交给系统选字，不重复弹。
                    if (!moved && !longPressFired) onSingleTap?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                    onBubbleTouch?.invoke(false)
                    removeCallbacks(longPressCheck)
                }
            }
            return super.dispatchTouchEvent(ev) // 不消费：系统长按选字/复制照常
        }
    }

    /** 单个气泡 View 的引用与已渲染内容，用于流式增量局部更新 */
    private class BubbleRef(
        val wrap: View,
        val body: TextView,
        val det: TextView?,
        val proc: TextView?,
        val reasonDet: TextView?,
        val reasonBody: TextView?,
        var structKey: String,
        var text: String,
        var process: String,
        var reasoning: String,
    )
    /** 按消息索引缓存气泡，避免每个 token 重建整表（重建会让滚动位置乱跳） */
    private val bubbleRefs = mutableListOf<BubbleRef>()
    /** 发消息/切换会话后强制滚到底；普通流式增量则尊重用户当前滚动位置 */
    private var forceScrollNext = true
    /** 从后台返回时强制重建气泡，避免后台历史变化后旧索引/旧引用残留 */
    private var forceRebuildNext = false

    /**
     * 唯一朗读回调：本端提问的回复完成时由 AiConsoleClient.finishFromResult 触发。
     * 只在「用户确实从 AI 窗发出提问」时读一次；Web 端对话无论前后台都不朗读。
     * finishFromResult 运行在非主线程流协程，这里 post 回主线程再 speak。
     */
    private val localReplyListener: (String) -> Unit = { text ->
        post {
            if (autoSpeak && text.isNotBlank()) speakAuto(text)
        }
    }

    /** 跨端配置变化（工作模式名/预设）后刷新功能面板，不触发消息列表重建 */
    private val configChangeListener: () -> Unit = {
        post {
            if (isAttachedToWindow && ::featMode.isInitialized) refreshFeatures()
            if (::modeStrip.isInitialized) refreshModeStrip()
        }
    }

    /** 流式跟随节流：文字流很快，不逐 token 跳到底，按固定间隔平滑滚动，允许文字领先 */
    private var lastAutoScrollMs = 0L
    private val autoScrollIntervalMs = 400L
    /** 粘性跟随意图：用户贴底时置 true，主动上翻（触摸）时清 false，避免平滑动画途中误停 */
    private var followIntent = true

    /**
     * 流式期间的跟随滚动：限速、平滑。
     * - 用户本来就贴底时才跟随；手动上翻查看历史时完全不打扰。
     * - 每 [autoScrollIntervalMs] 毫秒才滚动一次，平滑滚到当前内容底部；
     *   文字继续增长时会暂时领先于视口，正是「不紧跟、看得清」的效果。
     * - 流式结束后由整表重建分支用 fullScroll 一次性对齐最终底部。
     */
    /**
     * 纯滚动到底部，绝不调用 fullScroll(FOCUS_DOWN)。
     * fullScroll 会把焦点转给滚区内第一个可聚焦的子 View——气泡是 selectable/focusable，
     * 于是输入框失焦 → OnFocusChangeListener 收键盘。贴底时弹键盘恰好走这条路径，
     * 表现为「一弹键盘就被强制关掉」。scrollTo 只改 scrollY，不碰焦点。
     */
    private fun scrollToBottomNoFocus() {
        chatScroll.post {
            val maxScroll = (chatList.height - chatScroll.height +
                chatScroll.paddingTop + chatScroll.paddingBottom).coerceAtLeast(0)
            chatScroll.scrollTo(chatScroll.scrollX, maxScroll)
        }
    }

    private fun followStreamScroll() {
        val now = System.currentTimeMillis()
        if (now - lastAutoScrollMs < autoScrollIntervalMs) return
        lastAutoScrollMs = now
        chatScroll.post {
            // post 是异步的：从入队到执行期间用户可能已上翻。这里必须再校验一次，
            // 否则平滑动画会在用户上翻后仍把视图拽回底部（"无法打断"的根因）。
            if (!followIntent) return@post
            val maxScroll = (chatList.height - chatScroll.height +
                chatScroll.paddingTop + chatScroll.paddingBottom).coerceAtLeast(0)
            // 微小差值不滚，避免逐 token 的抖动式跟随
            if (maxScroll - chatScroll.scrollY > dp(2)) {
                chatScroll.smoothScrollTo(0, maxScroll)
            }
        }
    }

    private val renderListener: () -> Unit = { render() }

    // ---- 独立悬浮窗 / IME 位置管理 ----
    private var windowManager: WindowManager? = null
    /** 本浮层是否已作为独立窗口添加到 WindowManager */
    private var windowAdded = false
    /** 保存独立窗口的初始全屏高度（像素），键盘收起时还原 */
    private var fullWindowHeight = 0
    private var imeWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null
    /** 当前已经应用到窗口上的键盘高度（像素），0 表示未上移 */
    private var appliedImeHeight = 0

    /**
     * Activity 模式（adjustResize）下的键盘跟随：窗口高度会由系统压缩，输入行随之贴到
     * 键盘上沿，但 ScrollView 的 scrollY 不会自动重新贴底 —— 键盘弹出可见区变小后，
     * 末尾几条消息被挤到视口外，看起来「对话没跟随上升」。这里监听可见高度变化，
     * 在用户本来就贴底（followIntent）时重新滚到底，键盘收起时同理恢复。
     * 悬浮窗模式有 imeWatcher 自行压缩窗口，不需要这个监听。
     */
    private var imeScrollWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastVisibleHeight = 0

    init {
        isClickable = true
        setBackgroundColor(Color.parseColor("#F21A1A1A"))
        visibility = GONE
        autoSpeak = context.getSharedPreferences("ai_console", Context.MODE_PRIVATE)
            .getBoolean("auto_speak", false)
        // Activity 模式由本层负责朗读；旧悬浮窗模式不注册，避免两个 overlay 重复播报
        if (!manageWindow) AiConsoleClient.addLocalReplyListener(localReplyListener)
        // 工作模式/预设在 Web 端改动后，服务端配置轮询会触发这里，功能面板即时刷新
        AiConsoleClient.addConfigListener(configChangeListener)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#F21A1A1A"))
        }

        // ---- header ----
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#FF242424"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        headerTitle = TextView(context).apply {
            text = "会话标题"
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 副标题改为实时 token 计数（本轮输入/输出/命中缓存），不再是 http 地址
        headerSub = TextView(context).apply {
            text = "token · 等待输入"
            textSize = 11f
            setTextColor(Color.parseColor("#FF9EFFB0"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 会话累计行：单轮 usage 下方补一行，空间紧张就压小字号，颜色略暗以区分层级
        headerSubTotal = TextView(context).apply {
            text = "会话累计 · 等待数据"
            textSize = 9.5f
            setTextColor(Color.parseColor("#FFA0B8C8"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 头部操作按钮统一做成圆角描边 chip，贴合深色 UI
        fun headerChip(label: String, fg: String, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor(fg))
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FF303030"))
                    setStroke(dp(1), Color.parseColor("#FF454545"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(4) }
                setOnClickListener {
                    dismissKeyboard() // 头部操作：非输入，先让输入框失焦收键盘
                    onClick()
                }
            }

        // 顺序：汉堡(最左) → 标题 → 联网/思考图标(右上) → 收起(最右)
        // 汉堡去掉 chip 默认 leftMargin 贴左，但左右内边距保持对称，
        // 让三条杠在按钮内居中，不会贴着按钮左边缘。
        menuBtn = headerChip("☰", "#FF9EFFB0") { openDrawer() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = 0
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        header.addView(menuBtn)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 与汉堡之间留合理间距，避免标题/计数器贴着图标
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10) }
        }
        titleCol.addView(headerTitle)
        titleCol.addView(headerSub)
        titleCol.addView(headerSubTotal)
        header.addView(titleCol)

        quickSearchBtn = headerChip("🌐", "#FF9AA5A0") { toggleQuick("search") }
        header.addView(quickSearchBtn)
        quickThinkBtn = headerChip("🧠", "#FF9AA5A0") { toggleQuick("think") }
        header.addView(quickThinkBtn)

        closeBtn = headerChip("收起 ⌄", "#FFCCCCCC") {
            if (onClose != null) onClose.invoke() else hide()
        }
        header.addView(closeBtn)
        root.addView(header)

        // ---- 诊断栏：当前会话 / 消息数 / 服务器，便于确认为哪个会话、是否连上 ----
        diagView = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#99AACCFF"))
            setPadding(dp(14), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#FF1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(diagView)

        // ---- 功能面板：对齐 Web 端已有能力，服务器配置仍留在工具箱 ----
        settingsPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#FF232323"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun featureButton(): TextView = TextView(context).apply {
            textSize = 11.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#FF343434"))
                setStroke(dp(1), Color.parseColor("#FF4A4A4A"))
            }
        }
        fun featureRow(vararg cells: TextView, topMargin: Int = 6): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { this.topMargin = dp(topMargin) }
                cells.forEachIndexed { i, cell ->
                    addView(cell, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        if (i > 0) leftMargin = dp(8)
                    })
                }
            }

        featAuto = featureButton().apply { setOnClickListener { toggleAutoSpeak() } }
        // 设置面板 = 模式级默认（新会话与 Web 端都跟随）；顶栏 chip = 本会话临时覆盖。
        featSearch = featureButton().apply { setOnClickListener { setModeDefault("search") } }
        featThink = featureButton().apply { setOnClickListener { setModeDefault("think") } }
        featMode = featureButton().apply { setOnClickListener { showModePicker() } }
        featAttach = featureButton().apply {
            setOnClickListener {
                dismissKeyboard()
                if (onPickFile != null) onPickFile.invoke()
                else Toast.makeText(context, "请在会话页使用添加文件", Toast.LENGTH_SHORT).show()
            }
        }
        featureView = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#FF99BBAE"))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        settingsPanel.addView(featureRow(featAuto, featSearch, topMargin = 0))
        settingsPanel.addView(featureRow(featThink, featMode))
        settingsPanel.addView(featAttach, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(6) })
        settingsPanel.addView(featureView)
        // 快捷开关面板已移除：联网/思考在顶栏图标、模式在模式条、附件在输入行，
        // 自动朗读移到抽屉系统组。settingsPanel 保留为内存对象仅供 refreshFeatures
        // 复用状态文案，不再挂进界面树。

        // ---- chat list ----
        chatList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            // ScrollView 内的子 View 必须显式 MATCH_PARENT，否则气泡的 MATCH_PARENT
            // 会被测量成 0 宽，消息内容看不见。
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chatScroll = ScrollView(context).apply {
            // 竖直 LinearLayout 中 weight 作用于 height：宽必须是 MATCH_PARENT，
            // 原来写 (0, 0, 1f) 会把 width 当成字面量 0dp → 列表宽度为 0 → 看不到会话内容。
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
            // 用户一触摸列表就视为要自己查看，暂停流式自动跟随；
            // 松手后若正好贴底则恢复，保证继续跟着最新输出。
            var touchStartY = 0f
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 只暂停流式跟随，不在 DOWN 阶段改焦点/键盘：
                        // 键盘收起会立刻改变布局高度，导致 UP 时点击坐标漂移，气泡点不中。
                        followIntent = false
                        touchStartY = ev.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 真正拖动超过阈值时，取消正在进行的平滑滚动动画——
                        // smoothScrollTo 的动画不会因为 followIntent 变 false 而自动停，
                        // 必须 stopScroll() 才能让用户上翻立刻生效（"可打断"的关键）。
                        if (kotlin.math.abs(ev.y - touchStartY) > dp(8)) {
                            followIntent = false
                            // ScrollView 无 stopScroll()；scrollTo 到当前位置会
                            // 中止内部 OverScroller 的平滑动画，实现立刻打断。
                            chatScroll.scrollTo(chatScroll.scrollX, chatScroll.scrollY)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        followIntent = !v.canScrollVertically(1)
                    }
                }
                false // 不消费，气泡点击/长按仍正常
            }
            // 空白区域点击也收键盘；子 View（气泡/操作卡）自己处理点击，不触发这里，
            // 避免操作卡的行内编辑框刚唤起键盘又被父容器收掉。
            setOnClickListener { dismissKeyboard() }
            addView(chatList)
        }
        root.addView(chatScroll)

        // ---- input row ----
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(10))
            setBackgroundColor(Color.parseColor("#FF242424"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        inputBox = EditText(context).apply {
            hint = "输入消息…"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) showKeyboardForInput()
                else if (!bubbleTouching) {
                    // 失焦即收键盘：所有非输入操作只需让输入框失焦，统一在这里收起。
                    // 例外：焦点是被气泡长按/点击抢走时，先不收——否则 DOWN 阶段布局跳变
                    // 会让 UP 点不中气泡；由气泡自己在 UP 之后调 dismissKeyboard 收。
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                }
            }
            setOnEditorActionListener { _, actionId, event ->
                val send = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.action == KeyEvent.ACTION_DOWN &&
                        event.keyCode == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed)
                if (send) { submit(); true } else false
            }
        }
        inputRow.addView(inputBox)
        // 附件入口移到输入行，与 Web 端 .attachBtn 对齐，不必再展开功能面板
        val attachBtn = TextView(context).apply {
            text = "📎"
            textSize = 15f
            setTextColor(Color.parseColor("#FFB8C6D8"))
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            setOnClickListener {
                dismissKeyboard()
                if (onPickFile != null) onPickFile.invoke()
                else Toast.makeText(context, "请在会话页使用添加文件", Toast.LENGTH_SHORT).show()
            }
        }
        inputRow.addView(attachBtn)
        sendBtn = TextView(context).apply {
            text = "发送"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF3B7D6B"))
            }
            setOnClickListener { submit() }
        }
        inputRow.addView(sendBtn)

        // ---- 工作模式条：对齐 Web 端 .modeSeg，六个模式等宽平分可视宽度 ----
        // 不再横滑：每个按钮 weight=1f 平分整行，字号/内边距压小以容纳 4 字模式名 + 选中点
        modeStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(3), dp(6), dp(4))
            setBackgroundColor(Color.parseColor("#FF242424"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val modeKeys = AiConsoleClient.MODE_DEFAULTS.keys.toList()
        modeKeys.forEachIndexed { idx, key ->
            val btn = TextView(context).apply {
                textSize = 10f
                gravity = Gravity.CENTER
                minimumWidth = 0
                setPadding(dp(2), dp(5), dp(2), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = if (idx == 0) 0 else dp(3)
                    rightMargin = if (idx == modeKeys.lastIndex) 0 else dp(3)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FF303030"))
                    setStroke(dp(1), Color.parseColor("#FF454545"))
                }
                setOnClickListener {
                    dismissKeyboard()
                    if (AiConsoleClient.currentMode() == key) {
                        // 重复点当前模式不再弹选择列表：模式条本身就是选择器，
                        // 需要编辑配置走抽屉里的设置入口即可。
                        refreshModeStrip()
                    } else {
                        AiConsoleClient.setMode(key) { ok, msg ->
                            Toast.makeText(context, if (ok) "已切换：$msg" else msg, Toast.LENGTH_SHORT).show()
                            refreshModeStrip()
                            refreshFeatures()
                        }
                        refreshModeStrip()
                    }
                }
            }
            modeStripButtons.add(btn)
            modeStrip.addView(btn)
        }
        root.addView(modeStrip)
        root.addView(inputRow)

        // ---- 侧边抽屉：包在内容区之上，绝不放进 chatList（气泡索引依赖 child index）----
        val outer = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#F21A1A1A"))
            addView(root)
        }
        drawerMask = View(context).apply {
            visibility = GONE
            alpha = 0f
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { closeDrawer() }
        }
        outer.addView(drawerMask)
        drawerWidth = (resources.displayMetrics.widthPixels * 0.82f)
            .roundToInt().coerceAtMost(dp(272))
        drawer = buildDrawer()
        outer.addView(drawer)
        addView(outer)
        refreshFeatures()
        refreshModeStrip()
    }

    private fun submit() {
        val text = inputBox.text.toString().trim()
        val hasAttachments = AiConsoleClient.attachmentCount() > 0
        if (text.isEmpty() && !hasAttachments) return
        // 新消息发出后强制滚到底部，避免停留在上方看不到自己的发言
        forceScrollNext = true
        // 与 CodexWeb 前端一致：提交成功才清空输入框。
        // 失败时保留原文，用户不会因断线丢失正在输入的内容。
        AiConsoleClient.sendTask(text) { ok, msg ->
            if (ok) {
                if (text.isNotEmpty() && inputBox.text.toString().trim() == text) inputBox.text.clear()
                refreshFeatures()
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- 侧边抽屉：对齐 Web 端窄屏 off-canvas 侧栏 ----

    /** 构建抽屉 View。它是内容 root 的兄弟节点，不进入 chatList，气泡索引不受影响。 */
    private fun buildDrawer(): LinearLayout {
        val ctx = context
        val d = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            translationX = -drawerWidth.toFloat()
            setBackgroundColor(Color.parseColor("#FF1B1B1C"))
            elevation = 16f
            layoutParams = FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.START }
        }
        fun dText(text: String, size: Float, color: String) = TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
        }

        d.addView(dText("CODEX  ·  工作区", 15f, "#FFFFFFFF").apply {
            setPadding(dp(16), dp(16), dp(12), dp(8))
        })
        // 顶部两行各自独立成行、统一用 LinearLayout.LayoutParams，避免两个 view 视觉重叠
        // （之前的写法让「新建会话」紧贴搜索框，看起来像挤进了搜索栏）。
        fun topRow(label: String, size: Float, color: String, radius: Int, bg: String,
                   marginTop: Int, onClick: () -> Unit) =
            dText(label, size, color).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(radius).toFloat()
                    setColor(Color.parseColor(bg))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(marginTop)
                }
                setOnClickListener {
                    closeDrawer()
                    dismissKeyboard()
                    onClick()
                }
            }

        // 搜索真正内嵌进抽屉：这里是可输入的 EditText，命中结果直接替换下方会话区，
        // 不再只是点开对话框的"假搜索栏"。命中消息可点开并滚动定位到对话里的原消息。
        drawerSearchInput = EditText(ctx).apply {
            hint = "🔍  搜索会话 / 历史内容"
            setHintTextColor(Color.parseColor("#77FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#FF2A2A2C"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(2) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    drawerSearchRunnable?.let { drawerSearchHandler.removeCallbacks(it) }
                    if (q.isBlank()) {
                        drawerSearchRunnable = null
                        loadDrawerSessions()
                    } else {
                        val task = Runnable { searchDrawerSessions(q) }
                        drawerSearchRunnable = task
                        drawerSearchHandler.postDelayed(task, 300L)
                    }
                }
            })
        }
        d.addView(drawerSearchInput)
        d.addView(topRow("＋  新建会话", 12.5f, "#FFDCE6FF", 100, "#FF243055", 10) {
            AiConsoleClient.newSession { ok, msg ->
                Toast.makeText(ctx, if (ok) "已新建会话" else msg, Toast.LENGTH_SHORT).show()
                refreshModeStrip()
                refreshFeatures()
            }
        })

        val scroller = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(6) }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(14))
        }
        scroller.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.addView(scroller)

        fun section(title: String) {
            box.addView(dText(title, 10f, "#FF81858C").apply {
                setPadding(dp(8), dp(12), dp(8), dp(4))
            })
        }
        fun item(label: String, onClick: () -> Unit) {
            box.addView(dText(label, 12.5f, "#FFD6D9DE").apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(Color.parseColor("#FF242426"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                setOnClickListener {
                    closeDrawer()
                    dismissKeyboard()
                    onClick()
                }
            })
        }
        fun openHub(page: String? = null) {
            runCatching {
                val i = android.content.Intent(ctx, CodexHubActivity::class.java)
                if (page != null) i.putExtra(CodexHubActivity.EXTRA_PAGE, page)
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }.onFailure {
                Toast.makeText(ctx, "打开工具箱失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 会话区紧跟「新建会话」按钮：进入抽屉第一眼就是可切换的会话列表
        section("会话")
        drawerSessionBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(drawerSessionBox)
        box.addView(dText("与 CodexWeb 共用同一批会话", 10f, "#FF6E737A").apply {
            setPadding(dp(10), dp(8), dp(10), dp(4))
        })

        section("工作区")
        item("对话 · 当前") { }
        item("工具箱") { openHub() }
        item("文件管理") { openHub("文件") }
        section("工具箱")
        item("网络抓包") { openHub("抓包") }
        item("APK 工具") { openHub("APK") }
        item("技能") { openHub("技能") }
        item("手机控制") { openHub("手机") }
        item("提示词库") { openHub("提示词") }
        item("我的助手") { openHub("助手") }
        section("系统")
        item("会话管理") { openHub("会话") }
        drawerAutoItem = dText(if (autoSpeak) "🔊 自动朗读：开" else "🔊 自动朗读：关",
            12.5f, "#FFD6D9DE").apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#FF242426"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            setOnClickListener {
                closeDrawer()
                dismissKeyboard()
                toggleAutoSpeak()
            }
        }
        box.addView(drawerAutoItem)
        item("设置") { openHub("设置") }
        item("使用说明") { openHub("说明") }

        drawerBalanceItem = dText("💰 余额 … · 点击刷新", 11f, "#FFE0C27A").apply {
            setPadding(dp(14), dp(8), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#FF242426"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(8) }
            setOnClickListener { refreshBalance() }
        }
        d.addView(drawerBalanceItem)
        d.addView(dText("●  与 CodexWeb 实时同步", 10f, "#FF7DD4A8").apply {
            setPadding(dp(16), dp(10), dp(12), dp(14))
        })
        return d
    }

    /** 拉取账户余额并刷新抽屉底部的余额行；走后端 /api/balance，Key 不下发。 */
    private fun refreshBalance() {
        if (balanceBusy || !::drawerBalanceItem.isInitialized) return
        balanceBusy = true
        drawerBalanceItem.text = "💰 余额 查询中…"
        AiConsoleClient.api("GET", "/api/balance") { resp ->
            balanceBusy = false
            if (!::drawerBalanceItem.isInitialized) return@api
            try {
                if (resp == null) {
                    // 区分失败原因：旧后端没有 /api/balance 会返回 404，
                    // 这是「Web 看不到余额行 + AI 窗点重试」同时出现的根因。
                    // 把真实错误显示出来，避免用户只看到无意义的「点击重试」。
                    val raw = AiConsoleClient.apiError()
                    val hint = when {
                        raw.contains("404") -> "后端版本过低，请升级 CodexWeb"
                        raw.contains("401") -> "连接已过期，请重新打开 AI 窗"
                        raw.contains("Failed") || raw.contains("timeout", true) ||
                            raw.contains("connect", true) -> "无法连接后端"
                        else -> raw.take(18).ifBlank { "查询失败" }
                    }
                    drawerBalanceItem.text = "💰 余额 · $hint"
                    drawerBalanceItem.setTextColor(Color.parseColor("#FFE57373"))
                    return@api
                }
                val ok = resp.optBoolean("ok")
                if (!ok) {
                    val unsupported = resp.optBoolean("unsupported")
                    val err = resp.optString("error")
                    drawerBalanceItem.text = if (unsupported) "💰 余额 · 仅官方接口支持"
                    else "💰 余额 · ${err.take(20).ifBlank { "查询失败" }}"
                    drawerBalanceItem.setTextColor(Color.parseColor("#FFE57373"))
                    return@api
                }
                val items = resp.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    drawerBalanceItem.text = "💰 余额 — · 点击刷新"
                    return@api
                }
                val first = items.optJSONObject(0)
                val cur = first?.optString("currency") ?: "CNY"
                val total = first?.optString("total") ?: "0"
                val sym = if (cur == "USD") "$" else "¥"
                val amount = total.toDoubleOrNull()?.let { String.format("%.2f", it) } ?: total
                val avail = resp.optBoolean("available", true)
                val suffix = if (!avail) " · 余额不足" else " · 点击刷新"
                drawerBalanceItem.text = "💰 余额 $sym$amount${
                    if (cur == "USD") " USD" else ""
                }$suffix"
                drawerBalanceItem.setTextColor(Color.parseColor("#FFE0C27A"))
            } catch (_: Exception) {
                drawerBalanceItem.text = "💰 余额 — · 点击重试"
            }
        }
    }

    private fun openDrawer() {
        if (drawerOpen || !::drawer.isInitialized) return
        dismissKeyboard()
        drawerOpen = true
        drawer.visibility = VISIBLE
        drawerMask.visibility = VISIBLE
        drawer.animate().translationX(0f).setDuration(200).start()
        drawerMask.animate().alpha(1f).setDuration(200).start()
        val q = if (::drawerSearchInput.isInitialized) drawerSearchInput.text.toString().trim() else ""
        if (q.isBlank()) loadDrawerSessions() else searchDrawerSessions(q)
        refreshBalance()
    }

    private fun closeDrawer() {
        if (!drawerOpen || !::drawer.isInitialized) return
        drawerOpen = false
        drawer.animate().translationX(-drawerWidth.toFloat()).setDuration(180)
            .withEndAction { if (!drawerOpen) drawer.visibility = GONE }.start()
        drawerMask.animate().alpha(0f).setDuration(180)
            .withEndAction { if (!drawerOpen) drawerMask.visibility = GONE }.start()
    }

    /** 抽屉里的会话列表：置顶优先、最近在后，与 Web 侧栏顺序一致 */
    private fun loadDrawerSessions() {
        val seq = ++drawerSearchSeq // 让尚在飞行的搜索结果失效，避免清空后旧结果回灌
        AiConsoleClient.loadSessions { list ->
            post {
                if (seq != drawerSearchSeq || !::drawerSessionBox.isInitialized) return@post
                drawerSessionBox.removeAllViews()
                val cur = AiConsoleClient.currentSessionId()
                val rows = list.sortedWith(
                    compareByDescending<AiConsoleClient.SessionItem> { it.pinned }
                        .thenByDescending { it.mtime }
                ).take(16)
                if (rows.isEmpty()) {
                    drawerSessionBox.addView(TextView(context).apply {
                        text = "暂无会话"
                        textSize = 11f
                        setTextColor(Color.parseColor("#FF81858C"))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                    })
                }
                rows.forEach { s ->
                    val active = s.id == cur
                    drawerSessionBox.addView(TextView(context).apply {
                        text = (if (active) "● " else "○ ") + s.title.ifBlank { s.id.take(8) }
                        textSize = 12f
                        setTextColor(Color.parseColor(if (active) "#FF9FE1CB" else "#FFB6BAC0"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(Color.parseColor(if (active) "#FF143A31" else "#FF242426"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(2) }
                        setOnClickListener {
                            closeDrawer()
                            dismissKeyboard()
                            if (active) return@setOnClickListener
                            AiConsoleClient.switchSession(s.id, s.mode) { ok, msg ->
                                Toast.makeText(context, if (ok) "已切换会话" else msg,
                                    Toast.LENGTH_SHORT).show()
                                refreshModeStrip()
                                refreshFeatures()
                            }
                        }
                    })
                }
            }
        }
    }

    /**
     * 抽屉内嵌搜索：直接把后端全局搜索结果渲染进抽屉会话区，
     * 每条命中消息可点击 → 切会话 → 在对话里滚动定位并高亮（与会话对话框同一套逻辑）。
     */
    private fun searchDrawerSessions(q: String) {
        val seq = ++drawerSearchSeq
        AiConsoleClient.api("GET", "/api/sessions/search?q=${AiConsoleClient.urlEncode(q)}") { resp ->
            post {
                if (seq != drawerSearchSeq || !::drawerSessionBox.isInitialized) return@post
                drawerSessionBox.removeAllViews()
                val arr = resp?.optJSONArray("results") ?: org.json.JSONArray()
                if (arr.length() == 0) {
                    drawerSessionBox.addView(TextView(context).apply {
                        text = "没有匹配「$q」的会话"
                        textSize = 11f
                        setTextColor(Color.parseColor("#FF81858C"))
                        setPadding(dp(10), dp(14), dp(10), dp(14))
                    })
                    return@post
                }
                val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sid = o.optString("id")
                    val matched = o.optJSONArray("matched_in")?.let { ma ->
                        (0 until ma.length()).joinToString("、") { ma.optString(it) }
                    }.orEmpty()
                    val hits = mutableListOf<SearchHit>()
                    o.optJSONArray("hits")?.let { ha ->
                        for (k in 0 until ha.length()) {
                            val h = ha.optJSONObject(k) ?: continue
                            hits.add(SearchHit(h.optString("role"), h.optString("text"), terms))
                        }
                    }
                    val row = SessionRow(
                        id = sid, title = o.optString("title"), mode = o.optString("mode", "chat"),
                        mtime = o.optLong("mtime"), snippet = o.optString("snippet"),
                        matched = matched, hits = hits,
                    )
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        isClickable = true
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(9).toFloat()
                            setColor(Color.parseColor("#FF252528"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                        setOnClickListener {
                            closeDrawer()
                            dismissKeyboard()
                            if (sid == AiConsoleClient.currentSessionId()) return@setOnClickListener
                            AiConsoleClient.switchSession(sid, row.mode) { ok, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                if (ok) forceScrollNext = true
                            }
                        }
                    }
                    card.addView(TextView(context).apply {
                        val label0 = row.title.ifBlank { sid }
                        val n0 = if (hits.isNotEmpty()) "  [${hits.size} 条命中]" else ""
                        text = "○ $label0$n0"
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    if (matched.isNotBlank()) card.addView(TextView(context).apply {
                        text = "命中于：$matched"
                        textSize = 10f
                        setTextColor(Color.parseColor("#FF9EFFB0"))
                    })
                    val roleLabel = mapOf("user" to "我", "assistant" to "AI")
                    hits.forEach { hit ->
                        card.addView(TextView(context).apply {
                            text = "▸ ${roleLabel[hit.role] ?: hit.role}：${hit.text}"
                            textSize = 11f
                            setTextColor(Color.parseColor("#FFCCAA88"))
                            maxLines = 3
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(6).toFloat()
                                setColor(Color.parseColor("#FF1B1B1B"))
                            }
                            setOnClickListener {
                                closeDrawer()
                                dismissKeyboard()
                                jumpToHit(row, hit)
                            }
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) })
                    }
                    if (hits.isEmpty() && row.snippet.isNotBlank()) card.addView(TextView(context).apply {
                        text = "  “${row.snippet.take(160)}”"
                        textSize = 11f
                        setTextColor(Color.parseColor("#FFCCAA88"))
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    drawerSessionBox.addView(card)
                }
            }
        }
    }

    /** 模式条高亮跟随当前模式；配置改名后也走这里同步显示 */
    private fun refreshModeStrip() {
        if (!::modeStrip.isInitialized) return
        val keys = AiConsoleClient.MODE_DEFAULTS.keys.toList()
        val cur = AiConsoleClient.currentMode()
        keys.forEachIndexed { i, key ->
            val b = modeStripButtons.getOrNull(i) ?: return@forEachIndexed
            val on = key == cur
            // 六个按钮平分宽度，名称压到 4 字并紧贴选中点，避免被 ellipsize 截断
            b.text = AiConsoleClient.modeLabel(key).take(4) + if (on) "●" else ""
            b.setTextColor(Color.parseColor(if (on) "#FFFFFFFF" else "#FFA8B0B8"))
            (b.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (on) "#FF355E52" else "#FF303030"))
        }
    }

    private fun toggleSettings() {
        settingsPanel.visibility = if (settingsPanel.visibility == VISIBLE) GONE else VISIBLE
        refreshFeatures()
    }

    /** 根据客户端当前模式/快速开关/待发附件刷新功能面板 */
    private fun refreshFeatures() {
        val cfg = AiConsoleClient.effectiveCfg()
        val modeLabel = AiConsoleClient.modeLabel(AiConsoleClient.currentMode())
        featAuto.text = if (autoSpeak) "🔊 自动朗读\n开" else "🔊 自动朗读\n关"
        featSearch.text = if (cfg.search) "🌐 联网搜索\n开" else "🌐 联网搜索\n关"
        featThink.text = if (cfg.think) "🧠 深度思考\n开" else "🧠 深度思考\n关"
        featMode.text = "🛠 工作模式\n${modeLabel.take(10)}"
        // 顶栏图标：开启时高亮描边，关闭时压暗，点一下即切换（与 Web .toggles 同源）
        fun tintQuickIcon(btn: TextView, on: Boolean) {
            btn.setTextColor(Color.parseColor(if (on) "#FF9EFFB0" else "#FF8A8A8A"))
            (btn.background as? GradientDrawable)?.setStroke(
                dp(1), Color.parseColor(if (on) "#FF3B7D6B" else "#FF454545"))
            (btn.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (on) "#FF1E3830" else "#FF303030"))
        }
        if (::quickSearchBtn.isInitialized) tintQuickIcon(quickSearchBtn, cfg.search)
        if (::quickThinkBtn.isInitialized) tintQuickIcon(quickThinkBtn, cfg.think)
        if (::drawerAutoItem.isInitialized) {
            drawerAutoItem.text = if (autoSpeak) "🔊 自动朗读：开" else "🔊 自动朗读：关"
        }
        val count = AiConsoleClient.attachmentCount()
        featAttach.text = if (count > 0) "📎 添加文件\n$count 个待发送" else "📎 添加文件"
        val summary = AiConsoleClient.attachmentSummary()
        featureView.text = if (summary.isBlank())
            "面板开关=模式默认（新会话/Web 同步）· 顶栏图标=仅本会话"
        else "待发送附件：$summary（点此清空）"
        featureView.setOnClickListener {
            if (AiConsoleClient.attachmentCount() > 0) {
                AiConsoleClient.clearAttachments()
                Toast.makeText(context, "已清空待发送附件", Toast.LENGTH_SHORT).show()
                refreshFeatures()
            }
        }
    }

    private fun toggleAutoSpeak() {
        dismissKeyboard()
        autoSpeak = !autoSpeak
        context.getSharedPreferences("ai_console", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_speak", autoSpeak).apply()
        if (!autoSpeak) {
            stopAutoTts()
        } else {
            // 朗读只在「本端提问完成」时触发，打开开关本身不会念任何历史消息
            ensureAutoTts()
        }
        refreshFeatures()
        Toast.makeText(context, if (autoSpeak) "已开启自动朗读" else "已关闭自动朗读", Toast.LENGTH_SHORT).show()
    }

    private fun toggleQuick(kind: String) {
        dismissKeyboard()
        AiConsoleClient.toggleQuick(kind) { ok, msg ->
            Toast.makeText(context, if (ok) msg else msg, Toast.LENGTH_SHORT).show()
            refreshFeatures()
        }
        refreshFeatures()
    }

    /** 设置面板开关：写模式默认 + 清本会话覆盖，新会话与 Web 端都会跟随 */
    private fun setModeDefault(kind: String) {
        dismissKeyboard()
        val enabled = if (kind == "think") AiConsoleClient.effectiveCfg().think
            else AiConsoleClient.effectiveCfg().search
        AiConsoleClient.setModeDefault(kind, !enabled) { ok, msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            refreshFeatures()
        }
        refreshFeatures()
    }

    private fun showModePicker() {
        dismissKeyboard()
        val keys = AiConsoleClient.MODE_DEFAULTS.keys.toList()
        val labels = keys.map { AiConsoleClient.modeLabel(it) }.toTypedArray()
        val checked = keys.indexOf(AiConsoleClient.currentMode()).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("工作模式")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AiConsoleClient.setMode(keys[which]) { ok, msg ->
                    Toast.makeText(context, if (ok) "已切换：$msg" else msg, Toast.LENGTH_SHORT).show()
                    refreshFeatures()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 宿主 Activity 选好文件后回调：读取 content:// 并转为网页端同款 base64 附件 */
    fun onPickedFile(uri: Uri?) {
        if (uri == null) return
        runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    else null
                }?.ifBlank { "attachment" } ?: "attachment"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("文件读取失败")
            val ok = AiConsoleClient.addAttachment(
                name, Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size
            )
            if (!ok) Toast.makeText(context, "附件超过 12 个或总计 12MB", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, "已添加：$name（发送时一起提交）", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "添加文件失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
        refreshFeatures()
    }

    /**
     * 初始化本地 TTS 引擎。原实现只在第一次 speakAuto 时创建，且对初始化失败/
     * 缺少中文语音包完全静默——开关显示已开却一点声音没有，看起来就是"没生效"。
     * 这里把失败原因显式提示出来，并在打开开关时预热引擎。
     */
    private fun ensureAutoTts() {
        if (autoTts != null) return
        try {
            autoTts = TextToSpeech(context.applicationContext) { status ->
                post {
                    if (status != TextToSpeech.SUCCESS) {
                        Toast.makeText(context, "语音引擎初始化失败（$status），请检查系统 TTS 设置",
                            Toast.LENGTH_LONG).show()
                        pendingAutoText = null
                        TtsAudioFocus.abandon(this@AiChatOverlay)
                        return@post
                    }
                    val ready = autoTts ?: return@post
                    // 朗读结束/出错归还焦点，媒体才能恢复播放；回调可能不在主线程，归还是线程安全的
                    ready.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) = TtsAudioFocus.abandon(this@AiChatOverlay)
                        override fun onError(utteranceId: String?) = TtsAudioFocus.abandon(this@AiChatOverlay)
                    })
                    val lang = ready.setLanguage(Locale.CHINA)
                    if (lang == TextToSpeech.LANG_MISSING_DATA ||
                        lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Toast.makeText(context, "系统缺少中文语音包，自动朗读无法使用",
                            Toast.LENGTH_LONG).show()
                        pendingAutoText = null
                        TtsAudioFocus.abandon(this@AiChatOverlay)
                        return@post
                    }
                    // 引擎初始化是异步的：这期间若媒体/来电抢占了焦点，焦点已不属于本朗读，不再念旧文本
                    pendingAutoText?.let {
                        if (TtsAudioFocus.isHeldBy(this@AiChatOverlay)) {
                            ready.speak(MarkdownText.plain(it), TextToSpeech.QUEUE_FLUSH, null, "auto")
                        }
                    }
                    pendingAutoText = null
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "语音引擎不可用：${e.message}", Toast.LENGTH_LONG).show()
            pendingAutoText = null
            TtsAudioFocus.abandon(this@AiChatOverlay)
        }
    }

    /**
     * 本端回复完成后自动朗读（本地 TTS，不上传音频）；调用方已保证一条只读一次。
     * 读前先申请音频焦点：拿不到说明此刻有更重要的声音（音乐/通话/语音输入），直接放弃本次朗读。
     */
    private fun speakAuto(text: String) {
        if (!autoSpeak || text.isBlank()) return
        if (!TtsAudioFocus.request(context, this) { onSpeakInterrupted() }) return
        val engine = autoTts
        if (engine == null) {
            // 引擎尚未就绪，等初始化回调里播；不要在引擎已就绪时残留 pending，
            // 否则下次重建引擎时会把旧文本再念一遍。
            pendingAutoText = text
            ensureAutoTts()
        } else {
            pendingAutoText = null
            // 去掉 Markdown 标记再念，否则 TTS 会把"星号星号""井号"读出来
            engine.speak(MarkdownText.plain(text), TextToSpeech.QUEUE_FLUSH, null, "auto")
        }
    }

    /** 被外界声音打断：立即停读但不销毁引擎（下次回复可快速恢复），焦点已被系统抢走无需归还 */
    private fun onSpeakInterrupted() {
        post {
            pendingAutoText = null
            autoTts?.stop()
        }
    }

    private fun stopAutoTts() {
        TtsAudioFocus.abandon(this)
        pendingAutoText = null
        autoTts?.stop()
        autoTts?.shutdown()
        autoTts = null
    }

    /** 会话列表展示项（本地列表与后端全局搜索统一成这个结构） */
    private data class SessionRow(
        val id: String, val title: String, val mode: String, val mtime: Long,
        val snippet: String = "", val matched: String = "",
        val hits: List<SearchHit> = emptyList()
    )

    /** 搜索命中消息：role + 命中片段（history 无 id，按正文匹配定位） */
    private data class SearchHit(
        val role: String, val text: String, val terms: List<String>
    )

    /** 切换会话后要跳转到的目标消息（按 role + 搜索词在历史正文中定位） */
    private var pendingJump: SearchHit? = null

    /** 从 CodexWeb 拉取会话列表，选择后切换到该会话（与网页端同步） */
    private fun showSessionPicker() {
        val ctx = context
        val current = AiConsoleClient.currentSessionId()

        val dialog = object : AppCompatDialog(ctx) {
            override fun onStart() {
                super.onStart()
                window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.94f).roundToInt(),
                    (resources.displayMetrics.heightPixels * 0.78f).roundToInt()
                )
            }
        }
        dialog.setTitle(null)
        dialog.setCanceledOnTouchOutside(true)

        val rootPad = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#FF232323"))
        }
        rootPad.addView(TextView(ctx).apply {
            text = "选择会话"
            textSize = 17f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(2))
        })
        val subTitle = TextView(ctx).apply {
            text = "与 CodexWeb 网页端同一批会话"
            textSize = 11f; setTextColor(Color.parseColor("#99AACCFF"))
            setPadding(0, 0, 0, dp(9))
        }
        rootPad.addView(subTitle)

        // 搜索框：调后端全局搜索（标题/分组/ID + 历史正文）
        val search = EditText(ctx).apply {
            hint = "全局搜索：标题、ID 或历史消息内容…"
            setHintTextColor(Color.parseColor("#77FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#FF141414"))
            }
        }
        rootPad.addView(search)

        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(ctx).apply {
            addView(listBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        }
        rootPad.addView(scroller)

        var searchSeq = 0 // 只接受最后一次搜索结果，避免快速输入时旧结果覆盖
        fun renderRows(rows: List<SessionRow>, searching: Boolean) {
            listBox.removeAllViews()
            if (searching) {
                listBox.addView(TextView(ctx).apply {
                    text = "搜索中…"
                    textSize = 12f; setTextColor(Color.parseColor("#99FFFFFF"))
                    setPadding(dp(10), dp(16), dp(10), dp(16))
                })
                return
            }
            if (rows.isEmpty()) {
                listBox.addView(TextView(ctx).apply {
                    text = "没有匹配的会话"
                    textSize = 12f; setTextColor(Color.parseColor("#99FFFFFF"))
                    setPadding(dp(10), dp(18), dp(10), dp(18))
                })
                return
            }
            rows.forEach { row ->
                val isCur = row.id == current
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (isCur) Color.parseColor("#FF2E4A42") else Color.parseColor("#FF2C2C2C"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(7) }
                }
                card.addView(TextView(ctx).apply {
                    val n = if (row.hits.isNotEmpty()) "  [${row.hits.size} 条命中]" else ""
                    text = (if (isCur) "● " else "○ ") + row.title.ifBlank { row.id } + n
                    textSize = 13f; setTextColor(Color.WHITE); maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                val time = try {
                    android.text.format.DateFormat.format("MM-dd HH:mm", row.mtime * 1000).toString()
                } catch (_: Exception) { "" }
                val meta = buildString {
                    append(row.id); append("  ·  ").append(row.mode)
                    if (time.isNotBlank()) append("  ·  ").append(time)
                    if (row.matched.isNotBlank()) append("\n命中：").append(row.matched)
                }
                card.addView(TextView(ctx).apply {
                    text = meta
                    textSize = 10f; setTextColor(Color.parseColor("#AA9AA0A8"))
                })
                // 列出该会话的全部命中消息，每条可点：切到会话并滚动定位到该消息
                if (row.hits.isNotEmpty()) {
                    val roleLabel = mapOf("user" to "我", "assistant" to "AI")
                    row.hits.forEach { hit ->
                        val hitView = TextView(ctx).apply {
                            val label = roleLabel[hit.role] ?: hit.role
                            text = "▸ $label：${hit.text}"
                            textSize = 11f; setTextColor(Color.parseColor("#FFCCAA88"))
                            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                            isClickable = true
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#FF1B1B1B"))
                            }
                            setOnClickListener {
                                dialog.dismiss()
                                jumpToHit(row, hit)
                            }
                        }
                        card.addView(hitView, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) })
                    }
                } else if (row.snippet.isNotBlank()) {
                    card.addView(TextView(ctx).apply {
                        text = "  “${row.snippet}”"
                        textSize = 11f; setTextColor(Color.parseColor("#FFCCAA88"))
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#FF1B1B1B"))
                        }
                    })
                }
                card.setOnClickListener {
                    dialog.dismiss()
                    AiConsoleClient.switchSession(row.id, row.mode) { ok, msg ->
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        if (ok) forceScrollNext = true
                    }
                }
                listBox.addView(card)
            }
        }

        // 本地列表（空搜索词）
        fun loadLocal() {
            AiConsoleClient.loadSessions { list ->
                val rows = list.sortedWith(
                    compareByDescending<AiConsoleClient.SessionItem> { it.title.startsWith("★") }
                        .thenByDescending { it.mtime }
                ).map { SessionRow(it.id, it.title, it.mode, it.mtime) }
                subTitle.text = "与 CodexWeb 网页端同一批会话 · 共 ${rows.size} 个"
                renderRows(rows, false)
            }
        }

        // 后端全局搜索
        fun searchGlobal(q: String) {
            val seq = ++searchSeq
            renderRows(emptyList(), true)
            val enc = try { java.net.URLEncoder.encode(q, "UTF-8") } catch (_: Exception) { q }
            AiConsoleClient.api("GET", "/api/sessions/search?q=$enc") { resp ->
                if (seq != searchSeq || !dialog.isShowing) return@api
                val arr = resp?.optJSONArray("results") ?: org.json.JSONArray()
                val rows = mutableListOf<SessionRow>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val matched = o.optJSONArray("matched_in")?.let { ma ->
                        (0 until ma.length()).joinToString("、") { ma.optString(it) }
                    }.orEmpty()
                    // 后端每条命中带 role/ts/turn_id/text，前端用来列出全部命中并点击定位
                    val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val hits = mutableListOf<SearchHit>()
                    o.optJSONArray("hits")?.let { ha ->
                        for (k in 0 until ha.length()) {
                            val h = ha.optJSONObject(k) ?: continue
                            hits.add(SearchHit(
                                role = h.optString("role"),
                                text = h.optString("text"),
                                terms = terms,
                            ))
                        }
                    }
                    rows.add(SessionRow(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        mode = o.optString("mode", "chat"),
                        mtime = o.optLong("mtime"),
                        snippet = o.optString("snippet"),
                        matched = matched,
                        hits = hits,
                    ))
                }
                subTitle.text = "全局搜索「$q」· 命中 ${rows.size} 个会话"
                renderRows(rows, false)
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isBlank()) loadLocal() else searchGlobal(q)
            }
        })
        loadLocal()

        // 底部：新建会话 + 取消
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val newBtn = TextView(ctx).apply {
            text = "＋ 新建会话"
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#FF3B7D6B"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener {
                AiConsoleClient.api("POST", "/api/sessions", "{}") { r ->
                    val id = r?.optString("id").orEmpty()
                    if (id.isNotBlank()) {
                        dialog.dismiss()
                        AiConsoleClient.switchSession(id) { ok, msg ->
                            Toast.makeText(ctx, if (ok) "已创建并切换到 $id" else msg, Toast.LENGTH_SHORT).show()
                            if (ok) forceScrollNext = true
                        }
                    } else Toast.makeText(ctx, AiConsoleClient.apiError(), Toast.LENGTH_SHORT).show()
                }
            }
        }
        val cancel = TextView(ctx).apply {
            text = "取消"
            textSize = 13f; setTextColor(Color.parseColor("#FFCCCCCC")); gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#FF333333"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        }
        actions.addView(newBtn)
        actions.addView(cancel)
        rootPad.addView(actions)

        dialog.setContentView(rootPad)
        dialog.show()
    }

    /** 点击搜索命中：切到该会话，历史加载完在 render() 里滚动定位并高亮 */
    private fun jumpToHit(row: SessionRow, hit: SearchHit) {
        pendingJump = hit
        val sameSession = row.id == AiConsoleClient.currentSessionId()
        if (sameSession) {
            // 已是当前会话，历史不会再加载，直接触发一次定位
            chatList.post { applyPendingJump() }
        } else {
            AiConsoleClient.switchSession(row.id, row.mode) { ok, msg ->
                if (!ok) {
                    pendingJump = null
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 在已加载的历史里按 role + 搜索词定位目标消息，滚动过去并短暂高亮。
     * history 接口不返回消息 id/时间戳，所以用「role 相同且正文含全部搜索词」匹配，
     * 与后端 search 的判定口径一致（所有词出现在同一条消息）。
     */
    private fun applyPendingJump() {
        val hit = pendingJump ?: return
        val idx = messagesSnapshot.indexOfFirst { m ->
            (hit.role.isBlank() || m.role == hit.role) &&
                hit.terms.all { t -> m.text.contains(t, true) }
        }
        if (idx < 0) {
            pendingJump = null
            Toast.makeText(context, "未能在历史中定位该消息，已打开会话", Toast.LENGTH_SHORT).show()
            return
        }
        pendingJump = null
        forceScrollNext = false // 不要滚到底，改成滚到命中位置
        val child = chatList.getChildAt(idx) ?: return
        chatScroll.post {
            chatScroll.scrollTo(0, child.top - dp(40).coerceAtLeast(0))
            // 短暂高亮命中的气泡正文
            bubbleRefs.getOrNull(idx)?.body?.let { body ->
                val origin = body.background
                body.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#FF6B5A2E"))
                    setStroke(dp(2), Color.parseColor("#FFFFD27A"))
                }
                body.postDelayed({ body.background = origin }, 2600)
            }
        }
    }

    /** 标题栏显示当前会话标题（与网页端一致）；sid 变化时异步拉取，避免每个 token 重复请求。
     *  [force] 用于运行结束后后端 auto_title 改了标题的场景，需绕过 sid 相等判断重拉。 */
    private fun refreshSessionTitle(sid: String?, force: Boolean = false) {
        val changed = sid != headerSessionId
        if (!force && !changed) return
        val switched = headerSessionId != null && changed
        headerSessionId = sid
        if (sid.isNullOrBlank()) {
            headerTitle.text = "新会话"
            return
        }
        // 切换会话时先用短 id 占位，避免异步拉取期间停留上一个会话的标题
        if (switched) headerTitle.text = sid.take(8)
        AiConsoleClient.loadSessions { list ->
            val title = list.firstOrNull { it.id == sid }?.title
            if (!title.isNullOrBlank()) headerTitle.text = title
            else if (headerTitle.text.isBlank() || switched) headerTitle.text = sid.take(8)
        }
    }

    fun show() {
        if (manageWindow) addStandaloneWindow() else attachImeScrollWatcher()
        visibility = VISIBLE
        closeDrawer()
        // 后台期间消息可能已被 Web 端改写，旧操作卡的 index/text 不可信，回前台先收掉
        dismissInlinePanel()
        forceRebuildNext = true
        headerSub.text = AiConsoleClient.usageHeaderText()
        headerSubTotal.text = AiConsoleClient.sessionUsageHeaderText()
        render()
        AiConsoleClient.addListener(renderListener)
        AiConsoleClient.startPollingIfRunning()
        focusInputBox()
    }

    fun hide() {
        if (manageWindow) removeStandaloneWindow()
        closeDrawer()
        visibility = GONE
        AiConsoleClient.removeListener(renderListener)
        // 这里不再 stopAutoTts()：切后台只收 UI，自动朗读应继续播完当前回复。
        // 历史轮询也不在这里停，进程存活期间继续同步 Web 端消息。
    }

    /** Activity 真正销毁时释放 TTS、监听器与操作卡 */
    fun release() {
        AiConsoleClient.removeListener(renderListener)
        AiConsoleClient.removeLocalReplyListener(localReplyListener)
        AiConsoleClient.removeConfigListener(configChangeListener)
        imeScrollWatcher?.let { runCatching { viewTreeObserver.removeOnGlobalLayoutListener(it) } }
        imeScrollWatcher = null
        dismissInlinePanel()
        stopAutoTts()
    }

    /**
     * 把对话面板作为「独立窗口」加到 WindowManager，而不再嵌在九键面板的窗口里。
     * 之前嵌在九键窗口里时，那个窗口带 FLAG_LAYOUT_NO_LIMITS、由 WorkbenchOverlay
     * 按键盘面板高度创建，既测不到键盘高度、改高度又会被其它逻辑干扰。独立窗口自己
     * 掌控 gravity/flags/height，才能真正做到键盘弹出时整块上移。
     */
    private fun addStandaloneWindow() {
        if (windowAdded) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }
        // 全屏、顶部对齐、可聚焦、不带 NO_LIMITS：
        //  - 不能加 NOT_TOUCH_MODAL：否则点击会穿透到底层九键窗口，底层收到
        //    ACTION_OUTSIDE 后把整个工作台/应用列表收起，表现为"点 AI 窗任意
        //    位置全部界面消失"；同时 IME 焦点也会被底层输入框抢走。
        //  - 不能加 WATCH_OUTSIDE_TOUCH：本窗口是全屏会话页，关闭只走收起按钮。
        //  - 不带 NO_LIMITS 才能通过 getWindowVisibleDisplayFrame 测到键盘高度。
        //  - ADJUST_NOTHING：禁止系统自动 pan/resize，键盘位置由下面的
        //    ImeWatcher 统一压缩窗口高度，避免两套逻辑互相打架。
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        fullWindowHeight = resources.displayMetrics.heightPixels
        runCatching { wm.addView(this, lp) }
        windowAdded = true
        attachImeWatcher()
    }

    private fun removeStandaloneWindow() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        val wm = windowManager
        if (wm != null && windowAdded) {
            imeWatcher?.let { runCatching { viewTreeObserver.removeOnGlobalLayoutListener(it) } }
            imeWatcher = null
            runCatching { wm.removeViewImmediate(this) }
        }
        windowAdded = false
        windowManager = null
        appliedImeHeight = 0
    }

    /**
     * 键盘弹出时把整块独立窗口压到键盘上沿：gravity=TOP，把窗口高度从整屏减到
     * visibleFrame.bottom，底边即抬到键盘之上，输入行始终可见；键盘收起则还原全屏。
     * 用「屏幕高度 - 可见区底边」推断键盘高度，并用像素级防抖避免 updateViewLayout 抖动。
     */
    private fun attachImeWatcher() {
        if (imeWatcher != null) return
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isAttachedToWindow || visibility != VISIBLE) return
                val wm = windowManager ?: return
                val lp = layoutParams as? WindowManager.LayoutParams ?: return

                val visibleFrame = Rect()
                getWindowVisibleDisplayFrame(visibleFrame)
                val screenHeight = if (fullWindowHeight > 0) fullWindowHeight else resources.displayMetrics.heightPixels
                val imeHeight = (screenHeight - visibleFrame.bottom).coerceAtLeast(0)
                val keyboardOpen = imeHeight > dp(80)
                val targetHeight = if (keyboardOpen) visibleFrame.bottom else screenHeight

                // 像素级防抖：高度变化很小就不折腾窗口，避免 updateViewLayout 抖动
                if (kotlin.math.abs(targetHeight - appliedImeHeight) < dp(12)) return
                appliedImeHeight = targetHeight

                lp.height = if (keyboardOpen) targetHeight else WindowManager.LayoutParams.MATCH_PARENT
                runCatching { wm.updateViewLayout(this@AiChatOverlay, lp) }
            }
        }
        imeWatcher = listener
        viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /**
     * Activity 模式的键盘跟随：系统 adjustResize 压缩窗口后，输入行到了键盘上沿，
     * 但 ScrollView 的 scrollY 保持原值，末尾消息被顶出视口。这里在可见高度变化时，
     * 若用户本来就贴底（followIntent）就重新滚到底；键盘收起后同理恢复到底部。
     * 阈值过滤掉状态栏/导航栏等微小变化，只对真正的键盘开合做出响应。
     */
    private fun attachImeScrollWatcher() {
        if (manageWindow || imeScrollWatcher != null) return
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isAttachedToWindow || visibility != VISIBLE) return
                val frame = Rect()
                getWindowVisibleDisplayFrame(frame)
                val h = frame.height()
                if (lastVisibleHeight == 0) { lastVisibleHeight = h; return }
                val delta = kotlin.math.abs(h - lastVisibleHeight)
                if (delta < dp(80)) return // 非键盘引起的高度变化（状态栏等）忽略
                // 必须在更新 lastVisibleHeight 之前算出视口压缩量：
                // shrink>0 键盘弹出（视口变矮），shrink<0 键盘收起（视口恢复）。
                val shrink = lastVisibleHeight - h
                lastVisibleHeight = h
                if (followIntent) {
                    scrollToBottomNoFocus()
                } else {
                    // 用户上翻查看历史时不强制贴底，但把 scrollY 按视口压缩量同步回补，
                    // 让原本贴着输入行的内容继续贴在键盘上沿——即对话随键盘高度定位。
                    // scrollBy 会被系统 clamp 到 [0, maxScroll]，不会滚出边界。
                    if (shrink != 0) chatScroll.scrollBy(0, shrink)
                }
            }
        }
        imeScrollWatcher = listener
        viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** 输入框一旦获得焦点就弹键盘（含用户手动点击输入框的情况） */
    private fun showKeyboardForInput() {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        // 普通 Activity 窗口用 showSoftInput(view, 0) 即可；窗口还没拿到焦点时
        // 系统会静默忽略，所以由 OnFocusChangeListener + onWindowFocusChanged 兜底。
        if (!imm.showSoftInput(inputBox, 0)) {
            imm.showSoftInput(inputBox, InputMethodManager.SHOW_FORCED)
        }
    }

    /** 直接收起键盘并让输入框失焦。
     *  - 不依赖焦点监听的间接路径：部分机型 clearFocus 不会立刻收 IME，显得「不灵敏」。
     *  - 输入框自身点击仍可重新唤起；发送与正常输入不调用这里。 */
    private fun dismissKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = inputBox.windowToken ?: this@AiChatOverlay.windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
        if (inputBox.hasFocus()) inputBox.clearFocus()
        // 有些机型 token 在 clearFocus 后才失效，补一次保证真的收掉
        inputBox.post {
            imm.hideSoftInputFromWindow(inputBox.windowToken ?: this@AiChatOverlay.windowToken, 0)
        }
    }

    /** 当前系统键盘是否可见（用于键盘收起后的滚动位置补偿） */
    private fun isKeyboardVisible(): Boolean {
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val screenHeight = if (fullWindowHeight > 0) fullWindowHeight else resources.displayMetrics.heightPixels
        return (screenHeight - frame.bottom) > dp(80)
    }

    /** 让输入框拿到焦点并弹键盘；Activity 拿到窗口焦点后也会再调一次 */
    fun focusInputBox() {
        inputBox.isFocusable = true
        inputBox.isFocusableInTouchMode = true
        fun requestFocusAndIme() {
            if (!isAttachedToWindow || visibility != VISIBLE) return
            inputBox.requestFocus()
            showKeyboardForInput()
        }
        inputBox.post { requestFocusAndIme() }
        inputBox.postDelayed({ requestFocusAndIme() }, 180)
        inputBox.postDelayed({ requestFocusAndIme() }, 500)
    }

    fun isVisible(): Boolean = visibility == VISIBLE

    /** 宿主把输入条里的文本带进来 */
    fun prefill(text: String) {
        if (text.isNotEmpty()) {
            inputBox.setText(text)
            inputBox.setSelection(inputBox.text.length)
        }
    }

    /**
     * 工具箱全局搜索命中后跳转：设置定位意图，历史加载完成时 render() 自动滚动高亮。
     * terms 是搜索词（空格分隔），role 为空则不限角色。
     */
    fun requestJump(role: String, terms: String) {
        val list = terms.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (list.isEmpty()) return
        pendingJump = SearchHit(role = role, text = terms, terms = list)
        if (messagesSnapshot.isNotEmpty()) post { applyPendingJump() }
    }

    /**
     * 气泡下的模式按钮：按用户策略 + 当前模式的真实能力动态决定。
     * 模式自定义后（例如纯对话开了联网、轻量关了联网），这里会立即跟着变，
     * 不再写死「chat 没工具 / lite 有工具」这类过时假设。
     */
    private val cliIntentRe = Regex(
        "运行一下|执行一下|帮我(执行|运行|编译|打包|改文件|写文件|读文件)|" +
            "写入文件|保存到文件|读取文件|修改文件|创建文件|删除文件|" +
            "编译|打包|抓包|反编译|git\\s+(commit|push|clone)"
    )
    private val searchIntentRe = Regex("联网搜|搜索一下|查一下最新|最新消息|实时数据|打开(网页|链接|网站)")
    private val explainOnlyRe = Regex("解释|什么是|为什么|区别|原理|什么意思|讲讲|翻译|润色|举个例子|这段代码")
    private val capLimitRe = Regex(
        "无法|不能|没有(工具|权限|能力)|不支持|仅能|只能|受限|建议切换|需要切换|请切换|改用|换成"
    )

    private fun suggestedModes(text: String): List<String> {
        // 流式输出期间不插按钮：否则关键词刚出现时 structKey 变化会整表重建，造成跳动。
        // 运行结束后状态刷新会自然触发一次重建，按钮随最终文本出现。
        if (AiConsoleClient.running || text.isBlank()) return emptyList()
        val cfg = AiConsoleClient.suggestCfg()
        if (cfg.policy == "off" || explainOnlyRe.containsMatchIn(text)) return emptyList()
        val need = mutableListOf<String>()
        if (cliIntentRe.containsMatchIn(text)) need += "cli"
        if (searchIntentRe.containsMatchIn(text)) need += "search"
        if (need.isEmpty()) return emptyList()
        val current = AiConsoleClient.currentMode()
        // 当前模式已具备所需能力 —— 用户正在合适的模式做合适的事，不打扰。
        val miss = need.filter { !AiConsoleClient.modeCan(current, it) }
        if (miss.isEmpty()) return emptyList()
        // 保守档仍需助手明确说做不到；主动档识别到意图即可。
        if (cfg.policy == "cautious" && !capLimitRe.containsMatchIn(text)) return emptyList()
        val order = AiConsoleClient.MODE_DEFAULTS.keys.toList()
        return order.filter { key ->
            key != current && miss.all { c -> AiConsoleClient.modeCan(key, c) }
        }.sortedWith(compareBy({ it != cfg.preferred }, { order.indexOf(it) })).take(2)
    }

    /** 决定气泡 View 结构是否需要新建的签名：正文/过程文本的变化不在此列 */
    private fun structKeyOf(m: ChatMessage): String =
        "${m.role}|${m.failed}|${m.process.isNotBlank()}|${m.reasoning.isNotBlank()}|" +
            "${m.sources.isNotEmpty()}|" + suggestedModes(m.text).joinToString(",")

    private fun render() {
        messagesSnapshot = AiConsoleClient.messages
        // 副标题显示本轮实时 token 计数与命中率；第三行显示会话累计，对齐网页端 chip
        headerSub.text = AiConsoleClient.usageHeaderText()
        headerSubTotal.text = AiConsoleClient.sessionUsageHeaderText()
        refreshFeatures()
        val sidRaw = AiConsoleClient.currentSessionId()
        // 运行结束时后端 auto_title 会总结并改写会话标题，强制重拉一次让标题跟上
        val runningNow = AiConsoleClient.running
        val justFinished = headerWasRunning && !runningNow
        headerWasRunning = runningNow
        // 朗读不在这里触发：render() 会被历史轮询、切会话、回前台、开关切换等频繁调用，
        // 任何"读末尾正文"的兜底都会造成重复。朗读唯一入口是本端提问完成时的回调。
        // 运行结束后才生成模式按钮；这里主动重建一次让最终文本上的按钮出现。
        if (justFinished && messagesSnapshot.any { suggestedModes(it.text).isNotEmpty() }) {
            forceRebuildNext = true
        }
        refreshSessionTitle(sidRaw, force = justFinished)
        val sid = sidRaw ?: "未建立"
        diagView.text = "会话 $sid · ${messagesSnapshot.size} 条消息 · " +
            if (AiConsoleClient.hasServerConfigured()) AiConsoleClient.serverLabel() else "未配置服务器地址"

        val forceNow = forceScrollNext
        if (forceNow) followIntent = true
        forceScrollNext = false
        val rebuildFromResume = forceRebuildNext
        forceRebuildNext = false

        // 统一滚动策略：强制场景瞬间到底；流式跟随走节流平滑，避免窗口乱跳。
        fun applyScroll() {
            if (forceNow) scrollToBottomNoFocus()
            else if (followIntent) followStreamScroll()
        }

        // 是否需要整表重建：仅会话切换 / 条数变化 / 某条结构变化（role、失败按钮、过程区有无）。
        // 正文与过程文本的高频增长永远走下面的局部更新，绝不 removeAllViews——
        // 重建会让 ScrollView 内容高度瞬间归零、滚动位置被 clamp，表现为窗口来回乱跳。
        val needRebuild = rebuildFromResume || forceNow || sid != renderedSid ||
            messagesSnapshot.size != bubbleRefs.size ||
            messagesSnapshot.indices.any { structKeyOf(messagesSnapshot[it]) != bubbleRefs[it].structKey }

        if (needRebuild) {
            // 新会话或刚发送时旧操作卡属于上一次上下文，直接收掉，避免按旧 index 插到新消息里
            if (forceNow || sid != renderedSid) dismissInlinePanel()
            chatList.removeAllViews()
            bubbleRefs.clear()
            inlineActionView = null
            for ((idx, msg) in messagesSnapshot.withIndex()) {
                val v = bubble(msg, idx)
                chatList.addView(v)
                val body = (v as? LinearLayout)?.getChildAt(0) as? TextView
                if (body == null) continue
                bubbleRefs.add(BubbleRef(
                    wrap = v,
                    body = body,
                    det = v.findViewWithTag("ai_proc_det") as? TextView,
                    proc = v.findViewWithTag("ai_proc_body") as? TextView,
                    reasonDet = v.findViewWithTag("ai_reason_det") as? TextView,
                    reasonBody = v.findViewWithTag("ai_reason_body") as? TextView,
                    structKey = structKeyOf(msg),
                    text = msg.text,
                    process = msg.process,
                    reasoning = msg.reasoning,
                ))
            }
            renderedSid = sid
            pendingAction?.let { (i, m) ->
                if (i in messagesSnapshot.indices) insertInlinePanel(i, m)
            }
            applyScroll()
            if (pendingJump != null) chatList.post { applyPendingJump() }
            return
        }

        // 局部更新：逐条对比，只改真正变化的 TextView（可能有多条 assistant 同时增长）
        for (i in messagesSnapshot.indices) {
            val m = messagesSnapshot[i]
            val ref = bubbleRefs[i]
            if (m.text != ref.text) {
                ref.body.text = renderBubbleText(m.text, m.role)
                ref.text = m.text
            }
            if (m.process != ref.process) {
                ref.proc?.text = m.process
                ref.det?.text = "▸ 过程（${m.process.lineCount()} 步）"
                ref.process = m.process
            }
            if (m.reasoning != ref.reasoning) {
                ref.reasonBody?.text = m.reasoning
                // 思考文本还在增长时保留用户已展开的状态，别把标题打回收起文案
                val expanded = ref.reasonBody?.visibility == VISIBLE
                ref.reasonDet?.text = if (expanded)
                    "▾ 已深度思考（${m.reasoning.length} 字）"
                else "🧠 已深度思考（${m.reasoning.length} 字）"
                ref.reasoning = m.reasoning
            }
        }
        applyScroll()
        if (pendingJump != null) chatList.post { applyPendingJump() }
    }

    /**
     * 点击气泡：在气泡正下方插入一张样式不同的操作卡。
     * 用内嵌 View 而不是 PopupWindow——后者的测量时机在悬浮窗/Activity 混合坐标下
     * 极易算错位置而完全不可见；内嵌卡属于会话列表，必然随列表绘制并跟随滚动。
     * 长按已交还 TextView 自身做文字选择/复制，这里只处理点击。
     */
    private fun showMessageActions(anchor: View, msg: ChatMessage, index: Int) {
        if (msg.text.isBlank() || !isAttachedToWindow) return
        val key = index to msg.text
        // 同一条消息再次点击 → 收起卡片；不同消息一次点击直接切换，不需要点两下
        if (actionSheetKey == key && inlineActionView != null) {
            dismissInlinePanel()
            return
        }
        val keyboardWasOpen = isKeyboardVisible()
        val restoreY = chatScroll.scrollY
        // 这次点击里就收键盘；DOWN 阶段不动焦点，保证 UP 能命中气泡
        dismissKeyboard()
        pendingAction = index to msg
        insertInlinePanel(index, msg, anchor, restoreY = restoreY, keyboardWasOpen = keyboardWasOpen)
    }

    /** 收起操作卡并释放朗读资源 */
    private fun dismissInlinePanel() {
        actionSheet?.release()
        actionSheet = null
        actionSheetKey = null
        pendingAction = null
        inlineActionView?.let { runCatching { chatList.removeView(it) } }
        inlineActionView = null
    }

    private fun insertInlinePanel(
        index: Int,
        msg: ChatMessage,
        body: View? = null,
        restoreY: Int? = null,
        keyboardWasOpen: Boolean = false,
    ) {
        inlineActionView?.let { runCatching { chatList.removeView(it) } }
        inlineActionView = null
        // 锚点可能已被整表重建摘掉（parent 为 null）；回退到列表当前项，
        // 并用正文匹配校正 index，避免插到错误的消息下面。
        var idx = index
        var anchorWrap: View? = body?.parent as? View
        if (anchorWrap == null || chatList.indexOfChild(anchorWrap) < 0) {
            val byText = messagesSnapshot.indexOfFirst { it.text == msg.text }
            if (byText >= 0) idx = byText
            anchorWrap = chatList.getChildAt(idx)
        }
        if (anchorWrap == null) return
        val key = idx to msg.text
        if (actionSheetKey != key) {
            // 换消息时重新绑定，避免复制/删除/收藏打到上一条
            actionSheet?.release()
            actionSheet = null
            actionSheetKey = null
        }
        // 同一条消息重建（流式、布局变化）时复用实例，朗读/收藏状态不丢
        val sheet = actionSheet ?: MessageActionSheet(
            context,
            msg.text,
            idx,
            msgId = msg.id,
            sid = AiConsoleClient.currentSessionId().orEmpty(),
            role = msg.role,
            turnId = msg.turnId,
            ts = msg.ts,
            onQuote = { quoted ->
                val current = inputBox.text.toString()
                val merged = if (current.isBlank()) quoted else "$current\n$quoted".trimEnd() + "\n\n"
                inputBox.setText(merged)
                inputBox.setSelection(inputBox.text.length)
                focusInputBox()
                Toast.makeText(context, "已引用到输入框", Toast.LENGTH_SHORT).show()
            },
            onEdit = { newText, editTurnId ->
                dismissInlinePanel()
                forceScrollNext = true
                AiConsoleClient.editAndSend(newText, editTurnId) { ok, msg ->
                    if (ok) refreshFeatures()
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onRegen = { text0, regenTurnId ->
                // 助手刷新时上层按 turnId 取同一轮的用户原问题；用户重发则 sheet 已传入自身文本
                val isUserMsg = msg.role == "user"
                val question = if (isUserMsg) text0 else
                    AiConsoleClient.messages.firstOrNull {
                        it.role == "user" && it.turnId == regenTurnId
                    }?.text?.trim().orEmpty()
                if (question.isBlank()) {
                    Toast.makeText(context, "找不到这一轮的原问题，无法重新生成", Toast.LENGTH_SHORT).show()
                } else {
                    CodexUi.confirm(context,
                        if (isUserMsg) "重新发送这条消息？" else "刷新这条回答？",
                        "会用同一轮的原问题重新请求一次，旧的这一轮先删除（服务端自动备份），新回答出现在对话末尾。"
                    ) {
                        dismissInlinePanel()
                        forceScrollNext = true
                        AiConsoleClient.editAndSend(question, regenTurnId) { ok, msg ->
                            if (ok) refreshFeatures()
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = { dismissInlinePanel() }
        ).also {
            actionSheet = it
            actionSheetKey = key
        }
        val card = sheet.build()
        val pos = chatList.indexOfChild(anchorWrap)
        if (pos < 0) return
        chatList.addView(
            card,
            pos + 1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
        inlineActionView = card
        val settleScroll = {
            runCatching {
                // 键盘收起后视口高度变化，先把点击前的滚动位置还回去，消除坐标漂移
                if (restoreY != null && chatScroll.scrollY != restoreY) chatScroll.scrollY = restoreY
                ensureCardVisible(card)
            }
        }
        if (keyboardWasOpen) {
            // IME 收起是异步的，等两帧布局稳定后再校正，避免动画中途被二次位移
            chatScroll.post { chatScroll.post { chatList.post(Runnable { settleScroll() }) } }
        } else {
            card.post(Runnable { settleScroll() })
        }
    }

    /** 只在卡片超出视口时做最小位移，替代 smoothScrollTo(card.bottom) 的整屏上跳 */
    private fun ensureCardVisible(card: View) {
        if (!card.isAttachedToWindow) return
        val margin = dp(8)
        val top = card.top
        val bottom = card.bottom
        val viewTop = chatScroll.scrollY
        val viewBottom = viewTop + chatScroll.height - chatScroll.paddingBottom
        val delta = when {
            bottom > viewBottom - margin -> (bottom - viewBottom + margin).coerceAtLeast(0)
            top < viewTop + margin -> (top - viewTop - margin).coerceAtMost(0)
            else -> 0
        }
        if (delta != 0) chatScroll.scrollBy(0, delta)
    }

    private fun bubble(msg: ChatMessage, index: Int): View {
        // P2b: recall 建议是可点击的提示卡，绝不自动恢复话题。
        if (msg.role == "suggestion") return suggestionBubble(msg)
        val isUser = msg.role == "user"
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val body = BubbleBody(context).apply {
            text = renderBubbleText(msg.text, msg.role)
            textSize = 13f
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#FFE8E8E8"))
            setPadding(dp(11), dp(8), dp(11), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (isUser) Color.parseColor("#FF355C7D") else Color.parseColor("#FF333333"))
            }
            // 短按弹卡、长按选字全部由 BubbleBody.dispatchTouchEvent 协调：
            // 不设 OnClickListener（首次点击会被聚焦消费），不设 OnTouchListener（会覆盖选字）。
            // bubbleTouching 在 dispatch 阶段置位，阻止 DOWN 抢焦点时输入框收键盘造成落点漂移。
            //
            // 关键：监听器必须无条件安装！助手回复是先以 text="" 占位插入、再流式局部更新
            // 正文的（render 走增量路径不重建气泡）。若按 msg.text.isNotBlank() 决定是否安装，
            // 新消息的气泡会永远没有点击/长按监听 —— 表现为「新消息点不动也长不动」，
            // 而一创建就有正文的错误气泡却正常。
            setTextIsSelectable(true)
            isClickable = true   // 部分机型长按菜单的前置条件
            isLongClickable = true
            onBubbleTouch = { down -> bubbleTouching = down }
            // 点击当场处理，绝不 post 延迟：回复完成后 400ms 的 refreshHistory 会
            // removeAllViews + bubbleRefs.clear()，延迟一帧正好可能撞进这个空窗，
            // getOrNull(index) 拿到 null 就静默 return —— 表现为「最新一条点不动」。
            // 锚点失效时 insertInlinePanel 会回退到 chatList 当前项。
            val self = this
            onSingleTap = {
                // 用实时快照的正文：气泡创建时可能是空占位，点击时正文已流式填充。
                // index 越界（消息数变化）时按气泡身份找不到就放弃，避免张冠李戴。
                val live = messagesSnapshot.getOrNull(index) ?: msg
                showMessageActions(self, live, index)
            }
        }
        wrap.addView(body)

        // 深度思考过程：独立折叠块，默认收起，点标题展开/收起（对齐 Web 的「已深度思考」details）。
        // reasoning 是累积文本，整段展示；与「过程」（命令/工具）分开，避免混在一起看不清。
        if (!isUser && msg.reasoning.isNotBlank()) {
            val reasonBody = TextView(context).apply {
                tag = "ai_reason_body"
                text = msg.reasoning
                textSize = 11.5f
                setTextColor(Color.parseColor("#FFB9B0C8"))
                setPadding(dp(11), dp(7), dp(11), dp(7))
                visibility = GONE
                maxWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
                setTextIsSelectable(true)
            }
            val reasonDet = TextView(context).apply {
                tag = "ai_reason_det"
                text = "🧠 已深度思考（${msg.reasoning.length} 字）"
                textSize = 11f
                setTextColor(Color.parseColor("#FFB0A8CC"))
                setPadding(dp(4), dp(3), dp(4), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FF2A2633"))
                }
                setOnClickListener {
                    reasonBody.visibility = if (reasonBody.visibility == VISIBLE) GONE else VISIBLE
                    text = if (reasonBody.visibility == VISIBLE)
                        "▾ 已深度思考（${reasonBody.text.length} 字）"
                    else "🧠 已深度思考（${reasonBody.text.length} 字）"
                }
            }
            wrap.addView(reasonDet)
            wrap.addView(reasonBody)
        }

        // 联网搜索来源：对齐 Web 端 .srcDet —— 默认折叠，标题是「搜索来源（N）」，
        // 展开后每条来源是可点击的超链接标题，点击用系统浏览器打开（只放行 http/https）。
        if (!isUser && msg.sources.isNotEmpty()) {
            val srcBody = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
                setPadding(dp(8), dp(2), dp(8), dp(4))
            }
            msg.sources.forEach { (title, url) ->
                val safeUrl = runCatching { android.net.Uri.parse(url) }.getOrNull()?.let { uri ->
                    if (uri.scheme == "http" || uri.scheme == "https") uri else null
                }
                srcBody.addView(TextView(context).apply {
                    text = title
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#FF88BBEE"))
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    isClickable = true
                    // 标题即超链接：点击用系统浏览器打开，跟 Web 端 <a srcLink> 一致。
                    setOnClickListener {
                        if (safeUrl == null) {
                            Toast.makeText(context, "来源链接无效", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        runCatching {
                            context.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW, safeUrl
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            val srcDet = TextView(context).apply {
                text = "🌐 搜索来源（${msg.sources.size}）"
                textSize = 11f
                setTextColor(Color.parseColor("#FF88BBEE"))
                setPadding(dp(4), dp(3), dp(4), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FF222E3A"))
                }
                setOnClickListener {
                    srcBody.visibility = if (srcBody.visibility == VISIBLE) GONE else VISIBLE
                }
            }
            wrap.addView(srcDet)
            wrap.addView(srcBody)
        }

        // 助手建议切模式时，把对应模式按钮放进对话气泡下方，一次点击即切换。
        val suggestions = if (!isUser) suggestedModes(msg.text) else emptyList()
        if (suggestions.isNotEmpty()) {
            val modeRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(3), dp(4), dp(2))
            }
            suggestions.forEach { key ->
                val short = AiConsoleClient.modeLabel(key)
                val chip = TextView(context).apply {
                    text = "⇄ 切到$short"
                    textSize = 11f
                    setTextColor(Color.parseColor("#FFBBE0CC"))
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setColor(Color.parseColor("#FF2E4A42"))
                        setStroke(dp(1), Color.parseColor("#FF587268"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(6) }
                    setOnClickListener {
                        dismissKeyboard()
                        AiConsoleClient.setMode(key) { ok, result ->
                            Toast.makeText(
                                context, if (ok) "已切换：$result" else result, Toast.LENGTH_SHORT
                            ).show()
                            if (ok) {
                                // 把触发这条回复的用户原话回填，方便在新模式里直接重试
                                val prompt = AiConsoleClient.messages.getOrNull(index - 1)
                                    ?.takeIf { it.role == "user" }?.text?.trim()
                                if (!prompt.isNullOrBlank()) {
                                    inputBox.setText(prompt)
                                    inputBox.setSelection(prompt.length)
                                    focusInputBox()
                                }
                                forceRebuildNext = true
                                refreshFeatures()
                                render()
                            }
                        }
                    }
                }
                modeRow.addView(chip)
            }
            wrap.addView(modeRow)
        }

        // 发送失败：给一个显式的「重试」按钮，用户主动点才重发。
        // 绝不自动重发——后台断线期间的自动重发正是 token 雪球的根源。
        if (!isUser && msg.failed) {
            val retryBtn = TextView(context).apply {
                text = "↻ 重试"
                textSize = 12f
                setTextColor(Color.parseColor("#FFFFB4A2"))
                setPadding(dp(11), dp(4), dp(11), dp(6))
                setOnClickListener {
                    AiConsoleClient.retryFailed { ok, err ->
                        if (!ok) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            wrap.addView(retryBtn)
        }

        if (!isUser && msg.process.isNotBlank()) {
            val det = TextView(context).apply {
                tag = "ai_proc_det"
                text = "▸ 过程（${msg.process.lineCount()} 步）"
                textSize = 11f
                setTextColor(Color.parseColor("#88999999"))
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            val processView = TextView(context).apply {
                tag = "ai_proc_body"
                text = msg.process
                textSize = 11f
                setTextColor(Color.parseColor("#AA999999"))
                setPadding(dp(11), dp(6), dp(11), dp(6))
                visibility = GONE
                maxWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
            }
            det.setOnClickListener {
                processView.visibility = if (processView.visibility == VISIBLE) GONE else VISIBLE
            }
            wrap.addView(det)
            wrap.addView(processView)
        }
        return wrap
    }

    // P2b: recall 建议提示卡。展示「是否恢复旧话题」，两个按钮由用户决定；
    // 客户端绝不自动重锚话题。与 Web 端 .recall-sug 行为一致。
    private fun suggestionBubble(msg: ChatMessage): View {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FF232830"))
                setStroke(dp(1), Color.parseColor("#FF3A4657"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        wrap.addView(TextView(context).apply {
            text = msg.text
            textSize = 13f
            setTextColor(Color.parseColor("#FFE8E8E8"))
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun chip(label: String, yes: Boolean): TextView = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor(if (yes) "#FF9FD8C0" else "#FFB9B0C8"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor(if (yes) "#FF2E4A42" else "#FF33343E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
            setOnClickListener {
                AiConsoleClient.decideRecallSuggestion(
                    yes, msg.suggestionCardId, msg.suggestionTurn)
            }
        }
        row.addView(chip("恢复", true))
        row.addView(chip("忽略", false))
        wrap.addView(row)
        return wrap
    }

    private fun String.lineCount(): Int = if (isBlank()) 0 else split('\n').size

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
