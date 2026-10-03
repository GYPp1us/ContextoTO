package site.arcol.contextoto

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun bothProtocolsUseTheSameVersionedModelEndpoint() {
        assertEquals("https://example.com/v1/models", modelListUrl("https://example.com"))
        assertEquals("https://example.com/v1/models", modelListUrl("https://example.com/v1/"))
        assertEquals("https://example.com/v1/models", modelListUrl("https://example.com/v1/responses"))
        assertEquals("https://example.com/v1/models", modelListUrl("https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/v1/models", modelListUrl("https://example.com/v1/model"))
        assertEquals("https://example.com/provider/v1/models", modelListUrl("https://example.com/provider/v1"))
        assertEquals("https://example.com/v1/responses", apiUrl("https://example.com/v1/chat/completions", "responses"))
    }
    @Test fun endpointCredentialsAndInvalidQueriesAreRejected() {
        listOf("http://example.com/v1", "https://key@example.com/v1", "https://example.com/v1?key=secret").forEach {
            assertTrue(runCatching { modelListUrl(it) }.isFailure)
        }
    }
    @Test fun catalogPreservesIdsEffortMetadataAndDeduplicates() {
        val models = parseModelCatalog(JSONObject("""{"data":[{"id":"z-model"},{"id":"a-model","name":"A","effort":{"supported_levels":["low","high","max"],"default_level":"high"}},{"id":"a-model"},{"id":null}]}"""))
        assertEquals(listOf("a-model", "z-model"), models.map { it.id })
        assertEquals("A", models.first().name)
        assertEquals(listOf("low", "high", "max"), models.first().supportedEfforts)
        assertEquals(models, parseModelCatalog(modelCatalogJson(models)))
        assertTrue(runCatching { parseModelCatalog(JSONObject("""{"error":"bad"}""")) }.isFailure)
    }
    @Test fun realHttpsGetUsesBearerAndReportsAuthFailureWithoutEchoingKey(): Unit = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCert.sslSocketFactory(), false)
            server.start()
            val client = ModelCatalogClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .followRedirects(false).build())
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"test-model"}]}"""))
            val result = client.fetch(server.url("/v1").toString(), "fixture-token")
            assertEquals("test-model", result.single().id)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"fixture-token"}"""))
            val error = runCatching { client.fetch(server.url("/v1").toString(), "fixture-token") }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("鉴权"))
            assertFalse(error.message!!.contains("fixture-token"))
        }
    }
    @Test fun declaredEffortLevelsAreHonoured() {
        val provider = Provider("DeepSeek 官方", "https://api.deepseek.com", "deepseek-flash", "",
            listOf("low", "high", "max"), "high")
        assertEquals("high", modelEffort(provider, "medium"))
        assertEquals("max", modelEffort(provider, "max"))
    }
}
