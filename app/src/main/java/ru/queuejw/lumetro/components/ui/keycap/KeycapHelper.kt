package ru.queuejw.lumetro.components.ui.keycap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import ru.queuejw.lumetro.R

/**
 * 拟物机械键盘键帽背景：侧壁(底座) + 内凹顶面 + 顶部高光条 + 内侧投影 + 台阶描边 + 金属边框贴图；
 * 按下态顶面下沉、高光消失，制造实体按键的按压感。可选 [tint] 叠加一层半透明色用于状态高亮。
 */
object KeycapHelper {

    fun create(
        context: Context,
        cornerDp: Float = 10f,
        depthDp: Float = 3f,
        tint: Int? = null,
        baseColor: Int = 0xFF1A1A1A.toInt(),
        topColor: Int = 0xFF2E2E2E.toInt(),
        bottomColor: Int = 0xFF1E1E1E.toInt(),
        pressedTopColor: Int = 0xFF1A1A1A.toInt(),
        pressedBottomColor: Int = 0xFF141414.toInt(),
        strokeColor: Int = 0x18FFFFFF,
        highlightVisible: Boolean = true,
        frameVisible: Boolean = true,
        image: Bitmap? = null,
        frameImage: Bitmap? = null,
        frameAlpha: Int = 255
    ): Drawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                keycap(
                    context, cornerDp, depthDp, tint, true,
                    baseColor, topColor, bottomColor, pressedTopColor, pressedBottomColor,
                    strokeColor, highlightVisible, frameVisible, image, frameImage, frameAlpha
                )
            )
            addState(
                intArrayOf(),
                keycap(
                    context, cornerDp, depthDp, tint, false,
                    baseColor, topColor, bottomColor, pressedTopColor, pressedBottomColor,
                    strokeColor, highlightVisible, frameVisible, image, frameImage, frameAlpha
                )
            )
        }
    }

    private fun keycap(
        context: Context,
        cornerDp: Float,
        depthDp: Float,
        tint: Int?,
        pressed: Boolean,
        baseColor: Int,
        topColor: Int,
        bottomColor: Int,
        pressedTopColor: Int,
        pressedBottomColor: Int,
        strokeColor: Int,
        highlightVisible: Boolean,
        frameVisible: Boolean,
        image: Bitmap?,
        frameImage: Bitmap?,
        frameAlpha: Int
    ): LayerDrawable {
        val radius = dp(context, cornerDp).toFloat()
        val depth = dp(context, depthDp)
        val halfDepth = depth / 2

        // 侧壁底座：键帽外壳侧面，体现侧壁厚度
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(baseColor)
        }

        if (image != null) {
            val imageFace = RoundedBitmapDrawableFactory.create(context.resources, image).apply {
                cornerRadius = radius
                setAntiAlias(true)
            }
            if (pressed) {
                imageFace.setColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP)
            }

            val layers = mutableListOf<Drawable>(base, imageFace)
            var tintIndex = -1
            var frameIndex = -1
            if (tint != null) {
                tintIndex = layers.size
                layers.add(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radius
                        setColor(tint)
                    }
                )
            }
            if (frameVisible) {
                buildFrame(context, radius, frameImage, frameAlpha)?.let {
                    frameIndex = layers.size
                    layers.add(it)
                }
            }

            val faceTop = if (pressed) depth else 0
            val faceBottom = if (pressed) 0 else depth

            return LayerDrawable(layers.toTypedArray()).apply {
                setLayerInset(0, 0, 0, 0, 0)
                setLayerInset(1, 0, faceTop, 0, faceBottom)
                if (tintIndex >= 0) setLayerInset(tintIndex, 0, faceTop, 0, faceBottom)
                if (frameIndex >= 0) setLayerInset(frameIndex, 0, faceTop, 0, faceBottom)
            }
        }

        // 内凹顶面：顶亮底暗，模拟字符区凹陷的光影
        val face = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = if (pressed) intArrayOf(pressedTopColor, pressedBottomColor)
            else intArrayOf(topColor, bottomColor)
            // 台阶描边：顶面与侧壁分界的微亮线
            setStroke(dp(context, 0.5f), if (pressed) 0x10FFFFFF else strokeColor)
        }

        // 顶部弧形高光条：模拟键帽顶面弧度反光，按下时消失
        val highlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = if (pressed || !highlightVisible) intArrayOf(0x00FFFFFF, 0x00FFFFFF)
            else intArrayOf(0x40FFFFFF, 0x00FFFFFF)
        }

        // 内侧投影：凹陷区域顶边的暗影，按下时消失
        val innerShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = if (pressed) intArrayOf(0x00000000, 0x00000000)
            else intArrayOf(0x33000000, 0x00000000)
        }

        val content = mutableListOf<Drawable>(base, face, innerShadow, highlight)
        if (tint != null) {
            content.add(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(tint)
                }
            )
        }

        // 金属边框贴图作为最顶层（中间透明，露出键帽面）
        if (frameVisible) {
            buildFrame(context, radius, frameImage, frameAlpha)?.let { content.add(it) }
        }

        val faceTop = if (pressed) depth else 0
        val faceBottom = if (pressed) 0 else depth
        val layers = content.toTypedArray()

        return LayerDrawable(layers).apply {
            // base: 全尺寸
            setLayerInset(0, 0, 0, 0, 0)
            // face: 按下时下沉
            setLayerInset(1, 0, faceTop, 0, faceBottom)
            // innerShadow: 紧贴顶面顶部，高度约半个 depth
            setLayerInset(2, 0, faceTop, 0, faceBottom + (if (pressed) 0 else halfDepth))
            // highlight: 占顶面约 30%
            setLayerInset(3, 0, faceTop, 0, faceBottom + (if (pressed) 0 else (depth * 7 / 10)))
            if (tint != null) setLayerInset(4, 0, faceTop, 0, faceBottom)
        }
    }

    private fun buildFrame(context: Context, radius: Float, image: Bitmap?, alpha: Int): Drawable? {
        if (image != null) {
            return RoundedBitmapDrawableFactory.create(context.resources, image).apply {
                cornerRadius = radius
                setAntiAlias(true)
                this.alpha = alpha
            }
        }
        return ContextCompat.getDrawable(context, R.drawable.icon_frame)?.apply {
            this.alpha = alpha
        }
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
}
