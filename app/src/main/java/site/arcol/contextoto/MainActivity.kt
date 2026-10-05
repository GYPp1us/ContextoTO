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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        UpdateChecker.cleanupInterrupted(this)
        val model = ViewModelProvider(this)[ReaderViewModel::class.java]
        setContent { ReaderApp(model.content, model.store, model.settings, model.engine) }
    }
}

internal data class Palette(
    val paper: Color, val ink: Color, val muted: Color, val glass: Color, val sheet: Color,
    val word: Color, val paragraph: Color, val purple: Color, val gold: Color
)
internal val ReadingFont = FontFamily(
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

internal data class GlyphCharacter(val text: String, val rect: Rect, val baseline: Float, val sizePx: Float, val color: Color,
                                  val weight: FontWeight = FontWeight.Normal)
internal data class TokenGlyph(val token: Token, val rect: Rect, val baseline: Float, val sizePx: Float, val color: Color,
                              val characters: List<GlyphCharacter> = emptyList())
internal data class WordTarget(val paragraphIndex: Int, val token: Token, val anchor: TokenGlyph, val inSentence: Boolean)
private data class SentenceTarget(val paragraphIndex: Int, val sentence: Sentence, val sourceGlyphs: List<TokenGlyph>)
internal data class ReaderScale(val bodySize: Float, val lineFactor: Float, val sideMargin: Float, val paragraphGap: Float)
private data class ArticleSentence(val paragraphIndex: Int, val number: Int, val sentence: Sentence)
private data class CachedSentence(val item: ArticleSentence, val translation: String)
private data class SilentRunState(
    val active: ArticleSentence? = null, val progress: QueryProgress? = null,
    val error: String? = null, val running: Boolean = false
)

@Composable
private fun ReaderApp(content: Content, store: UserStore, settings: SecureSettings, engine: AnalysisEngine) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val learning = remember { LearningStore(store, content) }
    val deckScope = rememberCoroutineScope()
    val deck = remember { DeckMotion(deckScope) }
    var wordPage by remember { mutableStateOf(false) }
    var wordMenu by remember { mutableStateOf(false) }
    var studyBusy by remember { mutableStateOf(false) }
    var importKind by remember { mutableStateOf<Boolean?>(null) }
    var focusOpen by remember { mutableStateOf(false) }
    var guideStep by remember { mutableIntStateOf(if (settings.guideStep >= 0) settings.guideStep else if (store.getPlace() != null) 3 else 0) }
    var regenerate by remember { mutableStateOf<String?>(null) }
    var forceWord by remember { mutableStateOf<Set<WordModule>>(emptySet()) }
    var forceSentence by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sourceJump by remember { mutableStateOf<Appearance?>(null) }
    var reviewReturn by remember { mutableStateOf(false) }
    LaunchedEffect(wordPage) { if (wordPage) reviewReturn = false }
    val initial = remember { store.getPlace() }
    var articleIndex by remember { mutableIntStateOf(content.articles.indexOfFirst { it.id == initial?.articleId }.coerceAtLeast(0)) }
    val article = content.articles[articleIndex]
    var dark by remember { mutableStateOf(settings.dark) }
    var markQueriedWords by remember { mutableStateOf(settings.markQueriedWords) }
    var silentInference by remember { mutableStateOf(settings.silentInference) }
    var customStatusBar by remember { mutableStateOf(settings.customStatusBar) }
    var bookmarkVisible by remember { mutableStateOf(settings.bookmarkVisible) }
    var bookmarkPlace by remember(article.id) { mutableStateOf(store.bookmarkPlace(article.id).let { saved ->
        val paragraph = saved.paragraph.coerceIn(0, article.paragraphs.lastIndex)
        BookmarkPlace(paragraph, Content.sentenceAt(article.paragraphs[paragraph], saved.sentenceStart).start)
    }) }
    val paragraphLayouts = remember(article.id) { mutableStateMapOf<Int, TextLayoutResult>() }
    val queryTasks by engine.tasks.collectAsState()
    val engineRevision by engine.cacheRevision.collectAsState()
    var clockLabel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) { clockLabel = clock.format(Date()); delay(10_000) }
    }
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
    DisposableEffect(dark, customStatusBar) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = if (dark) android.graphics.Color.rgb(37, 37, 37) else android.graphics.Color.rgb(242, 240, 234)
        activity.window.navigationBarColor = if (dark) android.graphics.Color.rgb(37, 37, 37) else android.graphics.Color.rgb(242, 240, 234)
        activity.window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (customStatusBar) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { }
    }
    var provider by remember { mutableStateOf(settings.provider()) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsRetained by remember { mutableStateOf(false) }
    var directoryRetiring by remember { mutableStateOf(false) }
    var cutoutFactor by remember { mutableStateOf(settings.cutoutFactor) }
    var learningSettingsRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(wordPage, drawerOpen, wordMenu, settingsOpen) {
        deck.settle(when { settingsOpen -> -1f; drawerOpen -> -.6f; wordMenu -> 1.6f; wordPage -> 1f; else -> 0f })
    }
    LaunchedEffect(settingsOpen, deck.position.value) {
        if (settingsOpen || deck.position.value < -.6001f) settingsRetained = true
        else if (deck.position.value >= -.001f) settingsRetained = false
        if (deck.position.value <= -.999f) directoryRetiring = false
    }
    LaunchedEffect(settingsOpen) { if (settingsOpen) { wordPage = false; wordMenu = false; drawerOpen = false } }
    fun selectDeck(target: Float) {
        settingsOpen = target == -1f; drawerOpen = target == -.6f; wordMenu = target == 1.6f
        wordPage = target >= 1f
        deck.settle(target)
    }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateStatus by remember { mutableStateOf("点击检查 GitHub 最新版本") }
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
    val cachedSentences = remember(article.id, provider, cacheEpoch, engineRevision) {
        articleSentences.mapNotNull { item -> engine.cachedSentence(provider, item.sentence.text)?.let {
            CachedSentence(item, it.optString("translation_zh"))
        } }
    }
    val silentPercent = if (articleSentences.isEmpty()) 100 else cachedSentences.size * 100 / articleSentences.size
    val activeQuery = queryTasks.firstOrNull { it.status == QueryStatus.RUNNING && !it.silent }
    val percentLabel = "${activeQuery?.percent ?: silentPercent}%"
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
    LaunchedEffect(sentenceOpen) {
        if (sentenceOpen != null) {
            lastSentence = sentenceOpen
            sentenceMotion.snapTo(0f)
            snapshotFlow { sentenceDestination }.first { it.isNotEmpty() }
            sentenceMotion.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        } else {
            sentenceMotion.animateTo(0f, tween(330, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(wordTarget) {
        if (wordTarget != null) {
            lastWord = wordTarget
            wordMotion.snapTo(0f)
            snapshotFlow { wordDestination }.first { it != null }
            wordMotion.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        } else {
            wordMotion.animateTo(0f, tween(310, easing = FastOutSlowInEasing))
        }
    }
    val savedItem = if (article.id == initial?.articleId) initial.item else 0
    val savedOffset = if (article.id == initial?.articleId) initial.offset else 0
    val listState = remember(article.id) { androidx.compose.foundation.lazy.LazyListState(savedItem, savedOffset) }
    val readerScope = rememberCoroutineScope()
    fun advanceBookmark(index: Int, offset: Int = 0) {
        if (index in article.paragraphs.indices) {
            val place = BookmarkPlace(index, Content.sentenceAt(article.paragraphs[index], offset).start)
            if (place > bookmarkPlace) {
                bookmarkPlace = store.advanceSentenceBookmark(article.id, place)
                // Consecutive auto-scroll movements coalesce to one activity per 30-second window.
                learning.activity("bookmark", "${article.id}|${System.currentTimeMillis() / 30_000}")
            }
        }
    }
    LaunchedEffect(article.id, listState, bookmarkPlace) {
        snapshotFlow {
            val info = listState.layoutInfo
            val paragraphItem = info.visibleItemsInfo.firstOrNull { it.key == "paragraph-${bookmarkPlace.paragraph}" }
            val currentLayout = paragraphLayouts[bookmarkPlace.paragraph]
            val bottom = currentLayout?.let { sentenceBookmarkBands(it, bookmarkPlace.sentenceStart).lastOrNull()?.bottom }
            val passed = if (paragraphItem != null && bottom != null) paragraphItem.offset + bottom <= 0f
                else listState.firstVisibleItemIndex > bookmarkPlace.paragraph + 1
            if (!passed) null else articleSentences.firstOrNull { item ->
                val visible = info.visibleItemsInfo.firstOrNull { it.key == "paragraph-${item.paragraphIndex}" }
                val layout = paragraphLayouts[item.paragraphIndex]
                visible != null && layout != null &&
                    visible.offset + sentenceBookmarkBands(layout, item.sentence.start).last().bottom > 0f
            }?.let { BookmarkPlace(it.paragraphIndex, it.sentence.start) }
        }.distinctUntilChanged().collect { next ->
            if (next != null) advanceBookmark(next.paragraph, next.sentenceStart)
        }
    }

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
            regenerate != null -> regenerate = null
            importKind != null -> importKind = null
            guideStep < 4 -> { guideStep = 4; settings.guideStep = 4 }
            focusOpen -> focusOpen = false
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
            wordMenu -> wordMenu = false
            wordPage -> wordPage = false
            reviewReturn -> { wordPage = true; reviewReturn = false }
        }
    }
    BackHandler(updateOpen || settingsOpen || silentOpen || wordTarget != null || sentenceOpen != null || drawerOpen ||
        importKind != null || focusOpen || regenerate != null || guideStep < 4 || wordPage || reviewReturn) { closeTop() }

    LaunchedEffect(article.id, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(350).collect { (item, offset) ->
                store.savePlace(article.id, item, offset)
            }
    }
    LaunchedEffect(focusUrl, focusSubjectId, focusItemId, appResumed, drawerOpen, wordMenu, settingsOpen, updateOpen, importKind, guideStep) {
        if (focusUrl.isBlank() || focusSubjectId <= 0 || focusItemId <= 0) {
            focusStatus = "未配置专注上报"
            return@LaunchedEffect
        }
        if (!appResumed || drawerOpen || wordMenu || settingsOpen || updateOpen || importKind != null || guideStep < 4) {
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
                engine.sentence(provider, article, item.sentence, silent = true) { progress ->
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
        wordResult = cached
        if (forceWord.isEmpty() && engine.wordComplete(provider, text, target.token)) {
            learning.recordAppearance(article, target.paragraphIndex, target.token, cached); lookupEpoch++
        } else if (provider.key.isBlank()) {
            if (content.lexeme(target.token.text) != null || cached != null) {
                learning.recordAppearance(article, target.paragraphIndex, target.token, cached); lookupEpoch++
            }
            val missing = cached?.optJSONArray("_missing_modules")
            wordError = if (cached == null) "尚未配置 API Key，可先查看预置词典。" else
                "已有缓存已保留；待补${(0 until (missing?.length() ?: 0)).joinToString("、") { missing?.optString(it).orEmpty() }}。配置 API Key 后只查询缺项。"
        } else {
            try {
                wordResult = engine.word(provider, article, target.paragraphIndex, target.token, forceWord) { wordProgress = it }
                forceWord = emptySet()
                wordProgress = null
                store.recordLookup(article.id, "word", content.lemma(target.token.text)); lookupEpoch++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                wordResult = engine.cachedWord(provider, text, target.token)
                if (wordResult != null || content.lexeme(target.token.text) != null) {
                    learning.recordAppearance(article, target.paragraphIndex, target.token, wordResult); lookupEpoch++
                }
                wordError = readableQueryError(error); wordProgress = null
            }
        }
    }
    LaunchedEffect(sentenceOpen, retrySentence, provider) {
        val target = sentenceOpen ?: return@LaunchedEffect
        sentenceResult = null; sentenceProgress = null; sentenceError = null
        val cached = engine.cachedSentence(provider, target.sentence.text)
        if (cached != null && forceSentence.isEmpty()) {
            sentenceResult = cached
            store.recordLookup(article.id, "sentence", "${target.paragraphIndex}:${target.sentence.start}"); lookupEpoch++
            learning.activity("lookup", "sentence|${article.id}|${target.paragraphIndex}|${target.sentence.start}")
            cacheEpoch++
        } else if (provider.key.isBlank()) {
            sentenceError = "尚未配置 API Key。英文原句仍可阅读。"
        } else {
            try {
                    sentenceResult = engine.sentence(provider, article, target.sentence, forceModules = forceSentence) { sentenceProgress = it }
                    forceSentence = emptySet()
                    sentenceProgress = null
                    store.recordLookup(article.id, "sentence", "${target.paragraphIndex}:${target.sentence.start}"); lookupEpoch++
                    learning.activity("lookup", "sentence|${article.id}|${target.paragraphIndex}|${target.sentence.start}")
                    cacheEpoch++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { sentenceError = readableQueryError(error); sentenceProgress = null }
        }
    }

    val hasOverlay = drawerOpen || wordMenu || settingsOpen || silentOpen || sentenceOpen != null || wordTarget != null ||
        studyBusy || importKind != null || focusOpen || guideStep < 4 || regenerate != null || updateOpen
    val modalOverlay = hasOverlay && !drawerOpen && !wordMenu && !settingsOpen ||
        silentOpen || sentenceOpen != null || wordTarget != null || studyBusy || importKind != null || focusOpen || guideStep < 4 || regenerate != null || updateOpen
    val popupBlur by animateFloatAsState(if (modalOverlay) GLASS_BLUR else 0f, tween(290, easing = FastOutSlowInEasing), label = "body blur")
    val directoryProgress = if ((settingsOpen && !directoryRetiring) || (settingsRetained && !settingsOpen)) 0f
        else directoryFraction(deck.position.value)
    val menuProgress = wordMenuFraction(deck.position.value)
    val blur = maxOf(popupBlur, GLASS_BLUR * maxOf(directoryProgress, menuProgress))
    val updateBlur by animateFloatAsState(if (updateOpen) GLASS_BLUR else 0f,
        tween(290, easing = FastOutSlowInEasing), label = "settings blur")
    val regenerateBlur by animateFloatAsState(if (regenerate != null) GLASS_BLUR else 0f,
        tween(240, easing = FastOutSlowInEasing), label = "regeneration menu blur")
    val currentWordMotion = if (wordTarget != null && wordTarget != lastWord) 0f else wordMotion.value
    val currentSentenceMotion = if (sentenceOpen != null && sentenceOpen != lastSentence) 0f else sentenceMotion.value

    LaunchedEffect(article.id, sourceJump) {
        sourceJump?.takeIf { it.articleId == article.id }?.let { occurrence ->
            listState.scrollToItem((occurrence.paragraph + 1).coerceAtLeast(0))
            if (occurrence.paragraph >= 0) {
                val layout = snapshotFlow { paragraphLayouts[occurrence.paragraph] }.first { it != null }!!
                val line = layout.getLineForOffset(occurrence.start.coerceIn(0, layout.layoutInput.text.length - 1))
                listState.scrollToItem(occurrence.paragraph + 1, layout.getLineTop(line).roundToInt())
            }
            sourceJump = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.paper).clipToBounds().pointerInput(modalOverlay) {
        val tracker = VelocityTracker()
        var dragBounds: ClosedFloatingPointRange<Float> = -.6f..1f
        var returningFromSettings = false
        detectHorizontalDragGestures(onDragStart = { start ->
            returningFromSettings = settingsOpen || deck.position.value < -.61f
            dragBounds = when {
                returningFromSettings -> -1f..0f
                drawerOpen || deck.position.value < -.1f -> -.6f..0f
                wordMenu || deck.position.value > 1.1f -> 1f..1.6f
                wordPage || deck.position.value > .5f -> 0f..1.6f
                else -> -.6f..1f
            }
            tracker.resetTracking(); tracker.addPosition(android.os.SystemClock.uptimeMillis(), start)
            if (!modalOverlay) deckScope.launch(start = CoroutineStart.UNDISPATCHED) { deck.startDrag() }
        }, onHorizontalDrag = { change, delta ->
            if (!modalOverlay && !deck.swapping) {
                change.consume(); tracker.addPosition(change.uptimeMillis, change.position)
                deckScope.launch(start = CoroutineStart.UNDISPATCHED) { deck.drag(-delta / size.width, dragBounds) }
            }
        }, onDragCancel = { if (!modalOverlay) selectDeck(deckTarget(deck.position.value, 0f, dragBounds, returningFromSettings)) },
            onDragEnd = {
                if (!modalOverlay && !deck.swapping) selectDeck(deckTarget(deck.position.value, -tracker.calculateVelocity().x / size.width, dragBounds, returningFromSettings))
            })
    }) {
        val width = maxWidth
        val height = maxHeight
        val density = LocalDensity.current
        val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
        val topReserve = if (customStatusBar) 6.dp else safeTop + with(density) { (scale.bodySize * scale.lineFactor * .8f).sp.toDp() }
        val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(this).toDp() } * cutoutFactor
        val statusHeight = maxOf(cutoutTop, 42.dp * cutoutFactor)
        val cutouts = activity.window.decorView.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
        val widthPx = with(density) { width.toPx() }
        val leftEdge = if (customStatusBar) with(density) { (cutouts.filter { it.centerX() < widthPx / 3 }.maxOfOrNull { it.right } ?: 0).toDp() } else 0.dp
        val rightEdge = if (customStatusBar) with(density) { (cutouts.filter { it.centerX() > widthPx * 2 / 3 }.maxOfOrNull { widthPx - it.left } ?: 0f).toDp() } else 0.dp
        val pageShift = with(density) { width.toPx() } * deck.position.value
        val cardScale = 1f - .1f * deck.card.value
        val cardOffset = with(density) { width.toPx() } * .12f * deck.card.value * if (deck.incoming) -1 else 1
        val readingLayer = rememberGraphicsLayer()
        var readingHeader by remember { mutableStateOf(108.dp) }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = -pageShift + cardOffset
            scaleX = cardScale; scaleY = cardScale; alpha = 1f - deck.card.value
            renderEffect = if (blur > 0.1f) BlurEffect(blur, blur, TileMode.Clamp) else null
        }) {
            Column(Modifier.fillMaxWidth().zIndex(1f).onSizeChanged { readingHeader = with(density) { it.height.toDp() } }) {
                FrostedBar(readingLayer, colors, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(top = topReserve)) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (customStatusBar) statusHeight else 42.dp)
                    .padding(start = maxOf(scale.sideMargin.dp, leftEdge + 8.dp), end = maxOf(scale.sideMargin.dp, rightEdge + 8.dp),
                        top = if (customStatusBar) 2.dp else 22.dp, bottom = if (customStatusBar) 2.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (customStatusBar) {
                        FocusStatusLabel(focusStatus, colors) { focusOpen = true }
                        Spacer(Modifier.weight(1f))
                    } else {
                        Text("≡", Modifier.clickable { drawerOpen = true }.padding(end = 18.dp), color = colors.ink, fontSize = 27.sp)
                        Text("ContextoTO", color = colors.ink, fontFamily = ReadingFont, fontSize = 19.sp)
                        Spacer(Modifier.weight(1f))
                        Text(article.id.uppercase(), color = colors.muted, fontSize = 12.sp, fontFamily = ReadingFont)
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.width(58.dp).height(if (customStatusBar) statusHeight else 42.dp).clickable {
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
                Row(Modifier.fillMaxWidth().padding(horizontal = scale.sideMargin.dp).padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!customStatusBar) FocusStatusLabel(focusStatus, colors) { focusOpen = true }
                    if (customStatusBar) {
                        Text("≡", Modifier.clickable { drawerOpen = true }.padding(end = 12.dp), color = colors.ink, fontSize = 23.sp)
                        Text(smallCapsTitle(article.title, 16f), color = colors.ink, fontFamily = ReadingFont,
                            fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(article.id.uppercase().take(8), color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (!customStatusBar) Spacer(Modifier.weight(1f))
                    if (!customStatusBar) Text(if (activeQuery != null) "查询进度（估算）" else "句子缓存", color = colors.muted, fontSize = 10.sp)
                    Spacer(Modifier.width(16.dp))
                    val bookmarkNumber = articleSentences.firstOrNull { it.paragraphIndex == bookmarkPlace.paragraph &&
                        it.sentence.start == bookmarkPlace.sentenceStart }?.number ?: 1
                    Text("书签 · ${bookmarkNumber}句", Modifier.clickable {
                        readerScope.launch {
                            val layout = paragraphLayouts[bookmarkPlace.paragraph]
                            val top = layout?.getLineTop(layout.getLineForOffset(bookmarkPlace.sentenceStart))?.roundToInt() ?: 0
                            listState.animateScrollToItem(bookmarkPlace.paragraph + 1, top)
                        }
                    }, color = colors.word, fontSize = 10.sp)
                }
                }
                }
            }
                Box(Modifier.fillMaxSize().navigationBarsPadding().clipToBounds().drawWithContent {
                    readingLayer.record { this@drawWithContent.drawContent() }; drawLayer(readingLayer)
                }) {
                if (bookmarkVisible) SentenceBookmarkBackdrop(listState, bookmarkPlace, paragraphLayouts, scale.sideMargin, colors)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = scale.sideMargin.dp, end = scale.sideMargin.dp, top = readingHeader, bottom = 64.dp
                )) {
                    item(key = "title") {
                        UiHeading("READING / ${articleIndex + 1} OF ${content.articles.size}", colors, size = 11)
                        Spacer(Modifier.height(10.dp))
                        val sourceSentence = sentenceOpen ?: lastSentence.takeIf { sentenceMotion.value > .001f }
                        val sourceWord = wordTarget ?: lastWord.takeIf { wordMotion.value > .001f }
                        val hidden = when {
                            sourceSentence?.paragraphIndex == -1 && sentenceDestination.isNotEmpty() -> sourceSentence.sentence.start to sourceSentence.sentence.end
                            sourceWord?.paragraphIndex == -1 && !sourceWord.inSentence && wordDestination != null ->
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
                    itemsIndexed(article.paragraphs, key = { index, _ -> "paragraph-$index" }) { index, text ->
                        val sourceSentence = sentenceOpen ?: lastSentence.takeIf { sentenceMotion.value > .001f }
                        val sourceWord = wordTarget ?: lastWord.takeIf { wordMotion.value > .001f }
                        val hidden = when {
                            sourceSentence?.paragraphIndex == index && sentenceDestination.isNotEmpty() ->
                                sourceSentence.sentence.start to sourceSentence.sentence.end
                            sourceWord?.paragraphIndex == index && !sourceWord.inSentence && wordDestination != null ->
                                sourceWord.token.start to sourceWord.token.end
                            else -> null
                        }
                        InteractiveParagraph(text, colors, null, content,
                            if (markQueriedWords) store.queriedWords(article.id) else emptySet(), scale,
                            Modifier.fillMaxWidth().padding(bottom = scale.paragraphGap.dp),
                            onWord = { token, anchor ->
                                advanceBookmark(index, token.start)
                                wordDestination = null
                                wordTarget = WordTarget(index, token, anchor, false)
                            },
                            onLongWord = { token, glyphs ->
                                advanceBookmark(index, token.start)
                                val sentence = Content.sentenceAt(text, token.start)
                                sentenceDestination = emptyList()
                                sentenceOpen = SentenceTarget(index, sentence, glyphs.map { glyph ->
                                    glyph.copy(token = glyph.token.copy(
                                        start = glyph.token.start - sentence.start,
                                        end = glyph.token.end - sentence.start))
                                })
                            }, hidden = hidden, onLayout = { paragraphLayouts[index] = it })
                    }
                }
                }
        }
        StudyPage(content, learning, engine, provider, settings, learningSettingsRevision, colors, Modifier.fillMaxSize(),
            with(density) { width.toPx() } - pageShift + cardOffset, cardScale, 1f - deck.card.value, menuProgress,
            if (customStatusBar) topReserve else safeTop, wordPage, wordMenu, { wordMenu = it },
            settingsOpen || importKind != null || guideStep < 4 || focusOpen, focusStatus, { focusOpen = true },
            { wordMenu = false; settingsOpen = true }, { importKind = true }, { guideStep = 0; settings.guideStep = 0 }, { studyBusy = it }, statusHeight,
            onSwitchMode = { change -> deck.exchange(1f, change) { wordMenu = false } },
            onSource = { occurrence ->
                val index = content.articles.indexOfFirst { it.id == occurrence.articleId }
                if (index >= 0) { articleIndex = index; sourceJump = occurrence; wordPage = false; reviewReturn = true }
            })
        if (directoryProgress > .001f) {
            Box(Modifier.fillMaxSize().background(colors.paper.copy(alpha = GLASS_TINT * directoryProgress)).clickable { selectDeck(0f) })
            ArticleDrawer(content, store, article.id, lookupEpoch + engineRevision, colors,
                Modifier.width(width * .80f).fillMaxHeight().zIndex(if (settingsOpen) .5f else 0f).graphicsLayer {
                    translationX = with(density) { width.toPx() } * -.16f * (1f - directoryProgress)
                    alpha = directoryProgress
                }.navigationBarsPadding().padding(top = topReserve),
                onSelect = { index ->
                    if (index == articleIndex) selectDeck(0f) else deck.exchange(0f, {
                        articleIndex = index; wordTarget = null; sentenceOpen = null
                        store.savePlace(content.articles[index].id, 0, 0)
                    }) { drawerOpen = false }
                }, onSettings = { directoryRetiring = true; drawerOpen = false; settingsOpen = true },
                onImport = { drawerOpen = false; importKind = false }, onGuide = { drawerOpen = false; guideStep = 0; settings.guideStep = 0 })
        }

        val activeSentence = sentenceOpen ?: lastSentence
        if (sentenceOpen != null || sentenceMotion.value > .001f) GlassScrim(colors, sentenceMotion.value) { sentenceOpen = null; wordTarget = null }
        if (activeSentence != null) {
            val sentenceY = topReserve + 8.dp
            val secondBlur by animateFloatAsState(if (wordTarget?.inSentence == true || regenerate != null) GLASS_BLUR else 0f,
                tween(220), label = "sentence behind word blur")
            AnimatedVisibility(sentenceOpen != null,
                enter = fadeIn(tween(220)), exit = fadeOut(tween(340))) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = if (secondBlur > .1f) BlurEffect(secondBlur, secondBlur, TileMode.Clamp) else null
                }) {
                    SentenceSheet(article, activeSentence, sentenceResult, sentenceProgress, sentenceError,
                        content, store, colors, scale, markQueriedWords, lookupEpoch + engineRevision, currentSentenceMotion,
                        (wordTarget ?: lastWord.takeIf { wordMotion.value > .001f })?.takeIf { it.inSentence && wordDestination != null },
                        Modifier.align(Alignment.TopCenter).offset(y = sentenceY).fillMaxWidth()
                            .heightIn(max = height - sentenceY - 18.dp),
                        onGeometry = { sentenceDestination = it },
                        onWord = { token, anchor ->
                            advanceBookmark(activeSentence.paragraphIndex, activeSentence.sentence.start)
                            val absolute = Token(token.text, token.start + activeSentence.sentence.start,
                                token.end + activeSentence.sentence.start)
                            wordDestination = null
                            wordTarget = WordTarget(activeSentence.paragraphIndex, absolute,
                                anchor.copy(token = absolute), true)
                        }, onRetry = { retrySentence++ }, onAction = { regenerate = "sentence" })
                }
            }
        }
        if (wordTarget != null || wordMotion.value > .001f) GlassScrim(colors, wordMotion.value) { wordTarget = null }
        val displayWord = wordTarget ?: lastWord
        AnimatedVisibility(wordTarget != null,
            modifier = Modifier.graphicsLayer { renderEffect = if (regenerateBlur > .1f) BlurEffect(regenerateBlur, regenerateBlur, TileMode.Clamp) else null },
            enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
            if (displayWord != null) WordSheet(displayWord, article, content, wordResult, wordProgress, wordError, colors,
                maxWidth, maxHeight, topReserve, currentWordMotion, onTitleGeometry = { wordDestination = it },
                onRetry = { retryWord++ }, onConfigure = {
                    wordTarget = null; sentenceOpen = null; settingsOpen = true
                }, onAction = { regenerate = "word" })
        }

        val flightSentence = sentenceOpen ?: lastSentence
        if (flightSentence != null && sentenceDestination.isNotEmpty()) {
            MotionGlyphs(flightSentence.sourceGlyphs, sentenceDestination, currentSentenceMotion, sentenceOpen != null)
        }
        val flightWord = wordTarget ?: lastWord
        if (flightWord != null && wordDestination != null) {
            MotionGlyphs(listOf(flightWord.anchor), listOf(wordDestination!!), currentWordMotion, wordTarget != null)
        }

        if (silentOpen) GlassScrim(colors) {
            flightPercent = percentLabel
            flightAnchor = percentAnchor
            silentOpen = false
        }
        AnimatedVisibility(silentOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(310))) {
            SilentSheet(article, articleSentences, cachedSentences, silentRun, silentInference,
                provider.key.isNotBlank(), queryTasks,
                if (percentMotion.value < 1f) flightPercent else percentLabel,
                colors, maxWidth, maxHeight, topReserve, percentMotion.value,
                onPercentGeometry = { percentDestination = it }, onRetry = { retrySilent++ })
        }
        if (flightAnchor != null && percentDestination != null) {
            MotionGlyphs(listOf(flightAnchor!!), listOf(percentDestination!!), percentMotion.value, silentOpen)
        }
        if (regenerate != null) {
            GlassScrim(colors) { regenerate = null }
            val wordChoices = WordModule.entries.map { it.label } + "全部"
            val sentenceChoices = listOf("整句翻译", "从句划分", "词汇讲解", "全部")
            SimpleChoiceSheet("重新生成", colors, topReserve + 35.dp, if (regenerate == "word") wordChoices else sentenceChoices) { choice ->
                if (regenerate == "word") {
                    forceWord = if (choice == "全部") WordModule.entries.toSet() else WordModule.entries.filter { it.label == choice }.toSet()
                    retryWord++
                } else {
                    forceSentence = when (choice) { "整句翻译" -> setOf("translation_zh"); "从句划分" -> setOf("clauses")
                        "词汇讲解" -> setOf("glosses"); else -> setOf("translation_zh", "clauses", "glosses") }
                    retrySentence++
                }
                regenerate = null
            }
        }
        if (focusOpen) {
            GlassScrim(colors) { focusOpen = false }
            SimpleChoiceSheet("Focus / $clockLabel", colors, topReserve + 40.dp, listOf(focusStatus, "上报设置 →")) {
                if (it == "上报设置 →") { focusOpen = false; settingsOpen = true }
            }
        }
        importKind?.let { vocabulary -> ImportOverlay(vocabulary, content, learning, colors, topReserve) { importKind = null } }
        if (guideStep < 4) GuideOverlay(guideStep, content, colors, topReserve) { guideStep = it; settings.guideStep = it }

        if (settingsOpen || settingsRetained || deck.position.value < -.6001f) {
            SettingsSheet(colors, dark, markQueriedWords, silentInference, customStatusBar, bookmarkVisible, provider, settings,
                updateStatus, updateInfo, updateChecking, focusStatus,
                Modifier.align(Alignment.TopCenter).fillMaxSize().graphicsLayer {
                    translationX = with(density) { width.toPx() } * (-1f - deck.position.value)
                }
                    .navigationBarsPadding().padding(top = topReserve).graphicsLayer {
                        renderEffect = if (updateBlur > .1f) BlurEffect(updateBlur, updateBlur, TileMode.Clamp) else null
                    },
                onDark = { dark = it; settings.dark = it },
                onMarkQueriedWords = { markQueriedWords = it; settings.markQueriedWords = it },
                onSilentInference = { silentInference = it; settings.silentInference = it },
                onCustomStatusBar = { customStatusBar = it; settings.customStatusBar = it },
                onBookmarkVisible = { bookmarkVisible = it; settings.bookmarkVisible = it },
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
                    cutoutFactor = settings.cutoutFactor; learningSettingsRevision++
                    selectDeck(0f)
                }, onBack = { selectDeck(0f) })
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

@Composable
internal fun FocusStatusLabel(status: String, colors: Palette, onClick: () -> Unit) {
    val active = status.startsWith("正在记录")
    val label = when {
        active -> "专注 · 已上报"
        status.contains("冲突") -> "专注 · 冲突"
        status.contains("连接中断") || status.contains("失败") || status.contains("不可用") -> "专注 · 离线"
        status.contains("连接") -> "专注 · 连接中"
        status.contains("暂停") -> "专注 · 暂停"
        status.contains("未配置") -> "专注 · 未配置"
        else -> "专注 · 不可用"
    }
    Text("${if (active) "●" else "○"} $label", Modifier.clickable(onClick = onClick),
        color = if (active) colors.word else colors.muted, fontSize = 10.sp)
}


private fun readableQueryError(error: Throwable): String = when {
    error is java.net.SocketTimeoutException || error.message?.contains("timeout", ignoreCase = true) == true ->
        "模型等待超时，可能仍在排队或推理。请稍后重试。"
    error is org.json.JSONException -> "模型结果格式异常，请重试。"
    error.message?.startsWith("模型接口返回 HTTP") == true -> error.message.orEmpty()
    else -> error.message ?: "解析失败，请稍后重试。"
}

@Composable
internal fun GlassScrim(colors: Palette, level: Float = 1f, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(colors.paper.copy(alpha = GLASS_TINT * level.coerceIn(0f, 1f))).clickable(onClick = onDismiss))
}

internal fun measuredGlyph(token: Token, visible: AnnotatedString, layout: TextLayoutResult, position: Offset,
                          fontPx: Float, fontSp: Float, ink: Color): TokenGlyph {
    val characters = (token.start until token.end).map { index ->
        val box = layout.getBoundingBox(index)
        // Color and size are independent styles: a small-caps size span has no color.
        val color = resolveGlyphColor(visible, index, ink)
        val size = resolveGlyphSize(visible, index, fontSp)
        GlyphCharacter(visible.text.substring(index, index + 1),
            Rect(box.left + position.x, box.top + position.y, box.right + position.x, box.bottom + position.y),
            layout.getLineBaseline(layout.getLineForOffset(index)) + position.y,
            fontPx * size / fontSp, color, resolveGlyphWeight(visible, index))
    }
    val rect = Rect(characters.minOf { it.rect.left }, characters.minOf { it.rect.top },
        characters.maxOf { it.rect.right }, characters.maxOf { it.rect.bottom })
    return TokenGlyph(token, rect, characters.first().baseline, fontPx, characters.first().color, characters)
}

@Composable
internal fun InteractiveParagraph(
    text: String, colors: Palette, analysis: JSONObject?, content: Content, queried: Set<String>,
    scale: ReaderScale, modifier: Modifier = Modifier, onWord: (Token, TokenGlyph) -> Unit,
    onLongWord: (Token, List<TokenGlyph>) -> Unit,
    onGeometry: ((List<TokenGlyph>) -> Unit)? = null, hidden: Pair<Int, Int>? = null,
    titleMode: Boolean = false, titleFirstLarge: Boolean = true,
    onLayout: ((TextLayoutResult) -> Unit)? = null
) {
    var result by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var position by remember(text) { mutableStateOf(Offset.Zero) }
    val fontPx = with(LocalDensity.current) { scale.bodySize.sp.toPx() }
    val visible = remember(text, analysis, queried, colors, titleMode, titleFirstLarge, scale.bodySize) {
        annotateParagraph(text, analysis, content, queried, colors, null,
            if (titleMode) scale.bodySize else null, titleFirstLarge)
    }
    val annotated = remember(visible, hidden) { buildAnnotatedString {
        append(visible)
        if (hidden != null && hidden.first in 0 until hidden.second && hidden.second <= text.length)
            addStyle(SpanStyle(color = Color.Transparent), hidden.first, hidden.second)
    } }
    fun glyph(token: Token, layout: TextLayoutResult): TokenGlyph {
        return measuredGlyph(token, visible, layout, position, fontPx, scale.bodySize, colors.ink)
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
        }, onTextLayout = { result = it; onLayout?.invoke(it)
            onGeometry?.invoke(Content.tokens(text).map { token -> glyph(token, it) })
        }, color = colors.ink, fontFamily = ReadingFont,
        fontSize = scale.bodySize.sp, lineHeight = (scale.bodySize * scale.lineFactor).sp)
}

@Composable
internal fun MotionGlyphs(source: List<TokenGlyph>, destination: List<TokenGlyph>, progress: Float, active: Boolean) {
    if (progress >= 1f || (progress <= .001f && !active) || source.isEmpty() || destination.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val regular = remember { context.resources.getFont(R.font.tinos_regular) }
    val bold = remember { context.resources.getFont(R.font.tinos_bold) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = regular } }
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val targetByStart = destination.associateBy { it.token.start }
            source.forEachIndexed { index, from ->
                val to = targetByStart[from.token.start] ?: return@forEachIndexed
                val stagger = (index * .018f).coerceAtMost(.25f)
                val t = ((progress - stagger) / (1f - stagger)).coerceIn(0f, 1f)
                if (from.characters.isNotEmpty() && from.characters.size == to.characters.size) {
                    from.characters.zip(to.characters).forEach { (sourceChar, targetChar) ->
                        val color = lerp(sourceChar.color, targetChar.color, t)
                        paint.textSize = sourceChar.sizePx + (targetChar.sizePx - sourceChar.sizePx) * t
                        val x = sourceChar.rect.left + (targetChar.rect.left - sourceChar.rect.left) * t
                        val y = sourceChar.baseline + (targetChar.baseline - sourceChar.baseline) * t
                        fun draw(weight: FontWeight, alpha: Float) {
                            paint.typeface = if (weight >= FontWeight.SemiBold) bold else regular
                            paint.color = color.copy(alpha = color.alpha * alpha).toArgb()
                            canvas.nativeCanvas.drawText(sourceChar.text, x, y, paint)
                        }
                        if (sourceChar.weight == targetChar.weight) draw(sourceChar.weight, 1f) else {
                            draw(sourceChar.weight, 1f - t)
                            draw(targetChar.weight, t)
                        }
                    }
                    return@forEachIndexed
                }
                paint.color = lerp(from.color, to.color, t).toArgb()
                paint.typeface = regular
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
internal fun RevealElement(order: Int, revealKey: Any, modifier: Modifier = Modifier,
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
                              colors: Palette, hidden: Pair<Int, Int>?, titleSize: Float? = null,
                              titleFirstLarge: Boolean = true): AnnotatedString =
    buildAnnotatedString {
        if (titleSize != null) append(smallCapsTitle(text, titleSize, titleFirstLarge)) else append(text)
        for (token in Content.tokens(text)) {
            val lemma = content.lemma(token.text)
            if (lemma in queried) addStyle(SpanStyle(fontWeight = FontWeight.Bold), token.start, token.end)
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

private fun smallCapsTitle(title: String, fullSize: Float, firstLarge: Boolean = true): AnnotatedString = buildAnnotatedString {
    val firstLetter = title.indexOfFirst { it.isLetter() }
    title.forEachIndexed { index, char ->
        val start = length
        append(if (char.isLowerCase()) char.uppercaseChar() else char)
        if (char.isLowerCase() && (index != firstLetter || !firstLarge)) {
            addStyle(SpanStyle(fontSize = (fullSize * .78f).sp), start, length)
        }
    }
}

@Composable
private fun ArticleDrawer(
    content: Content, store: UserStore, activeId: String, epoch: Int, colors: Palette, modifier: Modifier,
    onSelect: (Int) -> Unit, onSettings: () -> Unit, onImport: () -> Unit, onGuide: () -> Unit
) {
    val lastId = remember(epoch) { store.getPlace()?.articleId ?: activeId }
    Column(modifier.padding(top = 30.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 25.dp), verticalAlignment = Alignment.CenterVertically) {
            UiHeading("ARTICLES", colors)
            Spacer(Modifier.weight(1f))
            Text("${content.articles.size}", color = colors.word, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(18.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(content.articles) { index, article ->
                val words = remember(epoch, article.id) { store.countLookups(article.id, "word") }
                val sentences = remember(epoch, article.id) { store.countLookups(article.id, "sentence") }
                Column(Modifier.fillMaxWidth().background(if (article.id == activeId) colors.ink.copy(alpha = .055f) else Color.Transparent)
                    .clickable { onSelect(index) }.padding(horizontal = 25.dp, vertical = 14.dp)) {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            JumpLink("导入文章", colors, onClick = onImport)
            JumpLink("使用引导", colors, onClick = onGuide)
        }
        JumpLink("接口与外观设置", colors, Modifier.padding(25.dp), bold = true, onClick = onSettings)
    }
}

@Composable
private fun SilentSheet(
    article: Article, sentences: List<ArticleSentence>, cached: List<CachedSentence>, run: SilentRunState,
    enabled: Boolean, hasKey: Boolean, tasks: List<QueryTask>, percent: String, colors: Palette, screenWidth: androidx.compose.ui.unit.Dp,
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
                UiHeading("TASKS / ${article.id.uppercase()}", colors, Modifier.padding(top = 15.dp), 11)
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
            UiHeading("TASKS & CACHE", colors, size = 27)
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
        if (enabled && run.error != null && hasKey) JumpLink("重试静默推理", colors, Modifier.padding(top = 12.dp), bold = true, onClick = onRetry)
        Spacer(Modifier.height(24.dp))
        RevealElement(4, article.id) {
            UiHeading("HISTORY & CACHE", colors, size = 15)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds().verticalScroll(rememberScrollState())) {
            val manual = tasks.filter { !it.silent }.take(6)
            if (manual.isNotEmpty()) {
                UiHeading("QUERIES", colors, size = 12)
                Text("关闭浮层不取消请求；阶段百分比为估算，写入缓存后才是 100%。", color = colors.muted,
                    fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp, bottom = 16.dp))
                manual.forEach { task ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("${task.articleId.uppercase()} · ${task.label}", color = colors.ink,
                                fontFamily = ReadingFont, fontSize = 17.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Text("${task.percent}%", color = colors.word, fontFamily = ReadingFont, fontSize = 17.sp)
                        }
                        val tokens = task.progress.exactReasoningTokens ?: task.progress.approximateReasoningTokens
                        val detail = when (task.status) {
                            QueryStatus.COMPLETE -> "已写入缓存"
                            QueryStatus.FAILED -> task.error ?: "查询失败"
                            QueryStatus.RUNNING -> when (task.progress.phase) {
                                QueryPhase.CONTEXT -> "检查模块 / 拼接上下文"
                                QueryPhase.FIRST -> "等待首字返回 · 已推理约 $tokens tokens"
                                QueryPhase.STREAM -> "接收完整结果 · 已推理约 $tokens tokens"
                                QueryPhase.COMPLETE -> "校验结果 / 合并缓存"
                            }
                        }
                        Text(detail, color = if (task.status == QueryStatus.FAILED) colors.paragraph else colors.muted,
                            fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                UiHeading("SENTENCES", colors, Modifier.padding(bottom = 13.dp), 12)
            }
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
    markQueriedWords: Boolean, queryRevision: Int, motion: Float,
    nestedWord: WordTarget?, modifier: Modifier,
    onGeometry: (List<TokenGlyph>) -> Unit, onWord: (Token, TokenGlyph) -> Unit, onRetry: () -> Unit, onAction: () -> Unit
) {
    val text = target.sentence.text
    val queried = remember(article.id, result, markQueriedWords, queryRevision) {
        if (markQueriedWords) store.queriedWords(article.id) else emptySet()
    }
    Column(modifier.navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp)) {
        RevealElement(0, target) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UiHeading("SENTENCE / ${if (article.kind == "导入") "LOCAL" else article.id.uppercase()}", colors, Modifier.weight(1f), 11)
                Text("↻", Modifier.clickable(onClick = onAction).padding(10.dp), color = colors.word, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(13.dp))
        InteractiveParagraph(text, colors, result, content, queried,
            scale.copy(bodySize = (scale.bodySize * 1.12f).coerceAtLeast(18f), lineFactor = 1.48f),
            Modifier.graphicsLayer { alpha = if (motion >= 1f) 1f else 0f },
            onWord = onWord, onLongWord = { _, _ -> }, onGeometry = onGeometry,
            hidden = nestedWord?.let { (it.token.start - target.sentence.start) to
                (it.token.end - target.sentence.start) },
            titleMode = target.paragraphIndex < 0, titleFirstLarge = target.sentence.start == 0)
        Spacer(Modifier.height(25.dp))
        if (result != null) {
            RevealElement(1, result) {
                UiHeading("TRANSLATION", colors, size = 12)
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
                    UiHeading("CLAUSES", colors, size = 12)
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
                                Text(clause.optString("kind"), color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(clause.optString("quote"), color = colors.muted, fontFamily = FontFamily.Serif, fontSize = 14.sp,
                                    lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(clause.optString("brief_zh"), color = colors.muted, fontFamily = FontFamily.Serif, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            val glosses = result.optJSONArray("glosses")
            if (glosses != null && glosses.length() > 0) {
                Spacer(Modifier.height(15.dp))
                RevealElement(6, result) {
                    UiHeading("VOCABULARY", colors, size = 12)
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
                JumpLink("重试解析", colors, Modifier.padding(top = 12.dp), onClick = onRetry)
            }
        } else {
            RevealElement(1, target) { ProgressBlock(progress, colors) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
internal fun WordSheet(
    target: WordTarget, article: Article, content: Content, result: JSONObject?, progress: QueryProgress?,
    error: String?, colors: Palette, screenWidth: androidx.compose.ui.unit.Dp, screenHeight: androidx.compose.ui.unit.Dp,
    topReserve: androidx.compose.ui.unit.Dp, motion: Float, onTitleGeometry: (TokenGlyph) -> Unit,
    onRetry: () -> Unit, onConfigure: () -> Unit, onAction: (() -> Unit)? = null,
    heading: String = "WORD / ${if (article.kind == "导入") "LOCAL" else article.id.uppercase()}", allowQueries: Boolean = true,
    onBlankTap: (() -> Unit)? = null, onHold: (() -> Unit)? = null,
    beforeSenses: @Composable () -> Unit = {}, afterContent: @Composable () -> Unit = {}
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
    val title = if (target.paragraphIndex < 0) smallCapsTitle(target.token.text, 42f,
        target.token.start == article.title.indexOfFirst { it.isLetter() }) else AnnotatedString(target.token.text)
    RetainedColumn(Modifier.offset(x = x, y = y).width(panelWidth).heightIn(max = maxSheetHeight)
        .onSizeChanged { if (measuredHeightPx != it.height) measuredHeightPx = it.height }
        .navigationBarsPadding()
        .then(if (onBlankTap != null || onHold != null) Modifier.pointerInput(target, onBlankTap, onHold) {
            detectTapGestures(onTap = { onBlankTap?.invoke() }, onLongPress = { onHold?.invoke() })
        } else Modifier).padding(horizontal = 5.dp, vertical = 10.dp)) {
        RevealElement(0, target) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UiHeading(heading, colors, Modifier.weight(1f), 11)
                if (onAction != null) Text(if (heading.startsWith("REVIEW")) "⚑" else "↻",
                    Modifier.clickable(onClick = onAction).padding(10.dp), color = colors.word, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, modifier = Modifier.graphicsLayer {
            alpha = if (motion >= 1f) 1f else 0f
        }.onGloballyPositioned { coordinates ->
            val layout = titleLayout ?: return@onGloballyPositioned
            val position = coordinates.positionInRoot()
            val size = with(density) { 42.sp.toPx() }
            onTitleGeometry(measuredGlyph(Token(target.token.text, 0, target.token.text.length), title, layout,
                position, size, 42f, colors.ink).copy(token = target.token))
        }, onTextLayout = { titleLayout = it }, color = colors.ink, fontFamily = ReadingFont,
            fontSize = 42.sp, lineHeight = 47.sp, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(5.dp))
        RevealElement(1, target) {
            Text(content.banks.filter { content.lemma(target.token.text) in it.words }.joinToString(" · ") { it.name }.ifBlank { "语境词" }, color = colors.word,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
        }
        val phonetics = result?.optJSONObject("phonetics")
        if (phonetics != null) RevealElement(2, phonetics.toString()) {
            Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                listOf("uk", "us").forEach { accent ->
                    if (phonetics.optString(accent).isNotBlank()) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Text(accent.uppercase(), color = colors.word, fontSize = 10.sp,
                            modifier = Modifier.width(30.dp).padding(top = 3.dp))
                        Text(phonetics.optString(accent), color = colors.muted, fontFamily = ReadingFont,
                            fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(25.dp))
        beforeSenses()
        if (result != null && result.optJSONObject("context_sense") != null) {
            val contextSense = result.optJSONObject("context_sense")
            RevealElement(2, result) {
                UiHeading("01  IN CONTEXT", colors, size = 11)
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
                UiHeading("02  MEANINGS", colors, size = 11)
            }
            Spacer(Modifier.height(10.dp))
            dictionarySenses.forEachIndexed { index, (part, meaning) ->
                RevealElement(6 + index, senseKey to index, Modifier.fillMaxWidth()) {
                    SenseLine(part, meaning, colors)
                }
            }
            if (common != null) {
                for (i in 0 until common.length()) {
                    val item = common.optJSONObject(i) ?: continue
                    RevealElement(6 + i, senseKey to i, Modifier.fillMaxWidth()) {
                        SenseLine(item.optString("part_of_speech"), item.optString("zh"), colors)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        content.banks.filter { content.lemma(target.token.text) in it.words && it.words[content.lemma(target.token.text)]?.translation != lexeme?.translation }.forEach { bank ->
            val entry = bank.words.getValue(content.lemma(target.token.text))
            RevealElement(8, bank.id to target) {
                Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                    Text("词库提供 · ${bank.name}", color = colors.word, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                    splitDictionarySenses(entry.translation).forEach { (part, meaning) -> SenseLine(part, meaning, colors) }
                    if (entry.ipaUk.isNotBlank() || entry.ipaUs.isNotBlank()) Text("UK ${entry.ipaUk}  US ${entry.ipaUs}", color = colors.muted,
                        fontFamily = ReadingFont, fontSize = 13.sp)
                }
            }
        }
        val derivatives = result?.optJSONArray("derivatives")
        if (derivatives != null && derivatives.length() > 0) {
            RevealElement(9, result) {
                UiHeading("03  DERIVATIVES", colors, size = 11)
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
                        Text(item.optString("zh"), color = colors.muted, fontFamily = FontFamily.Serif, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                    }
                }
            }
        }
        if (lexeme == null && result == null) RevealElement(5, target) {
            Text("不在预置词表中", color = colors.muted, fontSize = 13.sp)
        }
        val missing = result?.optJSONArray("_missing_modules")
        if (allowQueries && (result == null || (missing?.length() ?: 0) > 0) && error == null) RevealElement(6, target) {
            ProgressBlock(progress, colors, if (result == null) "正在解析" else
                "补全 · ${(0 until (missing?.length() ?: 0)).joinToString(" / ") { missing?.optString(it).orEmpty() }}")
        }
        if (error != null) {
            RevealElement(6, error) {
                Text(error, color = colors.paragraph, fontSize = 14.sp, lineHeight = 20.sp)
            }
            RevealElement(7, error) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    JumpLink("重试", colors, onClick = onRetry)
                    JumpLink("设置接口", colors, onClick = onConfigure)
                }
            }
        }
        afterContent()
        Spacer(Modifier.height(32.dp))
    }
}

private fun splitDictionarySenses(translation: String): List<Pair<String, String>> =
    translation.split(Regex("\\s*/\\s*")).filter { it.isNotBlank() }.map { sense ->
        val parts = Regex("^([a-z]{1,5}\\.|\\[[^]]+])\\s*(.*)", RegexOption.IGNORE_CASE).find(sense.trim())
        if (parts == null) "释义" to sense.trim() else parts.groupValues[1] to parts.groupValues[2]
    }

@Composable
internal fun SenseLine(part: String, meaning: String, colors: Palette) {
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
internal fun ProgressBlock(progress: QueryProgress?, colors: Palette, label: String = "正在解析") {
    val phase = progress?.phase ?: QueryPhase.CONTEXT
    val phases = listOf("拼接上下文", "等待首字返回", "接收完整结果")
    val active = when (phase) {
        QueryPhase.CONTEXT -> 0; QueryPhase.FIRST -> 1; else -> 2
    }
    val fraction by animateFloatAsState((active + 1) / 3f, tween(540, easing = FastOutSlowInEasing), label = "query phases")
    val transition = rememberInfiniteTransition(label = "query pulse")
    val pulse by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        UiHeading("ANALYSIS", colors, size = 15)
        Text(label, color = colors.muted, fontFamily = FontFamily.SansSerif, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
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
            UiHeading("UPDATE / $version", colors, size = 11)
        }
        Spacer(Modifier.height(22.dp))
        RevealElement(1, version) {
            UiHeading(if (ready) "READY TO INSTALL" else "DOWNLOAD UPDATE", colors, size = 28)
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
            JumpLink("安装更新", colors, size = 17, bold = true, onClick = onInstall)
            Text("若系统要求允许安装，请授权后返回再点安装。", color = colors.muted,
                fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        } else if (error != null) {
            Spacer(Modifier.height(21.dp))
            JumpLink("重新下载", colors, size = 16, bold = true, onClick = onRetry)
        }
        Spacer(Modifier.height(26.dp))
        Text(if (ready) "关闭" else "取消下载", Modifier.clickable(onClick = onCancel),
            color = colors.muted, fontSize = 13.sp)
    }
}


@Composable
internal fun ScaleStepper(label: String, value: String, decrease: () -> Unit, increase: () -> Unit, colors: Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        UiHeading(label, colors, size = 12)
        Spacer(Modifier.weight(1f))
        Text("−", Modifier.clickable(onClick = decrease).padding(horizontal = 12.dp, vertical = 4.dp),
            color = colors.word, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(value, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("+", Modifier.clickable(onClick = increase).padding(horizontal = 12.dp, vertical = 4.dp),
            color = colors.word, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SettingInput(label: String, value: String, colors: Palette, secret: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        if (value.isBlank()) Text(label, color = colors.muted, fontSize = 14.sp)
        BasicTextField(value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink, fontSize = 14.sp, fontFamily = FontFamily.SansSerif),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            singleLine = true)
    }
}
