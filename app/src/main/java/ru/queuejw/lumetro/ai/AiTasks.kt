package ru.queuejw.lumetro.ai

import android.content.Context
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AiTask(val id: String, val text: String) {
    /** 列表与芯片上显示的短名 */
    val label: String
        get() = text.replace('\n', ' ').trim().let { if (it.length > 12) it.take(12) else it }
}

/**
 * 常用 AI 任务库：完整指令文本，供全屏 AI 页编辑、九键搜索匹配与数字键绑定。
 */
object AiTasks {

    private const val PREFS = "ai_tasks"
    private const val KEY_TASKS = "tasks"

    private val pinyinFormat by lazy {
        HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
            vCharType = HanyuPinyinVCharType.WITH_V
        }
    }

    private val letterDigits = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
    )

    fun all(context: Context): List<AiTask> {
        val raw = prefs(context).getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id")
                val text = obj.optString("text")
                if (id.isBlank() || text.isBlank()) null else AiTask(id, text)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(context: Context, id: String): AiTask? = all(context).firstOrNull { it.id == id }

    fun add(context: Context, text: String): AiTask {
        val task = AiTask(UUID.randomUUID().toString(), text.trim())
        save(context, all(context) + task)
        return task
    }

    fun update(context: Context, id: String, text: String) {
        save(context, all(context).map { if (it.id == id) it.copy(text = text.trim()) else it })
    }

    fun remove(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    /** 九键数字串（T9）匹配：全拼、首字母与英文片段都参与 */
    fun match(context: Context, input: String, limit: Int = 4): List<AiTask> {
        val digits = input.filter(Char::isDigit)
        if (digits.isEmpty()) return emptyList()
        return all(context).filter { haystack(it.text).contains(digits) }.take(limit)
    }

    private fun save(context: Context, tasks: List<AiTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(JSONObject().apply {
                put("id", task.id)
                put("text", task.text)
            })
        }
        prefs(context).edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun haystack(text: String): String {
        val fullPinyin = StringBuilder()
        val initials = StringBuilder()
        for (char in text) {
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' -> {
                    fullPinyin.append(char)
                    initials.append(char)
                }

                else -> {
                    val pinyin = runCatching {
                        PinyinHelper.toHanyuPinyinStringArray(char, pinyinFormat)?.firstOrNull()
                    }.getOrNull()
                    if (!pinyin.isNullOrBlank()) {
                        fullPinyin.append(pinyin)
                        initials.append(pinyin.first())
                    }
                }
            }
        }
        return encode(fullPinyin.toString()) + " " + encode(initials.toString())
    }

    private fun encode(text: String): String = buildString {
        for (char in text.lowercase()) {
            when {
                char in letterDigits -> append(letterDigits.getValue(char))
                char.isDigit() -> append(char)
            }
        }
    }
}
