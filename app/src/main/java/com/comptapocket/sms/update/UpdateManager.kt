package com.comptapocket.sms.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.comptapocket.sms.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val latestVersionCode: Int,
    val releaseDate: String = "",
    val changelog: String = "",
    val downloadUrl: String = "",
    val releaseUrl: String = ""
)

object UpdateManager {

    const val REPO_URL = "https://github.com/bkhmondocteur/comptapocket-sms"
    private const val VERSION_URL = "https://raw.githubusercontent.com/bkhmondocteur/comptapocket-sms/main/version.json"
    private const val GITHUB_API_URL = "https://api.github.com/repos/bkhmondocteur/comptapocket-sms/releases/latest"

    suspend fun checkUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        val currentCode = BuildConfig.VERSION_CODE

        try {
            // 1. Tenter d'abord de lire version.json direct depuis GitHub
            var jsonStr: String? = fetchUrl(VERSION_URL)

            // 2. Si version.json n'est pas encore disponible, interroger l'API GitHub Releases
            if (jsonStr == null || jsonStr.isBlank()) {
                jsonStr = fetchUrl(GITHUB_API_URL)
            }

            if (!jsonStr.isNullOrBlank()) {
                val json = JSONObject(jsonStr)

                val latestVersion: String
                val latestCode: Int
                val changelog: String
                val downloadUrl: String
                val releaseUrl: String
                val releaseDate: String

                if (json.has("versionName")) {
                    // Format version.json personnalisé
                    latestVersion = json.optString("versionName", currentVersion)
                    latestCode = json.optInt("versionCode", currentCode)
                    changelog = json.optString("changelog", "Améliorations et corrections générales.")
                    downloadUrl = json.optString(
                        "downloadUrl",
                        "https://github.com/bkhmondocteur/comptapocket-sms/releases/latest/download/ComptaPocket-SMS.apk"
                    )
                    releaseUrl = json.optString("releaseUrl", "https://github.com/bkhmondocteur/comptapocket-sms/releases/latest")
                    releaseDate = json.optString("releaseDate", "")
                } else if (json.has("tag_name")) {
                    // Format GitHub Releases API
                    val tag = json.optString("tag_name").removePrefix("v")
                    latestVersion = tag
                    latestCode = parseVersionToCode(tag)
                    changelog = json.optString("body", "Mise à jour disponible.")
                    releaseUrl = json.optString("html_url", "https://github.com/bkhmondocteur/comptapocket-sms/releases/latest")
                    releaseDate = json.optString("published_at", "")

                    val assets = json.optJSONArray("assets")
                    var foundUrl = "https://github.com/bkhmondocteur/comptapocket-sms/releases/latest/download/ComptaPocket-SMS.apk"
                    if (assets != null && assets.length() > 0) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                                foundUrl = asset.optString("browser_download_url", foundUrl)
                                break
                            }
                        }
                    }
                    downloadUrl = foundUrl
                } else {
                    return@withContext UpdateInfo(false, currentVersion, currentVersion, currentCode)
                }

                val hasUpdate = latestCode > currentCode || isVersionGreater(latestVersion, currentVersion)

                return@withContext UpdateInfo(
                    hasUpdate = hasUpdate,
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    latestVersionCode = latestCode,
                    releaseDate = releaseDate,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    releaseUrl = releaseUrl
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        UpdateInfo(false, currentVersion, currentVersion, currentCode)
    }

    private fun fetchUrl(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "ComptaPocket-SMS-App")
            conn.connect()
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun startDownload(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseVersionToCode(ver: String): Int {
        val parts = ver.split(".").mapNotNull { it.toIntOrNull() }
        var code = 0
        var mult = 10000
        for (p in parts) {
            code += p * mult
            mult /= 100
        }
        return code
    }

    private fun isVersionGreater(latest: String, current: String): Boolean {
        val p1 = latest.split(".").mapNotNull { it.toIntOrNull() }
        val p2 = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val v1 = p1.getOrElse(i) { 0 }
            val v2 = p2.getOrElse(i) { 0 }
            if (v1 > v2) return true
            if (v1 < v2) return false
        }
        return false
    }
}
