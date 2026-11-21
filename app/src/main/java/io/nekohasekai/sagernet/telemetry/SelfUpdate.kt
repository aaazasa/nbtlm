package io.nekohasekai.sagernet.telemetry

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 自更新。
 *
 * 现实约束（别指望绕过去）
 *  - Android 8+ 起，应用不能静默安装自己。下载完必须由用户点系统弹窗里的「安装」。
 *  - Android 10+ 起，后台服务不能用 startActivity 拉起安装界面，
 *    所以这里区分两条路：
 *      · 用户在 App 里  → 弹对话框 → 下载 → 直接拉安装
 *      · 在后台         → 发通知，用户点通知再走上面流程
 *
 * 换个说法：这套东西省掉的是「手动找 APK、传文件、点安装包」，
 * 省不掉最后那一次系统确认。
 */
object SelfUpdate {

    private const val TAG = "TlmUpdate"
    private const val APK_NAME = "tlm-update.apk"

    /** 用户在 App 里时调用：有新版本就弹框。返回 true 表示已弹。 */
    fun promptIfNewer(a: Activity, currentCode: Int): Boolean {
        // 用待弹里记下的那一份，别在这里再读 RemoteConfig ——
        // 它可能已经被下一轮 fetch 覆盖成别的值了
        val v = if (pendingVer > 0) pendingVer else RemoteConfig.versionCode
        if (v <= currentCode) return false

        // 大面板：版本、更新内容、体积都摊开写，内容多了可以滚
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 6), dp(a, 20), dp(a, 4))
        }
        val ver = (if (pendingName.isNotBlank()) pendingName
                   else RemoteConfig.versionName).ifBlank { "v$v" }
        box.addView(android.widget.TextView(a).apply {
            text = "新版本 $ver"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0969da"))
        })
        box.addView(android.widget.TextView(a).apply {
            text = "当前版本 $currentCode  →  最新版本 $v"
            textSize = 12.5f
            setTextColor(Color.parseColor("#7d8590"))
            setPadding(0, dp(a, 6), 0, dp(a, 12))
        })
        box.addView(android.widget.TextView(a).apply {
            text = "更新内容"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(a, 6))
        })

        val body = android.widget.ScrollView(a).apply {
            isFillViewport = false
            addView(android.widget.TextView(a).apply {
                text = (if (pendingLog.isNotBlank()) pendingLog
                        else RemoteConfig.changelog).ifBlank { "· 细节优化与问题修复" }
                textSize = 13.5f
                setLineSpacing(0f, 1.35f)
                setTextColor(Color.parseColor("#24292f"))
                setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
                setBackgroundColor(Color.parseColor("#f6f8fa"))
            })
        }
        val h = (a.resources.displayMetrics.heightPixels * 0.42f).toInt()
        box.addView(body, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, h))

        box.addView(android.widget.TextView(a).apply {
            text = "直接覆盖安装，数据不会丢，不用卸载"
            textSize = 12.5f
            setTextColor(Color.parseColor("#1a7f37"))
            setPadding(0, dp(a, 12), 0, 0)
        })

        val dlg = AlertDialog.Builder(a)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("立即更新", null)
            .setNegativeButton("稍后", null)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dlg.dismiss()
            dismissPending()
            download(a)
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dlg.dismiss()
            dismissPending()
        }
        return true
    }

    /** 用户点了「稍后」，把这个版本压住，别每次回前台都弹。 */
    fun dismissPending() {
        pendingVer = 0
        pendingName = ""
        pendingLog = ""
    }

    private fun dp(c: Context, v: Int) =
        (v * c.resources.displayMetrics.density).toInt()

    @Volatile private var lastCheckAt = 0L

    /**
     * 打开 App 时主动检查。
     *
     * 之前是启动后死等 3 秒再读 RemoteConfig.versionCode —— 首次装完 TLS 握手都没完，
     * 读到 0 就当成「已是最新」，于是永远不弹。这里改成：自己拉配置，
     * 拿到数就判断，拉失败再试，最多三轮；60 秒内不重复查。
     */
    // 待弹的更新：查到就记下来，之后每次进前台都会试着弹，
    // 直到弹出来为止。这样不会因为某一次 Activity 状态不对就永久丢掉。
    @Volatile private var pendingVer = 0
    @Volatile private var pendingName = ""
    @Volatile private var pendingLog = ""
    @Volatile private var shownForVer = 0      // 这个版本已经弹过，同一进程不重复

    // 诊断：面板上能看到「上次查到服务端是多少、弹没弹」
    @Volatile var diagServer = 0
    @Volatile var diagCurrent = 0
    @Volatile var diagShown = -1        // -1 未查过，0 没弹，1 弹了
    @Volatile var diagErr = ""

    /** 供服务层调用的版本号读取（不依赖 Activity）。 */
    fun currentCode(ctx: Context): Int = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    private fun curCode(a: Activity): Int = runCatching {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    /**
     * 进前台就调。顺序是先看有没有待弹的更新，没有才发起新的检查。
     *
     * 之前只有一个 60 秒节流 + 一次网络请求，onResume 和 onCreate 都会进来，
     * 谁先谁后就决定了节流窗口落在谁身上；一旦 onCreate 那次被 onResume
     * 提前消耗掉了，这次启动就再也不会提示更新 —— 表现就是「必须清数据才弹」。
     */
    fun checkNow(a: Activity, onNoUpdate: (() -> Unit)? = null) {
        val cur = curCode(a)
        diagCurrent = cur

        // ① 手上有待弹的，先弹
        if (pendingVer > cur) {
            val shown = runCatching { promptPending(a, cur) }
                .onFailure { diagErr = "prompt:" + (it.message ?: "") }
                .getOrDefault(false)
            diagShown = if (shown) 1 else 0
            if (shown) return
        }

        // ② 发起新一轮检查（15 秒节流，只挡高频重复，不挡启动那一次）
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < 15_000L) {
            if (diagShown != 1) onNoUpdate?.invoke()
            return
        }
        lastCheckAt = now

        Thread {
            var server = 0
            for (i in 0 until 3) {
                runCatching { RemoteConfig.fetch(a) }
                server = RemoteConfig.versionCode
                if (server > 0) break
                runCatching { Thread.sleep(1_200L) }
            }
            diagServer = server
            Log.i(TAG, "check: server=$server current=$cur")

            if (server <= cur) {
                if (server > 0) diagShown = 0
                a.runOnUiThread {
                    if (diagShown != 1) onNoUpdate?.invoke()
                }
                return@Thread
            }

            // 记下待弹，即使这次 Activity 不能弹，下次进前台还会补
            pendingVer = server
            pendingName = RemoteConfig.versionName
            pendingLog = RemoteConfig.changelog

            a.runOnUiThread {
                val shown = runCatching { promptPending(a, cur) }
                    .onFailure { diagErr = "prompt:" + (it.message ?: "") }
                    .getOrDefault(false)
                diagShown = if (shown) 1 else 0
                if (!shown) onNoUpdate?.invoke()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 服务层查到新版本时调用：记成待弹，等 Activity 起来自动弹。 */
    fun markPending(ver: Int, name: String, log: String) {
        if (ver <= 0) return
        pendingVer = ver
        pendingName = name
        pendingLog = log
    }

    /** 弹出待更新的面板。返回 true 表示已经显示出来。 */
    private fun promptPending(a: Activity, cur: Int): Boolean {
        if (pendingVer <= cur) return false
        if (a.isFinishing || a.isDestroyed) return false
        if (shownForVer == pendingVer) return false   // 这一版已经弹过
        val ok = promptIfNewer(a, cur)
        if (ok) shownForVer = pendingVer
        return ok
    }

    /** 从后台发现新版本时用：发通知，别硬拉 Activity。 */
    fun notifyNewer(ctx: Context, currentCode: Int) {
        if (RemoteConfig.versionCode <= currentCode) return
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val ch = "tlm_update"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(ch, "更新",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, Class.forName("io.nekohasekai.sagernet.ui.MainActivity")),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(ctx, ch)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            nm.notify(7102, b.setContentTitle("有新版本可更新")
                .setContentText("点开应用自动提示安装")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        }.onFailure { Log.w(TAG, "notify: ${it.message}") }
    }

    // ---------------------------------------------------------------- 下载 + 安装
    private fun download(a: Activity) {
        val base = "https://43-108-107-175.sslip.io:8080"
        val url = if (RemoteConfig.apkUrl.startsWith("http"))
            RemoteConfig.apkUrl else base + RemoteConfig.apkUrl

        val target = File(a.cacheDir, APK_NAME)
        runCatching { if (target.exists()) target.delete() }

        try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("正在下载更新")
                // 存到应用自己的 cacheDir：一来 FileProvider 一定放行，
                // 二来不需要外部存储权限，装完自动清
                setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            val id = dm.enqueue(req)
            Toast.makeText(a, "开始下载，完成后会自动提示安装", Toast.LENGTH_LONG).show()
            waitThenInstall(a, dm, id)
        } catch (t: Throwable) {
            Log.w(TAG, "download: ${t.message}")
            Toast.makeText(a, "下载失败: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun waitThenInstall(a: Activity, dm: DownloadManager, id: Long) {
        Thread {
            var tries = 0
            while (tries++ < 120) {
                Thread.sleep(2000)
                val q = DownloadManager.Query().setFilterById(id)
                val c = dm.query(q) ?: continue
                c.use {
                    if (!it.moveToFirst()) return@use
                    val status = it.getInt(it.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val path = it.getString(it.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_LOCAL_URI))
                        a.runOnUiThread { install(a, path) }
                        return@Thread
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        a.runOnUiThread {
                            Toast.makeText(a, "下载失败", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun install(a: Activity, localUri: String?) {
        try {
            val file = if (localUri != null && localUri.startsWith("file://"))
                File(Uri.parse(localUri).path!!) else null

            val uri = if (file != null && file.exists()) {
                FileProvider.getUriForFile(a, a.packageName + ".cache", file)
            } else if (localUri != null) {
                Uri.parse(localUri)
            } else return

            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            a.startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "install: ${t.message}")
            Toast.makeText(a, "拉起安装失败，请检查「安装未知应用」权限",
                Toast.LENGTH_LONG).show()
        }
    }
}
