package com.poco.camera.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.poco.camera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AvailableUpdate(
    val version: String,
    val apkUrl: String,
    val sha256Url: String,
    val releaseUrl: String,
)

/** Consent-based updater for published NeoCam GitHub Releases. */
class UpdateManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("neocam_update_checks", Context.MODE_PRIVATE)

    suspend fun checkForUpdate(force: Boolean = false): AvailableUpdate? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val age = now - prefs.getLong("last_successful_check", 0L)
        if (!force && age in 0 until CHECK_INTERVAL_MS) return@withContext null
        val release = JSONObject(readSmallText(API_LATEST))
        check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) {
            "Релиз ещё не опубликован"
        }
        val tag = release.getString("tag_name")
        val newest = version(tag) ?: error("Неизвестная версия GitHub Release")
        val installed = version(BuildConfig.VERSION_NAME) ?: error("Неизвестная версия NeoCam")
        prefs.edit().putLong("last_successful_check", now).apply()
        if (compare(newest, installed) <= 0) return@withContext null

        val prefix = "https://github.com/DimSimd2020/NeoCam/releases/download/$tag/"
        val assets = release.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (index in 0 until assets.length()) {
            val item = assets.getJSONObject(index)
            val name = item.optString("name")
            val url = item.optString("browser_download_url")
            if (!url.startsWith(prefix)) continue
            if (name == "NeoCam-$tag.apk" || name == "NeoCam-" + tag.removePrefix("v") + ".apk") apk = url
            if (name.endsWith(".apk.sha256") && name.startsWith("NeoCam-")) sha = url
        }
        AvailableUpdate(
            version = tag.removePrefix("v"),
            apkUrl = apk ?: error("В релизе нет NeoCam APK"),
            sha256Url = sha ?: error("В релизе нет SHA-256 для APK"),
            releaseUrl = release.getString("html_url"),
        )
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings() {
        context.startActivity(Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun downloadAndVerify(update: AvailableUpdate, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val expected = Regex("[a-fA-F0-9]{64}").find(readSmallText(update.sha256Url))
                ?.value?.lowercase() ?: error("Некорректная SHA-256 сумма")
            val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
            val part = File(dir, "NeoCam-update.apk.part")
            val apk = File(dir, "NeoCam-update.apk")
            part.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val connection = openHttps(update.apkUrl)
                try {
                    check(connection.responseCode in 200..299 && connection.url.protocol == "https") {
                        "Ошибка скачивания APK: HTTP " + connection.responseCode
                    }
                    val size = connection.contentLengthLong
                    check(size <= MAX_BYTES) { "APK слишком большой" }
                    var downloaded = 0L
                    var lastPercent = -1
                    connection.inputStream.use { input ->
                        part.outputStream().buffered().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                downloaded += n
                                check(downloaded <= MAX_BYTES) { "APK слишком большой" }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                if (size > 0L) {
                                    val pct = (downloaded * 100L / size).toInt().coerceIn(0, 100)
                                    if (pct != lastPercent) {
                                        lastPercent = pct
                                        withContext(Dispatchers.Main) { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                    check(downloaded > 0L && (size <= 0 || downloaded == size)) {
                        "Неполная загрузка APK"
                    }
                } finally { connection.disconnect() }
                check(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                    "SHA-256 APK не совпадает с контрольной суммой релиза"
                }
                verifyPackage(part)
                apk.delete()
                check(part.renameTo(apk)) { "Не удалось подготовить APK" }
                apk
            } finally { part.delete() }
        }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updatefiles", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun verifyPackage(apk: File) {
        @Suppress("DEPRECATION")
        val candidate = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: error("Не удалось распознать APK")
        check(candidate.packageName == context.packageName) { "APK другого приложения" }
        check(candidate.longVersionCode > BuildConfig.VERSION_CODE.toLong()) {
            "Это не новая версия приложения"
        }
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val currentSigners = installed.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet()
        val futureSigners = candidate.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet()
        check(!currentSigners.isNullOrEmpty() && currentSigners == futureSigners) {
            "APK подписан другим ключом. Для обновления поверх установленного приложения нужен постоянный ключ. Перейдите на GitHub, удалите предыдущую версию NeoCam и установите новый APK."
        }
    }

    private fun readSmallText(address: String): String {
        val connection = openHttps(address)
        try {
            check(connection.responseCode in 200..299 && connection.url.protocol == "https") {
                "Ошибка сети: HTTP " + connection.responseCode
            }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(output.size() + n <= 1_048_576) { "Слишком большой ответ" }
                    output.write(buffer, 0, n)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }

    private fun openHttps(address: String): HttpURLConnection {
        val url = URL(address)
        require(url.protocol == "https") { "Разрешён только HTTPS" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NeoCam/" + BuildConfig.VERSION_NAME)
        }
    }

    private fun version(value: String): List<Int>? =
        Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$").matchEntire(value)?.groupValues
            ?.drop(1)?.map { it.toIntOrNull() ?: 0 }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0..2) {
            val result = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    companion object {
        private const val API_LATEST = "https://api.github.com/repos/DimSimd2020/NeoCam/releases/latest"
        private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
        private const val MAX_BYTES = 300L * 1024L * 1024L
    }
}
