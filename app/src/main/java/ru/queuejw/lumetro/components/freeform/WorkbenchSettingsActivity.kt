package ru.queuejw.lumetro.components.freeform

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.ai.AiConsoleActivity
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.actionCard
import ru.queuejw.lumetro.ai.CodexUi.button
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.row
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.settingsActionCard
import ru.queuejw.lumetro.ai.settingsSlider
import ru.queuejw.lumetro.ai.settingsSwitch
import ru.queuejw.lumetro.components.core.sidebar.SidebarAccessibilityService
import ru.queuejw.lumetro.components.freeform.gesture.LeftGestureStripManager
import ru.queuejw.lumetro.gesture.TriggerSide
import ru.queuejw.lumetro.pin.PinPoolFloating
import ru.queuejw.lumetro.settings.AppearanceSettingsActivity
import ru.queuejw.lumetro.settings.InspireSettingsActivity
import ru.queuejw.lumetro.settings.SearchEngineSettingsActivity

/** 启动器总设置。视觉统一到 AI 窗的 CodexUi 卡片风格。 */
class WorkbenchSettingsActivity : AppCompatActivity() {

    private lateinit var settings: WorkbenchSettings
    private lateinit var gestureManager: LeftGestureStripManager
    private var workbenchManager: WorkbenchManager? = null

    private var gestureWidth = 4
    private var gestureHeight = 180
    private var gestureOffset = 0
    private var gestureAlpha = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = WorkbenchSettings(this)

        val service = SidebarAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(this, "无障碍服务未启动，无法调整手势条", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        gestureManager = LeftGestureStripManager.getInstance(this, service)
        workbenchManager = WorkbenchManager.getInstance()

        gestureWidth = settings.gestureStripWidth
        gestureHeight = settings.gestureStripHeight
        gestureOffset = settings.gestureStripOffset
        gestureAlpha = (settings.gestureStripAlpha * 100).toInt()

        setContentView(CodexUi.root(this) {
            title("启动器设置", "工作台 · 手势 · 九键面板，全部与悬浮层实时同步")

            section("工作台") {
                settingsSwitch("启用工作台", settings.enabled) {
                    settings.enabled = it
                    if (it) WorkbenchManager.showWorkbench() else WorkbenchManager.hideWorkbench()
                }
                settingsSlider("工作台高度", 5, 300, settings.height, "dp") {
                    settings.height = it
                    if (settings.enabled) WorkbenchManager.showWorkbench()
                }
            }

            section("左侧手势条") {
                settingsSlider("宽度", 2, 32, gestureWidth, "dp") { gestureWidth = it }
                settingsSlider("高度（0 为全屏）", 0, 820, gestureHeight, "dp") { gestureHeight = it }
                settingsSlider("垂直偏移", 0, 800, gestureOffset, "dp") { gestureOffset = it }
                settingsSlider("不透明度", 0, 100, gestureAlpha, "%") { gestureAlpha = it }
                hint("手势条设置需点下方按钮应用并持久化")
                row { button("应用手势设置") { applyGesture() } }
            }

            section("九键面板") {
                settingsSlider("面板宽度", 50, 100, (settings.appListWidthRatio * 100).toInt(), "%") {
                    settings.appListWidthRatio = it / 100f
                    workbenchManager?.hideAppsList()
                }
                settingsSlider("面板高度", 10, 200, (settings.appListHeightRatio * 100).toInt(), "%") {
                    settings.appListHeightRatio = it / 100f
                    workbenchManager?.hideAppsList()
                }
                settingsSlider("垂直偏移", 0, 400, settings.appListVerticalOffset, "dp") {
                    settings.appListVerticalOffset = it
                    workbenchManager?.hideAppsList()
                }
                settingsSlider("面板圆角", 0, 50, settings.appListCornerRadius, "dp") {
                    settings.appListCornerRadius = it
                    workbenchManager?.hideAppsList()
                }
                settingsSlider("背景暗度", 0, 90, (settings.appListDimAlpha / 0.9f * 100).toInt(), "%") {
                    settings.appListDimAlpha = it / 100f * 0.9f
                    workbenchManager?.hideAppsList()
                }
                settingsSlider("图标间距", 0, 30, settings.iconSpacing, "dp") {
                    settings.iconSpacing = it
                    if (settings.enabled) WorkbenchManager.showWorkbench()
                }
            }

            section("子设置") {
                settingsActionCard("键盘与工作台外观", "键帽十种预设、工作台独立键帽、图标裁切样式、AI 栏与配色",
                    "打开") { open(KeyboardStyleActivity::class.java) }
                settingsActionCard("屏幕取字与 Pin 池", "OCR 优先级、取字确认、显示模式与自动保存策略",
                    "打开") { open(InspireSettingsActivity::class.java) }
                settingsActionCard("搜索引擎", "管理搜索链接与默认引擎",
                    "打开") { open(SearchEngineSettingsActivity::class.java) }
                settingsActionCard("外观", "屏幕内容与 Pin 池的窗口模糊",
                    "打开") { open(AppearanceSettingsActivity::class.java) }
                settingsActionCard("AI 会话", "打开 CodexWeb 对话窗口",
                    "打开") { open(AiConsoleActivity::class.java) }
            }

            section("应用管理") {
                row {
                    button("图标包") { workbenchManager?.showIconPackPicker(this@WorkbenchSettingsActivity) }
                    button("刷新应用") { workbenchManager?.refreshApps() }
                }
                row {
                    button("冻结应用") { workbenchManager?.showFreezeManagementDialog(this@WorkbenchSettingsActivity) }
                    button("隐藏应用") { workbenchManager?.showHiddenManagementDialog(this@WorkbenchSettingsActivity) }
                }
                row {
                    button("Pin 池") {
                        PinPoolFloating.start(applicationContext, side = TriggerSide.peek())
                    }
                    button("完成") { finish() }
                }
            }
        })
        applyGesture()
    }

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }

    private fun applyGesture() {
        gestureManager.updateConfig(gestureWidth, gestureHeight, gestureOffset, gestureAlpha / 100f)
        settings.gestureStripWidth = gestureWidth
        settings.gestureStripHeight = gestureHeight
        settings.gestureStripOffset = gestureOffset
        settings.gestureStripAlpha = gestureAlpha / 100f
        Toast.makeText(this, "手势设置已应用", Toast.LENGTH_SHORT).show()
    }
}
