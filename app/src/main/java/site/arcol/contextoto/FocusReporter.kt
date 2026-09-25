package site.arcol.contextoto

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class FocusSubject(val id: Int, val name: String)
data class FocusItem(val id: Int, val subjectId: Int, val name: String, val label: String)
data class FocusCatalog(val subjects: List<FocusSubject>, val items: List<FocusItem>) {
    fun item(subjectId: Int, itemId: Int): FocusItem? =
        items.firstOrNull { it.id == itemId && it.subjectId == subjectId }
}

internal fun focusBaseUrl(raw: String): String {
    val value = raw.trim().trimEnd('/').removeSuffix("/catalog").removeSuffix("/frame")
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri?.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) {
        "上报链接必须是 HTTPS 的目录或帧地址"
    }
    return value
}

internal fun parseFocusCatalog(json: JSONObject): FocusCatalog {
    val subjectsJson = json.optJSONArray("subjects") ?: throw IOException("目录缺少科目")
    val itemsJson = json.optJSONArray("focus_items") ?: throw IOException("目录缺少事项")
    val subjects = (0 until subjectsJson.length()).mapNotNull { index ->
        subjectsJson.optJSONObject(index)?.let { item ->
            val id = item.optInt("id", 0)
            if (id > 0) FocusSubject(id, item.optString("name")) else null
        }
    }.distinctBy { it.id }
    val items = (0 until itemsJson.length()).mapNotNull { index ->
        itemsJson.optJSONObject(index)?.let { item ->
            val id = item.optInt("id", 0)
            val subjectId = item.optInt("subject_id", 0)
            if (id > 0 && subjectId > 0) FocusItem(id, subjectId,
                item.optString("name"), item.optString("label")) else null
        }
    }.filter { item -> subjects.any { it.id == item.subjectId } }.distinctBy { it.id }
    if (subjects.isEmpty() || items.isEmpty()) throw IOException("目录没有可选科目或事项")
    return FocusCatalog(subjects, items)
}

object FocusReporter {
    const val SOURCE = "contextoto"
    private const val HEARTBEAT_MS = 20_000L
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
    private val sessionLock = Mutex()

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion {
            if (it is CancellationException) call.cancel()
        }
        try { call.execute() } finally { cancellation.dispose() }
    }

    suspend fun catalog(rawUrl: String): FocusCatalog {
        val request = Request.Builder().url(focusBaseUrl(rawUrl) + "/catalog")
            .header("Accept", "application/json").build()
        return execute(request).use { response ->
            if (!response.isSuccessful) throw IOException("读取专注目录失败：HTTP ${response.code}")
            parseFocusCatalog(JSONObject(response.body?.string() ?: throw IOException("专注目录为空")))
        }
    }

    private suspend fun frame(base: String, state: String, subjectId: Int = 0, itemId: Int = 0): Int {
        val body = JSONObject().put("source", SOURCE).put("state", state)
        if (state == "focus") {
            body.put("subject_id", subjectId)
            body.put("focus_item_id", itemId)
        }
        val request = Request.Builder().url("$base/frame")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request).use { response ->
            if (BuildConfig.DEBUG) Log.d("ContextoTOFocus", "$state HTTP ${response.code}")
            response.code
        }
    }

    suspend fun track(rawUrl: String, subjectId: Int, itemId: Int, onStatus: (String) -> Unit) =
        sessionLock.withLock {
        val base = focusBaseUrl(rawUrl)
        val catalog = catalog(base)
        val subject = catalog.subjects.firstOrNull { it.id == subjectId }
            ?: throw IOException("所选科目已不在目录中")
        val item = catalog.item(subjectId, itemId)
            ?: throw IOException("所选事项与科目不匹配，请重新选择")
        var accepted = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    when (val code = frame(base, "focus", subjectId, itemId)) {
                        in 200..299 -> {
                            accepted = true
                            onStatus("正在记录 · ${subject.name} / ${item.name}")
                        }
                        409 -> {
                            accepted = false
                            onStatus("专注冲突或今日已结算 · 请在 Dashboard 查看")
                            return
                        }
                        else -> onStatus("上报失败：HTTP $code · 20 秒后重试")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    onStatus("连接中断 · 20 秒后重试")
                }
                delay(HEARTBEAT_MS)
            }
        } finally {
            if (accepted) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { frame(base, "idle") }
            }
        }
        }
}
