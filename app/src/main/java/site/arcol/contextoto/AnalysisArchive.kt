package site.arcol.contextoto

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class AnalysisBundle(val articles: JSONArray, val wordCount: Int, val sentenceCount: Int)

/** Portable whitelisted records, not a private database backup. ZIP paths are never extracted. */
internal object AnalysisArchive {
    const val MAX_BYTES = 32 * 1024 * 1024
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun read(stream: InputStream): AnalysisBundle {
        val files = mutableMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.json", "analyses.json") && entry.name !in files) { "不是合法解析包，或包含异常 ZIP 路径" }
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer); if (count < 0) break
                    total += count; require(total <= MAX_BYTES) { "解析包解压超过 32 MB，请分批导入" }
                    bytes.write(buffer, 0, count)
                }
                files[entry.name] = bytes.toByteArray()
            }
        }
        val manifest = JSONObject(files["manifest.json"]?.toString(Charsets.UTF_8) ?: error("缺少清单"))
        require(manifest.optString("format") == "contextoto-analysis-zip" && manifest.optInt("version") == 1) { "解析包版本不支持" }
        val bytes = files["analyses.json"] ?: error("缺少解析数据")
        require(digest(bytes) == manifest.optString("sha256")) { "解析包校验失败" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == "contextoto-analysis" && root.optInt("version") == 1) { "解析结构不支持" }
        val articles = root.getJSONArray("articles"); require(articles.length() in 1..256) { "文章数量超限" }
        var words = 0; var sentences = 0
        for (i in 0 until articles.length()) {
            val article = articles.getJSONObject(i); require(article.getString("title").length in 1..10000) { "标题无效" }
            val paragraphs = article.getJSONArray("paragraphs"); require(paragraphs.length() in 1..1024) { "段落数量无效" }
            for (p in 0 until paragraphs.length()) require(paragraphs.getString(p).length in 1..500000) { "正文长度无效" }
            words += article.optJSONArray("words")?.length() ?: 0
            sentences += article.optJSONArray("sentences")?.length() ?: 0
        }
        require(words + sentences <= 100000) { "解析条目数量超限" }
        return AnalysisBundle(articles, words, sentences)
    }

    fun export(content: Content, engine: AnalysisEngine, provider: Provider, articles: List<Article>, stream: OutputStream) {
        val data = JSONArray(articles.map { article ->
            val words = JSONArray(); val sentences = JSONArray()
            (listOf(article.title) + article.paragraphs).forEachIndexed { index, text ->
                Content.tokens(text).forEach { token -> engine.archiveWord(provider, text, token)?.let {
                    words.put(JSONObject().put("paragraph", index - 1).put("start", token.start).put("end", token.end).put("text", token.text).put("analysis", it))
                } }
                Content.sentences(text).forEach { sentence -> engine.cachedSentence(provider, sentence.text)?.let {
                    val safe = JSONObject(); listOf("translation_zh", "clauses", "glosses").forEach { field ->
                        if (field == "translation_zh" && it.optString(field).isNotBlank()) safe.put(field, it.getString(field))
                        else it.optJSONArray(field)?.let { array -> safe.put(field, checkedRanges(array, sentence.text)) }
                    }
                    sentences.put(JSONObject().put("paragraph", index - 1).put("start", sentence.start).put("text", sentence.text).put("analysis", safe))
                } }
            }
            JSONObject().put("id", article.id).put("kind", article.kind).put("title", article.title).put("paragraphs", JSONArray(article.paragraphs))
                .put("words", words).put("sentences", sentences)
        })
        val bytes = JSONObject().put("format", "contextoto-analysis").put("version", 1).put("articles", data).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size < MAX_BYTES - 4096) { "解析超过 32 MB，请选择当前文章分批导出" }
        val manifest = JSONObject().put("format", "contextoto-analysis-zip").put("version", 1).put("sha256", digest(bytes))
        ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("analyses.json")); zip.write(bytes); zip.closeEntry()
        }
    }

    fun import(bundle: AnalysisBundle, content: Content, store: UserStore, seedHash: String? = null): Pair<Int, Int> {
        val additions = mutableListOf<JSONObject>(); val records = mutableListOf<AnalysisRecord>()
        fun wordKey(kind: String, source: String) = Content.sha256("v3|$kind|$source")
        for (i in 0 until bundle.articles.length()) {
            val source = bundle.articles.getJSONObject(i); val paragraphs = source.getJSONArray("paragraphs").let { a -> (0 until a.length()).map(a::getString) }
            val title = source.getString("title")
            val existing = content.articles.firstOrNull { it.paragraphs == paragraphs }
            val article = existing ?: Article("local-${Content.sha256(paragraphs.joinToString("\n\n")).take(24)}", "导入", title, paragraphs)
            if (existing == null) additions += JSONObject().put("id", article.id).put("title", article.title).put("paragraphs", JSONArray(paragraphs))
            fun paragraph(index: Int) = if (index == -1) article.title else article.paragraphs.getOrNull(index) ?: error("解析段落超出范围")
            val words = source.optJSONArray("words") ?: JSONArray()
            for (w in 0 until words.length()) {
                val word = words.getJSONObject(w); val p = word.getInt("paragraph")
                if (p == -1 && title != article.title) continue
                val text = paragraph(p); val begin = word.getInt("start"); val end = word.getInt("end"); val surface = word.getString("text")
                require(begin >= 0 && end <= text.length && end > begin && text.substring(begin, end) == surface && Content.tokenAt(text, begin)?.end == end) { "解析词位与文章不匹配" }
                val sentence = Content.sentenceAt(text, begin); val analysis = word.getJSONObject("analysis")
                val lemma = analysis.optString("lemma", surface.lowercase())
                require(lemma.matches(Regex("[a-z]+(?:[-'’][a-z]+)*"))) { "解析原型无效" }
                val identityKey = Content.sha256("v5|word-identity|${surface.lowercase()}|${sentence.text}|${begin - sentence.start}")
                val identity = analysis.optJSONObject("lexical_identity") ?: JSONObject().put("lemma", lemma).put("form", analysis.optString("form"))
                require(identity.optString("lemma") == lemma) { "原型模块与词元不匹配" }
                records += AnalysisRecord(identityKey, "word-identity", decodeModule(QueryModule.IDENTITY, identity, surface))
                analysis.optJSONObject("context_sense")?.let { value ->
                    val key = Content.sha256("v5|word-context|${surface.lowercase()}|${sentence.text}|${begin - sentence.start}")
                    records += AnalysisRecord(key, "word-context", decodeModule(QueryModule.CONTEXT, value, sentence.text))
                    records += AnalysisRecord(wordKey("word-context-module", "$lemma|${sentence.text}|${begin - sentence.start}"), "word-context", decodeModule(QueryModule.CONTEXT, value, sentence.text))
                }
                val common = JSONObject()
                if (analysis.has("common_senses")) common.put("common_senses", if (analysis.getJSONArray("common_senses").length() == 0) JSONArray()
                    else decodeModule(QueryModule.SENSES, analysis, lemma).getJSONArray("common_senses"))
                if (analysis.has("derivatives")) common.put("derivatives", decodeModule(QueryModule.DERIVATIVES, analysis, lemma, lemma).getJSONArray("derivatives"))
                if (common.length() > 0) records += AnalysisRecord(wordKey("word-common", lemma), "word-common", common)
                analysis.optJSONObject("phonetics")?.let { value ->
                    val checked = JSONObject(); listOf("uk", "us").forEach { accent -> value.optString(accent).takeIf { it.isNotBlank() }?.let { ipa ->
                        require(ipa.matches(Regex("/.+/")) && ipa.length < 160) { "音标无效" }; checked.put(accent, ipa)
                    } }
                    if (checked.length() > 0) records += AnalysisRecord(wordKey("word-pronunciation", surface.lowercase()), "word-pronunciation", JSONObject().put("phonetics", checked))
                }
            }
            val sentences = source.optJSONArray("sentences") ?: JSONArray()
            for (s in 0 until sentences.length()) {
                val value = sentences.getJSONObject(s); val p = value.getInt("paragraph")
                if (p == -1 && title != article.title) continue
                val text = paragraph(p); val start = value.getInt("start"); val quote = value.getString("text")
                require(start >= 0 && start + quote.length <= text.length && text.substring(start, start + quote.length) == quote) { "解析句子不匹配" }
                val raw = value.getJSONObject("analysis"); val checked = JSONObject()
                if (raw.has("translation_zh")) checked.put("translation_zh", decodeModule(QueryModule.TRANSLATION, raw, quote).getString("translation_zh"))
                if (raw.has("clauses")) checked.put("clauses", decodeModule(QueryModule.CLAUSES, raw, quote).getJSONArray("clauses"))
                raw.optJSONArray("glosses")?.let { glosses ->
                    val valid = checkedRanges(glosses, quote); require(valid.length() == glosses.length()) { "词汇讲解范围无效" }; checked.put("glosses", valid)
                }
                records += AnalysisRecord(Content.sha256("v4|sentence|$quote"), "sentence", checked)
            }
        }
        store.applyAnalysisArchive(additions, records, seedHash)
        LearningStore(store, content).refreshContent(); store.bumpLearningRevision()
        return additions.size to records.size
    }

    fun installPreset(context: android.content.Context, content: Content, store: UserStore) {
        if ("first_two_analyses.zip" !in context.assets.list("").orEmpty()) return
        val bytes = context.assets.open("first_two_analyses.zip").use { it.readBytes() }; val hash = digest(bytes)
        if (!store.hasSeed(hash)) import(read(ByteArrayInputStream(bytes)), content, store, hash)
    }
}
