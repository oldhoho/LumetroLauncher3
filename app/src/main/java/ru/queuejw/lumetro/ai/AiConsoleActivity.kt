package ru.queuejw.lumetro.ai

import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.R
import kotlin.math.roundToInt

/**
 * 全屏 AI 控制台：任务输入、常用任务编辑、状态/日志/最近总结展示。
 * 所有网络与状态逻辑在 [AiConsoleClient] 中，与九键面板共用。
 */
class AiConsoleActivity : AppCompatActivity() {

    private lateinit var inputTask: EditText
    private lateinit var serverInput: EditText
    private lateinit var sendButton: Button
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var reportTime: TextView
    private lateinit var reportSummary: TextView
    private lateinit var taskContainer: LinearLayout

    private val renderListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_console)

        inputTask = findViewById(R.id.et_ai_task)
        serverInput = findViewById(R.id.et_ai_server)
        sendButton = findViewById(R.id.btn_ai_send)
        stateView = findViewById(R.id.tv_ai_state)
        logView = findViewById(R.id.tv_ai_log)
        logScroll = findViewById(R.id.scroll_ai_log)
        reportTime = findViewById(R.id.tv_report_time)
        reportSummary = findViewById(R.id.tv_report_summary)
        taskContainer = findViewById(R.id.container_ai_tasks)

        AiConsoleClient.init(this)
        serverInput.setText(AiConsoleClient.currentInputUrl())

        findViewById<Button>(R.id.btn_ai_server_save).setOnClickListener { saveServer() }
        sendButton.setOnClickListener { submitTask(inputTask.text.toString().trim()) }
        findViewById<Button>(R.id.btn_ai_task_add).setOnClickListener { showTaskEditor(null) }

        renderTasks()
        render()
        AiConsoleClient.refreshReport()
    }

    override fun onResume() {
        super.onResume()
        AiConsoleClient.init(this)
        AiConsoleClient.addListener(renderListener)
        render()
        AiConsoleClient.startPollingIfRunning()
    }

    override fun onPause() {
        super.onPause()
        AiConsoleClient.removeListener(renderListener)
    }

    private fun render() {
        stateView.text = "${AiConsoleClient.statusText}  ${AiConsoleClient.serverLabel()}"
        val step = AiConsoleClient.step
        val log = AiConsoleClient.logText
        logView.text = buildString {
            if (step.isNotBlank()) append(step).append("\n\n")
            append(log)
            val error = AiConsoleClient.lastError
            if (log.isBlank() && !error.isNullOrBlank()) append(error)
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        if (AiConsoleClient.summary.isNotBlank()) {
            reportSummary.text = AiConsoleClient.summary
            reportTime.text = AiConsoleClient.summaryTime
        }
    }

    private fun renderTasks() {
        taskContainer.removeAllViews()
        val tasks = AiTasks.all(this)
        if (tasks.isEmpty()) {
            taskContainer.addView(TextView(this).apply {
                text = "还没有常用任务"
                setTextColor(android.graphics.Color.parseColor("#88FFFFFF"))
                textSize = 12f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (task in tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = task.text
                setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showTaskEditor(task) }
            })
            row.addView(TextView(this).apply {
                text = "编辑"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#FF88CCFF"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showTaskEditor(task) }
            })
            row.addView(TextView(this).apply {
                text = "删除"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#FFFF6666"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    AiTasks.remove(this@AiConsoleActivity, task.id)
                    renderTasks()
                }
            })
            taskContainer.addView(row)
        }
    }

    private fun showTaskEditor(task: AiTask?) {
        val input = EditText(this).apply {
            setText(task?.text.orEmpty())
            hint = "完整指令文本，例如：打开 B 站播放我收藏的歌单"
            setHintTextColor(android.graphics.Color.parseColor("#88FFFFFF"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(if (task == null) "新建常用任务" else "编辑常用任务")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "任务内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (task == null) AiTasks.add(this, text) else AiTasks.update(this, task.id, text)
                renderTasks()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitTask(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入任务", Toast.LENGTH_SHORT).show()
            return
        }
        sendButton.isEnabled = false
        AiConsoleClient.sendTask(text) { ok, msg ->
            sendButton.isEnabled = true
            if (ok) {
                stateView.text = "AI 运行中"
            } else {
                stateView.text = msg
                logView.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveServer() {
        val normalized = AiConsoleClient.setServer(this, serverInput.text.toString())
        if (normalized == null) {
            Toast.makeText(this, "服务器地址无效", Toast.LENGTH_SHORT).show()
            return
        }
        serverInput.setText(normalized)
        AiConsoleClient.checkServer { ok, msg ->
            stateView.text = if (ok) "连接成功：$normalized（$msg）" else "连接失败：$normalized\n$msg"
            if (!ok) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
