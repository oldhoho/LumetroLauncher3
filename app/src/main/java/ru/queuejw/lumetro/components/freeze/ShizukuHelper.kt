package ru.queuejw.lumetro.components.freeze

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ShizukuHelper private constructor() {

    companion object {
        private const val TAG = "ShizukuHelper"
        private const val RESTORE_RETRY_COOLDOWN_MS = 2000L
        @Volatile private var instance: ShizukuHelper? = null
        fun getInstance(): ShizukuHelper = instance ?: synchronized(this) { instance ?: ShizukuHelper().also { instance = it } }
    }

    private var context: Context? = null
    private var isAvailable = false
    private var hasPermission = false
    private var packageManagerService: Any? = null
    private var setAppEnabledSettingMethod: Method? = null
    private var getAppEnabledSettingMethod: Method? = null
    private var setPackagesSuspendedMethod: Method? = null
    private var arePackagesSuspendedMethod: Method? = null
    private var activityTaskManagerService: Any? = null
    private var getTasksMethod: Method? = null
    private var moveTaskToFrontMethod: Method? = null
    private var cachedUserId = 0
    @Volatile private var lastRestoreFailureAt = 0L
    @Volatile private var moveTaskToFrontUnsupported = false
    private val loggedCauseTypes = mutableSetOf<String>()

    fun init(ctx: Context) {
        context = ctx.applicationContext
        checkStatus()
    }

    fun checkStatus() {
        try {
            isAvailable = Shizuku.pingBinder()
            if (isAvailable) {
                hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                if (hasPermission && (packageManagerService == null || activityTaskManagerService == null)) initReflection()
            }
        } catch (e: Exception) {
            isAvailable = false
        }
    }

    fun isReady(): Boolean = isAvailable && hasPermission

    private fun initReflection() {
        try {
            ru.queuejw.lumetro.components.freeform.util.U.allowReflection()
            val ipmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterfaceMethod = ipmStub.getMethod("asInterface", IBinder::class.java)
            val binder = SystemServiceHelper.getSystemService("package")
            if (binder != null) {
                packageManagerService = asInterfaceMethod.invoke(null, ShizukuBinderWrapper(binder))
            }
            val ipmClass = Class.forName("android.content.pm.IPackageManager")
            setAppEnabledSettingMethod = ipmClass.getMethod("setApplicationEnabledSetting", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            getAppEnabledSettingMethod = ipmClass.getMethod("getApplicationEnabledSetting", String::class.java, Int::class.javaPrimitiveType)
            try { setPackagesSuspendedMethod = ipmClass.getMethod("setPackagesSuspended", arrayOf<String>()::class.java, Boolean::class.javaPrimitiveType) } catch (e: Exception) {}
            try { arePackagesSuspendedMethod = ipmClass.getMethod("arePackagesSuspended", arrayOf<String>()::class.java) } catch (e: Exception) {}
            cachedUserId = 0
        } catch (e: Exception) {
            Log.e(TAG, "反射初始化失败", e)
        }

        try {
            val iatmStub = Class.forName("android.app.IActivityTaskManager\$Stub")
            val iatmAsInterface = iatmStub.getMethod("asInterface", IBinder::class.java)
            val taskBinder = SystemServiceHelper.getSystemService("activity_task")
            if (taskBinder != null) {
                activityTaskManagerService = iatmAsInterface.invoke(null, ShizukuBinderWrapper(taskBinder))
            }
            val iatmClass = Class.forName("android.app.IActivityTaskManager")
            getTasksMethod = iatmClass.methods
                .filter { it.name == "getTasks" }
                .let { candidates ->
                    candidates.firstOrNull { m ->
                        val p = m.parameterTypes
                        p.size in 2..4 && p[0] == Int::class.javaPrimitiveType &&
                            p[1] == Boolean::class.javaPrimitiveType &&
                            p.drop(2).all { it == Boolean::class.javaPrimitiveType || it == Int::class.javaPrimitiveType }
                    } ?: candidates.firstOrNull { m ->
                        val p = m.parameterTypes
                        p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType
                    } ?: candidates.minByOrNull { it.parameterCount }
                }
            moveTaskToFrontMethod = iatmClass.methods
                .filter { it.name == "moveTaskToFront" }
                .let { candidates ->
                    candidates.firstOrNull { m ->
                        val p = m.parameterTypes
                        p.size == 3 && p[0] == Int::class.javaPrimitiveType &&
                            p[1] == Int::class.javaPrimitiveType && p[2] == Bundle::class.java
                    } ?: candidates.firstOrNull { m ->
                        val p = m.parameterTypes
                        p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType
                    } ?: candidates.minByOrNull { it.parameterCount }
                }
            Log.d(TAG, "activity_task 初始化: service=${activityTaskManagerService != null}, getTasks=${getTasksMethod?.parameterCount}, moveTaskToFront=${moveTaskToFrontMethod?.parameterCount}")
        } catch (e: Exception) {
            Log.e(TAG, "activity_task 反射初始化失败", e)
        }
    }

    fun freezeApp(pkg: String): Boolean {
        if (!isReady() || packageManagerService == null) return false
        try {
            setAppEnabledSettingMethod?.invoke(packageManagerService, pkg, 3, 0, cachedUserId, context?.packageName)
            return true
        } catch (e: Exception) {
            try {
                setPackagesSuspendedMethod?.invoke(packageManagerService, arrayOf(pkg), true)
                return true
            } catch (e2: Exception) {
                Log.e(TAG, "冻结失败: $pkg", e)
                return false
            }
        }
    }

    fun unfreezeApp(pkg: String): Boolean {
        if (!isReady() || packageManagerService == null) return false
        try {
            setAppEnabledSettingMethod?.invoke(packageManagerService, pkg, 0, 0, cachedUserId, context?.packageName)
            try { setPackagesSuspendedMethod?.invoke(packageManagerService, arrayOf(pkg), false) } catch (e: Exception) {}
            return true
        } catch (e: Exception) {
            Log.e(TAG, "解冻失败: $pkg", e)
            return false
        }
    }

    /**
     * 将应用已有任务栈带到前台（不重建 Activity）。
     * 普通应用拿不到其他应用的任务列表，这里借助 Shizuku 以 shell 身份查询并恢复。
     */
    fun moveTaskToFront(packageName: String): Boolean {
        if (moveTaskToFrontUnsupported) return false
        if (SystemClock.elapsedRealtime() - lastRestoreFailureAt < RESTORE_RETRY_COOLDOWN_MS) return false
        if (!isReady()) {
            Log.w(TAG, "moveTaskToFront 跳过: Shizuku 未就绪")
            return false
        }
        if (activityTaskManagerService == null) {
            Log.w(TAG, "moveTaskToFront 跳过: activity_task 服务绑定失败")
            return false
        }
        return try {
            val tasks = queryTasks()
            val task = tasks.firstOrNull { info ->
                info.topActivity?.packageName == packageName ||
                    info.baseIntent?.component?.packageName == packageName
            }
            if (task == null) {
                // 任务不在最近任务列表里，说明应用没在运行，直接回退启动，不再占用 Shizuku 通道
                Log.d(TAG, "moveTaskToFront: $packageName 无运行任务，跳过")
                return false
            }
            val method = moveTaskToFrontMethod
            if (method == null) {
                Log.w(TAG, "moveTaskToFront 跳过: 找不到 moveTaskToFront 方法")
                return false
            }
            method.invoke(activityTaskManagerService, *buildTaskArgs(method, task.taskId, 0))
            Log.d(TAG, "moveTaskToFront 成功: $packageName taskId=${task.taskId}")
            true
        } catch (e: Exception) {
            lastRestoreFailureAt = SystemClock.elapsedRealtime()
            val cause = e.cause ?: e
            when (cause) {
                is SecurityException, is IllegalArgumentException -> {
                    // 权限不足或参数构造不对，重试无意义，直接停用避免刷屏和占用 Binder
                    moveTaskToFrontUnsupported = true
                    Log.w(TAG, "moveTaskToFront 不可用，停用: ${cause.javaClass.simpleName}: ${cause.message}")
                }
                else -> {
                    // 多为 Shizuku 重启后 binder 失效，置空以便下次 checkStatus() 重建
                    activityTaskManagerService = null
                    if (synchronized(loggedCauseTypes) { loggedCauseTypes.add(cause.javaClass.name) }) {
                        Log.w(TAG, "moveTaskToFront 异常 cause", cause)
                    }
                    Log.w(TAG, "moveTaskToFront 失败: $packageName (${cause.javaClass.simpleName}: ${cause.message})")
                }
            }
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryTasks(): List<ActivityManager.RunningTaskInfo> {
        val method = getTasksMethod
        if (method == null) {
            Log.w(TAG, "queryTasks 跳过: 找不到 getTasks 方法")
            return emptyList()
        }
        val result = method.invoke(activityTaskManagerService, *buildTaskArgs(method, 20, 0))
        return result as? List<ActivityManager.RunningTaskInfo> ?: emptyList()
    }

    /**
     * 按方法签名逐个参数生成实参。
     * Android 10+ 的签名是 (IApplicationThread, String callingPackage, int task, int flags, Bundle)，
     * taskId 并不在第一个参数位置，必须按 int 参数出现顺序填：第一个 int = taskId/maxNum，第二个 = flags。
     * 对象参数（IApplicationThread / callingPackage）传 null，服务端允许为空，传入不匹配的包名反而会 SecurityException。
     */
    private fun buildTaskArgs(method: Method, first: Int, second: Int): Array<Any?> {
        var intIndex = 0
        return method.parameterTypes.map { type ->
            when {
                type == Int::class.javaPrimitiveType || type == java.lang.Integer::class.java -> {
                    val value = if (intIndex == 0) first else second
                    intIndex++
                    value
                }
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
                type == Bundle::class.java -> Bundle()
                else -> null
            }
        }.toTypedArray()
    }

    fun isAppFrozen(pkg: String): Boolean {
        if (!isReady() || packageManagerService == null) return false
        try {
            val state = getAppEnabledSettingMethod?.invoke(packageManagerService, pkg, cachedUserId) as? Int ?: 0
            if (state == 2 || state == 3) return true
            val result = arePackagesSuspendedMethod?.invoke(packageManagerService, arrayOf(pkg)) as? BooleanArray
            return result?.firstOrNull() == true
        } catch (e: Exception) {
            return false
        }
    }
}
