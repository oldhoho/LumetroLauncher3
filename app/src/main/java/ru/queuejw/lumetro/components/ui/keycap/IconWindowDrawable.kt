package ru.queuejw.lumetro.components.ui.keycap

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * 工作台图标「裁切窗口」效果。图标来源大小/形状/透明区各不相同，直接摆放会杂乱。
 * 这里在图标视图范围内开一个统一形状的窗口，把图标按 centerCrop 放大铺满、**只露出
 * 窗内部分**；窗内先铺统一底色填平透明区，因此所有图标最终呈现为同一造型。
 *
 * 十种预设只在「窗口形状 + 底色 + 窗沿」上变化，图标铺满逻辑共用。
 * 一次 onDraw 完成：裁剪窗口 → 铺底色 → 画图标(centerCrop) → 窗沿。
 */
class IconWindowDrawable(
    private val icon: Drawable?,
    private val accent: Int,
    private val highlight: Boolean,
    private val style: Int = STYLE_ROUNDED
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val windowPath = Path()
    private var baseColor: Int = 0xFF16191C.toInt()
    private var bezelShader: LinearGradient? = null

    private val styleSafe: Int get() = style.coerceIn(0, STYLE_COUNT - 1)

    init {
        val rgb = accent and 0x00FFFFFF
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        baseColor = (0xFF000000.toInt() or
                ((0x16 + (r * 0.10f).toInt()) shl 16) or
                ((0x19 + (g * 0.10f).toInt()) shl 8) or
                (0x1C + (b * 0.10f).toInt()))
        // 亮底预设：给深色图标用的浅色窗
        when (styleSafe) {
            STYLE_PEARL -> baseColor = 0xFFE8E8E6.toInt()
            STYLE_SAND -> baseColor = 0xFFD8CDBE.toInt()
        }
    }

    private fun rebuild(b: Rect) {
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val s = styleSafe
        val r = min(w, h)
        windowPath.reset()
        when (s) {
            STYLE_CIRCLE, STYLE_RING, STYLE_DARK_RING -> {
                windowPath.addCircle(b.centerX().toFloat(), b.centerY().toFloat(), r / 2f, Path.Direction.CW)
            }
            STYLE_SQUARE, STYLE_PEARL, STYLE_SAND -> {
                windowPath.addRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), Path.Direction.CW)
            }
            STYLE_CAPSULE -> {
                windowPath.addRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r / 2f, r / 2f, Path.Direction.CW)
            }
            STYLE_HEX -> {
                val cx = b.centerX().toFloat(); val cy = b.centerY().toFloat()
                val rx = w / 2f; val ry = h / 2f
                val pts = floatArrayOf(
                    cx, b.top.toFloat(), cx + rx, cy - ry * 0.5f, cx + rx, cy + ry * 0.5f,
                    cx, b.bottom.toFloat(), cx - rx, cy + ry * 0.5f, cx - rx, cy - ry * 0.5f
                )
                windowPath.moveTo(pts[0], pts[1])
                var i = 2
                while (i < pts.size) { windowPath.lineTo(pts[i], pts[i + 1]); i += 2 }
                windowPath.close()
            }
            STYLE_DIAMOND -> {
                val cx = b.centerX().toFloat(); val cy = b.centerY().toFloat()
                windowPath.moveTo(cx, b.top.toFloat())
                windowPath.lineTo(b.right.toFloat(), cy)
                windowPath.lineTo(cx, b.bottom.toFloat())
                windowPath.lineTo(b.left.toFloat(), cy)
                windowPath.close()
            }
            else -> {
                val radius = minOf(w, h) * 0.24f
                windowPath.addRoundRect(
                    b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    radius, radius, Path.Direction.CW
                )
            }
        }
        bezelShader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0x8CFFFFFF.toInt(), 0x14FFFFFF, 0x00000000, 0x4D000000),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild(bounds)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 2 || h <= 2) return

        // ① 裁剪到统一窗口
        canvas.save()
        canvas.clipPath(windowPath)

        // ② 统一底色：填平图标自身的透明区/异形边界
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = baseColor
        canvas.drawRect(b, paint)

        // ③ 图标 centerCrop 铺满窗口，只露窗内部分
        if (icon != null) {
            val iw = icon.intrinsicWidth
            val ih = icon.intrinsicHeight
            if (iw > 0 && ih > 0) {
                val scale = max(w.toFloat() / iw, h.toFloat() / ih)
                val dw = iw * scale
                val dh = ih * scale
                val l = b.left + (w - dw) / 2f
                val t = b.top + (h - dh) / 2f
                icon.setBounds(l.toInt(), t.toInt(), (l + dw).toInt(), (t + dh).toInt())
            } else {
                icon.bounds = b
            }
            icon.draw(canvas)
        }
        canvas.restore()

        // ④ 窗沿
        val s = styleSafe
        paint.style = Paint.Style.STROKE
        when (s) {
            STYLE_NONE -> { /* 无窗沿 */ }
            STYLE_RING, STYLE_DARK_RING -> {
                paint.shader = null
                paint.strokeWidth = if (highlight) 3f else 2f
                paint.color = if (s == STYLE_DARK_RING) {
                    (accent and 0x00FFFFFF) or (0xC0 shl 24).toInt()
                } else if (highlight) {
                    (accent and 0x00FFFFFF) or 0xE0000000.toInt()
                } else {
                    0x66FFFFFF
                }
                drawBezel(canvas, b)
            }
            STYLE_GLOW -> {
                paint.shader = bezelShader
                paint.strokeWidth = if (highlight) 3f else 2f
                drawBezel(canvas, b)
            }
            else -> {
                paint.shader = null
                paint.strokeWidth = if (highlight) 2.5f else 1.2f
                paint.color = if (s == STYLE_PEARL || s == STYLE_SAND) {
                    0x33000000
                } else if (highlight) {
                    (accent and 0x00FFFFFF) or 0xD0000000.toInt()
                } else {
                    0x3AFFFFFF
                }
                drawBezel(canvas, b)
            }
        }
        paint.style = Paint.Style.FILL
        paint.shader = null
    }

    private fun drawBezel(canvas: Canvas, b: Rect) {
        canvas.drawPath(windowPath, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        icon?.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        icon?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION", "deprecation")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val STYLE_ROUNDED = 0
        const val STYLE_CIRCLE = 1
        const val STYLE_SQUARE = 2
        const val STYLE_CAPSULE = 3
        const val STYLE_HEX = 4
        const val STYLE_DIAMOND = 5
        const val STYLE_RING = 6
        const val STYLE_GLOW = 7
        const val STYLE_PEARL = 8
        const val STYLE_SAND = 9
        const val STYLE_NONE = 10
        const val STYLE_DARK_RING = 11
        const val STYLE_COUNT = 12

        val STYLE_NAMES = arrayOf(
            "圆角方窗", "圆形窗", "直角方窗", "胶囊窗", "六边形窗", "菱形窗",
            "亮色环", "柔光沿", "珍珠白", "暖砂白", "无边框", "暗色环"
        )
    }
}
