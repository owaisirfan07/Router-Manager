package com.cpagency.wifimanager

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks the GitHub repo's latest Release for a newer build and downloads it.
 *
 * GitHub Actions publishes every push to main as a Release tagged "b<versionCode>"
 * (e.g. b115) with RouterManager.apk attached. If that number is bigger than the
 * installed versionCode, an update is available.
 */
object UpdateChecker {

    private const val REPO = "owaisirfan07/Router-Manager"

    /** Only changed by tests. */
    internal var apiBase = "https://api.github.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val notes: String,
        val sizeBytes: Long
    )

    /** Returns update info if a newer release exists, or null if up to date / check failed. */
    fun checkForUpdate(currentVersionCode: Long): UpdateInfo? {
        return try {
            val req = Request.Builder()
                .url("$apiBase/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "RouterManagerApp")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)

                // tag looks like "b115" -> 115 (old "v1.0" style tags are ignored)
                val code = json.getString("tag_name").removePrefix("b").toLongOrNull() ?: return null
                if (code <= currentVersionCode) return null

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        return UpdateInfo(
                            versionName = json.optString("name").removePrefix("v").ifBlank { code.toString() },
                            versionCode = code,
                            downloadUrl = asset.getString("browser_download_url"),
                            notes = json.optString("body").trim(),
                            sizeBytes = asset.optLong("size", -1)
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads the APK into [dest]. onProgress gets 0-100 (or -1 if size unknown). */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "RouterManagerApp").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IllegalStateException("Could not save the update file")
        }
    }
}
