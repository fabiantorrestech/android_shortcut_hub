package com.fabiantorrestech.androidshortcuthub

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow

/**
 * Full-screen editor for a single [ScrollBoxTileState]'s inner grid, opened from the main layout
 * editor when a scrollbox tile is selected.
 *
 * It drives a scoped [OverlayEditorState] over the scrollbox's [ScrollBoxTileState.children] and
 * reuses [OverlayGridPreview] (EditorPreview) and [OverlayTileInspector] — the same machinery as the
 * main editor — so child App / Intent tiles select, move, resize, rename, and configure identically.
 * Scroll direction, scrollbar edge, and inner grid size are edited here; children are restricted to
 * App and Intent tiles.
 *
 * Changes are folded back into [parentEditorState] on exit (Done / back), where the main editor's
 * overall Save / Discard governs final persistence.
 */
@Composable
internal fun ScrollBoxEditorScreen(
    parentEditorState: OverlayEditorState,
    scrollBoxId: Int,
    onBack: () -> Unit,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
    fontEvents: Flow<TileFontSelection>,
    iconEvents: Flow<TileIconSelection>,
) {
    val context = LocalContext.current

    // Snapshot the scrollbox once; bail out safely if it no longer exists.
    val scrollBox = parentEditorState.tiles.firstOrNull { it.id == scrollBoxId } as? ScrollBoxTileState
    if (scrollBox == null) {
        LaunchedEffect(scrollBoxId) { onBack() }
        return
    }

    // Scoped editor state for the inner grid — reuses every grid operation OverlayEditorState provides.
    val innerEditorState = remember(scrollBoxId) {
        OverlayEditorState(
            OverlayUiState(
                tiles = scrollBox.children,
                gridRows = scrollBox.innerRows,
                gridColumns = scrollBox.innerColumns,
                nextTileId = scrollBox.nextChildId,
                defaultTextScale = parentEditorState.savedState.defaultTextScale,
                defaultBoldText = parentEditorState.savedState.defaultBoldText,
                defaultFontUri = parentEditorState.savedState.defaultFontUri,
                defaultFontName = parentEditorState.savedState.defaultFontName,
                defaultTextColorMode = parentEditorState.savedState.defaultTextColorMode,
                defaultTextColorHex = parentEditorState.savedState.defaultTextColorHex,
                hapticFeedbackEnabled = parentEditorState.savedState.hapticFeedbackEnabled,
            ),
        )
    }
    var scrollDirection by remember(scrollBoxId) { mutableStateOf(scrollBox.scrollDirection) }
    var scrollbarEdge by remember(scrollBoxId) { mutableStateOf(scrollBox.scrollbarEdge) }
    var gridPopupOpen by remember { mutableStateOf(false) }

    val defaultFontWeight = if (parentEditorState.savedState.defaultBoldText) FontWeight.Bold else FontWeight.Normal
    val defaultTextColor = resolveDefaultTileTextColor(
        mode = parentEditorState.savedState.defaultTextColorMode,
        hex = parentEditorState.savedState.defaultTextColorHex,
        fallback = MaterialTheme.colorScheme.onSurface,
    )

    fun commitAndExit() {
        val committed = innerEditorState.commit()
        parentEditorState.updateTile(scrollBoxId) { current ->
            (current as? ScrollBoxTileState)?.copy(
                children = committed.tiles,
                innerRows = committed.gridRows,
                innerColumns = committed.gridColumns,
                nextChildId = committed.nextTileId,
                scrollDirection = scrollDirection,
                scrollbarEdge = scrollbarEdge,
            ) ?: current
        }
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar: back/done | title | grid size ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { commitAndExit() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done editing scrollbox")
            }
            Text(
                text = scrollBox.displayLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            Box {
                IconButton(onClick = { gridPopupOpen = true }) {
                    Icon(Icons.Default.GridView, contentDescription = "Inner grid size")
                }
                DropdownMenu(expanded = gridPopupOpen, onDismissRequest = { gridPopupOpen = false }) {
                    ScrollBoxGridSizeContent(innerEditorState)
                }
            }
        }

        // ── Scroll direction + bar edge controls ────────────────────────────────
        val isVertical = scrollDirection == ScrollDirection.VERTICAL
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Scroll direction", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectChip("↕ Vertical", selected = isVertical, modifier = Modifier.weight(1f)) {
                    scrollDirection = ScrollDirection.VERTICAL
                    if (scrollbarEdge == ScrollbarEdge.TOP || scrollbarEdge == ScrollbarEdge.BOTTOM) {
                        scrollbarEdge = ScrollbarEdge.RIGHT
                    }
                }
                SelectChip("↔ Horizontal", selected = !isVertical, modifier = Modifier.weight(1f)) {
                    scrollDirection = ScrollDirection.HORIZONTAL
                    if (scrollbarEdge == ScrollbarEdge.LEFT || scrollbarEdge == ScrollbarEdge.RIGHT) {
                        scrollbarEdge = ScrollbarEdge.BOTTOM
                    }
                }
            }
            Text("Scrollbar edge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val edgeOptions = if (isVertical) {
                    listOf("Left" to ScrollbarEdge.LEFT, "Right" to ScrollbarEdge.RIGHT)
                } else {
                    listOf("Top" to ScrollbarEdge.TOP, "Bottom" to ScrollbarEdge.BOTTOM)
                }
                edgeOptions.forEach { (label, edge) ->
                    SelectChip(label, selected = scrollbarEdge == edge, modifier = Modifier.weight(1f)) {
                        scrollbarEdge = edge
                    }
                }
            }
        }

        // ── Inner grid preview ──────────────────────────────────────────────────
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.2f),
            contentAlignment = Alignment.Center,
        ) {
            val innerAspect = innerEditorState.gridColumns.toFloat() / innerEditorState.gridRows.toFloat()
            val availAspect = maxWidth / maxHeight
            val previewWidth = if (availAspect > innerAspect) maxHeight * innerAspect else maxWidth
            val previewHeight = if (availAspect > innerAspect) maxHeight else maxWidth / innerAspect

            Box(
                modifier = Modifier
                    .size(previewWidth, previewHeight)
                    .background(Color.Black.copy(alpha = parentEditorState.overlayBackgroundAlpha)),
            ) {
                OverlayGridPreview(
                    tiles = innerEditorState.tiles.toList(),
                    gridRows = innerEditorState.gridRows,
                    gridColumns = innerEditorState.gridColumns,
                    showGrid = true,
                    mode = OverlayRenderMode.EditorPreview,
                    selectedTileId = innerEditorState.selectedTileId,
                    isMoveMode = false,
                    defaultTextScale = parentEditorState.savedState.defaultTextScale,
                    defaultFontWeight = defaultFontWeight,
                    defaultFontFamily = null,
                    defaultTextColor = defaultTextColor,
                    hapticFeedbackEnabled = parentEditorState.savedState.hapticFeedbackEnabled,
                    preloadedFonts = emptyMap(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    onTileSelect = { id ->
                        innerEditorState.selectedTileId = if (innerEditorState.selectedTileId == id) null else id
                    },
                )
            }
        }

        // ── Add-tile action row (App / Intent only) ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                val cell = innerEditorState.findFirstOpenCell(1, 1)
                if (cell == null) {
                    Toast.makeText(context, "No space in scrollbox", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                val newId = innerEditorState.nextTileId++
                innerEditorState.addTile(
                    AppTileState(
                        id = newId,
                        row = cell.first,
                        column = cell.second,
                        app = LaunchableApp(label = "App", componentName = null),
                    ),
                )
                innerEditorState.selectedTileId = newId
                Toast.makeText(context, "Tap the tile then 'Change app' in the inspector", Toast.LENGTH_SHORT).show()
            }) { Text("+ App") }

            OutlinedButton(onClick = {
                val cell = innerEditorState.findFirstOpenCell(1, 1)
                if (cell == null) {
                    Toast.makeText(context, "No space in scrollbox", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                val newId = innerEditorState.nextTileId++
                innerEditorState.addTile(
                    IntentTileState(
                        id = newId,
                        row = cell.first,
                        column = cell.second,
                        intentAction = "android.intent.action.MAIN",
                    ),
                )
                innerEditorState.selectedTileId = newId
                Toast.makeText(context, "Edit the intent in the inspector below", Toast.LENGTH_SHORT).show()
            }) { Text("+ Intent") }
        }

        // ── Inspector for the selected child ────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            OverlayTileInspector(
                editorState = innerEditorState,
                onConfigureWidget = {},
                loadLaunchableApps = { loadInstalledLaunchableApps(context) },
                openFontPicker = openFontPicker,
                openIconPicker = openIconPicker,
                fontEvents = fontEvents,
                iconEvents = iconEvents,
            )
        }

        // ── Done ────────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Button(onClick = { commitAndExit() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

/** Small selectable chip built from an OutlinedButton, matching the editor's other selectors. */
@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Popup content for setting the scrollbox's inner grid dimensions. */
@Composable
private fun ScrollBoxGridSizeContent(innerEditorState: OverlayEditorState) {
    var rowsInput by remember(innerEditorState.gridRows) { mutableStateOf(innerEditorState.gridRows.toString()) }
    var colsInput by remember(innerEditorState.gridColumns) { mutableStateOf(innerEditorState.gridColumns.toString()) }

    Column(
        modifier = Modifier
            .width(260.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Inner Grid Size", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "The grid scrolls when it exceeds the scrollbox's footprint.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = rowsInput,
            onValueChange = { rowsInput = it.filter(Char::isDigit).take(2) },
            label = { Text("Rows (1–20)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = colsInput,
            onValueChange = { colsInput = it.filter(Char::isDigit).take(2) },
            label = { Text("Columns (1–12)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                val rows = rowsInput.toIntOrNull()?.coerceIn(1, 20) ?: innerEditorState.gridRows
                val cols = colsInput.toIntOrNull()?.coerceIn(1, 12) ?: innerEditorState.gridColumns
                rowsInput = rows.toString()
                colsInput = cols.toString()
                innerEditorState.applyGridSize(rows, cols)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply Grid Size") }
    }
}
