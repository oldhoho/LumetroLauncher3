package ru.queuejw.lumetro.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.settingsSlider
import ru.queuejw.lumetro.ai.settingsSwitch
import ru.queuejw.lumetro.components.prefs.Prefs

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContentView(CodexUi.root(this) {
            title("基础设置", "磁贴外观与锁定")
            section("磁贴") {
                settingsSlider("圆角半径", 0, 24, prefs.tileCornerRadius, "dp") {
                    prefs.tileCornerRadius = it
                }
                settingsSwitch("锁定磁贴", prefs.tilesLocked) { prefs.tilesLocked = it }
            }
        })
    }
}
