package ru.queuejw.lumetro.components.core.ai

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AI 状态单一数据源：内存单例 + SharedPreferences 持久化。
 * Launcher UI 重建后从这里恢复显示。
 */
object AiStatusManager {

    const val STATE_HIDDEN = "hidden"
    const val STATE_IDLE = "idle"
    const val STATE_RUNNING = "running"

    private const val PREFS_NAME = "ai_status"
    private const val KEY_STATE = "state"
    private const val KEY_TEXT = "text"
    private const val KEY_UPDATED_AT = "updated_at"

    @Volatile
    var state: String = STATE_HIDDEN
        private set

    @Volatile
    var text: String? = null
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    private val listeners = CopyOnWriteArrayList<(String, String?) -> Unit>()
    private var prefs: SharedPreferences? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        state = p.getString(KEY_STATE, STATE_HIDDEN) ?: STATE_HIDDEN
        text = p.getString(KEY_TEXT, null)
        updatedAt = p.getLong(KEY_UPDATED_AT, 0L)
        initialized = true
    }

    fun isFresh(timeoutMs: Long): Boolean =
        state != STATE_HIDDEN && System.currentTimeMillis() - updatedAt in 0..timeoutMs

    fun update(context: Context, newState: String, newText: String?) {
        init(context)
        state = newState
        text = newText
        updatedAt = System.currentTimeMillis()
        prefs?.edit()
            ?.putString(KEY_STATE, newState)
            ?.putString(KEY_TEXT, newText)
            ?.putLong(KEY_UPDATED_AT, updatedAt)
            ?.apply()
        listeners.forEach { it(newState, newText) }
    }

    fun addListener(listener: (String, String?) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (String, String?) -> Unit) {
        listeners.remove(listener)
    }
}
