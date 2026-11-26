package ru.queuejw.lumetro.ai

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.ai.CodexUi.dp
import ru.queuejw.lumetro.ai.pages.*

/**
 * Web 端功能中心的原生复刻。按 Web 侧栏分组（工作区 / 工具箱 / 系统）改成两段式导航：
 * 上排是分组，下排是该分组的页面；手机上不用在十几个标签里横滑找入口。
 */
class CodexHubActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var groupBar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var sessionLabel: TextView

    private val groups = linkedMapOf(
        "工作区" to listOf("对话", "终端", "文件"),
        "工具箱" to listOf("抓包", "APK", "技能", "手机", "提示词"),
        "系统" to listOf("搜索", "会话", "审查", "运行", "设置", "收藏", "说明", "诊断")
    )
    private var currentGroup = "工具箱"
    private var current = "抓包"
    /** 从 AI 窗侧边抽屉直达指定工具页 */
    companion object { const val EXTRA_PAGE = "page" }
    private var uploadTarget: ((Uri) -> Unit)? = null
    private val pickUpload = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadTarget?.invoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiConsoleClient.init(this)
        intent.getStringExtra(EXTRA_PAGE)?.takeIf { it.isNotBlank() }?.let { target ->
            groups.entries.firstOrNull { it.value.contains(target) }?.let { entry ->
                currentGroup = entry.key
                current = target
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CodexUi.color("#F21A1A1A"))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(this@CodexHubActivity, 14), dp(this@CodexHubActivity, 12),
                dp(this@CodexHubActivity, 14), dp(this@CodexHubActivity, 8))
            addView(TextView(this@CodexHubActivity).apply {
                text = "Codex 工具箱"
                textSize = 17f; setTextColor(Color.WHITE)
            })
            sessionLabel = TextView(this@CodexHubActivity).apply {
                textSize = 10.5f; setTextColor(CodexUi.color("#FF9AA5A0"))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(this@CodexHubActivity, 10), 0, dp(this@CodexHubActivity, 8), 0)
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openChat() }
            }
            addView(sessionLabel)
            addView(TextView(this@CodexHubActivity).apply {
                text = "返回对话"
                textSize = 11f; setTextColor(CodexUi.color("#FF88CCFF"))
                setOnClickListener { openChat() }
            })
        })

        groupBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(this@CodexHubActivity, 10), 0, dp(this@CodexHubActivity, 10), dp(this@CodexHubActivity, 4))
        }
        root.addView(groupBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(this@CodexHubActivity, 10), dp(this@CodexHubActivity, 4),
                dp(this@CodexHubActivity, 10), dp(this@CodexHubActivity, 8))
        }
        root.addView(HorizontalScrollView(this).apply { addView(tabBar) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
        setContentView(root)

        groups.keys.forEach { name ->
            groupBar.addView(TextView(this).apply {
                text = name
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(this@CodexHubActivity, 10), dp(this@CodexHubActivity, 7),
                    dp(this@CodexHubActivity, 10), dp(this@CodexHubActivity, 7))
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(this@CodexHubActivity, 4)
                }
                setOnClickListener {
                    currentGroup = name
                    current = groups[name]?.firstOrNull() ?: return@setOnClickListener
                    renderNav(); select(current)
                }
            })
        }
        renderNav()
        updateSessionLabel()
        select(current)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun updateSessionLabel() {
        val sid = AiConsoleClient.currentSessionId()
        sessionLabel.text = if (sid.isNullOrBlank()) "尚未建立会话" else "会话 $sid"
    }

    private fun renderNav() {
        for (i in 0 until groupBar.childCount) {
            val tv = groupBar.getChildAt(i) as TextView
            val on = tv.text == currentGroup
            tv.setTextColor(CodexUi.color(if (on) "#FFFFFFFF" else "#FF9AA5A0"))
            tv.background = CodexUi.roundedBg(this,
                if (on) CodexUi.ACCENT else "#FF242424", 8)
        }
        tabBar.removeAllViews()
        groups[currentGroup]?.forEach { name ->
            tabBar.addView(TextView(this).apply {
                text = name
                textSize = 11.5f
                setPadding(dp(this@CodexHubActivity, 11), dp(this@CodexHubActivity, 6),
                    dp(this@CodexHubActivity, 11), dp(this@CodexHubActivity, 6))
                setOnClickListener { current = name; renderNav(); select(name) }
            })
        }
        for (i in 0 until tabBar.childCount) {
            val tv = tabBar.getChildAt(i) as TextView
            val on = tv.text == current
            tv.setTextColor(CodexUi.color(if (on) "#FFFFFFFF" else "#FFB0B0B0"))
            tv.background = CodexUi.roundedBg(this,
                if (on) "#FF355E52" else "#FF222222", 7)
        }
    }

    private fun select(name: String) {
        updateSessionLabel()
        content.removeAllViews()
        val view = try {
        when (name) {
            "对话" -> { openChat(); return }
            "终端" -> terminalPage(this, ::toast) { cmd -> runInChat(cmd, false) }
            "文件" -> filesPage(this, ::toast) {
                uploadTarget = { uri -> uploadToWorkspace(uri) }
                pickUpload.launch("*/*")
            }
            "抓包" -> capturePage(this, ::toast) { runInChat(it, false) }
            "APK" -> apkPage(this, ::toast) { runInChat(it, false) }
            "技能" -> skillsPage(this, ::toast)
            "手机" -> devicePage(this, ::toast)
            "提示词" -> promptsPage(this, ::toast)
            "搜索" -> searchPage(this, ::toast) { sid, snippet -> switchAndOpen(sid, snippet) }
            "会话" -> sessionsPage(this, ::toast) { openChat() }
            "审查" -> contextPage(this, ::toast)
            "运行" -> runPage(this, ::toast)
            "设置" -> settingsPage(this, ::toast)
            "收藏" -> favoritesPage(this, ::toast)
            "说明" -> guidePage(this, ::toast)
            "诊断" -> diagPage(this, ::toast)
            else -> diagPage(this, ::toast)
        }
        } catch (e: Exception) {
            // 页面构建失败时留在工具箱并显示原因，不要静默返回对话页
            android.util.Log.e("CodexHub", "页面构建失败: $name", e)
            toast("「$name」加载失败：${e.message}")
            val err = TextView(this).apply {
                text = "「$name」加载失败\n${e.javaClass.simpleName}: ${e.message}\n\n这是页面构建错误，与 CodexWeb 连接无关。可切换标签重试；若反复出现，请把上面的异常文本反馈给开发者。"
                textSize = 13f
                setTextColor(CodexUi.color("#FFFF9A9A"))
                setPadding(CodexUi.dp(this@CodexHubActivity, 12), CodexUi.dp(this@CodexHubActivity, 16),
                    CodexUi.dp(this@CodexHubActivity, 12), CodexUi.dp(this@CodexHubActivity, 12))
            }
            content.addView(err, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        content.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun openChat() {
        startActivity(Intent(this, AiConsoleActivity::class.java))
        finish()
    }

    /** Web 的「填入对话」：不自动发送，用户确认后再回车 */
    private fun runInChat(text: String, autoSend: Boolean) {
        if (text.isBlank()) return
        val intent = Intent(this, AiConsoleActivity::class.java)
            .putExtra(AiConsoleActivity.EXTRA_PREFILL, text)
            .putExtra(AiConsoleActivity.EXTRA_PREFILL_ONLY, !autoSend)
        startActivity(intent)
    }

    /**
     * 全局搜索命中：切到该会话后带搜索词进入对话页，对话页会滚动定位并高亮命中消息。
     * [terms] 是用户输入的关键词（空格分隔），不是把片段塞进输入框。
     */
    private fun switchAndOpen(sid: String, terms: String) {
        AiConsoleClient.switchSession(sid, null) { ok, msg ->
            if (!ok) { toast(msg); return@switchSession }
            val intent = Intent(this, AiConsoleActivity::class.java)
            if (terms.isNotBlank()) {
                intent.putExtra(AiConsoleActivity.EXTRA_JUMP_TERMS, terms)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun uploadToWorkspace(uri: Uri) {
        val sid = AiConsoleClient.currentSessionId()
        if (sid.isNullOrBlank()) { toast("请先建立会话"); return }
        val name = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: "upload-${System.currentTimeMillis()}"
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
        if (bytes == null) { toast("读取文件失败"); return }
        if (bytes.size > 12 * 1024 * 1024) { toast("文件超过 12MB，请用附件发送"); return }
        toast("上传 $name …")
        val path = "/api/files/upload?s=$sid&p=&name=${AiConsoleClient.urlEncode(name)}"
        AiConsoleClient.uploadFile(path, bytes) { ok, msg ->
            toast(if (ok) "已上传：$name" else "上传失败：$msg")
            select(current)
        }
    }
}
