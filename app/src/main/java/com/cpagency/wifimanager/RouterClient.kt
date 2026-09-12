package com.cpagency.wifimanager

import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Talks to the Huawei HG8546M router's web admin panel (same endpoints the
 * browser-based admin UI uses: login.cgi, WlanBasic.asp, set.cgi).
 *
 * NOTE: this targets the WPA/WPA2-PSK + AES configuration found on this
 * router. If the router's auth mode is changed to Open/WEP/RADIUS, the save
 * step would need adjustments.
 */
class RouterClient(private val baseUrl: String = "http://192.168.100.1") {

    private val cookies = mutableMapOf<String, Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) {
            for (c in cookieList) cookies[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    enum class SecurityMode(val label: String, val beaconType: String, val authParam: String) {
        WPA_PSK("WPA-PSK", "WPA", "WPAAuthenticationMode"),
        WPA2_PSK("WPA2-PSK", "11i", "IEEE11iAuthenticationMode"),
        WPA_WPA2_PSK("WPA/WPA2-PSK", "WPAand11i", "X_HW_WPAand11iAuthenticationMode")
    }

    data class WifiInfo(
        val ssid: String,
        val domain: String,
        val token: String,
        val wifiEnabled: Boolean,
        val broadcastEnabled: Boolean,
        val wmmEnabled: Boolean,
        val wpsEnabled: Boolean,
        val maxDevices: Int,
        val securityMode: SecurityMode
    )

    private fun extract(regex: Regex, text: String, group: Int = 1): String? =
        regex.find(text)?.groupValues?.getOrNull(group)

    /** Load the login page and grab the CSRF-like token it embeds. */
    private fun fetchLoginToken(): String {
        val req = Request.Builder().url("$baseUrl/").build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
        return extract(Regex("id=\"hwonttoken\"[^>]*value=\"([^\"]+)\""), body)
            ?: throw IllegalStateException("Could not find login token on router page")
    }

    /** Log in with username/password (password is base64-encoded, not hashed). */
    fun login(username: String, password: String) {
        val token = fetchLoginToken()
        val encodedPassword = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)

        val form = FormBody.Builder()
            .add("UserName", username)
            .add("PassWord", encodedPassword)
            .add("x.X_HW_Token", token)
            .build()

        val req = Request.Builder().url("$baseUrl/login.cgi").post(form).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("Login failed: HTTP ${it.code}")
        }
    }

    /** Read the current WiFi (2.4G) settings from WlanBasic.asp. */
    fun getWifiInfo(): WifiInfo {
        val req = Request.Builder().url("$baseUrl/html/amp/wlanbasic/WlanBasic.asp").build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }

        val ssid = extract(
            Regex("stWlanWifi\\(\"[^\"]*\",\"[^\"]*\",\"[01]\",\"([^\"]*)\""), body
        ) ?: "unknown"

        // Domain appears in the page source as "InternetGatewayDevice\x2eLANDevice\x2e1..."
        // (a literal backslash-escape, not a real dot) - decode it before use.
        val rawDomain = extract(Regex("new stWlan\\(\"([^\"]+)\""), body)
        val domain = rawDomain?.replace("\\x2e", ".")
            ?: "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1"

        val token = extract(Regex("id=\"hwonttoken\"[^>]*value=\"([^\"]+)\""), body)
            ?: throw IllegalStateException("Could not find session token on WlanBasic page")

        // stWlan(domain,name,enable,ssid,wlHide,DeviceNum,wmmEnable,BeaconType,...)
        val wlanFields = Regex(
            "new stWlan\\(\"[^\"]*\",\"[^\"]*\",\"([01])\",\"[^\"]*\",\"([01])\",\"(\\d+)\",\"([01])\",\"(\\w+)\""
        ).find(body)

        val wifiEnabled = wlanFields?.groupValues?.get(1) == "1"
        val broadcastEnabled = wlanFields?.groupValues?.get(2) == "1"
        val maxDevices = wlanFields?.groupValues?.get(3)?.toIntOrNull() ?: 32
        val wmmEnabled = wlanFields?.groupValues?.get(4) == "1"
        val beaconType = wlanFields?.groupValues?.get(5) ?: "WPAand11i"

        val securityMode = when (beaconType) {
            "WPA" -> SecurityMode.WPA_PSK
            "11i" -> SecurityMode.WPA2_PSK
            else -> SecurityMode.WPA_WPA2_PSK
        }

        // WPS enabled state: new stWpsPin(domain, ConfigMethod, DevicePassword, PinGenerator, Enable)
        val wpsEnabled = extract(Regex("new stWpsPin\\([^)]*,\"(\\d)\"\\)"), body) == "1"

        return WifiInfo(
            ssid = ssid,
            domain = domain,
            token = token,
            wifiEnabled = wifiEnabled,
            broadcastEnabled = broadcastEnabled,
            wmmEnabled = wmmEnabled,
            wpsEnabled = wpsEnabled,
            maxDevices = maxDevices,
            securityMode = securityMode
        )
    }

    /**
     * Change SSID, password, and other basic WiFi settings on the main 2.4G
     * network. Pass the fields you want changed; anything unchanged should
     * be passed back as the current value from [WifiInfo].
     */
    fun saveWifi(
        info: WifiInfo,
        newSsid: String,
        newPassword: String?,
        broadcastEnabled: Boolean,
        wmmEnabled: Boolean,
        wpsEnabled: Boolean,
        maxDevices: Int,
        securityMode: SecurityMode
    ) {
        val url = "$baseUrl/set.cgi" +
            "?w=InternetGatewayDevice.X_HW_DEBUG.AMP.WifiCoverSetWlanBasic" +
            "&y=${info.domain}" +
            "&k=${info.domain}.PreSharedKey.1" +
            "&RequestFile=html/amp/wlanbasic/WlanBasic.asp"

        val formBuilder = FormBody.Builder()
            .add("y.Enable", "1")
            .add("y.SSIDAdvertisementEnabled", if (broadcastEnabled) "1" else "0")
            .add("y.WMMEnable", if (wmmEnabled) "1" else "0")
            .add("y.SSID", newSsid)
            .add("y.X_HW_AssociateNum", maxDevices.toString())
            .add("y.BeaconType", securityMode.beaconType)
            .add("y.${securityMode.authParam}", "PSKAuthentication")
            .add("y.X_HW_GroupRekey", "3600")
            .add("z.Enable", if (wpsEnabled) "1" else "0")
            // "cover" params the official form always sends alongside the main ones
            .add("w.SsidInst", "1")
            .add("w.SSID", newSsid)
            .add("w.Enable", "1")
            .add("w.Standard", "11bgn")
            .add("w.BasicAuthenticationMode", "None")
            .add("w.BasicEncryptionModes", "AESEncryption")
            .add("w.WPAAuthenticationMode", "EAPAuthentication")
            .add("w.WPAEncryptionModes", "AESEncryption")
            .add("w.IEEE11iAuthenticationMode", "EAPAuthentication")
            .add("w.IEEE11iEncryptionModes", "AESEncryption")
            .add("w.MixAuthenticationMode", "PSKAuthentication")
            .add("w.MixEncryptionModes", "AESEncryption")
            .add("w.BeaconType", securityMode.beaconType)
            .add("w.WEPEncryptionLevel", "104-bit")
            .add("w.WEPKeyIndex", "1")
            .add("x.X_HW_Token", info.token)

        // encryption mode field name differs slightly per security mode
        val encryptionParam = when (securityMode) {
            SecurityMode.WPA_PSK -> "WPAEncryptionModes"
            SecurityMode.WPA2_PSK -> "IEEE11iEncryptionModes"
            SecurityMode.WPA_WPA2_PSK -> "X_HW_WPAand11iEncryptionModes"
        }
        formBuilder.add("y.$encryptionParam", "AESEncryption")

        if (!newPassword.isNullOrBlank()) {
            formBuilder.add("k.PreSharedKey", newPassword)
            formBuilder.add("w.Key", newPassword)
        }

        val req = Request.Builder().url(url).post(formBuilder.build()).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("Save failed: HTTP ${it.code}")
        }
    }

    /** Turn the whole 2.4G radio on/off (separate endpoint from saveWifi). */
    fun setWifiEnabled(info: WifiInfo, enabled: Boolean) {
        val form = FormBody.Builder()
            .add("x.X_HW_WlanEnable", if (enabled) "1" else "0")
            .add("x.X_HW_Token", info.token)
            .build()

        val req = Request.Builder()
            .url("$baseUrl/set.cgi?x=InternetGatewayDevice.LANDevice.1&RequestFile=html/amp/wlanbasic/WlanBasic.asp")
            .post(form)
            .build()

        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("Toggle WiFi failed: HTTP ${it.code}")
        }
    }
}
