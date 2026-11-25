package ru.queuejw.lumetro.components.freeform.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "lumetro_crash.log"
    private const val MAX_LOG_SIZE = 512L * 1024L

    @Volatile
    private var installed = false

    private var appContext: Context? = null

    /**
     * 在 Application.onCreate 或任意入口调用一次
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "write crash log failed", e)
            }
            // 交给系统默认处理（避免丢崩溃弹窗）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        val stack = sw.toString()

        val sb = StringBuilder()
        sb.append("==========================================\n")
        sb.append("TIME   : $time\n")
        sb.append("THREAD : ${thread.name}\n")
        sb.append("DEVICE : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("------------------------------------------\n")

        // ========== 插入最近的内存日志 ==========
        try {
            sb.append(LogBuffer.dump())
        } catch (e: Exception) { }
        // ========================================

        sb.append(stack)
        sb.append("\n\n")

        // 崩溃日志保留诊断价值，但只写应用私有目录，不依赖外部存储权限。
        val ctx = appContext ?: return
        try {
            val dir = File(ctx.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            if (file.exists() && file.length() > MAX_LOG_SIZE) file.delete()
            file.appendText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "write crash log to filesDir failed", e)
        }
    }

    fun clear() {
        try {
            appContext?.let { File(File(it.filesDir, "logs"), FILE_NAME).delete() }
        } catch (e: Exception) {
            // 忽略
        }
    }
}
