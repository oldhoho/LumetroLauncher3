package ru.queuejw.lumetro.ai

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * 轻量 Markdown → Spanned 渲染，供 AI 窗气泡显示使用。
 *
 * 原生 TextView 并非"不能显示符号"——它完整支持 Spanned 富文本，缺的只是
 * Markdown 解析器。Web 端有 marked 所以渲染成排版，AI 窗此前直接
 * `text = msg.text` 把 `**粗体**`、`### 标题`、`- 列表` 的标记原样吐出来，
 * 才显得满屏星号。这里做一个可控的小解析器，把常见语法转成样式并吃掉标记。
 *
 * 设计要点：
 * - 流式安全：未闭合的标记按原文保留（Web 端也是闭合后才渲染），
 *   不会每来一个 token 就闪一下。
 * - 不设 URLSpan：气泡短按要弹操作卡、长按要选字，可点链接会和触摸抢事件；
 *   链接改成着色 + 下划线，需要跳转可用 Web 端或搜索引擎。
 * - plain() 供自动朗读使用，去掉所有标记只留可读正文，TTS 不会念"星号"。
 */
internal object MarkdownText {

    private val SC = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    private val HEADING_RE = Regex("^(#{1,6})\\s+(.*)$")
    private val QUOTE_RE = Regex("^>\\s?(.*)$")
    private val LIST_RE = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.*)$")
    private val HR_RE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val LINK_RE = Regex("^\\[([^\\]]+)\\]\\(([^\\s)]+)\\)")

    /** 渲染为带样式的正文（气泡显示用） */
    fun render(raw: String): Spanned = build(raw, styled = true)

    /** 去掉 Markdown 标记的纯文本（自动朗读用，避免念出符号） */
    fun plain(raw: String): String = build(raw, styled = false).toString()

    private fun build(raw: String, styled: Boolean): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        if (raw.isEmpty()) return out
        val lines = raw.split('\n')
        var inFence = false
        val fence = StringBuilder()

        for (i in lines.indices) {
            val line = lines[i]
            if (i > 0) out.append('\n')

            // 围栏代码块：``` 之间整段等宽 + 底色，优先于其它语法
            if (line.trimStart().startsWith("```")) {
                if (inFence) {
                    flushFence(out, fence, styled)
                    fence.setLength(0)
                }
                inFence = !inFence
                continue
            }
            if (inFence) {
                fence.append(line).append('\n')
                continue
            }

            val heading = HEADING_RE.matchEntire(line)
            if (heading != null) {
                val start = out.length
                inline(out, heading.groupValues[2], styled)
                if (styled) {
                    val level = heading.groupValues[1].length
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SC)
                    out.setSpan(
                        RelativeSizeSpan(if (level <= 2) 1.16f else 1.05f),
                        start, out.length, SC
                    )
                }
                continue
            }

            val hr = HR_RE.matchEntire(line)
            if (hr != null) {
                if (styled) out.append("────────") else out.append("")
                if (styled) {
                    out.setSpan(ForegroundColorSpan(Color.parseColor("#FF808880")),
                        out.length - 8, out.length, SC)
                }
                continue
            }

            val quote = QUOTE_RE.matchEntire(line)
            if (quote != null) {
                val start = out.length
                out.append("│ ")
                inline(out, quote.groupValues[1], styled)
                if (styled) {
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SC)
                    out.setSpan(ForegroundColorSpan(Color.parseColor("#FFB9C6B9")),
                        start, out.length, SC)
                }
                continue
            }

            val list = LIST_RE.matchEntire(line)
            if (list != null) {
                val start = out.length
                out.append(list.groupValues[1]) // 保留缩进
                out.append("• ")
                inline(out, list.groupValues[3], styled)
                if (styled && list.groupValues[2].matches(Regex("\\d+\\."))) {
                    out.setSpan(ForegroundColorSpan(Color.parseColor("#FF88CCFF")),
                        start, start + 2, SC)
                }
                continue
            }

            inline(out, line, styled)
        }
        // 流式输出中代码块尚未闭合：按原文补上，别吞内容
        if (inFence && fence.isNotEmpty()) flushFence(out, fence, styled)
        return out
    }

    private fun flushFence(out: SpannableStringBuilder, fence: StringBuilder, styled: Boolean) {
        val text = fence.toString().trimEnd('\n')
        val start = out.length
        out.append(text)
        if (styled && text.isNotEmpty()) {
            out.setSpan(TypefaceSpan("monospace"), start, out.length, SC)
            out.setSpan(BackgroundColorSpan(Color.parseColor("#FF242424")), start, out.length, SC)
        }
    }

    /** 行内标记：粗体 / 斜体 / 删除线 / 行内代码 / 链接 */
    private fun inline(out: SpannableStringBuilder, s: String, styled: Boolean) {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    i = pair(out, s, i, if (s[i] == '*') "**" else "__", styled) { start, end ->
                        if (styled) out.setSpan(StyleSpan(Typeface.BOLD), start, end, SC)
                    }
                }
                s.startsWith("~~", i) -> {
                    i = pair(out, s, i, "~~", styled) { start, end ->
                        if (styled) out.setSpan(StrikethroughSpan(), start, end, SC)
                    }
                }
                s.startsWith("`", i) -> {
                    val close = s.indexOf('`', i + 1)
                    if (close > i + 1) {
                        val start = out.length
                        out.append(s.substring(i + 1, close))
                        if (styled) {
                            out.setSpan(TypefaceSpan("monospace"), start, out.length, SC)
                            out.setSpan(BackgroundColorSpan(Color.parseColor("#FF2E2E2E")),
                                start, out.length, SC)
                            out.setSpan(ForegroundColorSpan(Color.parseColor("#FFFFD479")),
                                start, out.length, SC)
                        }
                        i = close + 1
                    } else {
                        out.append(s[i]); i++
                    }
                }
                s[i] == '*' || s[i] == '_' -> {
                    val mark = s[i].toString()
                    i = pair(out, s, i, mark, styled) { start, end ->
                        if (styled) out.setSpan(StyleSpan(Typeface.ITALIC), start, end, SC)
                    }
                }
                s[i] == '[' -> {
                    val m = LINK_RE.find(s, i)
                    if (m != null && m.range.first == i) {
                        val start = out.length
                        out.append(m.groupValues[1])
                        if (styled) {
                            out.setSpan(ForegroundColorSpan(Color.parseColor("#FF88CCFF")),
                                start, out.length, SC)
                            out.setSpan(UnderlineSpan(), start, out.length, SC)
                        }
                        i = m.range.last + 1
                    } else {
                        out.append(s[i]); i++
                    }
                }
                else -> {
                    out.append(s[i]); i++
                }
            }
        }
    }

    /** 处理成对标记；未闭合时把起始符当普通字符输出，保证流式中间态不闪烁 */
    private inline fun pair(
        out: SpannableStringBuilder,
        s: String,
        i: Int,
        mark: String,
        styled: Boolean,
        apply: (start: Int, end: Int) -> Unit
    ): Int {
        val close = s.indexOf(mark, i + mark.length)
        if (close < i + mark.length + 1) {
            out.append(s[i]); return i + 1
        }
        val start = out.length
        inline(out, s.substring(i + mark.length, close), styled)
        apply(start, out.length)
        return close + mark.length
    }
}
