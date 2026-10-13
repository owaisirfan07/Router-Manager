package com.cpagency.wifimanager

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var client: RouterClient
    private val actions by lazy { RouterActions(client) }
    private var loggedIn = false
    private var currentInfo: RouterClient.WifiInfo? = null
    private val securityModes = RouterClient.SecurityMode.values()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    /** Screen history inside the current tab. */
    private val stack = ArrayList<String>()
    private var currentTab = "home"
    private var syncingTab = false

    // views
    private lateinit var scrollRoot: NestedScrollView
    private lateinit var navRow: View
    private lateinit var backButton: Button
    private lateinit var screenTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var loginSection: View
    private lateinit var wifiSection: View
    private lateinit var content: LinearLayout
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var ssidInput: TextInputEditText
    private lateinit var newPasswordInput: TextInputEditText
    private lateinit var wifiEnabledSwitch: SwitchMaterial
    private lateinit var broadcastSwitch: SwitchMaterial
    private lateinit var wmmSwitch: SwitchMaterial
    private lateinit var wpsSwitch: SwitchMaterial
    private lateinit var securityModeDropdown: AutoCompleteTextView
    private lateinit var maxDevicesInput: TextInputEditText

    /** Screen to open after login (e.g. tapped an "internet down" notification). */
    private var pendingOpen: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val target = intent.getStringExtra("open") ?: return
        if (loggedIn) show(target) else pendingOpen = target
    }

    private fun applyTheme() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (getSharedPreferences("ui", MODE_PRIVATE).getInt("theme", 0)) {
                1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        NetUtil.appContext = applicationContext
        pendingOpen = intent?.getStringExtra("open")
        setContentView(R.layout.activity_main)

        scrollRoot = findViewById(R.id.scrollRoot)
        navRow = findViewById(R.id.navRow)
        backButton = findViewById(R.id.backButton)
        screenTitle = findViewById(R.id.screenTitle)
        statusText = findViewById(R.id.statusText)
        loginSection = findViewById(R.id.loginSection)
        wifiSection = findViewById(R.id.wifiSection)
        content = findViewById(R.id.contentContainer)
        bottomNav = findViewById(R.id.bottomNav)

        val routerIpInput = findViewById<TextInputEditText>(R.id.routerIpInput)
        val usernameInput = findViewById<TextInputEditText>(R.id.usernameInput)
        val passwordInput = findViewById<TextInputEditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton)

        ssidInput = findViewById(R.id.ssidInput)
        newPasswordInput = findViewById(R.id.newPasswordInput)
        wifiEnabledSwitch = findViewById(R.id.wifiEnabledSwitch)
        broadcastSwitch = findViewById(R.id.broadcastSwitch)
        wmmSwitch = findViewById(R.id.wmmSwitch)
        wpsSwitch = findViewById(R.id.wpsSwitch)
        securityModeDropdown = findViewById(R.id.securityModeDropdown)
        maxDevicesInput = findViewById(R.id.maxDevicesInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        securityModeDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, securityModes.map { it.label })
        )

        // remember the last login on this phone
        val prefs = getSharedPreferences("login", MODE_PRIVATE)
        prefs.getString("ip", null)?.let { routerIpInput.setText(it) }
        prefs.getString("user", null)?.let { usernameInput.setText(it) }
        prefs.getString("pass", null)?.let { passwordInput.setText(it) }

        setupVersionText()

        backButton.setOnClickListener { goBack() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            if (loggedIn) client.clearCache() // Refresh = always ask the router again
            stack.lastOrNull()?.let { show(it, push = false) }
        }

        bottomNav.setOnItemSelectedListener { item ->
            if (!syncingTab) {
                val tab = when (item.itemId) {
                    R.id.tab_devices -> "devices"
                    R.id.tab_settings -> "settings"
                    R.id.tab_more -> "more"
                    else -> "home"
                }
                switchTab(tab)
            }
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            // tapping the current tab again goes back to its first screen
            if (stack.size > 1) switchTab(currentTab)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    stack.size > 1 -> goBack()
                    loggedIn && currentTab != "home" -> switchTab("home")
                    !loggedIn && stack.isNotEmpty() -> logout()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        loginButton.setOnClickListener {
            val ip = routerIpInput.text.toString().trim()
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString()

            client = RouterClient("http://$ip")
            statusText.text = "Logging in..."
            loginButton.isEnabled = false

            scope.launch {
                try {
                    withContext(Dispatchers.IO) { client.login(user, pass) }
                    prefs.edit().putString("ip", ip).putString("user", user).putString("pass", pass).putBoolean("auto", true).apply()
                    loggedIn = true
                    RouterSession.client = client
                    Monitor.start(this@MainActivity)
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
                    }
                    bottomNav.visibility = View.VISIBLE
                    switchTab("home")
                    if (pendingOpen != null) { show(pendingOpen!!); pendingOpen = null }
                } catch (e: Exception) {
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    loginButton.isEnabled = true
                }
            }
        }

        saveButton.setOnClickListener { saveWifi() }

        // auto login with the saved details (turned off when you log out yourself)
        if (prefs.getBoolean("auto", true) &&
            !prefs.getString("ip", null).isNullOrBlank() && !prefs.getString("user", null).isNullOrBlank() &&
            prefs.getString("pass", null) != null) {
            statusText.text = "Logging in..."
            if (savedInstanceState?.getString("screen") != null) pendingOpen = pendingOpen ?: savedInstanceState.getString("screen")
            loginButton.post { loginButton.performClick() }
        }

        // speed test works without logging in to the router
        findViewById<Button>(R.id.speedTestButton).setOnClickListener {
            stack.clear()
            show("speedtest")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (loggedIn) stack.lastOrNull()?.let { outState.putString("screen", it) }
    }

    override fun onDestroy() {
        speedTest?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /*  Navigation                                                          */
    /* ------------------------------------------------------------------ */

    private fun switchTab(tab: String) {
        currentTab = tab
        val id = when (tab) {
            "devices" -> R.id.tab_devices
            "settings" -> R.id.tab_settings
            "more" -> R.id.tab_more
            else -> R.id.tab_home
        }
        if (bottomNav.selectedItemId != id) {
            syncingTab = true
            bottomNav.selectedItemId = id
            syncingTab = false
        }
        stack.clear()
        show(tab)
    }

    private fun show(screen: String, push: Boolean = true) {
        if (push && stack.lastOrNull() != screen) stack.add(screen)
        loadJob?.cancel()
        pollJob?.cancel()
        liveJob?.cancel()
        speedTest?.stop()

        loginSection.visibility = View.GONE
        navRow.visibility = View.VISIBLE
        backButton.visibility = if (stack.size > 1 || !loggedIn) View.VISIBLE else View.INVISIBLE
        wifiSection.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()
        scrollRoot.scrollTo(0, 0)

        when {
            screen == "home" -> showDashboard()
            screen == "devices" -> showDevices()
            screen.startsWith("device:") -> showDevice(screen.removePrefix("device:"))
            screen.startsWith("tpdev:") -> showTpDevice(screen.removePrefix("tpdev:"))
            screen == "fibre" -> showFibreHistory()
            screen == "settings" -> showSettings()
            screen == "more" -> showMore()
            screen.startsWith("group:") -> showGroup(screen.removePrefix("group:"))
            screen == "wifiEdit" -> showWifiEdit()
            screen == "edit:radio" -> editRadio()
            screen == "edit:dhcp" -> editDhcp()
            screen == "edit:lan" -> editLanIp()
            screen == "edit:static" -> editReservations()
            screen == "edit:wan" -> editWan()
            screen == "speedtest" -> showSpeedTest()
            screen == "internet" -> showInternet()
            screen == "usage" -> showUsage()
            else -> Sections.byId(screen)?.let { showSection(it) }
        }
    }

    private fun goBack() {
        if (stack.size <= 1) {
            if (!loggedIn) logout()
            return
        }
        stack.removeAt(stack.size - 1)
        show(stack.last(), push = false)
    }

    private fun logout(manual: Boolean = false) {
        loadJob?.cancel()
        pollJob?.cancel()
        liveJob?.cancel()
        speedTest?.stop()
        if (manual) getSharedPreferences("login", MODE_PRIVATE).edit().putBoolean("auto", false).apply()
        loggedIn = false
        stack.clear()
        navRow.visibility = View.GONE
        content.visibility = View.GONE
        wifiSection.visibility = View.GONE
        bottomNav.visibility = View.GONE
        loginSection.visibility = View.VISIBLE
        screenTitle.text = "Router Manager"
        statusText.text = "Logged out"
    }

    /** Runs [block] in the background, showing errors as a card in [box]. */
    private fun load(box: LinearLayout, block: suspend () -> Unit) {
        box.addView(loadingText())
        loadJob = scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                box.removeAllViews()
                box.addView(errorCard(e))
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    private fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /* ------------------------------------------------------------------ */
    /*  HOME: animated flow + tiles                                         */
    /* ------------------------------------------------------------------ */

    private fun showDashboard() {
        screenTitle.text = "Home"
        statusText.text = "Loading..."

        val hero = FlowView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            elevation = dp(6).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
        }
        content.addView(hero)
        val body = box()
        content.addView(body)

        load(body) {
            val data = withContext(Dispatchers.IO) { client.loadSection(Sections.home) }
            body.removeAllViews()

            // internet card only when something is wrong - the hero already says "Online"
            val liveBox = box()
            body.addView(liveBox)
            watchLive(liveBox, homeOnly = true)

            val wan = Custom.internetWan(data)
            val up = wan?.get("ConnectionStatus") == "Connected"
            val devs = Custom.devices(data)
            val online = devs.filter { it["DevStatus"].equals("Online", true) }
            val info = data.first("stDeviceInfo")
            val optic = data.first("stOpticInfo")
            val rx = optic?.get("revOpticPower")?.trim().orEmpty()
            val rxVal = rx.toDoubleOrNull()
            val uptime = Fmt.value("Uptime", wan?.get("Uptime").orEmpty())

            hero.internetUp = up
            hero.statusTitle = if (up) "Online" else (wan?.get("ConnectionStatus")?.ifBlank { null } ?: "Offline")
            hero.statusSub = if (up) listOfNotNull(wan?.get("IPAddress")?.ifBlank { null }, uptime.ifBlank { null }?.let { "up $it" }).joinToString("  ·  ")
                             else "No internet from the ISP"
            hero.routerName = info?.get("ModelName") ?: "Router"
            hero.routerSub = "CPU ${data.v("cpuUsed").ifBlank { "--" }}  ·  RAM ${data.v("memUsed").ifBlank { "--" }}"
            hero.devicesLabel = "${online.size} device${if (online.size == 1) "" else "s"}"
            hero.update()

            val ssid = data.all("stWlanInfo").firstOrNull { it["ssid"].isNotBlank() }
            val fibreOk = rxVal?.let { it in -27.0..-8.0 }
            body.addView(tileRow(
                tile("Fibre signal", if (rxVal != null) "$rx dBm" else "--",
                    when { rxVal == null -> "--"; fibreOk == true -> "Good  ·  see history"; else -> "Weak - check cable" },
                    fibreOk) { show("fibre") },
                tile("Health", if (up && fibreOk == true) "All good" else "Check",
                    "Tap for diagnosis", if (up && fibreOk == true) true else null) { show("diagnose") }
            ))
            body.addView(usageTiles())

            body.addView(groupHeader("Quick actions"))
            body.addView(menuCard(
                Triple("WiFi name or password", (ssid?.get("ssid") ?: "") + if (ssid?.get("enable") == "1") "  ·  On" else "  ·  Off") { show("wifiEdit") },
                Triple("Internet speed test", "Real speed vs your package") { show("speedtest") },
                Triple("Reboot router", "Restart the router (about 2 min)") { confirmReboot() }
            ))
            statusText.text = "Connected to ${client.baseUrlHost()}"
            heroView = hero
            startTrafficPolling(hero)
        }
    }

    /** Live WiFi traffic: reads the router's WiFi byte counters every 3 s. */
    private fun startTrafficPolling(hero: FlowView) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastSent = -1L
            var lastRecv = -1L
            var lastTime = 0L
            while (isActive) {
                try {
                    val d = withContext(Dispatchers.IO) { client.page("html/amp/wlaninfo/wlaninfo.asp", maxAgeMs = 0) }
                    val p = d.first("stPacketInfo")
                    val sent = p?.get("totalBytesSent")?.toLongOrNull()
                    val recv = p?.get("totalBytesReceived")?.toLongOrNull()
                    val now = System.currentTimeMillis()
                    if (sent != null && recv != null) {
                        if (lastSent >= 0 && now > lastTime && sent >= lastSent && recv >= lastRecv) {
                            val secs = (now - lastTime) / 1000.0
                            hero.addSample((sent - lastSent) * 8 / secs / 1_000_000, (recv - lastRecv) * 8 / secs / 1_000_000)
                        }
                        lastSent = sent; lastRecv = recv; lastTime = now
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) { /* keep trying quietly */ }
                delay(5000)
            }
        }
    }

    // live graph only while the app is on screen
    private var heroView: FlowView? = null

    override fun onPause() {
        super.onPause()
        if (stack.lastOrNull() == "home") pollJob?.cancel()
    }

    private fun tileRow(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a); addView(b)
        (a.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
        (b.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
    }

    private fun tile(title: String, value: String, sub: String, good: Boolean?, onClick: () -> Unit): View {
        val card = baseCard()
        card.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(12) }
        card.addView(box().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply { text = title; textSize = 13f; setTextColor(color(R.color.textSecondary)) })
            addView(TextView(context).apply {
                text = value; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(when (good) { true -> R.color.good; false -> R.color.bad; null -> R.color.textPrimary }))
                setPadding(0, dp(4), 0, dp(2))
            })
            addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(color(R.color.textSecondary)) })
        })
        card.isClickable = true
        card.setOnClickListener { onClick() }
        return card
    }

    /* ------------------------------------------------------------------ */
    /*  DEVICES                                                             */
    /* ------------------------------------------------------------------ */

    /** IPs of this phone, to mark "This phone" and stop you blocking yourself. */
    private fun myIps(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun showDevices() {
        screenTitle.text = "Devices"
        statusText.text = "Loading..."
        val body = box()
        content.addView(body)
        load(body) {
            val (data, filter) = withContext(Dispatchers.IO) {
                client.page("html/bbsp/common/GetLanUserDevInfo.asp") to runCatching { actions.macFilter() }.getOrNull()
            }
            body.removeAllViews()
            val devs = Custom.devices(data)
            val mine = myIps()
            val blocked = { mac: String -> filter?.let { actions.isBlocked(mac, it) } == true }
            val online = devs.filter { it["DevStatus"].equals("Online", true) }
            val offline = devs - online.toSet()

            body.addView(groupHeader("Online (${online.size})"))
            body.addView(deviceList(online, mine, blocked))

            if (offline.isNotEmpty()) {
                val offBox = box().apply { visibility = View.GONE }
                lateinit var t: Button
                t = textButton("Show offline devices (${offline.size})") {
                    val open = offBox.visibility == View.VISIBLE
                    offBox.visibility = if (open) View.GONE else View.VISIBLE
                    t.text = if (open) "Show offline devices (${offline.size})" else "Hide offline devices"
                }
                body.addView(t)
                offBox.addView(deviceList(offline, mine, blocked))
                body.addView(offBox)
            }
            val nBlocked = filter?.rules?.size ?: 0
            if (nBlocked > 0) body.addView(noteText("$nBlocked device(s) have internet blocked."))

            // ---- TP-Link (view only) ----
            if (Monitor.tpPassword(this@MainActivity) != null) {
                val tpList = withContext(Dispatchers.IO) {
                    runCatching {
                        TpLinkClient(Monitor.tpHost(this@MainActivity)).apply { setPassword(Monitor.tpPassword(this@MainActivity)!!) }.stations()
                    }.getOrElse { Monitor.tpStations.value }
                }
                val usage = UsageStore.current(this@MainActivity).devices.associateBy { it.mac }
                body.addView(groupHeader("On TP-Link (${tpList.count { it.online }} online · view only)"))
                if (tpList.isEmpty()) {
                    body.addView(noteText("TP-Link not reachable. Its devices show here when this phone is on the TP-Link WiFi."))
                } else {
                    body.addView(baseCard().apply {
                        addView(box().apply {
                            tpList.sortedByDescending { it.online }.forEachIndexed { i, st ->
                                if (i > 0) addView(divider())
                                val used = usage[st.mac]?.let { "  ·  ${UsageStore.fmt(it.down + it.up)} this cycle" } ?: ""
                                val speed = if (st.online) "↓ ${rate(st.downBps)}  ↑ ${rate(st.upBps)}" else st.ip
                                addView(deviceRow(DeviceStore.display(this@MainActivity, st.mac, st.name), speed + used, st.online, null) {
                                    show("tpdev:${st.mac}")
                                })
                            }
                        })
                    })
                }
            }

            // ---- alerts ----
            val alertSw = SwitchMaterial(this@MainActivity).apply { isChecked = DeviceStore.alertsOn(this@MainActivity) }
            alertSw.setOnCheckedChangeListener { _, on -> DeviceStore.setAlertsOn(this@MainActivity, on) }
            body.addView(groupHeader("Alerts"))
            body.addView(baseCard().apply {
                addView(controlRow("New device alert", "Notify me when a device the app has never seen joins the WiFi", alertSw))
            })
            statusText.text = "${online.size} online · tap a device for controls"
        }
    }

    private fun deviceList(list: List<HwRecord>, mine: Set<String>, blocked: (String) -> Boolean): View {
        val card = baseCard()
        val b = box()
        list.forEachIndexed { i, d ->
            if (i > 0) b.addView(divider())
            val mac = d["MacAddr"]
            val name = DeviceStore.display(this, mac, d["HostName"].ifBlank { d["DevType"].ifBlank { "Unknown device" } })
            val via = if (d["PortType"] == "WIFI") "WiFi" else "Cable ${d["Port"]}"
            val tag = when {
                d["IpAddr"] in mine -> "This phone"
                blocked(mac) -> "Blocked"
                else -> null
            }
            b.addView(deviceRow(name, "${d["IpAddr"]} · $via", d["DevStatus"].equals("Online", true), tag) { show("device:$mac") })
        }
        card.addView(b)
        return card
    }

    private fun deviceRow(title: String, sub: String, online: Boolean, tag: String?, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(14), dp(12))
            isClickable = true
            val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = attrs.getDrawable(0); attrs.recycle()
            addView(TextView(context).apply {
                text = "●"; textSize = 12f
                setTextColor(color(if (online) R.color.good else R.color.divider))
                setPadding(0, 0, dp(12), 0)
            })
            addView(box().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(color(R.color.textPrimary)) })
                addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(color(R.color.textSecondary)) })
            })
            if (tag != null) addView(TextView(context).apply {
                text = tag; textSize = 12f
                setTextColor(color(if (tag == "Blocked") R.color.bad else R.color.primary))
                setPadding(dp(8), 0, dp(4), 0)
            })
            addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(color(R.color.textSecondary)) })
            setOnClickListener { onClick() }
        }

    private fun showDevice(mac: String) {
        screenTitle.text = "Device"
        statusText.text = mac
        val body = box()
        content.addView(body)
        load(body) {
            val (data, filter, res) = withContext(Dispatchers.IO) {
                Triple(
                    client.page("html/bbsp/common/GetLanUserDevInfo.asp"),
                    runCatching { actions.macFilter() }.getOrNull(),
                    runCatching { actions.reservations() }.getOrDefault(emptyList())
                )
            }
            body.removeAllViews()
            val dev = Custom.devices(data).firstOrNull { it["MacAddr"].equals(mac, true) }
                ?: throw IllegalStateException("Device not found any more")
            val name = DeviceStore.display(this@MainActivity, mac, dev["HostName"].ifBlank { dev["DevType"].ifBlank { "Unknown device" } })
            screenTitle.text = name
            val isMe = dev["IpAddr"] in myIps()
            val blocked = filter?.let { actions.isBlocked(mac, it) } == true

            body.addView(cardView(Custom.deviceCard(dev).copy(title = if (isMe) "$name (this phone)" else name)))

            // ---- controls ----
            body.addView(groupHeader("Controls"))
            val card = baseCard()
            val b = box()

            // internet on/off
            val sw = SwitchMaterial(this).apply { isChecked = !blocked; isEnabled = !isMe }
            b.addView(controlRow("Internet access",
                when { isMe -> "Can't block the phone you're using"; blocked -> "Blocked - this device has no internet"; else -> "Allowed" }, sw))
            sw.setOnCheckedChangeListener { v, allow ->
                v.isEnabled = false
                confirm(if (allow) "Allow internet?" else "Block internet?",
                    if (allow) "$name will get internet again." else "$name will stay connected to WiFi but won't have internet until you allow it again.",
                    onNo = { sw.setOnCheckedChangeListener(null); sw.isChecked = !allow; show("device:$mac", push = false) }
                ) {
                    runChange(if (allow) "Allowing..." else "Blocking...", { if (allow) actions.unblockDevice(mac) else actions.blockDevice(mac) },
                        done = { show("device:$mac", push = false) })
                }
            }
            b.addView(divider())

            // fixed IP
            val reserved = res.firstOrNull { it.mac.equals(mac, true) }
            b.addView(actionRow("Fixed IP address",
                if (reserved != null) "Always gets ${reserved.ip}" else "Not fixed (IP can change)",
                if (reserved != null) "Remove" else "Fix ${dev["IpAddr"]}") {
                if (reserved != null) {
                    confirm("Remove fixed IP?", "$name will get any free IP next time.") {
                        runChange("Removing...", { actions.deleteReservation(reserved.domain) }, done = { show("device:$mac", push = false) })
                    }
                } else {
                    confirm("Fix this IP?", "$name will always get ${dev["IpAddr"]} from the router.") {
                        runChange("Saving...", { actions.addReservation(mac, dev["IpAddr"]) }, done = { show("device:$mac", push = false) })
                    }
                }
            })

            b.addView(divider())
            b.addView(actionRow("Name on this phone", DeviceStore.nickname(this@MainActivity, mac) ?: "Not set (uses the router's name)", "Rename") {
                renameDevice(mac, name) { show("device:$mac", push = false) }
            })

            card.addView(b)
            body.addView(card)
            statusText.text = mac
        }
    }

    /** Bytes per second -> megabits per second (same unit as the internet package). */
    private fun rate(bytesPerSec: Long): String {
        val bits = bytesPerSec * 8.0
        return when {
            bits >= 1e6 -> "%.1f Mbps".format(bits / 1e6)
            bits >= 1e3 -> "%.0f Kbps".format(bits / 1e3)
            else -> "0 Kbps"
        }
    }

    private fun renameDevice(mac: String, current: String, done: () -> Unit) {
        val f = textField("Name", DeviceStore.nickname(this, mac) ?: "", hint = "Only saved on this phone. Leave empty to use the router's name.")
        val wrap = box().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f.view) }
        AlertDialog.Builder(this).setTitle("Rename $current").setView(wrap)
            .setPositiveButton("Save") { _, _ -> DeviceStore.setNickname(this, mac, f.get()); done() }
            .setNegativeButton("Cancel", null).show()
    }

    /** A device on the TP-Link router (view only - nothing is changed on the TP-Link). */
    private fun showTpDevice(mac: String) {
        screenTitle.text = "Device"
        statusText.text = "On TP-Link · view only"
        val body = box()
        content.addView(body)
        load(body) {
            val st = withContext(Dispatchers.IO) {
                runCatching {
                    TpLinkClient(Monitor.tpHost(this@MainActivity)).apply { setPassword(Monitor.tpPassword(this@MainActivity) ?: "") }.stations()
                }.getOrElse { Monitor.tpStations.value }
            }.firstOrNull { it.mac.equals(mac, true) }
            body.removeAllViews()
            val name = DeviceStore.display(this@MainActivity, mac, st?.name)
            screenTitle.text = name
            val used = UsageStore.current(this@MainActivity).devices.firstOrNull { it.mac.equals(mac, true) }
            val first = DeviceStore.firstSeen(this@MainActivity, mac)
            val rows = listOfNotNull(
                Row("Status", if (st?.online == true) "Online" else "Offline"),
                st?.let { Row("IP address", it.ip) },
                Row("MAC address", mac),
                st?.takeIf { it.online }?.let { Row("Speed now", "↓ ${rate(it.downBps)}   ↑ ${rate(it.upBps)}") },
                used?.let { Row("Data this cycle", "${UsageStore.fmt(it.down + it.up)}  (↓ ${UsageStore.fmt(it.down)} ↑ ${UsageStore.fmt(it.up)})") },
                if (first > 0) Row("First seen", java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.UK).format(java.util.Date(first))) else null
            )
            body.addView(cardView(Card(name, rows, note = "This device is on the TP-Link router. The app only reads the TP-Link, so blocking is not available here.")))
            body.addView(baseCard().apply {
                addView(actionRow("Name on this phone", DeviceStore.nickname(this@MainActivity, mac) ?: "Not set", "Rename") {
                    renameDevice(mac, name) { show("tpdev:$mac", push = false) }
                })
            })
        }
    }

    private fun controlRow(title: String, sub: String, sw: SwitchMaterial) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(12), dp(14), dp(12))
        addView(box().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(color(R.color.textPrimary)) })
            addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(color(R.color.textSecondary)) })
        })
        addView(sw)
    }

    private fun actionRow(title: String, sub: String, button: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(8), dp(8), dp(8))
        addView(box().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(color(R.color.textPrimary)) })
            addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(color(R.color.textSecondary)) })
        })
        addView(Button(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = button; setTextColor(color(R.color.primary)); setOnClickListener { onClick() }
        })
    }

    /* ------------------------------------------------------------------ */
    /*  SETTINGS                                                            */
    /* ------------------------------------------------------------------ */

    private fun showSettings() {
        screenTitle.text = "Settings"
        statusText.text = "Change router settings"

        content.addView(groupHeader("WiFi"))
        content.addView(menuCard(
            Triple("WiFi name & password", "Name, password, hide, WPS, security") { show("wifiEdit") },
            Triple("WiFi radio", "Channel, width, power, WiFi standard") { show("edit:radio") }
        ))
        content.addView(groupHeader("Network"))
        content.addView(menuCard(
            Triple("Internet (WAN)", "PPPoE username/password, VLAN, MRU, DNS") { show("edit:wan") },
            Triple("DHCP & DNS", "IP range given to devices, lease time, DNS") { show("edit:dhcp") },
            Triple("Fixed IPs", "Always give a device the same IP") { show("edit:static") },
            Triple("Router IP address", "The router's own LAN address") { show("edit:lan") }
        ))
        content.addView(groupHeader("App"))
        val modes = listOf("System default", "Light", "Dark")
        val cur = getSharedPreferences("ui", MODE_PRIVATE).getInt("theme", 0)
        content.addView(menuCard(
            Triple("Theme: ${modes[cur]}", "Light or dark look") {
                AlertDialog.Builder(this).setTitle("Theme")
                    .setSingleChoiceItems(modes.toTypedArray(), cur) { dlg, which ->
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("theme", which).apply()
                        dlg.dismiss()
                        applyTheme()
                    }.show()
            }
        ))
        content.addView(groupHeader("System"))
        content.addView(menuCard(
            Triple("Reboot router", "Restart the router (about 2 min)") { confirmReboot() },
            Triple("Log out", "Back to the login screen (stops auto login)") { logout(manual = true) }
        ))
    }

    private fun menuCard(vararg rows: Triple<String, String, () -> Unit>) = baseCard().apply {
        addView(box().apply {
            rows.forEachIndexed { i, (t, s, f) ->
                if (i > 0) addView(divider())
                addView(menuRow(t, s, f))
            }
        })
    }

    /* ---------------- form helpers ---------------- */

    private class Field(val view: View, val get: () -> String)

    private fun formCard(title: String?, vararg fields: Field) = baseCard().apply {
        addView(box().apply {
            setPadding(dp(18), dp(14), dp(18), dp(10))
            if (title != null) addView(TextView(context).apply {
                text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.textPrimary)); setPadding(0, 0, 0, dp(8))
            })
            fields.forEach { addView(it.view) }
        })
    }

    private fun textField(label: String, value: String, hint: String? = null, number: Boolean = false, password: Boolean = false): Field {
        val layout = layoutInflater.inflate(R.layout.field_text, null, false) as TextInputLayout
        layout.hint = label
        if (hint != null) layout.helperText = hint
        if (password) layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val edit = layout.findViewById<TextInputEditText>(R.id.fieldEdit)
        edit.inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        edit.typeface = Typeface.DEFAULT // password inputType switches to monospace otherwise
        edit.setText(value)
        return Field(layout) { edit.text.toString().trim() }
    }

    private fun switchField(label: String, checked: Boolean, sub: String? = null): Field {
        val sw = SwitchMaterial(this).apply { isChecked = checked }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            addView(box().apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(color(R.color.textPrimary)) })
                if (sub != null) addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(color(R.color.textSecondary)) })
            })
            addView(sw)
        }
        return Field(row) { if (sw.isChecked) "1" else "0" }
    }

    /** Dropdown. options = value to label. */
    private fun dropField(label: String, options: List<Pair<String, String>>, selected: String): Field {
        val layout = layoutInflater.inflate(R.layout.field_drop, null, false) as TextInputLayout
        layout.hint = label
        val drop = layout.findViewById<AutoCompleteTextView>(R.id.fieldDrop)
        drop.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, options.map { it.second }))
        drop.setText(options.firstOrNull { it.first == selected }?.second ?: selected, false)
        return Field(layout) { val t = drop.text.toString(); options.firstOrNull { it.second == t }?.first ?: t }
    }

    private fun warning(text: String) = cardView(Card("Careful", note = text, good = false))

    private fun confirm(title: String, msg: String, onNo: (() -> Unit)? = null, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Yes, continue") { _, _ -> onYes() }
            .setNegativeButton("Cancel") { _, _ -> onNo?.invoke() }
            .setOnCancelListener { onNo?.invoke() }
            .show()
    }

    /** Runs a change in the background with a small progress dialog. */
    private fun runChange(busy: String, work: () -> Unit, done: (() -> Unit)? = null) {
        val dialog = AlertDialog.Builder(this).setMessage(busy).setCancelable(false).show()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Saved on the router", Toast.LENGTH_SHORT).show()
                done?.invoke()
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity).setTitle("Not saved")
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton("OK") { _, _ -> done?.invoke() }.show()
            }
        }
    }

    /* ---------------- editors ---------------- */

    private fun editRadio() {
        screenTitle.text = "WiFi radio"
        statusText.text = "2.4 GHz radio settings"
        val body = box(); content.addView(body)
        load(body) {
            val r = withContext(Dispatchers.IO) { actions.radio() }
            body.removeAllViews()
            val ch = dropField("Channel", listOf("0" to "Auto (recommended)") + (1..13).map { "$it" to "Channel $it" }, r.channel)
            val width = dropField("Channel width", listOf("0" to "Auto 20/40 MHz", "1" to "20 MHz (more stable)", "2" to "40 MHz (faster)"), r.width)
            val power = dropField("Transmit power", listOf("100", "80", "60", "40", "20").map { it to "$it%" }, r.power)
            val std = dropField("WiFi standard", listOf(
                "11bgn" to "b/g/n mixed (recommended)", "11n" to "n only", "11bg" to "b/g mixed", "11g" to "g only", "11b" to "b only"), r.standard)
            val dtim = textField("DTIM period", r.dtim, "1-255", number = true)
            val beacon = textField("Beacon interval (ms)", r.beacon, "20-1000", number = true)
            val rts = textField("RTS threshold", r.rts, "1-2346", number = true)
            val frag = textField("Fragment threshold", r.frag, "256-2346", number = true)
            body.addView(formCard("Radio", ch, width, power, std))
            body.addView(formCard("Advanced (normally leave as is)", dtim, beacon, rts, frag))
            body.addView(noteText("WiFi will restart for a few seconds after saving - all devices reconnect."))
            body.addView(primaryButton("Save") {
                val n = r.copy(channel = ch.get(), width = width.get(), power = power.get(), standard = std.get(),
                    dtim = dtim.get(), beacon = beacon.get(), rts = rts.get(), frag = frag.get())
                confirm("Save WiFi radio?", "WiFi will drop for a few seconds.") {
                    runChange("Saving...", { actions.saveRadio(n) }, done = { show("edit:radio", push = false) })
                }
            })
            statusText.text = "2.4 GHz radio settings"
        }
    }

    private fun editDhcp() {
        screenTitle.text = "DHCP & DNS"
        statusText.text = "How the router hands out IPs"
        val body = box(); content.addView(body)
        load(body) {
            val l = withContext(Dispatchers.IO) { actions.lan() }
            body.removeAllViews()
            val en = switchField("DHCP server", l.dhcpEnabled, "Turn off only if another device gives out IPs")
            val start = textField("Start IP", l.start, "e.g. ${RouterActions.network(l.ip, l.mask)}2")
            val end = textField("End IP", l.end)
            val hours = (l.leaseSeconds / 3600).coerceAtLeast(1)
            val lease = dropField("Lease time", listOf("3600" to "1 hour", "21600" to "6 hours", "43200" to "12 hours",
                "86400" to "1 day", "259200" to "3 days", "604800" to "7 days").let { opts ->
                if (opts.any { it.first == l.leaseSeconds.toString() }) opts else opts + (l.leaseSeconds.toString() to "$hours hours")
            }, l.leaseSeconds.toString())
            val dns1 = textField("DNS 1", l.dns1, "Empty = router decides. e.g. 1.1.1.1 or 8.8.8.8")
            val dns2 = textField("DNS 2", l.dns2)
            body.addView(formCard("Router IP: ${l.ip}", en, start, end, lease))
            body.addView(formCard("DNS given to devices", dns1, dns2))
            body.addView(primaryButton("Save") {
                val n = l.copy(dhcpEnabled = en.get() == "1", start = start.get(), end = end.get(),
                    leaseSeconds = lease.get().toLongOrNull() ?: l.leaseSeconds, dns1 = dns1.get(), dns2 = dns2.get())
                confirm("Save DHCP settings?", "Devices pick up the change when they reconnect.") {
                    runChange("Saving...", { actions.saveDhcp(n) }, done = { show("edit:dhcp", push = false) })
                }
            })
            statusText.text = "How the router hands out IPs"
        }
    }

    private fun editLanIp() {
        screenTitle.text = "Router IP address"
        statusText.text = "The router's own address on your network"
        val body = box(); content.addView(body)
        load(body) {
            val l = withContext(Dispatchers.IO) { actions.lan() }
            body.removeAllViews()
            body.addView(warning("After changing it, the app logs in again at the new address. " +
                "Only the last number can change (same network ${RouterActions.network(l.ip, l.mask)}x), " +
                "otherwise every device incl. this phone would lose the router."))
            val ip = textField("Router IP", l.ip, "Outside the DHCP range ${l.start} - ${l.end}")
            body.addView(formCard("Subnet mask: ${l.mask}", ip))
            body.addView(primaryButton("Save") {
                val newIp = ip.get()
                if (newIp == l.ip) { Toast.makeText(this, "No change", Toast.LENGTH_SHORT).show(); return@primaryButton }
                confirm("Change router IP to $newIp?", "The app will reconnect to $newIp.") {
                    runChange("Saving...", {
                        actions.saveLanIp(newIp, l)
                        Thread.sleep(8000)
                        val prefs = getSharedPreferences("login", MODE_PRIVATE)
                        val newClient = RouterClient("http://$newIp")
                        newClient.login(prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")
                        client = newClient
                        prefs.edit().putString("ip", newIp).apply()
                    }, done = { switchTab("home") })
                }
            })
        }
    }

    private fun editReservations() {
        screenTitle.text = "Fixed IPs"
        statusText.text = "Always give a device the same IP"
        val body = box(); content.addView(body)
        load(body) {
            val (list, devData, lan) = withContext(Dispatchers.IO) {
                Triple(actions.reservations(), client.page("html/bbsp/common/GetLanUserDevInfo.asp"), actions.lan())
            }
            body.removeAllViews()
            val devs = Custom.devices(devData)
            val names = devs.associate { it["MacAddr"].lowercase() to it["HostName"] }

            if (list.isEmpty()) body.addView(noteText("No fixed IPs yet."))
            else {
                body.addView(groupHeader("Current (${list.size}/16)"))
                body.addView(baseCard().apply {
                    addView(box().apply {
                        list.forEachIndexed { i, r ->
                            if (i > 0) addView(divider())
                            addView(actionRow(r.ip, "${names[r.mac.lowercase()]?.ifBlank { null } ?: "Device"} · ${r.mac}", "Remove") {
                                confirm("Remove ${r.ip}?", "That device will get any free IP next time.") {
                                    runChange("Removing...", { actions.deleteReservation(r.domain) }, done = { show("edit:static", push = false) })
                                }
                            })
                        }
                    })
                })
            }

            body.addView(groupHeader("Add"))
            val opts = devs.map { it["MacAddr"] to "${it["HostName"].ifBlank { "Device" }} (${it["IpAddr"]})" }
            val dev = dropField("Device", opts + ("" to "Other (type MAC below)"), opts.firstOrNull()?.first ?: "")
            val macF = textField("MAC (only for Other)", "", "AA:BB:CC:DD:EE:FF")
            val ipF = textField("IP to give", "", "Must be in ${RouterActions.network(lan.ip, lan.mask)}x")
            body.addView(formCard(null, dev, macF, ipF))
            body.addView(primaryButton("Add fixed IP") {
                val mac = dev.get().ifBlank { macF.get() }
                val ip = ipF.get().ifBlank { devs.firstOrNull { it["MacAddr"] == mac }?.get("IpAddr") ?: "" }
                if (!RouterActions.sameSubnet(ip, lan.ip, lan.mask)) {
                    Toast.makeText(this@MainActivity, "IP must be in ${RouterActions.network(lan.ip, lan.mask)}x", Toast.LENGTH_LONG).show()
                    return@primaryButton
                }
                runChange("Saving...", { actions.addReservation(mac, ip) }, done = { show("edit:static", push = false) })
            })
            statusText.text = "Always give a device the same IP"
        }
    }

    private fun editWan() {
        screenTitle.text = "Internet (WAN)"
        statusText.text = "PPPoE connection from your ISP"
        val body = box(); content.addView(body)
        load(body) {
            val w = withContext(Dispatchers.IO) { actions.wan() }
            body.removeAllViews()
            body.addView(warning("These come from your ISP. A wrong value (VLAN, username, password) = no internet " +
                "until you set it back. Note the current values before changing."))
            body.addView(cardView(Card("Now", listOf(
                Row("Connection", w.name), Row("Status", w.status),
                Row("Username", w.username), Row("VLAN", if (w.vlan == "0") "Off" else w.vlan), Row("MRU", w.mru)
            ), good = w.status == "Connected")))

            val en = switchField("Internet connection on", w.enable)
            val user = textField("PPPoE username", w.username)
            val pass = textField("PPPoE password", "", "Leave empty to keep the current password", password = true)
            body.addView(formCard("Login", en, user, pass))

            val vlan = textField("VLAN ID", w.vlan, "0 = off. Only change if your ISP says so", number = true)
            val pri = dropField("802.1p priority", (0..7).map { "$it" to "$it" }, w.pri)
            val mru = textField("MRU", w.mru, "Normal: 1492", number = true)
            val nat = switchField("NAT", w.nat, "Keep on for normal home internet")
            val natType = dropField("NAT type", listOf("0" to "Port-restricted cone (default)", "1" to "Full cone (better for games)"), w.natType)
            val lcp = switchField("LCP echo check", w.lcpCheck)
            val dial = dropField("Dial mode", listOf("AlwaysOn" to "Always on", "Manual" to "Manual"), w.trigger)
            body.addView(formCard("Connection", vlan, pri, mru, nat, natType, lcp, dial))

            val dnsOn = switchField("Use my own DNS", w.dnsOverride, "Off = use the ISP's DNS")
            val dns = textField("DNS servers", w.dnsServers.ifBlank { "" }, "e.g. 1.1.1.1,8.8.8.8")
            body.addView(formCard("DNS", dnsOn, dns))

            body.addView(primaryButton("Save") {
                val n = w.copy(enable = en.get() == "1", username = user.get(), vlan = vlan.get().ifBlank { "0" }, pri = pri.get(),
                    mru = mru.get(), nat = nat.get() == "1", natType = natType.get(), lcpCheck = lcp.get() == "1",
                    trigger = dial.get(), dnsOverride = dnsOn.get() == "1", dnsServers = dns.get())
                val pw = pass.get().ifBlank { null }
                confirm("Save internet settings?", "The internet will reconnect (about 30 s). If it doesn't come back, set the old values again.") {
                    runChange("Saving...", { actions.saveWan(n, pw) }, done = { show("waninfo") })
                }
            })
            statusText.text = "PPPoE connection from your ISP"
        }
    }

    private fun confirmReboot() {
        confirm("Reboot the router?", "Internet and WiFi go off for about 2 minutes. The app reconnects by itself.") {
            val dialog = AlertDialog.Builder(this).setTitle("Rebooting").setMessage("Sending reboot...").setCancelable(false).show()
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { actions.reboot() }
                    dialog.setMessage("Router is restarting... waiting for it to come back")
                    delay(15000)
                    var back = false
                    val until = System.currentTimeMillis() + 240_000
                    while (!back && System.currentTimeMillis() < until) {
                        back = withContext(Dispatchers.IO) { client.isReachable() }
                        if (!back) delay(4000)
                    }
                    dialog.dismiss()
                    if (back) {
                        Toast.makeText(this@MainActivity, "Router is back", Toast.LENGTH_LONG).show()
                        switchTab("home")
                    } else {
                        AlertDialog.Builder(this@MainActivity).setTitle("Still restarting")
                            .setMessage("The router isn't answering yet. Make sure the phone is back on its WiFi, then tap Refresh.")
                            .setPositiveButton("OK", null).show()
                    }
                } catch (e: Exception) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Reboot failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  MORE: tools + read-only router info                                 */
    /* ------------------------------------------------------------------ */

    private val infoGroups: List<String>
        get() = Sections.all.map { it.group }.distinct().filter { it != "Status" }

    private fun showMore() {
        screenTitle.text = "More"
        statusText.text = "Tools and full router information"
        content.addView(groupHeader("Tools"))
        content.addView(menuCard(
            Triple("Internet monitor", "Outages: when and how long") { show("internet") },
            Triple("Data usage", "Monthly data, per device") { show("usage") },
            Triple("Internet speed test", "Real speed vs your package") { show("speedtest") },
            Triple("Fibre signal history", "Rx power over time") { show("fibre") },
            Triple("One-click diagnosis", "Fibre, ISP registration, LAN ports") { show("diagnose") },
            Triple("All connected devices (details)", "Full list with DHCP lease times") { show("devices_info") }
        ))
        content.addView(groupHeader("Router information (view only)"))
        content.addView(baseCard().apply {
            addView(box().apply {
                infoGroups.forEachIndexed { i, g ->
                    if (i > 0) addView(divider())
                    val n = Sections.all.count { it.group == g }
                    addView(menuRow(g.removePrefix("Advanced: "), "$n page${if (n == 1) "" else "s"}") { show("group:$g") })
                }
            })
        })
    }

    private fun showGroup(group: String) {
        screenTitle.text = group.removePrefix("Advanced: ")
        statusText.text = "View only"
        content.addView(baseCard().apply {
            addView(box().apply {
                Sections.all.filter { it.group == group }.forEachIndexed { i, s ->
                    if (i > 0) addView(divider())
                    addView(menuRow(s.title, null) { show(s.id) })
                }
            })
        })
    }

    /* ------------------------------------------------------------------ */
    /*  One section screen                                                  */
    /* ------------------------------------------------------------------ */

    private fun showSection(section: Section) {
        screenTitle.text = section.title
        statusText.text = "Loading..."
        wifiSection.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()
        section.note?.let { content.addView(noteText(it)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body)
        body.addView(loadingText())

        loadJob = scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { client.loadSection(section) }
                val result = section.render(data)
                body.removeAllViews()

                if (section.editWifi) {
                    body.addView(primaryButton("Edit WiFi settings") { show("wifiEdit") })
                }
                result.cards.forEach { body.addView(cardView(it)) }

                if (result.errors.isNotEmpty()) {
                    body.addView(noteText("Some pages could not be read:\n" + result.errors.joinToString("\n")))
                }

                if (result.raw.isNotEmpty()) {
                    val rawBox = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }
                    lateinit var toggle: Button
                    toggle = textButton("Show all raw data (${result.raw.size})") {
                        val open = rawBox.visibility == View.VISIBLE
                        rawBox.visibility = if (open) View.GONE else View.VISIBLE
                        toggle.text = if (open) "Show all raw data (${result.raw.size})" else "Hide raw data"
                    }
                    body.addView(toggle)
                    result.raw.forEach { rawBox.addView(cardView(it, small = true)) }
                    body.addView(rawBox)
                }
                statusText.text = section.group
            } catch (e: Exception) {
                body.removeAllViews()
                body.addView(errorCard(e))
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  WiFi edit (the original screen)                                     */
    /* ------------------------------------------------------------------ */

    private fun showWifiEdit() {
        screenTitle.text = "Edit WiFi"
        statusText.text = "Loading current WiFi settings..."
        content.visibility = View.GONE
        wifiSection.visibility = View.GONE

        loadJob = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { client.getWifiInfo() }
                currentInfo = info
                ssidInput.setText(info.ssid)
                newPasswordInput.setText("")
                wifiEnabledSwitch.isChecked = info.wifiEnabled
                broadcastSwitch.isChecked = info.broadcastEnabled
                wmmSwitch.isChecked = info.wmmEnabled
                wpsSwitch.isChecked = info.wpsEnabled
                maxDevicesInput.setText(info.maxDevices.toString())
                securityModeDropdown.setText(info.securityMode.label, false)
                statusText.text = "Main 2.4G WiFi - ${info.ssid}"
                wifiSection.visibility = View.VISIBLE
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
                Toast.makeText(this@MainActivity, "Could not read WiFi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveWifi() {
        val info = currentInfo ?: return
        val newSsid = ssidInput.text.toString().trim()
        val newPass = newPasswordInput.text.toString().ifBlank { null }
        val maxDevices = maxDevicesInput.text.toString().toIntOrNull() ?: info.maxDevices
        val selectedMode = securityModes.firstOrNull { it.label == securityModeDropdown.text.toString() }
            ?: info.securityMode

        if (newSsid.isEmpty()) {
            Toast.makeText(this, "SSID can't be empty", Toast.LENGTH_SHORT).show()
            return
        }
        if (newPass != null && newPass.length < 8) {
            Toast.makeText(this, "WiFi password must be at least 8 characters", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "Saving..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.saveWifi(
                        info = info,
                        newSsid = newSsid,
                        newPassword = newPass,
                        broadcastEnabled = broadcastSwitch.isChecked,
                        wmmEnabled = wmmSwitch.isChecked,
                        wpsEnabled = wpsSwitch.isChecked,
                        maxDevices = maxDevices,
                        securityMode = selectedMode
                    )
                    if (wifiEnabledSwitch.isChecked != info.wifiEnabled) {
                        client.setWifiEnabled(info, wifiEnabledSwitch.isChecked)
                    }
                }
                statusText.text = "Saved! Devices on this WiFi will disconnect and need to reconnect."
                Toast.makeText(this@MainActivity, "WiFi settings saved", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Small view builders                                                 */
    /* ------------------------------------------------------------------ */

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun baseCard(): MaterialCardView = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        radius = dp(16).toFloat()
        cardElevation = dp(2).toFloat()
        setCardBackgroundColor(color(R.color.cardBackground))
    }

    private fun cardView(card: Card, small: Boolean = false): View {
        val view = baseCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (card.good != null) {
            titleRow.addView(TextView(this).apply {
                text = "● "
                setTextColor(color(if (card.good) R.color.good else R.color.bad))
                textSize = 14f
            })
        }
        titleRow.addView(TextView(this).apply {
            text = card.title
            setTextColor(color(R.color.textPrimary))
            textSize = if (small) 13f else 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (card.link != null) {
            titleRow.addView(TextView(this).apply {
                text = "›"
                textSize = 22f
                setTextColor(color(R.color.textSecondary))
            })
        }
        box.addView(titleRow)

        card.big?.let {
            box.addView(TextView(this).apply {
                text = it
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(when (card.good) { true -> R.color.good; false -> R.color.bad; null -> R.color.textPrimary }))
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        card.note?.let {
            box.addView(TextView(this).apply {
                text = it
                textSize = 13f
                setTextColor(color(R.color.textSecondary))
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        card.rows.forEach { row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(if (small) 3 else 6), 0, dp(if (small) 3 else 6))
            }
            line.addView(TextView(this).apply {
                text = row.label
                textSize = if (small) 12f else 14f
                setTextColor(color(R.color.textSecondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, dp(10), 0)
            })
            line.addView(TextView(this).apply {
                text = row.value
                textSize = if (small) 12f else 14f
                setTextColor(color(R.color.textPrimary))
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            })
            box.addView(line)
        }

        view.addView(box)
        card.link?.let { id -> view.isClickable = true; view.setOnClickListener { show(id) } }
        return view
    }

    private fun groupHeader(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12f
        letterSpacing = 0.06f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.textSecondary))
        setPadding(dp(4), dp(22), 0, 0)
    }

    private fun menuRow(title: String, sub: String? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(if (sub != null) 11 else 14), dp(18), dp(if (sub != null) 11 else 14))
        isClickable = true
        isFocusable = true
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = attrs.getDrawable(0)
        attrs.recycle()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(color(R.color.textPrimary)) })
            if (sub != null) addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(color(R.color.textSecondary)) })
        })
        addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(color(R.color.textSecondary))
        })
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(18); marginEnd = dp(18)
        }
        setBackgroundColor(color(R.color.divider))
    }

    private fun loadingText() = noteText("Loading from router...")

    private fun noteText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.textSecondary))
        setPadding(dp(4), dp(8), dp(4), dp(4))
    }

    private fun errorCard(e: Exception): View {
        val msg = e.message ?: e.javaClass.simpleName
        val v = cardView(Card("Could not load", note = msg, good = false))
        if (msg.contains("session expired")) {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(v)
                addView(primaryButton("Log in again") { logout() })
            }
        }
        return v
    }

    private fun primaryButton(text: String, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.primary))
            setTextColor(color(android.R.color.white))
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }

    private fun textButton(text: String, onClick: () -> Unit) =
        Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            setTextColor(color(R.color.primary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /* ------------------------------------------------------------------ */
    /*  Internet monitor + data usage                                       */
    /* ------------------------------------------------------------------ */

    private var liveJob: Job? = null
    private val timeFmt get() = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
    private val dayFmt get() = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
    private val monthFmt get() = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US)

    private fun dur(ms: Long): String {
        val m = ms / 60_000
        return when { m < 1 -> "<1 min"; m < 60 -> "$m min"; else -> "${m / 60} h ${m % 60} min" }
    }

    private fun ms(v: Long?) = v?.let { "$it ms" } ?: "--"

    /** Fills [box] with the live internet status card. */
    private fun renderLive(box: LinearLayout, l: Monitor.Live) {
        box.removeAllViews()
        if (!Monitor.isEnabled(this)) {
            box.addView(cardView(Card("Internet", note = "Internet monitor is off. Turn it on in Internet monitor.", link = "internet")))
            return
        }
        val card = when (l.status) {
            Monitor.Status.STARTING -> Card("Internet", note = "Checking...", link = "internet")
            Monitor.Status.AWAY -> Card("Internet", note = "Monitor paused: ${l.reason}", link = "internet")
            Monitor.Status.ONLINE -> Card("Internet  ·  Online", good = true, link = "internet", rows = listOf(
                Row("Google DNS", ms(l.googleMs)),
                Row("Cloudflare", ms(l.cloudMs)),
                Row("Your ISP", if (l.ispLearned) ms(l.ispMs) else "--")
            ))
            Monitor.Status.DOWN -> Card("Internet connection problem", good = false, link = "internet",
                note = l.reason.ifBlank { null },
                rows = listOf(
                    Row("Router", if (l.routerMs != null) "Online" else "Offline"),
                    Row("ISP", when { l.kind == OutageStore.Kind.INTERNET -> "Online"; l.ispLearned -> "Unreachable"; else -> "Unreachable?" }),
                    Row("Internet", "Offline"),
                    Row("Started", timeFmt.format(java.util.Date(l.since))),
                    Row("Duration", dur(System.currentTimeMillis() - l.since))
                ))
        }
        box.addView(cardView(card))
        if (l.status == Monitor.Status.DOWN && l.routerMs != null) {
            box.addView(textButton("Send complaint to ISP") {
                reportToIsp(OutageStore.Outage(l.since, 0, l.kind ?: OutageStore.Kind.ISP, l.reason))
            })
        }
    }

    /** Keeps [box] updated with the monitor's live status while the screen is open. */
    private fun watchLive(box: LinearLayout, homeOnly: Boolean = false) {
        liveJob?.cancel()
        liveJob = scope.launch {
            Monitor.live.collect {
                if (homeOnly && (it.status == Monitor.Status.ONLINE || it.status == Monitor.Status.STARTING || !Monitor.isEnabled(this@MainActivity))) box.removeAllViews()
                else renderLive(box, it)
            }
        }
    }

    /**
     * Opens the share sheet (WhatsApp, SMS, email...) with a ready complaint
     * message for the ISP: when it went down, for how long, the reason, the
     * fibre signal and the account (PPPoE) username.
     */
    private fun reportToIsp(o: OutageStore.Outage) {
        val full = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.US)
        val last = FibreStore.all(this).lastOrNull()
        val msg = buildString {
            append("Hello, I want to report an internet problem.\n\n")
            PlanStore.account(this@MainActivity)?.takeIf { it.isNotBlank() }?.let { append("Account (PPPoE username): $it\n") }
            append("Down since: ${full.format(java.util.Date(o.start))}\n")
            if (o.end > 0) append("Back at: ${full.format(java.util.Date(o.end))}\n")
            append("${if (o.end > 0) "Total time down" else "Down for"}: ${dur(o.durationMs)}\n")
            append("Problem: ${o.kind.label}\n")
            if (o.reason.isNotBlank()) append("Detail: ${o.reason}\n")
            if (last != null && System.currentTimeMillis() - last.at < 6 * 3_600_000L) {
                append("Fibre signal (Rx): %.2f dBm (normal range -8 to -27)\n".format(last.rx))
            }
            val month = OutageStore.all(this@MainActivity).filter {
                it.kind != OutageStore.Kind.UNSEEN && System.currentTimeMillis() - it.start < 30L * 86_400_000L
            }
            if (month.size > 1) append("Outages in the last 30 days: ${month.size} (total ${dur(month.sumOf { it.durationMs })})\n")
            append("\nMy router is on and working. Please check the line / your side and fix it. Thank you.")
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Internet outage complaint")
            .putExtra(Intent.EXTRA_TEXT, msg)
        startActivity(Intent.createChooser(send, "Send complaint with"))
    }

    private fun usageTiles(): View {
        val cur = UsageStore.current(this)
        val daysLeft = ((cur.end - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
        val monthStart = cur.start
        val outs = OutageStore.all(this).filter { it.start >= monthStart && it.kind != OutageStore.Kind.UNSEEN }
        return tileRow(
            tile("Data this cycle", UsageStore.fmt(cur.total), "$daysLeft day${if (daysLeft == 1L) "" else "s"} left", null) { show("usage") },
            tile("Outages", "${outs.size}", if (outs.isEmpty()) "None this cycle" else dur(outs.sumOf { it.durationMs }) + " in total",
                if (outs.isEmpty()) true else null) { show("internet") }
        )
    }

    private fun showInternet() {
        screenTitle.text = "Internet monitor"
        statusText.text = "When the internet went down, and for how long"

        val liveBox = box()
        content.addView(liveBox)
        watchLive(liveBox)

        // controls
        val sw = SwitchMaterial(this).apply { isChecked = Monitor.isEnabled(this@MainActivity) }
        sw.setOnCheckedChangeListener { _, on -> Monitor.setEnabled(this, on); renderLive(liveBox, Monitor.live.value) }
        content.addView(baseCard().apply {
            addView(box().apply {
                addView(controlRow("Background monitor", "Checks every 15 seconds, only while this phone is on the home WiFi.", sw))
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    addView(divider())
                    addView(actionRow("Battery saver", "The phone may stop the monitor in the background", "Allow") {
                        try {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    })
                }
                addView(divider())
                addView(menuRow("Fibre signal history", "Rx power over the last 90 days") { show("fibre") })
            })
        })

        // history by month
        val all = OutageStore.all(this)
        if (all.isEmpty()) {
            content.addView(noteText("No outages recorded yet."))
            return
        }
        all.groupBy { monthFmt.format(java.util.Date(it.start)) }.forEach { (month, list) ->
            val real = list.filter { it.kind != OutageStore.Kind.UNSEEN }
            content.addView(groupHeader("$month  ·  ${real.size} outage${if (real.size == 1) "" else "s"}  ·  ${dur(real.sumOf { it.durationMs })}"))
            content.addView(baseCard().apply {
                addView(box().apply {
                    list.forEachIndexed { i, o ->
                        if (i > 0) addView(divider())
                        val title = "${dayFmt.format(java.util.Date(o.start))}  ·  ${timeFmt.format(java.util.Date(o.start))}"
                        val right = if (o.kind == OutageStore.Kind.UNSEEN) "reconnect" else (if (o.approx) "~" else "") + dur(o.durationMs)
                        addView(menuRow("$title      $right", o.kind.label + (if (o.reason.isNotBlank() && o.kind != OutageStore.Kind.UNSEEN) "\n${o.reason}" else "")) {
                            val msg = buildString {
                                append("Started: ${dayFmt.format(java.util.Date(o.start))}, ${timeFmt.format(java.util.Date(o.start))}\n")
                                if (o.end > 0 && o.kind != OutageStore.Kind.UNSEEN) append("Ended: ${timeFmt.format(java.util.Date(o.end))}\nDuration: ${dur(o.durationMs)}\n")
                                append("Cause: ${o.kind.label}\n")
                                if (o.reason.isNotBlank()) append("${o.reason}\n")
                                if (o.approx) append("\nThese times are approximate - the app was not checking at that moment.")
                            }
                            val dlg = AlertDialog.Builder(this@MainActivity).setTitle("Outage").setMessage(msg)
                                .setPositiveButton("Close", null)
                                .setNegativeButton("Delete") { _, _ -> OutageStore.remove(this@MainActivity, o); show("internet", push = false) }
                            if (o.kind == OutageStore.Kind.ISP || o.kind == OutageStore.Kind.INTERNET) {
                                dlg.setNeutralButton("Report to ISP") { _, _ -> reportToIsp(o) }
                            }
                            dlg.show()
                        })
                    }
                })
            })
        }
        content.addView(textButton("Clear all outage history") {
            confirm("Clear history?", "All outage records will be deleted.") {
                OutageStore.clear(this); show("internet", push = false)
            }
        })
    }

    /* ------------------------------------------------------------------ */
    /*  Fibre signal history                                                */
    /* ------------------------------------------------------------------ */

    private fun showFibreHistory() {
        screenTitle.text = "Fibre signal"
        statusText.text = "Optical receive power (Rx), saved every 15 minutes"
        val days = FibreStore.daily(this)
        val samples = FibreStore.all(this)
        val last = samples.lastOrNull()

        if (last != null) {
            val ok = last.rx in -27.0..-8.0
            content.addView(cardView(Card(
                "Latest reading", big = "%.2f dBm".format(last.rx), good = ok,
                note = if (ok) "Healthy. Normal range is -8 to -27 dBm (closer to -8 is stronger)."
                       else if (last.rx < -27) "Weak. Below -27 dBm the internet can drop. Usually a dirty/bent fibre cable or a fault on the ISP side."
                       else "Too strong (above -8 dBm). Unusual - tell the ISP.",
                rows = listOfNotNull(
                    Row("Time", java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.US).format(java.util.Date(last.at))),
                    last.tx?.let { Row("Transmit (Tx)", "%.2f dBm".format(it)) },
                    last.tempC?.let { Row("Module temperature", "%.0f °C".format(it)) }
                )
            )))
        }

        if (days.isEmpty()) {
            content.addView(noteText("No history yet. The background monitor saves a reading every 15 minutes while this phone is on the home WiFi."))
            return
        }

        content.addView(groupHeader("Daily average (last ${days.size} day${if (days.size == 1) "" else "s"})"))
        content.addView(baseCard().apply {
            addView(box().apply {
                setPadding(dp(10), dp(14), dp(10), dp(10))
                addView(FibreChart(context).apply { this.days = days })
                addView(TextView(context).apply {
                    text = "Line = daily average  ·  shaded = weakest to strongest that day  ·  green = healthy range"
                    textSize = 11.5f; setTextColor(color(R.color.textSecondary)); setPadding(dp(8), dp(6), dp(8), 0)
                })
            })
        })

        // trend: last 7 days vs the 7 before
        if (days.size >= 8) {
            val recent = days.takeLast(7).map { it.avgRx }.average()
            val before = days.dropLast(7).takeLast(7).map { it.avgRx }.average()
            val diff = recent - before
            content.addView(cardView(Card("Trend", rows = listOf(
                Row("Last 7 days", "%.2f dBm".format(recent)),
                Row("7 days before", "%.2f dBm".format(before)),
                Row("Change", "%+.2f dB".format(diff))
            ), note = when {
                diff <= -1.5 -> "Signal is getting weaker. If it keeps dropping, ask the ISP to check the fibre line."
                diff >= 1.5 -> "Signal got stronger (maybe the ISP fixed something)."
                else -> "Stable."
            }, good = if (diff <= -1.5) false else null)))
        }

        val worst = samples.minByOrNull { it.rx }
        if (worst != null) {
            content.addView(cardView(Card("Range", rows = listOf(
                Row("Weakest", "%.2f dBm  (%s)".format(worst.rx, java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.US).format(java.util.Date(worst.at)))),
                Row("Strongest", "%.2f dBm".format(samples.maxOf { it.rx })),
                Row("Readings saved", "${samples.size}")
            ))))
        }
    }

    private fun showUsage() {
        screenTitle.text = "Data usage"
        statusText.text = "Data used in the current package cycle"
        val cur = UsageStore.current(this)
        val now = System.currentTimeMillis()
        val daysLeft = ((cur.end - now) / 86_400_000L).coerceAtLeast(0)
        val full = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.US)

        content.addView(cardView(Card(
            "${dayFmt.format(java.util.Date(cur.start))} – ${dayFmt.format(java.util.Date(cur.end))}",
            big = UsageStore.fmt(cur.total),
            rows = listOf(
                Row("Download", UsageStore.fmt(cur.totalDown)),
                Row("Upload", UsageStore.fmt(cur.totalUp)),
                Row("Package renews", "${full.format(java.util.Date(cur.end))}  ($daysLeft day${if (daysLeft == 1L) "" else "s"})")
            )
        )))

        val tpState = Monitor.live.value.tpState
        content.addView(cardView(Card("Where it was used", rows = listOf(
            Row("Huawei WiFi (all devices)", UsageStore.fmt(cur.huaweiDown + cur.huaweiUp)),
            Row("TP-Link devices", UsageStore.fmt(cur.tpDown + cur.tpUp))
        ), note = "Huawei comes from the router's own counter (exact). TP-Link adds up each device's live speed (estimate), " +
            "and only counts while this phone is on the TP-Link WiFi." + if (tpState.isNotBlank() && !tpState.startsWith("OK")) "\n\nNow: $tpState" else "")))

        // per device
        val devs = cur.devices.filter { it.down + it.up > 0 }
        content.addView(groupHeader("Per device (TP-Link)"))
        if (devs.isEmpty()) {
            content.addView(noteText("No data yet. Enter the TP-Link password below, then keep this phone on the TP-Link WiFi."))
        } else {
            content.addView(baseCard().apply {
                addView(box().apply {
                    setPadding(0, dp(6), 0, dp(6))
                    val max = devs.maxOf { it.down + it.up }.coerceAtLeast(1)
                    devs.forEachIndexed { i, d ->
                        if (i > 0) addView(divider())
                        addView(usageRow(DeviceStore.display(this@MainActivity, d.mac, d.name), "↓ ${UsageStore.fmt(d.down)}   ↑ ${UsageStore.fmt(d.up)}",
                            UsageStore.fmt(d.down + d.up), (d.down + d.up).toFloat() / max))
                    }
                })
            })
        }

        // TP-Link setup
        val pass = textField("TP-Link admin password", Monitor.tpPassword(this).orEmpty(), password = true)
        val host = textField("TP-Link address", Monitor.tpHost(this))
        content.addView(groupHeader("TP-Link (view only)"))
        content.addView(formCard(null, host, pass))
        content.addView(primaryButton("Save TP-Link") {
            Monitor.setTpLink(this, host.get().ifBlank { "192.168.0.1" }, pass.get().ifBlank { null })
            Monitor.stop(this); Monitor.start(this)
            Toast.makeText(this, "Saved - data will start from the next check", Toast.LENGTH_SHORT).show()
        })

        // cycle settings
        content.addView(groupHeader("Package"))
        val day = UsageStore.cycleDay(this)
        content.addView(menuCard(
            Triple("Renews on day $day of each month", "At midnight on this day the cycle moves to history") {
                val days = (1..28).map { it.toString() }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Which day does the package renew?")
                    .setSingleChoiceItems(days, day - 1) { dlg, which ->
                        UsageStore.setCycleDay(this, which + 1); dlg.dismiss(); show("usage", push = false)
                    }.show()
            },
            Triple("Reset now", "Save the data so far to history, then start from zero") {
                confirm("Reset now?", "The data so far will be saved to history and counting will start from zero.") {
                    UsageStore.resetNow(this); show("usage", push = false)
                }
            }
        ))

        // history
        val hist = UsageStore.history(this)
        if (hist.isNotEmpty()) {
            content.addView(groupHeader("Previous cycles"))
            content.addView(baseCard().apply {
                addView(box().apply {
                    hist.forEachIndexed { i, h ->
                        if (i > 0) addView(divider())
                        val title = "${dayFmt.format(java.util.Date(h.start))} – ${dayFmt.format(java.util.Date(h.end))}${if (h.manual) " (reset)" else ""}"
                        addView(menuRow("$title      ${UsageStore.fmt(h.total)}", "↓ ${UsageStore.fmt(h.totalDown)}   ↑ ${UsageStore.fmt(h.totalUp)}") {
                            val msg = buildString {
                                append("Total: ${UsageStore.fmt(h.total)}\n")
                                append("Huawei WiFi: ${UsageStore.fmt(h.huaweiDown + h.huaweiUp)}\n")
                                append("TP-Link: ${UsageStore.fmt(h.tpDown + h.tpUp)}\n")
                                h.devices.sortedByDescending { it.down + it.up }.forEach { d ->
                                    append("\n${DeviceStore.display(this@MainActivity, d.mac, d.name)}: ${UsageStore.fmt(d.down + d.up)}")
                                }
                            }
                            AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(msg)
                                .setPositiveButton("Close", null)
                                .setNegativeButton("Delete") { _, _ ->
                                    confirm("Delete this cycle?", "It will be removed from history.") {
                                        UsageStore.deleteHistory(this@MainActivity, h); show("usage", push = false)
                                    }
                                }.show()
                        })
                    }
                })
            })
        }
    }

    private fun usageRow(title: String, sub: String, value: String, fraction: Float) = box().apply {
        setPadding(dp(18), dp(10), dp(18), dp(10))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                text = title; textSize = 15f; setTextColor(color(R.color.textPrimary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply { text = value; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.textPrimary)) })
        })
        addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(color(R.color.textSecondary)) })
        addView(LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color(R.color.divider)) }
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fraction.coerceIn(0.02f, 1f))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color(R.color.primary)) }
            })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (1f - fraction).coerceIn(0f, 0.98f))
            })
        })
    }

    /* ------------------------------------------------------------------ */
    /*  Internet speed test                                                 */
    /* ------------------------------------------------------------------ */

    private var speedTest: SpeedTest? = null

    /** WiFi link of this phone right now: (link speed Mbps, signal dBm), or null if not on WiFi. */
    private fun wifiLink(): Pair<Int, Int>? = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) != true) null
        else {
            @Suppress("DEPRECATION")
            val info = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
            if (info == null || info.linkSpeed <= 0) null else info.linkSpeed to info.rssi
        }
    } catch (_: Exception) { null }

    private fun fmtMbps(v: Double) = if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)

    private fun showSpeedTest() {
        screenTitle.text = "Speed Test"
        statusText.text = "Real speed you get vs the package you pay for"
        wifiSection.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()

        // live meter
        val meter = baseCard()
        val meterBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        val phase = TextView(this).apply {
            text = "Tap Start to test"
            textSize = 14f
            setTextColor(color(R.color.textSecondary))
            gravity = Gravity.CENTER
        }
        val bigNumber = TextView(this).apply {
            text = "--"
            textSize = 56f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.textPrimary))
            gravity = Gravity.CENTER
        }
        val unit = TextView(this).apply {
            text = "Mbps"
            textSize = 16f
            setTextColor(color(R.color.textSecondary))
            gravity = Gravity.CENTER
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(16) }
        }
        meterBox.addView(phase); meterBox.addView(bigNumber); meterBox.addView(unit); meterBox.addView(bar)
        meter.addView(meterBox)
        content.addView(meter)

        // results
        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resultsBox)

        lateinit var startBtn: Button
        startBtn = primaryButton("Start test") {
            if (speedTest != null) {
                speedTest?.stop()
                return@primaryButton
            }
            runSpeedTest(phase, bigNumber, unit, bar, resultsBox, startBtn)
        }
        content.addView(startBtn)

        // package speed
        val pd = PlanStore.downMbps(this); val pu = PlanStore.upMbps(this)
        content.addView(menuCard(
            Triple("Your package: " + (if (pd != null) "${fmtMbps(pd)} Mbps" + (pu?.let { " / ${fmtMbps(it)} up" } ?: "") else "not set"),
                "Set the speed you pay for, to compare with the real result") { editPlan() }
        ))

        content.addView(noteText("Tests this phone's connection using Cloudflare's speed servers (4 connections at once, like speedtest.net). " +
            "On WiFi the result can't be faster than the phone's WiFi link, so the app shows that too. Each test uses roughly 50-300 MB of data."))

        val historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyBox)
        renderSpeedHistory(historyBox)
    }

    private fun editPlan() {
        val down = textField("Download (Mbps)", PlanStore.downMbps(this)?.let { fmtMbps(it) } ?: "", hint = "e.g. 20", number = true)
        val up = textField("Upload (Mbps, optional)", PlanStore.upMbps(this)?.let { fmtMbps(it) } ?: "", number = true)
        val form = box().apply {
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(down.view); addView(up.view)
        }
        AlertDialog.Builder(this).setTitle("Package speed")
            .setMessage("The speed written on your ISP package / bill.")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                PlanStore.set(this, down.get().toDoubleOrNull(), up.get().toDoubleOrNull())
                show("speedtest", push = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Compares the measured speed with the package, and explains where the
     * limit is (the phone's WiFi link, weak signal, or the ISP line).
     */
    private fun packageCard(down: Double, up: Double?, link: Pair<Int, Int>?): View? {
        val plan = PlanStore.downMbps(this)
        val rows = ArrayList<Row>()
        val notes = ArrayList<String>()
        var good: Boolean? = null

        if (plan != null) {
            val pct = (down / plan * 100).toInt()
            rows += Row("Package (download)", "${fmtMbps(plan)} Mbps")
            rows += Row("You got", "%.1f Mbps  (%d%%)".format(down, pct))
            PlanStore.upMbps(this)?.let { pu -> up?.let { rows += Row("Upload", "%.1f of %s Mbps  (%d%%)".format(it, fmtMbps(pu), (it / pu * 100).toInt())) } }
            good = when { pct >= 80 -> true; pct >= 50 -> null; else -> false }
            notes += when {
                pct >= 80 -> "You are getting the speed you pay for."
                pct >= 50 -> "Below your package."
                else -> "Much lower than your package."
            }
        }
        if (link != null) {
            val (linkMbps, rssi) = link
            rows += Row("Phone WiFi link", "$linkMbps Mbps")
            rows += Row("WiFi signal", "$rssi dBm  (" + when {
                rssi >= -55 -> "excellent"; rssi >= -67 -> "good"; rssi >= -75 -> "weak"; else -> "very weak"
            } + ")")
            // real throughput over WiFi is usually about half of the link rate
            val wifiCap = linkMbps * 0.55
            val wifiLimited = rssi < -70 || (plan != null && wifiCap < plan * 0.9)
            if (plan != null && down < plan * 0.8) {
                notes += if (wifiLimited)
                    "The limit is most likely your WiFi, not the ISP: this phone's WiFi link can only carry about ${wifiCap.toInt()} Mbps" +
                        (if (rssi < -70) " because the signal is weak" else "") +
                        ". Test again close to the router (or from a laptop on a LAN cable) to see the real ISP speed."
                else
                    "Your WiFi is strong enough for this package, so the slow speed is most likely from the ISP side. " +
                        "Run a few tests at different times; if it stays low, show these results to the ISP."
            } else if (rssi < -70) {
                notes += "WiFi signal is weak here. Moving closer to the router will make it more stable."
            }
        } else if (plan != null && down < plan * 0.8) {
            notes += "This phone is not on WiFi, so this is the mobile-data speed, not your home internet."
        }
        if (plan == null) notes += "Set your package speed below to see if you get what you pay for."
        if (rows.isEmpty() && notes.isEmpty()) return null
        return cardView(Card("Package vs real speed", rows, good = good, note = notes.joinToString("\n\n")))
    }

    private fun runSpeedTest(phase: TextView, big: TextView, unit: TextView, bar: ProgressBar, resultsBox: LinearLayout, startBtn: Button) {
        val st = SpeedTest()
        speedTest = st
        startBtn.text = "Stop"
        resultsBox.removeAllViews()
        bar.progress = 0
        big.setTextColor(color(R.color.textPrimary))

        var meta: SpeedTest.Meta? = null
        var ping: SpeedTest.Ping? = null
        var down: Double? = null
        var up: Double? = null
        val link = wifiLink()

        fun showResults(done: Boolean) {
            resultsBox.removeAllViews()
            if (done && down != null) packageCard(down!!, up, link)?.let { resultsBox.addView(it) }
            val rows = ArrayList<Row>()
            rows += Row("Download", down?.let { "%.1f Mbps  (%s)".format(it, SpeedTest.rating(it)) } ?: "--")
            rows += Row("Upload", up?.let { "%.1f Mbps  (%s)".format(it, SpeedTest.rating(it)) } ?: "--")
            rows += Row("Ping", ping?.let { "%.0f ms".format(it.ms) } ?: "--")
            rows += Row("Jitter", ping?.let { "%.0f ms".format(it.jitterMs) } ?: "--")
            meta?.let {
                rows += Row("Provider (ISP)", it.isp)
                rows += Row("Your public IP", it.ip)
                rows += Row("Test server", it.server)
            }
            resultsBox.addView(cardView(Card(if (done) "Result" else "Testing...", rows, good = if (done) (down ?: 0.0) >= 8 else null)))
        }

        fun live(label: String, l: SpeedTest.Live) = runOnUiThread {
            if (speedTest !== st) return@runOnUiThread
            phase.text = label
            big.text = "%.1f".format(l.currentMbps)
            bar.progress = l.progress
        }

        showResults(false)
        loadJob = scope.launch {
            try {
                phase.text = "Finding server..."
                meta = withContext(Dispatchers.IO) { st.meta() }
                phase.text = "Measuring ping..."
                big.text = "--"
                unit.text = "ms"
                ping = withContext(Dispatchers.IO) { st.ping() }
                big.text = "%.0f".format(ping!!.ms)
                showResults(false)

                unit.text = "Mbps  ↓ download"
                down = withContext(Dispatchers.IO) { st.download(10, 4) { live("Testing download...", it) } }
                showResults(false)

                unit.text = "Mbps  ↑ upload"
                bar.progress = 0
                up = withContext(Dispatchers.IO) { st.upload(10, 4) { live("Testing upload...", it) } }

                phase.text = "Done"
                big.text = "%.1f".format(down!!)
                unit.text = "Mbps download  ·  %.1f upload".format(up!!)
                bar.progress = 100
                showResults(true)
                saveSpeedResult(down!!, up!!, ping!!.ms, link)
                (resultsBox.parent as? LinearLayout)?.let { parent ->
                    (parent.getChildAt(parent.childCount - 1) as? LinearLayout)?.let { renderSpeedHistory(it) }
                }
            } catch (e: Throwable) {
                if (speedTest === st) {
                    val stoppedByUser = e is InterruptedException || e is kotlinx.coroutines.CancellationException
                    phase.text = if (stoppedByUser) "Stopped" else "Test failed: ${e.message}"
                    if (!stoppedByUser) big.setTextColor(color(R.color.bad))
                    if (down != null || ping != null) showResults(false)
                }
            } finally {
                if (speedTest === st) {
                    speedTest = null
                    startBtn.text = "Start again"
                }
            }
        }
    }

    // last 10 results, stored on the phone
    private fun saveSpeedResult(down: Double, up: Double, pingMs: Double, link: Pair<Int, Int>?) {
        val prefs = getSharedPreferences("speedtest", MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("history", "[]"))
        val item = org.json.JSONObject()
            .put("t", System.currentTimeMillis()).put("d", down).put("u", up).put("p", pingMs)
        PlanStore.downMbps(this)?.let { item.put("plan", it) }
        link?.let { item.put("link", it.first).put("rssi", it.second) }
        val list = mutableListOf(item)
        for (i in 0 until minOf(arr.length(), 9)) list += arr.getJSONObject(i)
        prefs.edit().putString("history", org.json.JSONArray(list).toString()).apply()
    }

    private fun renderSpeedHistory(box: LinearLayout) {
        box.removeAllViews()
        val arr = org.json.JSONArray(getSharedPreferences("speedtest", MODE_PRIVATE).getString("history", "[]"))
        if (arr.length() == 0) return
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.UK)
        val rows = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val d = o.getDouble("d")
            val plan = o.optDouble("plan", Double.NaN)
            val pct = if (!plan.isNaN() && plan > 0) "  ·  ${(d / plan * 100).toInt()}%" else ""
            val wifi = if (o.has("link")) "\nWiFi link ${o.getInt("link")} Mbps, ${o.getInt("rssi")} dBm" else ""
            Row(fmt.format(java.util.Date(o.getLong("t"))),
                "↓ %.1f  ↑ %.1f Mbps  ·  %.0f ms%s%s".format(d, o.getDouble("u"), o.getDouble("p"), pct, wifi))
        }
        box.addView(cardView(Card("Previous tests", rows)))
    }

    /* ------------------------------------------------------------------ */
    /*  In-app updates (from GitHub Releases)                               */
    /* ------------------------------------------------------------------ */

    private var lastUpdateCheck = 0L
    private var updateDialogShowing = false
    private var pendingApk: File? = null

    private fun installedVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun installedVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    private fun setupVersionText() {
        val v = findViewById<TextView>(R.id.versionText)
        v.text = "Version ${installedVersionName()}  ·  Check for updates"
        v.setOnClickListener { checkForUpdate(manual = true) }
    }

    /** Runs when the app opens / comes back to the front (at most every 10 min), or when tapped. */
    private fun checkForUpdate(manual: Boolean = false) {
        if (updateDialogShowing) return
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheck < 10 * 60 * 1000) return
        lastUpdateCheck = now
        if (manual) Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()

        scope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate(installedVersionCode()) }
            if (update == null) {
                if (manual) Toast.makeText(this@MainActivity, "You're on the latest version (${installedVersionName()})", Toast.LENGTH_LONG).show()
                return@launch
            }
            showUpdateDialog(update)
        }
    }

    private fun showUpdateDialog(update: UpdateChecker.UpdateInfo) {
        if (isFinishing || updateDialogShowing) return
        updateDialogShowing = true
        val size = if (update.sizeBytes > 0) " (${Fmt.bytes(update.sizeBytes)})" else ""
        val msg = buildString {
            append("Version ${update.versionName} is available$size.\n")
            append("You have ${installedVersionName()}.")
            if (update.notes.isNotBlank()) append("\n\nWhat's new:\n${update.notes.take(500)}")
        }
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(msg)
            .setPositiveButton("Update now") { _, _ -> downloadAndInstall(update) }
            .setNegativeButton("Later", null)
            .setOnDismissListener { updateDialogShowing = false }
            .show()
    }

    private fun downloadAndInstall(update: UpdateChecker.UpdateInfo) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(this).apply {
            text = "Starting download..."
            setTextColor(color(R.color.textSecondary))
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar)
            addView(label)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading ${update.versionName}")
            .setView(box)
            .setCancelable(false)
            .show()

        val file = File(File(cacheDir, "updates"), "RouterManager-update.apk")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UpdateChecker.download(update.downloadUrl, file) { pct ->
                        runOnUiThread {
                            if (pct >= 0) {
                                bar.isIndeterminate = false
                                bar.progress = pct
                                label.text = "$pct%"
                            } else label.text = "Downloading..."
                        }
                    }
                }
                dialog.dismiss()
                installApk(file)
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update failed")
                    .setMessage("${e.message}\n\nYou can also download it from the GitHub Releases page.")
                    .setPositiveButton("Open GitHub") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun installApk(file: File) {
        // Android 8+: the user must allow this app to install apps (asked once)
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingApk = file
            AlertDialog.Builder(this)
                .setTitle("Allow updates")
                .setMessage("To install the update, allow \"Install unknown apps\" for this app on the next screen, then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel") { _, _ -> pendingApk = null }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // came back from the "Install unknown apps" screen -> continue the install
        val apk = pendingApk
        if (apk != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingApk = null
            installApk(apk)
            return
        }
        checkForUpdate()
        // restart the live graph when coming back to the Home screen
        val hero = heroView
        if (loggedIn && stack.lastOrNull() == "home" && hero != null && pollJob?.isActive != true) startTrafficPolling(hero)
    }
}
