package ru.queuejw.lumetro.components.freeform.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 触摸/手势事件日志：默认关闭；开启后仅输出到 logcat，不再写外部存储。
 */
object TouchLogger {

    private const val TAG = "TouchDebug"

    @Volatile
    private var enabled: Boolean = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) log("=== TouchLogger enabled ===")
    }

    fun isEnabled(): Boolean = enabled

    fun log(message: String) {
        if (!enabled) return
        Log.d(TAG, "[${timeFormat.format(Date())}] $message")
    }

    fun clear() {
        Log.d(TAG, "TouchLogger 仅输出到 logcat，无需清理文件")
    }
}
