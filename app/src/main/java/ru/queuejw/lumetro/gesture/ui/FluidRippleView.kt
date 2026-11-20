package ru.queuejw.lumetro.gesture.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.RelativeLayout
import ru.queuejw.lumetro.utils.dp2px

enum class EdgeSide { LEFT, RIGHT, TOP, BOTTOM }

class FluidRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    val commitDistancePx: Float = 96f.dp2px

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
    }
    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f

    fun applyAppearance() {
        invalidate()
    }

    fun updatePosition(x: Float, y: Float) {
        centerX = x
        centerY = y
        invalidate()
    }

    fun startFluidRipple(side: EdgeSide, x: Float, y: Float) {
        centerX = x
        centerY = y
        radius = 120f.dp2px
        invalidate()
    }

    fun stopFluidRipple() {
        radius = 0f
        invalidate()
    }

    fun stopImmediate() {
        radius = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius > 0f) canvas.drawCircle(centerX, centerY, radius, ripplePaint)
    }
}
