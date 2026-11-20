package ru.queuejw.lumetro.diagnostics

import android.util.Log

enum class LogModule {
    ACTION,
    OCR,
    OVERLAY,
    GESTURE,
    SETTINGS,
    OTHER,
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

object AppLogger {
    private const val TAG = "Lumetro"

    var detailed: Boolean = false

    fun event(
        module: LogModule,
        event: String,
        level: LogLevel = LogLevel.INFO,
        trace: String? = null,
        fields: Map<String, Any?>? = null,
        error: Throwable? = null,
    ) {
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        val message = buildString {
            append('[').append(module.name).append("] ").append(event)
            if (!trace.isNullOrBlank()) append(" | ").append(trace)
            fields?.takeIf { it.isNotEmpty() }?.let { append(" | ").append(it) }
        }
        Log.println(priority, TAG, message)
        error?.let { Log.println(Log.ERROR, TAG, Log.getStackTraceString(it)) }
    }
}
