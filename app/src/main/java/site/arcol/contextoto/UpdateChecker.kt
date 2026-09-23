package site.arcol.contextoto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(val current: String, val latest: String, val url: String) {
    val available: Boolean get() = compareVersions(latest, current) > 0
}

internal fun compareVersions(left: String, right: String): Int {
    val a = left.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val b = right.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val difference = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (difference != 0) return difference
    }
    return 0
}

object UpdateChecker {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/GYPp1us/ContextoTO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ContextoTO-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("检查更新失败：HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw IOException("版本信息为空"))
            val tag = json.getString("tag_name")
            val url = json.getString("html_url")
            UpdateInfo(BuildConfig.VERSION_NAME, tag, url)
        }
    }
}
