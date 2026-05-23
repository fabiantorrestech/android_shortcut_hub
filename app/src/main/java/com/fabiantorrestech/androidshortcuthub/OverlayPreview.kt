package com.fabiantorrestech.androidshortcuthub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
                                            config = tile.config,
                                            isMoveMode = isMoveMode,
                                            onLongPress = {
                                                if (hapticFeedbackEnabled) {
                                                    view.performHapticForcefully(HapticFeedbackType.LongPress)
                                                }
                                                onTileLongPress(tile)
                                            },
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
