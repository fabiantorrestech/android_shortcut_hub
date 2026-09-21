package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * In-app layout editor screen.
 *
 * Layout (top to bottom):
 * 1. Scrollable action bar — "+ App", "+ Widget", "+ Intent", "+ Volume Slider", "+ Brightness Slider"
 * 2. Tile inspector (scrollable, weight 1f) — shown when a tile is selected
 * 3. Grid preview (dark bg, weight 1.2f) — EditorPreview mode
 * 4. Save bar (per-tile Cancel appears when the selected tile has unsaved edits)
 */
@Composable
internal fun OverlayEditorScreen(
    portraitEditorState: OverlayEditorState,
    landscapeEditorState: OverlayEditorState,
    onBack: () -> Unit,
    onSave: (portrait: OverlayUiState, landscape: OverlayUiState) -> Unit,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
    openDefaultFontPicker: () -> Unit,
    fontEvents: Flow<TileFontSelection>,
    iconEvents: Flow<TileIconSelection>,
) {
    var activeTab by remember { mutableStateOf(OverlayOrientation.PORTRAIT) }
    val editorState = if (activeTab == OverlayOrientation.PORTRAIT) portraitEditorState else landscapeEditorState

    // When set, a container editor takes over the whole screen (swap, not overlay), so the shared
    // font/icon pick and widget-bind events route to exactly one consumer. Both ids are declared
    // before either early-return so their remembered state is always present.
    var editingScrollBoxId by remember { mutableStateOf<Int?>(null) }
    var editingWidgetStackId by remember { mutableStateOf<Int?>(null) }
    val currentEditingScrollBoxId = editingScrollBoxId
    if (currentEditingScrollBoxId != null) {
        ScrollBoxEditorScreen(
            parentEditorState = editorState,
            scrollBoxId = currentEditingScrollBoxId,
            onBack = { editingScrollBoxId = null },
            openFontPicker = openFontPicker,
            openIconPicker = openIconPicker,
            fontEvents = fontEvents,
            iconEvents = iconEvents,
        )
        return
    }
    val currentEditingWidgetStackId = editingWidgetStackId
    if (currentEditingWidgetStackId != null) {
        WidgetStackEditorScreen(
            parentEditorState = editorState,
            widgetStackId = currentEditingWidgetStackId,
            onBack = { editingWidgetStackId = null },
        )
        return
    }

    // Declared after the two early returns above, so it is only composed when no container editor
    // is open. Compose dispatches BackHandlers LIFO by composition depth, which means the
    // sub-editor's own handler wins while it is on screen and this one takes over once it closes —
    // exactly one level of unwind per press.
    BackHandler { onBack() }

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

    // Stable references that always point to the latest editor states, used by the bind-result callback
    val latestActiveState = rememberUpdatedState(editorState)
    val latestOtherState = rememberUpdatedState(
        if (activeTab == OverlayOrientation.PORTRAIT) landscapeEditorState else portraitEditorState,
    )

    // Screen dimensions the ACTIVE layout will actually be drawn at. The editor can be held in
    // either physical orientation while editing either tab, so normalise rather than trusting the
    // live configuration: portrait = short side wide, landscape = long side wide.
    val configuration = LocalConfiguration.current
    val shortSideDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val longSideDp = maxOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val latestTargetScreen = rememberUpdatedState(
        if (activeTab == OverlayOrientation.PORTRAIT) shortSideDp to longSideDp else longSideDp to shortSideDp,
    )

    /**
     * Footprint a freshly bound widget should get: derived from the provider's declared minimum
     * size against the active grid, exactly like the runtime overlay does. Falls back to 2×2 when
     * the provider can't be resolved.
     */
    fun nativeSpanFor(appWidgetId: Int, state: OverlayEditorState): Pair<Int, Int> {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId) ?: return 2 to 2
        val (screenWidthDp, screenHeightDp) = latestTargetScreen.value
        return resolveWidgetSpanForGrid(
            minWidthDp = info.minWidth,
            minHeightDp = info.minHeight,
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            gridRows = state.gridRows,
            gridColumns = state.gridColumns,
        )
    }

    // Deterministic result delivery for widget/slider binds — fires exactly when BindWidgetActivity
    // finishes (no matter how many config screens it launched internally) and only for the launch we
    // started. The typed insertion is read from the coordinator.
    fun consumeWidgetBindResult() {
        val insertion = WidgetBindingCoordinator.consumeCompletedInsertion()
        if (insertion == null) {
            android.util.Log.d(WIDGET_BIND_TAG, "editor.consumeWidgetBindResult -> nothing pending, no tile added")
            return
        }
        val active = latestActiveState.value
        val other = latestOtherState.value
        android.util.Log.d(
            WIDGET_BIND_TAG,
            "editor.consumeWidgetBindResult insertion=$insertion activeTiles=${active.tiles.size} " +
                "grid=${active.gridRows}x${active.gridColumns}",
        )
        when (insertion) {
            is TileInsertionEvent.WidgetAdded -> {
                val (nativeRows, nativeCols) = nativeSpanFor(insertion.selection.appWidgetId, active)
                val placement = active.findWidgetPlacement(nativeRows, nativeCols)
                android.util.Log.d(
                    WIDGET_BIND_TAG,
                    "editor.findWidgetPlacement nativeSpan=${nativeRows}x$nativeCols -> $placement",
                )
                if (placement == null) {
                    Toast.makeText(context, "No space on grid for this widget", Toast.LENGTH_SHORT).show()
                    ShortcutHubWidgetHost.getInstance(context).deleteAppWidgetId(insertion.selection.appWidgetId)
                    return
                }
                // Check if the other orientation already has this provider
                val matchingOtherWidget = other.tiles
                    .filterIsInstance<WidgetTileState>()
                    .firstOrNull { it.providerComponent == insertion.selection.providerComponent }
                if (matchingOtherWidget != null) {
                    // Prompt the user to choose shared vs independent
                    android.util.Log.d(
                        WIDGET_BIND_TAG,
                        "editor: same provider exists in other orientation (id=${matchingOtherWidget.appWidgetId}) " +
                            "-> showing shared/independent dialog instead of placing",
                    )
                    pendingWidgetInsertion = insertion
                    existingOtherAppWidgetId = matchingOtherWidget.appWidgetId
                } else {
                    // No matching widget in the other layout — place independently
                    val (cell, rowSpan, colSpan) = placement
                    val newId = active.nextTileId++
                    active.addTile(
                        WidgetTileState(
                            id = newId,
                            row = cell.first,
                            column = cell.second,
                            rowSpan = rowSpan,
                            columnSpan = colSpan,
                            appWidgetId = insertion.selection.appWidgetId,
                            providerComponent = insertion.selection.providerComponent,
                        ),
                    )
                    active.selectedTileId = newId
                    android.util.Log.d(
                        WIDGET_BIND_TAG,
                        "editor: placed widget tile id=$newId at (${cell.first},${cell.second}) span=${rowSpan}x$colSpan",
                    )
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
                    return
                }
                val cell = active.findFirstOpenCell(insertion.rowSpan, insertion.columnSpan)
                if (cell == null) {
                    Toast.makeText(context, "No space on grid for this slider", Toast.LENGTH_SHORT).show()
                    return
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

    val widgetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        android.util.Log.d(WIDGET_BIND_TAG, "editor.widgetLauncher callback fired, resultCode=${result.resultCode}")
        consumeWidgetBindResult()
    }

    // Widget shared/independent dialog
    val pendingWidget = pendingWidgetInsertion
    if (pendingWidget != null) {
        val otherWidgetId = existingOtherAppWidgetId

        fun placeWidget(appWidgetId: Int) {
            val (nativeRows, nativeCols) = nativeSpanFor(appWidgetId, editorState)
            val (cell, rowSpan, colSpan) = editorState.findWidgetPlacement(nativeRows, nativeCols) ?: return
            val newId = editorState.nextTileId++
            editorState.addTile(
                WidgetTileState(
                    id = newId,
                    row = cell.first,
                    column = cell.second,
                    rowSpan = rowSpan,
                    columnSpan = colSpan,
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

    // `configuration` is declared above, alongside the widget-span helpers.
    val shortSide = shortSideDp.toFloat()
    val longSide = longSideDp.toFloat()
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
    val isLandscapeEditor = configuration.screenWidthDp > configuration.screenHeightDp

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
            val anyUnsaved = portraitEditorState.hasUnsavedChanges || landscapeEditorState.hasUnsavedChanges
            Text(
                text = if (anyUnsaved) "Unsaved changes" else "All changes saved",
                style = MaterialTheme.typography.labelSmall,
                color = if (anyUnsaved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
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

        if (isLandscapeEditor) {
            // ── Landscape: preview left | controls right ─────────────────────
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
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

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
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

                    Row(
                        modifier = Modifier
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

                        OutlinedButton(onClick = {
                            val hasVolumeSlider = editorState.tiles.any { it is SystemSliderTileState && it.config.sliderType == SliderType.VOLUME }
                            val hasBrightnessSlider = editorState.tiles.any { it is SystemSliderTileState && it.config.sliderType == SliderType.BRIGHTNESS }
                            android.util.Log.d(WIDGET_BIND_TAG, "editor: '+ Widget' tapped (portrait/landscape bar)")
                            WidgetBindingCoordinator.startBinding()
                            widgetLauncher.launch(
                                BindWidgetActivity.createIntent(
                                    context,
                                    editorState.gridRows,
                                    editorState.gridColumns,
                                    hasVolumeSlider,
                                    hasBrightnessSlider,
                                    autoToggleOverlay = false,
                                ),
                            )
                        }) { Text("+ Widget") }

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
                            loadLaunchableApps = { loadInstalledLaunchableApps(context) },
                            openFontPicker = openFontPicker,
                            openIconPicker = openIconPicker,
                            fontEvents = fontEvents,
                            iconEvents = iconEvents,
                            onEditScrollBox = { editingScrollBoxId = it },
                            onEditWidgetStack = { editingWidgetStackId = it },
                        )
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val selectedId = editorState.selectedTileId
                            if (selectedId != null && editorState.isTileDirty(selectedId)) {
                                OutlinedButton(
                                    onClick = { editorState.revertTile(selectedId) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Cancel") }
                            }
                            Button(
                                onClick = {
                                    syncGlobalsFromActive()
                                    val portraitCommitted = portraitEditorState.commit()
                                    val landscapeCommitted = landscapeEditorState.commit()
                                    onSave(portraitCommitted, landscapeCommitted)
                                    portraitEditorState.markSaved(portraitCommitted)
                                    landscapeEditorState.markSaved(landscapeCommitted)
                                },
                                enabled = portraitEditorState.hasUnsavedChanges || landscapeEditorState.hasUnsavedChanges,
                                modifier = Modifier.weight(1f),
                            ) { Text("Save") }
                        }
                    }
                }
            }
        } else {
            // ── Portrait layout ──────────────────────────────────────────────
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
                    android.util.Log.d(WIDGET_BIND_TAG, "editor: '+ Widget' tapped (alt bar)")
                    WidgetBindingCoordinator.startBinding()
                    widgetLauncher.launch(
                        BindWidgetActivity.createIntent(
                            context,
                            editorState.gridRows,
                            editorState.gridColumns,
                            hasVolumeSlider,
                            hasBrightnessSlider,
                            autoToggleOverlay = false,
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

                // + Scrollbox
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

                // + Widget Stack
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
                    // Do NOT auto-open the widget editor — size/move on the grid first, then
                    // tap "Edit stack ›" to add widgets (consistent with Scrollbox).
                }) { Text("+ Widget Stack") }

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
                    loadLaunchableApps = { loadInstalledLaunchableApps(context) },
                    openFontPicker = openFontPicker,
                    openIconPicker = openIconPicker,
                    fontEvents = fontEvents,
                    iconEvents = iconEvents,
                    onEditScrollBox = { editingScrollBoxId = it },
                    onEditWidgetStack = { editingWidgetStackId = it },
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val selectedId = editorState.selectedTileId
                    if (selectedId != null && editorState.isTileDirty(selectedId)) {
                        OutlinedButton(
                            onClick = { editorState.revertTile(selectedId) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Cancel") }
                    }
                    Button(
                        onClick = {
                            syncGlobalsFromActive()
                            val portraitCommitted = portraitEditorState.commit()
                            val landscapeCommitted = landscapeEditorState.commit()
                            onSave(portraitCommitted, landscapeCommitted)
                            portraitEditorState.markSaved(portraitCommitted)
                            landscapeEditorState.markSaved(landscapeCommitted)
                        },
                        enabled = portraitEditorState.hasUnsavedChanges || landscapeEditorState.hasUnsavedChanges,
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
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
