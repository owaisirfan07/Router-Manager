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
/** How long a downloaded page is reused (ms). */
const val CACHE_MS = 30_000L

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
        val securityMode: SecurityMode,
        /** 11b / 11g / 11n / 11bg / 11bgn - re-sent unchanged on save */
        val standard: String = "11bgn"
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

    /* ---------- speed: short cache + parallel downloads ---------- */

    /** Pages read in the last [CACHE_MS] are reused, so going back/forth between screens is instant. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** Forget cached pages (Refresh button, and after every change we send). */
    fun clearCache() = cache.clear()

    /** 3 downloads at a time - faster than one by one, gentle enough for the router. */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)

    private val loginLock = Any()
    @Volatile private var loginGen = 0

    /** Downloads one router page. Logs in again once if the session has expired. */
    fun fetchPage(path: String, post: Boolean = false, needsToken: Boolean = false, maxAgeMs: Long = CACHE_MS): String {
        val key = "$path|$post|$needsToken"
        if (maxAgeMs > 0) cache[key]?.let { (t, body) -> if (System.currentTimeMillis() - t < maxAgeMs) return body }
        for (attempt in 0..1) {
            val gen = loginGen
            val token = if (needsToken) getPageToken() else null
            val (code, body) = rawFetch(path, post, token)
            if (code == 404) throw IllegalStateException("page not found on this router (404)")
            if (code >= 400) throw IllegalStateException("HTTP $code")
            if (!looksLoggedOut(body)) {
                cache[key] = System.currentTimeMillis() to body
                return body
            }
            // session expired -> log in again (only once, even if several downloads notice it together)
            val u = savedUser
            val p = savedPass
            if (attempt == 1 || u == null || p == null) throw IllegalStateException("Router session expired - please log in again")
            synchronized(loginLock) {
                if (loginGen == gen) { pageToken = null; login(u, p) }
            }
        }
        throw IllegalStateException("Could not load $path")
    }

    /** Downloads several pages at the same time; results keep the given order. */
    private fun fetchAll(srcs: List<Src>, maxAgeMs: Long): List<Result<String>> =
        srcs.map { s -> pool.submit<String> { fetchPage(s.path, s.post, s.token, maxAgeMs) } }
            .map { f -> runCatching { try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } } }

    /**
     * Sends a change the same way the router's own page does:
     * 1. loads [page] to get a fresh token (and to be sure we're logged in),
     * 2. POSTs [params] + x.X_HW_Token to [action], relative to the page's folder
     *    (e.g. page html/bbsp/dhcp/dhcp.asp + "set.cgi?x=..." -> /html/bbsp/dhcp/set.cgi?x=...).
     * Returns the router's response text.
     */
    fun submit(page: String, action: String, params: List<Pair<String, String>>): String {
        val pageBody = fetchPage(page, maxAgeMs = 0) // always a fresh token
        val token = extract(Regex("id=\"hwonttoken\"[^>]*value=\"([^\"]+)\""), pageBody)
            ?: extract(Regex("name=\"onttoken\"[^>]*value=\"([^\"]+)\""), pageBody)
            ?: throw IllegalStateException("Could not get a security token from the router")
        val dir = page.trimStart('/').substringBeforeLast('/', "")
        val url = "$baseUrl/" + (if (dir.isEmpty()) "" else "$dir/") + action
        val form = FormBody.Builder()
        for ((k, v) in params) form.add(k, v)
        form.add("x.X_HW_Token", token)
        val req = Request.Builder().url(url).header("Referer", "$baseUrl/$page").post(form.build()).build()
        try {
            return client.newCall(req).execute().use {
                if (!it.isSuccessful) throw IllegalStateException("Router refused the change (HTTP ${it.code})")
                it.body?.string() ?: ""
            }
        } finally {
            clearCache() // settings changed - don't show old values
        }
    }

    /** Parsed data of one page (helper for RouterActions). */
    fun page(vararg paths: String, maxAgeMs: Long = CACHE_MS): PageData {
        val d = PageData()
        val results = fetchAll(paths.map { Src(it) }, maxAgeMs)
        paths.forEachIndexed { i, p -> d.add(p, results[i].getOrThrow()) }
        return d
    }

    /** True if the router answers at all (used while it reboots). */
    fun isReachable(): Boolean = try {
        client.newCall(Request.Builder().url("$baseUrl/").build()).execute().use { it.code < 500 }
    } catch (e: Exception) { false }

    /** Downloads every page a section needs and parses them together. One failing page doesn't stop the rest. */
    fun loadSection(section: Section, maxAgeMs: Long = CACHE_MS): PageData {
        val data = PageData()
        val results = fetchAll(section.sources, maxAgeMs)
        section.sources.forEachIndexed { i, src ->
            results[i].fold(
                onSuccess = { data.add(src.path, it) },
                onFailure = { e ->
                    if (e.message?.contains("session expired") == true) throw e
                    data.errors[src.path] = e.message ?: e.javaClass.simpleName
                }
            )
        }
        return data
    }

    /** Log in with username/password (password is base64-encoded, not hashed). */
    fun login(username: String, password: String) {
        loginGen++
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
        val body = fetchPage("html/amp/wlanbasic/WlanBasic.asp", maxAgeMs = 0) // fresh token for saving

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

        // radio standard, from stWlanWifi(domain,name,enable,ssid,mode,...)
        val standard = extract(
            Regex("new stWlanWifi\\(\"[^\"]*\",\"[^\"]*\",\"[01]\",\"[^\"]*\",\"(\\w+)\""), body
        )?.takeIf { it.startsWith("11") } ?: "11bgn"

        return WifiInfo(
            standard = standard,
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
            .add("w.Standard", info.standard)
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
        clearCache()
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
        clearCache()
    }
}
