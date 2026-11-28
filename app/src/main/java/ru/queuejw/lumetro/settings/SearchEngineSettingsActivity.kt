package ru.queuejw.lumetro.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.queuejw.lumetro.ai.CodexUi
import ru.queuejw.lumetro.ai.CodexUi.button
import ru.queuejw.lumetro.ai.CodexUi.hint
import ru.queuejw.lumetro.ai.CodexUi.row
import ru.queuejw.lumetro.ai.CodexUi.section
import ru.queuejw.lumetro.ai.CodexUi.tinyButton
import ru.queuejw.lumetro.ai.CodexUi.title
import ru.queuejw.lumetro.ai.settingsValueRow
import ru.queuejw.lumetro.gesture.config.IconType
import ru.queuejw.lumetro.search.config.EngineType
import ru.queuejw.lumetro.search.config.SearchEngine
import ru.queuejw.lumetro.search.config.SearchEngineDataManager

/** 搜索引擎管理，视觉统一到 AI 窗卡片风格。 */
class SearchEngineSettingsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var defaultEngineValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CodexUi.root(this) {
            title("搜索引擎", "搜索链接中的 %s 会替换为选中文字")
            section("默认引擎") {
                defaultEngineValue = settingsValueRow("默认搜索引擎", "加载中…") {
                    showDefaultEngineDialog()
                }
            }
            section("已添加") {
                listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(listContainer)
            }
            section("操作") {
                row {
                    button("添加搜索引擎") { showEditDialog(null) }
                }
            }
        })
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            SearchEngineDataManager.ensureDefaultEngines(this@SearchEngineSettingsActivity)
            val engines = SearchEngineDataManager.readSearchEngineList(this@SearchEngineSettingsActivity)
            val defaultTag = SearchEngineDataManager.getDefaultEngineTag(this@SearchEngineSettingsActivity)
            defaultEngineValue.text = (SearchEngineDataManager.getDefaultSearchEngine(
                this@SearchEngineSettingsActivity
            )?.name ?: "未设置") + "  ›"
            listContainer.removeAllViews()
            if (engines.isEmpty()) {
                listContainer.hint("暂无搜索引擎")
                return@launch
            }
            engines.forEach { engine -> listContainer.addView(buildRow(engine, defaultTag)) }
        }
    }

    private fun buildRow(engine: SearchEngine, defaultTag: String?): LinearLayout {
        val isDefault = engine.tag == defaultTag
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CodexUi.dp(context, 8), CodexUi.dp(context, 8), CodexUi.dp(context, 8), CodexUi.dp(context, 8))
            background = CodexUi.roundedBg(context, "#FF1E1E1E", 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = CodexUi.dp(context, 6) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = engine.textIcon ?: engine.name?.take(1).orEmpty()
                    textSize = 14f
                    setTextColor(CodexUi.color("#FFE8E8E8"))
                    background = CodexUi.roundedBg(context, "#FF3B7D6B", 7)
                    setPadding(CodexUi.dp(context, 8), CodexUi.dp(context, 4), CodexUi.dp(context, 8), CodexUi.dp(context, 4))
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(CodexUi.dp(context, 8), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = (engine.name ?: "(未命名)") + if (isDefault) " · 默认" else ""
                        textSize = 12f
                        setTextColor(CodexUi.color("#FFE8E8E8"))
                    })
                    addView(TextView(context).apply {
                        text = engine.searchLink.orEmpty()
                        textSize = 10.5f
                        setTextColor(CodexUi.color("#AA9AA0A8"))
                        maxLines = 1
                    })
                })
            })
            row {
                if (!isDefault) tinyButton("设为默认") {
                    lifecycleScope.launch {
                        SearchEngineDataManager.setDefaultEngineTag(
                            this@SearchEngineSettingsActivity, engine.tag
                        )
                        reload()
                    }
                }
                tinyButton("编辑") { showEditDialog(engine) }
                tinyButton("删除") {
                    lifecycleScope.launch {
                        SearchEngineDataManager.removeSearchEngine(
                            this@SearchEngineSettingsActivity, engine.tag
                        )
                        reload()
                    }
                }
            }
        }
    }

    private fun showDefaultEngineDialog() {
        lifecycleScope.launch {
            val engines = SearchEngineDataManager.readSearchEngineList(this@SearchEngineSettingsActivity)
            if (engines.isEmpty()) return@launch
            val currentTag = SearchEngineDataManager.getDefaultEngineTag(this@SearchEngineSettingsActivity)
            val checked = engines.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)
            AlertDialog.Builder(this@SearchEngineSettingsActivity)
                .setTitle("默认搜索引擎")
                .setSingleChoiceItems(engines.map { it.name ?: "(未命名)" }.toTypedArray(), checked) { dialog, which ->
                    lifecycleScope.launch {
                        SearchEngineDataManager.setDefaultEngineTag(
                            this@SearchEngineSettingsActivity, engines[which].tag
                        )
                        dialog.dismiss()
                        reload()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
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
            setPadding(CodexUi.dp(context, 16), CodexUi.dp(context, 10), CodexUi.dp(context, 16), 0)
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
