package ru.queuejw.lumetro.ai.pages

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.queuejw.lumetro.ai.AiConsoleClient
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.actionCard
import ru.queuejw.lumetro.ai.CodexUi.button
import ru.queuejw.lumetro.ai.CodexUi.checkRow
import ru.queuejw.lumetro.ai.CodexUi.divider
import ru.queuejw.lumetro.ai.CodexUi.edit
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.label
import ru.queuejw.lumetro.ai.CodexUi.mono
import ru.queuejw.lumetro.ai.CodexUi.row
import ru.queuejw.lumetro.ai.CodexUi.root
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.segment
import ru.queuejw.lumetro.ai.CodexUi.status
import ru.queuejw.lumetro.ai.CodexUi.tinyButton
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.CodexUi.toggleButton
import ru.queuejw.lumetro.ai.PhoneControl

private fun showJson(ctx: Context, toast: ToastFn, r: JSONObject?) {
    if (r == null) { toast(AiConsoleClient.apiError()); return }
    androidx.appcompat.app.AlertDialog.Builder(ctx)
        .setTitle("结果")
        .setMessage(r.toString(2).take(4000))
        .setPositiveButton("关闭", null)
        .show()
}

private fun join(base: String, name: String) = if (base.isBlank()) name else "$base/$name"

/** 设备动作共用一个作用域，避免每次点击都创建无生命周期的 CoroutineScope */
private val deviceActionScope = kotlinx.coroutines.MainScope()

/** 终端 / 工具环境共用：异步执行并存 pcap/安装包这类长任务，输出实时刷新 */
private fun runLong(
    ctx: Context,
    cmd: String,
    outBox: TextView,
    onDone: (String) -> Unit = {}
) {
    val body = JSONObject()
        .put("cmd", cmd)
        .put("session", AiConsoleClient.currentSessionId().orEmpty())
        .put("timeout", 600)
        .put("async", true).toString()
    AiConsoleClient.api("POST", "/api/exec", body) { r ->
        val job = r?.optString("job").orEmpty()
        if (job.isBlank()) {
            outBox.text = "启动失败：${AiConsoleClient.apiError()}"
            return@api
        }
        fun tick(tries: Int) {
            // 页面销毁或轮询超过 5 分钟后停止，避免脱离窗口的 View 继续发请求
            if (!outBox.isAttachedToWindow) return
            if (tries > 600) {
                outBox.text = "查询超时（已等待约 5 分钟），任务可能仍在运行"
                return
            }
            AiConsoleClient.api("GET", "/api/exec/state?job=$job", null) { st ->
                if (!outBox.isAttachedToWindow) return@api
                if (st == null) { outBox.text = "查询失败：${AiConsoleClient.apiError()}"; return@api }
                val text = (st.optString("out") + st.optString("err")).trim()
                outBox.text = if (st.optBoolean("done"))
                    "[完成 · 退出码 ${st.optInt("code")} · ${st.optLong("ms")}ms]\n${text.take(6000)}"
                else "[执行中… ${st.optLong("ms")}ms]\n${text.take(6000)}"
                if (st.optBoolean("done")) onDone(st.optString("out")) else outBox.postDelayed({ tick(tries + 1) }, 500)
            }
        }
        outBox.text = "任务已提交，等待输出…"
        tick(0)
    }
}

// ------------------------------------------------------------------ 设置

fun settingsPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("设置", "Web 端共享配置（收藏/能力/记忆）+ 启动器性能选项")

    val pollStatus = TextView(ctx).apply {
        textSize = 11f; setTextColor(CodexUi.color("#FF9EFFB0"))
    }
    section("Web 消息同步（性能）") {
        hint("先用轻量签名判断历史/会话状态是否变化，变化才拉全量；同时跟随网页端改的模式与快速开关。本地任务运行时自动跳过。")
        val opts = listOf(
            "" to "关闭", "1500" to "1.5 秒", "3000" to "3 秒",
            "5000" to "5 秒", "10000" to "10 秒"
        )
        val selected = if (AiConsoleClient.historyPollingEnabled())
            AiConsoleClient.historyPollInterval().toString() else ""
        fun refreshStatus() {
            pollStatus.text = if (!AiConsoleClient.historyPollingEnabled())
                "当前：已关闭（不会自动同步 Web 新消息）"
            else "当前：每 ${AiConsoleClient.historyPollInterval() / 1000} 秒检查一次历史；无新消息时只有一次轻量 GET"
        }
        segment(opts, selected) { value ->
            if (value.isEmpty()) AiConsoleClient.setHistoryPolling(false)
            else AiConsoleClient.setHistoryPolling(true, value.toLong())
            refreshStatus()
            toast(if (value.isEmpty()) "已关闭 Web 同步轮询" else "轮询间隔已更新")
        }
        addView(pollStatus)
        refreshStatus()
    }

    section("上下文审查记录") {
        hint("关闭后不再保存每次对话的 prompt / 用量；已有记录仍可在审查页查看。")
        val box = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FF9EFFB0")) }
        fun refresh() {
            box.text = if (AiConsoleClient.contextRecordEnabled()) "当前：正在记录" else "当前：已暂停记录"
        }
        row {
            toggleButton("审查记录", AiConsoleClient.contextRecordEnabled()) {
                AiConsoleClient.setContextRecord(!AiConsoleClient.contextRecordEnabled())
                refresh()
            }
        }
        addView(box); refresh()
    }

    section("CodexWeb 服务器") {
        hint("带凭证的完整地址：http://<IP>:8123/?cxkey=<凭证>")
        val urlEdit = edit("http://127.0.0.1:8123/?cxkey=...", AiConsoleClient.currentInputUrl())
        row {
            button("保存并重连") {
                val raw = urlEdit.text.toString().trim()
                if (AiConsoleClient.setServer(ctx, raw) == null) toast("地址无效")
                else {
                    toast("已保存，正在连接…")
                    AiConsoleClient.checkServer { ok, msg -> toast(msg) }
                }
            }
            button("测试连接") { AiConsoleClient.checkServer { ok, msg -> toast(msg) } }
        }
        status("当前：${AiConsoleClient.serverLabel()}")
    }

    section("模型与 API（写后端 config）") {
        hint("API Key 与 Base URL 写在设备端 CodexWeb 后端，Key 不会被回显")
        val urlEdit2 = edit("https://api.deepseek.com", "https://api.deepseek.com")
        val modelEdit = edit("模型名", "deepseek-flash")
        val keyEdit = edit("API Key（sk-...）", "")
        row {
            button("DeepSeek") { urlEdit2.setText("https://api.deepseek.com"); modelEdit.setText("deepseek-flash") }
            button("OpenAI") { urlEdit2.setText("https://api.openai.com/v1"); modelEdit.setText("gpt-4o-mini") }
        }
        row {
            button("保存配置") {
                val body = JSONObject()
                    .put("key", keyEdit.text.toString().trim())
                    .put("url", urlEdit2.text.toString().trim())
                    .put("model", modelEdit.text.toString().trim()).toString()
                AiConsoleClient.api("POST", "/api/setup", body) {
                    toast(if (it != null) "配置已保存" else AiConsoleClient.apiError())
                }
            }
            button("查看状态") { AiConsoleClient.api("GET", "/api/api-status") { showJson(ctx, toast, it) } }
        }
    }

    // 与 Web 设置页「完全体能力」对齐：记忆 / 上下文压缩 / 自动标题 / 智能模式提醒
    section("完全体能力（与 Web 端共享）") {
        hint("与网页端设置页同一份 caps.json；关闭某项即回到极简直连。改动立即生效于两端下一次对话。")
        val capBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(capBox)
        val capsInfo = listOf(
            "memory" to "长期记忆 · 把本会话 MEMORY.md 注入请求",
            "compact" to "上下文压缩 · 历史过长时压成摘要",
            "auto_title" to "自动标题 · 首轮后自动命名会话",
            "smart_mode" to "智能模式提醒 · 纯对话中识别工具意图"
        )
        fun reload() {
            AiConsoleClient.api("GET", "/api/capabilities") { resp ->
                capBox.removeAllViews()
                capsInfo.forEach { (key, label) ->
                    val on = resp?.optBoolean(key, false) ?: false
                    capBox.row {
                        toggleButton(if (on) "$label · 开" else "$label · 关", on) {
                            val patch = JSONObject().put(key, !on)
                            AiConsoleClient.api("POST", "/api/capabilities", patch.toString()) { r ->
                                toast(if (r != null) "能力已更新，Web 端同步" else AiConsoleClient.apiError())
                                reload()
                            }
                        }
                    }
                }
            }
        }
        reload()
        // MEMORY.md 编辑器（与 Web 设置页同一份文件）
        val memEdit = edit("每行一条长期记忆…", "", singleLine = false)
        row {
            button("读取记忆") {
                val sid = AiConsoleClient.currentSessionId()
                if (sid.isNullOrBlank()) toast("请先建立会话")
                else AiConsoleClient.api("GET", "/api/memory?s=${AiConsoleClient.urlEncode(sid)}") { r ->
                    memEdit.setText(r?.optString("text").orEmpty())
                    toast(if (r != null) "已读取 MEMORY.md" else AiConsoleClient.apiError())
                }
            }
            button("保存记忆") {
                val sid = AiConsoleClient.currentSessionId()
                if (sid.isNullOrBlank()) toast("请先建立会话")
                else AiConsoleClient.api(
                    "POST", "/api/memory?s=${AiConsoleClient.urlEncode(sid)}",
                    JSONObject().put("text", memEdit.text.toString()).toString()
                ) { toast(if (it != null) "记忆已保存，下次对话生效" else AiConsoleClient.apiError()) }
            }
        }
        addView(memEdit)
    }

    section("启动凭证 / 原生能力") {
        row {
            button("启动 URL") { AiConsoleClient.api("GET", "/api/launch-url") { showJson(ctx, toast, it) } }
            button("能力配置") { AiConsoleClient.api("GET", "/api/capabilities") { showJson(ctx, toast, it) } }
            button("存储限制") { AiConsoleClient.api("GET", "/api/guide") { showJson(ctx, toast, it) } }
        }
    }
}

// ------------------------------------------------------------------ 文件

fun filesPage(ctx: Context, toast: ToastFn, onPick: () -> Unit): View = root(ctx) {
    title("文件管理", "浏览会话工作区；支持打开、编辑、删除与上传")

    var path = ""
    val crumbs = TextView(ctx).apply {
        textSize = 11f; setTextColor(CodexUi.color("#FF88CCFF"))
        setPadding(0, 0, 0, CodexUi.dp(ctx, 6))
    }
    addView(crumbs)
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    addView(listBox)

    // 提前创建编辑器对象：列表点击回调里也要填入路径，不能等到声明之后才可见
    fun styledEdit(hintText: String, singleLine: Boolean) = EditText(ctx).apply {
        hint = hintText
        setTextColor(Color.WHITE)
        setHintTextColor(CodexUi.color("#AA9AA0A8"))
        textSize = 13f
        isSingleLine = singleLine
        minHeight = if (singleLine) 0 else CodexUi.dp(ctx, 90)
        background = CodexUi.roundedBg(ctx, "#FF1B1B1B", 7)
        setPadding(CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 5) }
    }
    val fileEdit = styledEdit("文件路径（点上方文件名自动填入）", true)
    val fileText = styledEdit("文件内容", false)

    fun reload() {
        crumbs.text = "工作区：/" + path.ifBlank { "" }
        AiConsoleClient.api("GET",
            "/api/files/list?s=${AiConsoleClient.currentSessionId().orEmpty()}&p=${AiConsoleClient.urlEncode(path)}"
        ) { r ->
            listBox.removeAllViews()
            val arr = r?.optJSONArray("entries") ?: JSONArray()
            if (arr.length() == 0) listBox.hint("空目录")
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val name = e.optString("name")
                val dir = e.optBoolean("dir")
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 6),
                        CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 6))
                    background = CodexUi.roundedBg(ctx, "#FF242424", 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 5) }
                }
                card.addView(TextView(ctx).apply {
                    text = (if (dir) "目录  " else "文件  ") + name +
                        if (dir) "" else "  · ${e.optLong("size")} B"
                    textSize = 12f; setTextColor(Color.WHITE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    setOnClickListener {
                        if (dir) { path = join(path, name); reload() }
                        else fileEdit.setText(join(path, name)); fileText.setText("")
                    }
                })
                card.row {
                    if (!dir) tinyButton("打开") {
                        AiConsoleClient.api("GET",
                            "/api/files/read?s=${AiConsoleClient.currentSessionId().orEmpty()}&p=${AiConsoleClient.urlEncode(join(path, name))}"
                        ) { rr ->
                            fileEdit.setText(join(path, name))
                            fileText.setText(rr?.optString("text").orEmpty())
                            if (rr?.optBoolean("binary") == true) toast("二进制文件，请在网页端下载")
                        }
                    }
                    tinyButton("删除") {
                        val target = join(path, name)
                        CodexUi.confirm(ctx, "删除文件？", "将永久删除会话工作区里的 $target，无法撤销。") {
                            AiConsoleClient.api("POST", "/api/files/delete",
                                JSONObject().put("s", AiConsoleClient.currentSessionId().orEmpty())
                                    .put("p", target).toString()) {
                                toast(if (it != null) "已删除" else AiConsoleClient.apiError()); reload()
                            }
                        }
                    }
                }
                listBox.addView(card)
            }
        }
    }

    section("目录操作") {
        row {
            button("上级") {
                path = path.split("/").dropLast(1).joinToString("/")
                reload()
            }
            button("刷新") { reload() }
            button("新建目录") {
                androidx.appcompat.app.AlertDialog.Builder(ctx).apply {
                    setTitle("目录名")
                    val input = EditText(ctx)
                    setView(input)
                    setPositiveButton("创建") { _, _ ->
                        val name = input.text.toString().trim().trim('/')
                        if (name.isNotBlank()) AiConsoleClient.api("POST", "/api/files/mkdir",
                            JSONObject().put("s", AiConsoleClient.currentSessionId().orEmpty())
                                .put("p", join(path, name)).toString()) { reload() }
                    }
                    setNegativeButton("取消", null)
                    show()
                }
            }
            button("上传") { onPick() }
        }
    }

    section("文本编辑器") {
        addView(fileEdit)
        addView(fileText)
        row {
            button("读取") {
                AiConsoleClient.api("GET",
                    "/api/files/read?s=${AiConsoleClient.currentSessionId().orEmpty()}&p=${AiConsoleClient.urlEncode(fileEdit.text.toString().trim())}"
                ) { r -> fileText.setText(r?.optString("text").orEmpty()) }
            }
            button("保存") {
                AiConsoleClient.api("POST", "/api/files/write",
                    JSONObject().put("s", AiConsoleClient.currentSessionId().orEmpty())
                        .put("p", fileEdit.text.toString().trim())
                        .put("text", fileText.text.toString()).toString()) {
                    toast(if (it != null) "已保存" else AiConsoleClient.apiError())
                }
            }
            button("删除") {
                val target = fileEdit.text.toString().trim()
                if (target.isBlank()) { toast("请先填写文件路径"); return@button }
                CodexUi.confirm(ctx, "删除文件？", "将永久删除会话工作区里的 $target，无法撤销。") {
                    AiConsoleClient.api("POST", "/api/files/delete",
                        JSONObject().put("s", AiConsoleClient.currentSessionId().orEmpty())
                            .put("p", target).toString()) {
                        toast("已删除"); reload()
                    }
                }
            }
        }
    }
    reload()
}

// ------------------------------------------------------------ 抓包 / APK

private const val ENSURE_JAVA =
    "command -v java >/dev/null 2>&1 || apk add --no-cache openjdk17-jre-headless >/dev/null 2>&1 || true"
private const val APKTOOL_INSTALL = ENSURE_JAVA +
    "; mkdir -p /opt/apktool && cp /codexweb/tools/apktool.jar /opt/apktool/apktool.jar" +
    " && echo '#!/bin/sh' > /usr/local/bin/apktool" +
    " && echo 'exec java -jar /opt/apktool/apktool.jar \"\$@\"' >> /usr/local/bin/apktool" +
    " && chmod +x /usr/local/bin/apktool && echo '--- apktool ---' && apktool --version"
private const val JADX_INSTALL = ENSURE_JAVA +
    "; mkdir -p /opt/jadx && cp /codexweb/tools/jadx.jar /opt/jadx/jadx.jar" +
    " && echo '#!/bin/sh' > /usr/local/bin/jadx" +
    " && echo 'exec java -cp /opt/jadx/jadx.jar jadx.cli.JadxCLI \"\$@\"' >> /usr/local/bin/jadx" +
    " && chmod +x /usr/local/bin/jadx && echo '--- jadx ---' && jadx --version"
private const val PROBE_CMD = "for c in tcpdump dig java apktool jadx; do printf \"%-10s \" \"\$c\";" +
    " command -v \"\$c\" 2>/dev/null || echo \"-\"; done"

private data class ToolDef(val id: String, val name: String, val desc: String,
                           val install: String, val uninstall: String)

private val CAPTURE_TOOLS = listOf(
    ToolDef("tcpdump", "tcpdump", "命令行抓包，导出 pcap", "apk add --no-cache tcpdump", "apk del tcpdump"),
    ToolDef("dig", "dig", "DNS 查询与诊断", "apk add --no-cache bind-tools", "apk del bind-tools")
)
private val APK_TOOLS = listOf(
    ToolDef("java", "java (JRE)", "apktool / jadx 的运行依赖",
        "apk add --no-cache openjdk17-jre-headless", "apk del openjdk17-jre-headless"),
    ToolDef("apktool", "apktool", "APK 反编译 / 回编译（内置 jar，离线可装）",
        APKTOOL_INSTALL, "rm -f /usr/local/bin/apktool /opt/apktool/apktool.jar"),
    ToolDef("jadx", "jadx", "DEX 反编译成 Java 源码（内置 jar，离线可装）",
        JADX_INSTALL, "rm -f /usr/local/bin/jadx /opt/jadx/jadx.jar")
)

private fun toolPage(
    ctx: Context, toast: ToastFn,
    titleText: String, desc: String, tools: List<ToolDef>,
    promptCards: List<Triple<String, String, String>>,
    onPrompt: (String) -> Unit
): View = root(ctx) {
    title(titleText, desc)
    val statusBox = TextView(ctx).apply {
        textSize = 11f; setTextColor(CodexUi.color("#FFD0D8D0"))
    }
    val summaryBox = TextView(ctx).apply {
        textSize = 11.5f; setTextColor(CodexUi.color("#FF9EFFB0"))
    }
    // checks = 勾选状态（缺失项默认勾选）；states = 探测结果，null 表示尚未探测
    val checks = tools.map { it.id to true }.toMap().toMutableMap()
    val states = mutableMapOf<String, Boolean>()
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun renderChecks() {
        listBox.removeAllViews()
        tools.forEach { t ->
            val have = states[t.id]
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(CodexUi.dp(ctx, 6), CodexUi.dp(ctx, 5), CodexUi.dp(ctx, 6), CodexUi.dp(ctx, 5))
                background = CodexUi.roundedBg(ctx, "#FF1E1E1E", 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 5) }
            }
            val box = android.widget.CheckBox(ctx).apply {
                isChecked = checks[t.id] == true
                setOnCheckedChangeListener { _, v -> checks[t.id] = v }
            }
            row.addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = t.name; textSize = 12f; setTextColor(Color.WHITE)
                })
                addView(TextView(ctx).apply {
                    text = t.desc; textSize = 10.5f; setTextColor(CodexUi.color("#AA9AA0A8"))
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply {
                text = when (have) {
                    null -> "？ 未探测"
                    true -> "✓ 可用"
                    false -> "✗ 缺失"
                }
                textSize = 11f
                setTextColor(CodexUi.color(when (have) {
                    null -> "#AA9AA0A8"
                    true -> "#FF9EFFB0"
                    false -> "#FFFF9A9A"
                }))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            listBox.addView(row)
        }
        if (states.isEmpty()) {
            summaryBox.text = "尚未探测"
        } else {
            val have = tools.filter { states[it.id] == true }.map { it.name }
            val miss = tools.filter { states[it.id] == false }.map { it.name }
            summaryBox.text = "可用：${have.joinToString("、").ifBlank { "无" }} · " +
                "缺失：${miss.joinToString("、").ifBlank { "无" }}"
        }
    }

    fun doProbe() {
        statusBox.text = "探测中…"
        runLong(ctx, PROBE_CMD, statusBox) { out ->
            tools.forEach { t ->
                val m = Regex("^${t.id}\\s+(\\S+)", RegexOption.MULTILINE).find(out)
                val ok = m != null && m.groupValues[1] != "-"
                states[t.id] = ok
                checks[t.id] = !ok  // 缺失项默认勾选
            }
            renderChecks()
        }
    }

    fun applyTools(install: Boolean) {
        val selected = tools.filter { checks[it.id] == true }
        if (selected.isEmpty()) { toast("请先勾选工具"); return }
        val parts = mutableListOf<String>()
        selected.forEach { t ->
            parts.add("echo '=== ${if (install) "安装" else "卸载"} ${t.name} ==='")
            parts.add(if (install) t.install else t.uninstall)
        }
        parts.add("echo '=== FINAL PROBE ==='")
        parts.add(PROBE_CMD)
        runLong(ctx, parts.joinToString("\n"), statusBox) { out ->
            val tail = out.lastIndexOf("=== FINAL PROBE ===")
            val probe = if (tail >= 0) out.substring(tail) else out
            tools.forEach { t ->
                val m = Regex("^${t.id}\\s+(\\S+)", RegexOption.MULTILINE).find(probe)
                val ok = m != null && m.groupValues[1] != "-"
                states[t.id] = ok
                checks[t.id] = !ok
            }
            renderChecks()
        }
    }

    section("工具环境") {
        hint("探测后缺失项默认勾选；安装/卸载在 proot 沙箱执行，输出实时刷新。")
        addView(listBox)
        addView(summaryBox)
        row {
            button("探测环境") { doProbe() }
            button("安装勾选") { applyTools(true) }
            button("卸载勾选") {
                CodexUi.confirm(ctx, "卸载勾选的工具？",
                    "将从 proot 沙箱中移除已勾选的工具，之后需要重新安装才能使用。",
                    positive = "确认卸载") { applyTools(false) }
            }
        }
        addView(statusBox)
    }
    renderChecks()
    doProbe()  // 进入页面自动探测，用户无需先手点即可看到可用/缺失

    section("通过对话使用") {
        promptCards.forEach { (t, d, p) -> actionCard(t, d, "填入对话") { onPrompt(p) } }
    }
    hint("仅用于分析你自己的设备或已获授权的目标。")
}

fun capturePage(ctx: Context, toast: ToastFn, onPrompt: (String) -> Unit): View = toolPage(
    ctx, toast,
    "网络抓包", "在 proot/Alpine 里按需安装抓包工具，或把分析任务交给 Codex。",
    CAPTURE_TOOLS,
    listOf(
        Triple("抓包命令", "安装 tcpdump 后抓包并存到工作区，交给 Codex 解析。",
            "请给出在 proot/Alpine 里用 tcpdump 对默认网卡抓包、保存 pcap 到当前工作区的完整命令，并说明各参数含义。"),
        Triple("HTTP(S) 分析", "解析自有应用的 HTTP 日志，定位接口与异常状态。",
            "请写一个纯 Python 标准库的脚本，解析工作区里的 HTTP 请求日志，统计状态码与耗时分布，并输出便于阅读的表格。"),
        Triple("证书与 HTTPS", "理解证书校验与本地调试代理的风险边界。",
            "请解释 HTTPS 证书校验原理，以及本地调试代理为什么需要安装根证书；说明其中的安全风险与适用边界。"),
        Triple("pcap 解析", "把 pcap 放进工作区，让 Codex 写离线解析器。",
            "工作区里如果放了 pcap 文件，请用 Python 写一个最小解析器，读出数据链路层类型与前面若干数据包的协议摘要。")
    ), onPrompt
)

fun apkPage(ctx: Context, toast: ToastFn, onPrompt: (String) -> Unit): View = toolPage(
    ctx, toast,
    "APK 工具", "探测 Java / apktool / jadx；缺失工具可在 guest 内离线安装。",
    APK_TOOLS,
    listOf(
        Triple("工具链探测", "检查当前可用工具并给出安装方案。",
            "请根据终端探测结果，说明当前环境可用的 APK 工具，并为缺失工具给出 Alpine apk 的安装命令。"),
        Triple("反编译", "解包、Manifest、资源、dex 的静态分析步骤。",
            "请把工作区里的 APK 当作待分析样本，给出一套完整的静态分析步骤：解包、看 AndroidManifest、反编译 dex、定位敏感权限与组件，并写出对应命令。"),
        Triple("构建与签名", "生成 keystore、对齐、签名与验证。",
            "请写一个脚本，用 keytool 生成调试 keystore，再用 zipalign -p 4 和 apksigner 完成 APK 对齐、签名与验证；说明每一步的作用。"),
        Triple("清单分析", "解析包名、权限、组件与 SDK 版本。",
            "请写一个 Python 脚本，从工作区的 APK 中解析 AndroidManifest，列出包名、版本、权限、Activity/Service/Receiver 与最低 SDK 版本。")
    ), onPrompt
)

// ------------------------------------------------------------------ 终端

fun terminalPage(ctx: Context, toast: ToastFn, onPrompt: (String) -> Unit): View = root(ctx) {
    title("终端", "在 proot 沙箱内执行命令；长任务后台运行，输出实时刷新")
    val outBox = TextView(ctx).apply {
        textSize = 11f; setTextColor(CodexUi.color("#FFD0D8D0"))
        setPadding(CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 6),
            CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 6))
        background = CodexUi.roundedBg(ctx, "#FF141414", 6)
    }
    val cmdEdit = edit("命令，如：uname -a", "")
    section("执行") {
        row {
            button("运行") {
                val cmd = cmdEdit.text.toString().trim()
                if (cmd.isBlank()) return@button
                if (cmd == "clear") { outBox.text = ""; return@button }
                outBox.text = "$ $cmd\n"
                runLong(ctx, cmd, outBox)
            }
            button("交给 AI") {
                val cmd = cmdEdit.text.toString().trim()
                if (cmd.isNotBlank()) onPrompt("解释这条命令并给出更安全/更完整的写法：$cmd")
            }
            button("清空") { outBox.text = "" }
        }
        addView(outBox)
    }
    section("快捷命令") {
        val chips = listOf(
            "系统信息" to "uname -a && cat /etc/os-release | head -3",
            "磁盘/文件" to "ls -la && df -h .",
            "已装工具" to "for c in apk busybox wget python3 tcpdump dig apktool jadx java; do printf '%-10s ' \"\$c\"; command -v \$c || echo '-'; done",
            "装 tcpdump" to "apk add --no-cache tcpdump",
            "装 apktool" to APKTOOL_INSTALL,
            "装 jadx" to JADX_INSTALL
        )
        chips.forEach { (label, cmd) ->
            row {
                button(label) { cmdEdit.setText(cmd); outBox.text = "$ $cmd\n"; runLong(ctx, cmd, outBox) }
            }
        }
    }
    hint("命令只运行在 App 自带的 proot 沙箱内，不会获得设备真实 root 权限。")
}

// ------------------------------------------------------------------ 技能

fun skillsPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("模块 DIY", "自然语言生成手机技能草稿，检查后保存为白名单动作")
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    addView(listBox)

    var draft: JSONObject? = null
    fun reload() {
        AiConsoleClient.api("GET", "/api/skills") { r ->
            listBox.removeAllViews()
            val arr = r?.optJSONArray("skills") ?: JSONArray()
            if (arr.length() == 0) listBox.hint("还没有技能模块")
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 7),
                        CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 7))
                    background = CodexUi.roundedBg(ctx, "#FF262626", 9)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 5) }
                }
                card.addView(TextView(ctx).apply {
                    text = (if (s.optBoolean("enabled")) "[已启用] " else "[已停用] ") + s.optString("name")
                    textSize = 12f; setTextColor(Color.WHITE)
                })
                card.addView(TextView(ctx).apply {
                    text = s.optString("desc").take(180)
                    textSize = 10.5f; setTextColor(CodexUi.color("#FFCCCCCC"))
                })
                card.row {
                    tinyButton(if (s.optBoolean("enabled")) "停用" else "启用") {
                        AiConsoleClient.api("POST", "/api/skills/toggle",
                            JSONObject().put("id", s.optString("id"))
                                .put("enabled", !s.optBoolean("enabled")).toString()) { reload() }
                    }
                    tinyButton("删除") {
                        CodexUi.confirm(ctx, "删除技能模块？",
                            "模块「${s.optString("name")}」将被删除，手机控制模式不能再调用它。") {
                            AiConsoleClient.api("POST", "/api/skills/delete",
                                JSONObject().put("id", s.optString("id")).toString()) {
                                toast("已删除"); reload()
                            }
                        }
                    }
                }
                listBox.addView(card)
            }
        }
    }

    // 草稿编辑器：生成后把 name/desc/steps 填进输入框，用户能直观检查并修改，
    // 保存时从输入框读回，而不是保存内存里那份看不到的草稿。
    val draftBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    var nameEdit: EditText? = null
    var descEdit: EditText? = null
    val stepRows = mutableListOf<Triple<EditText, EditText, EditText>>()

    fun addStep(step: JSONObject?) {
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, CodexUi.dp(ctx, 3), 0, CodexUi.dp(ctx, 3))
        }
        fun field(hintText: String, value: String): EditText = EditText(ctx).apply {
            hint = hintText
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(CodexUi.color("#AA9AA0A8"))
            textSize = 10.5f
            background = CodexUi.roundedBg(ctx, "#FF1B1B1B", 6)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = CodexUi.dp(ctx, 3) }
        }
        val actionEdit = field("动作", step?.optString("action") ?: "tap")
        val argEdit = field("参数", step?.optString("arg") ?: "")
        val labelEdit = field("说明", step?.optString("label") ?: "")
        rowView.addView(actionEdit)
        rowView.addView(argEdit)
        rowView.addView(labelEdit)
        draftBox.addView(rowView)
        stepRows.add(Triple(actionEdit, argEdit, labelEdit))
    }

    fun renderEditor(skill: JSONObject?) {
        draftBox.removeAllViews()
        stepRows.clear()
        if (skill == null) {
            draftBox.hint("尚未生成草稿。点上方「AI 生成」后，草稿会显示在这里供检查修改。")
            return
        }
        nameEdit = draftBox.edit("模块名称", skill.optString("name"))
        descEdit = draftBox.edit("用途说明", skill.optString("desc"))
        draftBox.label("步骤（动作 / 参数 / 说明）")
        val arr = skill.optJSONArray("steps") ?: JSONArray()
        if (arr.length() == 0) addStep(null) else
            for (i in 0 until arr.length()) addStep(arr.optJSONObject(i))
        draftBox.hint("参数里的 {关键词} 会在执行时由 AI 填入；动作只能用白名单里的值。")
    }

    section("生成草稿") {
        val specEdit = edit("描述模块，可用 {关键词} 表示运行时参数", "", singleLine = false)
        row {
            button("AI 生成") {
                val spec = specEdit.text.toString().trim()
                if (spec.isBlank()) { toast("请先描述"); return@button }
                toast("正在生成…")
                AiConsoleClient.api("POST", "/api/skills/draft",
                    JSONObject().put("spec", spec).toString()) { r ->
                    val skill = r?.optJSONObject("skill")
                    draft = skill
                    if (skill != null) {
                        renderEditor(skill)
                        toast("草稿已生成，请检查步骤和参数后保存")
                    } else toast(AiConsoleClient.apiError())
                }
            }
            button("加一步") { addStep(null) }
        }
        addView(draftBox)
        renderEditor(null)
        row {
            button("保存草稿") {
                val name = nameEdit?.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) { toast("请先生成草稿"); return@button }
                val steps = JSONArray()
                stepRows.forEach { (a, g, l) ->
                    steps.put(JSONObject()
                        .put("action", a.text.toString().trim())
                        .put("arg", g.text.toString().trim())
                        .put("label", l.text.toString().trim()))
                }
                val skill = JSONObject()
                    .put("name", name)
                    .put("desc", descEdit?.text?.toString()?.trim().orEmpty())
                    .put("steps", steps)
                AiConsoleClient.api("POST", "/api/skills/save",
                    JSONObject().put("skill", skill).toString()) {
                    toast(if (it != null) "已保存模块" else AiConsoleClient.apiError()); reload()
                }
            }
            button("刷新列表") { reload() }
        }
    }
    reload()
}

// ------------------------------------------------------------------ 手机控制

fun devicePage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("手机控制", "Shizuku 授权 + 白名单动作手动执行；与 Web 端同一套动作")
    val statusBox = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FFD0D8D0")) }
    val outBox = TextView(ctx).apply {
        text = "（尚无输出）"
        textSize = 11f
        setTextColor(CodexUi.color("#FFD0D8D0"))
    }
    section("授权状态") {
        fun refresh() {
            val s = PhoneControl.status(ctx)
            statusBox.text = when {
                s.optBoolean("ok") -> "已就绪 · Shizuku 已授权（API ${s.optInt("serverVersion")}）"
                !s.optBoolean("installed") -> "未检测到 Shizuku，请先安装并启动 Shizuku App"
                !s.optBoolean("binder") -> "Shizuku 服务未连接，请在 Shizuku App 中启动服务"
                !s.optBoolean("attached") -> "握手未完成，请彻底退出本 App 再重开"
                else -> "尚未授权，点下方「请求授权」并在系统弹窗中允许"
            } + if (s.has("error")) "\n诊断：${s.optString("error")}" else ""
        }
        row {
            button("刷新状态") { refresh() }
            button("打开 Shizuku") {
                val r = PhoneControl.openShizuku(ctx)
                if (!r.optBoolean("ok")) toast(r.optString("error"))
            }
            button("请求授权") {
                val r = PhoneControl.request()
                toast(if (r.optBoolean("sent")) "授权请求已送达" else r.optString("error"))
                outBox.postDelayed({ refresh() }, 1200)
            }
        }
        addView(statusBox); refresh()
    }

    val prefs = ctx.getSharedPreferences("ai_console", Context.MODE_PRIVATE)
    var caps = try {
        JSONArray(prefs.getString("phone_caps", """["input"]"""))
    } catch (e: Exception) { JSONArray().put("input") }
    fun saveCaps() { prefs.edit().putString("phone_caps", caps.toString()).apply() }
    fun has(cap: String) = (0 until caps.length()).any { caps.optString(it) == cap }

    section("允许的能力") {
        listOf("input" to "输入手势（点击/滑动/输入/按键）",
            "apps" to "应用与网址（打开应用、网址、拨号盘）",
            "system" to "系统开关（Wi-Fi/蓝牙/飞行模式/亮度/熄屏）",
            "screen" to "截图").forEach { (id, label) ->
            checkRow(label, "", has(id)) { on ->
                val next = JSONArray()
                for (i in 0 until caps.length()) if (caps.optString(i) != id) next.put(caps.optString(i))
                if (on) next.put(id)
                caps = next; saveCaps()
            }
        }
    }

    section("执行动作") {
        // 结果框放在动作列表上方，点完立刻能看到反馈（之前在列表底部，视觉上像点击无效）
        addView(outBox)
        val groups = mapOf(
            "input" to listOf("tap" to "x y", "swipe" to "x1 y1 x2 y2 [ms]",
                "text" to "文本", "key" to "HOME/BACK/ENTER/..."),
            "apps" to listOf("openapp" to "包名", "appsearch" to "B站 周杰伦",
                "openurl" to "https://...", "dial" to "号码"),
            "system" to listOf("wifi" to "on/off", "bluetooth" to "on/off",
                "airplane" to "on/off", "brightness" to "1-255", "timeout" to "never/毫秒"),
            "screen" to listOf("screenshot" to "")
        )
        groups.forEach { (cap, actions) ->
            if (!has(cap)) return@forEach
            actions.forEach { (action, placeholder) ->
                val argEdit = edit("$action · $placeholder", "")
                row {
                    button("执行 $action") {
                        val arg = argEdit.text.toString()
                        val cmd = PhoneControl.validate(action, arg)
                        if (cmd == null) {
                            outBox.text = "动作或参数不合法：$action $arg"
                            toast("动作或参数不合法：$action $arg")
                            return@button
                        }
                        if (outBox.text.toString().startsWith("执行中")) {
                            toast("上一条命令仍在执行，请稍候")
                            return@button
                        }
                        outBox.text = "执行中：$cmd …"
                        deviceActionScope.launch {
                            val r = PhoneControl.execute(action, arg)
                            if (!outBox.isAttachedToWindow) return@launch
                            outBox.text = if (r.optBoolean("ok")) {
                                "完成：$cmd\n退出码 ${r.optInt("exit")}\n" +
                                    (r.optString("out").ifBlank { "（无标准输出）" }) +
                                    (r.optString("err").takeIf { it.isNotBlank() }?.let { "\n错误输出：$it" } ?: "")
                            } else "失败：${r.optString("error")}"
                            toast(if (r.optBoolean("ok")) "执行完成（退出码 ${r.optInt("exit")}）"
                                  else "执行失败：${r.optString("error")}")
                        }
                    }
                }
            }
        }
    }
    hint("只控制你自己的设备。点击、输入与系统开关会真实作用于当前手机。")
}

// ------------------------------------------------------------------ 诊断

fun diagPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("诊断与说明", "后端运行状态、连接健康与使用说明")
    val box = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FFD0D8D0")) }
    section("运行诊断") {
        row {
            button("加载诊断") {
                AiConsoleClient.api("GET", "/api/diag") {
                    box.text = it?.toString(2)?.take(5000) ?: AiConsoleClient.apiError()
                }
            }
            button("健康检查") { AiConsoleClient.checkServer { ok, msg -> toast(msg) } }
            button("API 状态") { AiConsoleClient.api("GET", "/api/api-status") { showJson(ctx, toast, it) } }
            button("使用说明") {
                AiConsoleClient.api("GET", "/api/guide") { r ->
                    val text = r?.optString("text")?.take(8000)
                        ?: r?.toString(2)?.take(8000) ?: "无"
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("使用说明").setMessage(text)
                        .setPositiveButton("关闭", null).show()
                }
            }
        }
        addView(box)
    }
}

// ------------------------------------------------------------------ 搜索

fun searchPage(ctx: Context, toast: ToastFn, onOpen: (String, String) -> Unit): View = root(ctx) {
    title("全局搜索", "搜索标题、分组与历史消息；与 Web 端同一接口")
    val query = edit("输入关键词（标题 / 分组 / 消息内容）", "")
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    addView(listBox)
    section("搜索") {
        row {
            button("搜索") {
                val q = query.text.toString().trim()
                if (q.isBlank()) { toast("请输入关键词"); return@button }
                AiConsoleClient.api("GET", "/api/sessions/search?q=${AiConsoleClient.urlEncode(q)}") { r ->
                    listBox.removeAllViews()
                    val arr = r?.optJSONArray("results") ?: JSONArray()
                    if (arr.length() == 0) listBox.hint("没有匹配的会话")
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val sid = s.optString("id")
                        val snippet = s.optString("snippet")
                        val card = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 7),
                                CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 7))
                            background = CodexUi.roundedBg(ctx, "#FF262626", 9)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 5) }
                        }
                        card.addView(TextView(ctx).apply {
                            text = s.optString("title").ifBlank { sid }
                            textSize = 12.5f; setTextColor(Color.WHITE)
                        })
                        card.addView(TextView(ctx).apply {
                            text = "命中：${s.optJSONArray("matched_in")?.let { (0 until it.length()).joinToString("、") { i2 -> it.optString(i2) } }} · 分数 ${s.optInt("score")}"
                            textSize = 10.5f; setTextColor(CodexUi.color("#FF9EFFB0"))
                        })
                        if (snippet.isNotBlank()) card.addView(TextView(ctx).apply {
                            text = snippet.take(220)
                            textSize = 11f; setTextColor(CodexUi.color("#FFCCCCCC"))
                            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                        })
                        card.row {
                            tinyButton("打开会话") { onOpen(sid, "") }
                            if (snippet.isNotBlank()) tinyButton("带入片段") { onOpen(sid, snippet) }
                        }
                        listBox.addView(card)
                    }
                }
            }
            button("清空") { listBox.removeAllViews() }
        }
    }
}

// ------------------------------------------------------------------ 说明

fun guidePage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("使用说明", "来自 CodexWeb 后端的内置指南")
    val box = TextView(ctx).apply { textSize = 11.5f; setTextColor(CodexUi.color("#FFD8DEE0")) }
    section("指南") {
        AiConsoleClient.api("GET", "/api/guide") { r ->
            box.text = r?.optString("text")?.take(12000)
                ?: r?.toString(2)?.take(12000)
                ?: AiConsoleClient.apiError()
        }
        addView(box)
    }
}

// ------------------------------------------------------------------ 提示词库

fun promptsPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("提示词与工作模式", "与 Web 端同一套工作模式、自定义配置与系统词注入")

    section("工作模式") {
        val modes = AiConsoleClient.MODE_DEFAULTS.keys.map {
            it to when (it) {
                "full" -> "全能"; "lite" -> "轻量"; "chat" -> "纯对话"
                "device" -> "手机控制"; else -> "自定义"
            }
        }
        segment(modes, AiConsoleClient.currentMode()) { mode ->
            AiConsoleClient.setMode(mode) { ok, msg -> toast(if (ok) "已切换：$msg" else msg) }
        }
        hint("纯对话 / 手机控制模式不注入系统词，避免 token 雪球。")
    }

    section("自定义配置") {
        val cfgBox = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FF9EFFB0")) }
        fun refreshCfg() {
            val c = AiConsoleClient.customCfg()
            cfgBox.text = "当前自定义：推理 ${c.effort} · 思考 ${c.think} · 联网 ${c.search} · 系统词 ${c.prompt}"
        }
        segment(listOf("minimal" to "极简", "low" to "低", "medium" to "中", "high" to "高"),
            AiConsoleClient.customCfg().effort) { effort ->
            val c = AiConsoleClient.customCfg().copy(effort = effort)
            AiConsoleClient.saveCustomCfg(c); refreshCfg()
        }
        row {
            toggleButton("思考指令", AiConsoleClient.customCfg().think) {
                val c = AiConsoleClient.customCfg()
                AiConsoleClient.saveCustomCfg(c.copy(think = !c.think)); refreshCfg()
            }
            toggleButton("联网指令", AiConsoleClient.customCfg().search) {
                val c = AiConsoleClient.customCfg()
                AiConsoleClient.saveCustomCfg(c.copy(search = !c.search)); refreshCfg()
            }
            toggleButton("注入系统词", AiConsoleClient.customCfg().prompt) {
                val c = AiConsoleClient.customCfg()
                AiConsoleClient.saveCustomCfg(c.copy(prompt = !c.prompt)); refreshCfg()
            }
        }
        addView(cfgBox); refreshCfg()
    }

    var presets = AiConsoleClient.promptPresets().toMutableList()
    var active = AiConsoleClient.activePromptId()
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val nameEdit = EditText(ctx)
    val textEdit = EditText(ctx)

    fun renderEditor() {
        val p = presets.firstOrNull { it.id == active } ?: presets.firstOrNull() ?: return
        nameEdit.setText(p.name)
        textEdit.setText(p.text)
    }
    fun renderList() {
        listBox.removeAllViews()
        presets.forEach { p ->
            val tv = TextView(ctx).apply {
                text = (if (p.id == active) "● " else "  ") + p.name
                textSize = 11.5f
                setTextColor(CodexUi.color(if (p.id == active) "#FF88CCFF" else "#FFDDDDDD"))
                setPadding(CodexUi.dp(ctx, 7), CodexUi.dp(ctx, 5),
                    CodexUi.dp(ctx, 7), CodexUi.dp(ctx, 5))
                setOnClickListener { active = p.id; AiConsoleClient.setActivePromptId(p.id); renderList(); renderEditor() }
            }
            listBox.addView(tv)
        }
    }
    renderList(); renderEditor()

    section("系统词预设") {
        addView(listBox)
        row {
            button("新建") {
                val id = "p" + System.currentTimeMillis().toString(36)
                presets.add(AiConsoleClient.PromptPreset(id, "未命名预设", ""))
                active = id
                AiConsoleClient.savePromptPresets(presets)
                AiConsoleClient.setActivePromptId(id)
                renderList(); renderEditor()
            }
            button("保存") {
                val p = presets.firstOrNull { it.id == active } ?: return@button
                val updated = p.copy(name = nameEdit.text.toString().trim().ifBlank { "未命名预设" },
                    text = textEdit.text.toString())
                presets = presets.map { if (it.id == active) updated else it }.toMutableList()
                AiConsoleClient.savePromptPresets(presets)
                renderList(); toast("预设已保存")
            }
            button("删除") {
                if (presets.size <= 1) { toast("至少保留一个预设"); return@button }
                val name = presets.firstOrNull { it.id == active }?.name ?: "当前预设"
                CodexUi.confirm(ctx, "删除预设？", "预设「$name」及其系统词将被删除，无法撤销。") {
                    presets = presets.filter { it.id != active }.toMutableList()
                    active = presets.first().id
                    AiConsoleClient.savePromptPresets(presets)
                    AiConsoleClient.setActivePromptId(active)
                    renderList(); renderEditor()
                }
            }
        }
        nameEdit.hint = "预设名称"
        nameEdit.setTextColor(Color.WHITE)
        nameEdit.setBackgroundColor(CodexUi.color("#FF1B1B1B"))
        textEdit.hint = "系统词 / 角色设定"
        textEdit.setTextColor(Color.WHITE)
        textEdit.setBackgroundColor(CodexUi.color("#FF1B1B1B"))
        textEdit.isSingleLine = false
        textEdit.minHeight = CodexUi.dp(ctx, 90)
        addView(nameEdit); addView(textEdit)
    }

    section("发送时注入") {
        val hintBox = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FF9EFFB0")) }
        fun refreshHint() {
            hintBox.text = when (AiConsoleClient.promptInjectMode()) {
                "global" -> "全局：所有对话都注入当前选中预设"
                "session" -> "本会话：只注入绑定到当前会话的预设"
                else -> "关闭：不注入系统词"
            }
        }
        segment(listOf("off" to "关闭", "global" to "全局", "session" to "本会话"),
            AiConsoleClient.promptInjectMode()) {
            AiConsoleClient.setPromptInjectMode(it); refreshHint()
        }
        row {
            button("绑定到本会话") {
                val sid = AiConsoleClient.currentSessionId()
                if (sid.isNullOrBlank()) { toast("请先建立会话"); return@button }
                AiConsoleClient.bindPromptToSession(sid, active)
                refreshHint(); toast("已绑定到本会话")
            }
            button("复制系统词") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("系统词", textEdit.text.toString()))
                toast("已复制")
            }
        }
        addView(hintBox); refreshHint()
    }
}
