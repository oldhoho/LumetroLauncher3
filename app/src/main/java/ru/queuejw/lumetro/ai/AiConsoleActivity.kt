package ru.queuejw.lumetro.ai

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.queuejw.lumetro.R

/**
 * 全屏 AI 控制台。所有网络与状态逻辑在 [AiConsoleClient] 中，与九键面板共用。
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

        AiConsoleClient.init(this)
        serverInput.setText(AiConsoleClient.currentInputUrl())

        findViewById<Button>(R.id.btn_ai_server_save).setOnClickListener { saveServer() }
        sendButton.setOnClickListener { submitTask(inputTask.text.toString().trim()) }

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
}
