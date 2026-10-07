package site.arcol.contextoto

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object WordBankCsv {
    fun encode(bank: WordBank): String {
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        return buildString {
            append("word,translation,ipa_uk,ipa_us,rank\n")
            bank.words.values.sortedBy { it.word }.forEach { entry ->
                append(listOf(entry.word, entry.translation, entry.ipaUk, entry.ipaUs, entry.rank?.toString().orEmpty()).joinToString(",", transform = ::quote))
                append('\n')
            }
        }
    }
}

@Composable
internal fun BankManagementSection(bank: WordBank, content: Content, learning: LearningStore, colors: Palette, onMessage: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember(bank.id) { mutableStateOf("") }
    var name by remember(bank.id, bank.name) { mutableStateOf(bank.name) }
    var choosingMerge by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(WordBankCsv.encode(bank)) } ?: error("无法写入词库") }
                onMessage("已导出 ${bank.words.size} 个词，不包含学习记录")
            } catch (failure: Exception) { onMessage(failure.message ?: "导出失败") }
            finally { working = false }
        }
    }
    JumpLink(if (expanded) "收起词库管理" else "展开词库管理", colors, Modifier.fillMaxWidth(), size = 15) { expanded = !expanded }
    RetainedFoldout(expanded, "bank-management") {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            UiHeading("WORD BANK", colors, size = 17)
            Text(if (bank.id in setOf("builtin", "general")) "预置库 · 只读" else "自定义库 · 本地保存", color = colors.word, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
            if (bank.id == "general") Text("ECDICT · MIT · 10,000 个独立高频词元；以原数据标注音标与词形来源。", color = colors.muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp))
            SettingInput("搜索词条或中文释义", search, colors, onChange = { search = it })
            val all = remember(bank, search) { bank.words.values.filter { search.isBlank() || it.word.contains(search, true) || it.translation.contains(search) }.sortedBy { it.word } }
            Text("${all.size} 个匹配 · 展示前 80 项", color = colors.muted, fontSize = 11.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(vertical = 12.dp)) {
                items(all.take(80), key = { it.word }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(entry.word, color = colors.ink, fontFamily = ReadingFont, fontSize = 19.sp)
                        Text(entry.translation.replace('\n', ' ').take(140), color = colors.muted, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            JumpLink(if (working) "正在处理…" else "导出当前词库 · CSV", colors, Modifier.fillMaxWidth(), enabled = !working) {
                exporter.launch("ContextoTO-${bank.id.take(36)}-words.csv")
            }
            if (bank.id !in setOf("builtin", "general")) {
                SettingInput("词库名称", name, colors, onChange = { name = it })
                JumpLink("保存名称", colors, Modifier.fillMaxWidth(), enabled = !working && name.isNotBlank()) {
                    learning.renameBank(bank.id, name); onMessage("词库已重命名")
                }
                JumpLink("合并到另一个自定义库", colors, Modifier.fillMaxWidth(), enabled = !working) { choosingMerge = !choosingMerge }
                RetainedFoldout(choosingMerge, "bank-merge") { Column {
                    val choices = content.banks.filter { it.id != bank.id && it.id !in setOf("builtin", "general") }
                    if (choices.isEmpty()) Text("请先创建另一个自定义词库", color = colors.muted, fontSize = 12.sp)
                    choices.forEach { target -> JumpLink(target.name, colors, Modifier.fillMaxWidth(), enabled = !working) {
                        scope.launch {
                            working = true
                            try { val count = withContext(Dispatchers.IO) { learning.mergeBank(bank.id, target.id) }
                                choosingMerge = false; onMessage("已合并 $count 个新词到 ${target.name}，原库保留")
                            } catch (failure: Exception) { onMessage(failure.message ?: "合并失败") }
                            finally { working = false }
                        }
                    } }
                } }
                JumpLink("删除当前词库", colors, Modifier.fillMaxWidth(), enabled = !working) { deleting = !deleting }
                RetainedFoldout(deleting, "bank-delete") { Column {
                    Text("只移除这个词库。已加入的词、释义快照、复习记录、缓存和热度保留。", color = colors.paragraph, fontSize = 12.sp, lineHeight = 20.sp)
                    JumpLink("确认移除词库", colors, Modifier.fillMaxWidth(), enabled = !working) {
                        learning.deleteBank(bank.id); deleting = false; onMessage("已移除词库，学习数据和释义快照保留")
                    }
                } }
            }
        }
    }
}
