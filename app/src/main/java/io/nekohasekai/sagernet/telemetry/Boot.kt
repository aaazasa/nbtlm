package io.nekohasekai.sagernet.telemetry

import android.content.Context

/**
 * 启动痕迹。
 *
 * 只在排查「一进 App 就闪退 / 卡死，看不到任何提示」时有用：
 * 每一步都写进 SharedPreferences，随心跳的 diag 上报。
 * 进程就算被杀，下次起来也能从面板上看到上一次停在哪个阶段。
 *
 * 写成同步 commit：异步写有可能还没落盘进程就没了，那这条痕迹就白记了。
 */
object Boot {

    private const val PREF = "tlm_boot"

    fun mark(ctx: Context, stage: String) {
        runCatching {
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString("stage", stage)
                .putLong("at", System.currentTimeMillis())
                .commit()
        }
    }

    fun stage(ctx: Context): String = runCatching {
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val s = p.getString("stage", "") ?: ""
        if (s.isBlank()) return@runCatching ""
        val at = p.getLong("at", 0L)
        val ago = if (at > 0) (System.currentTimeMillis() - at) / 1000L else -1L
        "$s (${ago}s ago)"
    }.getOrDefault("")
}
