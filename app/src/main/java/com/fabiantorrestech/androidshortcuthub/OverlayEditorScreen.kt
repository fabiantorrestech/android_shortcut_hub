package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * In-app layout editor screen.
 *
 * Portrait, top to bottom: top bar (back, title, appearance/grid popups, Save) → Portrait/Landscape
 * tabs → grid preview → scrollable add-tile row → tile inspector. Landscape puts the preview and
 * the controls side by side instead.
 *
 * Save is the single commit point for the whole editor and appears in the top bar only while
 * [OverlayEditorState.hasUnsavedChanges] is true for either orientation. Because that value is
 * derived from a comparison against the saved baseline rather than a flag edits set, undoing a
 * change by hand makes the button go away again. Leaving with changes pending prompts; per-tile
 * undo lives in the inspector next to the tile it affects.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // When set, a container editor or the app picker takes over the whole screen (swap, not
    // overlay), so the shared font/icon pick and widget-bind events route to exactly one consumer.
    // All three ids are declared before any early-return so their remembered state is always
    // present, and each swapped-in screen brings its own BackHandler, giving one level of unwind.
    var editingScrollBoxId by remember { mutableStateOf<Int?>(null) }
    var editingWidgetStackId by remember { mutableStateOf<Int?>(null) }
    var pickingAppForTileId by remember { mutableStateOf<Int?>(null) }
    var showElementList by remember { mutableStateOf(false) }

    if (showElementList) {
        TileElementList(editorState = editorState, onBack = { showElementList = false })
        return
    }
    val currentPickingAppForTileId = pickingAppForTileId
    if (currentPickingAppForTileId != null) {
        AppPickerScreen(
            onAppSelected = { app ->
                editorState.updateTile(currentPickingAppForTileId) { tile ->
                    (tile as? AppTileState)?.copy(app = app) ?: tile
                }
                pickingAppForTileId = null
            },
            onBack = { pickingAppForTileId = null },
        )
        return
    }
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

    /** The one place the editor persists anything. Both orientations are written together. */
    fun saveAll() {
        syncGlobalsFromActive()
        val portraitCommitted = portraitEditorState.commit()
        val landscapeCommitted = landscapeEditorState.commit()
        onSave(portraitCommitted, landscapeCommitted)
        portraitEditorState.markSaved(portraitCommitted)
        landscapeEditorState.markSaved(landscapeCommitted)
    }

    // Derived, so undoing an edit by hand takes the Save button away again and leaving is silent.
    val anyUnsaved = portraitEditorState.hasUnsavedChanges || landscapeEditorState.hasUnsavedChanges
    var showExitPrompt by remember { mutableStateOf(false) }

    /**
     * Leaving used to discard the draft without a word: MainActivity drops LayoutTab from
     * composition, taking both editor states with it. Ask first when there is something to lose.
     */
    fun attemptExit() {
        if (anyUnsaved) showExitPrompt = true else onBack()
    }

    // Declared after the two early returns above, so it is only composed when no container editor
    // is open. Compose dispatches BackHandlers LIFO by composition depth, which means the
    // sub-editor's own handler wins while it is on screen and this one takes over once it closes —
    // exactly one level of unwind per press.
    BackHandler { attemptExit() }

    /**
     * Kept current through [rememberUpdatedState] because the collector below is keyed on `Unit`
     * and so never restarts. Without this it would capture `editorState` and `syncGlobalsFromActive`
     * from the *first* composition, and picking a default font after switching to the Landscape tab
     * wrote the font into the portrait state instead. The widget-bind path already takes this
     * precaution; this one had been missed.
     */
    val applyDefaultFont = rememberUpdatedState<(String, String) -> Unit> { uri, name ->
        editorState.defaultFontUri = uri
        editorState.defaultFontName = name
        syncGlobalsFromActive()
    }

    LaunchedEffect(Unit) {
        OverlayEditorState.defaultFontEvents().collectLatest { (uri, name) ->
            applyDefaultFont.value(uri, name)
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

    if (showExitPrompt) {
        AlertDialog(
            onDismissRequest = { showExitPrompt = false },
            title = { Text("Save changes?") },
            text = { Text("You have unsaved changes to this layout.") },
            confirmButton = {
                Button(onClick = {
                    showExitPrompt = false
                    saveAll()
                    onBack()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitPrompt = false
                    // reset() restores each state to its baseline. Both orientations are reverted
                    // because Save writes both, so a discard has to undo both too.
                    portraitEditorState.reset()
                    landscapeEditorState.reset()
                    onBack()
                }) { Text("Discard") }
            },
        )
    }

    // ── Shared wiring ────────────────────────────────────────────────────────
    // Both arrangements drive the same callbacks, so portrait and landscape can differ in layout
    // without being able to differ in behaviour.

    val onSelectTab: (OverlayOrientation) -> Unit = { target ->
        syncGlobalsFromActive()
        activeTab = target
    }

    fun addWidget() {
        val hasVolumeSlider = editorState.tiles.any {
            it is SystemSliderTileState && it.config.sliderType == SliderType.VOLUME
        }
        val hasBrightnessSlider = editorState.tiles.any {
            it is SystemSliderTileState && it.config.sliderType == SliderType.BRIGHTNESS
        }
        android.util.Log.d(WIDGET_BIND_TAG, "editor: '+ Widget' tapped")
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
    }

    val topBar: @Composable () -> Unit = {
        EditorTopBar(
            editorState = editorState,
            anyUnsaved = anyUnsaved,
            onBack = { attemptExit() },
            onSave = { saveAll() },
            onPopupDismissed = { syncGlobalsFromActive() },
            openDefaultFontPicker = openDefaultFontPicker,
            onShowElements = { showElementList = true },
        )
    }

    val inspector: @Composable (Modifier) -> Unit = { inspectorModifier ->
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
            onPickApp = { pickingAppForTileId = it },
            openFontPicker = openFontPicker,
            openIconPicker = openIconPicker,
            fontEvents = fontEvents,
            iconEvents = iconEvents,
            onEditScrollBox = { editingScrollBoxId = it },
            onEditWidgetStack = { editingWidgetStackId = it },
            modifier = inspectorModifier,
        )
    }

    if (isLandscapeEditor) {
        // ── Landscape: preview left | controls right ─────────────────────────
        // Width is the plentiful axis here, so a plain split already gives the preview half the
        // screen and the inspector a full-height column. No sheet needed.
        Column(modifier = Modifier.fillMaxSize()) {
            topBar()
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EditorPreviewPane(
                    editorState = editorState,
                    deviceAspectRatio = deviceAspectRatio,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    EditorOrientationTabs(activeTab, editorState, onSelectTab)
                    AddTileRow(editorState = editorState, onAddWidget = { addWidget() })
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        inspector(Modifier)
                    }
                }
            }
        }
    } else {
        // ── Portrait: preview owns the screen, controls live in a sheet ──────
        // Portrait used to stack five regions in one Column - top bar, tabs, preview at
        // weight(1.4f), add-tile row, inspector at weight(1f) - so roughly 200dp of fixed chrome
        // was taken off the top and the inspector then held ~40% of what was left whether or not
        // anything was selected. The preview, the thing the user is actually arranging, got a
        // little over half of the remainder.
        //
        // The inspector is now a sheet that overlays the preview instead of shrinking it. At rest
        // it peeks just far enough for the selected tile's summary and the add-tile row; dragging
        // it up reveals the full inspector over the preview, and it never takes space it is not
        // using.
        val sheetScaffoldState = rememberBottomSheetScaffoldState(
            bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded),
        )
        val sheetScope = rememberCoroutineScope()
        val screenHeight = configuration.screenHeightDp.dp

        BottomSheetScaffold(
            scaffoldState = sheetScaffoldState,
            topBar = topBar,
            sheetPeekHeight = EDITOR_SHEET_PEEK_HEIGHT,
            sheetContent = {
                SelectedTileSummary(
                    editorState = editorState,
                    onClick = { sheetScope.launch { sheetScaffoldState.bottomSheetState.expand() } },
                )
                AddTileRow(editorState = editorState, onAddWidget = { addWidget() })
                HorizontalDivider()
                // Bounded so the sheet cannot grow past the screen: the inspector scrolls
                // internally, and an unbounded scroll container inside a sheet has no height to
                // measure against.
                inspector(Modifier.heightIn(max = screenHeight * 0.55f))
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
                EditorOrientationTabs(activeTab, editorState, onSelectTab)
                EditorPreviewPane(
                    editorState = editorState,
                    deviceAspectRatio = deviceAspectRatio,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/** Tall enough for the summary row and the add-tile row, and nothing more. */
private val EDITOR_SHEET_PEEK_HEIGHT = 132.dp

/**
 * The one line of the sheet that is always visible: what is selected and how big it is. Tapping it
 * expands the sheet, so the selection made in the preview has an obvious way through to its
 * controls without hunting for the drag handle.
 */
@Composable
private fun SelectedTileSummary(
    editorState: OverlayEditorState,
    onClick: () -> Unit,
) {
    val selectedId = editorState.selectedTileId
    val tile = selectedId?.let { id -> editorState.tiles.firstOrNull { it.id == id } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile == null) {
            Text(
                text = "Tap a tile to select it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = tile.displayLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${tile.columnSpan}×${tile.rowSpan}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Request code used when launching the widget configure activity from the editor. */
internal const val CONFIGURE_WIDGET_REQUEST_CODE = 9001
