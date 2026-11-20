package ru.queuejw.lumetro.components.freeform

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.ui.dialog.ColorDialog
import kotlin.math.roundToInt

class KeyboardStyleActivity : AppCompatActivity() {

    private lateinit var style: WorkbenchStyle
    private lateinit var container: LinearLayout
    private var pendingColorTarget: ((String) -> Unit)? = null
    private var pendingImageKey: String? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_style)

        style = WorkbenchStyle(this)
        container = findViewById(R.id.container_style)

        supportFragmentManager.setFragmentResultListener("color", this) { _, bundle ->
            val value = bundle.getString("color_value") ?: return@setFragmentResultListener
            pendingColorTarget?.invoke(value)
        }

        findViewById<Button>(R.id.btn_style_reset).setOnClickListener {
            style.reset()
            listOf(
                StyleImageStore.KEYBOARD_PANEL,
                StyleImageStore.AI_BAR,
                StyleImageStore.WORKBENCH,
                StyleImageStore.KEYCAP,
                StyleImageStore.KEYCAP_FRAME
            ).forEach { StyleImageStore.clear(this, it) }
            buildRows()
            applyToPanels()
        }
        findViewById<Button>(R.id.btn_style_done).setOnClickListener {
            finish()
        }

        buildRows()
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
                if (ok) {
                    buildRows()
                    applyToPanels()
                }
            }
        }
    }

    private fun applyToPanels() {
        val manager = WorkbenchManager.getInstance() ?: return
        manager.rebuildAppList()
        manager.refreshWorkbenchAppearance()
    }

    private fun buildRows() {
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll_style)
        val scrollY = scroll?.scrollY ?: 0
        container.removeAllViews()

        section("性能")
        toggle("省性能模式（关闭面板阴影）", style.reduceEffects) { style.reduceEffects = it }

        section("九键面板")
        slider("键盘高度", 140, 320, style.keyboardHeightDp, "dp") { style.keyboardHeightDp = it }
        color("键盘底色", { style.keyboardPanelColor }) { style.keyboardPanelColor = it }
        slider("键盘不透明度", 0, 100, style.keyboardPanelAlpha, "%") { style.keyboardPanelAlpha = it }
        imageRow("背景图片", StyleImageStore.KEYBOARD_PANEL)

        section("键帽")
        slider("键帽圆角", 0, 20, style.keycapCornerDp, "dp") { style.keycapCornerDp = it }
        slider("键帽厚度", 0, 8, style.keycapDepthDp, "dp") { style.keycapDepthDp = it }
        slider("键间距", 0, 10, style.keyGapDp, "dp") { style.keyGapDp = it }
        color("按键顶部色", { style.keyTopColor }) { style.keyTopColor = it }
        color("按键底部色", { style.keyBottomColor }) { style.keyBottomColor = it }
        color("按下顶部色", { style.keyPressedTopColor }) { style.keyPressedTopColor = it }
        color("按下底部色", { style.keyPressedBottomColor }) { style.keyPressedBottomColor = it }
        toggle("金属边框贴图", style.keycapFrameEnabled) { style.keycapFrameEnabled = it }
        slider("边框不透明度", 0, 100, style.keycapFrameAlpha, "%") { style.keycapFrameAlpha = it }
        imageRow("边框图片", StyleImageStore.KEYCAP_FRAME)
        imageRow("键帽贴图", StyleImageStore.KEYCAP)

        section("文字")
        slider("数字键字号", 10, 24, style.digitTextSizeSp, "sp") { style.digitTextSizeSp = it }
        slider("功能键字号", 8, 20, style.functionTextSizeSp, "sp") { style.functionTextSizeSp = it }
        slider("副文字字号", 6, 14, style.subTextSizeSp, "sp") { style.subTextSizeSp = it }
        color("主文字颜色", { style.primaryTextColor }) { style.primaryTextColor = it }
        color("副文字颜色", { style.subTextColor }) { style.subTextColor = it }

        section("AI 栏")
        toggle("显示 AI 栏", style.aiBarVisible) { style.aiBarVisible = it }
        color("AI 栏底色", { style.aiBarColor }) { style.aiBarColor = it }
        imageRow("背景图片", StyleImageStore.AI_BAR)
        color("AI 栏文字色", { style.aiBarTextColor }) { style.aiBarTextColor = it }

        section("工作条")
        color("工作条底色", { style.workbenchColor }) { style.workbenchColor = it }
        slider("工作条不透明度", 0, 100, style.workbenchAlpha, "%") { style.workbenchAlpha = it }
        imageRow("背景图片", StyleImageStore.WORKBENCH)
        slider("工作条圆角", 0, 40, style.workbenchCornerDp, "dp") { style.workbenchCornerDp = it }
        toggle("顶部高光线", style.workbenchLineVisible) { style.workbenchLineVisible = it }
        color("高光线颜色", { style.workbenchLineColor }) { style.workbenchLineColor = it }
        slider("图标大小", 20, 48, style.workbenchIconSizeDp, "dp") { style.workbenchIconSizeDp = it }
        slider("图标名称字号", 6, 12, style.workbenchNameTextSizeSp, "sp") { style.workbenchNameTextSizeSp = it }
        color("图标名称颜色", { style.workbenchNameColor }) { style.workbenchNameColor = it }
        color("冻结角标颜色", { style.badgeColor }) { style.badgeColor = it }

        scroll?.post { scroll.scrollTo(0, scrollY) }
    }

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = "━━━ $title ━━━"
            setTextColor(0xFFAADDFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun slider(
        title: String,
        min: Int,
        max: Int,
        value: Int,
        unit: String,
        onChange: (Int) -> Unit
    ) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val valueView = TextView(this).apply {
            text = "$value$unit"
            setTextColor(0xFF80CBC4.toInt())
            textSize = 14f
        }
        header.addView(valueView)

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val newValue = min + progress
                    valueView.text = "$newValue$unit"
                    if (fromUser) onChange(newValue)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        column.addView(header)
        column.addView(seekBar)
        container.addView(column)
    }

    private fun toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        })

        container.addView(row)
    }

    private fun color(title: String, get: () -> String, set: (String) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(24))
        }

        fun refreshSwatch() {
            swatch.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(WorkbenchStyle.colorOf(get()))
                setStroke(dp(1), 0x55FFFFFF)
            }
        }
        refreshSwatch()
        row.addView(swatch)

        row.setOnClickListener {
            pendingColorTarget = { hex ->
                set(WorkbenchStyle.mergeColor(get(), hex))
                refreshSwatch()
            }
            ColorDialog(this).apply {
                arguments = bundleOf(ColorDialog.ARG_INITIAL_COLOR to WorkbenchStyle.colorOf(get()))
            }.show(supportFragmentManager, "color")
        }

        container.addView(row)
    }

    private fun imageRow(title: String, key: String) {
        val context = this
        val hasImage = StyleImageStore.has(context, key)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        row.addView(TextView(this).apply {
            text = if (hasImage) "$title：已设置" else "$title：未设置"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(Button(this).apply {
            text = "选择图片"
            textSize = 12f
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                pendingImageKey = key
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    },
                    REQ_IMAGE
                )
            }
        })

        row.addView(Button(this).apply {
            text = "清除"
            textSize = 12f
            minimumWidth = 0
            isEnabled = hasImage
            setOnClickListener {
                StyleImageStore.clear(context, key)
                buildRows()
                applyToPanels()
            }
        })

        container.addView(row)
    }

    companion object {
        private const val REQ_IMAGE = 4001
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
