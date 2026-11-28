package ru.queuejw.lumetro.settings

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.settingsSwitch
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.saveKey
import ru.queuejw.lumetro.utils.settingsStore

class AppearanceSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CodexUi.root(this) {
            title("外观设置", "窗口与卡片的模糊效果，需系统支持")
            section("屏幕内容") {
                switch("背景模糊", KEY_SCREEN_CONTENT_BACKGROUND_BLUR, false,
                    "取字面板后的窗口背景虚化，需要 Android 12+ 窗口模糊支持。")
                switch("卡片背景模糊", KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR, false,
                    "取字卡片自身半透明并尝试区域模糊；与背景模糊同时开启时仅保留背景模糊。")
            }
            section("Pin 池") {
                switch("背景模糊", KEY_PIN_POOL_BACKGROUND_BLUR, false,
                    "Pin 池窗口后方的背景虚化，需要 Android 12+ 支持。")
                switch("卡片背景模糊", KEY_PIN_POOL_CARD_BACKGROUND_BLUR, false,
                    "Pin 池卡片半透明与边缘描边；不支持模糊时仍保留半透明效果。")
            }
        })
    }

    private fun LinearLayout.switch(name: String, key: String, default: Boolean, desc: String) {
        settingsSwitch(name, settingsStore.readKey(key, default)) {
            settingsStore.saveKey(key, it)
        }
        hint(desc)
    }

    companion object {
        const val KEY_SCREEN_CONTENT_BACKGROUND_BLUR = "key_screen_content_background_blur"
        const val KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR = "key_screen_content_card_background_blur"
        const val KEY_PIN_POOL_BACKGROUND_BLUR = "key_pin_pool_background_blur"
        const val KEY_PIN_POOL_CARD_BACKGROUND_BLUR = "key_pin_pool_card_background_blur"
    }
}
