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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
internal fun StudyPage(content: Content, learning: LearningStore, engine: AnalysisEngine, provider: Provider,
                       settings: SecureSettings, settingsRevision: Int, colors: Palette,
                       modifier: Modifier, pageOffset: Float, cardScale: Float, cardAlpha: Float, menuFraction: Float,
                       top: Dp, active: Boolean, menuOpen: Boolean, onMenu: (Boolean) -> Unit,
                       externalOverlay: Boolean, focusStatus: String, onFocus: () -> Unit, onSettings: () -> Unit,
                       onImport: () -> Unit, onGuide: () -> Unit, onBusy: (Boolean) -> Unit, cutoutHeight: Dp = 0.dp,
                       onSwitchMode: (() -> Unit) -> Unit, onSource: (Appearance) -> Unit) {
    val epoch by learning.revision.collectAsState()
    val cacheRevision by engine.cacheRevision.collectAsState()
    var mode by remember { mutableStateOf("复习") }
    var question by remember { mutableStateOf(learning.currentQuestion()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var early by remember { mutableStateOf(false) }
    var roundSeen by remember { mutableStateOf(emptySet<String>()) }
    var prepared by remember { mutableStateOf<ReviewQuestion?>(null) }
    var preparation by remember { mutableStateOf(QuestionPreparation()) }
    var preparationError by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<StudyDetail?>(null) }
    var lastDetail by remember { mutableStateOf<StudyDetail?>(null) }
    var source by remember { mutableStateOf<TokenGlyph?>(null) }
    var destination by remember { mutableStateOf<TokenGlyph?>(null) }
    var actionsWord by remember { mutableStateOf<String?>(null) }
    var actionsQuestion by remember { mutableStateOf<ReviewQuestion?>(null) }
    var actionsAnchor by remember { mutableStateOf(Offset.Zero) }
    var studyCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
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
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(60_000) } }
    val answeredCount = remember(epoch, today) { learning.heatmap()[today.toString()]?.get("review") ?: 0 }
    val dailyWords = remember(epoch, today) { learning.dailyReviewedCount() }
    val dailyLimitReached = settings.dailyReviewLimit > 0 && dailyWords >= settings.dailyReviewLimit
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
        if (completed != null) {
            roundSeen = roundSeen + completed.word
            learning.closeQuestion(completed.id)
            question = prepared?.let(learning::activateQuestion); prepared = null; refresh++
        }
    }
    BackHandler(active && (busy || menuOpen)) {
        when { issueOpen -> issueOpen = false; progressOpen -> progressOpen = false; actionsWord != null -> { actionsWord = null; actionsQuestion = null }; heatDay != null -> heatDay = null
            detail != null -> closeDetail(); else -> onMenu(false) }
    }
    LaunchedEffect(refresh, epoch, cacheRevision, settingsRevision, today, question?.id, question?.answered) {
        val current = question
        if (current == null || current.answered) {
            val ownGeneration = ++generation
            prepared = null
            loading = true
            preparationError = null
            try {
                val candidate = withContext(Dispatchers.IO) {
                    if (current == null) learning.nextQuestion(early, excluded = roundSeen, dailyLimit = settings.dailyReviewLimit) { preparation = it }
                    else learning.prepareQuestion(early, excluded = roundSeen + current.word, dailyLimit = settings.dailyReviewLimit) { preparation = it }
                }
                if (generation == ownGeneration) {
                    if (current == null) question = candidate else prepared = candidate
                }
            } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (failure: Exception) { if (generation == ownGeneration) preparationError = failure.message ?: "题目准备失败" }
            finally { if (generation == ownGeneration) loading = false }
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
    LaunchedEffect(detail?.word, detail?.question?.id, detail?.anchor) {
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
    val modalBlur by animateFloatAsState(if (detail != null || actionsWord != null || issueOpen || heatDay != null || progressOpen || externalOverlay) GLASS_BLUR else 0f,
        tween(290), label = "study blur")
    val blur = maxOf(modalBlur, GLASS_BLUR * menuFraction)
    val detailBlur by animateFloatAsState(if (issueOpen || actionsWord != null || externalOverlay) GLASS_BLUR else 0f, tween(240), label = "study detail blur")
    BoxWithConstraints(modifier.onGloballyPositioned { studyCoordinates = it }) {
        val pageWidth = maxWidth; val pageHeight = maxHeight
        val studyLayer = rememberGraphicsLayer()
        var headerHeight by remember { mutableStateOf(106.dp) }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = pageOffset; scaleX = cardScale; scaleY = cardScale; alpha = cardAlpha
        }) {
        Column(Modifier.fillMaxWidth().zIndex(1f).quietClickable { }.onSizeChanged { headerHeight = with(density) { it.height.toDp() } }.graphicsLayer {
            renderEffect = if (blur > .1f) BlurEffect(blur, blur, TileMode.Clamp) else null
        }) {
            FrostedBar(studyLayer, colors, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(top = top)) {
            Row(Modifier.fillMaxWidth().heightIn(min = cutoutHeight.coerceAtLeast(24.dp)).padding(horizontal = 24.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusStatusLabel(focusStatus, colors, onFocus)
                Spacer(Modifier.weight(1f))
                StudyWordText(percent, 13f, colors, percentDestination != null && (progressOpen || percentMotion.value > .001f),
                    Modifier.clickable { percentFlightText = percent; percentFlightSource = percentSource; percentDestination = null; progressOpen = true }.padding(vertical = 8.dp),
                    onGlyph = { percentSource = it }, ink = colors.word)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                UiHeading(mode, colors, Modifier.weight(1f), 25)
                Text("生词  /  ${state.count { !it.archived }}", color = colors.muted, fontSize = 11.sp)
                Text("≡", Modifier.clickable { onMenu(true) }.padding(start = 16.dp), color = colors.ink, fontSize = 25.sp)
            }
            }
            }
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding().graphicsLayer {
            renderEffect = if (blur > .1f) BlurEffect(blur, blur, TileMode.Clamp) else null
        }.drawWithContent { studyLayer.record { this@drawWithContent.drawContent() }; drawLayer(studyLayer) }.padding(horizontal = 24.dp)) {
            when (mode) {
                "复习" -> {
                    val q = question
                    if (q == null) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            if (loading) {
                                UiHeading("PREPARING", colors, size = 30)
                                PreparationProgress(preparation, colors)
                            } else if (preparationError != null) {
                                UiHeading("PREPARATION PAUSED", colors, size = 28)
                            } else {
                                CelebrationMark(colors)
                                UiHeading(if (state.isEmpty()) "A FRESH START" else if (dailyLimitReached) "DAILY GOAL MET" else "ROUND COMPLETE", colors, size = 30)
                            }
                            preparationError?.let { Text(it, color = colors.paragraph) }
                            Text(if (dailyLimitReached) "今天已复习 $dailyWords 个不同的词，达到设置中的每日数量。明天继续，或在设置中调整上限。"
                                else if (state.isEmpty()) "查过的词会自动加入复习，也可以从词库随机加入。" else "本轮没有新的可用题目。新词优先，同一轮不重复；释义不足的词暂缓。",
                                Modifier.padding(top = 18.dp), fontSize = 14.sp, lineHeight = 23.sp, color = colors.muted)
                            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                JumpLink("选择词库", colors) { mode = "词库" }
                                if (preparationError != null) JumpLink("重试准备", colors) { refresh++ }
                                else if (dailyLimitReached) JumpLink("调整复习数量", colors, onClick = onSettings)
                                else if (state.isNotEmpty()) JumpLink("开始下一轮", colors) { early = true; roundSeen = emptySet(); refresh++ }
                            }
                        }
                    } else RevealElement(0, q.id, Modifier.weight(1f)) {
                      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = headerHeight)) {
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
                }
                "列表" -> {
                    Spacer(Modifier.height(headerHeight))
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
                            var coordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                            Column(Modifier.fillMaxWidth().onGloballyPositioned { coordinates = it }.pointerInput(word.word) {
                                detectTapGestures(onTap = { glyph?.let { destination = null; detail = StudyDetail(word.word, it) } }, onLongPress = { point ->
                                    actionsAnchor = (coordinates?.localToRoot(point) ?: point) - (studyCoordinates?.positionInRoot() ?: Offset.Zero)
                                    actionsQuestion = null; actionsWord = word.word
                                })
                            }.padding(vertical = 14.dp)) {
                                StudyWordText(word.word, 25f, colors, (detail ?: lastDetail.takeIf { motion.value > .001f })?.word == word.word && destination != null, onGlyph = { glyph = it })
                                val brief = remember(word.word, epoch, cacheRevision) { learning.meanings(word.word).firstOrNull()?.text.orEmpty() }
                                Text(brief.ifBlank { "释义待补齐" }, color = colors.muted, fontFamily = FontFamily.Serif, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                                Text(if (word.archived) "已归档" else if (word.mastered) "长期间隔" else "${word.memory.reviews} 次复习 · ${learning.appearances(word.word).size} 个出现",
                                    color = colors.word, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
                else -> StudyLibraries(content, learning, state, epoch, settings, settingsRevision, headerHeight, colors, Modifier.weight(1f), onImport,
                    onDay = { heatDay = it }, onMessage = { message = it })
            }
            if (message.isNotBlank()) Text(message, Modifier.clickable { message = "" }.padding(vertical = 10.dp), color = colors.word, fontSize = 12.sp)
        }
        }
        if (active || menuFraction > .001f) Box(Modifier.fillMaxSize().zIndex(2f)) {
        if (menuFraction > .001f) {
            Box(Modifier.fillMaxSize().background(colors.paper.copy(alpha = GLASS_TINT * menuFraction)).quietClickable { onMenu(false) })
            Column(Modifier.align(Alignment.CenterEnd).width(pageWidth * .8f).fillMaxHeight().graphicsLayer {
                translationX = with(density) { pageWidth.toPx() } * .16f * (1f - menuFraction); alpha = menuFraction
            }.padding(top = top + 28.dp).verticalScroll(rememberScrollState()).padding(horizontal = 30.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                UiHeading("WORDS", colors, size = 25)
                listOf("复习", "列表", "词库").forEachIndexed { i, item -> Box(Modifier.graphicsLayer {
                    val amount = ((menuFraction - i * .08f) / (1f - i * .08f)).coerceIn(0f, 1f)
                    alpha = amount; translationY = -8.dp.toPx() * (1f - amount)
                }) {
                    Text(uiSmallCaps(uiTitle(item), 23f), Modifier.fillMaxWidth().quietClickable {
                        if (mode == item) onMenu(false) else onSwitchMode { mode = item }
                    }.padding(vertical = 12.dp),
                        color = if (mode == item) colors.word else colors.ink, fontSize = 23.sp, fontFamily = ReadingFont, fontWeight = FontWeight.Bold)
                } }
                JumpLink("导入词库", colors) { onMenu(false); onImport() }
                JumpLink("使用引导", colors) { onMenu(false); onGuide() }
                JumpLink("设置", colors) { onMenu(false); onSettings() }
            }
        }
        val displayed = detail ?: lastDetail
        if (detail != null || motion.value > .001f) {
            if (displayed?.question != null) Box(Modifier.fillMaxSize().background(colors.paper.copy(alpha = GLASS_TINT * motion.value)).pointerInput(displayed.question) {
                detectTapGestures(onTap = { closeDetail() }, onLongPress = { point -> if (detail != null) {
                    actionsAnchor = point; actionsQuestion = displayed.question; actionsWord = displayed.word
                } })
            }) else GlassScrim(colors, motion.value) { closeDetail() }
        }
        AnimatedVisibility(detail != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(310)) +
            if (displayed?.question != null) scaleOut(tween(310), .7f, TransformOrigin(.5f, 0f)) else scaleOut(tween(310), 1f)) {
            if (displayed != null) Box(Modifier.fillMaxSize().graphicsLayer {
                renderEffect = if (detailBlur > .1f) BlurEffect(detailBlur, detailBlur, TileMode.Clamp) else null
            }) {
                val fake = Article("study", "study", displayed.word, listOf(displayed.word))
                val token = Token(displayed.word, 0, displayed.word.length)
                val result = remember(displayed.word, cacheRevision) { engine.cachedLexeme(displayed.word) }
                WordSheet(WordTarget(0, token, displayed.anchor, false), fake, content, result, null, null, colors,
                    pageWidth, pageHeight, top, if (displayed.question != null && detail == null) 1f else if (detail != null && detail != lastDetail) 0f else motion.value,
                    onTitleGeometry = { destination = it }, onRetry = {}, onConfigure = onSettings,
                    onAction = if (displayed.question != null) ({ issueOpen = true }) else null,
                    heading = if (displayed.question != null) "REVIEW / ANSWER" else "WORD / COLLECTION", allowQueries = false,
                    onBlankTap = if (displayed.question != null) ({ closeDetail() }) else null,
                    onHold = if (displayed.question != null) ({ point ->
                        actionsAnchor = point - (studyCoordinates?.positionInRoot() ?: Offset.Zero)
                        actionsQuestion = displayed.question; actionsWord = displayed.word
                    }) else null,
                    beforeSenses = {
                        displayed.question?.let { q -> RevealElement(3, q.id) {
                            Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                                UiHeading("ANSWER SOURCE", colors, size = 12)
                                SenseLine(q.answer.part, q.answer.text, colors)
                                Text("来源 · ${q.answer.source}", color = colors.muted, fontSize = 11.sp)
                            }
                        } }
                    }, afterContent = {
                        if (provider.key.isBlank() && learning.meanings(displayed.word).isEmpty()) JumpLink("词元模块待补齐 · 设置接口", colors,
                            Modifier.padding(vertical = 15.dp), onClick = onSettings)
                        if (provider.key.isNotBlank()) {
                            if (supplementProgress != null) ProgressBlock(supplementProgress, colors, "补齐词元模块")
                            else JumpLink("补齐音标 / 通用义 / 派生词", colors, Modifier.padding(vertical = 15.dp)) {
                                scope.launch {
                                    try { supplementProgress = QueryProgress(QueryPhase.CONTEXT)
                                        engine.lexeme(provider, displayed.word) { supplementProgress = it }
                                    } catch (failure: Exception) { supplementError = failure.message }
                                    finally { supplementProgress = null }
                                }
                            }
                        }
                        supplementError?.let { Text(it, color = colors.paragraph, fontSize = 12.sp) }
                        OccurrenceDetails(displayed.word, content, learning, epoch + cacheRevision, colors, onSource)
                        if (learning.studyWord(displayed.word)?.archived == true) JumpLink("重新加入复习", colors,
                            Modifier.padding(vertical = 20.dp)) { learning.archive(displayed.word, false) }
                    })
            }
        }
        if (active && displayed != null && destination != null && !issueOpen && (detail != null || displayed.question == null))
            MotionGlyphs(listOf(displayed.anchor), listOf(destination!!), motion.value, detail != null)
        if (actionsWord != null) {
            if (detail != null) Box(Modifier.fillMaxSize().quietClickable { actionsWord = null; actionsQuestion = null })
            else GlassScrim(colors) { actionsWord = null; actionsQuestion = null }
            NearbyChoices(actionsWord!!, colors, actionsAnchor, pageWidth, pageHeight, top) { choice ->
                val word = actionsWord!!
                val revisedQuestion = actionsQuestion
                if (revisedQuestion != null) {
                    val revised = learning.reviseAnswer(revisedQuestion, when (choice) { "记住了" -> "remember"; "忘记了" -> "forget"; else -> "archive" })
                    question = revised; detail = detail?.copy(question = revised)
                    closeDetail()
                } else when (choice) { "记住了" -> learning.selfGrade(word, true); "忘记了" -> learning.selfGrade(word, false)
                    "不要了" -> learning.archive(word, true); else -> learning.archive(word, false) }
                actionsWord = null; actionsQuestion = null; message = "$word · $choice"
            }
        }
        if (issueOpen && displayed?.question != null) {
            Box(Modifier.fillMaxSize().quietClickable { issueOpen = false })
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
                            UiHeading("TODAY", colors)
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
}

@Composable
internal fun StudyWordText(word: String, size: Float, colors: Palette, hidden: Boolean, modifier: Modifier = Modifier,
                          onGlyph: (TokenGlyph) -> Unit, ink: androidx.compose.ui.graphics.Color = colors.ink) {
    var layout by remember(word) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current; val text = AnnotatedString(word)
    Text(text, modifier.graphicsLayer { alpha = if (hidden) 0f else 1f }.onGloballyPositioned { coordinates ->
        layout?.let { onGlyph(measuredGlyph(Token(word, 0, word.length), text, it, coordinates.positionInRoot(),
            with(density) { size.sp.toPx() }, size, ink)) }
    }, onTextLayout = { layout = it }, color = ink, fontSize = size.sp, fontFamily = ReadingFont, fontWeight = FontWeight.Normal)
}

@Composable
internal fun SimpleChoiceSheet(title: String, colors: Palette, top: Dp, choices: List<String>, contentTitle: Boolean = false, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = top).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)) {
        RevealElement(0, title) {
            if (contentTitle) Text(title, Modifier.padding(bottom = 24.dp), color = colors.ink, fontFamily = ReadingFont, fontSize = 30.sp)
            else UiHeading(title, colors, Modifier.padding(bottom = 24.dp), 26)
        }
        choices.forEachIndexed { i, choice -> RevealElement(i + 1, title to choice, Modifier.fillMaxWidth()) {
            if (choice.endsWith(" →")) JumpLink(choice, colors, Modifier.fillMaxWidth().padding(vertical = 10.dp), size = 16) { onSelect(choice) }
            else Text(choice, Modifier.fillMaxWidth().clickable { onSelect(choice) }.padding(vertical = 18.dp), color = colors.word, fontFamily = FontFamily.SansSerif, fontSize = 16.sp)
        } }
    }
}

@Composable
private fun NearbyChoices(word: String, colors: Palette, anchor: Offset, width: Dp, height: Dp, safeTop: Dp, onSelect: (String) -> Unit) {
    val density = LocalDensity.current
    val menuWidth = minOf(240.dp, width * .66f)
    val x = (with(density) { anchor.x.toDp() } - menuWidth / 2).coerceIn(16.dp, (width - menuWidth - 16.dp).coerceAtLeast(16.dp))
    val fingerY = with(density) { anchor.y.toDp() }
    val estimated = 200.dp
    val y = (if (fingerY + estimated + 40.dp < height) fingerY + 12.dp else fingerY - estimated - 12.dp)
        .coerceIn(safeTop + 12.dp, (height - estimated - 36.dp).coerceAtLeast(safeTop + 12.dp))
    Column(Modifier.offset(x, y).width(menuWidth)) {
        RevealElement(0, word to anchor) { Text(word, color = colors.ink, fontFamily = ReadingFont, fontSize = 20.sp,
            modifier = Modifier.padding(bottom = 12.dp)) }
        listOf("记住了", "忘记了", "不要了").forEachIndexed { index, label -> RevealElement(index + 1, word to anchor) {
            Text(label, Modifier.fillMaxWidth().quietClickable { onSelect(label) }.padding(vertical = 15.dp), color = colors.word,
                fontFamily = FontFamily.SansSerif, fontSize = 16.sp)
        } }
    }
}

@Composable
private fun OccurrenceDetails(word: String, content: Content, learning: LearningStore, revision: Int, colors: Palette,
                              onSource: (Appearance) -> Unit) {
    val occurrences = remember(word, revision) { learning.appearances(word) }
    val legacy = remember(word, revision) { learning.legacyArticles(word) }
    Spacer(Modifier.height(25.dp))
    RevealElement(9, word) { UiHeading("APPEARANCES / ${occurrences.size}", colors, size = 12) }
    occurrences.forEachIndexed { index, occurrence ->
        var expanded by remember(occurrence.id) { mutableStateOf(false) }
        val article = content.articles.firstOrNull { it.id == occurrence.articleId }
        RevealElement(10 + index, occurrence.id, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(top = 17.dp)) {
                    Text("${if (expanded) "−" else "+"} ${article?.title ?: occurrence.articleId}",
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }, color = colors.muted, fontFamily = ReadingFont, fontSize = 12.sp)
                Text(occurrence.meaning.ifBlank { "本句释义待补齐" }, color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 7.dp))
                RetainedFoldout(expanded, "occurrence-${occurrence.id}") { RevealElement(0, occurrence.id) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(occurrence.sentence, color = colors.ink, fontFamily = ReadingFont, fontSize = 18.sp, lineHeight = 26.sp)
                        Text("${occurrence.surface}${if (!occurrence.surface.equals(word, true)) " → 原型 $word" else ""} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(occurrence.queriedAt))}",
                            color = colors.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                        if (article != null) JumpLink("回到原文", colors) { onSource(occurrence) }
                    }
                }
                }
            }
        }
    }
    legacy.forEach { id -> Column(Modifier.padding(top = 14.dp)) {
        Text("历史查询 · ${content.articles.firstOrNull { it.id == id }?.title ?: id}", color = colors.muted, fontFamily = ReadingFont,
            fontSize = 12.sp, lineHeight = 19.sp)
        Text("旧版未保存具体词位与当时句义。", color = colors.muted, fontSize = 12.sp, lineHeight = 19.sp)
    } }
    if (occurrences.isEmpty() && legacy.isEmpty()) Text("尚无主动查词出现记录。", color = colors.muted,
        fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun StudyLibraries(content: Content, learning: LearningStore, state: List<StudyWord>, epoch: Int,
                           settings: SecureSettings, settingsRevision: Int, headerHeight: Dp, colors: Palette,
                           modifier: Modifier, onImport: () -> Unit, onDay: (String) -> Unit, onMessage: (String) -> Unit) {
    var selected by remember { mutableStateOf("builtin") }; var choosing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importCount = remember(settingsRevision) { settings.randomImportCount }
    val pulse by animateFloatAsState(if (importing) .94f else 1f, tween(230), label = "enroll feedback")
    val bank = content.banks.firstOrNull { it.id == selected } ?: content.banks.first()
    val queried = remember(epoch) { learning.queried() }
    val mastered = state.filter { it.mastered }.map { it.word }.toSet()
    val heat = remember(epoch) { learning.heatmap() }
    RetainedColumn(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(headerHeight))
        Text(bank.name + "  ⌄", Modifier.fillMaxWidth().clickable { choosing = !choosing }.padding(vertical = 15.dp), color = colors.ink,
            fontFamily = FontFamily.Serif, fontSize = 22.sp)
        RetainedFoldout(choosing, "banks") { Column { content.banks.forEachIndexed { i, option -> RevealElement(i, option.id) {
            Text(option.name, Modifier.fillMaxWidth().clickable { selected = option.id; choosing = false }.padding(vertical = 12.dp), color = colors.word, fontSize = 14.sp)
        } } } }
        Text("${bank.words.size} 个词元", color = colors.muted, fontSize = 11.sp)
        JumpLink(if (importing) "正在加入…" else "随机加入 $importCount 个词", colors,
            Modifier.padding(vertical = 24.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, enabled = !importing, size = 16) {
            importing = true
            scope.launch {
                try {
                    delay(180)
                    val count = withContext(Dispatchers.IO) { learning.randomImport(bank, importCount) }
                    onMessage(if (count > 0) "已加入 $count 个新词" else "没有尚未加入的词")
                    delay(650)
                } finally { importing = false }
            }
        }
        listOf("已学会" to bank.words.keys.count { it in mastered }, "已查询" to bank.words.keys.count { it in queried }).forEach { (label, count) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Bottom) {
                UiHeading(label, colors, Modifier.weight(1f), 14)
                val percentage = if (bank.words.isEmpty()) 0.0 else count * 100.0 / bank.words.size
                Text(if (count == 0) "0%" else java.lang.String.format(java.util.Locale.US, if (percentage < 1) "%.2f%%" else "%.1f%%", percentage),
                    color = colors.ink, fontFamily = ReadingFont, fontSize = 32.sp)
            }
            Text("$count / ${bank.words.size}", color = colors.muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.muted.copy(alpha = .1f))) {
                Box(Modifier.fillMaxWidth(if (bank.words.isEmpty()) 0f else count.toFloat() / bank.words.size).fillMaxHeight().background(colors.word))
            }
        }
        UiHeading("DAILY RHYTHM", colors, Modifier.padding(top = 34.dp, bottom = 12.dp), 24)
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
        JumpLink("导入词库", colors, Modifier.padding(vertical = 30.dp), onClick = onImport)
        BankManagementSection(bank, content, learning, colors, onMessage)
    }
}

@Composable
private fun PreparationProgress(progress: QuestionPreparation, colors: Palette) {
    val fraction by animateFloatAsState(progress.fraction, tween(320), label = "local preparation")
    Text(progress.label, color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
    Box(Modifier.fillMaxWidth().height(3.dp).background(colors.muted.copy(alpha = .12f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(colors.word))
    }
    Text("本地准备 · 不调用模型", color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun CelebrationMark(colors: Palette) {
    Canvas(Modifier.size(92.dp).padding(bottom = 24.dp)) {
        val c = center
        drawLine(colors.word, c + Offset(-16.dp.toPx(), 0f), c + Offset(-5.dp.toPx(), 10.dp.toPx()), 2.dp.toPx())
        drawLine(colors.word, c + Offset(-5.dp.toPx(), 10.dp.toPx()), c + Offset(19.dp.toPx(), -15.dp.toPx()), 2.dp.toPx())
        repeat(7) { i ->
            val a = i * 2 * Math.PI / 7
            val x = kotlin.math.cos(a).toFloat(); val y = kotlin.math.sin(a).toFloat()
            drawLine(if (i % 2 == 0) colors.gold else colors.word, c + Offset(x * 29.dp.toPx(), y * 29.dp.toPx()),
                c + Offset(x * 34.dp.toPx(), y * 34.dp.toPx()), 1.5.dp.toPx())
        }
    }
}
