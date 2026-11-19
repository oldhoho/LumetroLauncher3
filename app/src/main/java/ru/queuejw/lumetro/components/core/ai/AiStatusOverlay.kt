package ru.queuejw.lumetro.components.core.ai

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.freeform.WorkbenchSettings

/**
 * 工作台图层之上（底部工作条上方）显示的 AI 状态小标签。
 * 依赖无障碍服务，窗口层级与工作台相同，后添加的窗口覆盖在工作台之上。
 */
class AiStatusOverlay(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AiStatusOverlay"
        const val TIMEOUT_MS = 10_000L
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastText: String? = null

    private val timeoutRunnable = Runnable {
        hide()
        // 超时后同步清空持久化状态，避免工作台重建时把过期信息恢复出来
        AiStatusManager.update(service, AiStatusManager.STATE_HIDDEN, null)
    }

    fun update(state: String, text: String?) {
        if (Looper.myLooper() != mainHandler.looper) {
            mainHandler.post { update(state, text) }
            return
        }
        if (state == AiStatusManager.STATE_HIDDEN) {
            mainHandler.removeCallbacks(timeoutRunnable)
            hide()
            return
        }
        val label = text?.takeIf { it.isNotBlank() } ?: when (state) {
            AiStatusManager.STATE_RUNNING -> "🤖 AI 运行中..."
            AiStatusManager.STATE_IDLE -> "✅ AI 待命"
            else -> "AI"
        }
        show(label)
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    fun release() {
        mainHandler.removeCallbacks(timeoutRunnable)
        hide()
    }

    private fun show(text: String) {
        val tv = view ?: createView().also { view = it }
        if (tv.text != text) tv.text = text
        val lp = buildParams()
        params = lp
        if (tv.parent == null) {
            try {
                windowManager.addView(tv, lp)
            } catch (e: Exception) {
                Log.e(TAG, "addView failed", e)
            }
        } else {
            try {
                windowManager.updateViewLayout(tv, lp)
            } catch (e: Exception) {
                Log.e(TAG, "updateViewLayout failed", e)
            }
        }
    }

    private fun hide() {
        val tv = view ?: return
        if (tv.parent == null) return
        try {
            windowManager.removeViewImmediate(tv)
        } catch (e: Exception) {
            Log.e(TAG, "removeView failed", e)
        }
    }

    private fun createView(): TextView {
        val horizontal = dp(16)
        val vertical = dp(6)
        return TextView(service).apply {
            id = R.id.ai_status_overlay
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                setColor(0xCC1F1F1F.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        // 工作台高度取自设置，工作条上方留出一点间距
        val workbenchHeight = WorkbenchSettings(service).height
        val bottomOffset = dp(workbenchHeight + 8)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = -bottomOffset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setTitle("AI_STATUS_OVERLAY")
            }
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
