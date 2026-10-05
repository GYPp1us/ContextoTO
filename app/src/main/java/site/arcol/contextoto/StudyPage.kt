package site.arcol.contextoto

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import org.json.JSONObject

private data class StudyDetail(val word: String, val anchor: TokenGlyph, val question: ReviewQuestion? = null)

@Composable
internal fun StudyPage(content: Content, learning: LearningStore, engine: AnalysisEngine, provider: Provider, colors: Palette,
                       modifier: Modifier, top: Dp, active: Boolean, menuOpen: Boolean, onMenu: (Boolean) -> Unit,
                       externalOverlay: Boolean, focusStatus: String, onFocus: () -> Unit, onSettings: () -> Unit,
                       onImport: () -> Unit, onGuide: () -> Unit, onBusy: (Boolean) -> Unit, cutoutHeight: Dp = 0.dp,
                       onSource: (Appearance) -> Unit) {
    val epoch by learning.revision.collectAsState()
    val cacheRevision by engine.cacheRevision.collectAsState()
    var mode by remember { mutableStateOf("复习") }
    var question by remember { mutableStateOf(learning.currentQuestion()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var early by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<StudyDetail?>(null) }
    var lastDetail by remember { mutableStateOf<StudyDetail?>(null) }
    var source by remember { mutableStateOf<TokenGlyph?>(null) }
    var destination by remember { mutableStateOf<TokenGlyph?>(null) }
    var actionsWord by remember { mutableStateOf<String?>(null) }
    var issueOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var heatDay by remember { mutableStateOf<String?>(null) }
    var supplementError by remember { mutableStateOf<String?>(null) }
    var supplementProgress by remember { mutableStateOf<QueryProgress?>(null) }
    var progressOpen by remember { mutableStateOf(false) }
    var percentSource by remember { mutableStateOf<TokenGlyph?>(null) }
    var percentDestination by remember { mutableStateOf<TokenGlyph?>(null) }
    var percentFlightSource by remember { mutableStateOf<TokenGlyph?>(null) }
    var percentFlightText by remember { mutableStateOf("0%") }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val motion = remember { Animatable(0f) }
    val percentMotion = remember { Animatable(0f) }
    val tasks by engine.tasks.collectAsState()
    val task = tasks.firstOrNull { it.status == QueryStatus.RUNNING && !it.silent }
    val state = remember(epoch) { learning.studyWords() }
    val answeredCount = remember(epoch) { learning.heatmap()[LocalDate.now().toString()]?.get("review") ?: 0 }
    val remainingCount = state.count { !it.archived && it.memory.due <= System.currentTimeMillis() }
    val percent = if (task != null) "${queryPercent(task.progress)}%" else
        "${if (answeredCount + remainingCount == 0) 0 else 100 * answeredCount / (answeredCount + remainingCount)}%"
    val busy = detail != null || actionsWord != null || issueOpen || heatDay != null || progressOpen ||
        motion.value in .001f.. .999f || percentMotion.value in .001f.. .999f
    SideEffect { onBusy(active && busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    fun closeDetail() {
        val completed = detail?.question
        detail = null; issueOpen = false
        if (completed != null) scope.launch {
            delay(330)
            learning.closeQuestion(completed.id); question = null; refresh++
        }
    }
    BackHandler(active && (busy || menuOpen)) {
        when { issueOpen -> issueOpen = false; progressOpen -> progressOpen = false; actionsWord != null -> actionsWord = null; heatDay != null -> heatDay = null
            detail != null -> closeDetail(); else -> onMenu(false) }
    }
    LaunchedEffect(active, mode, refresh, epoch) {
        if (active && mode == "复习" && question == null && !loading) {
            loading = true
            question = withContext(Dispatchers.IO) { learning.nextQuestion(early) }
            loading = false
        }
    }
    LaunchedEffect(cacheRevision) {
        if (question?.answered == false && learning.currentQuestion()?.id != question?.id) { question = null; refresh++ }
    }
    LaunchedEffect(question?.id, question?.answered, active, mode) {
        val q = question
        if (q?.answered == true && active && mode == "复习") {
            delay(500)
            snapshotFlow { source }.first { it != null }
            destination = null; detail = StudyDetail(q.word, source!!, q)
        }
    }
    LaunchedEffect(detail) {
        if (detail != null) {
            supplementError = null; supplementProgress = null
            lastDetail = detail; motion.snapTo(0f)
            snapshotFlow { destination }.first { it != null }
            motion.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        } else motion.animateTo(0f, tween(310, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(progressOpen) {
        if (progressOpen) {
            percentMotion.snapTo(0f)
            snapshotFlow { percentDestination }.first { it != null }
            percentMotion.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        } else percentMotion.animateTo(0f, tween(310, easing = FastOutSlowInEasing))
    }
    fun answer(action: String, index: Int? = null) {
        val q = question ?: return
        if (q.answered) return
        question = learning.answer(q, action, index)
    }
    val blur by animateFloatAsState(if (busy || menuOpen || externalOverlay) 26f else 0f, tween(290), label = "study blur")
    val detailBlur by animateFloatAsState(if (issueOpen || externalOverlay) 26f else 0f, tween(240), label = "study detail blur")
    BoxWithConstraints(modifier) {
        val pageWidth = maxWidth; val pageHeight = maxHeight
        val shift by animateFloatAsState(if (menuOpen) with(density) { (pageWidth * -.6f).toPx() } else 0f,
            tween(340, easing = FastOutSlowInEasing), label = "study menu shift")
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(top = top).graphicsLayer {
            translationX = shift
            renderEffect = if (blur > .1f) BlurEffect(blur, blur, TileMode.Clamp) else null
        }.padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = cutoutHeight.coerceAtLeast(42.dp)).padding(top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusStatusLabel(focusStatus, colors, onFocus)
                Spacer(Modifier.weight(1f))
                StudyWordText(percent, 13f, colors, percentDestination != null && (progressOpen || percentMotion.value > .001f),
                    Modifier.clickable { percentFlightText = percent; percentFlightSource = percentSource; percentDestination = null; progressOpen = true }.padding(vertical = 8.dp),
                    onGlyph = { percentSource = it }, ink = colors.word)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mode, color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 26.sp, modifier = Modifier.weight(1f))
                Text("生词  /  ${state.count { !it.archived }}", color = colors.muted, fontSize = 11.sp)
                Text("≡", Modifier.clickable { onMenu(true) }.padding(start = 16.dp), color = colors.ink, fontSize = 25.sp)
            }
            when (mode) {
                "复习" -> {
                    val q = question
                    if (q == null) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(if (loading) "Preparing…" else "A little, every day.", fontFamily = ReadingFont, fontSize = 32.sp, color = colors.ink)
                            Text(if (state.isEmpty()) "查过的词会自动加入复习，也可以从词库随机加入。" else "本轮没有可用的到期题目。新词每日最多 10 个；释义不足时会暂缓。",
                                Modifier.padding(top = 18.dp), fontSize = 14.sp, lineHeight = 23.sp, color = colors.muted)
                            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                Text("选择词库 →", Modifier.clickable { mode = "词库" }, color = colors.word, fontSize = 15.sp)
                                if (state.isNotEmpty()) Text("提前复习 →", Modifier.clickable { early = true; refresh++ }, color = colors.word, fontSize = 15.sp)
                            }
                        }
                    } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(24.dp))
                        StudyWordText(q.word, 44f, colors, (detail ?: lastDetail.takeIf { motion.value > .001f })?.word == q.word && destination != null,
                            Modifier.fillMaxWidth().padding(bottom = 34.dp), onGlyph = { source = it })
                        q.options.forEachIndexed { i, option ->
                            val correct = q.answered && i == q.correct
                            val wrong = q.answered && i == q.selected && !correct
                            val accent = when { correct -> colors.word; wrong -> colors.paragraph; else -> colors.muted }
                            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(accent.copy(alpha = if (correct || wrong) .18f else .07f))
                                .clickable(enabled = !q.answered) { answer("choice", i) }.padding(horizontal = 18.dp, vertical = 17.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(('A' + i).toString(), Modifier.width(28.dp), color = accent, fontFamily = ReadingFont, fontSize = 17.sp)
                                    Text(option, color = if (q.answered && !correct && !wrong) colors.muted else colors.ink,
                                        fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.weight(1f))
                                }
                                if (correct || wrong) Text(if (correct) "正确" else "你的选择", color = accent,
                                    fontSize = 10.sp, modifier = Modifier.padding(start = 28.dp, top = 6.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("记住了" to "remember", "忘记了" to "forget", "不要了" to "archive").forEach { (label, action) ->
                                Box(Modifier.weight(1f).background(colors.word.copy(alpha = .10f)).clickable(enabled = !q.answered) { answer(action) }
                                    .padding(vertical = 17.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = colors.ink, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                "列表" -> {
                    var search by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("在学") }
                    SettingInput("搜索单词", search, colors, onChange = { search = it })
                    Row(Modifier.padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        listOf("在学", "已学会", "归档", "全部").forEach { item -> Text(item, Modifier.clickable { filter = item },
                            fontSize = 12.sp, color = if (filter == item) colors.word else colors.muted) }
                    }
                    val rows = state.filter { it.word.contains(search, true) && when (filter) {
                        "在学" -> !it.archived; "已学会" -> it.mastered; "归档" -> it.archived; else -> true
                    } }
                    LazyColumn(Modifier.weight(1f)) {
                        if (rows.isEmpty()) item { Text("这里还没有单词", color = colors.muted, modifier = Modifier.padding(top = 25.dp)) }
                        items(rows, key = { it.word }) { word ->
                            var glyph by remember { mutableStateOf<TokenGlyph?>(null) }
                            Column(Modifier.fillMaxWidth().combinedClickable(onClick = { glyph?.let { destination = null; detail = StudyDetail(word.word, it) } },
                                onLongClick = { actionsWord = word.word }).padding(vertical = 14.dp)) {
                                StudyWordText(word.word, 25f, colors, (detail ?: lastDetail.takeIf { motion.value > .001f })?.word == word.word && destination != null, onGlyph = { glyph = it })
                                val brief = remember(word.word, epoch, cacheRevision) { learning.meanings(word.word).firstOrNull()?.text.orEmpty() }
                                Text(brief.ifBlank { "释义待补齐" }, color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                                Text(if (word.archived) "已归档" else if (word.mastered) "长期间隔" else "${word.memory.reviews} 次复习 · ${learning.appearances(word.word).size} 个出现",
                                    color = colors.word, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
                else -> StudyLibraries(content, learning, state, epoch, colors, Modifier.weight(1f), onImport,
                    onDay = { heatDay = it }, onMessage = { message = it })
            }
            if (message.isNotBlank()) Text(message, Modifier.clickable { message = "" }.padding(vertical = 10.dp), color = colors.word, fontSize = 12.sp)
        }
        if (menuOpen) GlassScrim(colors) { onMenu(false) }
        AnimatedVisibility(menuOpen, Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(340)) { it / 5 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(290)) { it / 5 }) {
            Column(Modifier.width(pageWidth * .8f).fillMaxHeight().padding(top = top + 28.dp).pointerInput(Unit) {
                var drag = 0f
                detectHorizontalDragGestures(onHorizontalDrag = { _, dx -> drag += dx }, onDragEnd = { if (drag > 58) onMenu(false); drag = 0f })
            }.padding(horizontal = 30.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Text("Words", fontFamily = ReadingFont, fontSize = 38.sp, color = colors.ink)
                listOf("复习", "列表", "词库").forEachIndexed { i, item -> RevealElement(i, menuOpen) {
                    Text(item, Modifier.fillMaxWidth().clickable { mode = item; onMenu(false) }.padding(vertical = 12.dp),
                        color = if (mode == item) colors.word else colors.ink, fontSize = 23.sp, fontFamily = FontFamily.Serif)
                } }
                Text("导入词库 →", Modifier.clickable { onMenu(false); onImport() }, color = colors.word, fontSize = 15.sp)
                Text("使用引导 →", Modifier.clickable { onMenu(false); onGuide() }, color = colors.muted, fontSize = 14.sp)
                Text("设置 →", Modifier.clickable { onMenu(false); onSettings() }, color = colors.muted, fontSize = 14.sp)
            }
        }
        val displayed = detail ?: lastDetail
        if (detail != null) GlassScrim(colors) { closeDetail() }
        AnimatedVisibility(detail != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
            if (displayed != null) Box(Modifier.fillMaxSize().graphicsLayer {
                renderEffect = if (detailBlur > .1f) BlurEffect(detailBlur, detailBlur, TileMode.Clamp) else null
            }) {
                val fake = Article("study", "study", displayed.word, listOf(displayed.word))
                val token = Token(displayed.word, 0, displayed.word.length)
                val result = remember(displayed.word, cacheRevision) { engine.cachedLexeme(displayed.word) }
                WordSheet(WordTarget(0, token, displayed.anchor, false), fake, content, result, null, null, colors,
                    pageWidth, pageHeight, top, if (detail != null && detail != lastDetail) 0f else motion.value,
                    onTitleGeometry = { destination = it }, onRetry = {}, onConfigure = onSettings,
                    onAction = if (displayed.question != null) ({ issueOpen = true }) else null,
                    heading = if (displayed.question != null) "REVIEW / ANSWER" else "WORD / COLLECTION", allowQueries = false,
                    beforeSenses = {
                        displayed.question?.let { q -> RevealElement(3, q.id) {
                            Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                                Text("本题所取释义", color = colors.word, fontSize = 11.sp)
                                SenseLine(q.answer.part, q.answer.text, colors)
                                Text("来源 · ${q.answer.source}", color = colors.muted, fontSize = 11.sp)
                            }
                        } }
                    }, afterContent = {
                        if (provider.key.isBlank() && learning.meanings(displayed.word).isEmpty()) Text("词元模块尚未缓存 · 设置接口 →",
                            Modifier.clickable(onClick = onSettings).padding(vertical = 15.dp), color = colors.muted, fontSize = 13.sp)
                        if (provider.key.isNotBlank()) {
                            if (supplementProgress != null) ProgressBlock(supplementProgress, colors, "补齐词元模块")
                            else Text("补齐音标 / 通用义 / 派生词 →", Modifier.clickable {
                                scope.launch {
                                    try { supplementProgress = QueryProgress(QueryPhase.CONTEXT)
                                        engine.lexeme(provider, displayed.word) { supplementProgress = it }
                                    } catch (failure: Exception) { supplementError = failure.message }
                                    finally { supplementProgress = null }
                                }
                            }.padding(vertical = 15.dp), color = colors.word, fontSize = 13.sp)
                        }
                        supplementError?.let { Text(it, color = colors.paragraph, fontSize = 12.sp) }
                        OccurrenceDetails(displayed.word, content, learning, epoch + cacheRevision, colors, onSource)
                        if (learning.studyWord(displayed.word)?.archived == true) Text("重新加入复习 →",
                            Modifier.clickable { learning.archive(displayed.word, false) }.padding(vertical = 20.dp), color = colors.word, fontSize = 14.sp)
                    })
            }
        }
        if (active && displayed != null && destination != null && !issueOpen) MotionGlyphs(listOf(displayed.anchor), listOf(destination!!), motion.value, detail != null)
        if (actionsWord != null) {
            GlassScrim(colors) { actionsWord = null }
            SimpleChoiceSheet(actionsWord!!, colors, top + 60.dp, listOf("记住了", "忘记了", "不要了")) { choice ->
                val word = actionsWord!!
                when (choice) { "记住了" -> learning.selfGrade(word, true); "忘记了" -> learning.selfGrade(word, false)
                    "不要了" -> learning.archive(word, true); else -> learning.archive(word, false) }
                actionsWord = null; message = "$word · $choice"
            }
        }
        if (issueOpen && displayed?.question != null) {
            GlassScrim(colors) { issueOpen = false }
            SimpleChoiceSheet("题目错误", colors, top + 50.dp, listOf("干扰义项与正确义相近", "多个答案成立", "正确释义有误", "词形 / 上下文不匹配", "其他")) {
                learning.reportIssue(displayed.question, it); issueOpen = false; message = "已记录问题，仅撤销本次评分"
            }
        }
        heatDay?.let { day ->
            GlassScrim(colors) { heatDay = null }
            SimpleChoiceSheet(day, colors, top + 60.dp, listOf("查询 · ${learning.heatmap()[day]?.get("lookup") ?: 0}",
                "书签 · ${learning.heatmap()[day]?.get("bookmark") ?: 0}", "复习 · ${learning.heatmap()[day]?.get("review") ?: 0}")) { heatDay = null }
        }
        if (progressOpen) GlassScrim(colors) { percentFlightText = percent; percentFlightSource = percentSource; progressOpen = false }
        AnimatedVisibility(progressOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
            Column(Modifier.fillMaxWidth().padding(top = top + 40.dp).padding(horizontal = 32.dp)) {
                StudyWordText(if (percentMotion.value < 1f) percentFlightText else percent, 42f, colors, percentMotion.value < 1f,
                    onGlyph = { percentDestination = it }, ink = colors.word)
                RevealElement(1, progressOpen) {
                    Column(Modifier.padding(top = 25.dp)) {
                        if (task != null) {
                            Text("后台查询 · ${task.label}", color = colors.ink, fontFamily = ReadingFont, fontSize = 22.sp)
                            ProgressBlock(task.progress, colors)
                        } else {
                            Text("今日复习", color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 25.sp)
                            Text("已提交 $answeredCount 次 · 当前到期 $remainingCount 词", color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
                            Text("百分比为今日已提交 /（已提交 + 当前到期）；不是词汇掌握率。", color = colors.muted, fontSize = 12.sp,
                                lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        }
        if (active && percentFlightSource != null && percentDestination != null) MotionGlyphs(listOf(percentFlightSource!!), listOf(percentDestination!!), percentMotion.value, progressOpen)
    }
}

@Composable
private fun StudyWordText(word: String, size: Float, colors: Palette, hidden: Boolean, modifier: Modifier = Modifier,
                          onGlyph: (TokenGlyph) -> Unit, ink: androidx.compose.ui.graphics.Color = colors.ink) {
    var layout by remember(word) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current; val text = AnnotatedString(word)
    Text(text, modifier.graphicsLayer { alpha = if (hidden) 0f else 1f }.onGloballyPositioned { coordinates ->
        layout?.let { onGlyph(measuredGlyph(Token(word, 0, word.length), text, it, coordinates.positionInRoot(),
            with(density) { size.sp.toPx() }, size, ink)) }
    }, onTextLayout = { layout = it }, color = ink, fontSize = size.sp, fontFamily = ReadingFont, fontWeight = FontWeight.Normal)
}

@Composable
internal fun SimpleChoiceSheet(title: String, colors: Palette, top: Dp, choices: List<String>, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = top).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)) {
        RevealElement(0, title) { Text(title, fontFamily = FontFamily.Serif, fontSize = 26.sp, color = colors.ink, modifier = Modifier.padding(bottom = 24.dp)) }
        choices.forEachIndexed { i, choice -> RevealElement(i + 1, title to choice, Modifier.fillMaxWidth()) {
            Text(choice, Modifier.fillMaxWidth().clickable { onSelect(choice) }.padding(vertical = 18.dp), color = colors.word, fontSize = 16.sp)
        } }
    }
}

@Composable
private fun OccurrenceDetails(word: String, content: Content, learning: LearningStore, revision: Int, colors: Palette,
                              onSource: (Appearance) -> Unit) {
    val occurrences = remember(word, revision) { learning.appearances(word) }
    val legacy = remember(word, revision) { learning.legacyArticles(word) }
    Spacer(Modifier.height(25.dp))
    RevealElement(9, word) { Text("查过的出现 · ${occurrences.size}", color = colors.word, fontSize = 12.sp) }
    occurrences.forEachIndexed { index, occurrence ->
        var expanded by remember(occurrence.id) { mutableStateOf(false) }
        val article = content.articles.firstOrNull { it.id == occurrence.articleId }
        RevealElement(10 + index, occurrence.id, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(top = 17.dp)) {
                    Text("${if (expanded) "−" else "+"} ${article?.title ?: occurrence.articleId}",
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }, color = colors.muted, fontSize = 12.sp)
                Text(occurrence.meaning.ifBlank { "本句释义待补齐" }, color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 7.dp))
                if (expanded) RevealElement(0, occurrence.id) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(occurrence.sentence, color = colors.ink, fontFamily = ReadingFont, fontSize = 18.sp, lineHeight = 26.sp)
                        Text("${occurrence.surface} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(occurrence.queriedAt))}",
                            color = colors.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                        if (article != null) Text("回到原文 →", Modifier.clickable { onSource(occurrence) }.padding(vertical = 12.dp), color = colors.word, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    legacy.forEach { id -> Text("历史查询 · ${content.articles.firstOrNull { it.id == id }?.title ?: id}\n旧版未保存具体词位与当时句义。", color = colors.muted,
        fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 14.dp)) }
    if (occurrences.isEmpty() && legacy.isEmpty()) Text("尚无主动查词出现记录。", color = colors.muted,
        fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun StudyLibraries(content: Content, learning: LearningStore, state: List<StudyWord>, epoch: Int, colors: Palette,
                           modifier: Modifier, onImport: () -> Unit, onDay: (String) -> Unit, onMessage: (String) -> Unit) {
    var selected by remember { mutableStateOf("builtin") }; var choosing by remember { mutableStateOf(false) }
    val bank = content.banks.firstOrNull { it.id == selected } ?: content.banks.first()
    val queried = remember(epoch) { learning.queried() }
    val mastered = state.filter { it.mastered }.map { it.word }.toSet()
    val heat = remember(epoch) { learning.heatmap() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(bank.name + "  ⌄", Modifier.fillMaxWidth().clickable { choosing = !choosing }.padding(vertical = 15.dp), color = colors.ink,
            fontFamily = FontFamily.Serif, fontSize = 22.sp)
        if (choosing) content.banks.forEachIndexed { i, option -> RevealElement(i, option.id) {
            Text(option.name, Modifier.fillMaxWidth().clickable { selected = option.id; choosing = false }.padding(vertical = 12.dp), color = colors.word, fontSize = 14.sp)
        } }
        Text("${bank.words.size} 个词元", color = colors.muted, fontSize = 11.sp)
        Text("随机加入 10 个词 →", Modifier.fillMaxWidth().padding(vertical = 24.dp).background(colors.word.copy(alpha = .09f))
            .clickable { val count = learning.randomImport(bank); onMessage(if (count > 0) "已加入 $count 个新词" else "没有尚未加入的词") }.padding(16.dp),
            color = colors.word, fontSize = 16.sp)
        listOf("已学会" to bank.words.keys.count { it in mastered }, "已查询" to bank.words.keys.count { it in queried }).forEach { (label, count) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Bottom) {
                Text(label, color = colors.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                val percentage = if (bank.words.isEmpty()) 0.0 else count * 100.0 / bank.words.size
                Text(if (count == 0) "0%" else java.lang.String.format(java.util.Locale.US, if (percentage < 1) "%.2f%%" else "%.1f%%", percentage),
                    color = colors.ink, fontFamily = ReadingFont, fontSize = 32.sp)
            }
            Text("$count / ${bank.words.size}", color = colors.muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.muted.copy(alpha = .1f))) {
                Box(Modifier.fillMaxWidth(if (bank.words.isEmpty()) 0f else count.toFloat() / bank.words.size).fillMaxHeight().background(colors.word))
            }
        }
        Text("Daily rhythm", color = colors.ink, fontFamily = ReadingFont, fontSize = 24.sp, modifier = Modifier.padding(top = 34.dp, bottom = 12.dp))
        Text("全局活动 · 查询 / 书签 / 复习", color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 16.dp))
        val end = LocalDate.now(); val start = end.minusDays(83)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(12) { week -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { weekday ->
                    val date = start.plusDays((week * 7 + weekday).toLong()).toString()
                    val events = heat[date].orEmpty(); val score = (events["lookup"] ?: 0) + (events["bookmark"] ?: 0) + 2 * (events["review"] ?: 0)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(colors.word.copy(alpha = if (score == 0) .08f else (.2f + score * .045f).coerceAtMost(.9f)))
                        .clickable { onDay(date) })
                }
            } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(start.toString(), color = colors.muted, fontSize = 9.sp)
            Spacer(Modifier.weight(1f)); Text(end.toString(), color = colors.muted, fontSize = 9.sp) }
        Text("导入词库 →", Modifier.clickable(onClick = onImport).padding(vertical = 30.dp), color = colors.word, fontSize = 15.sp)
    }
}
