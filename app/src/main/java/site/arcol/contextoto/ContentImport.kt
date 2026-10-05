package site.arcol.contextoto

import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportPreview(val articles: List<Article> = emptyList(), val words: List<Lexeme> = emptyList(),
                         val warnings: List<String> = emptyList(), val suggestedName: String = "导入词库")

object ContentImport {
    const val MAX_TOTAL = 16 * 1024 * 1024
    const val MAX_FILE = 2 * 1024 * 1024
    const val MAX_ENTRIES = 256
    fun parse(name: String, input: InputStream, vocabulary: Boolean): ImportPreview {
        if (!name.endsWith(".zip", true)) return parseText(name, bounded(input, MAX_FILE).toString(Charsets.UTF_8), vocabulary)
        val articles = mutableListOf<Article>(); val words = mutableListOf<Lexeme>(); val warnings = mutableListOf<String>()
        var total = 0; var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "压缩包文件数量超过 $MAX_ENTRIES" }
                require(!entry.name.replace('\\', '/').split('/').contains("..") && !entry.name.startsWith('/') &&
                    !entry.name.contains(':')) { "压缩包包含不安全路径" }
                if (!entry.isDirectory) {
                    val data = bounded(zip, MAX_FILE); total += data.size
                    require(total <= MAX_TOTAL) { "压缩包解压总量超过 16 MB" }
                    val parsed = runCatching { parseText(entry.name.substringAfterLast('/'), data.toString(Charsets.UTF_8), vocabulary) }
                    parsed.onSuccess { articles += it.articles; words += it.words; warnings += it.warnings }
                        .onFailure { warnings += "${entry.name}：${it.message}" }
                }
                zip.closeEntry()
            }
        }
        val merged = words.groupBy { it.word }.map { (_, entries) -> entries.first().copy(translation = entries.map { it.translation }.distinct().joinToString(" / ")) }
        return ImportPreview(articles.distinctBy { it.id }, merged, warnings, name.substringBeforeLast('.'))
    }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= limit) { "单个文件超过 2 MB" }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    fun parseText(name: String, raw: String, vocabulary: Boolean): ImportPreview {
        require(!raw.contains('\uFFFD') && !raw.contains('\u0000')) { "请使用 UTF-8 文本文件" }
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val ext = name.substringAfterLast('.', "").lowercase()
        if (vocabulary) {
            require(ext in setOf("csv", "tsv")) { "词库支持 CSV / TSV" }
            val rows = delimited(text, if (ext == "tsv") '\t' else ',')
            require(rows.isNotEmpty()) { "文件为空" }
            val headers = rows.first().map { it.trim().lowercase() }
            fun column(vararg labels: String) = labels.firstNotNullOfOrNull { label -> headers.indexOf(label).takeIf { it >= 0 } }
            val wordColumn = column("word", "单词") ?: error("缺少 word / 单词 列")
            val meaningColumn = column("translation", "meaning", "释义") ?: error("缺少 translation / 释义 列")
            val warnings = mutableListOf<String>()
            val words = rows.drop(1).mapIndexedNotNull { index, cells ->
                if (cells.all { it.isBlank() }) return@mapIndexedNotNull null
                val word = cells.getOrNull(wordColumn).orEmpty().trim().lowercase(java.util.Locale.US)
                val meaning = cells.getOrNull(meaningColumn).orEmpty().trim()
                if (!Regex("[a-z]+(?:['’-][a-z]+)*").matches(word) || meaning.isBlank()) {
                    warnings += "第 ${index + 2} 行：单词或释义无效"; null
                } else {
                    fun value(vararg names: String) = column(*names)?.let { cells.getOrNull(it) }.orEmpty().trim()
                    val pos = value("pos", "part_of_speech", "词性")
                    Lexeme(word, if (pos.isBlank()) meaning else "${abbreviatePartOfSpeech(pos)} $meaning", value("band").ifBlank { null },
                        value("frequency", "exam_freq").toIntOrNull(), value("rank", "排名").toIntOrNull(), false,
                        value("ipa_uk", "uk"), value("ipa_us", "us"))
                }
            }
            require(words.isNotEmpty()) { "没有有效词条" }
            val duplicates = words.size - words.distinctBy { it.word }.size
            if (duplicates > 0) warnings += "$duplicates 个重复词条将合并释义"
            val merged = words.groupBy { it.word }.map { (_, entries) -> entries.first().copy(translation = entries.map { it.translation }.distinct().joinToString(" / ")) }
            return ImportPreview(words = merged, warnings = warnings, suggestedName = name.substringBeforeLast('.'))
        }
        require(ext in setOf("txt", "md", "markdown")) { "文章支持 TXT / Markdown" }
        val heading = if (ext != "txt") Regex("(?m)^# (.+)$").find(text) else null
        val title = heading?.groupValues?.get(1)?.trim() ?: name.substringBeforeLast('.')
        val body = if (heading != null) text.removeRange(heading.range) else text
        val plain = if (ext == "txt") body else body.replace(Regex("(?m)^#{1,6} +"), "")
            .replace(Regex("!?\\[([^]]+)]\\([^)]+\\)"), "$1").replace("**", "").replace("__", "")
        val paragraphs = plain.split(Regex("\n\\s*\n")).map { it.trim().replace(Regex("\\s*\n\\s*"), " ") }.filter { it.isNotBlank() }
        require(paragraphs.isNotEmpty()) { "没有正文" }
        val id = "import-" + Content.sha256(paragraphs.joinToString("\n\n")).take(20)
        return ImportPreview(articles = listOf(Article(id, "导入", title, paragraphs)),
            warnings = if (ext != "txt" && text.contains("```")) listOf("Markdown 代码块将按纯文本保留，不执行内容") else emptyList())
    }
    /** RFC-style quoted fields, including embedded newlines, commas and doubled quotes. */
    fun delimited(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var index = 0
        fun finishCell() { row += cell.toString(); cell.setLength(0) }
        while (index < text.length) {
            val c = text[index++]
            when {
                c == '"' && quoted && index < text.length && text[index] == '"' -> { cell.append('"'); index++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> finishCell()
                c == '\n' && !quoted -> { finishCell(); rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
        }
        require(!quoted) { "CSV 引号没有闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty()) { finishCell(); rows += row }
        return rows
    }
}
