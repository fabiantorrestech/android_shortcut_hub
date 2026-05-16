package com.fabiantorrestech.androidshortcuthub

import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * In-app layout editor screen.
 *
 * Layout (top to bottom):
 * 1. Scrollable action bar — "+ App", "+ Widget", "+ Intent", "+ Volume Slider", "+ Brightness Slider"
 * 2. Tile inspector (scrollable, weight 1f) — shown when a tile is selected
 * 3. Grid preview (dark bg, weight 1.2f) — EditorPreview mode
 * 4. Save / Discard bar
 */
@Composable
internal fun OverlayEditorScreen(
    portraitEditorState: OverlayEditorState,
    landscapeEditorState: OverlayEditorState,
    onBack: () -> Unit,
    onSave: (portrait: OverlayUiState, landscape: OverlayUiState) -> Unit,
    onDiscard: () -> Unit,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
    openDefaultFontPicker: () -> Unit,
    fontEvents: Flow<TileFontSelection>,
    iconEvents: Flow<TileIconSelection>,
) {
    var activeTab by remember { mutableStateOf(OverlayOrientation.PORTRAIT) }
    val editorState = if (activeTab == OverlayOrientation.PORTRAIT) portraitEditorState else landscapeEditorState

    fun syncGlobalsFromActive() {
        val from = editorState
        val to = if (activeTab == OverlayOrientation.PORTRAIT) landscapeEditorState else portraitEditorState
        to.overlayBackgroundAlpha = from.overlayBackgroundAlpha
        to.defaultTextScale = from.defaultTextScale
        to.defaultBoldText = from.defaultBoldText
        to.defaultFontUri = from.defaultFontUri
        to.defaultFontName = from.defaultFontName
        to.defaultTextColorMode = from.defaultTextColorMode
        to.defaultTextColorHex = from.defaultTextColorHex
    }

    LaunchedEffect(Unit) {
        OverlayEditorState.defaultFontEvents().collectLatest { (uri, name) ->
            editorState.defaultFontUri = uri
            editorState.defaultFontName = name
            editorState.hasUnsavedChanges = true
            syncGlobalsFromActive()
        }
    }
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // Widget sharing dialog state
    var pendingWidgetInsertion by remember { mutableStateOf<TileInsertionEvent.WidgetAdded?>(null) }
    // existingOtherAppWidgetId is non-null when the other orientation already has the same provider
    var existingOtherAppWidgetId by remember { mutableStateOf<Int?>(null) }
    var showWidgetShareInfo by remember { mutableStateOf(false) }

    // Stable references that always point to the latest editor states, safe to use inside DisposableEffect
    val latestActiveState = rememberUpdatedState(editorState)
    val latestOtherState = rememberUpdatedState(
        if (activeTab == OverlayOrientation.PORTRAIT) landscapeEditorState else portraitEditorState,
    )

    // Listen for ON_RESUME to consume any pending widget insertion
    DisposableEffect(activity) {
        val lifecycle = activity?.lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val insertion = WidgetBindingCoordinator.consumeCompletedInsertion() ?: return@LifecycleEventObserver
                val active = latestActiveState.value
                val other = latestOtherState.value
                when (insertion) {
                    is TileInsertionEvent.WidgetAdded -> {
                        val cell = active.findFirstOpenCell(2, 2)
                        if (cell == null) {
                            Toast.makeText(context, "No space on grid for this widget", Toast.LENGTH_SHORT).show()
                            ShortcutHubWidgetHost.getInstance(context).deleteAppWidgetId(insertion.selection.appWidgetId)
                            return@LifecycleEventObserver
                        }
                        // Check if the other orientation already has this provider
                        val matchingOtherWidget = other.tiles
                            .filterIsInstance<WidgetTileState>()
                            .firstOrNull { it.providerComponent == insertion.selection.providerComponent }
                        if (matchingOtherWidget != null) {
                            // Prompt the user to choose shared vs independent
                            pendingWidgetInsertion = insertion
                            existingOtherAppWidgetId = matchingOtherWidget.appWidgetId
                        } else {
                            // No matching widget in the other layout — place independently
                            val newId = active.nextTileId++
                            active.addTile(
                                WidgetTileState(
                                    id = newId,
                                    row = cell.first,
                                    column = cell.second,
                                    rowSpan = 2,
                                    columnSpan = 2,
                                    appWidgetId = insertion.selection.appWidgetId,
                                    providerComponent = insertion.selection.providerComponent,
                                ),
                            )
                            active.selectedTileId = newId
                        }
                    }
                    is TileInsertionEvent.SystemSliderAdded -> {
                        val alreadyExists = active.tiles.any {
                            it is SystemSliderTileState && it.config.sliderType == insertion.config.sliderType
                        }
                        if (alreadyExists) {
                            Toast.makeText(
                                context,
                                "A ${insertion.config.sliderType.name.lowercase()} slider is already on the grid",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@LifecycleEventObserver
                        }
                        val cell = active.findFirstOpenCell(insertion.rowSpan, insertion.columnSpan)
                        if (cell == null) {
                            Toast.makeText(context, "No space on grid for this slider", Toast.LENGTH_SHORT).show()
                            return@LifecycleEventObserver
                        }
                        val newId = active.nextTileId++
                        active.addTile(
                            SystemSliderTileState(
                                id = newId,
                                row = cell.first,
                                column = cell.second,
                                rowSpan = insertion.rowSpan,
                                columnSpan = insertion.columnSpan,
                                config = insertion.config,
                            ),
                        )
                        active.selectedTileId = newId
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Widget shared/independent dialog
    val pendingWidget = pendingWidgetInsertion
    if (pendingWidget != null) {
        val otherWidgetId = existingOtherAppWidgetId

        fun placeWidget(appWidgetId: Int) {
            val cell = editorState.findFirstOpenCell(2, 2) ?: return
            val newId = editorState.nextTileId++
            editorState.addTile(
                WidgetTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = 2,
                    columnSpan = 2,
                    appWidgetId = appWidgetId,
                    providerComponent = pendingWidget.selection.providerComponent,
                ),
            )
            editorState.selectedTileId = newId
            pendingWidgetInsertion = null
            existingOtherAppWidgetId = null
            showWidgetShareInfo = false
        }

        AlertDialog(
            onDismissRequest = {
                // Default to independent on dismiss
                placeWidget(pendingWidget.selection.appWidgetId)
            },
            title = { Text("Widget placement") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This widget already exists in the other orientation layout. " +
                            "How should it be placed in this layout?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = { showWidgetShareInfo = !showWidgetShareInfo },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            if (showWidgetShareInfo) "ⓘ Hide details" else "ⓘ What's the difference?",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (showWidgetShareInfo) {
                        Text(
                            "Independent: a new widget instance is created. Each orientation has its own " +
                                "widget state and can be configured separately.\n\n" +
                                "Shared: both orientations point to the same widget instance. Changes to the " +
                                "widget's content or settings affect both orientations, and fewer system " +
                                "resources are used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { placeWidget(pendingWidget.selection.appWidgetId) }) {
                    Text("Independent (default)")
                }
            },
            dismissButton = {
                if (otherWidgetId != null) {
                    OutlinedButton(onClick = {
                        // Shared: release the newly allocated ID, reuse the other layout's ID
                        ShortcutHubWidgetHost.getInstance(context)
                            .deleteAppWidgetId(pendingWidget.selection.appWidgetId)
                        placeWidget(otherWidgetId)
                    }) {
                        Text("Shared")
                    }
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar: back arrow | unsaved label | popup buttons ─────────────
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
            if (editorState.hasUnsavedChanges) {
                Text(
                    text = "Unsaved changes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }
            // Appearance popup
            Box {
                IconButton(onClick = { appearancePopupOpen = true }) {
                    Icon(Icons.Default.FormatSize, contentDescription = "Default App Text Settings")
                }
                DropdownMenu(
                    expanded = appearancePopupOpen,
                    onDismissRequest = {
                        appearancePopupOpen = false
                        syncGlobalsFromActive()
                    },
                ) {
                    AppearancePopupContent(editorState, openDefaultFontPicker)
                }
            }
            // Grid / opacity popup
            Box {
                IconButton(onClick = { gridPopupOpen = true }) {
                    Icon(Icons.Default.GridView, contentDescription = "Grid Settings")
                }
                DropdownMenu(
                    expanded = gridPopupOpen,
                    onDismissRequest = {
                        gridPopupOpen = false
                        syncGlobalsFromActive()
                    },
                ) {
                    GridPopupContent(editorState)
                }
            }
        }

        // ── Portrait / Landscape tab toggle ─────────────────────────────────
        TabRow(selectedTabIndex = if (activeTab == OverlayOrientation.PORTRAIT) 0 else 1) {
            Tab(
                selected = activeTab == OverlayOrientation.PORTRAIT,
                onClick = {
                    syncGlobalsFromActive()
                    activeTab = OverlayOrientation.PORTRAIT
                },
                text = { Text("Portrait") },
            )
            Tab(
                selected = activeTab == OverlayOrientation.LANDSCAPE,
                onClick = {
                    syncGlobalsFromActive()
                    activeTab = OverlayOrientation.LANDSCAPE
                },
                text = { Text("Landscape") },
            )
        }

        if (activeTab == OverlayOrientation.LANDSCAPE && editorState.tiles.isEmpty()) {
            Text(
                text = "Landscape layout is empty — add tiles to configure it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ── Grid preview (device aspect ratio, centred) ──────────────────────
        val configuration = LocalConfiguration.current
        val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
        val longSide = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
        val deviceAspectRatio = if (activeTab == OverlayOrientation.LANDSCAPE)
            longSide / shortSide
        else
            shortSide / longSide
        val defaultFontWeight = if (editorState.savedState.defaultBoldText) FontWeight.Bold else FontWeight.Normal
        val defaultTextColor = resolveDefaultTileTextColor(
            mode = editorState.savedState.defaultTextColorMode,
            hex = editorState.savedState.defaultTextColorHex,
            fallback = MaterialTheme.colorScheme.onSurface,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.4f),
            contentAlignment = Alignment.Center,
        ) {
            val previewWidth = if (maxWidth / maxHeight > deviceAspectRatio) maxHeight * deviceAspectRatio else maxWidth
            val previewHeight = if (maxWidth / maxHeight > deviceAspectRatio) maxHeight else maxWidth / deviceAspectRatio

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
                    defaultTextScale = editorState.savedState.defaultTextScale,
                    defaultFontWeight = defaultFontWeight,
                    defaultFontFamily = null,
                    defaultTextColor = defaultTextColor,
                    hapticFeedbackEnabled = editorState.savedState.hapticFeedbackEnabled,
                    preloadedFonts = emptyMap(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    onTileSelect = { id -> editorState.selectedTileId = if (editorState.selectedTileId == id) null else id },
                )
            }
        }

        // ── Action bar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // + App
            OutlinedButton(onClick = {
                val cell = editorState.findFirstOpenCell(1, 1)
                if (cell == null) {
                    Toast.makeText(context, "No space on grid", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                // Placeholder app tile — user selects the app in the inspector
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

            // + Widget
            OutlinedButton(onClick = {
                val hasVolumeSlider = editorState.tiles.any { it is SystemSliderTileState && it.config.sliderType == SliderType.VOLUME }
                val hasBrightnessSlider = editorState.tiles.any { it is SystemSliderTileState && it.config.sliderType == SliderType.BRIGHTNESS }
                WidgetBindingCoordinator.startBinding()
                context.startActivity(
                    BindWidgetActivity.createIntent(
                        context,
                        editorState.gridRows,
                        editorState.gridColumns,
                        hasVolumeSlider,
                        hasBrightnessSlider,
                    ),
                )
            }) { Text("+ Widget") }

            // + Intent
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

            // + Volume Slider
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

            // + Brightness Slider
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

        // ── Inspector ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            OverlayTileInspector(
                editorState = editorState,
                onConfigureWidget = { appWidgetId ->
                    if (activity != null) {
                        ShortcutHubWidgetHost.getInstance(context)
                            .startAppWidgetConfigureActivityForResult(
                                activity,
                                appWidgetId,
                                0,
                                CONFIGURE_WIDGET_REQUEST_CODE,
                                null,
                            )
                    }
                },
                loadLaunchableApps = {
                    editorState.savedState.tiles.filterIsInstance<AppTileState>().map { it.app }
                },
                openFontPicker = openFontPicker,
                openIconPicker = openIconPicker,
                fontEvents = fontEvents,
                iconEvents = iconEvents,
            )
        }

        // ── Save / Discard bar ───────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        syncGlobalsFromActive()
                        onSave(portraitEditorState.commit(), landscapeEditorState.commit())
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        portraitEditorState.reset()
                        landscapeEditorState.reset()
                        onDiscard()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Discard") }
            }
        }
    }
}

/** Request code used when launching the widget configure activity from the editor. */
internal const val CONFIGURE_WIDGET_REQUEST_CODE = 9001

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
        OutlinedButton(
            onClick = {
                val rows = rowsInput.toIntOrNull()?.coerceIn(1, 24) ?: editorState.gridRows
                val cols = colsInput.toIntOrNull()?.coerceIn(1, 16) ?: editorState.gridColumns
                rowsInput = rows.toString()
                colsInput = cols.toString()
                editorState.applyGridSize(rows, cols)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Apply Grid Size") }

        Text(
            "Background Opacity  ${"%.0f".format(editorState.overlayBackgroundAlpha * 100)}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = editorState.overlayBackgroundAlpha,
            onValueChange = { editorState.overlayBackgroundAlpha = it; editorState.hasUnsavedChanges = true },
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
            onValueChange = { editorState.defaultTextScale = it; editorState.hasUnsavedChanges = true },
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
                onCheckedChange = { editorState.defaultBoldText = it; editorState.hasUnsavedChanges = true },
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
                    editorState.hasUnsavedChanges = true
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
                            onClick = { editorState.defaultTextColorMode = mode; editorState.hasUnsavedChanges = true },
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
                    editorState.hasUnsavedChanges = true
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
