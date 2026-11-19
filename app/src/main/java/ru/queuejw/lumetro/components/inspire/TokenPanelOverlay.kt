package ru.queuejw.lumetro.components.inspire

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.card.MaterialCardView
import ru.queuejw.lumetro.R

/**
 * Bottom token panel used for both screen selections and clipboard content.
 * Chips keep their source offsets so copying a selection preserves the original spacing.
 */
internal class TokenPanelOverlay(
    context: Context,
    private val rawText: String,
    tokens: List<String>,
    private val onDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val themedContext = ContextThemeWrapper(context, R.style.Base_Theme_MPL)
    private val density = themedContext.resources.displayMetrics.density

    private data class Token(val text: String, val start: Int, val end: Int)

    private val entries: List<Token> = buildList {
        var offset = 0
        tokens.forEach { token ->
            add(Token(token, offset, offset + token.length))
            offset += token.length
        }
    }
    private val chips = mutableListOf<TextView>()
    private val selected = BooleanArray(entries.size)
    private val panel = FrameLayout(themedContext)
    private var attached = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    fun show() {
        if (attached) return
        panel.setOnClickListener { dismiss() }
        panel.addView(buildCard(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        attached = true
        windowManager.addView(panel, params)
    }

    fun dismiss() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeView(panel) }
        onDismiss()
    }

    private fun buildCard(): View {
        val card = MaterialCardView(themedContext).apply {
            radius = 20f * density
            cardElevation = 12f * density
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
        }
        val content = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }

        content.addView(TextView(themedContext).apply {
            text = "文字分词"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(themedContext).apply {
            text = "点击词语选择；不选择时复制全部"
            setTextColor(0xFF777777.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(8))
        })

        val flow = FlowLayout(themedContext)
        entries.forEachIndexed { index, token ->
            if (token.text.isBlank()) return@forEachIndexed
            val chip = TextView(themedContext).apply {
                text = token.text.trim()
                tag = index
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextColor(Color.BLACK)
                background = chipBackground(false)
                setOnClickListener {
                    selected[index] = !selected[index]
                    applyChipState(this, selected[index])
                }
            }
            chips += chip
            flow.addView(chip, ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }

        val scroll = ScrollView(themedContext).apply {
            addView(flow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (themedContext.resources.displayMetrics.heightPixels * 0.42f).toInt(),
            )
        }
        content.addView(scroll)

        content.addView(LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
            addView(actionButton("全选") {
                val selectAll = selected.indices.any { !selected[it] && entries[it].text.isNotBlank() }
                chips.forEach { chip ->
                    selected[chip.tag as Int] = selectAll
                    applyChipState(chip, selectAll)
                }
            })
            addView(actionButton("复制") { copySelection() })
            addView(actionButton("关闭") { dismiss() })
        })

        card.addView(content)
        return card
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        TextView(themedContext).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF0050EF.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

    private fun copySelection() {
        val first = selected.indexOfFirst { it }
        val last = selected.indexOfLast { it }
        val output = if (first < 0) {
            rawText
        } else {
            val start = entries[first].start
            val end = entries[last].end
            rawText.substring(start.coerceIn(0, rawText.length), end.coerceIn(0, rawText.length))
        }
        if (output.isBlank()) {
            Toast.makeText(themedContext, "没有可复制的文字", Toast.LENGTH_SHORT).show()
            return
        }
        ClipboardUtils.copyText(themedContext, output)
        Toast.makeText(themedContext, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun applyChipState(chip: TextView, isSelected: Boolean) {
        chip.setTextColor(if (isSelected) Color.WHITE else Color.BLACK)
        chip.background = chipBackground(isSelected)
    }

    private fun chipBackground(isSelected: Boolean) = GradientDrawable().apply {
        cornerRadius = 8f * density
        setColor(if (isSelected) 0xFF0050EF.toInt() else 0xFFF0F0F0.toInt())
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private class FlowLayout(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            var rowWidth = 0
            var rowHeight = 0
            var totalHeight = 0
            var maxRowWidth = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                measureChild(child, MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST), heightMeasureSpec)
                val lp = child.layoutParams as MarginLayoutParams
                val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
                val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
                if (rowWidth > 0 && rowWidth + childWidth > widthSize) {
                    maxRowWidth = maxOf(maxRowWidth, rowWidth)
                    totalHeight += rowHeight
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += childWidth
                rowHeight = maxOf(rowHeight, childHeight)
            }
            maxRowWidth = maxOf(maxRowWidth, rowWidth)
            totalHeight += rowHeight
            setMeasuredDimension(
                resolveSize(maxRowWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val widthSize = right - left
            var x = 0
            var y = 0
            var rowHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val lp = child.layoutParams as MarginLayoutParams
                val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
                val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
                if (x > 0 && x + childWidth > widthSize) {
                    x = 0
                    y += rowHeight
                    rowHeight = 0
                }
                child.layout(
                    x + lp.leftMargin,
                    y + lp.topMargin,
                    x + lp.leftMargin + child.measuredWidth,
                    y + lp.topMargin + child.measuredHeight,
                )
                x += childWidth
                rowHeight = maxOf(rowHeight, childHeight)
            }
        }

        override fun generateDefaultLayoutParams(): LayoutParams =
            MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
            MarginLayoutParams(context, attrs)

        override fun checkLayoutParams(params: LayoutParams?): Boolean = params is MarginLayoutParams
    }
}
