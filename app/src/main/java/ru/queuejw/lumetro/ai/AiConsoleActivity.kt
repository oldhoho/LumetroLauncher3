package ru.queuejw.lumetro.ai

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 独立会话页。AI 栏点击后从这里进入：普通 Activity 窗口天然拥有 IME token，
 * 系统 adjustResize 负责键盘弹出时把输入行顶上去，不受工作台悬浮窗层级干扰。
 * 进入时只收起九键面板；工作台 dock 保持常驻，会话页底部按 dock 高度避让。
 */
class AiConsoleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL = "ai_prefill"
        /** 工具箱里点「填入对话」时只填文字、不自动发送，与 Web 端一致 */
        const val EXTRA_PREFILL_ONLY = "ai_prefill_only"
        /** 全局搜索命中：切到会话后滚动定位并高亮目标消息 */
        const val EXTRA_JUMP_TERMS = "ai_jump_terms"
        const val EXTRA_JUMP_ROLE = "ai_jump_role"
    }

    private var overlay: AiChatOverlay? = null
    private var pendingPrefill: String? = null
    private var prefillOnly = false
    private var jumpTerms: String? = null
    private var jumpRole: String? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        overlay?.onPickedFile(uri)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AiConsoleClient.init(this)
        pendingPrefill = intent.getStringExtra(EXTRA_PREFILL)
        prefillOnly = intent.getBooleanExtra(EXTRA_PREFILL_ONLY, false)
        jumpTerms = intent.getStringExtra(EXTRA_JUMP_TERMS)
        jumpRole = intent.getStringExtra(EXTRA_JUMP_ROLE)
        val view = AiChatOverlay(
            this,
            onClose = { finish() },
            manageWindow = false,
            onPickFile = { pickFile.launch("*/*") },
        )
        overlay = view
        if (!jumpTerms.isNullOrBlank()) {
            view.requestJump(jumpRole ?: "", jumpTerms!!)
        }
        setContentView(view)

        // 输入行紧贴会话页可用区底部（adjustResize 会在键盘弹出时自动上移整体）。
        // 之前按 dock 高度垫 padding，反而在无键盘时留下一条固定空白，已移除。
    }

    override fun onResume() {
        super.onResume()
        AiConsoleClient.init(this)
        overlay?.show()
        // 启动 Web 端历史轮询；若本地任务在后台断流，这里同时重连 SSE
        AiConsoleClient.startPollingIfRunning()
        // 无会话则自动创建、有会话则拉取历史：打开即看到内容
        AiConsoleClient.prepareSession { ok, msg ->
            if (!ok) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // onCreate/onResume 阶段 showSoftInput 会被系统静默忽略（窗口还没焦点）。
        // 这里才是 Activity 真正可交互的时机，补一次焦点和键盘请求。
        if (hasFocus) {
            overlay?.post {
                overlay?.focusInputBox()
                pendingPrefill?.let { text ->
                    pendingPrefill = null
                    overlay?.prefill(text)
                    if (!prefillOnly) {
                        AiConsoleClient.sendTask(text) { ok, msg ->
                            if (!ok) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        // 切后台不收 UI 监听、不停 TTS、不停轮询：本端回复的朗读要播完，
        // Web 端新消息继续同步展示（但不再朗读）。回前台才是完整状态。
        super.onPause()
    }

    override fun onDestroy() {
        overlay?.release()
        AiConsoleClient.stopHistoryPolling()
        super.onDestroy()
    }
}
