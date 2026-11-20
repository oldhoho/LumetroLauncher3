package ru.queuejw.lumetro.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.saveKey
import ru.queuejw.lumetro.utils.settingsStore

class InspireSettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var modeValue: TextView
    private lateinit var deleteValue: TextView
    private lateinit var autoDeleteRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(container) })

        addTitle("屏幕取字设置")

        addSection("框选取字")
        addSwitch(
            "OCR 优先", KEY_OCR_PRIORITY, true,
            "框选后优先用 OCR 识别屏幕文字；关闭则先读无障碍文本，失败再走 OCR。"
        )
        addSwitch(
            "框选后二次确认", KEY_INSPIRE_DOUBLE_CHECK, true,
            "取字前先显示选区预览，确认后才开始识别，避免误选。"
        )
        addSwitch(
            "截图选字层", KEY_DEFAULT_IMAGE_TEXT_OVERLAY, false,
            "打开屏幕取字时直接进入截图选字，可在图片上点选或框选文字，不再先画选区。"
        )
        addSwitch(
            "链接雷达", KEY_SCREEN_CONTENT_LINK_RADAR, false,
            "取字时识别屏幕中的链接并显示打开提示，点击即可按链接规则跳转。"
        )

        addSection("Pin 池")
        addValueRow(
            "显示模式", displayModeLabel(currentDisplayMode()),
            "决定 Pin 池以全屏还是左/右侧边栏打开；跟随触发区会按手势从哪一侧呼出。"
        ) {
            showDisplayModeDialog()
        }
        addSwitch(
            "本地规则智能提取", KEY_PIN_POOL_LOCAL_RULE_EXTRACTION, false,
            "用本地 OCR 和规则猜测提醒时间、图片通知标题，只在本机处理，不上传内容。"
        )
        addSwitch(
            "自动保存图片到 Pin 池", KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES, true,
            "取字、图片编辑或复制图片时，同时存入 Pin 池的「自动保存」分类。"
        )
        addSwitch(
            "自动写入剪贴板", KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD, false,
            "上述图片保存到 Pin 池时同时写入系统剪贴板，会覆盖剪贴板原有内容。"
        )
        autoDeleteRow = addValueRow(
            "定期删除", autoDeleteLabel(),
            "自动保存分类中未置顶的旧图片会在打开 Pin 池时清理，0 天表示不删除。"
        ) { showAutoDeleteDialog() }
        updateAutoDeleteVisibility()
    }

    private fun addTitle(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun addSection(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun addSwitch(title: String, key: String, default: Boolean, description: String) {
        container.addView(Switch(this).apply {
            text = title
            isChecked = settingsStore.readKey(key, default)
            setOnCheckedChangeListener { _, checked ->
                settingsStore.saveKey(key, checked)
                if (key == KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES) updateAutoDeleteVisibility()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        })
        addDescription(description)
    }

    private fun addDescription(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun addValueRow(
        title: String,
        value: String,
        description: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueView = TextView(this).apply {
            text = value
            textSize = 15f
        }
        row.addView(valueView)
        container.addView(row)
        addDescription(description)
        when (title) {
            "显示模式" -> modeValue = valueView
            else -> deleteValue = valueView
        }
        return row
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
        var checked = modes.indexOfFirst { it.first == currentDisplayMode() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Pin 池显示模式")
            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), checked) { dialog, which ->
                checked = which
                settingsStore.saveKey(KEY_PIN_POOL_DISPLAY_MODE, modes[which].first)
                modeValue.text = modes[which].second
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
        val values = (0..PIN_POOL_AUTO_DELETE_DAYS_MAX).map { it }
        val labels = values.map { if (it == 0) "不自动删除" else "$it 天" }.toTypedArray()
        val current = settingsStore.readKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, 0)
        AlertDialog.Builder(this)
            .setTitle("定期删除")
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                settingsStore.saveKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, values[which])
                deleteValue.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateAutoDeleteVisibility() {
        val autoSave = settingsStore.readKey(KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES, true)
        autoDeleteRow.visibility = if (autoSave) LinearLayout.VISIBLE else LinearLayout.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        const val KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES =
            "key_pin_pool_save_screen_content_images"
        const val KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD =
            "key_pin_pool_auto_copy_image_to_clipboard"
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
