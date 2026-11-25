package ru.queuejw.lumetro.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale

/**
 * 点击消息气泡后弹出的「内嵌操作卡」。
 *
 * 以普通 View 插入被点击气泡的正下方，随会话列表绘制/滚动，避免 PopupWindow
 * 在悬浮窗/Activity 混合坐标下测量时机导致位置算错而不可见。
 *
 * 标题带 ☝🏻 指向上方那条消息，不重复正文。八宫格（4×2）：
 * 复制/转发/收藏/删除/第五格/朗读/翻译/搜一搜。
 * - 收藏：点击后变「✅ 已收藏」，再点取消（服务端全局配置，跨端同步）。
 * - 朗读：点击后变「▶ 停止」，再点停止朗读；读完自动复原。
 * - 第五格按角色区分：用户消息是「修改」（Web 端 DeepSeek 式编辑，点后在气泡下
 *   展开行内编辑框，确认后由上层删除本轮及之后、用新文本重新发送）；助手消息
 *   保持「引用」（把原文按引用格式填入输入框，可继续编辑后发送）。
 * 长按消息由 TextView 自身处理文字选择/复制，与此卡无关。
 */
class MessageActionSheet(
    private val ctx: Context,
    private val text: String,
    private val index: Int,
    private val onEdit: (newText: String, turnId: String) -> Unit,
    private val onQuote: (quoted: String) -> Unit,
    private val onDismiss: () -> Unit,
    /** 后端稳定消息 id / 所属会话 / 角色 / 轮次 id；空串表示本地乐观消息 */
    private val msgId: String = "",
    private val sid: String = "",
    private val role: String = "",
    private val turnId: String = "",
    private val ts: Long = 0L,
) {
    private var tts: TextToSpeech? = null
    private var speakCell: TextView? = null
    private var favCell: TextView? = null
    private var cardView: LinearLayout? = null
    private var editBox: LinearLayout? = null
    private var editInput: EditText? = null
    private var isSpeaking = false
    private var isFavorited = if (msgId.isNotBlank())
        AiConsoleClient.isFavoriteId(msgId) else AiConsoleClient.favorites().contains(text)

    private data class Action(val label: String, val icon: String)
    private val columns = 4
    private val items = listOf(
        Action("复制", "📋"), Action("转发", "↗"), Action("收藏", "⭐"), Action("删除", "🗑"),
        // Web 端语义：用户消息可「修改」，助手消息只做「引用」回填输入框。
        (if (role == "user") Action("修改", "✏️") else Action("引用", "❝")),
        Action("朗读", "🔊"), Action("翻译", "🌐"), Action("搜一搜", "🔍"),
    )

    /** 构造并返回操作卡 View；调用方负责把它插入气泡下方。 */
    fun build(): View {
        AiConsoleClient.addConfigListener(configListener)
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF2E3A38.toInt())
                val r = dp(14).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, r, r, r, r)
                setStroke(dp(1), 0xFF587268.toInt())
            }
        }

        // 标题行 + 关闭
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(2))
        }
        val title = TextView(ctx).apply {
            text = "消息操作 ☝🏻"
            textSize = 12f
            setTextColor(0xFFBBE0CC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 13f
            setTextColor(0xFF99BBAE.toInt())
            setPadding(dp(10), dp(2), dp(2), dp(2))
            setOnClickListener { onDismiss() }
        }
        titleRow.addView(title)
        titleRow.addView(close)
        card.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 4×2 八宫格：横向 LinearLayout 手动排布，避开 ScrollView 内嵌 GridView 的测量问题
        for (row in 0 until (items.size + columns - 1) / columns) {
            val rowLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            for (col in 0 until columns) {
                val pos = row * columns + col
                if (pos >= items.size) break
                val a = items[pos]
                val cell = TextView(ctx).apply {
                    text = cellText(a)
                    gravity = Gravity.CENTER
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = GradientDrawable().apply {
                        setColor(0xFF3B4A46.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                        if (col > 0) leftMargin = dp(6)
                    }
                    setOnClickListener { onAction(a.label) }
                }
                when (a.label) {
                    "朗读" -> speakCell = cell
                    "收藏" -> favCell = cell
                }
                rowLayout.addView(cell)
            }
            card.addView(rowLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        card.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            )
        })
        cardView = card
        return card
    }

    /** 朗读中/已收藏时单元格显示不同文案与图标 */
    private fun cellText(a: Action): String = when {
        a.label == "朗读" && isSpeaking -> "▶\n停止"
        a.label == "收藏" && isFavorited -> "✅\n已收藏"
        else -> "${a.icon}\n${a.label}"
    }

    private fun refreshCells() {
        items.forEach { a ->
            val v = when (a.label) {
                "朗读" -> speakCell
                "收藏" -> favCell
                else -> null
            }
            v?.text = cellText(a)
        }
    }

    /** Web 端改了收藏时同步本卡状态（轮询拉到新 config rev 会触发） */
    private val configListener: () -> Unit = {
        val remote = if (msgId.isNotBlank()) AiConsoleClient.isFavoriteId(msgId)
                     else AiConsoleClient.favorites().contains(text)
        if (remote != isFavorited) {
            isFavorited = remote
            favCell?.post { refreshCells() }
        }
    }

    /** 会话层收起卡片时调用：停止朗读、释放 TTS */
    fun release() {
        isSpeaking = false
        closeEdit()
        cardView = null
        AiConsoleClient.removeConfigListener(configListener)
        shutdownTts()
    }

    private fun onAction(action: String) {
        when (action) {
            // 朗读/收藏需要卡片留在原地展示切换后的状态，不能收起
            "朗读" -> { toggleSpeak(); return }
            "收藏" -> { toggleFavorite(); return }
            // 修改要在卡片内展开行内编辑框，也不能先收起
            "修改" -> { beginEdit(); return }
            // 引用需要收起卡片并把内容交给输入框；焦点/键盘由上层恢复
            "引用" -> { onDismiss(); onQuote(quotedText()); return }
        }
        // 其余动作先收起卡再执行：否则「删除」触发的重绘会把旧卡按旧 index 恢复回来。
        onDismiss()
        when (action) {
            "复制" -> {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI消息", text))
                toast("已复制")
            }
            "转发" -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    ctx.startActivity(Intent.createChooser(send, "转发消息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast("没有可转发的应用") }
            }
            "删除" -> {
                if (index < 0) toast("无法定位该消息")
                else CodexUi.confirm(
                    ctx,
                    "删除这一轮？",
                    "会删除这条消息所在的整轮对话（含助手回答），两端同步消失；服务端会自动备份。",
                ) {
                    AiConsoleClient.deleteMessage(index) { ok, msg ->
                        toast(if (ok) msg else "删除失败：$msg")
                    }
                }
            }
            // 用百度翻译（国内可访问），auto→zh 自动识别源语言
            "翻译" -> openUrl("https://fanyi.baidu.com/#auto/zh/" + URLEncoder.encode(text, "UTF-8"))
            "搜一搜" -> openUrl("https://www.baidu.com/s?wd=" + URLEncoder.encode(text, "UTF-8"))
        }
    }

    /** 助手消息「引用」：按行加 > 生成引用块，填入输入框后用户可继续追问。 */
    private fun quotedText(): String {
        val plain = text.trim()
        if (plain.isEmpty()) return ""
        val quoted = plain.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) ">" else "> $line"
        }
        return "$quoted\n\n"
    }

    /**
     * 对齐 Web 端 DeepSeek 式「修改」：只有已落库的用户消息可编辑。
     * 在卡片内展开输入框（预填原文），确认后由上层删除本轮及之后并重新发送。
     */
    private fun beginEdit() {
        if (role != "user") {
            toast("只能修改你发送的消息")
            return
        }
        if (turnId.isBlank()) {
            toast("这一轮尚未保存，无法修改")
            return
        }
        if (AiConsoleClient.running) {
            toast("已有任务在运行，请等待结束")
            return
        }
        val card = cardView ?: return
        // 同一张卡重复点修改：已展开则聚焦输入框，不重复添加
        editBox?.let {
            editInput?.requestFocus()
            return
        }
        // 去掉末尾可能存在的 📎 附件标记行，保留纯问题文本
        val plain = text.replace(Regex("\\n?📎[^\\n]*$"), "").trim()
        val density = ctx.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val input = EditText(ctx).apply {
            setText(plain)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF1B1B1B.toInt()); cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0xFF587268.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tip = TextView(ctx).apply {
            text = "修改后，本轮回答及其之后的所有对话会被移除（服务端自动备份），再用新内容重新生成。"
            textSize = 10.5f
            setTextColor(0xFFBBD0C4.toInt())
            setPadding(dp(2), dp(4), dp(2), dp(2))
        }
        val cancel = TextView(ctx).apply {
            text = "取消"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFD0D0D0.toInt())
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = GradientDrawable().apply {
                setColor(0xFF3B3B3B.toInt()); cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { closeEdit() }
        }
        val send = TextView(ctx).apply {
            text = "发送修改"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = GradientDrawable().apply {
                setColor(0xFF3B7D6B.toInt()); cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
            setOnClickListener {
                val newText = input.text.toString().trim()
                if (newText.isBlank()) { toast("内容为空，未做修改"); return@setOnClickListener }
                if (newText == plain) { toast("内容没有变化"); return@setOnClickListener }
                closeEdit()
                CodexUi.confirm(
                    ctx,
                    "修改并重新生成？",
                    "将删除这一轮及其之后的所有对话（服务端自动备份），再用新内容重新生成。",
                    positive = "确认修改"
                ) { onEdit(newText, turnId) }
            }
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
            addView(cancel)
            addView(send)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF23302C.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF3B7D6B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            addView(input)
            addView(tip)
            addView(btnRow)
        }
        card.addView(box)
        editBox = box
        editInput = input
        input.requestFocus()
    }

    /** 收起行内编辑框 */
    private fun closeEdit() {
        editBox?.let { runCatching { cardView?.removeView(it) } }
        editBox = null
        editInput = null
    }

    private fun toggleFavorite() {
        if (msgId.isBlank()) {
            // 本地乐观消息（还没落库）无法生成跨端稳定 id，退回本地收藏
            if (isFavorited) {
                AiConsoleClient.removeFavorite(text)
                isFavorited = false
                toast("已取消收藏（本地）")
            } else {
                AiConsoleClient.addFavorite(text)
                isFavorited = true
                toast("已收藏到本地，可在工具箱→收藏查看")
            }
            refreshCells()
            return
        }
        // 有稳定 id：写服务端全局配置，Web 端刷新后立即可见
        val item = AiConsoleClient.FavoriteItem(
            msgId, sid, role, turnId, text, if (ts > 0) ts else System.currentTimeMillis()
        )
        if (isFavorited) {
            isFavorited = false
            refreshCells()
            AiConsoleClient.removeFavoriteRemote(msgId) { ok, msg ->
                if (!ok) toast("取消收藏失败：$msg")
                else toast("已取消收藏，Web 端同步")
            }
        } else {
            isFavorited = true
            refreshCells()
            AiConsoleClient.toggleFavoriteRemote(item) { ok, msg ->
                if (!ok) toast("收藏失败：$msg")
                else toast("已收藏，Web 端同步")
            }
        }
    }

    private fun toggleSpeak() {
        if (isSpeaking) {
            isSpeaking = false
            shutdownTts()
            refreshCells()
            toast("已停止朗读")
        } else {
            startSpeak(text)
        }
    }

    private fun startSpeak(content: String) {
        if (content.isBlank()) { toast("空消息"); return }
        isSpeaking = true
        refreshCells()
        toast("开始朗读，再点一次可停止")
        val appCtx = ctx.applicationContext
        tts = TextToSpeech(appCtx) { status ->
            // 用户可能在引擎初始化期间点了停止
            val current = tts
            if (!isSpeaking || current == null) { shutdownTts(); return@TextToSpeech }
            if (status == TextToSpeech.SUCCESS) {
                val r = current.setLanguage(Locale.CHINA)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    toast("当前系统不支持中文朗读")
                    finishSpeak()
                } else {
                    current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) = finishSpeak()
                        override fun onError(id: String?) = finishSpeak()
                    })
                    current.speak(content, TextToSpeech.QUEUE_FLUSH, null, "msg")
                }
            } else {
                toast("语音引擎初始化失败")
                finishSpeak()
            }
        }
    }

    /** TTS 回调可能不在 UI 线程，回到主线程更新图标 */
    private fun finishSpeak() {
        val cell = speakCell
        if (cell != null) cell.post {
            isSpeaking = false
            refreshCells()
            shutdownTts()
        } else {
            isSpeaking = false
            shutdownTts()
        }
    }

    private fun shutdownTts() {
        val t = tts
        tts = null
        runCatching { t?.stop(); t?.shutdown() }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure { toast("没有可用的浏览器") }
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
