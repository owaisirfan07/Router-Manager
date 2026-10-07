package com.cpagency.wifimanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One shared logged-in Huawei session for the app and the background monitor. */
object RouterSession {
    @Volatile var client: RouterClient? = null

    fun get(c: Context): RouterClient? {
        client?.let { return it }
        val p = c.getSharedPreferences("login", Context.MODE_PRIVATE)
        val ip = p.getString("ip", null) ?: return null
        val user = p.getString("user", null) ?: return null
        val pass = p.getString("pass", null) ?: return null
        return try {
            RouterClient("http://$ip").also { it.login(user, pass); client = it }
        } catch (_: Exception) { null }
    }
}

/** Live internet status, published by the monitor for the screens. */
object Monitor {
    enum class Status { ONLINE, DOWN, AWAY, STARTING }

    data class Live(
        val status: Status = Status.STARTING,
        val kind: OutageStore.Kind? = null,
        val reason: String = "",
        val since: Long = 0,
        val routerMs: Long? = null,
        val ispMs: Long? = null,
        val googleMs: Long? = null,
        val cloudMs: Long? = null,
        val ispLearned: Boolean = false,
        val updatedAt: Long = 0,
        val tpState: String = "",
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    internal fun publish(l: Live) { _live.value = l }

    private const val PREFS = "monitor"
    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        if (on) start(c) else stop(c)
    }

    fun tpPassword(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tp_pass", null)
    fun tpHost(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tp_host", "192.168.0.1")!!
    fun setTpLink(c: Context, host: String, pass: String?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tp_host", host).putString("tp_pass", pass).apply()
    }

    fun start(c: Context) {
        if (!isEnabled(c) || c.getSharedPreferences("login", Context.MODE_PRIVATE).getString("pass", null) == null) return
        try { ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java)) } catch (_: Exception) { }
    }

    fun stop(c: Context) { c.stopService(Intent(c, MonitorService::class.java)) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NetUtil.appContext = context.applicationContext
            Monitor.start(context)
        }
    }
}

/**
 * Background internet monitor (runs while this phone is on the home WiFi):
 *  - every 15 s: router / ISP / Google DNS / Cloudflare checks -> outages
 *  - every 15 s: TP-Link per-device speeds -> data usage (estimate)
 *  - every 60 s: Huawei WiFi byte counters -> data usage (exact)
 */
class MonitorService : Service() {
    companion object {
        private const val CH_STATUS = "monitor_status"
        private const val CH_ALERT = "internet_alerts"
        private const val ID_STATUS = 7
        private const val ID_ALERT = 8
        private const val TICK_MS = 15_000L
        private const val HUAWEI = "192.168.100.1"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val tp by lazy { TpLinkClient(Monitor.tpHost(this)) }

    // monitor state (kept in memory; outages themselves are saved in OutageStore)
    private var failCount = 0
    private var firstFailAt = 0L
    private var lastFailAt = 0L
    private var lastActiveAt = 0L
    private var lastOnlineAt = 0L
    private var tick = 0L
    private var ispHosts: List<Pair<String, Int>> = emptyList()
    private var ispLearned: Pair<String, Int>? = null
    private var reason = ""
    private var reasonAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NetUtil.appContext = applicationContext
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Internet monitor", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false); description = "Shows that the internet monitor is running"
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Internet down alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when the internet goes down and comes back"
            })
        }
        getSharedPreferences("monitor", MODE_PRIVATE).getString("isp_learned", null)?.split(':')?.let {
            if (it.size == 2) ispLearned = it[0] to (it[1].toIntOrNull() ?: 53)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, ID_STATUS, statusNotification("Internet monitor chal raha hai"), type)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive) {
                try { runTick() } catch (_: Exception) { }
                delay(TICK_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun runTick() {
        tick++
        val now = System.currentTimeMillis()
        val wifi = NetUtil.wifiNetwork(this)
        if (wifi == null) { away(now, "Phone WiFi par nahi hai"); return }

        val r = scope.async { NetUtil.probe(HUAWEI, 80, 2000, wifi) }
        val g = scope.async { NetUtil.probe("8.8.8.8", 53, 2500, wifi) }
        val cf = scope.async { NetUtil.probe("1.1.1.1", 53, 2500, wifi) }
        val isp = scope.async { ispProbe(wifi) }
        val tpList = scope.async { tpPoll() }
        val routerMs = r.await(); val googleMs = g.await(); val cloudMs = cf.await(); val ispMs = isp.await()
        val tpState = tpList.await()

        val internetOk = googleMs != null || cloudMs != null
        val home = routerMs != null || tpState.startsWith("OK")
        if (!home) { away(now, "Router se rabta nahi (phone ghar par nahi, ya bijli band)"); return }

        // came back after a gap the app didn't watch? check if the router reconnected meanwhile
        val gap = if (lastActiveAt > 0) now - lastActiveAt else 0
        lastActiveAt = now

        if (routerMs != null && (tick % 20 == 1L || gap > 120_000)) refreshWan(now, gap, internetOk)
        if (routerMs != null && tick % 4 == 1L) huaweiCounters(now)

        if (internetOk) {
            lastOnlineAt = now
            failCount = 0
            OutageStore.open(this)?.let { o ->
                val end = if (gap > 120_000) lastFailAt.coerceAtLeast(o.start) else now
                val closed = o.copy(end = end, approx = o.approx || gap > 120_000, reason = o.reason.ifBlank { reason })
                OutageStore.add(this, closed); OutageStore.setOpen(this, null)
                alert("Internet wapas aa gaya", "${o.kind.label} - ${durText(closed.durationMs)} band raha")
            }
            reason = ""
            publish(Monitor.Status.ONLINE, null, now, routerMs, ispMs, googleMs, cloudMs, tpState)
            return
        }

        // internet failing
        if (failCount == 0) firstFailAt = now
        failCount++
        lastFailAt = now
        val kind = when {
            routerMs == null -> OutageStore.Kind.ROUTER
            ispLearned != null && ispMs != null -> OutageStore.Kind.INTERNET
            else -> OutageStore.Kind.ISP
        }
        if (routerMs != null && now - reasonAt > 60_000) { reason = diagnose(); reasonAt = now }
        if (failCount >= 2) {
            val open = OutageStore.open(this)
            if (open == null) {
                val o = OutageStore.Outage(firstFailAt, 0, kind, reason)
                OutageStore.setOpen(this, o)
                alert("Internet band hai", "${kind.label}${if (reason.isNotBlank()) " - $reason" else ""}")
            } else if (open.reason != reason && reason.isNotBlank()) {
                OutageStore.setOpen(this, open.copy(reason = reason))
            }
            publish(Monitor.Status.DOWN, kind, OutageStore.open(this)?.start ?: firstFailAt, routerMs, ispMs, googleMs, cloudMs, tpState)
        } else {
            publish(Monitor.Status.ONLINE, null, lastOnlineAt, routerMs, ispMs, googleMs, cloudMs, tpState)
        }
    }

    private fun away(now: Long, why: String) {
        Monitor.publish(Monitor.live.value.copy(status = Monitor.Status.AWAY, reason = why, updatedAt = now))
        updateStatus("Monitor ruka hua - $why")
    }

    private fun publish(st: Monitor.Status, kind: OutageStore.Kind?, since: Long, routerMs: Long?, ispMs: Long?,
                        googleMs: Long?, cloudMs: Long?, tpState: String) {
        Monitor.publish(Monitor.Live(st, kind, reason, since, routerMs, ispMs, googleMs, cloudMs,
            ispLearned != null, System.currentTimeMillis(), tpState))
        val usage = UsageStore.current(this)
        updateStatus(
            if (st == Monitor.Status.ONLINE) "Internet online · ${(googleMs ?: cloudMs) ?: "--"} ms · is mahine ${UsageStore.fmt(usage.total)}"
            else "Internet band · ${kind?.label ?: ""}"
        )
    }

    /** ISP check: a host on the ISP side (WAN gateway / ISP DNS) that has answered at least once. */
    private fun ispProbe(wifi: android.net.Network): Long? {
        ispLearned?.let { (h, p) -> return NetUtil.probe(h, p, 2000, wifi) }
        for ((h, p) in ispHosts) {
            val ms = NetUtil.probe(h, p, 2000, wifi)
            if (ms != null) {
                ispLearned = h to p
                getSharedPreferences("monitor", MODE_PRIVATE).edit().putString("isp_learned", "$h:$p").apply()
                return ms
            }
        }
        return null
    }

    private fun tpPoll(): String {
        val pass = Monitor.tpPassword(this) ?: return "TP-Link password set nahi"
        return try {
            tp.setPassword(pass)
            val list = tp.stations()
            UsageStore.addTpLink(this, list)
            "OK ${list.count { it.online }} devices"
        } catch (e: Exception) {
            "TP-Link: ${e.message ?: "nahi mila"}"
        }
    }

    private fun huaweiCounters(now: Long) {
        try {
            val c = RouterSession.get(this) ?: return
            val p = c.page("html/amp/wlaninfo/wlaninfo.asp", maxAgeMs = 0).first("stPacketInfo") ?: return
            val sent = p["totalBytesSent"].toLongOrNull() ?: return
            val recv = p["totalBytesReceived"].toLongOrNull() ?: return
            UsageStore.addHuawei(this, sent, recv, now)
        } catch (_: Exception) { }
    }

    /** Reads the router's WAN info: learns ISP hosts, and spots reconnects the app missed. */
    private fun refreshWan(now: Long, gap: Long, internetOk: Boolean) {
        try {
            val c = RouterSession.get(this) ?: return
            val d = c.page("html/bbsp/common/wan_list_info.asp", "html/bbsp/common/wan_list.asp", maxAgeMs = 0)
            val wan = Custom.internetWan(d) ?: return
            val hosts = ArrayList<Pair<String, Int>>()
            wan["dnsstr"].split(',', ' ').map { it.trim() }.filter { it.count { ch -> ch == '.' } == 3 }.forEach { hosts += it to 53 }
            wan["Gateway"].trim().takeIf { it.count { ch -> ch == '.' } == 3 }?.let { hosts += it to 80; hosts += it to 53 }
            ispHosts = hosts
            val uptimeS = wan["Uptime"].trim().toLongOrNull()
            if (gap > 120_000 && internetOk && uptimeS != null && uptimeS * 1000 < gap) {
                val reconnectAt = now - uptimeS * 1000
                OutageStore.add(this, OutageStore.Outage(reconnectAt, reconnectAt, OutageStore.Kind.UNSEEN,
                    "Router ka internet ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(reconnectAt))} par dobara juda", approx = true))
            }
        } catch (_: Exception) { }
    }

    /** Asks the router why the internet is down (the router is still reachable on WiFi). */
    private fun diagnose(): String = try {
        val c = RouterSession.get(this)
        if (c == null) "" else {
            val d = c.page("html/bbsp/common/wan_list_info.asp", "html/bbsp/common/wan_list.asp", "html/amp/opticinfo/opticinfo.asp", maxAgeMs = 0)
            val wan = Custom.internetWan(d)
            val los = d.first("stOpticInfo")?.get("LosStatus")?.trim()
            when {
                los == "1" -> "Fibre signal nahi aa raha (LOS) - fibre cable ya ISP ki taraf masla"
                wan != null && wan["ConnectionStatus"] != "Connected" ->
                    "Router ISP se connect nahi ho pa raha" + (wan["LastConnErr"].takeIf { it.isNotBlank() && it != "ERROR_NONE" }?.let { " ($it)" } ?: "")
                wan != null -> "Router ISP se connected hai magar internet nahi chal raha - ISP ka masla"
                else -> ""
            }
        }
    } catch (_: Exception) { "" }

    private fun durText(ms: Long): String {
        val m = ms / 60_000
        return when { m < 1 -> "1 minute se kam"; m < 60 -> "$m minute"; else -> "${m / 60} ghante ${m % 60} minute" }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).putExtra("open", "internet").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Router Manager")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private var lastStatusText = ""
    private fun updateStatus(text: String) {
        if (text == lastStatusText) return
        lastStatusText = text
        try { getSystemService(NotificationManager::class.java).notify(ID_STATUS, statusNotification(text)) } catch (_: Exception) { }
    }

    private fun alert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp()).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(ID_ALERT, n) } catch (_: SecurityException) { }
    }
}
