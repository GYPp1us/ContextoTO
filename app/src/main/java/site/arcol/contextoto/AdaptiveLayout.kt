package site.arcol.contextoto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Measured, inset-consumed space, not the physical display or orientation flag. */
internal data class ReaderViewport(val width: Float = 360f, val height: Float = 780f, val fontScale: Float = 1f) {
    val compact get() = width > height * 1.15f
    // Leave two genuinely readable columns. Large accessibility text falls back to one.
    val split get() = compact && width / fontScale.coerceAtLeast(1f) >= 600f
    val gutter get() = 24f
    val gap get() = 32f
    val sheetWidth get() = if (split) (width - gutter * 2).coerceAtMost(1080f) else width * .86f
    fun readingMargin(preference: Float): Float = maxOf(preference, (width - 960f) / 2)
    fun sheetHeight(top: Float): Float = (height - top - 12f).coerceAtLeast(1f)
}

internal val LocalReaderViewport = staticCompositionLocalOf { ReaderViewport() }
/** Glyph geometry is in root coordinates; panes live inside the consumed safe insets. */
internal val LocalSurfaceOrigin = staticCompositionLocalOf { Offset.Zero }

@Composable
internal fun AdaptiveFooter(modifier: Modifier = Modifier, leading: @Composable () -> Unit, action: @Composable () -> Unit) {
    if (LocalReaderViewport.current.compact) Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.weight(1f)) { leading() }; Box { action() }
    } else Column(modifier) { leading(); action() }
}

/** One detail vocabulary: primary text on the left, reference material on the right.
 * Both panes scroll independently in a short/wide viewport. Movable content preserves
 * expanded entries and reveal state when the exact same detail changes its layout. */
@Composable
internal fun DetailColumns(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    leading: @Composable ColumnScope.() -> Unit,
    trailing: @Composable ColumnScope.() -> Unit
) {
    val viewport = LocalReaderViewport.current
    val lead by rememberUpdatedState(leading)
    val tail by rememberUpdatedState(trailing)
    val heading by rememberUpdatedState(header)
    val headerContent = remember { movableContentOf { heading() } }
    val leadingContent = remember { movableContentOf { Column(Modifier.fillMaxWidth(), content = lead) } }
    val trailingContent = remember { movableContentOf { Column(Modifier.fillMaxWidth(), content = tail) } }
    val primaryScroll = rememberScrollState()
    val referenceScroll = rememberScrollState()
    if (viewport.split) {
        Column(modifier) {
        headerContent()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(viewport.gap.dp)) {
            RetainedColumn(Modifier.weight(.44f).fillMaxHeight().testTag("detail-primary"), primaryScroll) { leadingContent() }
            RetainedColumn(Modifier.weight(.56f).fillMaxHeight().testTag("detail-reference"), referenceScroll) { trailingContent() }
        }
        }
    } else {
        RetainedColumn(modifier, primaryScroll) { headerContent(); leadingContent(); trailingContent() }
    }
}
