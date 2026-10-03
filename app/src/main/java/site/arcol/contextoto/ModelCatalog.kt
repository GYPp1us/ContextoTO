package site.arcol.contextoto

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ModelOption(val id: String, val name: String = id, val owner: String = "",
                       val supportedEfforts: List<String> = emptyList(), val defaultEffort: String = "")

internal fun providerBaseUrl(name: String, custom: String): String = when (name) {
    "DeepSeek 官方" -> "https://api.deepseek.com"
    "Command Code GOAT" -> "https://api.commandcode.ai/provider/v1"
    else -> custom.trim().trimEnd('/')
}

internal fun modelListUrl(baseUrl: String): String {
    return apiUrl(baseUrl, "models")
}

internal fun apiUrl(baseUrl: String, resource: String): String {
    val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw IllegalArgumentException("请填写有效的 HTTPS API 端点")
    require(url.isHttps && url.username.isBlank() && url.password.isBlank() && url.query == null && url.fragment == null) {
        "API 端点须为不含账号、查询参数或片段的 HTTPS 地址"
    }
    var path = url.encodedPath.trimEnd('/')
    listOf("/chat/completions", "/responses", "/models", "/model").firstOrNull { path.endsWith(it) }
        ?.let { path = path.removeSuffix(it) }
    if (path.isBlank()) path = "/v1"
    return url.newBuilder().encodedPath("$path/$resource").build().toString()
}

internal fun parseModelCatalog(json: JSONObject): List<ModelOption> {
    val items = json.optJSONArray("data") ?: throw IllegalArgumentException("端点返回的模型列表格式不正确")
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = if (item.isNull("id")) "" else item.optString("id").trim()
        if (id.isBlank()) return@mapNotNull null
        val effort = item.optJSONObject("effort")
        val levels = effort?.optJSONArray("supported_levels")
        ModelOption(id, item.optString("name").takeUnless { it.isBlank() || it == "null" } ?: id,
            item.optString("owned_by").takeUnless { it == "null" }.orEmpty(),
            (0 until (levels?.length() ?: 0)).mapNotNull { levels?.optString(it)?.takeIf { value -> value.isNotBlank() && value != "null" } },
            effort?.optString("default_level").orEmpty())
    }.distinctBy { it.id }.sortedBy { it.id.lowercase(java.util.Locale.US) }
}

internal fun modelCatalogJson(models: List<ModelOption>): JSONObject = JSONObject().put("data", JSONArray(models.map {
    JSONObject().put("id", it.id).put("name", it.name).put("owned_by", it.owner)
        .put("effort", JSONObject().put("supported_levels", JSONArray(it.supportedEfforts)).put("default_level", it.defaultEffort))
}))

/** Discovery reads the same base URL and bearer credential as chat; it never submits a completion. */
class ModelCatalogClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()) {
    suspend fun fetch(baseUrl: String, key: String): List<ModelOption> {
        require(key.isNotBlank()) { "请填写 API Key，或使用已保存的密钥" }
        val request = Request.Builder().url(modelListUrl(baseUrl))
            .header("Authorization", "Bearer ${key.trim()}").header("Accept", "application/json").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("模型目录暂时无法连接，请重试", error))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val models = response.use { http ->
                            if (!http.isSuccessful) throw IOException(when (http.code) {
                                401, 403 -> "模型目录鉴权失败，请检查 API Key"
                                404, 405 -> "此端点不提供模型目录，可手动填写模型 ID"
                                else -> "模型目录返回 HTTP ${http.code}，请稍后重试"
                            })
                            val body = http.body ?: throw IOException("端点未返回模型目录")
                            if (body.contentLength() > 2 * 1024 * 1024) throw IOException("模型目录过大")
                            val source = body.source()
                            source.request(2L * 1024 * 1024 + 1)
                            if (source.buffer.size > 2L * 1024 * 1024) throw IOException("模型目录过大")
                            parseModelCatalog(JSONObject(source.readUtf8()))
                        }
                        if (continuation.isActive) continuation.resume(models)
                    } catch (error: Exception) {
                        val readable = if (error is org.json.JSONException) IOException("端点返回的模型列表格式不正确") else error
                        if (continuation.isActive) continuation.resumeWithException(readable)
                    }
                }
            })
        }
    }
}

internal fun modelEffort(provider: Provider, requested: String): String = when {
    provider.supportedEfforts.isEmpty() -> if (provider.official && requested == "medium") "high" else requested
    requested in provider.supportedEfforts -> requested
    provider.defaultEffort in provider.supportedEfforts -> provider.defaultEffort
    "high" in provider.supportedEfforts -> "high"
    else -> provider.supportedEfforts.first()
}
