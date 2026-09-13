package com.cpagency.wifimanager

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var client: RouterClient
    private var currentInfo: RouterClient.WifiInfo? = null
    private val securityModes = RouterClient.SecurityMode.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val routerIpInput = findViewById<TextInputEditText>(R.id.routerIpInput)
        val usernameInput = findViewById<TextInputEditText>(R.id.usernameInput)
        val passwordInput = findViewById<TextInputEditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton)

        val loginSection = findViewById<android.view.View>(R.id.loginSection)
        val wifiSection = findViewById<android.view.View>(R.id.wifiSection)

        val ssidInput = findViewById<TextInputEditText>(R.id.ssidInput)
        val newPasswordInput = findViewById<TextInputEditText>(R.id.newPasswordInput)
        val wifiEnabledSwitch = findViewById<SwitchMaterial>(R.id.wifiEnabledSwitch)
        val broadcastSwitch = findViewById<SwitchMaterial>(R.id.broadcastSwitch)
        val wmmSwitch = findViewById<SwitchMaterial>(R.id.wmmSwitch)
        val wpsSwitch = findViewById<SwitchMaterial>(R.id.wpsSwitch)
        val securityModeDropdown = findViewById<AutoCompleteTextView>(R.id.securityModeDropdown)
        val maxDevicesInput = findViewById<TextInputEditText>(R.id.maxDevicesInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        securityModeDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, securityModes.map { it.label })
        )

        checkForUpdate()

        loginButton.setOnClickListener {
            val ip = routerIpInput.text.toString().trim()
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString()

            client = RouterClient("http://$ip")
            statusText.text = "Logging in..."

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    withContext(Dispatchers.IO) { client.login(user, pass) }
                    val info = withContext(Dispatchers.IO) { client.getWifiInfo() }
                    currentInfo = info

                    ssidInput.setText(info.ssid)
                    wifiEnabledSwitch.isChecked = info.wifiEnabled
                    broadcastSwitch.isChecked = info.broadcastEnabled
                    wmmSwitch.isChecked = info.wmmEnabled
                    wpsSwitch.isChecked = info.wpsEnabled
                    maxDevicesInput.setText(info.maxDevices.toString())
                    securityModeDropdown.setText(info.securityMode.label, false)

                    statusText.text = "Connected \u2013 ${info.ssid}"
                    loginSection.visibility = android.view.View.GONE
                    wifiSection.visibility = android.view.View.VISIBLE
                } catch (e: Exception) {
                    statusText.text = "Error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Login/read failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        saveButton.setOnClickListener {
            val info = currentInfo ?: return@setOnClickListener
            val newSsid = ssidInput.text.toString().trim()
            val newPass = newPasswordInput.text.toString().ifBlank { null }
            val maxDevices = maxDevicesInput.text.toString().toIntOrNull() ?: info.maxDevices
            val selectedMode = securityModes.firstOrNull { it.label == securityModeDropdown.text.toString() }
                ?: info.securityMode

            if (newSsid.isEmpty()) {
                Toast.makeText(this, "SSID can't be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            statusText.text = "Saving..."
            CoroutineScope(Dispatchers.Main).launch {
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
    }

    private fun checkForUpdate() {
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        CoroutineScope(Dispatchers.Main).launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate(currentVersion) }
            if (update != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage("Version ${update.versionName} is available. Download it now?")
                    .setPositiveButton("Update") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show()
            }
        }
    }
}
