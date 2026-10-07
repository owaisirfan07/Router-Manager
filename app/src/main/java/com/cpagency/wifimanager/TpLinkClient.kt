package com.cpagency.wifimanager

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * READ-ONLY client for the TP-Link TL-WR820N (firmware 1.0.2, "TDDP" web UI).
 * Only logs in and reads the connected-device table (live up/down speed per
 * device). It never changes anything on the TP-Link.
 *
 * Protocol (from the router's own Quary.js / ajax.js):
 *  - POST /?code=2&asyn=1            -> "00007" (not logged in) + nonce + charset
 *  - session = securityEncode(nonce, orgAuthPwd(password), charset)
 *  - POST /?code=7&asyn=0&id=session -> "00000" = logged in
 *  - POST /?code=2&asyn=0&id=session, body "13" -> device table
 * Speeds "up"/"down" are bytes per second.
 */
class TpLinkClient(private val host: String = "192.168.0.1") {

    data class Station(
        val mac: String, val ip: String, val name: String,
        val online: Boolean, val upBps: Long, val downBps: Long, val wifi: Boolean
    )

    private val http = OkHttpClient.Builder()
        .socketFactory(NetUtil.WifiSocketFactory)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var session: String? = null
    private var password: String? = null

    fun setPassword(p: String) { if (p != password) { password = p; session = null } }

    private class Resp(val errno: Int, val lines: List<String>)

    private fun post(query: String, body: String = ""): Resp {
        val url = "http://$host/?$query".toHttpUrl()
        val req = Request.Builder().url(url)
            .header("Referer", "http://$host/")
            .post(body.toRequestBody("text/plain;charset=UTF-8".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { it.body?.string().orEmpty() }
        // same parsing as the router's ajax.js: first line = error number, rest = data
        val nl = text.indexOf("\r\n")
        val first = if (nl >= 0) text.substring(0, nl) else text
        return if (first.isNotEmpty() && first.all { it.isDigit() }) {
            Resp(first.toInt(), text.substring(nl + 2).split("\r\n").filter { it.isNotEmpty() })
        } else Resp(0, text.split("\r\n").filter { it.isNotEmpty() })
    }

    private fun login(authLines: List<String>) {
        val pwd = password ?: throw IllegalStateException("TP-Link password set nahi hai")
        if (authLines.size < 4) throw IllegalStateException("TP-Link ne login info nahi bheji")
        val nonce = authLines[2]
        val charset = authLines[3]
        val s = securityEncode(nonce, orgAuthPwd(pwd), charset)
        val r = post("code=7&asyn=0&id=" + encodeUri(s))
        if (r.errno != 0) throw IllegalStateException("TP-Link login fail - password check karein")
        session = s
    }

    /** Device table; logs in by itself when needed. */
    fun stations(): List<Station> {
        for (attempt in 0..1) {
            val s = session
            val r = if (s == null) post("code=2&asyn=1") else post("code=2&asyn=0&id=" + encodeUri(s), "13")
            if (r.errno == EUNAUTH) {
                session = null
                if (attempt == 1) break
                login(r.lines)
                continue
            }
            if (r.errno != 0) throw IllegalStateException("TP-Link error ${r.errno}")
            if (s == null) continue // got data without a session?? try again with one
            return parse(r.lines)
        }
        throw IllegalStateException("TP-Link login nahi ho saka")
    }

    private fun parse(lines: List<String>): List<Station> {
        val table = HashMap<String, HashMap<Int, String>>()
        for (l in lines) {
            val p = l.split(' ', limit = 3)
            if (p.size < 2) continue
            val idx = p[1].toIntOrNull() ?: continue
            table.getOrPut(p[0]) { HashMap() }[idx] = p.getOrElse(2) { "" }
        }
        val macs = table["mac"] ?: return emptyList()
        return macs.entries.sortedBy { it.key }.mapNotNull { (i, mac) ->
            if (mac == "00-00-00-00-00-00" || mac.isBlank()) return@mapNotNull null
            val ip = table["ip"]?.get(i).orEmpty()
            val online = table["online"]?.get(i) == "1"
            if (ip == "0.0.0.0" && !online) return@mapNotNull null
            Station(
                mac = mac.replace('-', ':').lowercase(),
                ip = ip,
                name = java.net.URLDecoder.decode(table["name"]?.get(i).orEmpty(), "UTF-8").ifBlank { "Unknown" },
                online = online,
                upBps = table["up"]?.get(i)?.toLongOrNull() ?: 0,
                downBps = table["down"]?.get(i)?.toLongOrNull() ?: 0,
                wifi = table["type"]?.get(i) == "1"
            )
        }
    }

    companion object {
        private const val EUNAUTH = 7

        fun securityEncode(a: String, b: String, charset: String): String {
            val out = StringBuilder()
            val len = maxOf(a.length, b.length)
            for (g in 0 until len) {
                var l = 187; var i = 187
                when {
                    g >= a.length -> i = b[g].code
                    g >= b.length -> l = a[g].code
                    else -> { l = a[g].code; i = b[g].code }
                }
                out.append(charset[(l xor i) % charset.length])
            }
            return out.toString()
        }

        fun orgAuthPwd(pwd: String): String = securityEncode(
            pwd, "RDpbLfCPsJZ7fiv",
            "yLwVl0zKqws7LgKPRQ84Mdt708T1qQ3Ha7xv3H7NyU84p21BriUWBU43odz3iP4rBL3cD02KZciXTysVXiV8ngg6vL48rPJyAUw0HurW20xqxv9aYb4M9wK1Ae0wlro510qXeU07kV57fQMc8L6aLgMLwygtc0F10a0Dg70TOoouyFhdysuRMO51yY5ZlOZZLEal1h0t9YQW0Ko7oBwmCAHoic4HYbUyVeU3sfQ1xtXcPcf1aT303wAQhv66qzW"
        )

        /** Same as JavaScript encodeURIComponent. */
        fun encodeUri(s: String): String {
            val keep = "-_.!~*'()"
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in keep) sb.append(c)
                else sb.append('%').append("%02X".format(b.toInt() and 0xff))
            }
            return sb.toString()
        }
    }
}
