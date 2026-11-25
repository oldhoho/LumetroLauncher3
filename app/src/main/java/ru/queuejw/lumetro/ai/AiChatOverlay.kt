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
    private val settingsToggle: TextView
    private val settingsPanel: LinearLayout
    /** 设置面板改为对齐 Web 端的功能按钮 */
    private lateinit var featAuto: TextView
    private lateinit var featSearch: TextView
    private lateinit var featThink: TextView
    private lateinit var featMode: TextView
    private lateinit var featAttach: TextView
    private lateinit var featureView: TextView

    /** 自动朗读：回复完成后本地 TTS 播报，开关状态存本机 */
    private var autoSpeak = false
    private var autoTts: TextToSpeech? = null
    private var pendingAutoText: String? = null
    /** 已朗读过的最后一条正文，避免同一条被本地 render 与 Web 轮询各播一次 */
    private var lastSpokenText: String? = null
    /** 基线所属会话：切换会话时把当前末尾回复记为已读，避免翻开旧会话就念历史 */
    private var lastSpokenSid: String? = null

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

    /** 单个气泡 View 的引用与已渲染内容，用于流式增量局部更新 */
    private class BubbleRef(
        val wrap: View,
        val body: TextView,
        val det: TextView?,
        val proc: TextView?,
        var structKey: String,
        var text: String,
        var process: String,
    )
    /** 按消息索引缓存气泡，避免每个 token 重建整表（重建会让滚动位置乱跳） */
    private val bubbleRefs = mutableListOf<BubbleRef>()
    /** 发消息/切换会话后强制滚到底；普通流式增量则尊重用户当前滚动位置 */
    private var forceScrollNext = true
    /** 从后台返回时强制重建气泡，避免后台历史变化后旧索引/旧引用残留 */
    private var forceRebuildNext = false

    /** Web 端新增回复的朗读回调（Activity 模式下注册，后台也保持） */
    private val remoteAssistantListener: (String) -> Unit = { text ->
        // 按正文去重：Web 端轮询与本地 render 不会重复播同一条
        if (autoSpeak) speakAutoIfNew(text)
    }

    /** 跨端配置变化（工作模式名/预设）后刷新功能面板，不触发消息列表重建 */
    private val configChangeListener: () -> Unit = {
        post {
            if (isAttachedToWindow && ::featMode.isInitialized) refreshFeatures()
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
    private fun followStreamScroll() {
        val now = System.currentTimeMillis()
        if (now - lastAutoScrollMs < autoScrollIntervalMs) return
        lastAutoScrollMs = now
        chatScroll.post {
            val maxScroll = (chatList.height - chatScroll.height +
                chatScroll.paddingTop + chatScroll.paddingBottom).coerceAtLeast(0)
            if (maxScroll > chatScroll.scrollY) {
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

    init {
        isClickable = true
        setBackgroundColor(Color.parseColor("#F21A1A1A"))
        visibility = GONE
        autoSpeak = context.getSharedPreferences("ai_console", Context.MODE_PRIVATE)
            .getBoolean("auto_speak", false)
        // Activity 模式由本层负责朗读；旧悬浮窗模式不注册，避免两个 overlay 重复播报
        if (!manageWindow) AiConsoleClient.addRemoteAssistantListener(remoteAssistantListener)
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
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#FF242424"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerTitle = TextView(context).apply {
            text = "会话标题"
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 副标题改为实时 token 计数（输入/输出/命中缓存），不再是 http 地址
        headerSub = TextView(context).apply {
            text = "token · 等待输入"
            textSize = 11f
            setTextColor(Color.parseColor("#FF9EFFB0"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(headerTitle)
        titleCol.addView(headerSub)
        header.addView(titleCol)

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

        header.addView(headerChip("会话", "#FFFFCC88") { showSessionPicker() })

        header.addView(headerChip("工具箱", "#FF9EFFB0") {
            runCatching {
                val i = android.content.Intent(context, CodexHubActivity::class.java)
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
        })

        settingsToggle = headerChip("设置", "#FF88CCFF") { toggleSettings() }
        header.addView(settingsToggle)

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
        featSearch = featureButton().apply { setOnClickListener { toggleQuick("search") } }
        featThink = featureButton().apply { setOnClickListener { toggleQuick("think") } }
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
        root.addView(settingsPanel)

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
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 只暂停流式跟随，不在 DOWN 阶段改焦点/键盘：
                        // 键盘收起会立刻改变布局高度，导致 UP 时点击坐标漂移，气泡点不中。
                        followIntent = false
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
        root.addView(inputRow)

        addView(root)
        refreshFeatures()
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
        val count = AiConsoleClient.attachmentCount()
        featAttach.text = if (count > 0) "📎 添加文件\n$count 个待发送" else "📎 添加文件"
        val summary = AiConsoleClient.attachmentSummary()
        featureView.text = if (summary.isBlank())
            "快速开关仅本会话生效 · 服务器地址在工具箱设置"
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
            lastSpokenText = null
        } else {
            // 刚打开时，把当前末尾回复当作「已播过」的基线，避免翻开旧会话就念历史
            lastSpokenSid = AiConsoleClient.currentSessionId()
            lastSpokenText = AiConsoleClient.messages.lastOrNull {
                it.role == "assistant" && !it.failed && it.text.isNotBlank()
            }?.text
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
                        return@post
                    }
                    val ready = autoTts ?: return@post
                    val lang = ready.setLanguage(Locale.CHINA)
                    if (lang == TextToSpeech.LANG_MISSING_DATA ||
                        lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Toast.makeText(context, "系统缺少中文语音包，自动朗读无法使用",
                            Toast.LENGTH_LONG).show()
                        pendingAutoText = null
                        return@post
                    }
                    pendingAutoText?.let { ready.speak(it, TextToSpeech.QUEUE_FLUSH, null, "auto") }
                    pendingAutoText = null
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "语音引擎不可用：${e.message}", Toast.LENGTH_LONG).show()
            pendingAutoText = null
        }
    }

    /** 只朗读尚未播过的新回复：本地流式完成与 Web 轮询都走这里，按正文去重 */
    private fun speakAutoIfNew(text: String) {
        if (!autoSpeak || text.isBlank()) return
        if (text == lastSpokenText) return
        lastSpokenText = text
        speakAuto(text)
    }

    /** 会话变化时把已有末尾回复设为基线：翻开旧会话不念历史，新会话空历史则基线为空 */
    private fun syncSpokenBaseline(sid: String?) {
        if (sid == lastSpokenSid) return
        lastSpokenSid = sid
        lastSpokenText = messagesSnapshot.lastOrNull {
            it.role == "assistant" && !it.failed && it.text.isNotBlank()
        }?.text
    }

    /** 回复完成后自动朗读（本地 TTS，不上传音频） */
    private fun speakAuto(text: String) {
        if (!autoSpeak || text.isBlank()) return
        pendingAutoText = text
        val engine = autoTts
        if (engine == null) ensureAutoTts()
        else engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "auto")
    }

    private fun stopAutoTts() {
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
            m.role == hit.role && hit.terms.all { t -> m.text.contains(t, true) }
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
        if (manageWindow) addStandaloneWindow()
        visibility = VISIBLE
        // 后台期间消息可能已被 Web 端改写，旧操作卡的 index/text 不可信，回前台先收掉
        dismissInlinePanel()
        forceRebuildNext = true
        headerSub.text = AiConsoleClient.usageHeaderText()
        render()
        AiConsoleClient.addListener(renderListener)
        AiConsoleClient.startPollingIfRunning()
        focusInputBox()
    }

    fun hide() {
        if (manageWindow) removeStandaloneWindow()
        visibility = GONE
        AiConsoleClient.removeListener(renderListener)
        // 这里不再 stopAutoTts()：切后台只收 UI，自动朗读应继续播完当前回复。
        // 历史轮询也不在这里停，进程存活期间继续同步 Web 端消息。
    }

    /** Activity 真正销毁时释放 TTS、监听器与操作卡 */
    fun release() {
        AiConsoleClient.removeListener(renderListener)
        AiConsoleClient.removeRemoteAssistantListener(remoteAssistantListener)
        AiConsoleClient.removeConfigListener(configChangeListener)
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
     * 回复里提到其它工作模式时（典型：「请切换到手机控制模式」），
     * 直接在气泡下方给出切换按钮，用户不用再去设置面板里找。
     * 排除当前模式，避免显示无意义的「切到当前模式」。
     */
    private fun suggestedModes(text: String): List<String> {
        // 流式输出期间不插按钮：否则关键词刚出现时 structKey 变化会整表重建，造成跳动。
        // 运行结束后状态刷新会自然触发一次重建，按钮随最终文本出现。
        if (AiConsoleClient.running || text.isBlank()) return emptyList()
        // 默认名 + 用户自定义名都能触发（如把自定义改名成「写周报」，回复里提到也识别）
        val hints = AiConsoleClient.MODE_DEFAULTS.keys.associateWith { key ->
            listOf(AiConsoleClient.modeLabel(key), AiConsoleClient.MODE_DEFAULTS[key]?.name.orEmpty())
                .filter { it.isNotBlank() }
        }
        val current = AiConsoleClient.currentMode()
        return AiConsoleClient.MODE_DEFAULTS.keys.filter { key ->
            key != current && hints[key]?.any { text.contains(it) } == true
        }
    }

    /** 决定气泡 View 结构是否需要新建的签名：正文/过程文本的变化不在此列 */
    private fun structKeyOf(m: ChatMessage): String =
        "${m.role}|${m.failed}|${m.process.isNotBlank()}|${suggestedModes(m.text).joinToString(",")}"

    private fun render() {
        messagesSnapshot = AiConsoleClient.messages
        // 副标题显示实时 token 计数与命中率，对齐网页端 chip
        headerSub.text = AiConsoleClient.usageHeaderText()
        refreshFeatures()
        val sidRaw = AiConsoleClient.currentSessionId()
        syncSpokenBaseline(sidRaw)
        // 运行结束时后端 auto_title 会总结并改写会话标题，强制重拉一次让标题跟上
        val runningNow = AiConsoleClient.running
        val justFinished = headerWasRunning && !runningNow
        headerWasRunning = runningNow
        if (autoSpeak && !runningNow) {
            // 直接以"当前末尾回复正文"为准：本地刚播完、后台 Web 新增、回到前台补播都覆盖。
            // justFinished 边缘检测在"切到别的页面再回来"时会漏事件，是开关"看起来没生效"的主因。
            messagesSnapshot.lastOrNull {
                it.role == "assistant" && !it.failed && it.text.isNotBlank()
            }?.let { speakAutoIfNew(it.text) }
        }
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
            if (forceNow) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
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
                    structKey = structKeyOf(msg),
                    text = msg.text,
                    process = msg.process,
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
                ref.body.text = m.text.ifBlank { if (m.role == "user") "" else "…" }
                ref.text = m.text
            }
            if (m.process != ref.process) {
                ref.proc?.text = m.process
                ref.det?.text = "▸ 过程（${m.process.lineCount()} 步）"
                ref.process = m.process
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
        val anchorWrap = (body?.parent as? View) ?: chatList.getChildAt(index) ?: return
        val key = index to msg.text
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
            index,
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
        val isUser = msg.role == "user"
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val body = TextView(context).apply {
            text = msg.text.ifBlank { if (isUser) "" else "…" }
            textSize = 13f
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#FFE8E8E8"))
            setPadding(dp(11), dp(8), dp(11), dp(8))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (isUser) Color.parseColor("#FF355C7D") else Color.parseColor("#FF333333"))
            }
            // 点击呼出操作卡；长按则交给系统做文字选择/复制，不再拦截。
            // 空占位（"…"）既不可点也不可选，避免误触。
            if (msg.text.isNotBlank()) {
                // setTextIsSelectable(true) 内部会置 focusable=true，这是长按出现选择手柄
                // 和系统「复制」菜单的前提。之前为修点击漂移强行关成 false，选字就失效了。
                // 现在恢复焦点能力，改由 bubbleTouching 延迟收键盘来避免 UP 坐标漂移。
                setTextIsSelectable(true)
                isClickable = true
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> bubbleTouching = true
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> bubbleTouching = false
                    }
                    false // 不消费，长按选字/点击照常交给 TextView
                }
                setOnClickListener { v -> showMessageActions(v, msg, index) }
            }
        }
        wrap.addView(body)

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

    private fun String.lineCount(): Int = if (isBlank()) 0 else split('\n').size

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
