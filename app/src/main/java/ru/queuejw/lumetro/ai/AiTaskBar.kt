package ru.queuejw.lumetro.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 九键上方的一行 AI 栏：AI 任务状态、当前数字输入与匹配到的常用任务。
 */
class AiTaskBar(
    context: Context,
    private val onOpenConsole: () -> Unit,
    private val onSendTask: (AiTask) -> Unit,
) : LinearLayout(context) {

    private val dot = View(context)
    private val statusText = TextView(context)
    private val inputText = TextView(context)
    private val taskScroll = HorizontalScrollView(context)
    private val taskRow = LinearLayout(context)

    private val renderListener: () -> Unit = { renderStatus() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#FF0F0F0F"))
        setPadding(dp(8), dp(4), dp(4), dp(4))
        minimumHeight = dp(34)

        dot.layoutParams = LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        addView(dot)

        statusText.apply {
            textSize = 11f
            setTextColor(Color.parseColor("#CCE8E8E8"))
            maxLines = 1
            includeFontPadding = false
            setOnClickListener { onOpenConsole() }
        }
        addView(HorizontalScrollView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(statusText)
            setOnClickListener { onOpenConsole() }
        })

        inputText.apply {
            textSize = 11f
            setTextColor(Color.parseColor("#88FFFFFF"))
            maxLines = 1
            includeFontPadding = false
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
        }
        addView(inputText)

        taskRow.orientation = HORIZONTAL
        taskRow.gravity = Gravity.CENTER_VERTICAL
        taskScroll.apply {
            visibility = GONE
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(taskRow)
        }
        addView(taskScroll)

        addView(TextView(context).apply {
            text = "›"
            textSize = 16f
            setTextColor(Color.parseColor("#99FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(4), 0)
            setOnClickListener { onOpenConsole() }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AiConsoleClient.init(context)
        AiConsoleClient.addListener(renderListener)
        renderStatus()
    }

    override fun onDetachedFromWindow() {
        AiConsoleClient.removeListener(renderListener)
        super.onDetachedFromWindow()
    }

    fun setInput(input: String, tasks: List<AiTask>) {
        inputText.text = if (input.isEmpty()) "" else "#$input"
        inputText.visibility = if (input.isEmpty()) GONE else VISIBLE

        taskRow.removeAllViews()
        for (task in tasks) {
            taskRow.addView(TextView(context).apply {
                text = task.label
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#FF2A2A2A"))
                }
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener { onSendTask(task) }
            })
        }
        taskScroll.visibility = if (tasks.isEmpty()) GONE else VISIBLE
    }

    private fun renderStatus() {
        val running = AiConsoleClient.running
        val error = AiConsoleClient.lastError
        val step = AiConsoleClient.step.ifBlank {
            AiConsoleClient.logText.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        }
        statusText.text = when {
            running -> "▶ " + step.ifBlank { "AI 运行中" }
            !error.isNullOrBlank() -> error
            else -> "AI 待命"
        }
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                when {
                    running -> Color.parseColor("#FF51C878")
                    !error.isNullOrBlank() -> Color.parseColor("#FFE06565")
                    else -> Color.parseColor("#FF7A7A7A")
                }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
