package ru.queuejw.lumetro.components.core.sidebar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.queuejw.lumetro.components.core.ai.AiStatusManager
import ru.queuejw.lumetro.components.core.ai.AiStatusOverlay
import ru.queuejw.lumetro.components.core.receivers.AppReceiver
import ru.queuejw.lumetro.components.freeform.WorkbenchManager
import ru.queuejw.lumetro.components.freeform.WorkbenchSettings
import ru.queuejw.lumetro.components.freeform.gesture.LeftGestureStripManager
import ru.queuejw.lumetro.components.freeze.ShizukuHelper
import ru.queuejw.lumetro.components.inspire.ScreenContentController
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import ru.queuejw.lumetro.gesture.GestureManager
import ru.queuejw.lumetro.inspire.ManagedBitmap
import ru.queuejw.lumetro.inspire.InspireFloating

class SidebarAccessibilityService : AccessibilityService(), CoroutineScope {

    private val serviceJob = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Main + serviceJob

    val gestureManager: GestureManager? = null

    suspend fun takeManagedScreenshot(): ManagedBitmap? =
        takeScreenshot()?.let(ManagedBitmap::from)

    companion object {
        private const val TAG = "SidebarA11yService"
        var sidebarManager: SidebarManager? = null
            private set
        var workbenchManager: WorkbenchManager? = null
            private set
        var gestureStripManager: LeftGestureStripManager? = null
            private set
        private var instance: SidebarAccessibilityService? = null

        fun getInstance(): SidebarAccessibilityService? = instance

        fun startScreenContent(): Boolean {
            val service = instance ?: return false
            service.startScreenContentSelection()
            return true
        }

        fun isServiceEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${SidebarAccessibilityService::class.java.name}"
            return try {
                val enabled = android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (enabled == 1) {
                    val enabledServices = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    )
                    enabledServices?.contains(serviceName) == true
                } else false
            } catch (e: Exception) { false }
        }

        fun toggleWorkbench() {
            try {
                workbenchManager?.toggle()
            } catch (e: Exception) {
                Log.e(TAG, "toggleWorkbench error", e)
            }
        }

        fun isWorkbenchShowing(): Boolean {
            return try {
                workbenchManager?.isShowing() ?: false
            } catch (e: Exception) {
                false
            }
        }

        fun openRecentTasks() {
            try {
                instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            } catch (e: Exception) {
                Log.e(TAG, "openRecentTasks error", e)
            }
        }

        fun updateForegroundApp(packageName: String) {
            try {
                workbenchManager?.updateForegroundApp(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "updateForegroundApp error", e)
            }
        }

        fun showWorkbench() {
            try {
                workbenchManager?.show()
            } catch (e: Exception) {
                Log.e(TAG, "showWorkbench error", e)
            }
        }

        fun refreshGestureStrip() {
            try {
                gestureStripManager?.show()
            } catch (e: Exception) {
                Log.e(TAG, "refreshGestureStrip error", e)
            }
        }
    }

    private var receiver: BroadcastReceiver? = null
    private var appReceiver: AppReceiver? = null
    private var aiStatusOverlay: AiStatusOverlay? = null
    private val aiStatusListener: (String, String?) -> Unit = { state, text ->
        aiStatusOverlay?.update(state, text)
    }
    private var lastPackage = ""
    private var isFullscreen = false
    private var fullscreenCheckHandler = Handler(Looper.getMainLooper())
    private var fullscreenCheckRunnable: Runnable? = null
    private var screenContentController: ScreenContentController? = null
    private val screenshotExecutor: ExecutorService =
        ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(10))

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        try {
            serviceInfo?.let { info ->
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                info.notificationTimeout = 100
                serviceInfo = info
            }
            Log.d(TAG, "Accessibility service connected")

            try {
                // ========== 初始化 Shizuku ==========
                try {
                    ShizukuHelper.getInstance().init(applicationContext)
                    Log.d(TAG, "Shizuku initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku init failed", e)
                }

                // ========== 初始化 WorkbenchManager ==========
                workbenchManager = WorkbenchManager.init(this, this)
                workbenchManager?.show()
                Log.d(TAG, "Workbench initialized via WorkbenchManager")

                // ========== 从持久化设置恢复手势条配置 ==========
                val settings = WorkbenchSettings(this)
                val density = resources.displayMetrics.density
                
                gestureStripManager = LeftGestureStripManager.getInstance(this, this).apply {
                    stripWidth = (settings.gestureStripWidth * density).toInt()
                    stripHeight = if (settings.gestureStripHeight > 0) (settings.gestureStripHeight * density).toInt() else 0
                    stripOffset = (settings.gestureStripOffset * density).toInt()
                    stripAlpha = settings.gestureStripAlpha
                    
                    Log.d(TAG, "Restored gesture strip settings: width=${settings.gestureStripWidth}, height=${settings.gestureStripHeight}, offset=${settings.gestureStripOffset}, alpha=${settings.gestureStripAlpha}")
                    
                    onSwipeRight = {
                        try {
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            Log.d(TAG, "Left gesture: swipe right -> back")
                        } catch (e: Exception) {
                            Log.e(TAG, "Swipe right failed", e)
                            try {
                                SidebarAccessibilityService.getInstance()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            } catch (e2: Exception) {
                                Log.e(TAG, "Back fallback failed", e2)
                            }
                        }
                    }
                    
                    show()
                }
                Log.d(TAG, "Left gesture strip initialized with restored settings")

                // ========== 初始化右侧手势条和磁贴面板（检查开关） ==========
                if (settings.tilesPanelEnabled) {
                    sidebarManager = SidebarManager(this).apply {
                        createGestureStrip()
                        configureTouchPassthrough()
                    }
                    Log.d(TAG, "Sidebar initialized (enabled)")
                } else {
                    Log.d(TAG, "Sidebar disabled by user setting")
                    sidebarManager = SidebarManager(this)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to init", e)
            }

            aiStatusOverlay?.release()
            aiStatusOverlay = AiStatusOverlay(this)
            AiStatusManager.init(this)
            AiStatusManager.addListener(aiStatusListener)
            // 工作台重建后若状态还在 10 秒有效期内，立即恢复显示
            if (AiStatusManager.isFresh(AiStatusOverlay.TIMEOUT_MS)) {
                aiStatusOverlay?.update(AiStatusManager.state, AiStatusManager.text)
            }
            setupReceiver()
            setupAppReceiver()
            screenContentController = ScreenContentController(this)

        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    try {
                        val packageName = event.packageName?.toString() ?: return
                        if (packageName != lastPackage) {
                            lastPackage = packageName
                            updateForegroundApp(packageName)
                            checkFullscreenState(packageName)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Window state changed error", e)
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    fullscreenCheckRunnable?.let { fullscreenCheckHandler.removeCallbacks(it) }
                    fullscreenCheckRunnable = Runnable {
                        checkFullscreenState(lastPackage)
                    }
                    fullscreenCheckHandler.postDelayed(fullscreenCheckRunnable!!, 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error", e)
        }
    }

    // ========== dp转px ==========
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    // ========== 获取窗口类型（兼容方法） ==========
    private fun getWindowType(window: AccessibilityWindowInfo): Int {
        return try {
            window.getType()
        } catch (e: Exception) {
            -1
        }
    }

    // ========== 检查全屏状态 ==========
    private fun checkFullscreenState(packageName: String) {
        try {
            if (packageName == applicationContext.packageName) {
                return
            }
            
            val windows = windows ?: emptyList()
            var navigationBarVisible = false
            var hasTargetAppWindow = false
            
            val screenHeight = resources.displayMetrics.heightPixels
            
            for (window in windows) {
                try {
                    val type = getWindowType(window)
                    
                    if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.getRoot()
                        val windowPackage = root?.getPackageName()?.toString()
                        if (windowPackage != packageName) {
                            continue
                        }
                        hasTargetAppWindow = true
                    }
                    
                    if (type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                        val bounds = android.graphics.Rect()
                        window.getBoundsInScreen(bounds)
                        
                        if (bounds.top > screenHeight * 0.7f && bounds.height() < 150.dpToPx()) {
                            navigationBarVisible = true
                        }
                    }
                } catch (e: Exception) {
                    // 忽略
                }
            }
            
            if (!hasTargetAppWindow) return
            
            val isFullscreenNow = !navigationBarVisible
            
            if (isFullscreenNow && !isFullscreen) {
                isFullscreen = true
                Log.d(TAG, "Fullscreen detected ($packageName), hiding workbench")
                workbenchManager?.hide()
                workbenchManager?.hideAppsList()
            } 
            else if (!isFullscreenNow && isFullscreen) {
                isFullscreen = false
                Log.d(TAG, "Exited fullscreen ($packageName), showing workbench")
                workbenchManager?.show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkFullscreenState error", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    fun startScreenContentSelection() {
        val floating = inspireFloating ?: InspireFloating(this).also { inspireFloating = it }
        floating.show()
    }

    private var inspireFloating: InspireFloating? = null

    fun processClipboardText(text: String) {
        screenContentController?.processClipboardText(text)
            ?: Log.w(TAG, "Screen content controller is not ready")
    }

    fun processClipboardImage(uri: android.net.Uri) {
        screenContentController?.processClipboardImage(uri)
            ?: Log.w(TAG, "Screen content controller is not ready")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun takeScreenshot(): Bitmap? = withTimeoutOrNull(3500L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withTimeoutOrNull null
        }
        suspendCancellableCoroutine { continuation ->
            takeScreenshot(0, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (!continuation.isActive) {
                        screenshot.hardwareBuffer.close()
                        return
                    }
                    val bitmap = runCatching {
                        var hardwareBitmap: Bitmap? = null
                        try {
                            hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                        } finally {
                            hardwareBitmap?.recycle()
                            screenshot.hardwareBuffer.close()
                        }
                    }.getOrNull()
                    continuation.resume(bitmap) { bitmap?.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

    override fun onDestroy() {
        inspireFloating?.destroy()
        inspireFloating = null
        serviceJob.cancel()
        super.onDestroy()
        instance = null
        screenContentController?.destroy()
        screenContentController = null
        screenshotExecutor.shutdownNow()
        fullscreenCheckHandler.removeCallbacksAndMessages(null)
        try {
            receiver?.let { unregisterReceiver(it) }
            appReceiver?.let { unregisterReceiver(it) }
            AiStatusManager.removeListener(aiStatusListener)
            aiStatusOverlay?.release()
            aiStatusOverlay = null

            LeftGestureStripManager.destroyInstance()
            gestureStripManager = null

            WorkbenchManager.destroyInstance()
            workbenchManager = null

            sidebarManager?.destroy()
            sidebarManager = null        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
    }

    private fun setupReceiver() {
        try {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    when (intent?.action) {
                        "ru.queuejw.lumetro.SHOW_PANEL" -> {
                            // 磁贴面板已移除，空操作
                        }
                        "ru.queuejw.lumetro.UPDATE_PANEL_BG" -> {
                            // 磁贴面板已移除，空操作
                        }
                        "ru.queuejw.lumetro.UPDATE_TILES" -> {
                            // 磁贴面板已移除，空操作
                        }
                        "ru.queuejw.lumetro.EXPAND_NOTIFICATION" -> {
                            try {
                                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                            } catch (e: Exception) {
                                Log.e(TAG, "EXPAND_NOTIFICATION error", e)
                            }
                        }
                        "ru.queuejw.lumetro.TOGGLE_WORKBENCH" -> {
                            try {
                                toggleWorkbench()
                            } catch (e: Exception) {
                                Log.e(TAG, "TOGGLE_WORKBENCH error", e)
                            }
                        }
                        "ru.queuejw.lumetro.SHOW_WORKBENCH" -> {
                            try {
                                workbenchManager?.showFast()
                            } catch (e: Exception) {
                                Log.e(TAG, "SHOW_WORKBENCH error", e)
                            }
                        }
                        "ru.queuejw.lumetro.AI_STATUS" -> {
                            try {
                                AiStatusManager.update(
                                    context,
                                    intent.getStringExtra("state") ?: AiStatusManager.STATE_HIDDEN,
                                    intent.getStringExtra("text")
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "AI_STATUS error", e)
                            }
                        }
                        "ru.queuejw.lumetro.UPDATE_GESTURE_STRIP" -> {
                            try {
                                refreshGestureStrip()
                            } catch (e: Exception) {
                                Log.e(TAG, "UPDATE_GESTURE_STRIP error", e)
                            }
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
                addAction("ru.queuejw.lumetro.SHOW_PANEL")
                addAction("ru.queuejw.lumetro.UPDATE_PANEL_BG")
                addAction("ru.queuejw.lumetro.UPDATE_TILES")
                addAction("ru.queuejw.lumetro.EXPAND_NOTIFICATION")
                addAction("ru.queuejw.lumetro.TOGGLE_WORKBENCH")
                addAction("ru.queuejw.lumetro.SHOW_WORKBENCH")
                addAction("ru.queuejw.lumetro.UPDATE_GESTURE_STRIP")
                addAction("ru.queuejw.lumetro.AI_STATUS")
            }, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "setupReceiver error", e)
        }
    }

    private fun setupAppReceiver() {
        try {
            appReceiver = AppReceiver(
                onAppInstalled = { pkg ->
                    Log.d(TAG, "App installed: $pkg")
                },
                onAppRemoved = { pkg ->
                    Log.d(TAG, "App removed: $pkg")
                },
                onAppChanged = {
                    Log.d(TAG, "App changed")
                }
            )
            registerReceiver(appReceiver, IntentFilter(Intent.ACTION_PACKAGE_CHANGED).apply {
                addDataScheme("package")
            })
        } catch (e: Exception) {
            Log.e(TAG, "setupAppReceiver error", e)
        }
    }
}