package ru.queuejw.lumetro.actions

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService
import ru.queuejw.lumetro.components.inspire.ClipboardUtils

/**
 * Transparent foreground activity used by the clipboard tile: reading the clipboard
 * requires the app to be in the foreground on Android 10+.
 *
 * The read is deferred until the window is focused, because onCreate runs before the
 * system grants this window clipboard access and would otherwise return an empty clip.
 */
class ClipboardContentActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.postDelayed({ handleClipboard() }, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            window.decorView.postDelayed({ handleClipboard() }, FOCUS_SETTLE_MS)
        }
    }

    private fun handleClipboard() {
        if (handled) return
        handled = true
        val service = SidebarAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(0, 0)
            return
        }
        val text = ClipboardUtils.getText(this)
        if (text.isNotBlank()) {
            service.processClipboardText(text)
        } else {
            val imageUri = ClipboardUtils.getImage(this)
            if (imageUri != null) {
                service.processClipboardImage(imageUri)
            } else {
                val message = if (ClipboardUtils.hasText(this)) {
                    "剪贴板中的文字为空"
                } else {
                    "剪贴板中没有可处理的文字或图片"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val FOCUS_SETTLE_MS = 80L
        private const val FOCUS_TIMEOUT_MS = 800L
    }
}
