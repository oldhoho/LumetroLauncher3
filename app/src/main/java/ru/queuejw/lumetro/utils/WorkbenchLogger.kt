package ru.queuejw.lumetro.utils

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作台日志：默认关闭。开启后写入应用私有 filesDir/logs，带 1MB 上限。
 * 不再写公共 Downloads，避免分区存储下静默失败和跨应用可读。
 */
object WorkbenchLogger {
    private const val LOG_FILE_NAME = "workbench_log.txt"
    private const val MAX_LOG_SIZE = 1024 * 1024

    private var logFile: File? = null
    private var isEnabled = false

    fun init(context: Context) {
        try {
            val dir = File(context.applicationContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, LOG_FILE_NAME)
        } catch (e: Exception) {
            logFile = null
        }
    }

    fun log(tag: String, message: String) {
        if (!isEnabled) return
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() > MAX_LOG_SIZE) file.delete()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            FileWriter(file, true).use { writer ->
                writer.append("[$timestamp] [$tag] $message\n")
            }
        } catch (e: Exception) {
            // 日志写入失败不应影响主流程
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        val errorMsg = if (throwable != null) {
            "$message | Error: ${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(tag, "ERROR: $errorMsg")
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun getLogContent(context: Context): String {
        return try {
            val file = File(File(context.applicationContext.filesDir, "logs"), LOG_FILE_NAME)
            if (file.exists()) file.readText() else "日志文件不存在"
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    fun clearLog(context: Context) {
        try {
            File(File(context.applicationContext.filesDir, "logs"), LOG_FILE_NAME).delete()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
