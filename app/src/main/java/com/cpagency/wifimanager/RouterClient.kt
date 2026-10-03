package com.cpagency.wifimanager

import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    init {
        // This router's login page sets a cookie literally NAMED "Cookie" with a
        // custom value (sid:Language:id) before submitting login - seed the same
        // pre-login placeholder value so the server accepts our login the same way.
        val host = baseUrl.toHttpUrl().host
        cookies["Cookie"] = Cookie.Builder()
            .name("Cookie")
            .value("body:Language:english:id=-1")
            .domain(host)
            .path("/")
            .build()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val newReq = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) RouterManagerApp")
                .header("Referer", "$baseUrl/index.asp")
                .build()
            chain.proceed(newReq)
        }
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

    /** Load the login page and grab the CSRF-like token it embeds
     *  (returned by the page's GetRandCnt() JS function, not a hidden input). */
    private fun fetchLoginToken(): String {
        val req = Request.Builder().url("$baseUrl/").build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
        return extract(Regex("GetRandCnt\\(\\)\\s*\\{\\s*return\\s*'([^']+)'"), body)
            ?: throw IllegalStateException("Could not find login token on router page")
    }

    fun baseUrlHost(): String = baseUrl.removePrefix("http://").removePrefix("https://")

    // remembered so we can log in again quietly when the router's session times out
    private var savedUser: String? = null
    private var savedPass: String? = null
    private var pageToken: String? = null

    private fun looksLoggedOut(body: String): Boolean =
        body.contains("GetRandCnt") ||
            (body.contains("top.location.replace") && body.length < 2500) ||
            (body.contains("login.asp", ignoreCase = true) && body.length < 1000)

    private fun rawFetch(path: String, post: Boolean, token: String?): Pair<Int, String> {
        val builder = Request.Builder().url("$baseUrl/${path.trimStart('/')}")
        if (post) {
            val form = FormBody.Builder()
            if (token != null) form.add("x.X_HW_Token", token)
            builder.post(form.build())
        }
        return client.newCall(builder.build()).execute().use { it.code to (it.body?.string() ?: "") }
    }

    /** Token some POST data endpoints need (same one the router's home page uses). */
    private fun getPageToken(): String {
        pageToken?.let { return it }
        val (_, body) = rawFetch("CustomApp/mainpage.asp", false, null)
        val t = extract(Regex("""MainPageToken\s*=\s*"([^"]+)""""), body)
            ?: extract(Regex("id=\"hwonttoken\"[^>]*value=\"([^\"]+)\""), body)
            ?: ""
        pageToken = t
        return t
    }

    /** Downloads one router page. Logs in again once if the session has expired. */
    fun fetchPage(path: String, post: Boolean = false, needsToken: Boolean = false): String {
        for (attempt in 0..1) {
            val token = if (needsToken) getPageToken() else null
            val (code, body) = rawFetch(path, post, token)
            if (code == 404) throw IllegalStateException("page not found on this router (404)")
            if (code >= 400) throw IllegalStateException("HTTP $code")
            if (!looksLoggedOut(body)) return body
            // session expired -> log in again and retry once
            val u = savedUser
            val p = savedPass
            if (attempt == 1 || u == null || p == null) throw IllegalStateException("Router session expired - please log in again")
            pageToken = null
            login(u, p)
        }
        throw IllegalStateException("Could not load $path")
    }

    /** Downloads every page a section needs and parses them together. One failing page doesn't stop the rest. */
    fun loadSection(section: Section): PageData {
        val data = PageData()
        for (src in section.sources) {
            try {
                data.add(src.path, fetchPage(src.path, src.post, src.token))
            } catch (e: Exception) {
                if (e.message?.contains("session expired") == true) throw e
                data.errors[src.path] = e.message ?: e.javaClass.simpleName
            }
        }
        return data
    }

    /** Log in with username/password (password is base64-encoded, not hashed). */
    fun login(username: String, password: String) {
        savedUser = username
        savedPass = password
        pageToken = null
        val token = fetchLoginToken()
        val encodedPassword = Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP)

        // The router's own login page does `document.cookie = "Cookie=body:Language:english:id=-1;path=/"`
        // before submitting - some firmware checks for this cookie, so we set it too.
        val httpUrl = baseUrl.toHttpUrl()
        cookies["Cookie"] = Cookie.Builder()
            .name("Cookie")
            .value("body:Language:english:id=-1")
            .domain(httpUrl.host)
            .path("/")
            .build()

        val form = FormBody.Builder()
            .add("UserName", username)
            .add("PassWord", encodedPassword)
            .add("x.X_HW_Token", token)
            .build()

        val req = Request.Builder()
            .url("$baseUrl/login.cgi")
            .header("Referer", "$baseUrl/")
            .post(form)
            .build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("Login failed: HTTP ${it.code}")
            val respBody = it.body?.string() ?: ""
            if (respBody.contains("login.asp", ignoreCase = true) ||
                respBody.contains("FailStat", ignoreCase = true)
            ) {
                throw IllegalStateException("Login failed - check username/password")
            }
        }
    }

    /** Read the current WiFi (2.4G) settings from WlanBasic.asp. */
    fun getWifiInfo(): WifiInfo {
        val body = fetchPage("html/amp/wlanbasic/WlanBasic.asp")

        val ssid = extract(
            Regex("stWlanWifi\\(\"[^\"]*\",\"[^\"]*\",\"[01]\",\"([^\"]*)\""), body
        ) ?: "unknown"

        // Domain appears in the page source as "InternetGatewayDevice\x2eLANDevice\x2e1..."
        // (a literal backslash-escape, not a real dot) - decode it before use.
        val rawDomain = extract(Regex("new stWlan\\(\"([^\"]+)\""), body)
        val domain = rawDomain?.replace("\\x2e", ".")
            ?: "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1"

        val token = extract(Regex("id=\"hwonttoken\"[^>]*value=\"([^\"]+)\""), body)
        if (token == null) {
            when {
                body.contains("GetRandCnt") ->
                    throw IllegalStateException("Login failed - check username/password and try again")
                body.contains("top.location.replace") ->
                    throw IllegalStateException("Got a redirect page instead of settings - session may not be sticking")
                body.contains("stWlanWifi") ->
                    throw IllegalStateException("Got the real settings page but no token field found - field format may differ")
                else -> {
                    val snippet = body.take(1500).replace("\n", " ")
                    throw IllegalStateException("Unexpected page: $snippet")
                }
            }
        }

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
