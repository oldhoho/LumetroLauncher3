package ru.queuejw.lumetro.components.inspire

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.min

/**
 * Full-screen accessibility overlay that turns a touch drag into a screen rectangle.
 * The overlay must be removed before taking a screenshot, otherwise it is captured too.
 */
internal class ScreenSelectionOverlay(
    context: Context,
    private val onSelected: (Rect, Int, Int) -> Unit,
    private val onCancelled: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = SelectionView(context)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private var attached = false

    fun show() {
        if (attached) return
        attached = true
        windowManager.addView(view, params)
    }

    fun dismiss() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeView(view) }
    }

    private inner class SelectionView(context: Context) : View(context) {
        private val dimPaint = Paint().apply { color = 0x66000000 }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics,
            )
        }
        private val hintBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99000000.toInt()
        }

        private val selection = Rect()
        private var startX = 0f
        private var startY = 0f
        private var dragging = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (selection.isEmpty) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            } else {
                canvas.drawRect(0f, 0f, width.toFloat(), selection.top.toFloat(), dimPaint)
                canvas.drawRect(0f, selection.bottom.toFloat(), width.toFloat(), height.toFloat(), dimPaint)
                canvas.drawRect(0f, selection.top.toFloat(), selection.left.toFloat(), selection.bottom.toFloat(), dimPaint)
                canvas.drawRect(selection.right.toFloat(), selection.top.toFloat(), width.toFloat(), selection.bottom.toFloat(), dimPaint)
                canvas.drawRect(selection, borderPaint)
            }
            drawHint(canvas)
        }

        private fun drawHint(canvas: Canvas) {
            val text = if (selection.isEmpty) "拖动框选要识别的文字，松手开始" else "松手开始识别"
            val padding = 12f * resources.displayMetrics.density
            val textWidth = hintPaint.measureText(text)
            val textHeight = hintPaint.textSize
            val left = (width - textWidth) / 2f
            val top = resources.displayMetrics.heightPixels * 0.12f
            canvas.drawRoundRect(
                left - padding,
                top - padding,
                left + textWidth + padding,
                top + textHeight + padding,
                padding,
                padding,
                hintBackground,
            )
            canvas.drawText(text, left, top + textHeight, hintPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    selection.set(startX.toInt(), startY.toInt(), startX.toInt(), startY.toInt())
                    dragging = true
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return true
                    selection.set(
                        min(startX, event.rawX).toInt(),
                        min(startY, event.rawY).toInt(),
                        maxOf(startX, event.rawX).toInt(),
                        maxOf(startY, event.rawY).toInt(),
                    )
                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return true
                    dragging = false
                    val threshold = 24f * resources.displayMetrics.density
                    if (selection.width() < threshold || selection.height() < threshold) {
                        onCancelled()
                    } else {
                        onSelected(Rect(selection), width, height)
                    }
                }
            }
            return true
        }
    }
}
