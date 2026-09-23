package site.arcol.contextoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

private data class WordTarget(val paragraphIndex: Int, val token: Token, val anchor: Rect, val inSentence: Boolean)
private data class SentenceTarget(val paragraphIndex: Int, val sentence: Sentence)
private data class ReaderScale(val bodySize: Float, val lineFactor: Float, val sideMargin: Float, val paragraphGap: Float)

@Composable
private fun ReaderApp(content: Content, store: UserStore, settings: SecureSettings, engine: AnalysisEngine) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val initial = remember { store.getPlace() }
    var articleIndex by remember { mutableIntStateOf(content.articles.indexOfFirst { it.id == initial?.articleId }.coerceAtLeast(0)) }
    val article = content.articles[articleIndex]
    var dark by remember { mutableStateOf(settings.dark) }
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
    var lookupEpoch by remember { mutableIntStateOf(0) }
    var lastSentence by remember { mutableStateOf<SentenceTarget?>(null) }
    var lastWord by remember { mutableStateOf<WordTarget?>(null) }
    LaunchedEffect(sentenceOpen) { if (sentenceOpen != null) lastSentence = sentenceOpen }
    LaunchedEffect(wordTarget) { if (wordTarget != null) lastWord = wordTarget }
    val savedItem = if (article.id == initial?.articleId) initial.item else 0
    val savedOffset = if (article.id == initial?.articleId) initial.offset else 0
    val listState = remember(article.id) { androidx.compose.foundation.lazy.LazyListState(savedItem, savedOffset) }

    fun closeTop() {
        when {
            settingsOpen -> settingsOpen = false
            wordTarget != null -> wordTarget = null
            sentenceOpen != null -> sentenceOpen = null
            drawerOpen -> drawerOpen = false
        }
    }
    BackHandler(settingsOpen || wordTarget != null || sentenceOpen != null || drawerOpen) { closeTop() }

    LaunchedEffect(article.id, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(350).collect { (item, offset) ->
                store.savePlace(article.id, item, offset)
            }
    }
    LaunchedEffect(article.id, provider.name, provider.key, provider.model) {
        if (provider.key.isNotBlank()) {
            val visibleIndex = (listState.firstVisibleItemIndex - 1).coerceIn(article.paragraphs.indices)
            val next = article.paragraphs.indices.asSequence().filter { it >= visibleIndex }
                .map { index -> Content.sentenceAt(article.paragraphs[index], 0) }
                .firstOrNull { engine.cachedSentence(provider, it.text) == null }
            if (next != null) runCatching { engine.sentence(provider, article, next) {} }
        }
    }
    LaunchedEffect(wordTarget, retryWord, provider) {
        val target = wordTarget ?: return@LaunchedEffect
        wordResult = null; wordProgress = null; wordError = null
        val text = article.paragraphs.getOrNull(target.paragraphIndex) ?: return@LaunchedEffect
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
        } else if (provider.key.isBlank()) {
            sentenceError = "尚未配置 API Key。英文原句仍可阅读。"
        } else {
            runCatching { engine.sentence(provider, article, target.sentence) { sentenceProgress = it } }
                .onSuccess {
                    sentenceResult = it; sentenceProgress = null
                    store.recordLookup(article.id, "sentence", "${target.paragraphIndex}:${target.sentence.start}"); lookupEpoch++
                }.onFailure { sentenceError = readableQueryError(it); sentenceProgress = null }
        }
    }

    val hasOverlay = drawerOpen || settingsOpen || sentenceOpen != null || wordTarget != null
    val blur by animateFloatAsState(if (hasOverlay) 26f else 0f, tween(290, easing = FastOutSlowInEasing), label = "body blur")

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.paper).navigationBarsPadding()) {
        val width = maxWidth
        val height = maxHeight
        val density = LocalDensity.current
        val drawerShift by animateFloatAsState(if (drawerOpen) with(density) { (width * .60f).toPx() } else 0f,
            tween(340, easing = FastOutSlowInEasing), label = "drawer shift")
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = drawerShift
            renderEffect = if (blur > 0.1f) BlurEffect(blur, blur, TileMode.Clamp) else null
        }) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = scale.sideMargin.dp, end = scale.sideMargin.dp, top = 22.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("≡", Modifier.clickable { drawerOpen = true }.padding(end = 18.dp), color = colors.ink, fontSize = 27.sp)
                    Text("ContextoTO", color = colors.ink, fontFamily = ReadingFont, fontSize = 19.sp)
                    Spacer(Modifier.weight(1f))
                    Text(article.id.uppercase(), color = colors.muted, fontSize = 12.sp, fontFamily = ReadingFont)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = scale.sideMargin.dp, end = scale.sideMargin.dp, bottom = 64.dp
                )) {
                    item {
                        Text("READING / ${articleIndex + 1} OF 48", color = colors.word, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(article.title, color = colors.ink, fontFamily = ReadingFont,
                            fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Normal,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(28.dp))
                    }
                    itemsIndexed(article.paragraphs) { index, text ->
                        InteractiveParagraph(text, colors, null, content, store.queriedWords(article.id), scale,
                            Modifier.fillMaxWidth().padding(bottom = scale.paragraphGap.dp),
                            onWord = { token, anchor -> wordTarget = WordTarget(index, token, anchor, false) },
                            onLongWord = { token -> sentenceOpen = SentenceTarget(index, Content.sentenceAt(text, token.start)) })
                    }
                }
            }
        }

        if (drawerOpen) GlassScrim(colors) { drawerOpen = false }
        AnimatedVisibility(drawerOpen, Modifier.align(Alignment.CenterStart),
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 5 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(290, easing = FastOutSlowInEasing)) { -it / 5 }) {
            ArticleDrawer(content, store, article.id, lookupEpoch, colors,
                Modifier.width(width * .80f).fillMaxHeight(),
                onSelect = { index ->
                    articleIndex = index; wordTarget = null; sentenceOpen = null; drawerOpen = false
                    store.savePlace(content.articles[index].id, 0, 0)
                }, onSettings = { drawerOpen = false; settingsOpen = true })
        }

        val activeSentence = sentenceOpen ?: lastSentence
        if (sentenceOpen != null) GlassScrim(colors) { sentenceOpen = null; wordTarget = null }
        if (activeSentence != null) {
            val secondBlur by animateFloatAsState(if (wordTarget?.inSentence == true) 23f else 0f,
                tween(220), label = "sentence behind word blur")
            AnimatedVisibility(sentenceOpen != null,
                enter = fadeIn(tween(260)) + slideInVertically(tween(330, easing = FastOutSlowInEasing)) { it / 8 },
                exit = fadeOut(tween(220)) + slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { -it / 10 }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = if (secondBlur > .1f) BlurEffect(secondBlur, secondBlur, TileMode.Clamp) else null
                }) {
                    SentenceSheet(article, activeSentence, sentenceResult, sentenceProgress, sentenceError,
                        content, store, colors, scale, Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = height * .82f),
                        onWord = { token, anchor ->
                            val absolute = Token(token.text, token.start + activeSentence.sentence.start,
                                token.end + activeSentence.sentence.start)
                            wordTarget = WordTarget(activeSentence.paragraphIndex, absolute, anchor, true)
                        }, onRetry = { retrySentence++ })
                }
            }
        }
        if (wordTarget?.inSentence == true) GlassScrim(colors) { wordTarget = null }

        if (wordTarget != null && wordTarget?.inSentence == false) GlassScrim(colors) { wordTarget = null }
        val displayWord = wordTarget ?: lastWord
        AnimatedVisibility(wordTarget != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(250, easing = FastOutSlowInEasing)) { it / 10 },
            exit = fadeOut(tween(190)) + slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 12 }) {
            if (displayWord != null) WordSheet(displayWord, article, content, wordResult, wordProgress, wordError, colors,
                maxWidth, maxHeight, onRetry = { retryWord++ }, onConfigure = {
                    wordTarget = null; sentenceOpen = null; settingsOpen = true
                })
        }

        if (settingsOpen) {
            GlassScrim(colors) { settingsOpen = false }
            SettingsSheet(colors, dark, provider, settings, Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(.86f),
                onDark = { dark = it; settings.dark = it }, onSave = {
                    provider = settings.provider()
                    scale = ReaderScale(settings.bodySize, settings.lineFactor, settings.sideMargin, settings.paragraphGap)
                    settingsOpen = false
                })
        }

        if (!hasOverlay) {
            Box(Modifier.fillMaxHeight().width(22.dp).align(Alignment.CenterStart)
                .pointerInput(article.id) {
                    var distance = 0f
                    detectHorizontalDragGestures(onHorizontalDrag = { _, delta -> distance += delta },
                        onDragEnd = { if (distance > 55f) drawerOpen = true; distance = 0f })
                })
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
    scale: ReaderScale, modifier: Modifier = Modifier, onWord: (Token, Rect) -> Unit, onLongWord: (Token) -> Unit
) {
    var result by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var position by remember(text) { mutableStateOf(Offset.Zero) }
    val annotated = remember(text, analysis, queried, colors) { annotateParagraph(text, analysis, content, queried, colors) }
    Text(annotated, modifier.onGloballyPositioned { position = it.positionInRoot() }
        .pointerInput(text, result) {
            detectTapGestures(
                onTap = { point ->
                    val layout = result ?: return@detectTapGestures
                    val token = Content.tokenAt(text, layout.getOffsetForPosition(point)) ?: return@detectTapGestures
                    val box = layout.getBoundingBox(token.start)
                    if (point.y >= box.top - 5 && point.y <= box.bottom + 5) {
                        onWord(token, Rect(box.left + position.x, box.top + position.y,
                            layout.getBoundingBox(token.end - 1).right + position.x, box.bottom + position.y))
                    }
                },
                onLongPress = { point ->
                    val layout = result ?: return@detectTapGestures
                    Content.tokenAt(text, layout.getOffsetForPosition(point))?.let(onLongWord)
                }
            )
        }, onTextLayout = { result = it }, color = colors.ink, fontFamily = ReadingFont,
        fontSize = scale.bodySize.sp, lineHeight = (scale.bodySize * scale.lineFactor).sp)
}

private fun annotateParagraph(text: String, analysis: JSONObject?, content: Content, queried: Set<String>, colors: Palette): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val clauses = analysis?.optJSONArray("clauses")
        val clauseColors = listOf(colors.word, colors.purple, colors.gold, colors.paragraph)
        if (clauses != null) for (i in 0 until clauses.length()) {
            val item = clauses.optJSONObject(i) ?: continue
            val start = item.optInt("start", -1); val end = item.optInt("end", -1)
            if (start in 0 until end && end <= text.length) addStyle(SpanStyle(color = clauseColors[i % clauseColors.size]), start, end)
        }
        for (token in Content.tokens(text)) {
            val lemma = content.lemma(token.text)
            if (lemma in queried) addStyle(SpanStyle(color = colors.paragraph, fontWeight = FontWeight.Bold), token.start, token.end)
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
                    Text(article.title, color = colors.ink, fontFamily = ReadingFont, fontSize = 17.sp,
                        lineHeight = 21.sp, fontWeight = if (article.id == lastId) FontWeight.Bold else FontWeight.Normal,
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
private fun SentenceSheet(
    article: Article, target: SentenceTarget, result: JSONObject?, progress: QueryProgress?, error: String?,
    content: Content, store: UserStore, colors: Palette, scale: ReaderScale, modifier: Modifier,
    onWord: (Token, Rect) -> Unit, onRetry: () -> Unit
) {
    val text = target.sentence.text
    val queried = remember(article.id, result) { store.queriedWords(article.id) }
    Column(modifier.background(colors.sheet).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("SENTENCE / ${article.id.uppercase()}", color = colors.paragraph, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(13.dp))
        InteractiveParagraph(text, colors, result, content, queried,
            scale.copy(bodySize = (scale.bodySize * 1.12f).coerceAtLeast(18f), lineFactor = 1.48f),
            onWord = onWord, onLongWord = {})
        Spacer(Modifier.height(25.dp))
          if (result != null) {
            Text("整句释义", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(result.optString("translation_zh"), color = colors.ink, fontSize = 19.sp, lineHeight = 30.sp,
                fontFamily = FontFamily.Serif)
            val clauses = result.optJSONArray("clauses")
            if (clauses != null && clauses.length() > 0) {
                Spacer(Modifier.height(25.dp))
                Text("从句范围", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                val clauseColors = listOf(colors.word, colors.purple, colors.gold, colors.paragraph)
                for (i in 0 until clauses.length()) {
                    val clause = clauses.optJSONObject(i) ?: continue
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
            val glosses = result.optJSONArray("glosses")
            if (glosses != null && glosses.length() > 0) {
                Spacer(Modifier.height(15.dp))
                Text("本句词语", color = colors.paragraph, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                for (i in 0 until glosses.length()) {
                    val item = glosses.optJSONObject(i) ?: continue
                    val word = item.optString("quote")
                    val labels = labelsFor(content.lexeme(word)).joinToString(" · ")
                    Text("$word  ·  ${item.optString("brief_zh")}  $labels", color = colors.ink,
                        fontSize = 14.sp, lineHeight = 23.sp)
                }
            }
          } else if (error != null) {
            Text(error, color = colors.paragraph, fontSize = 14.sp)
            Text("重试解析 →", Modifier.clickable(onClick = onRetry).padding(top = 12.dp), color = colors.word, fontSize = 14.sp)
          } else {
            ProgressBlock(progress, colors)
          }
          Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun WordSheet(
    target: WordTarget, article: Article, content: Content, result: JSONObject?, progress: QueryProgress?,
    error: String?, colors: Palette, screenWidth: androidx.compose.ui.unit.Dp, screenHeight: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit, onConfigure: () -> Unit
) {
    val density = LocalDensity.current
    val panelWidth = screenWidth * .88f
    val x = with(density) { target.anchor.left.toDp() }.coerceIn(12.dp, screenWidth - panelWidth - 12.dp)
    val top = with(density) { target.anchor.bottom.toDp() + 10.dp }
    val y = if (top > screenHeight * .51f) (with(density) { target.anchor.top.toDp() } - screenHeight * .50f).coerceAtLeast(12.dp) else top
    val lexeme = content.lexeme(target.token.text)
    Column(Modifier.offset(x = x, y = y).width(panelWidth).heightIn(max = screenHeight * .56f).background(colors.sheet)
        .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)) {
        Text(target.token.text, color = colors.ink, fontFamily = ReadingFont, fontSize = 34.sp,
            lineHeight = 39.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(labelsFor(lexeme).joinToString("  /  ").ifBlank { "语境词" }, color = colors.word,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
        Spacer(Modifier.height(18.dp))
        if (result != null) {
            Text("本句取义", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(result.optJSONObject("context_sense")?.optString("zh").orEmpty(), color = colors.ink,
                fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
            val evidence = result.optJSONObject("context_sense")?.optString("evidence").orEmpty()
            if (evidence.isNotBlank()) Text(evidence, color = colors.word, fontSize = 13.sp, fontFamily = ReadingFont)
            Spacer(Modifier.height(18.dp))
        }
        if (lexeme != null) {
            Text("预置词典 · 其他义项", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(lexeme.translation, color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(16.dp))
        }
        val common = result?.optJSONArray("common_senses")
        if (lexeme == null && common != null && common.length() > 0) {
            Text("常见义项", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            for (i in 0 until common.length()) {
                val item = common.optJSONObject(i) ?: continue
                Text("${item.optString("part_of_speech")}  ${item.optString("zh")}", color = colors.ink, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
        val derivatives = result?.optJSONArray("derivatives")
        if (derivatives != null && derivatives.length() > 0) {
            Text("派生词", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            for (i in 0 until derivatives.length()) {
                val item = derivatives.optJSONObject(i) ?: continue
                Text("${item.optString("word")}  ·  ${item.optString("relation")}  ${item.optString("zh")}",
                    color = colors.ink, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
        if (result == null && error == null) ProgressBlock(progress, colors)
        if (error != null) {
            Text(error, color = colors.paragraph, fontSize = 14.sp, lineHeight = 20.sp)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("重试 →", Modifier.clickable(onClick = onRetry), color = colors.word, fontSize = 14.sp)
                Text("设置接口 →", Modifier.clickable(onClick = onConfigure), color = colors.word, fontSize = 14.sp)
            }
        }
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
private fun SettingsSheet(
    colors: Palette, dark: Boolean, current: Provider, settings: SecureSettings, modifier: Modifier,
    onDark: (Boolean) -> Unit, onSave: () -> Unit
) {
    var selected by remember { mutableStateOf(current.name) }
    var key by remember(selected) { mutableStateOf("") }
    var url by remember { mutableStateOf(settings.customBaseUrl) }
    var model by remember { mutableStateOf(settings.customModel) }
    var bodySize by remember { mutableStateOf(settings.bodySize) }
    var lineFactor by remember { mutableStateOf(settings.lineFactor) }
    var sideMargin by remember { mutableStateOf(settings.sideMargin) }
    var paragraphGap by remember { mutableStateOf(settings.paragraphGap) }
    Column(modifier.background(colors.sheet).verticalScroll(rememberScrollState()).padding(26.dp)) {
        Text("SETTINGS", color = colors.word, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(12.dp))
        Text("阅读与连接", color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().clickable { onDark(!dark) }, verticalAlignment = Alignment.CenterVertically) {
            Text("深色主题", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (dark) "ON" else "OFF", color = colors.word, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
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
        Spacer(Modifier.height(28.dp))
        Text("保存并返回 →", Modifier.fillMaxWidth().background(colors.word.copy(alpha = .18f)).clickable {
            settings.providerName = selected
            settings.bodySize = bodySize
            settings.lineFactor = lineFactor
            settings.sideMargin = sideMargin
            settings.paragraphGap = paragraphGap
            if (selected == "自定义") { settings.customBaseUrl = url; settings.customModel = model }
            if (key.isNotBlank()) settings.saveKey(selected, key)
            key = ""
            onSave()
        }.padding(vertical = 15.dp, horizontal = 18.dp), color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
    Box(Modifier.fillMaxWidth().background(colors.paper.copy(alpha = .58f)).padding(13.dp)) {
        if (value.isBlank()) Text(label, color = colors.muted, fontSize = 14.sp)
        BasicTextField(value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink, fontSize = 14.sp),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            singleLine = true)
    }
}
