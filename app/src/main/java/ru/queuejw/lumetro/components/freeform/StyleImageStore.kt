package ru.queuejw.lumetro.components.freeform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 九键与工作台的自定义图片：导入时限制解码尺寸并压缩落盘，读取走内存缓存，
 * 避免大图解码阻塞主线程或反复解码。
 */
object StyleImageStore {

    const val KEYBOARD_PANEL = "keyboard_panel"
    const val AI_BAR = "ai_bar"
    const val WORKBENCH = "workbench"
    const val KEYCAP = "keycap"
    const val KEYCAP_FRAME = "keycap_frame"

    const val MAX_EDGE_BACKGROUND = 1080
    const val MAX_EDGE_KEYCAP = 512

    private const val DIR = "style_images"
    private const val MAX_FILE_BYTES = 400 * 1024
    private const val MAX_SOURCE_PIXELS = 40_000_000L

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun has(context: Context, key: String): Boolean = file(context, key).exists()

    fun clear(context: Context, key: String) {
        file(context, key).delete()
        cache.remove(key)
    }

    fun load(context: Context, key: String): Bitmap? {
        cache.get(key)?.let { return it }
        val source = file(context, key)
        if (!source.exists()) return null
        return try {
            BitmapFactory.decodeFile(source.absolutePath)?.also { cache.put(key, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun background(context: Context, key: String, alphaPercent: Int): Drawable? {
        val bitmap = load(context, key) ?: return null
        return FittedBitmapDrawable(bitmap, alphaPercent.coerceIn(0, 100) * 255 / 100)
    }

    suspend fun import(context: Context, key: String, uri: Uri, maxEdge: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
                if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) return@withContext false

                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) {
                    sample *= 2
                }

                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                var bitmap = resolver.openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@withContext false

                val longest = maxOf(bitmap.width, bitmap.height)
                if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaled !== bitmap) bitmap.recycle()
                    bitmap = scaled
                }

                saveCompressed(bitmap, file(context, key))
                cache.put(key, bitmap)
                true
            } catch (e: Exception) {
                false
            }
        }

    private fun saveCompressed(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        var quality = 85
        while (true) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (output.size() <= MAX_FILE_BYTES || quality <= 40) {
                target.writeBytes(output.toByteArray())
                return
            }
            quality -= 10
        }
    }

    private fun file(context: Context, key: String): File =
        File(File(context.filesDir, DIR), "$key.jpg")
}

/**
 * 按 View 尺寸一次性预缩放并居中裁剪，之后 draw 只做 1:1 位图拷贝，
 * 避免每帧重复缩放采样。尺寸变化时重新生成。
 */
internal class FittedBitmapDrawable(
    private val source: Bitmap,
    private val baseAlpha: Int
) : Drawable() {

    private var fitted: Bitmap? = null
    private var fittedWidth = 0
    private var fittedHeight = 0

    override fun onBoundsChange(bounds: Rect) {
        fit(bounds.width(), bounds.height())
    }

    private fun fit(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return
        if (width == fittedWidth && height == fittedHeight && fitted != null) return

        fitted?.recycle()
        fitted = render(width, height)
        fittedWidth = width
        fittedHeight = height
    }

    private fun render(width: Int, height: Int): Bitmap? = try {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { alpha = baseAlpha }
        canvas.drawBitmap(source, null, RectF(left, top, left + scaledWidth, top + scaledHeight), paint)
        output
    } catch (e: Throwable) {
        null
    }

    override fun draw(canvas: Canvas) {
        val bitmap = fitted ?: return
        canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
    }

    override fun setAlpha(alpha: Int) {
        // 透明度已在预渲染时合并
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // 背景图不支持外部颜色滤镜
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
