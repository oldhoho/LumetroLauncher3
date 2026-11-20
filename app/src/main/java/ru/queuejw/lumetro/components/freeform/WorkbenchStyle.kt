package ru.queuejw.lumetro.components.freeform

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * 九键面板与底部工作条的外观设置。默认值即原始外观，未改过任何项时视觉不变。
 */
class WorkbenchStyle(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("workbench_style", Context.MODE_PRIVATE)

    // ========== 九键面板 ==========
    var keyboardHeightDp: Int
        get() = prefs.getInt("keyboard_height", 200)
        set(value) = prefs.edit().putInt("keyboard_height", value.coerceIn(140, 320)).apply()

    var keyboardPanelColor: String
        get() = prefs.getString("keyboard_panel_color", "#141414")!!
        set(value) = prefs.edit().putString("keyboard_panel_color", value).apply()

    var keyboardPanelAlpha: Int
        get() = prefs.getInt("keyboard_panel_alpha", 100)
        set(value) = prefs.edit().putInt("keyboard_panel_alpha", value.coerceIn(0, 100)).apply()

    // ========== 键帽 ==========
    var keycapCornerDp: Int
        get() = prefs.getInt("keycap_corner", 8)
        set(value) = prefs.edit().putInt("keycap_corner", value.coerceIn(0, 20)).apply()

    var keycapDepthDp: Int
        get() = prefs.getInt("keycap_depth", 3)
        set(value) = prefs.edit().putInt("keycap_depth", value.coerceIn(0, 8)).apply()

    var keyGapDp: Int
        get() = prefs.getInt("key_gap", 2)
        set(value) = prefs.edit().putInt("key_gap", value.coerceIn(0, 10)).apply()

    var keyTopColor: String
        get() = prefs.getString("key_top_color", "#2E2E2E")!!
        set(value) = prefs.edit().putString("key_top_color", value).apply()

    var keyBottomColor: String
        get() = prefs.getString("key_bottom_color", "#1E1E1E")!!
        set(value) = prefs.edit().putString("key_bottom_color", value).apply()

    var keyPressedTopColor: String
        get() = prefs.getString("key_pressed_top_color", "#1A1A1A")!!
        set(value) = prefs.edit().putString("key_pressed_top_color", value).apply()

    var keyPressedBottomColor: String
        get() = prefs.getString("key_pressed_bottom_color", "#141414")!!
        set(value) = prefs.edit().putString("key_pressed_bottom_color", value).apply()

    var keycapFrameEnabled: Boolean
        get() = prefs.getBoolean("keycap_frame", true)
        set(value) = prefs.edit().putBoolean("keycap_frame", value).apply()

    var keycapFrameAlpha: Int
        get() = prefs.getInt("keycap_frame_alpha", 100)
        set(value) = prefs.edit().putInt("keycap_frame_alpha", value.coerceIn(0, 100)).apply()

    /** 关闭面板阴影等视觉效果，换取呼出与滚动更流畅 */
    var reduceEffects: Boolean
        get() = prefs.getBoolean("reduce_effects", false)
        set(value) = prefs.edit().putBoolean("reduce_effects", value).apply()

    // ========== 文字 ==========
    var digitTextSizeSp: Int
        get() = prefs.getInt("digit_text_size", 14)
        set(value) = prefs.edit().putInt("digit_text_size", value.coerceIn(10, 24)).apply()

    var functionTextSizeSp: Int
        get() = prefs.getInt("function_text_size", 11)
        set(value) = prefs.edit().putInt("function_text_size", value.coerceIn(8, 20)).apply()

    var subTextSizeSp: Int
        get() = prefs.getInt("sub_text_size", 8)
        set(value) = prefs.edit().putInt("sub_text_size", value.coerceIn(6, 14)).apply()

    var primaryTextColor: String
        get() = prefs.getString("primary_text_color", "#FFE8E8E8")!!
        set(value) = prefs.edit().putString("primary_text_color", value).apply()

    var subTextColor: String
        get() = prefs.getString("sub_text_color", "#99E8E8E8")!!
        set(value) = prefs.edit().putString("sub_text_color", value).apply()

    // ========== AI 栏 ==========
    var aiBarVisible: Boolean
        get() = prefs.getBoolean("ai_bar_visible", true)
        set(value) = prefs.edit().putBoolean("ai_bar_visible", value).apply()

    var aiBarColor: String
        get() = prefs.getString("ai_bar_color", "#0F0F0F")!!
        set(value) = prefs.edit().putString("ai_bar_color", value).apply()

    var aiBarTextColor: String
        get() = prefs.getString("ai_bar_text_color", "#CCE8E8E8")!!
        set(value) = prefs.edit().putString("ai_bar_text_color", value).apply()

    // ========== 工作条 ==========
    var workbenchColor: String
        get() = prefs.getString("workbench_color", "#1A1A1A")!!
        set(value) = prefs.edit().putString("workbench_color", value).apply()

    var workbenchAlpha: Int
        get() = prefs.getInt("workbench_alpha", 100)
        set(value) = prefs.edit().putInt("workbench_alpha", value.coerceIn(0, 100)).apply()

    var workbenchCornerDp: Int
        get() = prefs.getInt("workbench_corner", 0)
        set(value) = prefs.edit().putInt("workbench_corner", value.coerceIn(0, 40)).apply()

    var workbenchLineVisible: Boolean
        get() = prefs.getBoolean("workbench_line_visible", true)
        set(value) = prefs.edit().putBoolean("workbench_line_visible", value).apply()

    var workbenchLineColor: String
        get() = prefs.getString("workbench_line_color", "#33FFFFFF")!!
        set(value) = prefs.edit().putString("workbench_line_color", value).apply()

    var workbenchIconSizeDp: Int
        get() = prefs.getInt("workbench_icon_size", 28)
        set(value) = prefs.edit().putInt("workbench_icon_size", value.coerceIn(20, 48)).apply()

    var workbenchNameTextSizeSp: Int
        get() = prefs.getInt("workbench_name_text_size", 7)
        set(value) = prefs.edit().putInt("workbench_name_text_size", value.coerceIn(6, 12)).apply()

    var workbenchNameColor: String
        get() = prefs.getString("workbench_name_color", "#CCFFFFFF")!!
        set(value) = prefs.edit().putString("workbench_name_color", value).apply()

    var badgeColor: String
        get() = prefs.getString("badge_color", "#FF4488FF")!!
        set(value) = prefs.edit().putString("badge_color", value).apply()

    // ========== 派生颜色 ==========
    val keyboardPanel: Int get() = withAlpha(colorOf(keyboardPanelColor), keyboardPanelAlpha)

    val workbenchBackground: Int get() = withAlpha(colorOf(workbenchColor), workbenchAlpha)

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {

        fun colorOf(hex: String): Int = try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Color.BLACK
        }

        /** 按百分比覆盖颜色的透明度通道 */
        fun withAlpha(color: Int, percent: Int): Int =
            ColorUtils.setAlphaComponent(color, (percent.coerceIn(0, 100) * 255 / 100))

        /** 用新色替换 RGB，保留原色的透明度通道 */
        fun mergeColor(oldHex: String, newHex: String): String {
            val old = colorOf(oldHex)
            val new = colorOf(newHex)
            return String.format(
                "#%02X%02X%02X%02X",
                Color.alpha(old),
                Color.red(new),
                Color.green(new),
                Color.blue(new)
            )
        }
    }
}
