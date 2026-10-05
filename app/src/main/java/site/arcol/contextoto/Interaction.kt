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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val GLASS_BLUR = 34f
internal const val GLASS_TINT = .20f

@Composable
internal fun FrostedBar(source: GraphicsLayer, colors: Palette, offsetY: Float = 0f,
                        modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clipToBounds()) {
        // Reproduce the blurred backdrop over paper before tinting. A transparent
        // recording alone would let the original, sharp text bleed through it.
        Canvas(Modifier.matchParentSize().background(colors.paper).graphicsLayer { renderEffect = BlurEffect(GLASS_BLUR, GLASS_BLUR, TileMode.Clamp) }) {
            translate(top = -offsetY) { drawLayer(source) }
        }
        Box(Modifier.matchParentSize().background(colors.paper.copy(alpha = GLASS_TINT)))
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
    Text(uiTitle(title), modifier, color = colors.ink, fontFamily = ReadingFont,
        fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = .6.sp)
}

/** An underlined action, with only the upper feather of the arrow. Stroke matches text weight. */
@Composable
internal fun JumpLink(label: String, colors: Palette, modifier: Modifier = Modifier, enabled: Boolean = true,
                      size: Int = 14, bold: Boolean = false, onClick: () -> Unit) {
    val ink = if (enabled) colors.word else colors.muted
    Box(modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp)) {
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
internal fun deckTarget(position: Float, velocity: Float, bounds: ClosedFloatingPointRange<Float> = -1f..1.6f): Float {
    val anchors = listOf(-1f, -.6f, 0f, 1f, 1.6f)
    val projected = (position + velocity * .12f).coerceIn(bounds.start, bounds.endInclusive)
    return anchors.filter { it in bounds }.minBy { abs(it - projected) }
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
    val heights = mutableStateMapOf<Any, Int>()
    val collapsed = mutableStateMapOf<Any, Boolean>()
    val parents = mutableMapOf<Any, List<Any>>()
    val visibleKeys get() = heights.keys.filter { key -> parents[key].orEmpty().none { collapsed[it] == true } }
    val debt get() = visibleKeys.sumOf { heights[it] ?: 0 }
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (debt == 0 || scroll.value < scroll.maxValue - debt) return Offset.Zero
            if (available.y > 0f) return Offset(0f, available.y)
            var reclaim = minOf(debt.toFloat(), -available.y).toInt()
            if (reclaim <= 0) return Offset.Zero
            val removed = reclaim
            visibleKeys.forEach { key ->
                val amount = minOf(heights[key] ?: 0, reclaim)
                heights[key] = (heights[key] ?: 0) - amount; reclaim -= amount
            }
            // Layout above the viewport shrinks by this amount; compensate scroll to keep its anchor stable.
            scroll.dispatchRawDelta(-removed.toFloat())
            return Offset(0f, -removed.toFloat())
        }
    }
}
private val LocalRetainedSpace = staticCompositionLocalOf<RetainedSpace?> { null }
private val LocalFoldoutParents = staticCompositionLocalOf { emptyList<Any>() }

@Composable
internal fun RetainedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val retained = remember(scroll) { RetainedSpace(scroll) }
    CompositionLocalProvider(LocalRetainedSpace provides retained) {
        Column(modifier.nestedScroll(retained.connection).verticalScroll(scroll), content = content)
    }
}

@Composable
internal fun RetainedFoldout(expanded: Boolean, key: Any, content: @Composable () -> Unit) {
    val retained = LocalRetainedSpace.current
    val parents = LocalFoldoutParents.current
    var measured by remember(key) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(expanded) {
        retained?.parents?.set(key, parents)
        retained?.collapsed?.set(key, !expanded)
        if (expanded) retained?.heights?.remove(key) else if (measured > 0) retained?.heights?.set(key, measured)
    }
    DisposableEffect(key) { onDispose { retained?.heights?.remove(key); retained?.collapsed?.remove(key); retained?.parents?.remove(key) } }
    val reserve = if (!expanded && retained != null) retained.heights[key] ?: measured else 0
    Box(if (reserve > 0) Modifier.height(with(density) { reserve.toDp() }).clipToBounds() else Modifier) {
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(400), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(tween(400)) +
                scaleIn(tween(400), .7f, TransformOrigin(.5f, 0f)) + slideInVertically(tween(400)) { -12 },
            exit = fadeOut(tween(220)) + scaleOut(tween(260), .7f, TransformOrigin(.5f, 0f))) {
            CompositionLocalProvider(LocalFoldoutParents provides (parents + key)) {
                Box(Modifier.onSizeChanged { if (expanded) measured = it.height }) { content() }
            }
        }
    }
}
