package com.fabiantorrestech.androidshortcuthub

import android.media.AudioManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * Determines how [OverlayGridPreview] renders and responds to tile interactions.
 */
internal enum class OverlayRenderMode {
    /** Normal live overlay — tiles launch apps, widgets are real, sliders are interactive. */
    Runtime,
    /** In-app editor preview — tapping any tile selects it; widgets and sliders show placeholders. */
    EditorPreview,
}

/**
 * Shared tile grid renderer used both by the live [OverlayContent] (Runtime mode) and by the
 * in-app layout editor (EditorPreview mode).
 *
 * Callers supply lambdas for the behaviours that differ between modes:
 * - [onTileSelect]        — EditorPreview: called with tile id when any tile is tapped.
 * - [onTileTap]           — Runtime: called when a normal (App/Intent) tile is tapped.
 * - [onTileLongPress]     — Runtime: called to open the edit sheet for a tile.
 * - [onSliderBoundsChanged] — Runtime: tracks slider screen bounds for gesture protection.
 * - [widgetContent]       — Runtime: renders the real AppWidgetHostView inside the tile Box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OverlayGridPreview(
    tiles: List<TileState>,
    gridRows: Int,
    gridColumns: Int,
    showGrid: Boolean,
    mode: OverlayRenderMode,
    selectedTileId: Int?,
    isMoveMode: Boolean = false,
    defaultTextScale: Float,
    defaultFontWeight: FontWeight,
    defaultFontFamily: FontFamily?,
    defaultTextColor: Color,
    hapticFeedbackEnabled: Boolean,
    preloadedFonts: Map<String, FontFamily?>,
    modifier: Modifier = Modifier,
    onTileSelect: (Int) -> Unit = {},
    onTileTap: (TileState) -> Unit = {},
    onTileLongPress: (TileState) -> Unit = {},
    onSliderBoundsChanged: (Int, Rect) -> Unit = { _, _ -> },
    widgetContent: @Composable BoxScope.(WidgetTileState) -> Unit = {},
) {
    val view = LocalView.current

    // Picker / fan-out rendered at grid level to avoid TYPE_ACCESSIBILITY_OVERLAY popup z-order issues
    val context = LocalContext.current
    val a11yEnabled = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.getSystemService(AccessibilityManager::class.java)?.isEnabled == true
    }
    val pickerStreams = remember(a11yEnabled) {
        buildList {
            add(AudioManager.STREAM_MUSIC        to (Icons.AutoMirrored.Rounded.VolumeUp to "Media"))
            add(AudioManager.STREAM_RING         to (Icons.Rounded.Phone                 to "Ring"))
            add(AudioManager.STREAM_ALARM        to (Icons.Rounded.Alarm                 to "Alarm"))
            add(AudioManager.STREAM_NOTIFICATION to (Icons.Rounded.Notifications         to "Notif"))
            if (a11yEnabled) add(AudioManager.STREAM_ACCESSIBILITY to (Icons.Rounded.Accessibility to "TalkBack"))
        }
    }
    var pickerOpenTileId by remember { mutableStateOf<Int?>(null) }
    var pendingStreamForTile by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var fanOutOpenTileId by remember { mutableStateOf<Int?>(null) }
    var fanOutTileSnapshot by remember { mutableStateOf<SystemSliderTileState?>(null) }
    var fanOutEnterVisible by remember { mutableStateOf(false) }
    var fanOutExitRequested by remember { mutableStateOf(false) }
    val fanOutIsEntering = fanOutEnterVisible && !fanOutExitRequested
    val fanOutAlpha by animateFloatAsState(
        targetValue = when { fanOutExitRequested -> 0f; fanOutEnterVisible -> 1f; else -> 0f },
        animationSpec = tween(durationMillis = if (fanOutIsEntering) 120 else 280),
        label = "fanOutAlpha",
    )
    val fanOutBlur by animateDpAsState(
        targetValue = when { fanOutExitRequested -> 16.dp; fanOutEnterVisible -> 0.dp; else -> 16.dp },
        animationSpec = tween(durationMillis = if (fanOutIsEntering) 120 else 280),
        label = "fanOutBlur",
    )
    LaunchedEffect(fanOutOpenTileId) {
        val id = fanOutOpenTileId
        if (id != null) {
            fanOutTileSnapshot = tiles.filterIsInstance<SystemSliderTileState>().firstOrNull { it.id == id }
            fanOutExitRequested = false
            fanOutEnterVisible = true
        }
    }
    val dismissFanOut: () -> Unit = {
        fanOutExitRequested = true
        fanOutEnterVisible = false
    }
    LaunchedEffect(fanOutExitRequested) {
        if (fanOutExitRequested) {
            delay(300L)
            fanOutOpenTileId = null
            fanOutTileSnapshot = null
            fanOutExitRequested = false
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val cellWidth = maxWidth / gridColumns
        val cellHeight = maxHeight / gridRows

        // Optional grid lines
        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cw = size.width / gridColumns
                val ch = size.height / gridRows
                for (col in 0..gridColumns) {
                    val x = col * cw
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                for (row in 0..gridRows) {
                    val y = row * ch
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
            }
        }

        tiles.forEach { tile ->
            val isSelected = tile.id == selectedTileId
            val isWidgetInEditMode = tile is WidgetTileState && isMoveMode

            // Border and background colours differ between modes
            val tileBorderColor = when (mode) {
                OverlayRenderMode.EditorPreview -> when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                }
                OverlayRenderMode.Runtime -> when {
                    isWidgetInEditMode && isSelected -> MaterialTheme.colorScheme.tertiary
                    isWidgetInEditMode -> MaterialTheme.colorScheme.outlineVariant
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                }
            }
            val tileBackgroundColor = when (mode) {
                OverlayRenderMode.EditorPreview -> when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
                }
                OverlayRenderMode.Runtime -> when {
                    isWidgetInEditMode && isSelected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f)
                    isWidgetInEditMode -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    else -> Color.Transparent
                }
            }

            // Build the tap modifier according to mode and tile type
            val isInteractiveTile = tile is WidgetTileState || tile is SystemSliderTileState
            val tileModifier: Modifier = when (mode) {
                OverlayRenderMode.EditorPreview -> {
                    // In editor: all tiles are selectable via a simple tap
                    Modifier
                        .offset(x = cellWidth * tile.column, y = cellHeight * tile.row)
                        .size(width = cellWidth * tile.columnSpan, height = cellHeight * tile.rowSpan)
                        .padding(1.dp)
                        .combinedClickable(
                            onClick = { onTileSelect(tile.id) },
                            onLongClick = { onTileSelect(tile.id) },
                        )
                }
                OverlayRenderMode.Runtime -> {
                    if (isInteractiveTile && isMoveMode) {
                        Modifier
                            .offset(x = cellWidth * tile.column, y = cellHeight * tile.row)
                            .size(width = cellWidth * tile.columnSpan, height = cellHeight * tile.rowSpan)
                            .padding(6.dp)
                            .combinedClickable(
                                onClick = { onTileLongPress(tile) },
                                onLongClick = { onTileLongPress(tile) },
                            )
                    } else if (isInteractiveTile) {
                        // Widget / slider handle their own touch; long-press still opens sheet
                        Modifier
                            .offset(x = cellWidth * tile.column, y = cellHeight * tile.row)
                            .size(width = cellWidth * tile.columnSpan, height = cellHeight * tile.rowSpan)
                            .padding(6.dp)
                    } else {
                        Modifier
                            .offset(x = cellWidth * tile.column, y = cellHeight * tile.row)
                            .size(width = cellWidth * tile.columnSpan, height = cellHeight * tile.rowSpan)
                            .padding(6.dp)
                            .combinedClickable(
                                onClick = {
                                    if (hapticFeedbackEnabled) {
                                        view.performHapticForcefully(HapticFeedbackType.LongPress)
                                    }
                                    if (selectedTileId == null) {
                                        onTileTap(tile)
                                    }
                                },
                                onLongClick = {
                                    if (hapticFeedbackEnabled) {
                                        view.performHapticForcefully(HapticFeedbackType.LongPress)
                                    }
                                    onTileLongPress(tile)
                                },
                            )
                    }
                }
            }

            Card(
                modifier = tileModifier
                    .then(
                        if (tile is SystemSliderTileState && mode == OverlayRenderMode.Runtime) {
                            Modifier.onGloballyPositioned { coordinates ->
                                onSliderBoundsChanged(tile.id, coordinates.boundsInRoot())
                            }
                        } else {
                            Modifier
                        },
                    )
                    .border(
                        width = when {
                            isWidgetInEditMode || isSelected -> 2.dp
                            mode == OverlayRenderMode.EditorPreview -> 1.dp
                            else -> 0.dp
                        },
                        color = tileBorderColor,
                        shape = RoundedCornerShape(if (mode == OverlayRenderMode.EditorPreview) 4.dp else 18.dp),
                    ),
                shape = RoundedCornerShape(if (mode == OverlayRenderMode.EditorPreview) 4.dp else 18.dp),
                colors = CardDefaults.cardColors(containerColor = tileBackgroundColor),
            ) {
                Surface(color = tileBackgroundColor) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (mode) {
                            OverlayRenderMode.EditorPreview -> {
                                // Plain empty tile — no content, just the card border/background
                            }
                            OverlayRenderMode.Runtime -> {
                                when (tile) {
                                    is WidgetTileState -> {
                                        widgetContent(tile)
                                        if (isMoveMode) {
                                            Text(
                                                text = if (isSelected) "EDITING" else "WIDGET",
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(8.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.tertiary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }

                                    is AppTileState -> {
                                        AppTileContent(
                                            tile = tile,
                                            defaultTextScale = defaultTextScale,
                                            defaultFontFamily = defaultFontFamily,
                                            defaultFontWeight = defaultFontWeight,
                                            defaultTextColor = defaultTextColor,
                                            preloadedFonts = preloadedFonts,
                                            loadFontFamily = { null },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    is SystemSliderTileState -> {
                                        SystemSliderTile(
                                            tileId = tile.id,
                                            config = tile.config,
                                            isMoveMode = isMoveMode,
                                            onLongPress = {
                                                if (hapticFeedbackEnabled) {
                                                    view.performHapticForcefully(HapticFeedbackType.LongPress)
                                                }
                                                onTileLongPress(tile)
                                            },
                                            onPickerOpen = { id -> pickerOpenTileId = id },
                                            onFanOutOpen = { id -> fanOutOpenTileId = id },
                                            pendingStreamForTile = pendingStreamForTile,
                                            onStreamConsumed = { pendingStreamForTile = null },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        if (isMoveMode) {
                                            Text(
                                                text = if (isSelected) "EDITING" else tile.displayLabel.uppercase(),
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(8.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.tertiary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }

                                    else -> {
                                        Text(
                                            text = tile.displayLabel,
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .padding(8.dp),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = MaterialTheme.typography.bodyMedium.fontSize *
                                                    (tile.customTextScale ?: defaultTextScale),
                                                fontFamily = rememberTileFontFamily(
                                                    fontUri = tile.customFontUri,
                                                    preloadedFonts = preloadedFonts,
                                                    loadFontFamily = { null },
                                                ) ?: defaultFontFamily,
                                            ),
                                            color = defaultTextColor,
                                            fontWeight = when {
                                                isSelected -> FontWeight.SemiBold
                                                tile.customBoldText != null -> if (tile.customBoldText == true) FontWeight.Bold else FontWeight.Normal
                                                else -> defaultFontWeight
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Inline PICKER overlay ───────────────────────────────────────────────
        // Rendered last (on top of all tiles). Avoids TYPE_APPLICATION_PANEL popup z-order issue.
        if (pickerOpenTileId != null) {
            val pickerTile = tiles.filterIsInstance<SystemSliderTileState>()
                .firstOrNull { it.id == pickerOpenTileId }
            if (pickerTile != null) {
                val tileX = cellWidth * pickerTile.column
                val tileY = cellHeight * pickerTile.row
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { pickerOpenTileId = null },
                )
                Card(
                    modifier = Modifier
                        .offset(x = tileX + 4.dp, y = tileY + SLIDER_CHIP_HEIGHT + 4.dp)
                        .wrapContentWidth()
                        .wrapContentHeight(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        pickerStreams.forEach { (stream, pair) ->
                            val (icon, label) = pair
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        pendingStreamForTile = pickerOpenTileId!! to stream
                                        pickerOpenTileId = null
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(icon, contentDescription = null)
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        // ── Inline FAN-OUT overlay ──────────────────────────────────────────────
        val fanTile = fanOutTileSnapshot
        if (fanTile != null) {
            val tileLeft = cellWidth * fanTile.column
            val tileRight = tileLeft + cellWidth * fanTile.columnSpan
            val tileTop = cellHeight * fanTile.row
            val panelH = cellHeight * fanTile.rowSpan
            val colWidth = 52.dp
            val panelW = colWidth * pickerStreams.size + 32.dp
            val panelX = when {
                tileLeft >= panelW -> tileLeft - panelW           // prefer LEFT
                maxWidth - tileRight >= panelW -> tileRight       // else RIGHT
                else -> (maxWidth - panelW).coerceAtLeast(0.dp)   // fallback CENTER
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { dismissFanOut() },
            )
            FanOutPanel(
                streams = pickerStreams,
                panelHeight = panelH,
                modifier = Modifier
                    .offset(x = panelX, y = tileTop)
                    .graphicsLayer { alpha = fanOutAlpha }
                    .blur(fanOutBlur),
            )
        }
    }
}

/**
 * Placeholder shown for widget tiles in EditorPreview mode where real widgets cannot be rendered.
 */
@Composable
internal fun WidgetPlaceholder(tile: WidgetTileState, modifier: Modifier = Modifier) {
    val providerShortName = tile.providerComponent.substringAfterLast(".")
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                RoundedCornerShape(12.dp),
            )
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = providerShortName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}
