package ru.queuejw.lumetro.components.inspire

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService

/**
 * Drives screen selection, screenshot capture, OCR and the token panel from the
 * accessibility overlay layer so it can work above other applications.
 */
internal class ScreenContentController(
    private val service: SidebarAccessibilityService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var selectionOverlay: ScreenSelectionOverlay? = null
    private var tokenPanel: TokenPanelOverlay? = null
    private var workJob: Job? = null

    fun startSelection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("屏幕取字需要 Android 11 及以上")
            return
        }
        dismissAll()
        workJob = scope.launch { OcrProcessor.preload() }
        selectionOverlay = ScreenSelectionOverlay(
            context = service,
            onSelected = { rect, screenWidth, screenHeight ->
                selectionOverlay?.dismiss()
                selectionOverlay = null
                handleSelection(rect, screenWidth, screenHeight)
            },
            onCancelled = {
                selectionOverlay?.dismiss()
                selectionOverlay = null
            },
        ).also { it.show() }
    }

    fun processClipboardText(text: String) {
        if (text.isBlank()) {
            toast("剪贴板中没有文字")
            return
        }
        workJob?.cancel()
        workJob = scope.launch { showTokens(text) }
    }

    fun processClipboardImage(imageUri: android.net.Uri) {
        workJob?.cancel()
        workJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    service.contentResolver.openInputStream(imageUri)?.use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
            if (bitmap == null) {
                toast("无法读取剪贴板图片")
                return@launch
            }
            val rawText = OcrProcessor.process(bitmap).rawText
            bitmap.recycle()
            if (rawText.isBlank()) {
                toast("未识别到文字")
                return@launch
            }
            showTokens(rawText)
        }
    }

    fun destroy() {
        workJob?.cancel()
        dismissAll()
    }

    private fun handleSelection(rect: Rect, screenWidth: Int, screenHeight: Int) {
        workJob?.cancel()
        workJob = scope.launch {
            delay(150)
            val screenshot = service.takeScreenshot()
            val bitmap = screenshot?.let { normalizeToScreen(it, screenWidth, screenHeight) }

            val captured = captureScreenContent(service, rect) {
                bitmap?.let(ManagedBitmap::from)
            }
            val rawText = try {
                val ocrText = captured?.image?.requireBitmap()?.let { crop ->
                    OcrProcessor.process(crop).rawText
                }.orEmpty()
                ocrText.ifBlank { captured?.accessibilityText.orEmpty() }
            } finally {
                captured?.close()
                if (bitmap != null && bitmap !== screenshot) bitmap.recycle()
                screenshot?.recycle()
            }

            if (rawText.isBlank()) {
                toast("未识别到文字")
                return@launch
            }
            showTokens(rawText)
        }
    }

    private fun normalizeToScreen(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (width <= 0 || height <= 0 || (bitmap.width == width && bitmap.height == height)) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private suspend fun showTokens(rawText: String) {
        val tokens = withContext(Dispatchers.Default) {
            OcrProcessor.segment(service, rawText)
        }
        tokenPanel?.dismiss()
        tokenPanel = TokenPanelOverlay(
            context = service,
            rawText = rawText,
            tokens = tokens,
            onDismiss = { tokenPanel = null },
        ).also { it.show() }
    }

    private fun dismissAll() {
        selectionOverlay?.dismiss()
        selectionOverlay = null
        tokenPanel?.dismiss()
        tokenPanel = null
    }

    private fun toast(message: String) {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }
}
