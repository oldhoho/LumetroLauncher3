package ru.queuejw.lumetro.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.utils.readKey
import ru.queuejw.lumetro.utils.saveKey
import ru.queuejw.lumetro.utils.settingsStore

class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(container) })

        addTitle("外观设置")
        addSection("屏幕内容")
        addSwitch("背景模糊", KEY_SCREEN_CONTENT_BACKGROUND_BLUR, false)
        addSwitch("卡片背景模糊", KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR, false)

        addSection("Pin 池")
        addSwitch("背景模糊", KEY_PIN_POOL_BACKGROUND_BLUR, false)
        addSwitch("卡片背景模糊", KEY_PIN_POOL_CARD_BACKGROUND_BLUR, false)
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

    private fun addSwitch(title: String, key: String, default: Boolean) {
        container.addView(Switch(this).apply {
            text = title
            isChecked = settingsStore.readKey(key, default)
            setOnCheckedChangeListener { _, checked ->
                settingsStore.saveKey(key, checked)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_SCREEN_CONTENT_BACKGROUND_BLUR = "key_screen_content_background_blur"
        const val KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR = "key_screen_content_card_background_blur"
        const val KEY_PIN_POOL_BACKGROUND_BLUR = "key_pin_pool_background_blur"
        const val KEY_PIN_POOL_CARD_BACKGROUND_BLUR = "key_pin_pool_card_background_blur"
    }
}
