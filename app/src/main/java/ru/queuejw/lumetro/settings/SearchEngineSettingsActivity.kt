package ru.queuejw.lumetro.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.queuejw.lumetro.gesture.config.IconType
import ru.queuejw.lumetro.search.config.EngineType
import ru.queuejw.lumetro.search.config.SearchEngine
import ru.queuejw.lumetro.search.config.SearchEngineDataManager

/**
 * Manage the search engines shown under the token panel.
 * Field set matches the original add/edit sheet; the full engine picker page
 * belongs to the search-UI batch.
 */
class SearchEngineSettingsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "搜索引擎"
            textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = "搜索链接中 %s 会替换为选中文字"
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(4), 0, dp(12))
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        })

        root.addView(Button(this).apply {
            text = "添加搜索引擎"
            setOnClickListener { showEditDialog(null) }
        })

        setContentView(root)
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            SearchEngineDataManager.ensureDefaultEngines(this@SearchEngineSettingsActivity)
            val engines = SearchEngineDataManager.readSearchEngineList(this@SearchEngineSettingsActivity)
            listContainer.removeAllViews()
            if (engines.isEmpty()) {
                listContainer.addView(TextView(this@SearchEngineSettingsActivity).apply {
                    text = "暂无搜索引擎"
                    setPadding(0, dp(12), 0, dp(12))
                })
            }
            engines.forEach { engine ->
                listContainer.addView(buildRow(engine))
            }
        }
    }

    private fun buildRow(engine: SearchEngine): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@SearchEngineSettingsActivity).apply {
                text = engine.textIcon ?: engine.name?.take(1).orEmpty()
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setBackgroundColor(0xFFE8DEF8.toInt())
            })
            addView(LinearLayout(this@SearchEngineSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@SearchEngineSettingsActivity).apply {
                    text = engine.name ?: "(未命名)"
                    textSize = 15f
                })
                addView(TextView(this@SearchEngineSettingsActivity).apply {
                    text = engine.searchLink ?: ""
                    textSize = 12f
                    setTextColor(0xFF777777.toInt())
                })
            })
            addView(Button(this@SearchEngineSettingsActivity).apply {
                text = "编辑"
                setOnClickListener { showEditDialog(engine) }
            })
            addView(Button(this@SearchEngineSettingsActivity).apply {
                text = "删除"
                setOnClickListener {
                    lifecycleScope.launch {
                        SearchEngineDataManager.removeSearchEngine(
                            this@SearchEngineSettingsActivity, engine.tag,
                        )
                        reload()
                    }
                }
            })
        }

    private fun showEditDialog(existing: SearchEngine?) {
        val nameInput = EditText(this).apply {
            hint = "名称"
            setText(existing?.name.orEmpty())
        }
        val linkInput = EditText(this).apply {
            hint = "搜索链接，例如 https://www.bing.com/search?q=%s"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.searchLink.orEmpty())
        }
        val iconInput = EditText(this).apply {
            hint = "图标文字（单个字符）"
            setText(existing?.textIcon.orEmpty())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(nameInput)
            addView(linkInput)
            addView(iconInput)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加搜索引擎" else "编辑搜索引擎")
            .setView(content)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                val link = linkInput.text.toString().trim()
                val icon = iconInput.text.toString().trim()
                if (name.isEmpty() || link.isEmpty()) {
                    Toast.makeText(this, "名称和链接不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val engine = existing ?: SearchEngine()
                engine.name = name
                engine.searchLink = link
                engine.iconType = if (icon.isEmpty()) IconType.OTHER else IconType.TEXT
                engine.textIcon = icon.ifEmpty { name.take(1) }
                if (existing == null) {
                    engine.engineType = EngineType.DIRECT_LINK
                    engine.showInSearchEngineList = true
                }
                lifecycleScope.launch {
                    if (existing == null) {
                        SearchEngineDataManager.addSearchEngine(this@SearchEngineSettingsActivity, engine)
                    } else {
                        SearchEngineDataManager.updateSearchEngine(this@SearchEngineSettingsActivity, engine)
                    }
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_ROW_COUNT = "engine_row_count"
        const val KEY_COLUMN_COUNT = "engine_column_count"
        const val KEY_ENGINE_SHOW_LABEL = "engine_show_label"
        const val KEY_SHOW_IMAGE_BADGE = "search_show_image_badge"
        const val MIN_ROW_COUNT = 1
        const val MAX_ROW_COUNT = 4
        const val MIN_COLUMN_COUNT = 2
        const val MAX_COLUMN_COUNT = 6
    }
}
