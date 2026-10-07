package site.arcol.contextoto

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs

internal const val GLASS_BLUR = 34f
internal const val GLASS_TINT = .20f

internal fun Modifier.quietClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)

@Composable
internal fun FrostedBar(source: GraphicsLayer, colors: Palette, offsetY: Float = 0f,
                        modifier: Modifier = Modifier, opaqueBackdrop: Boolean = true,
                        tint: Float = GLASS_TINT, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clipToBounds()) {
        // Reproduce the blurred backdrop over paper before tinting. A transparent
        // recording alone would let the original, sharp text bleed through it.
        Canvas(Modifier.matchParentSize().then(if (opaqueBackdrop) Modifier.background(colors.paper) else Modifier)
            .graphicsLayer { renderEffect = BlurEffect(GLASS_BLUR, GLASS_BLUR, TileMode.Clamp) }) {
            translate(top = -offsetY) { drawLayer(source) }
        }
        if (tint > 0f) Box(Modifier.matchParentSize().background(colors.paper.copy(alpha = tint)))
        content()
    }
}

internal fun uiTitle(text: String): String = when (text) {
    "阅读与连接" -> "SETTINGS"
    "正文比例" -> "READING SCALE"
    "模型与连接" -> "MODEL & API"
    "碎片专注" -> "FOCUS REPORTING"
    "复习" -> "REVIEW"
    "列表" -> "COLLECTION"
    "词库" -> "WORD BANKS"
    "整句释义" -> "TRANSLATION"
    "从句范围" -> "CLAUSES"
    "词汇讲解" -> "VOCABULARY"
    "本句释义", "本题所取释义" -> "IN CONTEXT"
    "通用释义", "其他义项" -> "MEANINGS"
    "派生词" -> "DERIVATIVES"
    "题目错误" -> "QUESTION ISSUE"
    "今日复习" -> "TODAY"
    "已学会" -> "MASTERED"
    "已查询" -> "QUERIED"
    "音标" -> "PRONUNCIATION"
    "科目" -> "SUBJECT"
    "事项" -> "FOCUS ITEM"
    "请求协议" -> "PROTOCOL"
    "API 端点" -> "API ENDPOINT"
    "当前模型 ID" -> "MODEL ID"
    "深色主题" -> "DARK THEME"
    "沉浸状态栏" -> "IMMERSIVE STATUS"
    "跟随书签" -> "FOLLOW BOOKMARK"
    "已查询单词加粗" -> "QUERIED WORDS"
    "静默推理" -> "SILENT ANALYSIS"
    "复习与导入" -> "REVIEW & ENROLLMENT"
    "随机加入词数" -> "ENROLLMENT SIZE"
    "每日复习词数" -> "DAILY REVIEW LIMIT"
    "重新生成" -> "REGENERATE"
    "字号" -> "TYPE SIZE"
    "行距" -> "LINE HEIGHT"
    "左右边距" -> "SIDE MARGINS"
    "段落间距" -> "PARAGRAPH GAP"
    "挖孔预留高度" -> "CUTOUT HEIGHT"
    else -> text.uppercase(java.util.Locale.US)
}

@Composable
internal fun UiHeading(title: String, colors: Palette, modifier: Modifier = Modifier, size: Int = 25) {
    Text(uiSmallCaps(uiTitle(title), size.toFloat()), modifier, color = colors.ink, fontFamily = ReadingFont,
        fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = .6.sp)
}

/** An underlined action, with only the upper feather of the arrow. Stroke matches text weight. */
@Composable
internal fun JumpLink(label: String, colors: Palette, modifier: Modifier = Modifier, enabled: Boolean = true,
                      size: Int = 14, bold: Boolean = false, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val ink by animateColorAsState(if (!enabled) colors.muted else if (pressed) colors.ink else colors.word, tween(120), label = "link press")
    Box(Modifier.clickable(interactionSource = interactions, indication = null, enabled = enabled, onClick = onClick).then(modifier).padding(vertical = 8.dp)) {
    Column(Modifier.width(IntrinsicSize.Max)) {
        Text(label.removeSuffix(" →"), color = ink, fontFamily = FontFamily.SansSerif,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = size.sp)
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val stroke = (if (bold) 1.5.dp else 1.dp).toPx()
            val y = this.size.height * .8f
            drawLine(ink, Offset(0f, y), Offset(this.size.width, y), stroke)
            drawLine(ink, Offset(this.size.width - 5.dp.toPx(), y - 4.dp.toPx()), Offset(this.size.width, y), stroke)
        }
    }
    }
}

/** Physical page coordinate: settings=-1, directory=-.6, reading=0, words=1, menu=1.6. */
internal fun deckTarget(position: Float, velocity: Float, bounds: ClosedFloatingPointRange<Float> = -1f..1.6f,
                        settingsOnly: Boolean = false, origin: Float? = null): Float {
    val anchors = if (settingsOnly) listOf(-1f, 0f) else listOf(-1f, -.6f, 0f, 1f, 1.6f)
    val candidates = anchors.filter { it in bounds }
    val projected = (position + velocity * .15f).coerceIn(bounds.start, bounds.endInclusive)
    if (origin != null) {
        val index = candidates.indices.minBy { abs(candidates[it] - origin) }
        val next = (index + if (projected >= candidates[index]) 1 else -1).coerceIn(candidates.indices)
        if (abs(projected - candidates[index]) >= abs(candidates[next] - candidates[index]) * .4f) return candidates[next]
        return candidates[index]
    }
    return candidates.minBy { abs(it - projected) }
}
internal fun directoryFraction(position: Float): Float =
    (if (position < -.6f) (position + 1f) / .4f else -position / .6f).coerceIn(0f, 1f)
internal fun wordMenuFraction(position: Float): Float = ((position - 1f) / .6f).coerceIn(0f, 1f)

internal class DeckMotion(private val scope: CoroutineScope) {
    val position = Animatable(0f)
    val card = Animatable(0f)
    var incoming by mutableStateOf(false)
    var swapping by mutableStateOf(false)
    private var settling: Job? = null
    private var settlingTarget: Float? = null
    private var exchange: Job? = null
    fun settle(target: Float, duration: Int = 340) {
        // A state update can request this same destination in the next composition.
        // Keep the running transition, including the 160 ms exchange return leg.
        if (settling?.isActive == true && settlingTarget == target) return
        settling?.cancel()
        settlingTarget = target
        settling = scope.launch { position.animateTo(target, tween(duration, easing = FastOutSlowInEasing)) }
    }
    suspend fun startDrag() { settling?.cancel(); position.stop() }
    suspend fun drag(delta: Float, bounds: ClosedFloatingPointRange<Float>) {
        position.snapTo((position.value + delta).coerceIn(bounds.start, bounds.endInclusive))
    }
    fun exchange(target: Float, change: () -> Unit, selected: () -> Unit) {
        if (swapping) return
        exchange = scope.launch {
            swapping = true
            try {
                incoming = false; card.snapTo(0f); card.animateTo(1f, tween(120))
                change(); incoming = true; card.animateTo(0f, tween(120))
                selected(); settle(target, 160)
            } finally { card.snapTo(0f); swapping = false }
        }
    }
}

private class RetainedSpace(val scroll: ScrollState) {
    var floor by mutableIntStateOf(0)
    var natural = 0
    var viewport = 0
    private val collapsing = mutableSetOf<Any>()
    var collapseEpoch by mutableIntStateOf(0)
    fun begin(key: Any) { collapsing.add(key); if (scroll.value > 0) floor = maxOf(floor, scroll.value + viewport) }
    fun end(key: Any) { if (collapsing.remove(key)) collapseEpoch++ }
    val inTransition get() = collapsing.isNotEmpty()
    val debt get() = (floor - natural).coerceAtLeast(0)
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (debt == 0 || scroll.value + viewport <= natural) return Offset.Zero
            if (available.y > 0f) return Offset(0f, available.y)
            val reclaim = minOf(debt.toFloat(), -available.y).toInt()
            if (reclaim <= 0) return Offset.Zero
            floor -= reclaim
            scroll.dispatchRawDelta(-reclaim.toFloat())
            return Offset(0f, -reclaim.toFloat())
        }
    }
}
private val LocalRetainedSpace = staticCompositionLocalOf<RetainedSpace?> { null }

@Composable
internal fun RetainedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val retained = remember(scroll) { RetainedSpace(scroll) }
    CompositionLocalProvider(LocalRetainedSpace provides retained) {
        Box(modifier.onSizeChanged { retained.viewport = it.height }) {
            Layout(content = { Column(Modifier.fillMaxWidth(), content = content) },
                modifier = Modifier.fillMaxWidth().nestedScroll(retained.connection).verticalScroll(scroll)) { measurables, constraints ->
                retained.collapseEpoch // End of shrinking must invalidate the protected measurement.
                val child = measurables.single().measure(constraints.copy(minHeight = 0))
                retained.natural = child.height
                val height = maxOf(child.height, retained.floor)
                if (!retained.inTransition && child.height >= retained.floor && retained.floor > 0) retained.floor = 0
                layout(child.width, height) { child.place(0, 0) }
            }
        }
    }
}

@Composable
internal fun RetainedFoldout(expanded: Boolean, key: Any, content: @Composable () -> Unit) {
    val retained = LocalRetainedSpace.current
    var previous by remember(key) { mutableStateOf(expanded) }
    SideEffect { if (previous && !expanded) retained?.begin(key); if (!previous && expanded) retained?.end(key); previous = expanded }
    LaunchedEffect(expanded) { if (!expanded) { delay(300); retained?.end(key) } }
    DisposableEffect(key) { onDispose { retained?.end(key) } }
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(400), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(tween(400)) +
                scaleIn(tween(400), .7f, TransformOrigin(.5f, 0f)) + slideInVertically(tween(400)) { -12 },
            exit = fadeOut(tween(220)) + shrinkVertically(tween(260), shrinkTowards = androidx.compose.ui.Alignment.Top) + scaleOut(tween(260), .7f, TransformOrigin(.5f, 0f))) {
            content()
        }
}
