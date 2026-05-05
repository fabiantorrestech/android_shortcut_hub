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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.Flow

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
    editorState: OverlayEditorState,
    onBack: () -> Unit,
    onSave: (OverlayUiState) -> Unit,
    onDiscard: () -> Unit,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
    fontEvents: Flow<TileFontSelection>,
    iconEvents: Flow<TileIconSelection>,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // Listen for ON_RESUME to consume any pending widget insertion
    DisposableEffect(activity) {
        val lifecycle = activity?.lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val insertion = WidgetBindingCoordinator.consumeCompletedInsertion() ?: return@LifecycleEventObserver
                when (insertion) {
                    is TileInsertionEvent.WidgetAdded -> {
                        val cell = editorState.findFirstOpenCell(2, 2)
                        if (cell == null) {
                            Toast.makeText(context, "No space on grid for this widget", Toast.LENGTH_SHORT).show()
                            ShortcutHubWidgetHost.getInstance(context).deleteAppWidgetId(insertion.selection.appWidgetId)
                            return@LifecycleEventObserver
                        }
                        val newId = editorState.nextTileId++
                        editorState.addTile(
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
                        editorState.selectedTileId = newId
                    }
                    is TileInsertionEvent.SystemSliderAdded -> {
                        val alreadyExists = editorState.tiles.any {
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
                        val cell = editorState.findFirstOpenCell(insertion.rowSpan, insertion.columnSpan)
                        if (cell == null) {
                            Toast.makeText(context, "No space on grid for this slider", Toast.LENGTH_SHORT).show()
                            return@LifecycleEventObserver
                        }
                        val newId = editorState.nextTileId++
                        editorState.addTile(
                            SystemSliderTileState(
                                id = newId,
                                row = cell.first,
                                column = cell.second,
                                rowSpan = insertion.rowSpan,
                                columnSpan = insertion.columnSpan,
                                config = insertion.config,
                            ),
                        )
                        editorState.selectedTileId = newId
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Back / unsaved indicator ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            if (editorState.hasUnsavedChanges) {
                Text(
                    text = "Unsaved changes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // ── Grid preview (device aspect ratio, centred) ──────────────────────
        val configuration = LocalConfiguration.current
        val deviceAspectRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
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
                    .background(Color.Black.copy(alpha = editorState.savedState.overlayBackgroundAlpha)),
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
                val alreadyExists = editorState.tiles.any {
                    it is SystemSliderTileState && it.config.sliderType == SliderType.VOLUME
                }
                if (alreadyExists) {
                    Toast.makeText(context, "A volume slider is already on the grid", Toast.LENGTH_SHORT).show()
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
                val alreadyExists = editorState.tiles.any {
                    it is SystemSliderTileState && it.config.sliderType == SliderType.BRIGHTNESS
                }
                if (alreadyExists) {
                    Toast.makeText(context, "A brightness slider is already on the grid", Toast.LENGTH_SHORT).show()
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
                    onClick = { onSave(editorState.commit()) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = { editorState.reset(); onDiscard() },
                    modifier = Modifier.weight(1f),
                ) { Text("Discard") }
            }
        }
    }
}

/** Request code used when launching the widget configure activity from the editor. */
internal const val CONFIGURE_WIDGET_REQUEST_CODE = 9001
