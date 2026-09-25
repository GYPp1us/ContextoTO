package site.arcol.contextoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateChecker.cleanupInterrupted(this)
        val content = Content(this)
        val store = UserStore(this)
        val settings = SecureSettings(this)
        val engine = AnalysisEngine(content, store)
        setContent { ReaderApp(content, store, settings, engine) }
    }
}

private data class Palette(
    val paper: Color, val ink: Color, val muted: Color, val glass: Color, val sheet: Color,
    val word: Color, val paragraph: Color, val purple: Color, val gold: Color
)
private val ReadingFont = FontFamily(
    Font(R.font.tinos_regular, FontWeight.Normal),
    Font(R.font.tinos_bold, FontWeight.Bold)
)
private fun palette(dark: Boolean) = if (dark) Palette(
    Color(0xFF252525), Color(0xFFE8E7E1), Color(0xFFACAEAA), Color(0x66252525), Color(0xAA2D302E),
    Color(0xFF9FBEAF), Color(0xFFD2A18C), Color(0xFFB9A7CA), Color(0xFFD6BF91)
) else Palette(
    Color(0xFFF2F0EA), Color(0xFF262B29), Color(0xFF66716E), Color(0x55E6E8E4), Color(0xAAE9ECE8),
    Color(0xFF5D7B72), Color(0xFFA87562), Color(0xFF897999), Color(0xFFA48656)
)

private data class TokenGlyph(val token: Token, val rect: Rect, val baseline: Float, val sizePx: Float, val color: Color)
private data class WordTarget(val paragraphIndex: Int, val token: Token, val anchor: TokenGlyph, val inSentence: Boolean)
private data class SentenceTarget(val paragraphIndex: Int, val sentence: Sentence, val sourceGlyphs: List<TokenGlyph>)
private data class ReaderScale(val bodySize: Float, val lineFactor: Float, val sideMargin: Float, val paragraphGap: Float)
private data class ArticleSentence(val paragraphIndex: Int, val number: Int, val sentence: Sentence)
private data class CachedSentence(val item: ArticleSentence, val translation: String)
private data class SilentRunState(
    val active: ArticleSentence? = null, val progress: QueryProgress? = null,
    val error: String? = null, val running: Boolean = false
)

@Composable
private fun ReaderApp(content: Content, store: UserStore, settings: SecureSettings, engine: AnalysisEngine) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val initial = remember { store.getPlace() }
    var articleIndex by remember { mutableIntStateOf(content.articles.indexOfFirst { it.id == initial?.articleId }.coerceAtLeast(0)) }
    val article = content.articles[articleIndex]
    var dark by remember { mutableStateOf(settings.dark) }
    var markQueriedWords by remember { mutableStateOf(settings.markQueriedWords) }
    var silentInference by remember { mutableStateOf(settings.silentInference) }
    var focusUrl by remember { mutableStateOf(settings.focusUrl) }
    var focusSubjectId by remember { mutableIntStateOf(settings.focusSubjectId) }
    var focusItemId by remember { mutableIntStateOf(settings.focusItemId) }
    var focusStatus by remember { mutableStateOf("未配置专注上报") }
    var appResumed by remember { mutableStateOf(activity.lifecycle.currentState.isAtLeast(
        androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) appResumed = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) appResumed = false
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    var scale by remember { mutableStateOf(ReaderScale(settings.bodySize, settings.lineFactor, settings.sideMargin, settings.paragraphGap)) }
    val colors = palette(dark)
    SideEffect {
        activity.window.statusBarColor = if (dark) android.graphics.Color.rgb(37, 37, 37) else android.graphics.Color.rgb(242, 240, 234)
        activity.window.navigationBarColor = if (dark) android.graphics.Color.rgb(37, 37, 37) else android.graphics.Color.rgb(242, 240, 234)
        activity.window.isNavigationBarContrastEnforced = false
        activity.window.decorView.systemUiVisibility = if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    var provider by remember { mutableStateOf(settings.provider()) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("点击检查 GitHub 正式版") }
    var updateChecking by remember { mutableStateOf(false) }
    var updateOpen by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(DownloadProgress(DownloadPhase.CONNECTING)) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var updateFile by remember { mutableStateOf<File?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var installLaunched by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    var sentenceOpen by remember { mutableStateOf<SentenceTarget?>(null) }
    var wordTarget by remember { mutableStateOf<WordTarget?>(null) }
    var wordResult by remember { mutableStateOf<JSONObject?>(null) }
    var sentenceResult by remember { mutableStateOf<JSONObject?>(null) }
    var wordProgress by remember { mutableStateOf<QueryProgress?>(null) }
    var sentenceProgress by remember { mutableStateOf<QueryProgress?>(null) }
    var wordError by remember { mutableStateOf<String?>(null) }
    var sentenceError by remember { mutableStateOf<String?>(null) }
    var retryWord by remember { mutableIntStateOf(0) }
    var retrySentence by remember { mutableIntStateOf(0) }
    var retrySilent by remember { mutableIntStateOf(0) }
    var lookupEpoch by remember { mutableIntStateOf(0) }
    var cacheEpoch by remember { mutableIntStateOf(0) }
    val articleSentences = remember(article.id) {
        article.paragraphs.flatMapIndexed { paragraphIndex, text ->
            Content.sentences(text).filter { Content.tokens(it.text).isNotEmpty() }
                .map { paragraphIndex to it }
        }.mapIndexed { index, (paragraphIndex, sentence) -> ArticleSentence(paragraphIndex, index + 1, sentence) }
    }
    val cachedSentences = remember(article.id, provider, cacheEpoch) {
        articleSentences.mapNotNull { item -> engine.cachedSentence(provider, item.sentence.text)?.let {
            CachedSentence(item, it.optString("translation_zh"))
        } }
    }
    val silentPercent = if (articleSentences.isEmpty()) 100 else cachedSentences.size * 100 / articleSentences.size
    val percentLabel = "$silentPercent%"
    var silentRun by remember(article.id, provider) { mutableStateOf(SilentRunState()) }
    var silentOpen by remember { mutableStateOf(false) }
    var percentAnchor by remember { mutableStateOf<TokenGlyph?>(null) }
    var flightAnchor by remember { mutableStateOf<TokenGlyph?>(null) }
    var flightPercent by remember { mutableStateOf(percentLabel) }
    var percentSourceLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var percentDestination by remember { mutableStateOf<TokenGlyph?>(null) }
    val percentMotion = remember { Animatable(0f) }
    var lastSentence by remember { mutableStateOf<SentenceTarget?>(null) }
    var lastWord by remember { mutableStateOf<WordTarget?>(null) }
    var sentenceDestination by remember { mutableStateOf<List<TokenGlyph>>(emptyList()) }
    var wordDestination by remember { mutableStateOf<TokenGlyph?>(null) }
    val sentenceMotion = remember { Animatable(0f) }
    val wordMotion = remember { Animatable(0f) }
    LaunchedEffect(silentOpen, percentDestination != null) {
        if (silentOpen && percentDestination != null) {
            percentMotion.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        } else if (!silentOpen) {
            percentMotion.animateTo(0f, tween(310, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(sentenceOpen) { if (sentenceOpen != null) lastSentence = sentenceOpen }
    LaunchedEffect(wordTarget) { if (wordTarget != null) lastWord = wordTarget }
    LaunchedEffect(sentenceOpen, sentenceDestination.isNotEmpty()) {
        if (sentenceOpen != null && sentenceDestination.isNotEmpty()) {
            sentenceMotion.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        } else if (sentenceOpen == null) {
            sentenceMotion.animateTo(0f, tween(330, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(wordTarget, wordDestination != null) {
        if (wordTarget != null && wordDestination != null) {
            wordMotion.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        } else if (wordTarget == null) {
            wordMotion.animateTo(0f, tween(310, easing = FastOutSlowInEasing))
        }
    }
    val savedItem = if (article.id == initial?.articleId) initial.item else 0
    val savedOffset = if (article.id == initial?.articleId) initial.offset else 0
    val listState = remember(article.id) { androidx.compose.foundation.lazy.LazyListState(savedItem, savedOffset) }

    LaunchedEffect(article.id) {
        silentOpen = false
        percentAnchor = null
        flightAnchor = null
        percentDestination = null
        percentMotion.snapTo(0f)
    }

    fun closeUpdate() {
        updateJob?.cancel()
        updateJob = null
        if (!installLaunched) UpdateChecker.discardReady(activity)
        updateFile = null
        updateOpen = false
        updateError = null
    }
    fun startDownload(info: UpdateInfo) {
        if (updateOpen) return
        updateOpen = true
        updateProgress = DownloadProgress(DownloadPhase.CONNECTING)
        updateError = null
        updateFile = null
        installLaunched = false
        updateJob = updateScope.launch {
            try {
                updateFile = UpdateChecker.download(activity, info) { updateProgress = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateError = error.message ?: "下载失败，请重试"
            } finally {
                if (updateJob == currentCoroutineContext().job) updateJob = null
            }
        }
    }
    DisposableEffect(activity, updateOpen, updateJob) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && updateOpen && updateJob?.isActive == true)
                closeUpdate()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    fun closeTop() {
        when {
            updateOpen -> closeUpdate()
            settingsOpen -> settingsOpen = false
            silentOpen -> {
                flightPercent = percentLabel
                flightAnchor = percentAnchor
                silentOpen = false
            }
            wordTarget != null -> wordTarget = null
            sentenceOpen != null -> sentenceOpen = null
            drawerOpen -> drawerOpen = false
        }
    }
    BackHandler(updateOpen || settingsOpen || silentOpen || wordTarget != null || sentenceOpen != null || drawerOpen) { closeTop() }

    LaunchedEffect(article.id, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(350).collect { (item, offset) ->
                store.savePlace(article.id, item, offset)
            }
    }
    LaunchedEffect(focusUrl, focusSubjectId, focusItemId, appResumed, drawerOpen, settingsOpen, updateOpen) {
        if (focusUrl.isBlank() || focusSubjectId <= 0 || focusItemId <= 0) {
            focusStatus = "未配置专注上报"
            return@LaunchedEffect
        }
        if (!appResumed || drawerOpen || settingsOpen || updateOpen) {
            focusStatus = "阅读暂停 · 已停止上报"
            return@LaunchedEffect
        }
        focusStatus = "正在连接专注目录…"
        try {
            FocusReporter.track(focusUrl, focusSubjectId, focusItemId) { focusStatus = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            focusStatus = error.message ?: "专注上报不可用"
        }
    }
    LaunchedEffect(article.id, provider, silentInference, retrySilent) {
        silentRun = SilentRunState()
        if (!silentInference) return@LaunchedEffect
        if (provider.key.isBlank()) return@LaunchedEffect
        silentRun = SilentRunState(running = true)
        for (item in articleSentences.distinctBy { it.sentence.text }) {
            currentCoroutineContext().ensureActive()
            if (engine.cachedSentence(provider, item.sentence.text) != null) continue
            silentRun = SilentRunState(active = item, running = true)
            try {
                engine.sentence(provider, article, item.sentence) { progress ->
                    silentRun = silentRun.copy(progress = progress)
                }
                cacheEpoch++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                silentRun = SilentRunState(active = item, error = readableQueryError(error))
                return@LaunchedEffect
            }
            delay(350)
        }
        silentRun = SilentRunState()
    }
    LaunchedEffect(wordTarget, retryWord, provider) {
        val target = wordTarget ?: return@LaunchedEffect
        wordResult = null; wordProgress = null; wordError = null
        val text = if (target.paragraphIndex < 0) article.title
            else article.paragraphs.getOrNull(target.paragraphIndex) ?: return@LaunchedEffect
        val cached = engine.cachedWord(provider, text, target.token)
        if (cached != null) {
            wordResult = cached
            store.recordLookup(article.id, "word", content.lemma(target.token.text)); lookupEpoch++
        } else if (provider.key.isBlank()) {
            wordError = "尚未配置 API Key，可先查看预置词典。"
        } else {
            runCatching {
                engine.word(provider, article, target.paragraphIndex, target.token) { wordProgress = it }
            }.onSuccess {
                wordResult = it; wordProgress = null
                store.recordLookup(article.id, "word", content.lemma(target.token.text)); lookupEpoch++
            }.onFailure { wordError = readableQueryError(it); wordProgress = null }
        }
    }
    LaunchedEffect(sentenceOpen, retrySentence, provider) {
        val target = sentenceOpen ?: return@LaunchedEffect
        sentenceResult = null; sentenceProgress = null; sentenceError = null
        val cached = engine.cachedSentence(provider, target.sentence.text)
        if (cached != null) {
            sentenceResult = cached
            store.recordLookup(article.id, "sentence", "${target.paragraphIndex}:${target.sentence.start}"); lookupEpoch++
            cacheEpoch++
        } else if (provider.key.isBlank()) {
            sentenceError = "尚未配置 API Key。英文原句仍可阅读。"
        } else {
            runCatching { engine.sentence(provider, article, target.sentence) { sentenceProgress = it } }
                .onSuccess {
                    sentenceResult = it; sentenceProgress = null
                    store.recordLookup(article.id, "sentence", "${target.paragraphIndex}:${target.sentence.start}"); lookupEpoch++
                    cacheEpoch++
                }.onFailure { sentenceError = readableQueryError(it); sentenceProgress = null }
        }
    }

    val hasOverlay = drawerOpen || settingsOpen || silentOpen || sentenceOpen != null || wordTarget != null
    val blur by animateFloatAsState(if (hasOverlay) 26f else 0f, tween(290, easing = FastOutSlowInEasing), label = "body blur")
    val updateBlur by animateFloatAsState(if (updateOpen) 26f else 0f,
        tween(290, easing = FastOutSlowInEasing), label = "settings blur")

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.paper)) {
        val width = maxWidth
        val height = maxHeight
        val density = LocalDensity.current
        val topReserve = with(density) { (scale.bodySize * scale.lineFactor * .8f).sp.toDp() }
        val drawerShift by animateFloatAsState(if (drawerOpen) with(density) { (width * .60f).toPx() } else 0f,
            tween(340, easing = FastOutSlowInEasing), label = "drawer shift")
        Box(Modifier.fillMaxSize().pointerInput(article.id, hasOverlay) {
            var distance = 0f
            detectHorizontalDragGestures(onHorizontalDrag = { _, delta -> distance += delta },
                onDragEnd = {
                    if (!hasOverlay && abs(distance) > 58f) drawerOpen = true
                    distance = 0f
                })
        }.graphicsLayer {
            translationX = drawerShift
            renderEffect = if (blur > 0.1f) BlurEffect(blur, blur, TileMode.Clamp) else null
        }) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(top = topReserve)) {
                Row(Modifier.fillMaxWidth().padding(start = scale.sideMargin.dp, end = scale.sideMargin.dp, top = 22.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("≡", Modifier.clickable { drawerOpen = true }.padding(end = 18.dp), color = colors.ink, fontSize = 27.sp)
                    Text("ContextoTO", color = colors.ink, fontFamily = ReadingFont, fontSize = 19.sp)
                    Spacer(Modifier.weight(1f))
                    Text(article.id.uppercase(), color = colors.muted, fontSize = 12.sp, fontFamily = ReadingFont)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(58.dp).height(42.dp).clickable {
                        flightPercent = percentLabel
                        flightAnchor = percentAnchor
                        percentDestination = null
                        silentOpen = true
                    }, contentAlignment = Alignment.CenterEnd) {
                    Text(percentLabel, Modifier.graphicsLayer { alpha = if (percentDestination != null &&
                        (silentOpen || percentMotion.value > .001f)) 0f else 1f }
                        .onGloballyPositioned { coordinates ->
                            val layout = percentSourceLayout ?: return@onGloballyPositioned
                            val measured = layout.layoutInput.text.text
                            if (measured.isEmpty()) return@onGloballyPositioned
                            val position = coordinates.positionInRoot()
                            val first = layout.getBoundingBox(0)
                            val last = layout.getBoundingBox(measured.lastIndex)
                            percentAnchor = TokenGlyph(Token(measured, 0, measured.length),
                                Rect(position.x + first.left, position.y + first.top,
                                    position.x + last.right, position.y + first.bottom),
                                position.y + layout.getLineBaseline(0), with(density) { 12.sp.toPx() }, colors.word)
                        }, onTextLayout = { percentSourceLayout = it }, color = colors.word,
                        fontFamily = ReadingFont, fontSize = 12.sp)
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = scale.sideMargin.dp, end = scale.sideMargin.dp, bottom = 64.dp
                )) {
                    item {
                        Text("READING / ${articleIndex + 1} OF 48", color = colors.word, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(10.dp))
                        val sourceSentence = sentenceOpen ?: lastSentence.takeIf { sentenceMotion.value > .001f }
                        val sourceWord = wordTarget ?: lastWord.takeIf { wordMotion.value > .001f }
                        val hidden = when {
                            sourceSentence?.paragraphIndex == -1 -> sourceSentence.sentence.start to sourceSentence.sentence.end
                            sourceWord?.paragraphIndex == -1 && !sourceWord.inSentence ->
                                sourceWord.token.start to sourceWord.token.end
                            else -> null
                        }
                        InteractiveParagraph(article.title, colors, null, content,
                            if (markQueriedWords) store.queriedWords(article.id) else emptySet(),
                            scale.copy(bodySize = 24f, lineFactor = 1.25f), Modifier.fillMaxWidth(),
                            onWord = { token, anchor ->
                                wordDestination = null
                                wordTarget = WordTarget(-1, token, anchor, false)
                            }, onLongWord = { token, glyphs ->
                                val sentence = Content.sentenceAt(article.title, token.start)
                                sentenceDestination = emptyList()
                                sentenceOpen = SentenceTarget(-1, sentence, glyphs.map { glyph ->
                                    glyph.copy(token = glyph.token.copy(
                                        start = glyph.token.start - sentence.start,
                                        end = glyph.token.end - sentence.start))
                                })
                            }, hidden = hidden, titleMode = true)
                        Spacer(Modifier.height(28.dp))
                    }
                    itemsIndexed(article.paragraphs) { index, text ->
                        val sourceSentence = sentenceOpen ?: lastSentence.takeIf { sentenceMotion.value > .001f }
                        val sourceWord = wordTarget ?: lastWord.takeIf { wordMotion.value > .001f }
                        val hidden = when {
                            sourceSentence?.paragraphIndex == index ->
                                sourceSentence.sentence.start to sourceSentence.sentence.end
                            sourceWord?.paragraphIndex == index && !sourceWord.inSentence ->
                                sourceWord.token.start to sourceWord.token.end
                            else -> null
                        }
                        InteractiveParagraph(text, colors, null, content,
                            if (markQueriedWords) store.queriedWords(article.id) else emptySet(), scale,
                            Modifier.fillMaxWidth().padding(bottom = scale.paragraphGap.dp),
                            onWord = { token, anchor ->
                                wordDestination = null
                                wordTarget = WordTarget(index, token, anchor, false)
                            },
                            onLongWord = { token, glyphs ->
                                val sentence = Content.sentenceAt(text, token.start)
                                sentenceDestination = emptyList()
                                sentenceOpen = SentenceTarget(index, sentence, glyphs.map { glyph ->
                                    glyph.copy(token = glyph.token.copy(
                                        start = glyph.token.start - sentence.start,
                                        end = glyph.token.end - sentence.start))
                                })
                            }, hidden = hidden)
                    }
                }
            }
        }
        if (drawerOpen) GlassScrim(colors) { drawerOpen = false }
        AnimatedVisibility(drawerOpen, Modifier.align(Alignment.CenterStart),
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 5 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(290, easing = FastOutSlowInEasing)) { -it / 5 }) {
            ArticleDrawer(content, store, article.id, lookupEpoch, colors,
                Modifier.width(width * .80f).fillMaxHeight().navigationBarsPadding().padding(top = topReserve),
                onSelect = { index ->
                    articleIndex = index; wordTarget = null; sentenceOpen = null; drawerOpen = false
                    store.savePlace(content.articles[index].id, 0, 0)
                }, onSettings = { drawerOpen = false; settingsOpen = true })
        }

        val activeSentence = sentenceOpen ?: lastSentence
        if (sentenceOpen != null) GlassScrim(colors) { sentenceOpen = null; wordTarget = null }
        if (activeSentence != null) {
            val sentenceY = topReserve + 8.dp
            val secondBlur by animateFloatAsState(if (wordTarget?.inSentence == true) 23f else 0f,
                tween(220), label = "sentence behind word blur")
            AnimatedVisibility(sentenceOpen != null,
                enter = fadeIn(tween(220)), exit = fadeOut(tween(340))) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = if (secondBlur > .1f) BlurEffect(secondBlur, secondBlur, TileMode.Clamp) else null
                }) {
                    SentenceSheet(article, activeSentence, sentenceResult, sentenceProgress, sentenceError,
                        content, store, colors, scale, markQueriedWords, sentenceMotion.value,
                        (wordTarget ?: lastWord.takeIf { wordMotion.value > .001f })?.takeIf { it.inSentence },
                        Modifier.align(Alignment.TopCenter).offset(y = sentenceY).fillMaxWidth()
                            .heightIn(max = height - sentenceY - 18.dp),
                        onGeometry = { sentenceDestination = it },
                        onWord = { token, anchor ->
                            val absolute = Token(token.text, token.start + activeSentence.sentence.start,
                                token.end + activeSentence.sentence.start)
                            wordDestination = null
                            wordTarget = WordTarget(activeSentence.paragraphIndex, absolute,
                                anchor.copy(token = absolute), true)
                        }, onRetry = { retrySentence++ })
                }
            }
        }
        if (wordTarget?.inSentence == true) GlassScrim(colors) { wordTarget = null }

        if (wordTarget != null && wordTarget?.inSentence == false) GlassScrim(colors) { wordTarget = null }
        val displayWord = wordTarget ?: lastWord
        AnimatedVisibility(wordTarget != null,
            enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
            if (displayWord != null) WordSheet(displayWord, article, content, wordResult, wordProgress, wordError, colors,
                maxWidth, maxHeight, topReserve, wordMotion.value, onTitleGeometry = { wordDestination = it },
                onRetry = { retryWord++ }, onConfigure = {
                    wordTarget = null; sentenceOpen = null; settingsOpen = true
                })
        }

        val flightSentence = sentenceOpen ?: lastSentence
        if (flightSentence != null && sentenceDestination.isNotEmpty()) {
            MotionGlyphs(flightSentence.sourceGlyphs, sentenceDestination, sentenceMotion.value, sentenceOpen != null)
        }
        val flightWord = wordTarget ?: lastWord
        if (flightWord != null && wordDestination != null) {
            MotionGlyphs(listOf(flightWord.anchor), listOf(wordDestination!!), wordMotion.value, wordTarget != null)
        }

        if (silentOpen) GlassScrim(colors) {
            flightPercent = percentLabel
            flightAnchor = percentAnchor
            silentOpen = false
        }
        AnimatedVisibility(silentOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
            SilentSheet(article, articleSentences, cachedSentences, silentRun, silentInference,
                provider.key.isNotBlank(),
                if (percentMotion.value < 1f) flightPercent else percentLabel,
                colors, maxWidth, maxHeight, topReserve, percentMotion.value,
                onPercentGeometry = { percentDestination = it }, onRetry = { retrySilent++ })
        }
        if (flightAnchor != null && percentDestination != null) {
            MotionGlyphs(listOf(flightAnchor!!), listOf(percentDestination!!), percentMotion.value, silentOpen)
        }

        if (settingsOpen) {
            GlassScrim(colors) { settingsOpen = false }
            SettingsSheet(colors, dark, markQueriedWords, silentInference, provider, settings,
                updateStatus, updateInfo, updateChecking, focusStatus,
                Modifier.align(Alignment.TopCenter).fillMaxSize().statusBarsPadding()
                    .navigationBarsPadding().padding(top = topReserve).graphicsLayer {
                        renderEffect = if (updateBlur > .1f) BlurEffect(updateBlur, updateBlur, TileMode.Clamp) else null
                    },
                onDark = { dark = it; settings.dark = it },
                onMarkQueriedWords = { markQueriedWords = it; settings.markQueriedWords = it },
                onSilentInference = { silentInference = it; settings.silentInference = it },
                onCheckUpdate = {
                    if (!updateChecking) updateScope.launch {
                        updateChecking = true
                        updateStatus = "正在检查…"
                        updateInfo = null
                        try {
                            val info = UpdateChecker.check()
                            updateInfo = info.takeIf { it.available }
                            updateStatus = if (info.available) "发现 ${info.latest} · 点击在应用内下载"
                                else "已是最新版本 · v${info.current}"
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            updateStatus = error.message ?: "检查失败，请检查网络"
                        } finally {
                            updateChecking = false
                        }
                    }
                },
                onDownloadUpdate = { updateInfo?.let(::startDownload) },
                onSave = {
                    provider = settings.provider()
                    scale = ReaderScale(settings.bodySize, settings.lineFactor, settings.sideMargin, settings.paragraphGap)
                    focusUrl = settings.focusUrl
                    focusSubjectId = settings.focusSubjectId
                    focusItemId = settings.focusItemId
                    settingsOpen = false
                })
        }
        AnimatedVisibility(updateOpen, enter = fadeIn(tween(180)), exit = fadeOut(tween(260))) {
            Box(Modifier.fillMaxSize()) {
            GlassScrim(colors) { closeUpdate() }
            UpdateDownloadSheet(updateInfo?.latest.orEmpty(), updateProgress, updateError, updateFile != null,
                colors, width, topReserve,
                onCancel = { closeUpdate() },
                onRetry = { updateInfo?.let { info -> closeUpdate(); startDownload(info) } },
                onInstall = {
                    val apk = updateFile
                    if (apk != null) {
                        try {
                            if (!activity.packageManager.canRequestPackageInstalls()) {
                                activity.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:${activity.packageName}")))
                            } else {
                                val uri = androidx.core.content.FileProvider.getUriForFile(activity,
                                    "${activity.packageName}.updates", apk)
                                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                installLaunched = true
                            }
                        } catch (error: Exception) {
                            updateError = error.message ?: "无法打开系统安装器"
                        }
                    }
                })
            }
        }

    }
}

private fun readableQueryError(error: Throwable): String = when {
    error is java.net.SocketTimeoutException || error.message?.contains("timeout", ignoreCase = true) == true ->
        "模型等待超时，可能仍在排队或推理。请稍后重试。"
    error is org.json.JSONException -> "模型结果格式异常，请重试。"
    error.message?.startsWith("模型接口返回 HTTP") == true -> error.message.orEmpty()
    else -> error.message ?: "解析失败，请稍后重试。"
}

@Composable
private fun GlassScrim(colors: Palette, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(colors.glass).clickable(onClick = onDismiss))
}

@Composable
private fun InteractiveParagraph(
    text: String, colors: Palette, analysis: JSONObject?, content: Content, queried: Set<String>,
    scale: ReaderScale, modifier: Modifier = Modifier, onWord: (Token, TokenGlyph) -> Unit,
    onLongWord: (Token, List<TokenGlyph>) -> Unit,
    onGeometry: ((List<TokenGlyph>) -> Unit)? = null, hidden: Pair<Int, Int>? = null,
    titleMode: Boolean = false
) {
    var result by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var position by remember(text) { mutableStateOf(Offset.Zero) }
    val fontPx = with(LocalDensity.current) { scale.bodySize.sp.toPx() }
    val annotated = remember(text, analysis, queried, colors, hidden, titleMode, scale.bodySize) {
        annotateParagraph(text, analysis, content, queried, colors, hidden,
            if (titleMode) scale.bodySize else null)
    }
    fun glyph(token: Token, layout: TextLayoutResult): TokenGlyph {
        val first = layout.getBoundingBox(token.start)
        val last = layout.getBoundingBox(token.end - 1)
        val style = annotated.spanStyles.lastOrNull { token.start in it.start until it.end }
        val displayToken = if (titleMode) token.copy(text = token.text.uppercase()) else token
        val glyphSize = if (titleMode && token.start != text.indexOfFirst { it.isLetter() } &&
            text[token.start].isLowerCase()) fontPx * .78f else fontPx
        return TokenGlyph(displayToken, Rect(first.left + position.x, first.top + position.y,
            last.right + position.x, first.bottom + position.y),
            layout.getLineBaseline(layout.getLineForOffset(token.start)) + position.y,
            glyphSize, style?.item?.color ?: colors.ink)
    }
    Text(annotated, modifier.onGloballyPositioned {
        position = it.positionInRoot()
        result?.let { layout -> onGeometry?.invoke(Content.tokens(text).map { token -> glyph(token, layout) }) }
    }
        .pointerInput(text, result) {
            detectTapGestures(
                onTap = { point ->
                    val layout = result ?: return@detectTapGestures
                    val token = Content.tokenAt(text, layout.getOffsetForPosition(point)) ?: return@detectTapGestures
                    val box = layout.getBoundingBox(token.start)
                    if (point.y >= box.top - 5 && point.y <= box.bottom + 5) {
                        onWord(token, glyph(token, layout))
                    }
                },
                onLongPress = { point ->
                    val layout = result ?: return@detectTapGestures
                    val token = Content.tokenAt(text, layout.getOffsetForPosition(point)) ?: return@detectTapGestures
                    val sentence = Content.sentenceAt(text, token.start)
                    onLongWord(token, Content.tokens(text).filter { it.start >= sentence.start && it.end <= sentence.end }
                        .map { glyph(it, layout) })
                }
            )
        }, onTextLayout = { result = it }, color = colors.ink, fontFamily = ReadingFont,
        fontSize = scale.bodySize.sp, lineHeight = (scale.bodySize * scale.lineFactor).sp)
}

@Composable
private fun MotionGlyphs(source: List<TokenGlyph>, destination: List<TokenGlyph>, progress: Float, active: Boolean) {
    if (progress >= 1f || (progress <= .001f && !active) || source.isEmpty() || destination.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val regular = remember { context.resources.getFont(R.font.tinos_regular) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = regular } }
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val targetByStart = destination.associateBy { it.token.start }
            source.forEachIndexed { index, from ->
                val to = targetByStart[from.token.start] ?: return@forEachIndexed
                val stagger = (index * .018f).coerceAtMost(.25f)
                val t = ((progress - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                paint.color = lerp(from.color, to.color, t).toArgb()
                paint.alpha = 255
                paint.textSize = from.sizePx + (to.sizePx - from.sizePx) * t
                canvas.nativeCanvas.drawText(from.token.text,
                    from.rect.left + (to.rect.left - from.rect.left) * t,
                    from.baseline + (to.baseline - from.baseline) * t, paint)
            }
        }
    }
}

@Composable
private fun RevealElement(order: Int, revealKey: Any, modifier: Modifier = Modifier,
                          content: @Composable () -> Unit) {
    val reveal = remember(revealKey) { Animatable(0f) }
    val liftPx = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(revealKey, order) {
        val wait = (order * 28).coerceAtMost(280)
        delay(wait.toLong())
        reveal.animateTo(1f, tween(400 - wait, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer {
        val fraction = reveal.value
        alpha = fraction
        scaleX = .70f + .30f * fraction
        scaleY = .70f + .30f * fraction
        translationY = -liftPx * (1f - fraction)
        transformOrigin = TransformOrigin(.5f, 0f)
    }) { content() }
}

private fun annotateParagraph(text: String, analysis: JSONObject?, content: Content, queried: Set<String>,
                              colors: Palette, hidden: Pair<Int, Int>?, titleSize: Float? = null): AnnotatedString =
    buildAnnotatedString {
        if (titleSize != null) append(smallCapsTitle(text, titleSize)) else append(text)
        for (token in Content.tokens(text)) {
            val lemma = content.lemma(token.text)
            if (lemma in queried) addStyle(SpanStyle(color = colors.paragraph), token.start, token.end)
        }
        val clauses = analysis?.optJSONArray("clauses")
        val clauseColors = listOf(colors.word, colors.purple, colors.gold, colors.paragraph)
        if (clauses != null) for (i in 0 until clauses.length()) {
            val item = clauses.optJSONObject(i) ?: continue
            val start = item.optInt("start", -1); val end = item.optInt("end", -1)
            if (start in 0 until end && end <= text.length) addStyle(SpanStyle(color = clauseColors[i % clauseColors.size]), start, end)
        }
        if (hidden != null && hidden.first in 0 until hidden.second && hidden.second <= text.length) {
            addStyle(SpanStyle(color = Color.Transparent), hidden.first, hidden.second)
        }
    }

private fun smallCapsTitle(title: String, fullSize: Float): AnnotatedString = buildAnnotatedString {
    val firstLetter = title.indexOfFirst { it.isLetter() }
    title.forEachIndexed { index, char ->
        val start = length
        append(if (char.isLowerCase()) char.uppercaseChar() else char)
        if (char.isLowerCase() && index != firstLetter) {
            addStyle(SpanStyle(fontSize = (fullSize * .78f).sp), start, length)
        }
    }
}

@Composable
private fun ArticleDrawer(
    content: Content, store: UserStore, activeId: String, epoch: Int, colors: Palette, modifier: Modifier,
    onSelect: (Int) -> Unit, onSettings: () -> Unit
) {
    val lastId = remember(epoch) { store.getPlace()?.articleId ?: activeId }
    Column(modifier.background(colors.sheet).padding(top = 30.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 25.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ARTICLES", color = colors.ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            Spacer(Modifier.weight(1f))
            Text("48", color = colors.word, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text("继续阅读 / 已查询词 / 已查询句", Modifier.padding(horizontal = 25.dp), color = colors.muted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(content.articles) { index, article ->
                val words = remember(epoch, article.id) { store.countLookups(article.id, "word") }
                val sentences = remember(epoch, article.id) { store.countLookups(article.id, "sentence") }
                Column(Modifier.fillMaxWidth().clickable { onSelect(index) }.padding(horizontal = 25.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}".padStart(2, '0'), color = colors.word, fontSize = 13.sp,
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        if (article.id == lastId) Text("上次阅读", color = colors.paragraph, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(smallCapsTitle(article.title, 18f), color = colors.ink, fontFamily = ReadingFont, fontSize = 18.sp,
                        lineHeight = 24.sp, fontWeight = if (article.id == lastId) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("词 $words", color = colors.word, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("句 $sentences", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(25.dp)) {
            Text("接口与外观设置", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("→", color = colors.word, fontSize = 17.sp)
        }
    }
}

@Composable
private fun SilentSheet(
    article: Article, sentences: List<ArticleSentence>, cached: List<CachedSentence>, run: SilentRunState,
    enabled: Boolean, hasKey: Boolean, percent: String, colors: Palette, screenWidth: androidx.compose.ui.unit.Dp,
    screenHeight: androidx.compose.ui.unit.Dp, topReserve: androidx.compose.ui.unit.Dp, motion: Float,
    onPercentGeometry: (TokenGlyph) -> Unit, onRetry: () -> Unit
) {
    val density = LocalDensity.current
    val panelWidth = screenWidth * .86f
    val y = topReserve + 47.dp
    val panelHeight = (screenHeight - y - 20.dp).coerceAtLeast(240.dp)
    val fraction by animateFloatAsState(
        if (sentences.isEmpty()) 1f else cached.size.toFloat() / sentences.size,
        tween(650, easing = FastOutSlowInEasing), label = "silent cached fraction")
    var percentLayout by remember(percent) { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.offset(x = (screenWidth - panelWidth) / 2, y = y)
        .width(panelWidth).height(panelHeight).navigationBarsPadding().padding(horizontal = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            RevealElement(0, article.id) {
                Text("SILENT / ${article.id.uppercase()}", color = colors.word,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                    modifier = Modifier.padding(top = 15.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(percent, Modifier.graphicsLayer { alpha = if (motion >= 1f) 1f else 0f }
                .onGloballyPositioned { coordinates ->
                    val layout = percentLayout ?: return@onGloballyPositioned
                    val measured = layout.layoutInput.text.text
                    if (measured.isEmpty()) return@onGloballyPositioned
                    val position = coordinates.positionInRoot()
                    val first = layout.getBoundingBox(0)
                    val last = layout.getBoundingBox(measured.lastIndex)
                    onPercentGeometry(TokenGlyph(Token(measured, 0, measured.length),
                        Rect(position.x + first.left, position.y + first.top,
                            position.x + last.right, position.y + first.bottom),
                        position.y + layout.getLineBaseline(0), with(density) { 42.sp.toPx() }, colors.ink))
                }, onTextLayout = { percentLayout = it }, color = colors.ink,
                fontFamily = ReadingFont, fontSize = 42.sp, fontWeight = FontWeight.Normal)
        }
        Spacer(Modifier.height(13.dp))
        RevealElement(1, article.id) {
            Text("静默推理", color = colors.ink, fontFamily = FontFamily.Serif,
                fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        RevealElement(2, article.id to cached.size) {
            Text("${cached.size} / ${sentences.size} 句已缓存", color = colors.muted, fontSize = 13.sp)
        }
        Text(
            if (enabled) "串行后台请求 · 消耗所选服务额度" else "后台请求已停用 · 不消耗模型额度",
            color = colors.muted,
            fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.muted.copy(alpha = .22f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(colors.word))
        }
        Spacer(Modifier.height(17.dp))
        val active = run.active
        val status = when {
            !enabled -> "静默推理已关闭 · 已有缓存仍可查看"
            !hasKey -> "未配置 API Key · 请在设置中选择服务"
            run.error != null -> "推理已停下 · ${run.error}"
            active != null -> "正在处理第 ${active.number} / ${sentences.size} 句 · 第 ${active.paragraphIndex + 1} 段"
            run.running -> "正在检查缓存"
            cached.size == sentences.size -> "当前文章已全部缓存"
            else -> "等待开始"
        }
        RevealElement(3, article.id to status) {
            Text(status, color = if (run.error == null) colors.word else colors.paragraph,
                fontSize = 13.sp, lineHeight = 19.sp)
        }
        if (enabled && active != null && run.error == null) {
            val phase = when (run.progress?.phase) {
                QueryPhase.CONTEXT, null -> "拼接上下文"
                QueryPhase.FIRST -> "等待首字返回"
                QueryPhase.STREAM, QueryPhase.COMPLETE -> "接收完整结果"
            }
            val tokens = run.progress?.exactReasoningTokens ?: run.progress?.approximateReasoningTokens ?: 0
            Text("$phase${if (tokens > 0) " · 已推理约 $tokens tokens" else ""}",
                color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            Text(active.sentence.text, color = colors.muted, fontFamily = ReadingFont,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 9.dp))
        }
        if (enabled && run.error != null && hasKey) Text("重试静默推理 →", Modifier.clickable(onClick = onRetry)
            .padding(top = 12.dp), color = colors.word, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        RevealElement(4, article.id) {
            Text("已缓存内容", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState())) {
            if (cached.isEmpty()) Text("完成的句子会显示在这里。", color = colors.muted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            cached.forEach { entry ->
                RevealElement(5 + entry.item.number, entry.item, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                        Text("${entry.item.number.toString().padStart(2, '0')}  /  第 ${entry.item.paragraphIndex + 1} 段",
                            color = colors.word, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(entry.item.sentence.text, color = colors.ink, fontFamily = ReadingFont,
                            fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        Text(entry.translation, color = colors.muted, fontFamily = FontFamily.Serif,
                            fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SentenceSheet(
    article: Article, target: SentenceTarget, result: JSONObject?, progress: QueryProgress?, error: String?,
    content: Content, store: UserStore, colors: Palette, scale: ReaderScale,
    markQueriedWords: Boolean, motion: Float,
    nestedWord: WordTarget?, modifier: Modifier,
    onGeometry: (List<TokenGlyph>) -> Unit, onWord: (Token, TokenGlyph) -> Unit, onRetry: () -> Unit
) {
    val text = target.sentence.text
    val queried = remember(article.id, result, markQueriedWords) {
        if (markQueriedWords) store.queriedWords(article.id) else emptySet()
    }
    Column(modifier.navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp)) {
        RevealElement(0, target) {
            Text("SENTENCE / ${article.id.uppercase()}", color = colors.paragraph, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        }
        Spacer(Modifier.height(13.dp))
        InteractiveParagraph(text, colors, result, content, queried,
            scale.copy(bodySize = (scale.bodySize * 1.12f).coerceAtLeast(18f), lineFactor = 1.48f),
            Modifier.graphicsLayer { alpha = if (motion >= 1f) 1f else 0f },
            onWord = onWord, onLongWord = { _, _ -> }, onGeometry = onGeometry,
            hidden = nestedWord?.let { (it.token.start - target.sentence.start) to
                (it.token.end - target.sentence.start) },
            titleMode = target.paragraphIndex < 0)
        Spacer(Modifier.height(25.dp))
        if (result != null) {
            RevealElement(1, result) {
                Text("整句释义", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            RevealElement(2, result) {
                Text(result.optString("translation_zh"), color = colors.ink, fontSize = 19.sp, lineHeight = 30.sp,
                    fontFamily = FontFamily.Serif)
            }
            val clauses = result.optJSONArray("clauses")
            if (clauses != null && clauses.length() > 0) {
                Spacer(Modifier.height(25.dp))
                RevealElement(3, result) {
                    Text("从句范围", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                val clauseColors = listOf(colors.word, colors.purple, colors.gold, colors.paragraph)
                for (i in 0 until clauses.length()) {
                    val clause = clauses.optJSONObject(i) ?: continue
                    RevealElement(4 + i, result to i, Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                            Text("${i + 1}".padStart(2, '0'), color = clauseColors[i % clauseColors.size],
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(clause.optString("kind"), color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(clause.optString("quote"), color = colors.muted, fontFamily = FontFamily.Serif, fontSize = 14.sp,
                                    lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(clause.optString("brief_zh"), color = colors.muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            val glosses = result.optJSONArray("glosses")
            if (glosses != null && glosses.length() > 0) {
                Spacer(Modifier.height(15.dp))
                RevealElement(6, result) {
                    Text("本句词语", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                for (i in 0 until glosses.length()) {
                    val item = glosses.optJSONObject(i) ?: continue
                    val word = item.optString("quote")
                    val labels = labelsFor(content.lexeme(word)).joinToString(" · ")
                    RevealElement(7 + i, result to (100 + i), Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 19.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                Text(word, color = colors.ink, fontFamily = ReadingFont,
                                    fontSize = 21.sp, fontWeight = FontWeight.Normal,
                                    modifier = Modifier.weight(1f))
                                Text("${i + 1}".padStart(2, '0'), color = colors.word,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            if (labels.isNotBlank()) Text(labels, color = colors.word, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                            SenseLine("句义", item.optString("brief_zh"), colors)
                        }
                    }
                }
            }
        } else if (error != null) {
            RevealElement(1, error) {
                Text(error, color = colors.paragraph, fontSize = 14.sp)
            }
            RevealElement(2, error) {
                Text("重试解析 →", Modifier.clickable(onClick = onRetry).padding(top = 12.dp), color = colors.word, fontSize = 14.sp)
            }
        } else {
            RevealElement(1, target) { ProgressBlock(progress, colors) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun WordSheet(
    target: WordTarget, article: Article, content: Content, result: JSONObject?, progress: QueryProgress?,
    error: String?, colors: Palette, screenWidth: androidx.compose.ui.unit.Dp, screenHeight: androidx.compose.ui.unit.Dp,
    topReserve: androidx.compose.ui.unit.Dp, motion: Float, onTitleGeometry: (TokenGlyph) -> Unit,
    onRetry: () -> Unit, onConfigure: () -> Unit
) {
    val density = LocalDensity.current
    val panelWidth = screenWidth * .86f
    val x = (screenWidth - panelWidth) / 2
    val maxSheetHeight = screenHeight - topReserve - 18.dp
    var measuredHeightPx by remember(target) { mutableIntStateOf(0) }
    val measuredHeight = with(density) { measuredHeightPx.toDp() }
    val estimatedHeight = screenHeight * .57f
    val usedHeight = if (measuredHeightPx > 0) measuredHeight else estimatedHeight
    val minY = topReserve + 7.dp
    val maxY = (screenHeight - usedHeight - 10.dp).coerceAtLeast(minY)
    val desiredY = with(density) { target.anchor.rect.top.toDp() } - 29.dp
    val targetY = desiredY.coerceIn(minY, maxY)
    val y by animateDpAsState(targetY, tween(260, easing = FastOutSlowInEasing), label = "word sheet y")
    val lexeme = content.lexeme(target.token.text)
    var titleLayout by remember(target) { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.offset(x = x, y = y).width(panelWidth).heightIn(max = maxSheetHeight)
        .navigationBarsPadding()
        .onSizeChanged { if (measuredHeightPx != it.height) measuredHeightPx = it.height }
        .verticalScroll(rememberScrollState()).padding(horizontal = 5.dp, vertical = 10.dp)) {
        RevealElement(0, target) {
            Text("WORD / ${article.id.uppercase()}", color = colors.word, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (target.paragraphIndex < 0) smallCapsTitle(target.token.text, 42f)
            else AnnotatedString(target.token.text), modifier = Modifier.graphicsLayer {
            alpha = if (motion >= 1f) 1f else 0f
        }.onGloballyPositioned { coordinates ->
            val layout = titleLayout ?: return@onGloballyPositioned
            val position = coordinates.positionInRoot()
            val box = layout.getBoundingBox(0)
            val size = with(density) { 42.sp.toPx() }
            onTitleGeometry(TokenGlyph(target.token,
                Rect(position.x + box.left, position.y + box.top,
                    position.x + layout.getBoundingBox(target.token.text.length - 1).right, position.y + box.bottom),
                position.y + layout.getLineBaseline(0), size, colors.ink))
        }, onTextLayout = { titleLayout = it }, color = colors.ink, fontFamily = ReadingFont,
            fontSize = 42.sp, lineHeight = 47.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(5.dp))
        RevealElement(1, target) {
            Text(labelsFor(lexeme).joinToString("   ·   ").ifBlank { "语境词" }, color = colors.word,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
        }
        Spacer(Modifier.height(25.dp))
        if (result != null) {
            val contextSense = result.optJSONObject("context_sense")
            RevealElement(2, result) {
                Text("01  本句取义", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = .7.sp)
            }
            Spacer(Modifier.height(8.dp))
            RevealElement(3, result, Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(abbreviatePartOfSpeech(contextSense?.optString("part_of_speech").orEmpty().ifBlank { "语境" }),
                        color = colors.word, fontFamily = ReadingFont, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp).padding(top = 5.dp),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text(contextSense?.optString("zh").orEmpty(), color = colors.ink,
                        fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                }
            }
            val evidence = contextSense?.optString("evidence").orEmpty()
            if (evidence.isNotBlank()) RevealElement(4, result) {
                Text("“$evidence”", color = colors.muted, fontSize = 14.sp,
                    fontFamily = ReadingFont, modifier = Modifier.padding(start = 50.dp, top = 8.dp))
            }
            Spacer(Modifier.height(27.dp))
        }
        val common = result?.optJSONArray("common_senses")
        val dictionarySenses = lexeme?.let { splitDictionarySenses(it.translation) }.orEmpty()
        if (dictionarySenses.isNotEmpty() || (common != null && common.length() > 0)) {
            val senseKey = if (lexeme != null) target else result ?: target
            RevealElement(5, senseKey) {
                Text("02  ${if (lexeme != null) "预置词典" else "常见义项"}", color = colors.muted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            }
            Spacer(Modifier.height(10.dp))
            dictionarySenses.forEachIndexed { index, (part, meaning) ->
                RevealElement(6 + index, senseKey to index, Modifier.fillMaxWidth()) {
                    SenseLine(part, meaning, colors)
                }
            }
            if (lexeme == null && common != null) {
                for (i in 0 until common.length()) {
                    val item = common.optJSONObject(i) ?: continue
                    RevealElement(6 + i, senseKey to i, Modifier.fillMaxWidth()) {
                        SenseLine(item.optString("part_of_speech"), item.optString("zh"), colors)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        val derivatives = result?.optJSONArray("derivatives")
        if (derivatives != null && derivatives.length() > 0) {
            RevealElement(9, result) {
                Text("03  派生词", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = .7.sp)
            }
            Spacer(Modifier.height(9.dp))
            for (i in 0 until derivatives.length()) {
                val item = derivatives.optJSONObject(i) ?: continue
                RevealElement(10 + i, result to i, Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.optString("word"), color = colors.ink, fontFamily = ReadingFont,
                                fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(item.optString("relation"), color = colors.word, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        }
                        Text(item.optString("zh"), color = colors.muted, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    }
                }
            }
        }
        if (lexeme == null && result == null) RevealElement(5, target) {
            Text("不在预置词表中", color = colors.muted, fontSize = 13.sp)
        }
        if (result == null && error == null) RevealElement(6, target) { ProgressBlock(progress, colors) }
        if (error != null) {
            RevealElement(6, error) {
                Text(error, color = colors.paragraph, fontSize = 14.sp, lineHeight = 20.sp)
            }
            RevealElement(7, error) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Text("重试 →", Modifier.clickable(onClick = onRetry), color = colors.word, fontSize = 14.sp)
                    Text("设置接口 →", Modifier.clickable(onClick = onConfigure), color = colors.word, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun splitDictionarySenses(translation: String): List<Pair<String, String>> =
    translation.split(Regex("\\s*/\\s*")).filter { it.isNotBlank() }.map { sense ->
        val parts = Regex("^([a-z]{1,5}\\.|\\[[^]]+])\\s*(.*)", RegexOption.IGNORE_CASE).find(sense.trim())
        if (parts == null) "释义" to sense.trim() else parts.groupValues[1] to parts.groupValues[2]
    }

@Composable
private fun SenseLine(part: String, meaning: String, colors: Palette) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Text(abbreviatePartOfSpeech(part.ifBlank { "释义" }), color = colors.word, fontFamily = ReadingFont,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp).padding(top = 2.dp),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(meaning, color = colors.ink, fontFamily = FontFamily.Serif,
            fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}

private fun labelsFor(lexeme: Lexeme?): List<String> = buildList {
    if (lexeme == null) return@buildList
    if (lexeme.rank != null) add("主背 3000 #${lexeme.rank}")
    add("考纲 4801${lexeme.band?.let { " · $it" }.orEmpty()}")
    if (lexeme.gap) add("零语料 319")
}

@Composable
private fun ProgressBlock(progress: QueryProgress?, colors: Palette) {
    val phase = progress?.phase ?: QueryPhase.CONTEXT
    val phases = listOf("拼接上下文", "等待首字返回", "接收完整结果")
    val active = when (phase) {
        QueryPhase.CONTEXT -> 0; QueryPhase.FIRST -> 1; else -> 2
    }
    val fraction by animateFloatAsState((active + 1) / 3f, tween(540, easing = FastOutSlowInEasing), label = "query phases")
    val transition = rememberInfiniteTransition(label = "query pulse")
    val pulse by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("正在解析", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.muted.copy(alpha = .22f))) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(colors.word.copy(alpha = if (phase == QueryPhase.COMPLETE) 1f else pulse)))
        }
        Spacer(Modifier.height(13.dp))
        phases.forEachIndexed { index, title ->
            Text("${if (index < active) "✓" else if (index == active) "●" else "·"}  $title",
                color = if (index == active) colors.word else colors.muted,
                fontSize = 12.sp, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal)
        }
        if ((progress?.approximateReasoningTokens ?: 0) > 0) {
            val count = progress?.exactReasoningTokens?.toString() ?: "≈ ${progress?.approximateReasoningTokens}"
            Text("已推理 $count tokens", color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun UpdateDownloadSheet(
    version: String, progress: DownloadProgress, error: String?, ready: Boolean,
    colors: Palette, screenWidth: androidx.compose.ui.unit.Dp, topReserve: androidx.compose.ui.unit.Dp,
    onCancel: () -> Unit, onRetry: () -> Unit, onInstall: () -> Unit
) {
    val width = screenWidth * .86f
    val target = when (progress.phase) {
        DownloadPhase.CONNECTING -> .05f
        DownloadPhase.RECEIVING -> if (progress.total > 0) .10f + progress.fraction * .80f else .22f
        DownloadPhase.VERIFYING -> .94f
        DownloadPhase.READY -> 1f
    }
    val fraction by animateFloatAsState(target, tween(440, easing = FastOutSlowInEasing), label = "update download")
    val pulse by rememberInfiniteTransition(label = "download pulse").animateFloat(
        .48f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "download pulse alpha")
    val phaseIndex = when (progress.phase) {
        DownloadPhase.CONNECTING -> 0
        DownloadPhase.RECEIVING -> 1
        else -> 2
    }
    Column(Modifier.offset(x = (screenWidth - width) / 2, y = topReserve + 45.dp)
        .width(width).padding(horizontal = 4.dp)) {
        RevealElement(0, version) {
            Text("UPDATE / $version", color = colors.word, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        }
        Spacer(Modifier.height(22.dp))
        RevealElement(1, version) {
            Text(if (ready) "准备安装" else "下载更新", color = colors.ink,
                fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(11.dp))
        Text(if (ready) "安装包已校验" else if (progress.total > 0)
            "${(progress.fraction * 100).roundToInt()}%  ·  ${"%.1f".format(progress.bytes / 1048576.0)} / ${"%.1f".format(progress.total / 1048576.0)} MB"
            else if (progress.phase == DownloadPhase.CONNECTING) "正在连接 GitHub 发布源"
            else "已接收 ${"%.1f".format(progress.bytes / 1048576.0)} MB",
            color = colors.muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.muted.copy(alpha = .25f))) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp)
                .background(colors.word.copy(alpha = if (ready || error != null) 1f else pulse)))
        }
        Spacer(Modifier.height(21.dp))
        listOf("连接发布源", "接收安装包", "校验并准备安装").forEachIndexed { index, title ->
            Text("${if (index < phaseIndex || ready) "✓" else if (index == phaseIndex) "●" else "·"}  $title",
                color = if (index == phaseIndex || ready) colors.word else colors.muted,
                fontSize = 13.sp, lineHeight = 22.sp)
        }
        if (error != null) Text(error, color = colors.paragraph, fontSize = 13.sp,
            lineHeight = 20.sp, modifier = Modifier.padding(top = 16.dp))
        if (ready) {
            Spacer(Modifier.height(21.dp))
            Text("安装更新 →", Modifier.clickable(onClick = onInstall), color = colors.word,
                fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("若系统要求允许安装，请授权后返回再点安装。", color = colors.muted,
                fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        } else if (error != null) {
            Spacer(Modifier.height(21.dp))
            Text("重新下载 →", Modifier.clickable(onClick = onRetry), color = colors.word,
                fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(26.dp))
        Text(if (ready) "关闭" else "取消下载", Modifier.clickable(onClick = onCancel),
            color = colors.muted, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsSheet(
    colors: Palette, dark: Boolean, markQueriedWords: Boolean, silentInference: Boolean,
    current: Provider, settings: SecureSettings,
    updateStatus: String, updateInfo: UpdateInfo?, updateChecking: Boolean,
    focusStatus: String, modifier: Modifier,
    onDark: (Boolean) -> Unit, onMarkQueriedWords: (Boolean) -> Unit,
    onSilentInference: (Boolean) -> Unit, onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit, onSave: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(current.name) }
    var key by remember(selected) { mutableStateOf("") }
    var url by remember { mutableStateOf(settings.customBaseUrl) }
    var model by remember { mutableStateOf(settings.customModel) }
    var bodySize by remember { mutableStateOf(settings.bodySize) }
    var lineFactor by remember { mutableStateOf(settings.lineFactor) }
    var sideMargin by remember { mutableStateOf(settings.sideMargin) }
    var paragraphGap by remember { mutableStateOf(settings.paragraphGap) }
    var focusUrlInput by remember { mutableStateOf(settings.focusUrl) }
    var focusLinkEditing by remember { mutableStateOf(settings.focusUrl.isBlank()) }
    var focusCatalog by remember { mutableStateOf<FocusCatalog?>(null) }
    var focusSubjectId by remember { mutableIntStateOf(settings.focusSubjectId) }
    var focusItemId by remember { mutableIntStateOf(settings.focusItemId) }
    var focusCatalogStatus by remember { mutableStateOf("填写上报链接后读取目录") }
    var focusCatalogLoading by remember { mutableStateOf(false) }
    fun loadFocusCatalog() {
        if (focusCatalogLoading) return
        scope.launch {
            focusCatalogLoading = true
            focusCatalogStatus = "正在读取科目与事项…"
            try {
                val catalog = FocusReporter.catalog(focusUrlInput)
                focusCatalog = catalog
                val subject = catalog.subjects.firstOrNull { it.id == focusSubjectId }
                    ?: catalog.subjects.firstOrNull { it.name == "英语" && catalog.items.any { item -> item.subjectId == it.id } }
                    ?: catalog.subjects.firstOrNull { catalog.items.any { item -> item.subjectId == it.id } }
                focusSubjectId = subject?.id ?: 0
                val matching = catalog.items.filter { it.subjectId == focusSubjectId }
                focusItemId = matching.firstOrNull { it.id == focusItemId }?.id
                    ?: matching.firstOrNull { it.name == "二轮" }?.id ?: matching.firstOrNull()?.id ?: 0
                focusCatalogStatus = "目录已读取 · 请确认科目和事项"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                focusCatalog = null
                focusCatalogStatus = error.message ?: "读取目录失败"
            } finally {
                focusCatalogLoading = false
            }
        }
    }
    LaunchedEffect(Unit) {
        if (focusUrlInput.isNotBlank()) loadFocusCatalog()
    }
    Column(modifier) {
    Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState())
        .padding(start = 26.dp, end = 26.dp, top = 26.dp, bottom = 26.dp)) {
        Text("SETTINGS", color = colors.word, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(12.dp))
        Text("阅读与连接", color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().clickable { onDark(!dark) }, verticalAlignment = Alignment.CenterVertically) {
            Text("深色主题", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (dark) "ON" else "OFF", color = colors.word, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clickable { onMarkQueriedWords(!markQueriedWords) },
            verticalAlignment = Alignment.CenterVertically) {
            Text("正文标记已查询词", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (markQueriedWords) "ON" else "OFF", color = colors.word,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("默认关闭；只影响着色，不删除查询记录。", color = colors.muted,
            fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clickable { onSilentInference(!silentInference) },
            verticalAlignment = Alignment.CenterVertically) {
            Text("静默推理", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (silentInference) "ON" else "OFF", color = colors.word,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("默认关闭；开启后会在后台逐句调用模型并消耗额度。", color = colors.muted,
            fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("正文比例", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("参考图", Modifier.clickable {
                onDark(true); bodySize = 20f; lineFactor = 1.58f; sideMargin = 18f; paragraphGap = 30f
            }, color = colors.word, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        ScaleStepper("字号", "${bodySize.roundToInt()} sp", { bodySize = (bodySize - 1f).coerceAtLeast(16f) },
            { bodySize = (bodySize + 1f).coerceAtMost(28f) }, colors)
        ScaleStepper("行距", "${(lineFactor * 100).roundToInt()}%", { lineFactor = (lineFactor - .05f).coerceAtLeast(1.25f) },
            { lineFactor = (lineFactor + .05f).coerceAtMost(2f) }, colors)
        ScaleStepper("左右边距", "${sideMargin.roundToInt()} dp", { sideMargin = (sideMargin - 2f).coerceAtLeast(12f) },
            { sideMargin = (sideMargin + 2f).coerceAtMost(44f) }, colors)
        ScaleStepper("段落间距", "${paragraphGap.roundToInt()} dp", { paragraphGap = (paragraphGap - 4f).coerceAtLeast(12f) },
            { paragraphGap = (paragraphGap + 4f).coerceAtMost(72f) }, colors)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("应用更新", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (updateChecking) "正在检查…" else "检查更新 →",
                Modifier.clickable(enabled = !updateChecking, onClick = onCheckUpdate),
                color = colors.word, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(updateStatus, Modifier.fillMaxWidth().clickable(enabled = updateInfo != null,
            onClick = onDownloadUpdate).padding(top = 7.dp),
            color = if (updateInfo == null) colors.muted else colors.word,
            fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(30.dp))
        Text("碎片专注", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("阅读页处于前台时每 20 秒上报；退出阅读立即停止。", color = colors.muted,
            fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (focusUrlInput.isBlank()) "未配置上报链接" else "已配置 HTTPS 上报链接",
                color = colors.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(if (focusLinkEditing) "收起" else "修改 →",
                Modifier.clickable { focusLinkEditing = !focusLinkEditing },
                color = colors.word, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (focusLinkEditing) SettingInput("HTTPS 链接（可填 /catalog 或 /frame）", focusUrlInput, colors,
            secret = true, onChange = {
                focusUrlInput = it
                focusCatalog = null
                focusSubjectId = 0
                focusItemId = 0
                focusCatalogStatus = "链接变更后请重新读取目录"
            })
        Spacer(Modifier.height(10.dp))
        Text(if (focusCatalogLoading) "正在读取…" else "读取科目与事项 →",
            Modifier.clickable(enabled = !focusCatalogLoading && focusUrlInput.isNotBlank()) {
                loadFocusCatalog()
            }, color = colors.word, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(focusCatalogStatus, color = colors.muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 7.dp))
        val catalog = focusCatalog
        if (catalog != null) {
            Spacer(Modifier.height(16.dp))
            Text("科目", color = colors.muted, fontSize = 11.sp, letterSpacing = 1.sp)
            catalog.subjects.filter { subject -> catalog.items.any { it.subjectId == subject.id } }
                .forEach { subject ->
                    Text("${if (focusSubjectId == subject.id) "●" else "○"}  ${subject.name}  #${subject.id}",
                        Modifier.fillMaxWidth().clickable {
                            focusSubjectId = subject.id
                            val choices = catalog.items.filter { it.subjectId == subject.id }
                            focusItemId = choices.firstOrNull { it.name == "二轮" }?.id ?: choices.firstOrNull()?.id ?: 0
                        }.padding(vertical = 7.dp),
                        color = if (focusSubjectId == subject.id) colors.word else colors.ink,
                        fontSize = 14.sp)
                }
            Spacer(Modifier.height(11.dp))
            Text("事项", color = colors.muted, fontSize = 11.sp, letterSpacing = 1.sp)
            catalog.items.filter { it.subjectId == focusSubjectId }.forEach { item ->
                Text("${if (focusItemId == item.id) "●" else "○"}  ${item.name}  #${item.id}",
                    Modifier.fillMaxWidth().clickable { focusItemId = item.id }.padding(vertical = 7.dp),
                    color = if (focusItemId == item.id) colors.word else colors.ink, fontSize = 14.sp)
            }
        }
        Text("SOURCE / ${FocusReporter.SOURCE}  ·  $focusStatus", color = colors.muted,
            fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(28.dp))
        Text("模型服务", color = colors.muted, fontSize = 12.sp)
        listOf("DeepSeek 官方", "Command Code GOAT", "自定义").forEach { name ->
            Text(if (selected == name) "●  $name" else "○  $name", Modifier.fillMaxWidth().clickable { selected = name }
                .padding(vertical = 10.dp), color = if (selected == name) colors.word else colors.ink, fontSize = 16.sp,
                fontWeight = if (selected == name) FontWeight.Bold else FontWeight.Normal)
        }
        if (selected == "自定义") {
            Spacer(Modifier.height(12.dp))
            SettingInput("HTTPS base URL", url, colors, onChange = { url = it })
            Spacer(Modifier.height(10.dp))
            SettingInput("模型 ID", model, colors, onChange = { model = it })
        }
        Spacer(Modifier.height(17.dp))
        Text("API Key", color = colors.muted, fontSize = 12.sp)
        SettingInput("留空则保留已保存的密钥", key, colors, secret = true, onChange = { key = it })
        Spacer(Modifier.height(8.dp))
        Text("密钥仅保存在本机加密存储中；文章片段会发送给所选服务。", color = colors.muted, fontSize = 12.sp,
            lineHeight = 18.sp)
    }
    Row(Modifier.fillMaxWidth().height(104.dp)
        .background(colors.glass.copy(alpha = .84f)).clickable {
            val unchangedFocus = focusUrlInput == settings.focusUrl &&
                focusSubjectId == settings.focusSubjectId && focusItemId == settings.focusItemId
            if (focusUrlInput.isNotBlank() && !unchangedFocus &&
                focusCatalog?.item(focusSubjectId, focusItemId) == null) {
                focusCatalogStatus = "请先读取目录，并选择相互匹配的科目与事项"
                return@clickable
            }
            settings.providerName = selected
            settings.bodySize = bodySize
            settings.lineFactor = lineFactor
            settings.sideMargin = sideMargin
            settings.paragraphGap = paragraphGap
            if (selected == "自定义") { settings.customBaseUrl = url; settings.customModel = model }
            if (key.isNotBlank()) settings.saveKey(selected, key)
            settings.focusUrl = focusUrlInput
            settings.focusSubjectId = if (focusUrlInput.isBlank()) 0 else focusSubjectId
            settings.focusItemId = if (focusUrlInput.isBlank()) 0 else focusItemId
            key = ""
            onSave()
        }.padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("SAVE CHANGES", color = colors.word, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(5.dp))
            Text("保存并返回", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text("→", color = colors.word, fontSize = 23.sp)
    }
    }
}

@Composable
private fun ScaleStepper(label: String, value: String, decrease: () -> Unit, increase: () -> Unit, colors: Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.muted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text("−", Modifier.clickable(onClick = decrease).padding(horizontal = 12.dp, vertical = 4.dp),
            color = colors.word, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(value, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("+", Modifier.clickable(onClick = increase).padding(horizontal = 12.dp, vertical = 4.dp),
            color = colors.word, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingInput(label: String, value: String, colors: Palette, secret: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        if (value.isBlank()) Text(label, color = colors.muted, fontSize = 14.sp)
        BasicTextField(value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink, fontSize = 14.sp),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            singleLine = true)
    }
}
