package ru.queuejw.lumetro.ai

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.URLEncoder

/**
 * 手机控制页的 Shizuku 状态与本地执行。
 *
 * 启动器依赖的 Shizuku API 13.1.5 把 newProcess() 标成了 private（CodexWeb 用的
 * 是公开版本）。这里用反射调用同一个方法拿到 ShizukuRemoteProcess，从而像
 * CodexWeb 那样在启动器内直接执行白名单命令，不再依赖 CodexWeb App 在后台轮询
 * /api/device/poll 队列——那条链路在对方 App 没运行时会表现为「点了没反应」。
 */
object PhoneControl {

    fun status(ctx: Context): JSONObject = JSONObject().apply {
        val installed = try {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) { false }
        val binder = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        put("installed", installed)
        put("binder", binder)
        put("granted", granted)
        put("serverVersion", runCatching { Shizuku.getVersion() }.getOrDefault(-1))
        put("ok", installed && binder && granted)
    }

    fun request(): JSONObject = JSONObject().apply {
        val binder = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (binder) runCatching { Shizuku.requestPermission(1) }
        put("sent", binder)
        if (!binder) put("error", "Shizuku 服务未连接，请先启动 Shizuku")
    }

    fun openShizuku(ctx: Context): JSONObject = JSONObject().apply {
        val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent == null) {
            put("ok", false); put("error", "未安装 Shizuku")
        } else {
            runCatching {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                put("ok", true)
            }.onFailure { put("ok", false); put("error", it.toString()) }
        }
    }

    fun actions(): List<Pair<String, String>> = listOf(
        "tap" to "点击坐标", "swipe" to "滑动", "text" to "输入文本",
        "key" to "按键", "openapp" to "打开应用", "appsearch" to "应用内搜索",
        "openurl" to "打开网址", "dial" to "拨号盘", "wifi" to "Wi-Fi", "bluetooth" to "蓝牙",
        "airplane" to "飞行模式", "brightness" to "亮度",
        "timeout" to "熄屏时间", "screenshot" to "截图"
    )

    /**
     * 在启动器进程内通过 Shizuku 执行白名单动作。必须在 IO 线程调用。
     * 返回与 CodexWeb PhoneControlBridge.execute 相同结构：ok/cmd/exit/out/err。
     */
    suspend fun execute(action: String, arg: String): JSONObject = withContext(Dispatchers.IO) {
        val o = JSONObject()
        val cmd = validate(action, arg)
        if (cmd == null) {
            o.put("ok", false); o.put("error", "动作或参数不合法：$action $arg")
            return@withContext o
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            o.put("ok", false)
            o.put("error", "Shizuku 尚未授权，请先在上方「请求授权」并在系统弹窗中允许")
            return@withContext o
        }
        try {
            // 13.1.5 里 newProcess 是 private static；反射拿到后强制可访问。
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val proc = m.invoke(null, arrayOf("/system/bin/sh", "-c", cmd), null, null) as Process
            runCatching { proc.outputStream?.close() }
            val out = proc.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val err = proc.errorStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val code = proc.waitFor()
            o.put("ok", true)
            o.put("cmd", cmd)
            o.put("exit", code)
            o.put("out", out.trim().take(4000))
            o.put("err", err.trim().take(4000))
        } catch (t: Throwable) {
            o.put("ok", false)
            o.put("error", "${t.javaClass.simpleName}: ${t.message}" +
                "（若反复失败，请确认 Shizuku 服务已启动并彻底重开本 App）")
        }
        o
    }

    /** 与 Web 端一致的白名单校验：非法动作不提交给后端 */
    fun validate(action: String, arg: String): String? = build(action, arg.trim())

    private fun isInt(s: String) = s.toIntOrNull() != null

    /** shell 单引号转义：中文、空格、英文引号都能安全传给 input/am */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 与 CodexWeb PhoneControlBridge.build 完全同构的白名单命令。
     * 之前这里用了 `monkey -p pkg`（缺事件数）、`am start url`（缺 -d）、
     * `input keyevent HOME`（缺数字码）等无效命令，表现为「点了执行但手机没反应」。
     */
    private fun build(action: String, a: String): String? = when (action) {
        "tap" -> {
            val v = a.split(Regex("\\s+"))
            if (v.size == 2 && v.all { isInt(it) }) "input tap ${v[0]} ${v[1]}" else null
        }
        "swipe" -> {
            val v = a.split(Regex("\\s+"))
            if (v.size in 4..5 && v.all { isInt(it) }) "input swipe " + v.joinToString(" ") else null
        }
        "text" -> if (a.isNotBlank()) "input text ${q(a)}" else null
        "key" -> {
            val codes = mapOf(
                "HOME" to 3, "BACK" to 4, "RECENTS" to 187, "POWER" to 26,
                "VOLUP" to 24, "VOLDOWN" to 25, "MUTE" to 0, "ENTER" to 66,
                "DEL" to 67, "CAMERA" to 27, "PLAY" to 85, "PAUSE" to 85
            )
            codes[a.uppercase()]?.let { "input keyevent $it" } ?: if (isInt(a)) "input keyevent $a" else null
        }
        "openapp" ->
            if (Regex("^[A-Za-z0-9_.]+$").matches(a))
                "monkey -p ${q(a)} -c android.intent.category.LAUNCHER 1" else null
        // 「B站 周杰伦」这类应用内搜索：别名走官方 deep link，未知包名走 ACTION_SEARCH。
        "appsearch" -> {
            val v = a.split(Regex("\\s+"), 2)
            if (v.size != 2 || v[1].isBlank() || v[1].length > 200) null
            else {
                val app = v[0]
                val kw = v[1]
                var tpl: String? = null
                val pkg = when (app.lowercase()) {
                    "bili", "bilibili", "b站", "哔哩哔哩" -> {
                        tpl = "bilibili://search?keyword=%s"; "tv.danmaku.bili"
                    }
                    "douyin", "抖音" -> {
                        tpl = "snssdk1128://search?keyword=%s"; "com.ss.android.ugc.aweme"
                    }
                    "taobao", "淘宝" -> {
                        tpl = "taobao://s?keyword=%s"; "com.taobao.taobao"
                    }
                    "zhihu", "知乎" -> {
                        tpl = "zhihu://search?q=%s"; "com.zhihu.android"
                    }
                    "weibo", "微博" -> {
                        tpl = "sinaweibo://searchall?q=%s"; "com.sina.weibo"
                    }
                    "netease", "网易云音乐" -> {
                        tpl = "orpheus://search?keyword=%s"; "com.netease.cloudmusic"
                    }
                    "jd", "京东" -> "com.jingdong.app.mall"
                    "baidu", "百度" -> "com.baidu.searchbox"
                    else -> if (Regex("^[A-Za-z0-9_.]{1,120}$").matches(app)) app else return null
                }
                val enc = runCatching { URLEncoder.encode(kw, "UTF-8") }.getOrNull() ?: return null
                if (tpl != null) {
                    val uri = tpl.format(enc)
                    "am start -a android.intent.action.VIEW -d ${q(uri)} -p ${q(pkg)}"
                } else {
                    "am start -a android.intent.action.SEARCH -p ${q(pkg)} " +
                        "--es android.intent.extra.TEXT ${q(kw)}"
                }
            }
        }
        "openurl" ->
            if (Regex("^https?://[^\\s]+$").matches(a))
                "am start -a android.intent.action.VIEW -d ${q(a)}" else null
        "dial" ->
            if (Regex("^[0-9+*#\\- ]+$").matches(a))
                "am start -a android.intent.action.DIAL -d ${q("tel:" + a.replace(" ", ""))}" else null
        "wifi", "bluetooth" -> if (a == "on" || a == "off") "svc $action $a" else null
        "airplane" -> if (a == "on" || a == "off") {
            val v = if (a == "on") "1" else "0"
            val b = if (a == "on") "true" else "false"
            "settings put global airplane_mode_on $v; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $b"
        } else null
        "brightness" -> if (a.toIntOrNull() in 1..255) "settings put system screen_brightness $a" else null
        "timeout" -> if (a == "never") "settings put system screen_off_timeout 2147483647"
                     else if (isInt(a)) "settings put system screen_off_timeout $a" else null
        "screenshot" -> "screencap -p /sdcard/Pictures/cxweb-shot-${System.currentTimeMillis()}.png"
        else -> null
    }
}
