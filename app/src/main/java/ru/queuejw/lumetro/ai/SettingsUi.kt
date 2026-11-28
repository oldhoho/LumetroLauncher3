package ru.queuejw.lumetro.ai

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import ru.queuejw.lumetro.ai.CodexUi.actionCard
import ru.queuejw.lumetro.ai.CodexUi.row
import ru.queuejw.lumetro.ai.CodexUi.tinyButton
import ru.queuejw.lumetro.ai.CodexUi.toggleButton

/**
 * 启动器设置页的 AI 窗同款控件。色板、圆角卡片、分组标题直接复用 [CodexUi]，
 * 只在它缺少的滑块 / 色块行 / 图片行 / 值行上做补充，保证所有设置页视觉一致。
 */

/** 一级分组标题：比 [CodexUi.section] 更高一层，用于把多个相关分区归入一个大类。 */
fun LinearLayout.settingsGroup(title: String, sub: String? = null) {
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, CodexUi.dp(context, 16), 0, CodexUi.dp(context, 4))
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                CodexUi.dp(context, 3), CodexUi.dp(context, 17)
            ).apply { rightMargin = CodexUi.dp(context, 9) }
            setBackgroundColor(CodexUi.color(CodexUi.ACCENT))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(CodexUi.color("#FFFFFFFF"))
            })
            if (!sub.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = sub
                    textSize = 11f
                    setTextColor(CodexUi.color("#AA9AA0A8"))
                    setPadding(0, CodexUi.dp(context, 2), 0, 0)
                })
            }
        })
    })
}
fun LinearLayout.settingsSlider(
    label: String,
    min: Int,
    max: Int,
    value: Int,
    unit: String,
    onChange: (Int) -> Unit,
): SeekBar {
    var seek: SeekBar? = null
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, CodexUi.dp(context, 4), 0, CodexUi.dp(context, 2))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(CodexUi.color("#FFE8E8E8"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val valueView = TextView(context).apply {
                text = "$value$unit"
                textSize = 12f
                setTextColor(CodexUi.color("#FF9EFFB0"))
            }
            addView(valueView)
            seek = SeekBar(context).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, this.max)
                progressTintList = ColorStateList.valueOf(CodexUi.color(CodexUi.ACCENT))
                thumbTintList = ColorStateList.valueOf(CodexUi.color(CodexUi.ACCENT))
                splitTrack = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = min + p
                        valueView.text = "$v$unit"
                        if (fromUser) onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
        })

        addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    })
    return seek!!
}

/** AI 窗风格开关：选中深青绿底，关闭灰底，点击即时反馈 */
fun LinearLayout.settingsSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    var state = on
    row { toggleButton(label, on) { state = !state; onChange(state) } }
}

/** 可点击的值行，右侧带 › */
fun LinearLayout.settingsValueRow(label: String, value: String, onClick: () -> Unit): TextView {
    var valueView: TextView? = null
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            CodexUi.dp(context, 6), CodexUi.dp(context, 6),
            CodexUi.dp(context, 6), CodexUi.dp(context, 6)
        )
        background = CodexUi.roundedBg(context, "#FF1E1E1E", 8)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = CodexUi.dp(context, 5) }
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(CodexUi.color("#FFE8E8E8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        valueView = TextView(context).apply {
            text = "$value  ›"
            textSize = 12f
            setTextColor(CodexUi.color("#FF88CCFF"))
        }
        addView(valueView)
    })
    return valueView!!
}

/** 颜色行：左侧文字、右侧色块，整行点击打开取色器 */
fun LinearLayout.settingsColorRow(label: String, color: Int, onClick: () -> Unit): View {
    var swatch: View? = null
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            CodexUi.dp(context, 6), CodexUi.dp(context, 6),
            CodexUi.dp(context, 6), CodexUi.dp(context, 6)
        )
        background = CodexUi.roundedBg(context, "#FF1E1E1E", 8)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = CodexUi.dp(context, 5) }
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(CodexUi.color("#FFE8E8E8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                CodexUi.dp(context, 40), CodexUi.dp(context, 22)
            )
        }
        addView(swatch)
    })
    fun paint(hex: Int) {
        swatch?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = CodexUi.dp(context, 5).toFloat()
            setColor(hex)
            setStroke(CodexUi.dp(context, 1), Color.parseColor("#66FFFFFF"))
        }
    }
    paint(color)
    return swatch!!
}

/** 图片行：状态文字 + 选择/清除两个紧凑按钮 */
fun LinearLayout.settingsImageRow(
    label: String,
    hasImage: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = if (hasImage) "$label · 已设置" else "$label · 未设置"
            textSize = 12f
            setTextColor(CodexUi.color(if (hasImage) "#FF9EFFB0" else "#AA9AA0A8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tinyButton("选择", onPick)
            tinyButton("清除", onClear)
        })
    })
}

/** 操作卡：标题 + 说明 + 一个动作按钮 */
fun LinearLayout.settingsActionCard(title: String, desc: String, action: String, onClick: () -> Unit) =
    actionCard(title, desc, action, onClick)
