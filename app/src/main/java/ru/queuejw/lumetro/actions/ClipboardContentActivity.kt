package ru.queuejw.lumetro.actions

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService
import ru.queuejw.lumetro.components.inspire.ClipboardUtils

/**
 * Transparent foreground activity used by the clipboard tile: reading the clipboard
 * requires the app to be in the foreground on Android 10+.
 */
class ClipboardContentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = SidebarAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val text = ClipboardUtils.getText(this)
        if (text.isNotBlank()) {
            service.processClipboardText(text)
        } else {
            val imageUri = ClipboardUtils.getImage(this)
            if (imageUri == null) {
                Toast.makeText(this, "剪贴板中没有可处理的文字或图片", Toast.LENGTH_SHORT).show()
            } else {
                service.processClipboardImage(imageUri)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
