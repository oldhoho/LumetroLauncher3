package ru.queuejw.lumetro.ai.pages

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import ru.queuejw.lumetro.ai.AiConsoleClient
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.button
import ru.queuejw.lumetro.ai.CodexUi.divider
import ru.queuejw.lumetro.ai.CodexUi.edit
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.label
import ru.queuejw.lumetro.ai.CodexUi.mono
import ru.queuejw.lumetro.ai.CodexUi.root
import ru.queuejw.lumetro.ai.CodexUi.row
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.tinyButton
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.CodexUi.toggleButton

typealias ToastFn = (String) -> Unit

/** 给无空格长串（URL/JSON/token）每 chunk 字符插入零宽空格，避免窄屏 TextView 横向溢出不换行 */
private fun wrapLongTokens(s: String, chunk: Int = 48): String {
    val sb = StringBuilder(s.length + s.length / chunk + 4)
    var run = 0
    for (ch in s) {
        sb.append(ch)
        if (ch.isWhitespace()) run = 0 else {
            run++
            if (run >= chunk) { sb.append('\u200B'); run = 0 }
        }
    }
    return sb.toString()
}

/** prompt 折叠预览：对已 wrap 过的文本只做截断，绝不再 wrap 第二次。
 *  二次 wrap 会让 preview 与完整的 fullPrompt 在截断内几乎一致，
 *  展开后「看起来没变化」——这正是审查页展开看不到内容的根因之一。 */
private fun promptPreview(s: String, limit: Int): String =
    if (s.length > limit) s.take(limit) + "\n…（共 ${s.length} 字，点下方展开）" else s

// ------------------------------------------------------------------ 会话

fun sessionsPage(ctx: Context, toast: ToastFn, onSwitch: () -> Unit): View = root(ctx) {
    title("会话管理", "与 Web 端共享会话；置顶、分组、批量操作与切换")

    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    addView(listBox)
    val selected = mutableSetOf<String>()
    var reloadFn: () -> Unit = {}

    fun reload() { reloadFn() }

    fun setMeta(sid: String, meta: JSONObject, done: () -> Unit) {
        AiConsoleClient.api("POST", "/api/sessions/meta",
            JSONObject().put("id", sid).put("meta", meta).toString()) { done() }
    }

    reloadFn = {
        AiConsoleClient.loadSessions { list ->
            listBox.removeAllViews()
            if (list.isEmpty()) listBox.hint("还没有会话，点下方「新建会话」")
            val sorted = list.sortedWith(
                compareByDescending<AiConsoleClient.SessionItem> { it.pinned }
                    .thenByDescending { it.mtime })
            sorted.forEach { s ->
                val cur = AiConsoleClient.currentSessionId() == s.id
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 6),
                        CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 6))
                    background = CodexUi.roundedBg(ctx,
                        if (cur) "#FF2E4A42" else "#FF262626", 9)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = CodexUi.dp(ctx, 5) }
                }
                card.addView(TextView(ctx).apply {
                    text = "${if (s.pinned) "★ " else ""}${if (cur) "● " else ""}${s.title}"
                    textSize = 12.5f; setTextColor(Color.WHITE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                card.addView(TextView(ctx).apply {
                    text = "${s.id} · ${s.mode}" +
                        (if (s.group.isNotBlank()) " · ${s.group}" else "") +
                        " · ${android.text.format.DateFormat.format("MM-dd HH:mm", s.mtime * 1000)}"
                    textSize = 10f; setTextColor(CodexUi.color("#AA9AA0A8"))
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                card.row {
                    tinyButton(if (selected.contains(s.id)) "已选" else "选择") {
                        if (!selected.add(s.id)) selected.remove(s.id)
                        reload()
                    }
                    tinyButton(if (cur) "当前" else "切换") {
                        AiConsoleClient.switchSession(s.id, s.mode) { ok, msg ->
                            toast(msg); if (ok) { reload(); onSwitch() }
                        }
                    }
                    tinyButton(if (s.pinned) "取消置顶" else "置顶") {
                        setMeta(s.id, JSONObject().put("pinned", !s.pinned)) { reload() }
                    }
                    tinyButton("分组") {
                        val input = EditText(ctx).apply { setText(s.group) }
                        androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle("修改分组").setView(input)
                            .setPositiveButton("保存") { _, _ ->
                                setMeta(s.id, JSONObject().put("group", input.text.toString().trim())) { reload() }
                            }.setNegativeButton("取消", null).show()
                    }
                }
                card.row {
                    tinyButton("重命名") { renameDialog(ctx, s.id, s.title, toast) { reload() } }
                    tinyButton("清空") {
                        CodexUi.confirm(ctx, "清空会话历史？",
                            "会话「${s.title}」的全部对话将被清空，服务端会自动备份，Web 端同步。",
                            positive = "确认清空") {
                            AiConsoleClient.api("POST", "/api/sessions/clear",
                                JSONObject().put("id", s.id).toString()) { toast("已清空对话历史"); reload() }
                        }
                    }
                    tinyButton("删除") {
                        CodexUi.confirm(ctx, "删除会话？",
                            "会话「${s.title}」及其全部历史将被删除，服务端会自动备份，Web 端同步。") {
                            AiConsoleClient.api("POST", "/api/sessions/delete",
                                JSONObject().put("id", s.id).toString()) { toast("已删除"); reload() }
                        }
                    }
                }
                listBox.addView(card)
            }
        }
    }

    section("批量操作") {
        hint("勾选后可批量清空/删除；删除前后端会自动备份会话。")
        row {
            button("全选/取消") {
                AiConsoleClient.loadSessions { list ->
                    if (selected.size == list.size) selected.clear()
                    else list.forEach { selected.add(it.id) }
                    reload()
                }
            }
            button("批量清空") {
                if (selected.isEmpty()) { toast("请先勾选会话"); return@button }
                CodexUi.confirm(ctx, "批量清空 ${selected.size} 个会话？",
                    "所选会话的全部对话将被清空，服务端会自动备份，Web 端同步。",
                    positive = "确认清空") {
                    AiConsoleClient.api("POST", "/api/sessions/clear-batch",
                        JSONObject().put("ids", org.json.JSONArray(selected.toList())).toString()) {
                        toast("已批量清空"); selected.clear(); reload()
                    }
                }
            }
            button("批量删除") {
                if (selected.isEmpty()) { toast("请先勾选会话"); return@button }
                CodexUi.confirm(ctx, "批量删除 ${selected.size} 个会话？",
                    "所选会话及其全部历史将被删除，服务端会自动备份，Web 端同步。") {
                    AiConsoleClient.api("POST", "/api/sessions/delete-batch",
                        JSONObject().put("ids", org.json.JSONArray(selected.toList())).toString()) {
                        toast("已批量删除"); selected.clear(); reload()
                    }
                }
            }
        }
    }
    section("操作") {
        row {
            button("新建会话") {
                AiConsoleClient.api("POST", "/api/sessions", "{}") { r ->
                    val id = r?.optString("id").orEmpty()
                    if (id.isNotBlank()) {
                        toast("已创建 $id")
                        AiConsoleClient.switchSession(id) { _, _ -> reload() }
                    } else toast(AiConsoleClient.apiError())
                }
            }
            button("刷新") { reload() }
            button("返回对话") { onSwitch() }
        }
    }
    reload()
}

private fun renameDialog(ctx: Context, sid: String, title: String, toast: ToastFn, done: () -> Unit) {
    val input = EditText(ctx).apply { setText(title); setTextColor(Color.WHITE) }
    android.app.AlertDialog.Builder(ctx)
        .setTitle("重命名会话").setView(input)
        .setPositiveButton("保存") { _, _ ->
            AiConsoleClient.api("POST", "/api/sessions/meta",
                JSONObject().put("id", sid)
                    .put("meta", JSONObject().put("title", input.text.toString().trim())).toString()) {
                toast("已保存"); done()
            }
        }
        .setNegativeButton("取消", null).show()
}

// ------------------------------------------------------------------ 模式

fun modePage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("工作模式", "不同模式决定工具、推理强度和 token 成本")
    val curBox = TextView(ctx).apply { textSize = 12f; setTextColor(CodexUi.color("#FFFFCC88")) }
    addView(curBox)
    fun refresh() {
        val m = AiConsoleClient.currentMode()
        val cfg = AiConsoleClient.effectiveCfg()
        curBox.text = "当前：$m · 推理 ${cfg.effort} · 思考 ${cfg.think} · 联网 ${cfg.search}"
    }
    section("选择模式") {
        AiConsoleClient.MODE_DEFAULTS.keys.forEach { mode ->
            row {
                button(AiConsoleClient.modeLabel(mode)) {
                    AiConsoleClient.setMode(mode) { ok, msg -> toast(if (ok) "已切换：$msg" else msg); refresh() }
                }
                addView(TextView(ctx).apply {
                    text = AiConsoleClient.modeCfg(mode).desc
                    textSize = 11f; setTextColor(CodexUi.color("#FFCCCCCC"))
                })
            }
        }
    }
    section("本会话快速开关") {
        row {
            toggleButton("深度思考", AiConsoleClient.effectiveCfg().think) {
                AiConsoleClient.toggleQuick("think") { ok, msg -> toast(if (ok) msg else msg); refresh() }
            }
            toggleButton("智能搜索", AiConsoleClient.effectiveCfg().search) {
                AiConsoleClient.toggleQuick("search") { ok, msg -> toast(if (ok) msg else msg); refresh() }
            }
        }
        hint("六个模式的名称、说明与全部配置都在「提示词」页调整；秘书模式的长期记忆在「我的秘书」页。")
    }
    section("说明") {
        hint("全能：全部工具 + 高强度推理，能力最强但最费 token")
        hint("轻量：基础工具 + 低推理")
        hint("纯对话：极简直连 API，零系统提示/零工具，最省 token")
        hint("手机控制：从自制技能模块编排流程，确定性执行")
        hint("自定义：在提示词库逐项配置")
    }
    refresh()
}

// ------------------------------------------------------------------ 运行

fun runPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("运行控制与用量", "任务中可立即停止；用量取自实时 SSE 事件")
    val statusBox = TextView(ctx).apply { textSize = 12f; setTextColor(Color.WHITE) }
    val usageBox = TextView(ctx).apply {
        textSize = 12f; setTextColor(CodexUi.color("#FF9EFFB0"))
        setPadding(0, CodexUi.dp(ctx, 6), 0, CodexUi.dp(ctx, 6))
    }
    addView(statusBox); addView(usageBox)
    fun refresh() {
        statusBox.text = "状态：${AiConsoleClient.statusText} · ${AiConsoleClient.currentSessionId() ?: "无会话"}"
        usageBox.text = "本次用量：" + AiConsoleClient.usageSnapshot()
    }
    section("控制") {
        row {
            button("停止当前任务") { AiConsoleClient.stopJob { ok, msg -> toast(msg); refresh() } }
            button("取消设备链") { AiConsoleClient.cancelDevice { ok, msg -> toast(msg) } }
            button("刷新") { refresh() }
        }
    }
    section("最近诊断日志") {
        val logBox = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FFD0D8D0")) }
        addView(logBox)
        AiConsoleClient.api("GET", "/api/diag") { r ->
            logBox.text = r?.toString(2)?.take(3000) ?: "（无诊断数据）"
        }
    }
    refresh()
}

// ------------------------------------------------------------------ 上下文

/** 审查分区：对应 Web 端 renderReview 里的一节 revSec。 */
private data class ReviewBlock(val title: String, val body: String)

/** Web 端 events.data 一般是对象；旧记录/异常形态兜底成文本，保证不丢内容。 */
private fun eventObj(v: Any?): org.json.JSONObject = when (v) {
    is org.json.JSONObject -> v
    is String -> JSONObject().put("text", v)
    else -> JSONObject()
}

/**
 * 把一轮审查记录拆成与 Web 端同口径的分区。
 * 之前启动器只显示 user_input + effective_prompt，用户因此看不到注入指令、模型输出、
 * 工具调用和原始 usage —— 服务端 /api/context 其实把这些都落在 turn 里了。
 */
private fun reviewBlocks(t: org.json.JSONObject): List<ReviewBlock> {
    val out = mutableListOf<ReviewBlock>()
    out += ReviewBlock("用户输入（原文）", t.optString("user_input").ifBlank { "（空）" })

    val atts = t.optJSONArray("attachments")
    if (atts != null && atts.length() > 0) {
        val sb = StringBuilder()
        for (i in 0 until atts.length()) {
            val a = atts.optJSONObject(i) ?: continue
            sb.append("- ").append(a.optString("name")).append(" · ")
                .append(a.optLong("size")).append(" 字节 · ").append(a.optString("path")).append('\n')
        }
        out += ReviewBlock("随消息提交的附件（${atts.length()}）", sb.toString())
    }

    val inj = t.optJSONArray("injections")
    val injSb = StringBuilder()
    if (inj != null) for (i in 0 until inj.length()) {
        val x = inj.optJSONObject(i) ?: continue
        injSb.append('[').append(x.optString("kind")).append("]\n")
            .append(x.optString("text")).append("\n\n")
    }
    out += ReviewBlock("注入指令（${inj?.length() ?: 0} 条）",
        injSb.toString().ifBlank { "（未注入任何指令）" })

    out += ReviewBlock("传入的 Prompt（不含 Codex 内置系统提示/工具定义）",
        t.optString("effective_prompt").ifBlank { "（空）" })

    // events 按类型分组，顺序与 Web 端一致
    val by = linkedMapOf<String, MutableList<org.json.JSONObject>>()
    val ev = t.optJSONArray("events")
    if (ev != null) for (i in 0 until ev.length()) {
        val e = ev.optJSONObject(i) ?: continue
        by.getOrPut(e.optString("event")) { mutableListOf() }.add(eventObj(e.opt("data")))
    }
    fun joined(list: List<org.json.JSONObject>, f: (org.json.JSONObject) -> String) =
        list.joinToString("\n") { f(it) }

    by["message"]?.let { out += ReviewBlock("模型输出", joined(it) { d -> d.optString("text") }) }
    by["reasoning"]?.let { arr ->
        // 后端每帧下发累积文本；Web 端只保留"更完整的最后一版"，避免回复被重复 N 次
        val folded = mutableListOf<String>()
        arr.forEach { d ->
            val s = d.optString("text")
            if (s.isBlank()) return@forEach
            val prev = folded.lastOrNull()
            if (prev != null && s.startsWith(prev)) folded[folded.size - 1] = s else folded.add(s)
        }
        out += ReviewBlock("推理过程", folded.joinToString("\n"))
    }
    by["command"]?.let {
        out += ReviewBlock("命令执行", joined(it) { d ->
            "$ " + d.optString("command") + "\n" + d.optString("output") })
    }
    by["files"]?.let { out += ReviewBlock("文件变更", joined(it) { d -> d.toString(2) }) }
    by["tool"]?.let { out += ReviewBlock("工具调用", joined(it) { d -> d.toString(2) }) }
    by["usage"]?.let { out += ReviewBlock("原始 usage 数据", joined(it) { d -> d.toString(2) }) }
    val res = t.optJSONObject("result")
    if (res != null && !res.optString("error").isNullOrBlank()) {
        out += ReviewBlock("错误", res.optString("error"))
    }
    return out
}

/** 全屏查看器：每个分区一个独立 TextView，外层 ScrollView，不受审查页嵌套布局影响。 */
private fun showReviewDialog(ctx: Context, title: String, blocks: List<ReviewBlock>, toast: ToastFn) {
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(CodexUi.dp(ctx, 11), CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 11), CodexUi.dp(ctx, 8))
    }
    blocks.forEach { b ->
        container.addView(TextView(ctx).apply {
            text = "▸ ${b.title}（${b.body.length} 字）"
            textSize = 12f
            setTextColor(CodexUi.color("#FF88D0FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, CodexUi.dp(ctx, 9), 0, CodexUi.dp(ctx, 3))
        })
        container.addView(TextView(ctx).apply {
            text = b.body.take(20000).ifBlank { "（空）" }
            textSize = 10.5f
            setTextColor(CodexUi.color("#FFE2E2E2"))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 6), CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 6))
            background = CodexUi.roundedBg(ctx, "#FF141414", 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = CodexUi.dp(ctx, 5) }
        })
    }
    val scroller = android.widget.ScrollView(ctx).apply {
        setBackgroundColor(CodexUi.color("#FF1A1A1A"))
        addView(container, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val plain = blocks.joinToString("\n\n") { "【${it.title}】\n${it.body}" }
    val dlg = androidx.appcompat.app.AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(scroller)
        .setPositiveButton("复制全部内容", null)
        .setNegativeButton("关闭", null)
        .create()
    dlg.setOnShowListener {
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("审查记录", plain))
            toast("已复制全部审查内容（${plain.length} 字）")
        }
    }
    dlg.show()
    dlg.window?.setLayout(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
}

fun contextPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("上下文审查", "逐轮查看传给模型的 prompt、注入与 token 构成")
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    listBox.layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    addView(listBox)

    var turnsCache: org.json.JSONArray? = null
    fun reload() {
        val sid = AiConsoleClient.currentSessionId()
        if (sid.isNullOrBlank()) { listBox.removeAllViews(); listBox.hint("尚无会话"); return }
        AiConsoleClient.api("GET", "/api/context?s=$sid") { r ->
            listBox.removeAllViews()
            val turns = r?.optJSONArray("turns")
            turnsCache = turns
            if (turns == null || turns.length() == 0) {
                listBox.hint("本会话还没有审查记录，发送一条消息后即可查看")
                return@api
            }
            var ti = 0L; var tc = 0L; var to = 0L
            val sum = TextView(ctx).apply {
                textSize = 11f; setTextColor(CodexUi.color("#FF9EFFB0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 6) }
            }
            listBox.addView(sum)
            for (i in turns.length() - 1 downTo 0) {
                val t = turns.optJSONObject(i) ?: continue
                val u = t.optJSONObject("usage")
                val inTok = u?.let { it.optLong("input", it.optLong("input_tokens", 0)) } ?: 0
                val cached = u?.let { it.optLong("cached", it.optLong("cached_tokens", 0)) } ?: 0
                val outTok = u?.let { it.optLong("output", it.optLong("output_tokens", 0)) } ?: 0
                ti += inTok; tc += cached; to += outTok
                val tid = t.optString("id")
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
                    text = "#${i + 1} · ${t.optString("mode", "?")} · 输入 $inTok / 输出 $outTok"
                    textSize = 12f; setTextColor(Color.WHITE)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                val userText = wrapLongTokens(t.optString("user_input"))
                card.addView(TextView(ctx).apply {
                    text = if (userText.isBlank()) "（无）" else "用户输入：$userText"
                    textSize = 11f; setTextColor(CodexUi.color("#FFCCCCCC"))
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, CodexUi.dp(ctx, 2), 0, CodexUi.dp(ctx, 2))
                })
                // 与 Web 端同口径：一轮里不只 effective_prompt，还有用户输入、注入指令、
                // 模型输出、推理、工具调用、原始 usage 等分区。全部为查看器内容。
                val blocks = reviewBlocks(t)
                val preview = promptPreview(
                    wrapLongTokens("用户输入：" + t.optString("user_input") + "\n" +
                        "传入 Prompt：" + t.optString("effective_prompt")), 220)
                val promptView = TextView(ctx).apply {
                    text = preview
                    textSize = 10.5f; setTextColor(CodexUi.color("#FFD0D8D0"))
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 6),
                        CodexUi.dp(ctx, 8), CodexUi.dp(ctx, 6))
                    background = CodexUi.roundedBg(ctx, "#FF141414", 6)
                    maxLines = 6; ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = CodexUi.dp(ctx, 4) }
                }
                card.addView(promptView)
                // 卡片里加一行分区清单，让用户一眼看到本轮都有哪些数据（对齐 Web 的审查分区）
                card.addView(TextView(ctx).apply {
                    text = "分区：" + blocks.joinToString(" · ") { it.title.substringBefore("（") } +
                        "（共 ${blocks.sumOf { it.body.length }} 字）"
                    textSize = 10f; setTextColor(CodexUi.color("#FF9EFFB0"))
                    setPadding(0, CodexUi.dp(ctx, 4), 0, 0)
                })
                // 「原地展开」在审查页这条链路上反复失效，改为独立全屏查看器，必定可见可滚动。
                card.row {
                    tinyButton("查看本轮全部审查（${blocks.size} 分区）") {
                        showReviewDialog(ctx, "第 ${i + 1} 轮完整审查", blocks, toast)
                    }
                }
                card.row {
                    tinyButton("删除本轮") {
                        CodexUi.confirm(ctx, "删除这一轮？",
                            "会删除该轮的用户输入与回答，服务端自动备份，Web 端同步。") {
                            AiConsoleClient.api("POST", "/api/context/delete",
                                JSONObject().put("session", sid).put("id", tid).toString()) {
                                toast("已删除"); reload()
                            }
                        }
                    }
                }
                listBox.addView(card)
            }
            val rate = if (ti > 0) (100.0 * tc / ti) else 0.0
            sum.text = "输入 $ti · 缓存 $tc (${String.format("%.1f", rate)}%) · 输出 $to · ${turns.length()} 轮"
        }
    }

    section("记录开关") {
        val box = TextView(ctx).apply { textSize = 11f; setTextColor(CodexUi.color("#FF9EFFB0")) }
        fun refresh() {
            box.text = if (AiConsoleClient.contextRecordEnabled()) "正在记录每次对话" else "已暂停记录，历史记录仍可查看"
        }
        row {
            toggleButton("审查记录", AiConsoleClient.contextRecordEnabled()) {
                AiConsoleClient.setContextRecord(!AiConsoleClient.contextRecordEnabled()); refresh()
            }
        }
        addView(box); refresh()
    }
    section("操作") {
        row {
            button("刷新") { reload() }
            button("复制全部") {
                val turns = turnsCache
                if (turns == null || turns.length() == 0) { toast("暂无记录"); return@button }
                val sb = StringBuilder()
                for (i in 0 until turns.length()) {
                    val t = turns.optJSONObject(i) ?: continue
                    sb.append("===== #${i + 1} [${t.optString("mode")}] =====\n")
                    reviewBlocks(t).forEach { b ->
                        sb.append("【${b.title}】\n").append(b.body).append("\n\n")
                    }
                }
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("上下文审查", sb.toString()))
                toast("已复制")
            }
            button("清空全部") {
                val sid = AiConsoleClient.currentSessionId() ?: return@button
                CodexUi.confirm(ctx, "清空当前会话全部历史？",
                    "审查记录与对话历史是同一份数据；清空后两端都会消失，服务端自动备份。",
                    positive = "确认清空") {
                    AiConsoleClient.api("POST", "/api/context/clear",
                        JSONObject().put("session", sid).toString()) { toast("已清空"); reload() }
                }
            }
        }
    }
    divider()
    reload()
}

// ------------------------------------------------------------------ 收藏

fun favoritesPage(ctx: Context, toast: ToastFn): View = root(ctx) {
    title("我的收藏", "与 CodexWeb 网页端共享（服务端 config.json · 带版本号多端同步）")
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    addView(listBox)

    fun reload() {
        val all = AiConsoleClient.favoriteItems()
        listBox.removeAllViews()
        if (all.isEmpty()) {
            listBox.hint("还没有收藏。点击对话气泡 → 收藏，两端都会保存。")
            return
        }
        listBox.label("共 ${all.size} 条 · 服务端配置 rev ${AiConsoleClient.configRevision()}")
        all.forEachIndexed { i, item ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 8),
                    CodexUi.dp(ctx, 9), CodexUi.dp(ctx, 8))
                background = CodexUi.roundedBg(ctx, "#FF262626", 9)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = CodexUi.dp(ctx, 6) }
            }
            card.addView(TextView(ctx).apply {
                val full = if (item.text.length > 400) item.text.take(400) + "…" else item.text
                val meta = buildString {
                    if (item.role.isNotBlank()) append(if (item.role == "user") "提问" else "回复")
                    if (item.sid.isNotBlank()) append(" · ${item.sid.take(18)}")
                }
                this.text = if (meta.isBlank()) "${i + 1}. $full" else "${i + 1}. [$meta] $full"
                textSize = 12f; setTextColor(CodexUi.color("#FFE8E8E8"))
            })
            card.row {
                button("复制") {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI收藏", item.text))
                    toast("已复制")
                }
                button("移除") {
                    if (item.id.isNotBlank()) {
                        AiConsoleClient.removeFavoriteRemote(item.id) { ok, msg ->
                            toast(if (ok) "已移除，Web 端同步" else "移除失败：$msg")
                        }
                    } else {
                        AiConsoleClient.removeFavorite(item.text)
                        toast("已移除本地收藏")
                    }
                    reload()
                }
            }
            listBox.addView(card)
        }
    }
    reload()
    // 进入页面先拉一次服务端最新收藏，避免展示旧的离线缓存
    AiConsoleClient.loadConfig { ok, msg ->
        if (!ok) toast("刷新收藏失败：$msg")
        listBox.post { reload() }
    }
}
