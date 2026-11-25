package ru.queuejw.lumetro.ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

/**
 * 功能中心统一 UI 构建器。针对手机窄屏做了紧凑化：按钮等宽分摊、卡片圆角、
 * 分段选择器可横向滑动，避免 Web 端桌面布局直接搬过来后按钮溢出。
 */
internal object CodexUi {

    private const val BG = "#F21A1A1A"
    private const val CARD = "#FF262626"
    private const val CARD_ACTIVE = "#FF2E4A42"
    private const val LINE = "#FF3A3A3A"
    private const val TEXT = "#FFE8E8E8"
    private const val MUTED = "#AA9AA0A8"
    private const val INFO = "#FF88CCFF"
    private const val OK = "#FF9EFFB0"
    const val ACCENT = "#FF3B7D6B"

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun color(s: String) = Color.parseColor(s)

    /**
     * 删除/清空/卸载等不可逆操作的统一二次确认。
     * 文案要说清影响范围（本轮/全会话/两端同步/自动备份），不要只写「确定吗」。
     */
    fun confirm(
        ctx: Context,
        title: String,
        message: String,
        positive: String = "确认删除",
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    fun roundedBg(ctx: Context, hex: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp).toFloat(); setColor(color(hex))
        }

    fun root(ctx: Context, body: LinearLayout.() -> Unit): ScrollView {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            body()
        }
        return ScrollView(ctx).apply {
            setBackgroundColor(color(BG))
            addView(content)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
    }

    fun LinearLayout.title(text: String, sub: String? = null) {
        addView(TextView(context).apply {
            this.text = text
            textSize = 17f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 4), 0, dp(context, 2))
        })
        if (sub != null) addView(TextView(context).apply {
            this.text = sub
            textSize = 11f; setTextColor(color(MUTED))
            setPadding(0, 0, 0, dp(context, 8))
        })
    }

    fun LinearLayout.section(name: String, body: LinearLayout.() -> Unit) {
        addView(TextView(context).apply {
            text = "▸ $name"
            textSize = 12f; setTextColor(color(INFO))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 10), 0, dp(context, 5))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat(); setColor(color(CARD))
                setStroke(dp(context, 1), color(LINE))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 6)
            }
            body()
        })
    }

    fun LinearLayout.label(text: String) {
        addView(TextView(context).apply {
            this.text = text
            textSize = 12f; setTextColor(color(TEXT))
            setPadding(0, dp(context, 5), 0, dp(context, 2))
        })
    }

    fun LinearLayout.hint(text: String) {
        addView(TextView(context).apply {
            this.text = text
            textSize = 11f; setTextColor(color(MUTED))
            setPadding(0, dp(context, 2), 0, dp(context, 5))
        })
    }

    fun LinearLayout.status(text: String, good: Boolean = true) {
        addView(TextView(context).apply {
            this.text = text
            textSize = 11f; setTextColor(color(if (good) OK else "#FFFF9A9A"))
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        })
    }

    fun LinearLayout.edit(hintText: String, value: String = "", singleLine: Boolean = true): EditText =
        EditText(context).apply {
            hint = hintText
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(color(MUTED))
            textSize = 13f
            isSingleLine = singleLine
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 7).toFloat(); setColor(color("#FF1B1B1B"))
                setStroke(dp(context, 1), color(LINE))
            }
            setPadding(dp(context, 9), dp(context, 8), dp(context, 9), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 5)
            }
        }.also { addView(it) }

    private fun compactButton(ctx: Context, label: String, small: Boolean): Button =
        Button(ctx).apply {
            text = label
            textSize = if (small) 10f else 11.5f
            setTextColor(Color.WHITE)
            setAllCaps(false)
            minHeight = 0
            minimumHeight = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 7).toFloat(); setColor(color(ACCENT))
            }
            setPadding(dp(ctx, 6), dp(ctx, 5), dp(ctx, 6), dp(ctx, 5))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(ctx, 4); topMargin = dp(ctx, 2); bottomMargin = dp(ctx, 2)
            }
        }

    // 注意：这里是 LinearLayout 的扩展函数，also 的隐式 receiver 仍是父布局。
    // 必须显式用 it./btn. 给按钮本身绑定监听；否则监听器会挂到父布局上，
    // 按钮因为自身 clickable 会吞掉点击，表现为「按钮点了没反应」。
    fun LinearLayout.button(label: String, onClick: () -> Unit): Button =
        compactButton(context, label, false).also { btn ->
            btn.setOnClickListener { onClick() }
            addView(btn)
        }

    /** 紧凑小按钮，用于卡片内并排的多个操作（会话/技能页） */
    fun LinearLayout.tinyButton(label: String, onClick: () -> Unit): Button =
        compactButton(context, label, true).also { btn ->
            btn.setOnClickListener { onClick() }
            addView(btn)
        }

    /** 状态化按钮：开/关用不同底色，适合开关类操作 */
    fun LinearLayout.toggleButton(label: String, on: Boolean, onClick: () -> Unit): Button =
        compactButton(context, if (on) "$label · 开" else "$label · 关", false).also { btn ->
            btn.background = GradientDrawable().apply {
                cornerRadius = dp(context, 7).toFloat()
                setColor(color(if (on) ACCENT else "#FF444444"))
            }
            btn.setOnClickListener { onClick() }
            addView(btn)
        }

    fun LinearLayout.row(body: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 1); bottomMargin = dp(context, 1)
            }
            body()
        })
    }

    /**
     * 分段选择器：横向可滑，选中项高亮。用于模式、注入范围、轮询间隔等。
     */
    fun LinearLayout.segment(
        options: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit
    ) {
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
        }
        options.forEach { (value, label) ->
            val on = value == selected
            inner.addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(color(if (on) "#FFFFFFFF" else "#FFBBBBBB"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 7).toFloat()
                    setColor(color(if (on) ACCENT else "#FF1E1E1E"))
                }
                setPadding(dp(context, 11), dp(context, 6), dp(context, 11), dp(context, 6))
                setOnClickListener { onSelect(value) }
            })
        }
        addView(HorizontalScrollView(context).apply {
            addView(inner)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    /** 复刻 Web 工具页的四宫格说明卡：标题 + 说明 + 一个动作按钮 */
    fun LinearLayout.actionCard(title: String, desc: String, action: String, onClick: () -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 9).toFloat(); setColor(color(CARD))
                setStroke(dp(context, 1), color(LINE))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 6)
            }
            addView(TextView(context).apply {
                text = title
                textSize = 12.5f; setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = desc
                textSize = 11f; setTextColor(color("#FFCCCCCC"))
                setPadding(0, dp(context, 3), 0, dp(context, 7))
            })
            row { button(action, onClick) }
        })
    }

    /** 勾选行：能力授权、工具安装清单等 */
    fun LinearLayout.checkRow(
        label: String, desc: String, checked: Boolean, enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat(); setColor(color("#FF1E1E1E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 5)
            }
            val box = android.widget.CheckBox(context).apply {
                isChecked = checked
                isEnabled = enabled
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }
            addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 12f; setTextColor(color(if (enabled) TEXT else MUTED))
                })
                if (desc.isNotBlank()) addView(TextView(context).apply {
                    text = desc
                    textSize = 10.5f; setTextColor(color(MUTED))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    fun LinearLayout.tagLine(text: String) {
        addView(TextView(context).apply {
            this.text = text
            textSize = 10.5f; setTextColor(color(INFO))
            setPadding(0, dp(context, 3), 0, dp(context, 5))
        })
    }

    fun LinearLayout.mono(text: String, max: Int = 4000) {
        val t = if (text.length > max) text.take(max) + "\n…（已截断）" else text
        addView(TextView(context).apply {
            this.text = t.ifBlank { "（空）" }
            textSize = 11f; setTextColor(color("#FFD0D8D0"))
            typeface = Typeface.MONOSPACE
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 6).toFloat(); setColor(color("#FF141414"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 4)
            }
        })
    }

    fun LinearLayout.divider() {
        addView(View(context).apply {
            setBackgroundColor(color(LINE))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
                topMargin = dp(context, 6); bottomMargin = dp(context, 6)
            }
        })
    }
}
