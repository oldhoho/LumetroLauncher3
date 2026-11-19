package ru.queuejw.lumetro.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ru.queuejw.lumetro.R
import ru.queuejw.lumetro.components.freeform.WorkbenchSettings

/**
 * 工作台上滑呼出的 AI 输入条（Activity 承载，输入法由系统标准管理）。
 */
class AiInputActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var logView: TextView
    private lateinit var bar: LinearLayout

    private val statusListener: () -> Unit = { renderLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_input)

        bar = findViewById(R.id.ai_input_bar)
        input = findViewById(R.id.et_ai_input)
        logView = findViewById(R.id.tv_ai_input_log)

        // 高度与工作台一致
        bar.layoutParams = bar.layoutParams.apply {
            height = (WorkbenchSettings(this@AiInputActivity).height * resources.displayMetrics.density).toInt()
        }

        // 输入条抬到导航栏/手势条上方，避免与其重叠
        ViewCompat.setOnApplyWindowInsetsListener(bar) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp = bar.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.bottomMargin != navBar) {
                lp.bottomMargin = navBar
                bar.layoutParams = lp
            }
            insets
        }

        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                send()
                true
            } else {
                false
            }
        }

        logView.setOnClickListener { openConsole() }
        findViewById<TextView>(R.id.btn_ai_input_close).setOnClickListener { finish() }

        AiConsoleClient.init(this)
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        AiConsoleClient.init(this)
        AiConsoleClient.addListener(statusListener)
        renderLog()
        AiConsoleClient.startPollingIfRunning()
        input.post {
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onPause() {
        super.onPause()
        AiConsoleClient.removeListener(statusListener)
    }

    private fun send() {
        val task = input.text?.toString()?.trim().orEmpty()
        if (task.isEmpty()) {
            Toast.makeText(this, "请输入任务", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        AiConsoleClient.sendTask(task) { ok, msg ->
            if (!ok) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderLog() {
        val step = AiConsoleClient.step
        val lastLogLine = AiConsoleClient.logText.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        logView.text = when {
            AiConsoleClient.running -> "▶ ${step.ifBlank { lastLogLine }}"
            AiConsoleClient.lastError != null -> AiConsoleClient.lastError.orEmpty()
            else -> "${AiConsoleClient.statusText}${if (lastLogLine.isNotBlank()) " · $lastLogLine" else ""}"
        }
    }

    private fun openConsole() {
        startActivity(Intent(this, AiConsoleActivity::class.java))
        finish()
    }
}
