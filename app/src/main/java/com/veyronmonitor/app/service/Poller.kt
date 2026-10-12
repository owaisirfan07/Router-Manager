package com.veyronmonitor.app.service

import android.content.Context
import com.veyronmonitor.app.api.TumcApi
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.data.EnergyStore
import com.veyronmonitor.app.data.WarningLogStore
import com.veyronmonitor.app.model.AuthState
import com.veyronmonitor.app.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * One shared "fetch a reading and count it" routine, used by the open app,
 * the background service and the WorkManager backup job. Keeps one login
 * session in memory and logs in again by itself if the token expires.
 */
object Poller {
    @Volatile var auth: AuthState? = null
    @Volatile var device: Device? = null

    private val _latest = MutableStateFlow<JSONObject?>(null)
    /** The most recent reading from any source (app, service or worker). */
    val latest: StateFlow<JSONObject?> = _latest.asStateFlow()

    private val mutex = Mutex()

    fun setSession(a: AuthState?, d: Device?) { auth = a; device = d }

    fun clear() { auth = null; device = null; _latest.value = null }

    private fun login(context: Context): Boolean {
        val (u, p) = CredentialStore.load(context) ?: return false
        val a = TumcApi.login(u, p)
        val devices = TumcApi.getDevices(a)
        if (devices.isEmpty()) return false
        val keep = device?.serialNumber
        auth = a
        device = devices.firstOrNull { it.serialNumber == keep } ?: devices.first()
        return true
    }

    /**
     * Blocking (call from IO). Fetches the live reading, feeds it to the
     * energy meter and the warnings log. Returns the reading, or throws.
     */
    fun fetchOnce(context: Context): JSONObject {
        if (auth == null || device == null) {
            if (!login(context)) throw IllegalStateException("Not logged in")
        }
        val json = try {
            TumcApi.getRealData(auth!!, device!!)
        } catch (e: TumcApi.ApiException) {
            // Token expired or similar - log in again once and retry.
            if (!login(context)) throw e
            TumcApi.getRealData(auth!!, device!!)
        }
        handle(context, json)
        return json
    }

    suspend fun fetchOnceLocked(context: Context): JSONObject = mutex.withLock { fetchOnce(context) }

    /** Count a reading that some other code fetched. */
    @Synchronized
    fun handle(context: Context, json: JSONObject) {
        _latest.value = json
        EnergyStore.process(context, json)
        val warningArr = json.optJSONArray("warning")
        val warningCodes = if (warningArr != null) (0 until warningArr.length()).map { warningArr.optString(it) } else emptyList()
        WarningLogStore.recordActiveCodes(context, warningCodes, json.opt("fault1")?.toString())
    }
}
