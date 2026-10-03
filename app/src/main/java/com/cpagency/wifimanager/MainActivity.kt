package com.cpagency.wifimanager

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var client: RouterClient
    private var currentInfo: RouterClient.WifiInfo? = null
    private val securityModes = RouterClient.SecurityMode.values()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    /** Screen history: "home", a section id, or "wifiEdit". Empty = login screen. */
    private val stack = ArrayList<String>()

    // views
    private lateinit var scrollRoot: NestedScrollView
    private lateinit var navRow: View
    private lateinit var backButton: Button
    private lateinit var screenTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var loginSection: View
    private lateinit var wifiSection: View
    private lateinit var content: LinearLayout

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

        setupVersionText()

        backButton.setOnClickListener { goBack() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { stack.lastOrNull()?.let { show(it, push = false) } }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { logout() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
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
                    stack.clear()
                    show("home")
                } catch (e: Exception) {
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    loginButton.isEnabled = true
                }
            }
        }

        saveButton.setOnClickListener { saveWifi() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /*  Navigation                                                          */
    /* ------------------------------------------------------------------ */

    private fun show(screen: String, push: Boolean = true) {
        if (push && stack.lastOrNull() != screen) stack.add(screen)
        loadJob?.cancel()

        loginSection.visibility = View.GONE
        navRow.visibility = View.VISIBLE
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
        scrollRoot.scrollTo(0, 0)

        when (screen) {
            "home" -> showHome()
            "wifiEdit" -> showWifiEdit()
            else -> Sections.byId(screen)?.let { showSection(it) }
        }
    }

    private fun goBack() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        show(stack.last(), push = false)
    }

    private fun logout() {
        loadJob?.cancel()
        stack.clear()
        navRow.visibility = View.GONE
        content.visibility = View.GONE
        wifiSection.visibility = View.GONE
        loginSection.visibility = View.VISIBLE
        screenTitle.text = "Router Manager"
        statusText.text = "Logged out"
    }

    /* ------------------------------------------------------------------ */
    /*  Home dashboard + menu                                               */
    /* ------------------------------------------------------------------ */

    private fun showHome() {
        screenTitle.text = "Router Manager"
        wifiSection.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()
        statusText.text = "Loading dashboard..."

        val dashboard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(dashboard)
        dashboard.addView(loadingText())
        addMenu()

        loadJob = scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { client.loadSection(Sections.home) }
                val result = Sections.home.render(data)
                dashboard.removeAllViews()
                result.cards.forEach { dashboard.addView(cardView(it)) }
                statusText.text = "Connected to ${client.baseUrlHost()}"
            } catch (e: Exception) {
                dashboard.removeAllViews()
                dashboard.addView(errorCard(e))
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    private fun addMenu() {
        Sections.all.filter { it.id != "home" }.groupBy { it.group }.forEach { (group, sections) ->
            content.addView(groupHeader(group))
            val card = baseCard()
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sections.forEachIndexed { i, s ->
                if (i > 0) list.addView(divider())
                list.addView(menuRow(s.title) { show(s.id) })
            }
            card.addView(list)
            content.addView(card)
        }
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

    private fun menuRow(title: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        isFocusable = true
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = attrs.getDrawable(0)
        attrs.recycle()
        addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(color(R.color.textPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
        Button(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
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
    }
}
