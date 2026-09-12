package com.cpagency.wifimanager

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Checks the GitHub repo's "latest" release for a newer version than what's
 * installed. Works against a PUBLIC repo's releases API (no auth needed).
 */
object UpdateChecker {

    private const val REPO = "owaisirfan07/Router-Manager"
    private val client = OkHttpClient()

    data class UpdateInfo(val versionName: String, val downloadUrl: String)

    /** Returns update info if a newer release exists, or null if up to date / check failed. */
    fun checkForUpdate(currentVersionName: String): UpdateInfo? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                val tag = json.getString("tag_name").removePrefix("v")
                if (tag == currentVersionName) return null

                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                if (apkUrl == null) return null

                UpdateInfo(versionName = tag, downloadUrl = apkUrl)
            }
        } catch (e: Exception) {
            null
        }
    }
}
