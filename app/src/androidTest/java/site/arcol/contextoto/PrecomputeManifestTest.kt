package site.arcol.contextoto

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class PrecomputeManifestTest {
    @Test fun exportNativeSentenceManifestOnlyWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("exportPrecomputeManifest") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val content = Content(context)
        val articles = JSONArray(content.articles.take(2).map { article ->
            val paragraphs = listOf(article.title) + article.paragraphs
            JSONObject().put("id", article.id).put("title", article.title).put("kind", article.kind).put("paragraphs", JSONArray(article.paragraphs))
                .put("segments", JSONArray(paragraphs.flatMapIndexed { i, text ->
                    Content.sentences(text).map { sentence ->
                        JSONObject().put("paragraph", i - 1).put("start", sentence.start).put("end", sentence.end).put("text", sentence.text)
                            .put("tokens", JSONArray(Content.tokens(text).filter { it.start >= sentence.start && it.end <= sentence.end }.map { token ->
                                JSONObject().put("text", token.text).put("start", token.start).put("end", token.end)
                                    .put("local_identity", content.localIdentity(token.text) ?: JSONObject.NULL)
                            }))
                    }
                }))
        })
        java.io.File(context.getExternalFilesDir(null), "rc6-precompute-manifest.json").writeText(JSONObject().put("articles", articles).toString())
    }
}
