package site.arcol.contextoto

import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

internal fun sentenceBookmarkBands(layout: TextLayoutResult, start: Int, horizontalPad: Float = 0f,
                                   verticalPad: Float = 0f): List<BookmarkBand> {
    val text = layout.layoutInput.text.text
    if (text.isBlank()) return emptyList()
    val sentence = Content.sentenceAt(text, start.coerceIn(0, text.lastIndex))
    val first = layout.getLineForOffset(sentence.start)
    val last = layout.getLineForOffset((sentence.end - 1).coerceAtLeast(sentence.start))
    return (first..last).map { line ->
        val begin = maxOf(sentence.start, layout.getLineStart(line))
        val end = minOf(sentence.end, layout.getLineEnd(line, visibleEnd = true))
        val boxes = (begin until end).filter { !text[it].isWhitespace() }.map(layout::getBoundingBox)
        BookmarkBand((boxes.minOfOrNull { it.left } ?: layout.getLineLeft(line)) - horizontalPad,
            layout.getLineTop(line) - if (line == first) verticalPad else 0f,
            (boxes.maxOfOrNull { it.right } ?: layout.getLineRight(line)) + horizontalPad,
            layout.getLineBottom(line) + if (line == last) verticalPad else 0f)
    }
}

private class BookmarkFrame {
    var positions = emptyMap<Any, Int>()
    var displacement = 0f
    var lastBands = emptyList<BookmarkBand>()
    var lastDisplacement = 0f
    fun viewport(next: Map<Any, Int>) {
        val common = next.keys.firstOrNull { it in positions }
        if (common != null) displacement += next.getValue(common) - positions.getValue(common)
        positions = next
    }
}

@Composable
internal fun SentenceBookmarkBackdrop(list: LazyListState, place: BookmarkPlace,
                                      layouts: Map<Int, TextLayoutResult>, sideMargin: Float, colors: Palette) {
    val density = LocalDensity.current
    val padding = with(density) { 6.dp.toPx() }
    val vertical = with(density) { 4.dp.toPx() }
    val margin = with(density) { sideMargin.dp.toPx() }
    val layout = layouts[place.paragraph]
    val targetBands = remember(layout, place, padding, vertical, margin) {
        layout?.let { sentenceBookmarkBands(it, place.sentenceStart, padding, vertical).map { band -> band.offset(x = margin) } }.orEmpty()
    }
    val frame = remember { BookmarkFrame() }
    var from by remember { mutableStateOf<List<BookmarkBand>>(emptyList()) }
    var fromScroll by remember { mutableStateOf(0f) }
    val travel = remember { Animatable(1f) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    LaunchedEffect(place, targetBands) {
        if (targetBands.isEmpty()) return@LaunchedEffect
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "paragraph-${place.paragraph}" }
            ?: return@LaunchedEffect
        // Capture the displayed contour on interruption, so rapid queries continue from the current frame.
        from = frame.lastBands.takeIf { it.isNotEmpty() } ?: targetBands.map { it.offset(y = (target.offset - list.layoutInfo.viewportStartOffset).toFloat()) }
        fromScroll = frame.lastDisplacement
        if (frame.lastBands.isEmpty()) travel.snapTo(1f) else {
            travel.snapTo(0f)
            travel.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val info = list.layoutInfo
        frame.viewport(info.visibleItemsInfo.associate { it.key to (it.offset - info.viewportStartOffset) })
        val target = info.visibleItemsInfo.firstOrNull { it.key == "paragraph-${place.paragraph}" } ?: return@Canvas
        if (targetBands.isEmpty()) return@Canvas
        val destination = targetBands.map { it.offset(y = (target.offset - info.viewportStartOffset).toFloat()) }
        val origin = from.map { it.offset(y = frame.displacement - fromScroll) }
        val bands = interpolateBookmarkBands(origin, destination, travel.value)
        frame.lastBands = bands; frame.lastDisplacement = frame.displacement
        val path = Path().apply {
            moveTo(bands.first().left, bands.first().top)
            bands.forEachIndexed { index, band ->
                lineTo(band.left, band.bottom)
                if (index < bands.lastIndex) lineTo(bands[index + 1].left, band.bottom)
            }
            lineTo(bands.last().right, bands.last().bottom)
            for (index in bands.indices.reversed()) {
                val band = bands[index]
                lineTo(band.right, band.top)
                if (index > 0) lineTo(bands[index - 1].right, band.top)
            }
            close()
        }
        paint.pathEffect = CornerPathEffect(9.dp.toPx())
        paint.shader = LinearGradient(0f, bands.first().top, 0f, bands.last().bottom,
            colors.word.copy(alpha = .095f).toArgb(), colors.word.copy(alpha = .065f).toArgb(), Shader.TileMode.CLAMP)
        drawIntoCanvas { it.nativeCanvas.drawPath(path, paint) }
    }
}
