package com.shadow.injector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain self-updater: reads a version.json published on GitHub Pages, and if it advertises a
 * newer versionCode than the installed build, offers to download and install that APK.
 * Nothing here is obfuscated or hidden — it is the same flow any independently
 * distributed Android app uses.
 */
object Updater {

    private const val TAG = "ShadowUpdater"

    /** Version manifest. Edit the copy in docs/version.json and the site + app both update. */
    const val VERSION_URL = "https://demoneditz985-ctrl.github.io/P2077kng/version.json"

    const val TELEGRAM_URL = "https://t.me/+BBimnHMiSvpiYTBl"
    const val SITE_URL = "https://demoneditz985-ctrl.github.io/P2077kng/"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val force: Boolean
    )

    /** Blocking — call from a background thread. Returns null when there is nothing new. */
    fun checkUpdate(): Info? {
        return try {
            val json = JSONObject(fetch(VERSION_URL, connectMs = 8000, readMs = 8000) ?: return null)

            val latest = json.optInt("versionCode", 0)
            if (latest <= BuildConfig.VERSION_CODE) return null

            val apkUrl = json.optString("apkUrl", "")
            if (apkUrl.isBlank()) return null

            Info(
                versionCode = latest,
                versionName = json.optString("versionName", latest.toString()),
                apkUrl = apkUrl,
                notes = json.optString("notes", "Bug fixes and improvements."),
                force = json.optBoolean("force", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            null
        }
    }

    /** Blocking. Downloads the APK into the app's cache. */
    fun download(context: Context, info: Info): File? {
        return try {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, "shadow-injector-${info.versionName}.apk")
            if (target.exists()) target.delete()

            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            target
        } catch (e: Exception) {
            Log.w(TAG, "apk download failed", e)
            null
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun fetch(url: String, connectMs: Int, readMs: Int): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $url", e)
            null
        } finally {
            conn.disconnect()
        }
    }
}
