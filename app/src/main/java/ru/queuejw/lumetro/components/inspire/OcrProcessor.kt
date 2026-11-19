package ru.queuejw.lumetro.components.inspire

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.fleey.ppocrv5.ocr.AcceleratorType
import me.fleey.ppocrv5.ocr.OcrEngine
import me.fleey.ppocrv5.ocr.OcrResult
import ru.queuejw.lumetro.components.core.Lumetro
import kotlin.math.ceil
import kotlin.math.floor

data class OcrProcessingResult(
    val rawText: String = "",
    val imageTextNodes: List<ScreenContentNode> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

/**
 * Serialises all work on the single native PP-OCRv5 engine and converts its rotated
 * boxes into screen-coordinate nodes.
 */
object OcrProcessor {
    private const val TAG = "OcrProcessor"
    private const val OCR_PADDING_DP = 10

    private val initMutex = Mutex()
    private val useMutex = Mutex()

    @Volatile
    private var engine: OcrEngine? = null

    suspend fun preload() = withContext(Dispatchers.Default) {
        initMutex.withLock {
            if (engine == null) engine = createEngine()
        }
    }

    suspend fun process(originalBitmap: Bitmap): OcrProcessingResult =
        withContext(Dispatchers.Default) {
            useMutex.withLock {
                val ocrEngine = engine ?: initMutex.withLock {
                    engine ?: createEngine().also { engine = it }
                } ?: return@withLock OcrProcessingResult()

                val paddingPx = OCR_PADDING_DP.dp2px.toInt()
                originalBitmap.useSoftwareBitmap { source ->
                    val padded = createPaddedBitmap(source, paddingPx)
                    try {
                        val nodes = ocrEngine.process(padded)
                            .mapNotNull { it.toSourceNode(source.width, source.height, paddingPx) }
                            .sortedWith(NODE_POSITION_COMPARATOR)
                        OcrProcessingResult(
                            rawText = OcrTextFormatter.buildText(nodes),
                            imageTextNodes = nodes,
                            imageWidth = source.width,
                            imageHeight = source.height,
                        )
                    } finally {
                        if (!padded.isRecycled) padded.recycle()
                    }
                }
            }
        }

    fun segment(context: Context, rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        return SmartSegmenter.segment(context.applicationContext, rawText)
    }

    suspend fun shutdown() = withContext(Dispatchers.Default) {
        useMutex.withLock {
            engine?.close()
            engine = null
        }
    }

    private fun createEngine(): OcrEngine? =
        runCatching { OcrEngine.create(Lumetro.instance, AcceleratorType.CPU).getOrThrow() }
            .onFailure { Log.e(TAG, "OCR engine init failed", it) }
            .getOrNull()

    private fun createPaddedBitmap(source: Bitmap, paddingPx: Int): Bitmap {
        val padded = createBitmap(
            source.width + paddingPx * 2,
            source.height + paddingPx * 2,
        )
        Canvas(padded).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, paddingPx.toFloat(), paddingPx.toFloat(), null)
        }
        return padded
    }

    private fun OcrResult.toSourceNode(
        sourceWidth: Int,
        sourceHeight: Int,
        paddingPx: Int,
    ): ScreenContentNode? {
        if (text.isBlank()) return null
        val corners = box.getCorners()
        if (corners.isEmpty()) return null
        val bound = Rect(
            floor(corners.minOf { it.x }.toDouble()).toInt() - paddingPx,
            floor(corners.minOf { it.y }.toDouble()).toInt() - paddingPx,
            ceil(corners.maxOf { it.x }.toDouble()).toInt() - paddingPx,
            ceil(corners.maxOf { it.y }.toDouble()).toInt() - paddingPx,
        )
        if (!bound.intersect(0, 0, sourceWidth, sourceHeight)) return null
        return if (bound.isEmpty) null else ScreenContentNode(bound, text)
    }

    private val NODE_POSITION_COMPARATOR = compareBy<ScreenContentNode>(
        { it.bound.top },
        { it.bound.centerY() },
        { it.bound.left },
    )
}
