package ru.queuejw.lumetro.components.inspire

import android.graphics.BitmapFactory
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService

/**
 * Clipboard tokenization entry on top of the accessibility overlay layer.
 */
internal class ScreenContentController(
    private val service: SidebarAccessibilityService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tokenPanel: TokenPanelOverlay? = null
    private var workJob: Job? = null

    fun processClipboardText(text: String) {
        if (text.isBlank()) {
            toast("剪贴板中没有文字")
            return
        }
        workJob?.cancel()
        workJob = scope.launch { showTokens(text, splitLines = true) }
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
                toast("无法读取剪贴板图片（格式不支持或已失效）")
                return@launch
            }
            val result = OcrProcessor.process(bitmap)
            bitmap.recycle()
            result.error?.let { reason ->
                toast(reason)
                return@launch
            }
            if (result.rawText.isBlank()) {
                toast("未识别到文字")
                return@launch
            }
            showTokens(result.rawText, splitLines = true)
        }
    }

    fun destroy() {
        workJob?.cancel()
        tokenPanel?.dismiss()
        tokenPanel = null
    }

    private suspend fun showTokens(rawText: String, splitLines: Boolean = false) {
        val tokens = withContext(Dispatchers.Default) {
            OcrProcessor.segment(service, rawText)
        }
        val segments = if (splitLines) lineRanges(rawText) else null
        tokenPanel?.dismiss()
        tokenPanel = TokenPanelOverlay(
            context = service,
            rawText = rawText,
            tokens = tokens,
            onDismiss = { tokenPanel = null },
            segments = segments,
        ).also { it.show() }
    }

    private fun lineRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        text.forEachIndexed { index, ch ->
            if (ch == '\n') {
                if (index > start) ranges += start until index
                start = index + 1
            }
        }
        if (start < text.length) ranges += start until text.length
        return ranges
    }

    private fun toast(message: String) {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }
}
