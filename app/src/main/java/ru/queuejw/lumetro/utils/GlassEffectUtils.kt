package ru.queuejw.lumetro.utils

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView

object GlassEffectUtils {
    fun glassCardColor(color: Int, enabled: Boolean): Int =
        if (enabled) (color and 0x00FFFFFF) or 0xCC000000.toInt() else color

    private const val BACKGROUND_BLUR_RADIUS = 72
    private const val GLASS_SCRIM_ALPHA = 0.32f
    private const val GLASS_EDGE_ALPHA = 20

    fun applyWindowBlur(window: Window, enabled: Boolean) {
        applyWindowBlurProgress(window, if (enabled) 1f else 0f)
    }

    fun applyWindowBlurProgress(window: Window, progress: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetRadius = (BACKGROUND_BLUR_RADIUS * progress.coerceIn(0f, 1f)).toInt()
        applyWindowBlurRadius(window, targetRadius)
    }

    fun createWindowBlurAnimator(
        window: Window,
        fromProgress: Float,
        toProgress: Float
    ): ValueAnimator? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return ValueAnimator.ofFloat(fromProgress, toProgress).apply {
            addUpdateListener { animator ->
                applyWindowBlurProgress(window, animator.animatedValue as Float)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyWindowBlurRadius(window: Window, radius: Int) {
        if (radius > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        val params = window.attributes
        params.blurBehindRadius = radius
        runCatching { window.attributes = params }
        if (radius == 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    fun glassScrimColor(color: Int, enabled: Boolean): Int {
        if (!enabled) return color
        return ColorUtils.setAlphaComponent(color, (GLASS_SCRIM_ALPHA * 255).toInt())
    }

    fun usesCardRegionBlur(backgroundBlur: Boolean, cardBlur: Boolean): Boolean {
        return cardBlur && !backgroundBlur
    }

    fun applyGlassEdge(card: MaterialCardView) {
        val strokeWidth = (1f * card.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val onSurface = DynamicColorUtils.onSurfaceColor.takeIf { it != 0 } ?: Color.BLACK
        val strokeColor = ColorUtils.setAlphaComponent(onSurface, GLASS_EDGE_ALPHA)
        if (card.strokeWidth != strokeWidth) card.strokeWidth = strokeWidth
        if (card.strokeColor != strokeColor) card.strokeColor = strokeColor
    }

    fun setCardShadowEnabled(
        card: MaterialCardView,
        enabled: Boolean,
        blurRadius: Float = 28f,
    ) {
        card.cardElevation = if (enabled) blurRadius / 2f else 0f
    }

    fun applyCardStyle(
        card: MaterialCardView,
        color: Int,
        glassEnabled: Boolean,
        clearForeground: Boolean = false,
        blurRegion: Boolean = true,
        backgroundBlurEnabled: Boolean = false,
        overlayOnContent: Boolean = false,
    ) {
        card.setCardBackgroundColor(color)
        if (clearForeground) card.foreground = null
    }

    fun applyFixedGlassChrome(card: MaterialCardView, blurRadius: Float = 28f) {
        applyGlassEdge(card)
        setCardShadowEnabled(card, true, blurRadius)
    }
}
