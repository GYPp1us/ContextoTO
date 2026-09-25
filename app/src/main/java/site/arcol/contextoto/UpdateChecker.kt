package site.arcol.contextoto

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val current: String, val latest: String, val url: String,
    val apkUrl: String = "", val sha256: String = ""
) {
    val available: Boolean get() = compareVersions(latest, current) > 0
}

enum class DownloadPhase { CONNECTING, RECEIVING, VERIFYING, READY }
data class DownloadProgress(val phase: DownloadPhase, val bytes: Long = 0, val total: Long = -1) {
    val fraction: Float get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

internal fun compareVersions(left: String, right: String): Int {
    val leftVersion = left.removePrefix("v")
    val rightVersion = right.removePrefix("v")
    val a = leftVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val b = rightVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    val leftPre = leftVersion.substringAfter('-', "")
    val rightPre = rightVersion.substringAfter('-', "")
    if (leftPre.isBlank() || rightPre.isBlank()) return when {
        leftPre.isBlank() && rightPre.isNotBlank() -> 1
        rightPre.isBlank() && leftPre.isNotBlank() -> -1
        else -> 0
    }
    val pattern = Regex("([a-zA-Z]+)([0-9]+)")
    val leftMatch = pattern.matchEntire(leftPre)
    val rightMatch = pattern.matchEntire(rightPre)
    if (leftMatch != null && rightMatch != null) {
        val stage = leftMatch.groupValues[1].compareTo(rightMatch.groupValues[1])
        if (stage != 0) return stage
        return leftMatch.groupValues[2].toInt().compareTo(rightMatch.groupValues[2].toInt())
    }
    return leftPre.compareTo(rightPre)
}

internal fun parseRelease(json: JSONObject, current: String): UpdateInfo {
    val tag = json.getString("tag_name")
    val page = json.getString("html_url")
    val assets = json.optJSONArray("assets")
    val asset = (0 until (assets?.length() ?: 0))
        .mapNotNull { assets?.optJSONObject(it) }
        .firstOrNull { it.optString("name") == "app-release.apk" }
    val apkUrl = asset?.optString("browser_download_url").orEmpty()
    val digest = asset?.optString("digest").orEmpty()
    val sha256 = digest.removePrefix("sha256:")
    if (compareVersions(tag, current) > 0) {
        if (!apkUrl.startsWith("https://github.com/GYPp1us/ContextoTO/releases/download/") ||
            !apkUrl.endsWith("/app-release.apk")
        ) throw IOException("正式版缺少可下载的 APK")
        if (!digest.startsWith("sha256:") || !sha256.matches(Regex("[0-9a-fA-F]{64}")))
            throw IOException("发布信息缺少 APK 校验值")
    }
    return UpdateInfo(current, tag, page, apkUrl, sha256)
}

internal fun selectRelease(json: JSONArray, current: String): UpdateInfo {
    val latest = (0 until json.length()).mapNotNull { json.optJSONObject(it) }
        .filter { !it.optBoolean("draft") }
        .maxWithOrNull { a, b -> compareVersions(a.optString("tag_name"), b.optString("tag_name")) }
        ?: throw IOException("暂未找到发布版本")
    return parseRelease(latest, current)
}

object UpdateChecker {
    private const val PART_NAME = "contextoto-update.part"
    private const val APK_NAME = "contextoto-update.apk"
    private const val MAX_APK_SIZE = 250L * 1024 * 1024
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build()
    private val downloadLock = Mutex()

    private fun directory(context: Context): File = File(context.cacheDir, "updates")
    fun cleanupInterrupted(context: Context) {
        File(directory(context), PART_NAME).delete()
    }
    fun discardReady(context: Context) {
        File(directory(context), APK_NAME).delete()
    }

    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/GYPp1us/ContextoTO/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ContextoTO-Android/${BuildConfig.VERSION_NAME}")
            .build()
        val call = client.newCall(request)
        val cancel = currentCoroutineContext().job.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("检查更新失败：HTTP ${response.code}")
                selectRelease(JSONArray(response.body?.string() ?: throw IOException("版本信息为空")),
                    BuildConfig.VERSION_NAME)
            }
        } finally {
            cancel.dispose()
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (DownloadProgress) -> Unit): File =
        downloadLock.withLock { withContext(Dispatchers.IO) {
            require(info.available && info.apkUrl.isNotBlank()) { "没有可下载的更新" }
            val folder = directory(context)
            if (!folder.exists() && !folder.mkdirs()) throw IOException("无法创建下载目录")
            val partial = File(folder, PART_NAME)
            val ready = File(folder, APK_NAME)
            partial.delete()
            ready.delete()
            val request = Request.Builder().url(info.apkUrl)
                .header("User-Agent", "ContextoTO-Android/${BuildConfig.VERSION_NAME}").build()
            val call = client.newCall(request)
            val cancel = currentCoroutineContext().job.invokeOnCompletion { if (it is CancellationException) call.cancel() }
            var finished = false
            try {
                withContext(Dispatchers.Main) { onProgress(DownloadProgress(DownloadPhase.CONNECTING)) }
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
                    val body = response.body ?: throw IOException("下载内容为空")
                    val total = body.contentLength()
                    if (total > MAX_APK_SIZE) throw IOException("安装包超过大小限制")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    var lastEmission = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                if (bytes > MAX_APK_SIZE) throw IOException("安装包超过大小限制")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val now = System.currentTimeMillis()
                                if (now - lastEmission >= 100) {
                                    withContext(Dispatchers.Main) {
                                        onProgress(DownloadProgress(DownloadPhase.RECEIVING, bytes, total))
                                    }
                                    lastEmission = now
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.Main) {
                        onProgress(DownloadProgress(DownloadPhase.VERIFYING, bytes, total))
                    }
                    if (bytes == 0L || (total >= 0 && bytes != total)) throw IOException("安装包下载不完整")
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(info.sha256, ignoreCase = true)) throw IOException("安装包校验失败，请重试")
                    currentCoroutineContext().ensureActive()
                    if (!partial.renameTo(ready)) throw IOException("无法保存安装包")
                    withContext(Dispatchers.Main) {
                        onProgress(DownloadProgress(DownloadPhase.READY, bytes, bytes))
                    }
                    finished = true
                    ready
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally {
                cancel.dispose()
                partial.delete()
                if (!finished) ready.delete()
            }
        } }
}
