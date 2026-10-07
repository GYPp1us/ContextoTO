package site.arcol.contextoto

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AnalysisTransferOverlay(content: Content, store: UserStore, engine: AnalysisEngine, provider: Provider,
                                     current: Article, colors: Palette, top: Dp, onClose: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<AnalysisBundle?>(null) }
    var targets by remember { mutableStateOf(listOf(current)) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use {
                    AnalysisArchive.export(content, engine, provider, targets, it)
                } ?: error("无法写入文件") }
                status = "已导出 ${targets.size} 篇文章及已有解析，不含任何学习记录或配置。"
            } catch (failure: Exception) { status = failure.message ?: "导出失败" }
            finally { working = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true; plan = null
            try {
                plan = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use(AnalysisArchive::read) ?: error("无法读取解析包") }
                status = "校验通过；确认后仅补缺，不覆盖已有有效解析。"
            } catch (failure: Exception) { status = failure.message ?: "读取失败" }
            finally { working = false }
        }
    }
    BackHandler(true) { if (!working) onClose() }
    GlassScrim(colors) { if (!working) onClose() }
    Column(Modifier.fillMaxSize().padding(top = top + 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
        RevealElement(0, "archive") { UiHeading("ARTICLES & ANALYSES", colors, size = 25) }
        RevealElement(1, "archive") { Text("ZIP 解析包 · 可跨设备导入", color = colors.word, fontSize = 13.sp, modifier = Modifier.padding(top = 15.dp)) }
        Text("只包含文章、词形与解析。复习队列、评分、纠错、热度、书签、阅读位置、查询历史、密钥和专注配置均不导出。", color = colors.muted,
            fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 18.dp, bottom = 22.dp))
        if (working) ProgressBlock(null, colors, "本地解析包处理 · 不请求模型")
        JumpLink("导出当前文章", colors, Modifier.fillMaxWidth(), enabled = !working) {
            targets = listOf(current); exporter.launch("ContextoTO-${current.id}-analyses.zip")
        }
        JumpLink("导出全部文章", colors, Modifier.fillMaxWidth(), enabled = !working) {
            targets = content.articles.toList(); exporter.launch("ContextoTO-all-analyses.zip")
        }
        JumpLink("导入解析包", colors, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !working) {
            importer.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        if (status.isNotBlank()) Text(status, color = colors.word, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 18.dp))
        plan?.let { bundle ->
            UiHeading("PREVIEW", colors, size = 15)
            Text("${bundle.articles.length()} 篇 · ${bundle.sentenceCount} 句解析 · ${bundle.wordCount} 词位", color = colors.muted, fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 15.dp))
            for (i in 0 until bundle.articles.length()) Text(bundle.articles.getJSONObject(i).getString("title"), color = colors.ink,
                fontFamily = ReadingFont, fontSize = 19.sp, modifier = Modifier.padding(bottom = 14.dp))
            JumpLink("确认补缺导入", colors, Modifier.fillMaxWidth(), enabled = !working, size = 17) {
                scope.launch {
                    working = true
                    try {
                        val result = withContext(Dispatchers.IO) { AnalysisArchive.import(bundle, content, store) }
                        engine.notifyImportedCaches(); plan = null
                        status = "已加入 ${result.first} 篇新文章，合并 ${result.second} 条解析；原有数据保留。"
                    } catch (failure: Exception) { status = failure.message ?: "导入失败，未提交内容" }
                    finally { working = false }
                }
            }
        }
        JumpLink("返回", colors, Modifier.fillMaxWidth().padding(vertical = 24.dp), enabled = !working, onClick = onClose)
    }
}
