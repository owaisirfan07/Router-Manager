package com.veyronmonitor.app.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionLabel: String,
    val apkDownloadUrl: String
)

/**
 * Checks the app's own GitHub repo for a newer release than the currently
 * installed build. This is how "check for updates" works for a sideloaded
 * app (outside the Play Store) -- every CI build publishes a GitHub Release
 * tagged "v<versionCode>" with the APK attached (see
 * .github/workflows/build-apk.yml), and this simply asks GitHub which
 * release is newest and compares its version number to our own.
 */
object UpdateChecker {

    // Change these if you fork this project under a different GitHub account/repo.
    private const val REPO_OWNER = "owaisirfan07"
    private const val REPO_NAME = "Veyron-Monitor"

    private const val API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** Returns update info if a newer build than [currentVersionCode] is available, else null. */
    fun checkForUpdate(currentVersionCode: Int): UpdateInfo? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000

        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)

            val tag = json.optString("tag_name", "")
            val remoteVersionCode = tag.removePrefix("v").toIntOrNull() ?: return null
            if (remoteVersionCode <= currentVersionCode) return null

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return null

            return UpdateInfo(
                versionCode = remoteVersionCode,
                versionLabel = json.optString("name", tag),
                apkDownloadUrl = apkUrl
            )
        } catch (_: Exception) {
            return null // update checks should never crash or block the app
        } finally {
            connection.disconnect()
        }
    }
}
