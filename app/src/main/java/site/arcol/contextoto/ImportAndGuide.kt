package site.arcol.contextoto

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ImportOverlay(vocabulary: Boolean, content: Content, learning: LearningStore, colors: Palette, top: Dp,
                           onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var bankId by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) { onClose(); return@rememberLauncherForActivityResult }
        parsing = true
        scope.launch {
            try {
                preview = withContext(Dispatchers.IO) {
                    require(uris.size <= ContentImport.MAX_ENTRIES) { "一次最多选择 256 个文件" }
                    var size = 0
                    val previews = uris.map { uri ->
                        val filename = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else "import.txt"
                        } ?: "import.txt"
                        val parsed = context.contentResolver.openInputStream(uri)?.use { ContentImport.parse(filename, it, vocabulary) }
                            ?: error("无法读取 $filename")
                        size += parsed.articles.sumOf { article -> article.paragraphs.sumOf { it.toByteArray(Charsets.UTF_8).size } } +
                            parsed.words.sumOf { it.translation.toByteArray(Charsets.UTF_8).size + it.word.length }
                        require(size <= ContentImport.MAX_TOTAL) { "本次导入内容超过 16 MB" }
                        parsed
                    }
                    ImportPreview(previews.flatMap { it.articles }, previews.flatMap { it.words }, previews.flatMap { it.warnings },
                        previews.firstOrNull()?.suggestedName ?: "导入词库")
                }
                name = if (vocabulary) preview!!.suggestedName else preview!!.articles.singleOrNull()?.title.orEmpty()
            } catch (failure: Exception) { error = failure.message ?: "解析失败" }
            finally { parsing = false }
        }
    }
    LaunchedEffect(Unit) { launcher.launch(arrayOf("text/*", "application/zip", "application/octet-stream")) }
    BackHandler(!saving) { onClose() }
    GlassScrim(colors) { if (!saving) onClose() }
    Column(Modifier.fillMaxSize().padding(top = top + 28.dp).navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 28.dp, vertical = 20.dp)) {
        RevealElement(0, vocabulary) { UiHeading(if (vocabulary) "IMPORT WORDS" else "IMPORT READING", colors, size = 34) }
        Spacer(Modifier.height(25.dp))
        if (parsing || saving) ProgressBlock(null, colors, if (saving) "正在保存" else "本地解析 · 不调用模型")
        error?.let { Text(it, color = colors.paragraph, fontSize = 15.sp, modifier = Modifier.padding(vertical = 18.dp)) }
        result?.let { Text(it, color = colors.word, fontSize = 18.sp, modifier = Modifier.padding(vertical = 18.dp)) }
        val plan = preview
        if (plan != null && result == null && !saving) {
            RevealElement(1, plan) { Text(if (vocabulary) "${plan.words.map { it.word }.distinct().size} 个词元" else "${plan.articles.size} 篇文章",
                color = colors.word, fontSize = 15.sp) }
            if (vocabulary || plan.articles.size == 1) SettingInput(if (vocabulary) "词库名称" else "文章标题", name, colors, onChange = { name = it })
            if (vocabulary) {
                UiHeading("DESTINATION", colors, Modifier.padding(top = 20.dp), 12)
                Text("${if (bankId == null) "●" else "○"} 新建词库", Modifier.fillMaxWidth().clickable { bankId = null }.padding(vertical = 13.dp), color = colors.word, fontSize = 14.sp)
                content.banks.filter { it.id !in setOf("builtin", "general") }.forEach { bank ->
                    Text("${if (bankId == bank.id) "●" else "○"} 合并到 ${bank.name}", Modifier.fillMaxWidth().clickable { bankId = bank.id; name = bank.name }.padding(vertical = 12.dp), color = colors.ink, fontSize = 14.sp)
                }
                Text("词库不会自动进入复习；同词多库保留各自释义。", color = colors.muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
            plan.articles.forEachIndexed { i, article -> RevealElement(i + 2, article.id) {
                Column(Modifier.padding(top = 22.dp)) {
                    Text(article.title, color = colors.ink, fontFamily = ReadingFont, fontSize = 23.sp)
                    Text("${article.paragraphs.size} 段 · ${if (content.articles.any { it.id == article.id }) "重复，跳过" else "新文章"}", color = colors.word, fontSize = 11.sp)
                    Text(article.paragraphs.first().take(360), color = colors.muted, fontFamily = ReadingFont, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                }
            } }
            plan.words.take(8).forEachIndexed { i, word -> RevealElement(i + 2, word.word) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(word.word, color = colors.ink, fontFamily = ReadingFont, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Text(word.translation, color = colors.muted, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            } }
            if (plan.words.size > 8) Text("另有 ${plan.words.size - 8} 个词", color = colors.muted, fontSize = 12.sp)
            plan.warnings.forEach { warning -> Text(warning, color = colors.paragraph, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            JumpLink(if (plan.warnings.isEmpty()) "确认导入" else "确认有效内容并跳过异常", colors,
                Modifier.fillMaxWidth().padding(vertical = 28.dp), size = 17, enabled = !saving) {
                    saving = true
                    scope.launch {
                        try {
                            val counts = withContext(Dispatchers.IO) { learning.commitImport(plan, name, bankId) }
                            result = "已导入 ${counts.first} 篇文章、${counts.second} 个词元；原有内容和学习记录已保留。"
                        } catch (failure: Exception) { error = failure.message ?: "保存失败，未提交内容" }
                        finally { saving = false }
                    }
                }
        }
        if (!saving) JumpLink(if (result == null) "取消" else "完成", colors, Modifier.padding(vertical = 20.dp), onClick = onClose)
    }
}

@Composable
internal fun GuideOverlay(step: Int, content: Content, colors: Palette, top: Dp, onStep: (Int) -> Unit) {
    BackHandler(true) { } // Includes the interactive practice overlays; only explicit controls leave the guide.
    val titles = listOf("Tap a word", "Hold a sentence", "Follow your place", "Reading ↔ Words")
    val descriptions = listOf("点按英文词查看音标、义项、派生词与本句释义。点磨砂区域收起。", "长按英文词分析所在句；句中仍可点词查看详情。",
        "透明书签包裹当前句子。向下阅读或查询更远位置时，它会平滑跟随；点顶栏书签回到那里。",
        "空间顺序：文章目录｜文章｜生词｜生词菜单。相邻互滑；列表点词看详情，长按词可记住、忘记或归档。")
    var demo by remember(step) { mutableStateOf<String?>(null) }
    var lastDemo by remember(step) { mutableStateOf<String?>(null) }
    var target by remember(step) { mutableStateOf<WordTarget?>(null) }
    var sources by remember(step) { mutableStateOf(emptyList<TokenGlyph>()) }
    var destinations by remember(step) { mutableStateOf(emptyList<TokenGlyph>()) }
    var sentenceLayout by remember(step) { mutableStateOf<TextLayoutResult?>(null) }
    val motion = remember(step) { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val blur by animateFloatAsState(if (demo != null) GLASS_BLUR else 0f, tween(260))
    LaunchedEffect(demo) {
        if (demo != null) {
            lastDemo = demo
            motion.snapTo(0f); snapshotFlow { destinations }.first { it.isNotEmpty() }; motion.animateTo(1f, tween(420))
        } else motion.animateTo(0f, tween(310))
    }
    GlassScrim(colors) { }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val screenWidth = maxWidth; val screenHeight = maxHeight
    Column(Modifier.fillMaxSize().graphicsLayer { renderEffect = if (blur > .1f) BlurEffect(blur, blur, TileMode.Clamp) else null }
        .padding(top = top + 40.dp).navigationBarsPadding().padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.Center) {
        RevealElement(0, step) {
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                if (step < 2) {
                    drawCircle(colors.word, 12.dp.toPx(), center + Offset(0f, -20.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    drawLine(colors.ink, center + Offset(0f, -5.dp.toPx()), center + Offset(0f, 22.dp.toPx()), 2.dp.toPx())
                    if (step == 1) repeat(2) { i -> drawCircle(colors.word.copy(alpha = .5f - i * .15f), (22 + 10 * i).dp.toPx(), center + Offset(0f, -20.dp.toPx()), style = Stroke(1.dp.toPx())) }
                    drawLine(colors.word, center + Offset(0f, 35.dp.toPx()), center + Offset(0f, 68.dp.toPx()), 1.dp.toPx())
                    drawLine(colors.word, center + Offset(-5.dp.toPx(), 61.dp.toPx()), center + Offset(0f, 68.dp.toPx()), 1.dp.toPx())
                    drawLine(colors.word, center + Offset(5.dp.toPx(), 61.dp.toPx()), center + Offset(0f, 68.dp.toPx()), 1.dp.toPx())
                } else if (step == 2) {
                    repeat(3) { row -> drawLine(colors.muted, Offset(20.dp.toPx(), (30 + row * 30).dp.toPx()),
                        Offset(size.width - (if (row == 2) 65 else 20).dp.toPx(), (30 + row * 30).dp.toPx()), 2.dp.toPx()) }
                    drawRoundRect(colors.word.copy(alpha = .15f), Offset(10.dp.toPx(), 18.dp.toPx()),
                        androidx.compose.ui.geometry.Size(size.width - 20.dp.toPx(), 56.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                } else {
                    repeat(4) { i -> val x = size.width * (.04f + i * .245f)
                        drawRect(if (i == 0 || i == 3) colors.word.copy(alpha = .23f) else colors.ink.copy(alpha = .1f),
                            Offset(x, if (i == 0 || i == 3) 18.dp.toPx() else 30.dp.toPx()),
                            androidx.compose.ui.geometry.Size(size.width * .18f, 78.dp.toPx())) }
                    drawLine(colors.word, Offset(size.width * .32f, 125.dp.toPx()), Offset(size.width * .69f, 125.dp.toPx()), 2.dp.toPx())
                }
            }
        }
        RevealElement(1, step) {
            if (step < 2) Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.Center) {
                InteractiveParagraph(titles[step], colors, null, content, emptySet(), ReaderScale(34f, 1.25f, 0f, 0f),
                    Modifier.width(IntrinsicSize.Max), onWord = { token, anchor ->
                        target = WordTarget(0, token, anchor, false); sources = listOf(anchor); destinations = emptyList(); demo = "word"
                    }, onLongWord = { _, glyphs -> sources = glyphs; destinations = emptyList(); demo = "sentence" },
                    hidden = if (destinations.isNotEmpty() && (demo != null || motion.value > .001f))
                        if (sources.size > 1) 0 to titles[step].length else target?.token?.let { it.start to it.end } else null)
            } else UiHeading(if (step == 2) "FOLLOW YOUR PLACE" else "FOUR PANELS", colors, size = 28)
        }
        RevealElement(2, step) { Text(descriptions[step], color = colors.muted, fontSize = 15.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 18.dp)) }
        RevealElement(3, step) { Row(Modifier.fillMaxWidth().padding(top = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${step + 1} / 4", color = colors.muted, fontFamily = ReadingFont, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("跳过", Modifier.clickable { onStep(4) }.padding(12.dp), color = colors.muted, fontSize = 13.sp)
            JumpLink(if (step == 3) "开始" else "下一步", colors, Modifier.padding(12.dp)) { onStep(step + 1) }
        } }
    }
    if (demo != null || motion.value > .001f) GlassScrim(colors, motion.value) { demo = null }
    AnimatedVisibility(demo != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
    if (lastDemo == "word" && target != null) {
        val word = target!!.token.text.lowercase()
        val meaning = when (word) { "tap" -> "轻点"; "hold" -> "按住"; "a" -> "一个"; "sentence" -> "句子"; else -> "单词" }
        val analysis = remember(step, word) { JSONObject().put("common_senses", org.json.JSONArray().put(JSONObject().put("zh", meaning)
            .put("part_of_speech", if (word in setOf("tap", "hold")) "v." else "n."))).put("derivatives", org.json.JSONArray()) }
        WordSheet(target!!, Article("guide", "guide", titles[step], listOf(titles[step])), content, analysis, null, null,
            colors, screenWidth, screenHeight, top, motion.value, onTitleGeometry = { destinations = listOf(it) }, onRetry = {}, onConfigure = {},
            heading = "TRY A WORD", allowQueries = false, afterContent = {
                Text("引导练习 · 不请求模型，不写入学习记录", color = colors.muted, fontSize = 11.sp)
                Text("正式查询按模块补缺：音标和本句义等关闭思考；派生词、句子翻译与结构使用 MAX。AI 可能出错，可重新生成对应模块。", color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 15.dp))
                Text("先读本地词典与缓存；离线或未配置密钥时仍可阅读，缺少的解析会明确提示。关闭浮层不终止已发请求，可在顶栏查看进度。静默解析默认关闭，启用会消耗额度。", color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 12.dp))
            })
    }
    if (lastDemo == "sentence") Column(Modifier.fillMaxWidth().padding(top = top + 44.dp).padding(horizontal = 32.dp)) {
        UiHeading("TRY A SENTENCE", colors, size = 14)
        Text(titles[step], Modifier.padding(top = 22.dp).graphicsLayer { alpha = if (motion.value >= 1f) 1f else 0f }
            .onGloballyPositioned { coordinates -> sentenceLayout?.let { layout ->
                destinations = Content.tokens(titles[step]).map { token -> measuredGlyph(token, AnnotatedString(titles[step]), layout,
                    coordinates.positionInRoot(), with(density) { 34.sp.toPx() }, 34f, colors.ink) }
            } }, onTextLayout = { sentenceLayout = it }, fontFamily = ReadingFont, fontSize = 34.sp, color = colors.ink)
        RevealElement(1, step) { Column(Modifier.padding(top = 28.dp)) {
            UiHeading("TRANSLATION", colors, size = 12)
            Text(if (step == 0) "点按一个单词。" else "长按一个句子。", color = colors.ink, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontSize = 23.sp, modifier = Modifier.padding(top = 12.dp))
            Text("长按英文词即可分析其所在句；点空白收起练习。", color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 25.dp))
            Text("正式句子解析来自 AI：翻译、结构与词义各自查询和缓存。模型或网络不可用时保留原文和已有解析，不用失败结果替换旧内容。", color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 15.dp))
        } }
    }
    }
    if (sources.isNotEmpty() && destinations.isNotEmpty()) MotionGlyphs(sources, destinations, motion.value, demo != null)
    }
}
