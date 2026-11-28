package ru.queuejw.lumetro.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.settingsSwitch
import ru.queuejw.lumetro.ai.settingsValueRow
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.saveKey
import ru.queuejw.lumetro.utils.settingsStore

class InspireSettingsActivity : AppCompatActivity() {

    private lateinit var modeValue: TextView
    private lateinit var deleteValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CodexUi.root(this) {
            title("屏幕取字设置", "取字、链接识别与 Pin 池的保存策略")
            section("框选取字") {
                switch("OCR 优先", KEY_OCR_PRIORITY, true,
                    "框选后优先用 OCR 识别屏幕文字；关闭则先读无障碍文本，失败再走 OCR。")
                switch("框选后二次确认", KEY_INSPIRE_DOUBLE_CHECK, true,
                    "取字前先显示选区预览，确认后才开始识别，避免误选。")
                switch("截图选字层", KEY_DEFAULT_IMAGE_TEXT_OVERLAY, false,
                    "打开屏幕取字时直接进入截图选字，可在图片上点选或框选文字。")
                switch("链接雷达", KEY_SCREEN_CONTENT_LINK_RADAR, false,
                    "取字时识别屏幕中的链接并显示打开提示，点击即可按规则跳转。")
            }
            section("Pin 池") {
                modeValue = settingsValueRow("显示模式", displayModeLabel(currentDisplayMode())) {
                    showDisplayModeDialog()
                }
                hint("决定 Pin 池以全屏还是左/右侧边栏打开；跟随触发区会按手势从哪一侧呼出。")
                switch("本地规则智能提取", KEY_PIN_POOL_LOCAL_RULE_EXTRACTION, false,
                    "用本地 OCR 和规则猜测提醒时间、图片通知标题，只在本机处理，不上传内容。")
                switch("自动保存图片到 Pin 池", KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES, true,
                    "取字、图片编辑或复制图片时，同时存入 Pin 池的「自动保存」分类。")
                switch("自动写入剪贴板", KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD, false,
                    "上述图片保存到 Pin 池时同时写入系统剪贴板，会覆盖原有内容。")
                deleteValue = settingsValueRow("定期删除", autoDeleteLabel()) {
                    showAutoDeleteDialog()
                }
                hint("自动保存分类中未置顶的旧图片会在打开 Pin 池时清理，0 天表示不删除。")
                updateAutoDeleteVisibility()
            }
        })
    }

    private fun LinearLayout.switch(name: String, key: String, default: Boolean, desc: String) {
        settingsSwitch(name, settingsStore.readKey(key, default)) {
            settingsStore.saveKey(key, it)
            if (key == KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES) updateAutoDeleteVisibility()
        }
        hint(desc)
    }

    private fun currentDisplayMode(): String =
        settingsStore.readKey(KEY_PIN_POOL_DISPLAY_MODE, PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR)

    private fun displayModeLabel(mode: String): String = when (mode) {
        PIN_POOL_DISPLAY_MODE_FULLSCREEN -> "全屏"
        PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR -> "左侧边栏"
        PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER -> "跟随触发区"
        else -> "右侧边栏"
    }

    private fun showDisplayModeDialog() {
        val modes = arrayOf(
            PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR to "右侧边栏",
            PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR to "左侧边栏",
            PIN_POOL_DISPLAY_MODE_FULLSCREEN to "全屏",
            PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER to "跟随触发区",
        )
        val checked = modes.indexOfFirst { it.first == currentDisplayMode() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Pin 池显示模式")
            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), checked) { dialog, which ->
                settingsStore.saveKey(KEY_PIN_POOL_DISPLAY_MODE, modes[which].first)
                modeValue.text = "${modes[which].second}  ›"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun autoDeleteLabel(): String {
        val days = settingsStore.readKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, 0)
        return if (days <= 0) "不自动删除" else "$days 天"
    }

    private fun showAutoDeleteDialog() {
        val values = (0..PIN_POOL_AUTO_DELETE_DAYS_MAX).toList()
        val labels = values.map { if (it == 0) "不自动删除" else "$it 天" }.toTypedArray()
        val current = settingsStore.readKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, 0)
        AlertDialog.Builder(this)
            .setTitle("定期删除")
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                settingsStore.saveKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, values[which])
                deleteValue.text = "${labels[which]}  ›"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateAutoDeleteVisibility() {
        if (!::deleteValue.isInitialized) return
        val autoSave = settingsStore.readKey(KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES, true)
        (deleteValue.parent as? View)?.visibility = if (autoSave) View.VISIBLE else View.GONE
    }

    companion object {
        const val KEY_OCR_PRIORITY = "inspire_ocr_priority"
        const val KEY_INSPIRE_DOUBLE_CHECK = "key_inspire_double_check"
        const val KEY_SCREEN_CONTENT_LINK_RADAR = "key_screen_content_link_radar"
        const val KEY_INSPIRE_SELECTION_VIBRATE = "key_inspire_selection_vibrate"
        const val KEY_INSPIRE_EDGE_BACK = "key_inspire_edge_back"
        const val KEY_INSPIRE_DELAY_REMOVE_IMAGE = "key_inspire_delay_remove_image"
        const val KEY_INSPIRE_SHOW_ENGINE = "key_inspire_show_engine"
        const val KEY_DEFAULT_IMAGE_TEXT_OVERLAY = "key_default_image_text_overlay"
        const val KEY_INSPIRE_STRONG_COPY = "key_inspire_strong_copy"
        const val KEY_DEFAULT_SHARE_IMAGE_APP = "key_default_share_image_app"
        const val KEY_IMAGE_APP = "key_image_app"
        const val KEY_TRANSLATION_APP = "key_translation_app"
        const val KEY_SEARCH_APP = "key_search_app"
        const val KEY_SEARCH_LINK = "key_search_link"
        const val KEY_DEFAULT_SHARE_BEHAVIOR = "key_default_share_behavior"
        const val KEY_PIN_POOL_DISPLAY_MODE = "key_pin_pool_display_mode"
        const val KEY_PIN_POOL_LOCAL_RULE_EXTRACTION = "key_pin_pool_local_rule_extraction"
        const val DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION = false
        const val KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES = "key_pin_pool_save_screen_content_images"
        const val KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD = "key_pin_pool_auto_copy_image_to_clipboard"
        const val DEFAULT_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD = false
        const val KEY_PIN_POOL_AUTO_DELETE_DAYS = "key_pin_pool_auto_delete_days"
        const val PIN_POOL_AUTO_DELETE_DAYS_MAX = 30
        const val PIN_POOL_DISPLAY_MODE_FULLSCREEN = "fullscreen"
        const val PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR = "left_sidebar"
        const val PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR = "right_sidebar"
        const val PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER = "follow_trigger"
        const val PIN_POOL_DISPLAY_MODE_LEGACY_SIDEBAR = "sidebar"
    }
}
