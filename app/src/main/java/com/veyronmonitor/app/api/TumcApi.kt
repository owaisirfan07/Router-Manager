package com.veyronmonitor.app.api

import com.veyronmonitor.app.model.AuthState
import com.veyronmonitor.app.model.Device
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Client for the real i.Solar cloud backend, reverse-engineered directly
 * from the i.Solar Android app (a DCloud uni-app hybrid app, package
 * uni.UNI199C480). This is NOT the generic "ShineMonitor" platform used
 * by many other Voltronic/MPP-Solar rebrands -- i.Solar has its own
 * separate backend at tumcapp.com.
 *
 * Every request is form-urlencoded and signed with two headers:
 *   - "token": the session token returned at login (empty before login)
 *   - "vrt": a SHA-256-based signature computed from the request body
 *            and a secret "vrtKey" also returned at login
 *
 * Signing algorithm (ported 1:1 from the app's own JS bundle):
 *   1. h = "k1=v1&k2=v2..." (raw, NOT url-encoded, in field-declaration order)
 *   2. checksum = sum of the char codes of every character in h
 *   3. y = sha256Hex(checksum.toString())
 *   4. w = vrtKey + y.takeLast(8) + y.take(8)
 *   5. vrt header = sha256Hex(w)
 *
 * Note: the checksum string (h) must NOT be url-encoded, but the actual
 * HTTP body sent to the server IS standard application/x-www-form-urlencoded.
 * These are two different serializations of the same data and both matter.
 */
object TumcApi {

    private const val BASE_URL = "https://www.tumcapp.com/app"

    class ApiException(val code: Int, val apiMessage: String) :
        Exception("i.Solar API error $code: $apiMessage")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Computes the "vrt" signature header for a given ordered set of body fields. */
    private fun computeVrt(orderedFields: LinkedHashMap<String, String>, vrtKey: String): String {
        val h = orderedFields.entries.joinToString("&") { "${it.key}=${it.value}" }
        var checksum = 0L
        for (ch in h) checksum += ch.code
        val y = sha256Hex(checksum.toString())
        val w = vrtKey + y.takeLast(8) + y.take(8)
        return sha256Hex(w)
    }

    private fun formEncode(fields: LinkedHashMap<String, String>): String =
        fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    /**
     * Performs one signed POST request against the i.Solar API.
     *
     * @param path endpoint path, e.g. "/api/mobile/user/login"
     * @param fields ordered request fields -- order matters, it must match
     *               what the real app sends since it feeds into the signature
     * @param token current session token, or "" if not logged in yet
     * @param vrtKey current session signing secret, or "" if not logged in yet
     */
    private fun post(
        path: String,
        fields: LinkedHashMap<String, String>,
        token: String,
        vrtKey: String,
        readTimeoutMs: Int = 10_000
    ): JSONObject {
        val vrt = computeVrt(fields, vrtKey)
        val body = formEncode(fields)

        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("content-type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("token", token)
        connection.setRequestProperty("vrt", vrt)

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)
            val apiCode = json.optInt("code", -1)
            if (apiCode != 0) {
                throw ApiException(apiCode, json.optString("message", "unknown error"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    /** Step 1: log in with the same username/password used in the i.Solar app. */
    fun login(username: String, password: String): AuthState {
        val fields = linkedMapOf(
            "username" to username,
            "password" to md5Hex(password)
        )
        // Before login there is no token/vrtKey yet, so both are sent empty.
        val json = post("/api/mobile/user/login", fields, token = "", vrtKey = "")
        val dat = json.getJSONObject("data")
        return AuthState(
            token = dat.getString("token"),
            vrtKey = dat.getString("vrtKey")
        )
    }

    /** Step 2: list every inverter registered to this account. */
    fun getDevices(auth: AuthState): List<Device> {
        val fields = linkedMapOf(
            "openPage" to "1",
            "pageNum" to "1",
            "pageSize" to "500",
            "groupId" to "0"
        )
        val json = post("/api/mobile/deviceUser/getMyDevice", fields, auth.token, auth.vrtKey)
        val dat = json.getJSONObject("data")
        val list = dat.getJSONArray("list")
        return (0 until list.length()).map { i ->
            val d = list.getJSONObject(i)
            // org.json's optString() returns the literal string "null" (not "")
            // when a key is present but holds a JSON null, so we check isNull()
            // explicitly -- otherwise the toolbar showed a literal "null" title.
            fun cleanString(key: String): String? {
                if (!d.has(key) || d.isNull(key)) return null
                val v = d.optString(key)
                return v.ifBlank { null }
            }
            val sn = d.getString("deviceSn")
            Device(
                serialNumber = sn,
                displayName = cleanString("nickName") ?: cleanString("deviceName") ?: sn,
                isOnline = d.optInt("onlineStatus", 0) == 1
            )
        }
    }

    /**
     * Fetches the raw parameter-set data for a device. Field meanings are
     * highly model-specific (keyed by prodId/subId), so this surfaces the
     * raw values rather than guessing a schema. Only fields we've verified
     * against the real i.Solar app are exposed as editable in the UI.
     */
    fun getParams(auth: AuthState, device: Device): JSONObject {
        val fields = linkedMapOf("deviceSn" to device.serialNumber)
        val json = post("/api/mobile/paramSet/getParam", fields, auth.token, auth.vrtKey)
        return json.getJSONObject("data")
    }

    /**
     * Sends a single parameter change to the inverter. Only used for
     * parameters we've explicitly confirmed the meaning of against the
     * real i.Solar app (starting with "PC" -- Charging Priority) to avoid
     * sending a malformed or misunderstood command.
     *
     * Returns the full raw response (not just "data") so the caller can
     * inspect it for diagnostics -- a successful HTTP/code=0 response does
     * not always mean the inverter actually applied the change.
     */
    fun setParam(auth: AuthState, device: Device, key: String, value: String): JSONObject {
        val commands = JSONObject().put(key, value).toString()
        val fields = linkedMapOf(
            "deviceSn" to device.serialNumber,
            "commands" to commands
        )
        // The dongle has to relay this to the physical inverter and wait for
        // an acknowledgement, which is much slower than a plain data read --
        // 10s was too tight and caused false timeouts.
        return post("/api/mobile/paramSet/setParam", fields, auth.token, auth.vrtKey, readTimeoutMs = 45_000)
    }

    /**
     * Step 3: pull the latest live snapshot for one inverter.
     *
     * The exact field names in the response vary by inverter model/product
     * family (i.Solar renders a different UI component per prodId/subId),
     * so this returns the raw JSON object as-is rather than guessing a
     * fixed schema. The app displays this raw data so we can see your
     * Veyron II's actual field names and turn them into proper cards next.
     */
    fun getRealData(auth: AuthState, device: Device): JSONObject {
        val fields = linkedMapOf("deviceSn" to device.serialNumber)
        val json = post("/api/mobile/realData/getRealByDeviceSn", fields, auth.token, auth.vrtKey)
        return json.getJSONObject("data")
    }
}
