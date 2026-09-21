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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pieces of the layout editor's chrome that the portrait and landscape arrangements both need.
 *
 * These lived twice inside OverlayEditorScreen - the add-tile row alone was ~140 duplicated lines -
 * so every fix had to be made in two places, and the two copies had already drifted. Hoisting them
 * here leaves the two branches differing only in how they arrange the same parts.
 */

/**
 * The inset the runtime overlay applies around its grid on a full-size screen
 * (`OverlayUI.kt`, `padding(start = 16.dp, end = 16.dp, bottom = 16.dp)`). The editor preview
 * scales this rather than copying it, so the miniature keeps the real layout's proportions.
 */
private val RUNTIME_OVERLAY_INSET = 16.dp

/** Back, title, the appearance and grid popups, and the editor's single Save. */
@Composable
internal fun EditorTopBar(
    editorState: OverlayEditorState,
    anyUnsaved: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onPopupDismissed: () -> Unit,
    openDefaultFontPicker: () -> Unit,
    onShowElements: () -> Unit,
) {
    var gridPopupOpen by remember { mutableStateOf(false) }
    var appearancePopupOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = "Layout",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        // Reaches tiles the preview makes hard to hit - anything 1x1 on a large grid, and the
        // types that draw little of themselves.
        IconButton(onClick = onShowElements) {
            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Elements")
        }
        Box {
            IconButton(onClick = { appearancePopupOpen = true }) {
                Icon(Icons.Default.FormatSize, contentDescription = "Default App Text Settings")
            }
            DropdownMenu(
                expanded = appearancePopupOpen,
                onDismissRequest = { appearancePopupOpen = false; onPopupDismissed() },
            ) {
                AppearancePopupContent(editorState, openDefaultFontPicker)
            }
        }
        Box {
            IconButton(onClick = { gridPopupOpen = true }) {
                Icon(Icons.Default.GridView, contentDescription = "Grid Settings")
            }
            DropdownMenu(
                expanded = gridPopupOpen,
                onDismissRequest = { gridPopupOpen = false; onPopupDismissed() },
            ) {
                GridPopupContent(editorState)
            }
        }
        // The editor's only Save. Present only while something differs from the last save, so its
        // appearance is itself the "you have changes" signal the old text label carried.
        if (anyUnsaved) {
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

/** Which of the two saved layouts is being edited. */
@Composable
internal fun EditorOrientationTabs(
    activeTab: OverlayOrientation,
    editorState: OverlayEditorState,
    onSelect: (OverlayOrientation) -> Unit,
) {
    TabRow(selectedTabIndex = if (activeTab == OverlayOrientation.PORTRAIT) 0 else 1) {
        Tab(
            selected = activeTab == OverlayOrientation.PORTRAIT,
            onClick = { onSelect(OverlayOrientation.PORTRAIT) },
            text = { Text("Portrait") },
        )
        Tab(
            selected = activeTab == OverlayOrientation.LANDSCAPE,
            onClick = { onSelect(OverlayOrientation.LANDSCAPE) },
            text = { Text("Landscape") },
        )
    }

    if (activeTab == OverlayOrientation.LANDSCAPE && editorState.tiles.isEmpty()) {
        Text(
            text = "Landscape layout is empty - add tiles to configure it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * The scrollable "+ App / + Widget / ..." bar.
 *
 * Widget binding needs an ActivityResultLauncher, which only the host can own, so that one button
 * is a callback while the rest place their tile directly.
 */
@Composable
internal fun AddTileRow(
    editorState: OverlayEditorState,
    onAddWidget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = {
            val cell = editorState.findFirstOpenCell(1, 1)
            if (cell == null) {
                Toast.makeText(context, "No space on grid", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val newId = editorState.nextTileId++
            editorState.addTile(
                AppTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    app = LaunchableApp(label = "App", componentName = null),
                ),
            )
            editorState.selectedTileId = newId
            Toast.makeText(context, "Tap the tile then 'Change app' in the inspector", Toast.LENGTH_SHORT).show()
        }) { Text("+ App") }

        OutlinedButton(onClick = onAddWidget) { Text("+ Widget") }

        OutlinedButton(onClick = {
            val cell = editorState.findFirstOpenCell(1, 1)
            if (cell == null) {
                Toast.makeText(context, "No space on grid", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val newId = editorState.nextTileId++
            editorState.addTile(
                IntentTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    intentAction = "android.intent.action.MAIN",
                ),
            )
            editorState.selectedTileId = newId
            Toast.makeText(context, "Edit the intent in the inspector below", Toast.LENGTH_SHORT).show()
        }) { Text("+ Intent") }

        OutlinedButton(onClick = {
            val fit = listOf(3, 2, 1).firstNotNullOfOrNull { s ->
                editorState.findFirstOpenCell(s, s)?.let { it to s }
            }
            if (fit == null) {
                Toast.makeText(context, "No space on grid for a scrollbox", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val (cell, span) = fit
            val newId = editorState.nextTileId++
            editorState.addTile(
                ScrollBoxTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = span,
                    columnSpan = span,
                ),
            )
            editorState.selectedTileId = newId
            Toast.makeText(context, "Tap the scrollbox, then 'Edit contents' in the inspector", Toast.LENGTH_SHORT).show()
        }) { Text("+ Scrollbox") }

        OutlinedButton(onClick = {
            val fit = listOf(2, 1).firstNotNullOfOrNull { s ->
                editorState.findFirstOpenCell(s, s)?.let { it to s }
            }
            if (fit == null) {
                Toast.makeText(context, "No space on grid for a widget stack", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val (cell, span) = fit
            val newId = editorState.nextTileId++
            editorState.addTile(
                WidgetStackTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = span,
                    columnSpan = span,
                ),
            )
            editorState.selectedTileId = newId
            // Do NOT auto-open the widget editor — leave the stack placed + selected so
            // the user can size/move it on the grid first, then tap "Edit stack ›" to
            // add widgets (consistent with Scrollbox).
        }) { Text("+ Widget Stack") }

        OutlinedButton(onClick = {
            val count = editorState.tiles.count {
                it is SystemSliderTileState && it.config.sliderType == SliderType.VOLUME
            }
            if (count >= 2) {
                Toast.makeText(context, "Maximum 2 volume sliders allowed", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val cell = editorState.findFirstOpenCell(3, 1)
            if (cell == null) {
                Toast.makeText(context, "No space on grid for volume slider", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val newId = editorState.nextTileId++
            editorState.addTile(
                SystemSliderTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = 3,
                    columnSpan = 1,
                    config = SystemSliderConfig(sliderType = SliderType.VOLUME),
                ),
            )
            editorState.selectedTileId = newId
        }) { Text("+ Vol Slider") }

        OutlinedButton(onClick = {
            val count = editorState.tiles.count {
                it is SystemSliderTileState && it.config.sliderType == SliderType.BRIGHTNESS
            }
            if (count >= 2) {
                Toast.makeText(context, "Maximum 2 brightness sliders allowed", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val cell = editorState.findFirstOpenCell(3, 1)
            if (cell == null) {
                Toast.makeText(context, "No space on grid for brightness slider", Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            val newId = editorState.nextTileId++
            editorState.addTile(
                SystemSliderTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = 3,
                    columnSpan = 1,
                    config = SystemSliderConfig(sliderType = SliderType.BRIGHTNESS),
                ),
            )
            editorState.selectedTileId = newId
        }) { Text("+ Bright Slider") }
    }
}

/**
 * The grid preview, letterboxed to the aspect ratio of the screen the edited layout will run on.
 *
 * Reads appearance from the *live* draft rather than [OverlayEditorState.savedState]. It used to
 * read the baseline for text scale, bold and colour while reading the live background opacity, so
 * dragging the opacity slider updated the preview instantly but changing the text size, weight or
 * colour appeared to do nothing at all until Save.
 */
@Composable
internal fun EditorPreviewPane(
    editorState: OverlayEditorState,
    deviceAspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    val defaultFontWeight = if (editorState.defaultBoldText) FontWeight.Bold else FontWeight.Normal
    val defaultTextColor = resolveDefaultTileTextColor(
        mode = editorState.defaultTextColorMode,
        hex = editorState.defaultTextColorHex,
        fallback = MaterialTheme.colorScheme.onSurface,
    )

    // Width of the real screen this preview stands in for, so the runtime's inset can be scaled down
    // in proportion. deviceAspectRatio < 1 means the Portrait tab, whose screen is the short side.
    val configuration = LocalConfiguration.current
    val representedWidthDp =
        if (deviceAspectRatio < 1f) minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        else maxOf(configuration.screenWidthDp, configuration.screenHeightDp)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val wide = maxWidth / maxHeight > deviceAspectRatio
        val previewWidth = if (wide) maxHeight * deviceAspectRatio else maxWidth
        val previewHeight = if (wide) maxHeight else maxWidth / deviceAspectRatio

        // The runtime overlay pads the grid by RUNTIME_OVERLAY_INSET on a full-size screen
        // (OverlayUI.kt). This used to be copied into the preview as the same absolute 16dp, which
        // is not a miniature of it: at a 250dp-wide preview it was already ~1.6x too large, and
        // once the preview shrinks to make room for the inspector sheet it ate a growing share of
        // the box. Scaling it keeps the preview a faithful copy of the real layout at any size.
        val inset = RUNTIME_OVERLAY_INSET * (previewWidth.value / representedWidthDp.coerceAtLeast(1))

        Box(
            modifier = Modifier
                .size(previewWidth, previewHeight)
                .background(Color.Black.copy(alpha = editorState.overlayBackgroundAlpha)),
        ) {
            OverlayGridPreview(
                tiles = editorState.tiles.toList(),
                gridRows = editorState.gridRows,
                gridColumns = editorState.gridColumns,
                showGrid = true,
                mode = OverlayRenderMode.EditorPreview,
                selectedTileId = editorState.selectedTileId,
                isMoveMode = false,
                defaultTextScale = editorState.defaultTextScale,
                defaultFontWeight = defaultFontWeight,
                defaultFontFamily = null,
                defaultTextColor = defaultTextColor,
                hapticFeedbackEnabled = editorState.savedState.hapticFeedbackEnabled,
                preloadedFonts = emptyMap(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = inset, end = inset, bottom = inset),
                onTileSelect = { id ->
                    editorState.selectedTileId = if (editorState.selectedTileId == id) null else id
                },
            )
        }
    }
}

@Composable
private fun GridPopupContent(editorState: OverlayEditorState) {
    var rowsInput by remember(editorState.gridRows) { mutableStateOf(editorState.gridRows.toString()) }
    var colsInput by remember(editorState.gridColumns) { mutableStateOf(editorState.gridColumns.toString()) }

    Column(
        modifier = Modifier
            .width(260.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Grid Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = rowsInput,
            onValueChange = { rowsInput = it.filter(Char::isDigit).take(2) },
            label = { Text("Rows (1–24)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = colsInput,
            onValueChange = { colsInput = it.filter(Char::isDigit).take(2) },
            label = { Text("Columns (1–16)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Shrinking the grid deletes every tile that no longer fits. Say so before it happens
        // rather than after, since the only undo is leaving the editor without saving.
        val pendingRows = rowsInput.toIntOrNull()?.coerceIn(1, 24) ?: editorState.gridRows
        val pendingCols = colsInput.toIntOrNull()?.coerceIn(1, 16) ?: editorState.gridColumns
        val tilesLost = editorState.tilesLostByGridSize(pendingRows, pendingCols)
        if (tilesLost > 0) {
            Text(
                text = "Removes $tilesLost ${if (tilesLost == 1) "tile that no longer fits" else "tiles that no longer fit"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = {
                rowsInput = pendingRows.toString()
                colsInput = pendingCols.toString()
                editorState.applyGridSize(pendingRows, pendingCols)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (tilesLost > 0) "Apply and remove $tilesLost" else "Apply Grid Size") }

        Text(
            "Background Opacity  ${"%.0f".format(editorState.overlayBackgroundAlpha * 100)}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = editorState.overlayBackgroundAlpha,
            onValueChange = { editorState.overlayBackgroundAlpha = it },
            valueRange = 0f..0.9f,
        )
    }
}

@Composable
private fun AppearancePopupContent(editorState: OverlayEditorState, openDefaultFontPicker: () -> Unit) {
    val normalizedColorHex = remember(editorState.defaultTextColorHex) {
        normalizeHexColor(editorState.defaultTextColorHex)
    }
    val customHexValid = editorState.defaultTextColorHex.isNullOrBlank() || normalizedColorHex != null
    val previewTextColor = when (editorState.defaultTextColorMode) {
        DefaultTextColorMode.SYSTEM -> Color.Unspecified
        DefaultTextColorMode.BLACK -> Color.Black
        DefaultTextColorMode.WHITE -> Color.White
        DefaultTextColorMode.CUSTOM -> normalizedColorHex?.let { Color(android.graphics.Color.parseColor(it)) } ?: Color.Unspecified
    }

    Column(
        modifier = Modifier
            .width(280.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Default App Text Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        Text("Text size: ${"%.2f".format(editorState.defaultTextScale)}x", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = editorState.defaultTextScale,
            onValueChange = { editorState.defaultTextScale = it },
            valueRange = 0.5f..3.0f,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bold text", style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = editorState.defaultBoldText,
                onCheckedChange = { editorState.defaultBoldText = it },
            )
        }

        Text(
            "Font: ${editorState.defaultFontName ?: "System default"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openDefaultFontPicker, modifier = Modifier.weight(1f)) { Text("Choose") }
            OutlinedButton(
                onClick = {
                    editorState.defaultFontUri = null
                    editorState.defaultFontName = null
                },
                modifier = Modifier.weight(1f),
            ) { Text("Clear") }
        }

        Text("Text Color", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        val colorModeOptions = listOf(
            "System" to DefaultTextColorMode.SYSTEM,
            "Black" to DefaultTextColorMode.BLACK,
            "White" to DefaultTextColorMode.WHITE,
            "Custom" to DefaultTextColorMode.CUSTOM,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            colorModeOptions.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowItems.forEach { (label, mode) ->
                        val selected = editorState.defaultTextColorMode == mode
                        OutlinedButton(
                            onClick = { editorState.defaultTextColorMode = mode },
                            modifier = Modifier.weight(1f),
                            colors = if (selected) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ) else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        if (editorState.defaultTextColorMode == DefaultTextColorMode.CUSTOM) {
            OutlinedTextField(
                value = editorState.defaultTextColorHex ?: "",
                onValueChange = {
                    editorState.defaultTextColorHex = it.trim().take(9).ifBlank { null }
                },
                label = { Text("Hex color") },
                placeholder = { Text("#FFFFFF") },
                singleLine = true,
                isError = !customHexValid,
                supportingText = {
                    Text(if (customHexValid) normalizedColorHex ?: "#RRGGBB / #AARRGGBB" else "Invalid hex")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (editorState.defaultTextColorMode != DefaultTextColorMode.SYSTEM) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(modifier = Modifier.size(24.dp), shape = MaterialTheme.shapes.small, color = previewTextColor) {}
                Text("Preview", color = previewTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
