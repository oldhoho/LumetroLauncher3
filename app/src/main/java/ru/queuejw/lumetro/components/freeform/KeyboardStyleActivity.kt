package ru.queuejw.lumetro.components.freeform

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.button
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.row
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.settingsColorRow
import ru.queuejw.lumetro.ai.settingsGroup
import ru.queuejw.lumetro.ai.settingsImageRow
import ru.queuejw.lumetro.ai.settingsSlider
import ru.queuejw.lumetro.ai.settingsSwitch
import ru.queuejw.lumetro.ai.settingsValueRow
import ru.queuejw.lumetro.components.ui.dialog.ColorDialog
import ru.queuejw.lumetro.components.ui.keycap.IconWindowDrawable
import ru.queuejw.lumetro.components.ui.keycap.KeycapDrawable

/** 九键面板与工作台外观。视觉统一到 AI 窗的 CodexUi 卡片风格。 */
class KeyboardStyleActivity : AppCompatActivity() {

    private lateinit var style: WorkbenchStyle
    private lateinit var content: LinearLayout
    private var pendingColorTarget: ((String) -> Unit)? = null
    private var pendingImageKey: String? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style = WorkbenchStyle(this)

        supportFragmentManager.setFragmentResultListener("color", this) { _, bundle ->
            val value = bundle.getString("color_value") ?: return@setFragmentResultListener
            pendingColorTarget?.invoke(value)
        }

        setContentView(CodexUi.root(this) {
            content = this
            title("九键与工作台", "所有改动实时同步，返回后自动应用")
            buildRows()
        })
    }

    override fun onPause() {
        super.onPause()
        applyToPanels()
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val key = pendingImageKey ?: return
        val maxEdge = if (key == StyleImageStore.KEYCAP || key == StyleImageStore.KEYCAP_FRAME) {
            StyleImageStore.MAX_EDGE_KEYCAP
        } else {
            StyleImageStore.MAX_EDGE_BACKGROUND
        }
        ioScope.launch {
            val ok = StyleImageStore.import(this@KeyboardStyleActivity, key, uri, maxEdge)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@KeyboardStyleActivity,
                    if (ok) "图片已应用" else "图片导入失败（尺寸过大或格式不支持）",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) buildRows()
            }
        }
    }

    private fun resetAll() {
        style.reset()
        listOf(
            StyleImageStore.KEYBOARD_PANEL, StyleImageStore.AI_BAR, StyleImageStore.WORKBENCH,
            StyleImageStore.KEYCAP, StyleImageStore.KEYCAP_FRAME
        ).forEach { StyleImageStore.clear(this, it) }
        buildRows()
        applyToPanels()
    }

    private fun applyToPanels() {
        val manager = WorkbenchManager.getInstance() ?: return
        manager.rebuildAppList()
        manager.refreshWorkbenchAppearance()
    }

    private fun buildRows() {
        content.removeAllViews()

        // ===== 九键面板 =====
        content.settingsGroup("九键面板", "键盘本体、键帽与文字")
        content.section("键盘本体") {
            settingsSlider("键盘高度", 140, 320, style.keyboardHeightDp, "dp") { style.keyboardHeightDp = it }
            colorRow("键盘底色", { style.keyboardPanelColor }) { style.keyboardPanelColor = it }
            settingsSlider("键盘不透明度", 0, 100, style.keyboardPanelAlpha, "%") { style.keyboardPanelAlpha = it }
            imageRow("面板背景图片", StyleImageStore.KEYBOARD_PANEL)
        }
        content.section("键帽") {
            valueRow(
                "键帽风格", KeycapDrawable.STYLE_NAMES.getOrNull(style.keycapStyle).orEmpty(),
                KeycapDrawable.STYLE_NAMES.toList()
            ) { style.keycapStyle = it }
            settingsSlider("键帽圆角", 0, 20, style.keycapCornerDp, "dp") { style.keycapCornerDp = it }
            settingsSlider("键帽厚度", 0, 8, style.keycapDepthDp, "dp") { style.keycapDepthDp = it }
            settingsSlider("键间距", 0, 10, style.keyGapDp, "dp") { style.keyGapDp = it }
            colorRow("按键顶部色", { style.keyTopColor }) { style.keyTopColor = it }
            colorRow("按键底部色", { style.keyBottomColor }) { style.keyBottomColor = it }
            colorRow("按下顶部色", { style.keyPressedTopColor }) { style.keyPressedTopColor = it }
            colorRow("按下底部色", { style.keyPressedBottomColor }) { style.keyPressedBottomColor = it }
            settingsSwitch("金属边框贴图", style.keycapFrameEnabled) { style.keycapFrameEnabled = it }
            settingsSlider("边框不透明度", 0, 100, style.keycapFrameAlpha, "%") { style.keycapFrameAlpha = it }
            imageRow("边框图片", StyleImageStore.KEYCAP_FRAME)
            imageRow("键帽贴图", StyleImageStore.KEYCAP)
        }
        content.section("文字") {
            settingsSlider("数字键字号", 10, 24, style.digitTextSizeSp, "sp") { style.digitTextSizeSp = it }
            settingsSlider("功能键字号", 8, 20, style.functionTextSizeSp, "sp") { style.functionTextSizeSp = it }
            settingsSlider("副文字字号", 6, 14, style.subTextSizeSp, "sp") { style.subTextSizeSp = it }
            colorRow("主文字颜色", { style.primaryTextColor }) { style.primaryTextColor = it }
            colorRow("副文字颜色", { style.subTextColor }) { style.subTextColor = it }
        }

        // ===== 工作台 =====
        content.settingsGroup("工作台", "底部工作条、图标与键帽")
        content.section("工作条") {
            colorRow("工作条底色", { style.workbenchColor }) { style.workbenchColor = it }
            settingsSlider("工作条不透明度", 0, 100, style.workbenchAlpha, "%") { style.workbenchAlpha = it }
            imageRow("工作条背景图片", StyleImageStore.WORKBENCH)
            settingsSlider("工作条圆角", 0, 40, style.workbenchCornerDp, "dp") { style.workbenchCornerDp = it }
            settingsSwitch("顶部高光线", style.workbenchLineVisible) { style.workbenchLineVisible = it }
            colorRow("高光线颜色", { style.workbenchLineColor }) { style.workbenchLineColor = it }
        }
        content.section("应用图标") {
            settingsSlider("图标大小", 20, 48, style.workbenchIconSizeDp, "dp") { style.workbenchIconSizeDp = it }
            valueRow(
                "裁切样式",
                IconWindowDrawable.STYLE_NAMES.getOrNull(style.workbenchIconCropStyle).orEmpty(),
                IconWindowDrawable.STYLE_NAMES.toList()
            ) { style.workbenchIconCropStyle = it }
            hint("系统默认图标按所选窗口裁切统一造型；图标包已覆盖的图标与搜索按钮保留原样")
            settingsSlider("名称字号", 6, 12, style.workbenchNameTextSizeSp, "sp") {
                style.workbenchNameTextSizeSp = it
            }
            colorRow("名称颜色", { style.workbenchNameColor }) { style.workbenchNameColor = it }
            colorRow("冻结角标颜色", { style.badgeColor }) { style.badgeColor = it }
        }
        content.section("工作台键帽") {
            valueRow(
                "键帽风格", KeycapDrawable.STYLE_NAMES.getOrNull(style.workbenchKeycapStyle).orEmpty(),
                KeycapDrawable.STYLE_NAMES.toList()
            ) { style.workbenchKeycapStyle = it }
            settingsSlider("键帽圆角", 0, 20, style.workbenchKeycapCornerDp, "dp") {
                style.workbenchKeycapCornerDp = it
            }
            settingsSlider("键帽厚度", 0, 8, style.workbenchKeycapDepthDp, "dp") {
                style.workbenchKeycapDepthDp = it
            }
            colorRow("按键顶部色", { style.workbenchKeyTopColor }) { style.workbenchKeyTopColor = it }
            colorRow("按键底部色", { style.workbenchKeyBottomColor }) { style.workbenchKeyBottomColor = it }
            colorRow("按下顶部色", { style.workbenchKeyPressedTopColor }) { style.workbenchKeyPressedTopColor = it }
            colorRow("按下底部色", { style.workbenchKeyPressedBottomColor }) { style.workbenchKeyPressedBottomColor = it }
            settingsSwitch("金属边框贴图", style.workbenchKeycapFrameEnabled) {
                style.workbenchKeycapFrameEnabled = it
            }
            settingsSlider("边框不透明度", 0, 100, style.workbenchKeycapFrameAlpha, "%") {
                style.workbenchKeycapFrameAlpha = it
            }
            hint("边框与键帽贴图复用九键面板的图片；样式改动返回后应用")
        }

        // ===== AI 栏 =====
        content.settingsGroup("AI 栏", "桌面顶部的会话入口")
        content.section("显示与配色") {
            settingsSwitch("显示 AI 栏", style.aiBarVisible) { style.aiBarVisible = it }
            colorRow("AI 栏底色", { style.aiBarColor }) { style.aiBarColor = it }
            colorRow("AI 栏文字色", { style.aiBarTextColor }) { style.aiBarTextColor = it }
            imageRow("AI 栏背景图片", StyleImageStore.AI_BAR)
        }

        // ===== 全局 =====
        content.settingsGroup("全局", "性能与收尾")
        content.section("性能") {
            settingsSwitch("省性能模式（关闭面板阴影）", style.reduceEffects) {
                style.reduceEffects = it
            }
        }
        content.section("操作") {
            hint("恢复默认会清空全部外观设置与导入的图片")
            row {
                button("恢复默认") { resetAll() }
                button("完成") { finish() }
            }
        }
        applyToPanels()
    }

    private fun LinearLayout.valueRow(title: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
        settingsValueRow(title, value) {
            AlertDialog.Builder(this@KeyboardStyleActivity)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), options.indexOf(value).coerceAtLeast(0)) { d, which ->
                    onSelect(which)
                    d.dismiss()
                    buildRows()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun LinearLayout.colorRow(title: String, get: () -> String, set: (String) -> Unit) {
        var swatch: View? = null
        swatch = settingsColorRow(title, WorkbenchStyle.colorOf(get())) {
            pendingColorTarget = { hex ->
                set(WorkbenchStyle.mergeColor(get(), hex))
                swatch?.background = GradientDrawable().apply {
                    cornerRadius = CodexUi.dp(this@KeyboardStyleActivity, 5).toFloat()
                    setColor(WorkbenchStyle.colorOf(get()))
                    setStroke(CodexUi.dp(this@KeyboardStyleActivity, 1), 0x66FFFFFF)
                }
            }
            ColorDialog(this@KeyboardStyleActivity)
                .apply {
                    arguments = bundleOf(ColorDialog.ARG_INITIAL_COLOR to WorkbenchStyle.colorOf(get()))
                }
                .show(supportFragmentManager, "color")
        }
    }

    private fun LinearLayout.imageRow(title: String, key: String) {
        settingsImageRow(
            title,
            StyleImageStore.has(this@KeyboardStyleActivity, key),
            {
                pendingImageKey = key
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    },
                    REQ_IMAGE
                )
            },
            {
                StyleImageStore.clear(this@KeyboardStyleActivity, key)
                buildRows()
            }
        )
    }

    companion object {
        private const val REQ_IMAGE = 4001
    }
}
