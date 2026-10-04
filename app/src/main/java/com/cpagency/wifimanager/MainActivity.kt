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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    prefs.edit().putString("ip", ip).putString("user", user).putString("pass", pass).apply()
                    loggedIn = true
                    bottomNav.visibility = View.VISIBLE
                    switchTab("home")
                } catch (e: Exception) {
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    loginButton.isEnabled = true
                }
            }
        }

        saveButton.setOnClickListener { saveWifi() }

        // speed test works without logging in to the router
        findViewById<Button>(R.id.speedTestButton).setOnClickListener {
            stack.clear()
            show("speedtest")
        }
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

    private fun logout() {
        loadJob?.cancel()
        pollJob?.cancel()
        speedTest?.stop()
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

            val wan = Custom.internetWan(data)
            val up = wan?.get("ConnectionStatus") == "Connected"
            val devs = Custom.devices(data)
            val online = devs.filter { it["DevStatus"].equals("Online", true) }
            val wifiN = online.count { it["PortType"] == "WIFI" }
            val cableN = online.size - wifiN
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
            body.addView(tileRow(
                tile("Fibre signal", if (rxVal != null) "$rx dBm" else "--",
                    when { rxVal == null -> "--"; rxVal in -27.0..-8.0 -> "Good"; else -> "Weak - check cable" },
                    rxVal?.let { it in -27.0..-8.0 }) { show("opticinfo") },
                tile("Devices", "${online.size} online", "$wifiN WiFi  ·  $cableN cable", null) { switchTab("devices") }
            ))
            body.addView(tileRow(
                tile("WiFi", ssid?.get("ssid") ?: "--", if (ssid?.get("enable") == "1") "On  ·  2.4 GHz" else "Off", null) { show("wlaninfo") },
                tile("Health", if (up && rxVal?.let { it in -27.0..-8.0 } == true) "All good" else "Check",
                    "Tap for diagnosis", if (up && rxVal?.let { it in -27.0..-8.0 } == true) true else null) { show("diagnose") }
            ))

            body.addView(groupHeader("Quick actions"))
            body.addView(menuCard(
                Triple("Change WiFi name or password", ssid?.get("ssid") ?: "") { show("wifiEdit") },
                Triple("Internet speed test", "Real download / upload speed") { show("speedtest") },
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
            statusText.text = "${online.size} online · tap a device for controls"
        }
    }

    private fun deviceList(list: List<HwRecord>, mine: Set<String>, blocked: (String) -> Boolean): View {
        val card = baseCard()
        val b = box()
        list.forEachIndexed { i, d ->
            if (i > 0) b.addView(divider())
            val mac = d["MacAddr"]
            val name = d["HostName"].ifBlank { d["DevType"].ifBlank { "Unknown device" } }
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
            val name = dev["HostName"].ifBlank { dev["DevType"].ifBlank { "Unknown device" } }
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

            card.addView(b)
            body.addView(card)
            statusText.text = mac
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
        content.addView(groupHeader("System"))
        content.addView(menuCard(
            Triple("Reboot router", "Restart the router (about 2 min)") { confirmReboot() },
            Triple("Log out", "Back to the login screen") { logout() }
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
            Triple("Internet speed test", "Real download / upload speed") { show("speedtest") },
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
    /*  Internet speed test                                                 */
    /* ------------------------------------------------------------------ */

    private var speedTest: SpeedTest? = null

    private fun showSpeedTest() {
        screenTitle.text = "Speed Test"
        statusText.text = "Real download / upload speed of this phone's internet"
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

        content.addView(noteText("Tests this phone's connection using Cloudflare's speed servers (4 connections at once, like speedtest.net). On WiFi the result also depends on WiFi signal. Each test uses roughly 50-300 MB of data, so careful on mobile data."))

        val historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyBox)
        renderSpeedHistory(historyBox)
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

        fun showResults(done: Boolean) {
            resultsBox.removeAllViews()
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
                saveSpeedResult(down!!, up!!, ping!!.ms)
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
    private fun saveSpeedResult(down: Double, up: Double, pingMs: Double) {
        val prefs = getSharedPreferences("speedtest", MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("history", "[]"))
        val item = org.json.JSONObject()
            .put("t", System.currentTimeMillis()).put("d", down).put("u", up).put("p", pingMs)
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
            Row(fmt.format(java.util.Date(o.getLong("t"))),
                "↓ %.1f  ↑ %.1f Mbps  ·  %.0f ms".format(o.getDouble("d"), o.getDouble("u"), o.getDouble("p")))
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
