package ru.queuejw.lumetro.components.ui.keycap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * 纯 Canvas 绘制的多风格键帽。共用一次 onDraw，共 16 种风格：
 * 0 冷光玻璃 / 1 薄雾玻璃 / 2 新拟物 / 3 极简分层 / 4 重画金属 /
 * 5 深空哑光 / 6 霓虹描边 / 7 水晶透明 / 8 胶囊软键 / 9 双色台阶 /
 * 10 凹槽托盘 / 11 柔和凸起 / 12 聚光展台 / 13 皮革软垫 / 14 陶瓷釉面 / 15 香槟拉丝。
 * 后 6 种专为搭配拟物图标设计：更强调实体材质、光影与投影，而非扁平与霓虹。
 *
 * 相比旧的 LayerDrawable + PNG 边框方案：无位图解码/缩放/圆角裁剪，无多层全尺寸
 * overdraw；所有 Shader 只在 onBoundsChange 重建，draw 阶段零对象分配。
 *
 * 对外必须遵守 ConstantState / mutate 契约：AppListPanel 与 WorkbenchOverlay 都会
 * 取 constantState 后 newDrawable().mutate()，因此每个实例都持有独立的 Paint，
 * 且 [getConstantState] 返回携带全部参数的 State。
 */
class KeycapDrawable private constructor(
    private val spec: Spec
) : Drawable() {

    private data class Spec(
        val radius: Float,
        val depth: Int,
        val pressed: Boolean,
        val baseColor: Int,
        val topColor: Int,
        val bottomColor: Int,
        val pressedTopColor: Int,
        val pressedBottomColor: Int,
        val strokeColor: Int,
        val highlightVisible: Boolean,
        val frameVisible: Boolean,
        val frameAlpha: Int,
        val tint: Int?,
        val density: Float,
        val style: Int,
    )

    constructor(
        radius: Float,
        depth: Int,
        pressed: Boolean,
        baseColor: Int,
        topColor: Int,
        bottomColor: Int,
        pressedTopColor: Int,
        pressedBottomColor: Int,
        strokeColor: Int,
        highlightVisible: Boolean,
        frameVisible: Boolean,
        frameAlpha: Int,
        tint: Int?,
        density: Float,
        style: Int = STYLE_GLASS,
    ) : this(
        Spec(
            radius, depth, pressed, baseColor, topColor, bottomColor,
            pressedTopColor, pressedBottomColor, strokeColor,
            highlightVisible, frameVisible, frameAlpha, tint, density,
            style.coerceIn(0, STYLE_COUNT - 1)
        )
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var faceGradient: LinearGradient? = null
    private var glassGlow: LinearGradient? = null
    private var glassEdge: LinearGradient? = null
    private var neuroTop: LinearGradient? = null
    private var neuroBottom: LinearGradient? = null
    private var metalEdge: LinearGradient? = null
    private var brushed: LinearGradient? = null
    // 拟物材质专用渐变
    private var trayWell: LinearGradient? = null        // 凹槽托盘的内凹面
    private var trayLight: LinearGradient? = null       // 托盘上沿受光
    private var embossTop: LinearGradient? = null       // 柔和凸起顶部反光
    private var embossShade: LinearGradient? = null     // 柔和凸起底部接触阴影
    private var spotGlow: RadialGradient? = null        // 聚光展台中心亮区
    private var leatherGrain: LinearGradient? = null    // 皮革细纹
    private var ceramicGlaze: LinearGradient? = null    // 陶瓷釉面高光
    private var champagneEdge: LinearGradient? = null   // 香槟拉丝描边
    private var shadowPaint: Paint? = null              // 外部投影（仅材质类）
    private var drawAlpha = 255
    private var currentFilter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val h = bounds.height().toFloat()
        val w = bounds.width().toFloat()
        val top = if (spec.pressed) spec.depth.toFloat() else 0f
        val bottom = h - (if (spec.pressed) 0f else spec.depth.toFloat())
        if (bottom - top < 2f || w <= 2f) return

        val c1 = if (spec.pressed) spec.pressedTopColor else spec.topColor
        val c2 = if (spec.pressed) spec.pressedBottomColor else spec.bottomColor
        faceGradient = LinearGradient(0f, top, 0f, bottom, c1, c2, Shader.TileMode.CLAMP)

        val glowH = (bottom - top) * 0.42f
        glassGlow = LinearGradient(
            0f, top, 0f, top + glowH,
            intArrayOf(0x34FFFFFF.toInt(), 0x12FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        // 冷光描边：顶部亮、中部淡、底部近乎隐去。按下时整体压暗。
        val baseA = Color.alpha(spec.strokeColor)
        val rgb = spec.strokeColor and 0x00FFFFFF
        val scale = (spec.frameAlpha / 255f) * (if (spec.pressed) 0.45f else 1f)
        val topA = (baseA * 3.4f * scale).toInt().coerceIn(0, 255)
        val midA = (baseA * 1.0f * scale).toInt().coerceIn(0, 255)
        val botA = (baseA * 0.18f * scale).toInt().coerceIn(0, 255)
        glassEdge = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(rgb or (topA shl 24), rgb or (midA shl 24), rgb or (botA shl 24)),
            floatArrayOf(0f, 0.36f, 1f),
            Shader.TileMode.CLAMP
        )

        // 新拟物：同色系双向软阴影，靠明暗梯度做凸起/凹陷。
        val faceH = bottom - top
        neuroTop = LinearGradient(
            0f, top, 0f, top + faceH * 0.55f,
            intArrayOf(0x38000000, 0x00000000),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        neuroBottom = LinearGradient(
            0f, bottom - faceH * 0.5f, 0f, bottom,
            intArrayOf(0x00FFFFFF, 0x24FFFFFF.toInt()),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )

        // 重画金属：银色渐变描边 + 极淡的横向拉丝，完全不依赖 PNG。
        // 拉丝纹路必须沿 Y 变化（渐变从顶到底），才会画出横纹；沿 X 会变成竖条。
        val mScale = (spec.frameAlpha / 255f) * (if (spec.pressed) 0.5f else 1f)
        fun alpha(v: Int) = (v * mScale).toInt().coerceIn(0, 255)
        metalEdge = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(alpha(224), 222, 230, 238),
                Color.argb(alpha(138), 141, 155, 168),
                Color.argb(alpha(70), 70, 83, 95)
            ),
            floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP
        )
        val streaks = FloatArray(11) { it / 10f }
        val streakColors = IntArray(11) { i ->
            if (i % 2 == 0) 0x06FFFFFF else 0x00000000
        }
        brushed = LinearGradient(
            0f, top, 0f, bottom,
            streakColors, streaks, Shader.TileMode.CLAMP
        )

        // ===== 拟物材质：所有 shader 只在这里构建一次 =====
        val cx = w / 2f
        val cy = (top + bottom) / 2f
        val isPress = spec.pressed

        // 凹槽托盘：内凹面顶暗底亮，上沿一道受光高光
        trayWell = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x4D000000, 0x12000000, 0x20FFFFFF.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        trayLight = LinearGradient(
            0f, top, 0f, top + faceH * 0.36f,
            intArrayOf(
                if (isPress) 0x00000000 else 0x3AFFFFFF.toInt(),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )

        // 柔和凸起：顶部漫反射 + 底部接触阴影，比新拟物更柔、更厚
        embossTop = LinearGradient(
            cx, top - faceH * 0.1f, cx, cy,
            intArrayOf(0x30FFFFFF, 0x06FFFFFF, 0x00000000),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        embossShade = LinearGradient(
            cx, cy, cx, bottom + faceH * 0.1f,
            intArrayOf(0x00000000, 0x14000000, 0x4D000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )

        // 聚光展台：中心柔亮的径向光，边缘自然衰减
        val spotR = maxOf(w, faceH) * 0.72f
        spotGlow = RadialGradient(
            cx, cy - faceH * 0.08f, spotR,
            intArrayOf(
                if (isPress) 0x12FFFFFF else 0x46FFFFFF.toInt(),
                0x12FFFFFF, 0x00000000
            ),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )

        // 皮革细纹：沿 Y 的极密交替明暗，模拟压纹
        leatherGrain = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x0AFFFFFF, 0x00000000, 0x08000000, 0x00000000, 0x0AFFFFFF),
            floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f), Shader.TileMode.CLAMP
        )

        // 陶瓷釉面：上半段接近高光白，下半段保留底色冷润感
        ceramicGlaze = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xC8FFFFFF.toInt(), 0x5EFFFFFF.toInt(), 0x10FFFFFF, 0x14000000),
            floatArrayOf(0f, 0.22f, 0.62f, 1f), Shader.TileMode.CLAMP
        )

        // 香槟拉丝描边：暖金色，顶亮底暗
        champagneEdge = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFFF3D9C0.toInt(), 0xFFC9A67C.toInt(), 0xFF7E6246.toInt()),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )

        shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x66000000
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 2f || h <= 2f) return
        if (faceGradient == null) onBoundsChange(bounds)

        val radius = spec.radius.coerceAtMost(minOf(w, h) * 0.5f)
        val faceTop = b.top + (if (spec.pressed) spec.depth.toFloat() else 0f)
        val faceBottom = b.bottom - (if (spec.pressed) 0f else spec.depth.toFloat())
        if (faceBottom - faceTop < 2f) return

        paint.alpha = drawAlpha
        paint.colorFilter = currentFilter
        paint.style = Paint.Style.FILL
        paint.shader = null

        when (spec.style) {
            STYLE_GLASS -> drawGlass(canvas, b, radius, faceTop, faceBottom)
            STYLE_FROSTED -> drawFrosted(canvas, b, radius, faceTop, faceBottom)
            STYLE_NEURO -> drawNeuro(canvas, b, radius, faceTop, faceBottom)
            STYLE_FLAT -> drawFlat(canvas, b, radius, faceTop, faceBottom)
            STYLE_METAL -> drawMetal(canvas, b, radius, faceTop, faceBottom)
            STYLE_MATTE -> drawMatte(canvas, b, radius, faceTop, faceBottom)
            STYLE_NEON -> drawNeon(canvas, b, radius, faceTop, faceBottom)
            STYLE_CRYSTAL -> drawCrystal(canvas, b, radius, faceTop, faceBottom)
            STYLE_CAPSULE -> drawCapsule(canvas, b, radius, faceTop, faceBottom)
            STYLE_STEP -> drawStep(canvas, b, radius, faceTop, faceBottom)
            STYLE_TRAY -> drawTray(canvas, b, radius, faceTop, faceBottom)
            STYLE_EMBOSS -> drawEmboss(canvas, b, radius, faceTop, faceBottom)
            STYLE_SPOTLIGHT -> drawSpotlight(canvas, b, radius, faceTop, faceBottom)
            STYLE_LEATHER -> drawLeather(canvas, b, radius, faceTop, faceBottom)
            STYLE_CERAMIC -> drawCeramic(canvas, b, radius, faceTop, faceBottom)
            STYLE_CHAMPAGNE -> drawChampagne(canvas, b, radius, faceTop, faceBottom)
            else -> drawGlass(canvas, b, radius, faceTop, faceBottom)
        }

        paint.shader = null
    }

    private fun face(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        canvas.drawRoundRect(b.left.toFloat(), top, b.right.toFloat(), bottom, radius, radius, paint)
    }

    private fun overlayTint(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        val tint = spec.tint ?: return
        paint.shader = null
        paint.color = tint
        face(canvas, b, radius, top, bottom)
    }

    private fun stroke(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        val inset = width / 2f
        canvas.drawRoundRect(
            b.left.toFloat() + inset, top + inset,
            b.right.toFloat() - inset, bottom - inset,
            radius, radius, paint
        )
        paint.style = Paint.Style.FILL
    }

    private fun drawGlass(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())

        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)

        if (!spec.pressed && spec.highlightVisible) {
            paint.shader = glassGlow
            face(canvas, b, radius, top, bottom)
        }
        overlayTint(canvas, b, radius, top, bottom)

        if (spec.frameVisible) {
            paint.shader = glassEdge
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2.5f))
        }
    }

    private fun drawFrosted(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())

        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        // 奶白雾化层：比冷光玻璃更柔、更接近磨砂玻璃。
        paint.shader = null
        paint.color = if (spec.pressed) 0x08FFFFFF else 0x12FFFFFF
        face(canvas, b, radius, top, bottom)
        if (!spec.pressed && spec.highlightVisible) {
            paint.shader = glassGlow
            face(canvas, b, radius, top, bottom)
        }
        overlayTint(canvas, b, radius, top, bottom)

        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 2.6f * spec.frameAlpha / 255f *
                (if (spec.pressed) 0.5f else 1f)).toInt().coerceIn(0, 255)
            paint.shader = null
            paint.color = rgb or (a shl 24)
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    private fun drawNeuro(
        canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float
    ) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())

        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)

        if (!spec.pressed) {
            paint.shader = neuroTop
            face(canvas, b, radius, top, bottom)
        }
        paint.shader = neuroBottom
        face(canvas, b, radius, top, bottom)

        overlayTint(canvas, b, radius, top, bottom)

        if (spec.frameVisible) {
            paint.shader = null
            paint.color = if (spec.pressed) 0x0C000000 else 0x14FFFFFF
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    private fun drawFlat(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())

        paint.shader = null
        paint.color = if (spec.pressed) spec.pressedTopColor else spec.topColor
        face(canvas, b, radius, top, bottom)

        if (!spec.pressed && spec.highlightVisible) {
            paint.color = 0x2EFFFFFF
            val lineH = (1f * spec.density).coerceAtLeast(1f)
            val inset = radius * 0.7f
            canvas.drawRect(
                b.left + inset, top + lineH, b.right - inset, top + lineH * 2f, paint
            )
        }
        overlayTint(canvas, b, radius, top, bottom)

        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 1.6f * spec.frameAlpha / 255f)
                .toInt().coerceIn(0, 255)
            paint.color = rgb or (a shl 24)
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    private fun drawMetal(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())

        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        paint.shader = brushed
        face(canvas, b, radius, top, bottom)
        if (!spec.pressed && spec.highlightVisible) {
            paint.shader = glassGlow
            face(canvas, b, radius, top, bottom)
        }
        overlayTint(canvas, b, radius, top, bottom)

        if (spec.frameVisible) {
            paint.shader = metalEdge
            stroke(canvas, b, radius, top, bottom, (1.5f * spec.density).coerceIn(1f, 3f))
        }
    }

    /** 深空哑光：低对比渐变 + 四周极细描边，完全无高光 */
    private fun drawMatte(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 1.4f * spec.frameAlpha / 255f)
                .toInt().coerceIn(0, 255)
            paint.shader = null
            paint.color = rgb or (a shl 24)
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    /** 霓虹描边：暗面 + 加亮描边 + 内侧暗线，描边就是主角 */
    private fun drawNeon(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 4.5f * spec.frameAlpha / 255f *
                (if (spec.pressed) 0.6f else 1f)).toInt().coerceIn(0, 255)
            paint.shader = null
            paint.color = rgb or (a shl 24)
            val w = (2f * spec.density).coerceIn(1.2f, 3.5f)
            stroke(canvas, b, radius, top, bottom, w)
            paint.color = 0x77000000
            val inset = w * 2.2f
            canvas.drawRoundRect(
                b.left + inset, top + inset, b.right - inset, bottom - inset,
                radius, radius, paint.apply { style = Paint.Style.STROKE; strokeWidth = w * 0.6f }
            )
            paint.style = Paint.Style.FILL
        }
    }

    /** 水晶透明：半透明面 + 亮边，透出下层底色 */
    private fun drawCrystal(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        val outerAlpha = paint.alpha
        paint.alpha = (outerAlpha * 0.68f).toInt().coerceAtLeast(0)
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        paint.alpha = outerAlpha
        if (!spec.pressed && spec.highlightVisible) {
            paint.shader = glassGlow
            face(canvas, b, radius, top, bottom)
        }
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 3f * spec.frameAlpha / 255f)
                .toInt().coerceIn(0, 255)
            paint.shader = null
            paint.color = rgb or (a shl 24)
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    /** 胶囊软键：忽略圆角设置，强制半圆胶囊，纯色 + 顶部细高光 */
    private fun drawCapsule(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        val cap = minOf(b.width(), b.height()) * 0.5f
        paint.color = spec.baseColor
        face(canvas, b, cap, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = null
        paint.color = if (spec.pressed) spec.pressedTopColor else spec.topColor
        face(canvas, b, cap, top, bottom)
        if (!spec.pressed && spec.highlightVisible) {
            paint.color = 0x38FFFFFF
            val lineH = (1f * spec.density).coerceAtLeast(1f)
            canvas.drawRect(
                b.left + cap, top + lineH, b.right - cap, top + lineH * 2f, paint
            )
        }
        overlayTint(canvas, b, cap, top, bottom)
    }

    /** 双色台阶：上下两段纯色 + 中缝，像两级台阶 */
    private fun drawStep(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = null
        val split = top + (bottom - top) * 0.46f
        val upperBottom = (split + radius).coerceAtMost(bottom)
        val lowerTop = (split - radius).coerceAtLeast(top)
        paint.color = if (spec.pressed) spec.pressedTopColor else spec.topColor
        canvas.drawRoundRect(b.left.toFloat(), top, b.right.toFloat(), upperBottom, radius, radius, paint)
        paint.color = if (spec.pressed) spec.pressedBottomColor else spec.bottomColor
        canvas.drawRoundRect(b.left.toFloat(), lowerTop, b.right.toFloat(), bottom, radius, radius, paint)
        paint.color = 0x33000000
        canvas.drawRect(b.left.toFloat() + radius, split, b.right.toFloat() - radius, split + (1f * spec.density), paint)
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 1.8f * spec.frameAlpha / 255f)
                .toInt().coerceIn(0, 255)
            paint.color = rgb or (a shl 24)
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    // ========== 拟物材质风格（搭配拟物图标） ==========

    /** 外部柔影：材质类在键帽外侧铺一圈渐隐投影，强化“实体放在面板上”的观感 */
    private fun dropShadow(canvas: Canvas, b: Rect, radius: Float, strength: Float) {
        val sp = shadowPaint ?: return
        val oldAlpha = sp.alpha
        sp.alpha = (oldAlpha * strength).toInt().coerceIn(0, 255)
        val inset = radius * 0.18f
        canvas.drawRoundRect(
            b.left.toFloat() + inset, b.top.toFloat() + inset * 1.6f,
            b.right.toFloat() - inset, b.bottom.toFloat() + inset * 1.1f,
            radius, radius, sp
        )
        sp.alpha = oldAlpha
    }

    /** 10 凹槽托盘：键帽是一块托盘，中央向内凹，图标像嵌进去 */
    private fun drawTray(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.shader = null
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)

        // 中央凹槽：留出一圈托盘边沿
        val padX = minOf(b.width() * 0.12f, 14f * spec.density)
        val padTop = (b.height() * 0.16f).coerceAtLeast(3f)
        val padBottom = (b.height() * 0.20f).coerceAtLeast(3f)
        val l = b.left.toFloat() + padX
        val r = b.right.toFloat() - padX
        val t = top + padTop
        val bm = bottom - padBottom
        val wellRadius = (radius * 0.62f).coerceAtLeast(2f)
        if (bm - t > 2f && r - l > 2f) {
            paint.shader = trayWell
            canvas.drawRoundRect(l, t, r, bm, wellRadius, wellRadius, paint)
            if (!spec.pressed && spec.highlightVisible) {
                paint.shader = trayLight
                canvas.drawRoundRect(l, t, r, bm, wellRadius, wellRadius, paint)
            }
            // 凹槽下沿的一道反光，强化内凹深度
            paint.shader = null
            paint.color = 0x2FFFFFFF.toInt()
            canvas.drawRoundRect(
                l + wellRadius * 0.5f, bm - (1f * spec.density).coerceAtLeast(1f),
                r - wellRadius * 0.5f, bm,
                1f, 1f, paint
            )
        }
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            paint.shader = metalEdge
            stroke(canvas, b, radius, top, bottom, (1.2f * spec.density).coerceIn(1f, 2.5f))
        }
    }

    /** 11 柔和凸起：整块键帽像一颗打磨过的圆角实体，顶漫反光、底接触阴影 */
    private fun drawEmboss(canvas: Canvas, b: Rect, radius: Float, top: Float, faceBottom: Float) {
        dropShadow(canvas, b, radius, if (spec.pressed) 0.35f else 1f)
        paint.shader = null
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, faceBottom)
        if (!spec.pressed) {
            paint.shader = embossTop
            face(canvas, b, radius, top, faceBottom)
        }
        paint.shader = embossShade
        face(canvas, b, radius, top, faceBottom)
        overlayTint(canvas, b, radius, top, faceBottom)
        if (spec.frameVisible) {
            paint.shader = null
            paint.color = if (spec.pressed) 0x14000000 else 0x26FFFFFF.toInt()
            stroke(canvas, b, radius, top, faceBottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    /** 12 聚光展台：暗面 + 中心径向光晕，图标像被一束顶光照亮 */
    private fun drawSpotlight(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        paint.shader = null
        paint.color = if (spec.pressed) ColorUtils.blendARGB(spec.baseColor, Color.BLACK, 0.18f) else spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        // 四周压暗，让中心光更聚焦
        paint.shader = null
        paint.color = 0x2E000000
        stroke(canvas, b, radius, top, bottom, (2f * spec.density).coerceIn(1.5f, 4f))
        paint.shader = spotGlow
        face(canvas, b, radius, top, bottom)
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            val rgb = spec.strokeColor and 0x00FFFFFF
            val a = (Color.alpha(spec.strokeColor) * 2.2f * spec.frameAlpha / 255f)
                .toInt().coerceIn(0, 255)
            paint.shader = null
            paint.color = rgb or (a shl 24)
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    /** 13 皮革软垫：低饱和哑光底 + 细密压纹 + 柔和缝边 */
    private fun drawLeather(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        dropShadow(canvas, b, radius, if (spec.pressed) 0.3f else 0.85f)
        paint.shader = null
        paint.color = spec.baseColor
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        paint.shader = leatherGrain
        face(canvas, b, radius, top, bottom)
        if (!spec.pressed && spec.highlightVisible) {
            paint.shader = embossTop
            face(canvas, b, radius, top, bottom)
        }
        overlayTint(canvas, b, radius, top, bottom)
        // 缝线：内缩一圈的虚线，柔和米色
        if (spec.frameVisible) {
            paint.shader = null
            paint.color = 0x33F0E2CC.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (0.8f * spec.density).coerceAtLeast(0.7f)
            paint.pathEffect = DashPathEffect(floatArrayOf(3f * spec.density, 2.5f * spec.density), 0f)
            val inset = (radius * 0.55f).coerceAtLeast(2f)
            canvas.drawRoundRect(
                b.left.toFloat() + inset, top + inset,
                b.right.toFloat() - inset, bottom - inset,
                radius * 0.7f, radius * 0.7f, paint
            )
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
        }
    }

    /** 14 陶瓷釉面：温润白釉 + 一道弧形高光，深色/浅色底色均适用 */
    private fun drawCeramic(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        dropShadow(canvas, b, radius, if (spec.pressed) 0.3f else 0.9f)
        paint.shader = null
        // 用用户底色打底，再覆一层釉白，保留配色倾向
        paint.color = ColorUtils.blendARGB(spec.baseColor, 0xFFF3F1EC.toInt(), 0.62f)
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        paint.shader = faceGradient
        face(canvas, b, radius, top, bottom)
        paint.shader = null
        paint.color = 0x8CFFFFFF.toInt()
        face(canvas, b, radius, top, bottom)
        paint.shader = ceramicGlaze
        face(canvas, b, radius, top, bottom)
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            paint.shader = null
            paint.color = if (spec.pressed) 0x1A000000 else 0x33FFFFFF.toInt()
            stroke(canvas, b, radius, top, bottom, (1f * spec.density).coerceIn(0.8f, 2f))
        }
    }

    /** 15 香槟拉丝：暖金拉丝面 + 金属描边，高级金属质感 */
    private fun drawChampagne(canvas: Canvas, b: Rect, radius: Float, top: Float, bottom: Float) {
        dropShadow(canvas, b, radius, if (spec.pressed) 0.35f else 0.95f)
        paint.shader = null
        paint.color = if (spec.pressed) 0xFF6E5A42.toInt() else 0xFF8F7355.toInt()
        face(canvas, b, radius, b.top.toFloat(), b.bottom.toFloat())
        // 暖色面：把用户上下色与香槟金混合，既跟随配色又有金属感
        val warmTop = ColorUtils.blendARGB(spec.topColor, 0xFFE8CBA6.toInt(), 0.55f)
        val warmBottom = ColorUtils.blendARGB(spec.bottomColor, 0xFF9E7E5B.toInt(), 0.55f)
        val warmPressedTop = ColorUtils.blendARGB(spec.pressedTopColor, 0xFFBFA07A.toInt(), 0.5f)
        val warmPressedBottom = ColorUtils.blendARGB(spec.pressedBottomColor, 0xFF7A6247.toInt(), 0.5f)
        val warmGrad = LinearGradient(
            0f, top, 0f, bottom,
            if (spec.pressed) warmPressedTop else warmTop,
            if (spec.pressed) warmPressedBottom else warmBottom,
            Shader.TileMode.CLAMP
        )
        paint.shader = warmGrad
        face(canvas, b, radius, top, bottom)
        paint.shader = brushed
        face(canvas, b, radius, top, bottom)
        if (!spec.pressed && spec.highlightVisible) {
            paint.shader = glassGlow
            face(canvas, b, radius, top, bottom)
        }
        overlayTint(canvas, b, radius, top, bottom)
        if (spec.frameVisible) {
            paint.shader = champagneEdge
            stroke(canvas, b, radius, top, bottom, (1.5f * spec.density).coerceIn(1f, 3f))
        }
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        currentFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION", "deprecation")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getConstantState(): ConstantState = KeycapState(spec)

    override fun mutate(): Drawable = this

    private class KeycapState(private val spec: Spec) : ConstantState() {
        override fun newDrawable(): Drawable = KeycapDrawable(spec)
        override fun getChangingConfigurations(): Int = 0
    }

    companion object {
        const val STYLE_GLASS = 0
        const val STYLE_FROSTED = 1
        const val STYLE_NEURO = 2
        const val STYLE_FLAT = 3
        const val STYLE_METAL = 4
        const val STYLE_MATTE = 5
        const val STYLE_NEON = 6
        const val STYLE_CRYSTAL = 7
        const val STYLE_CAPSULE = 8
        const val STYLE_STEP = 9
        // 拟物材质（搭配拟物图标）
        const val STYLE_TRAY = 10
        const val STYLE_EMBOSS = 11
        const val STYLE_SPOTLIGHT = 12
        const val STYLE_LEATHER = 13
        const val STYLE_CERAMIC = 14
        const val STYLE_CHAMPAGNE = 15
        const val STYLE_COUNT = 16

        val STYLE_NAMES = arrayOf(
            "冷光玻璃", "薄雾玻璃", "新拟物", "极简分层", "重画金属",
            "深空哑光", "霓虹描边", "水晶透明", "胶囊软键", "双色台阶",
            "凹槽托盘", "柔和凸起", "聚光展台", "皮革软垫", "陶瓷釉面", "香槟拉丝"
        )
    }
}
