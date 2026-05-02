package com.fabiantorrestech.androidshortcuthub

import android.content.ComponentName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

// ── Shared constants ─────────────────────────────────────────────────────────

internal const val OVERLAY_PREFS_NAME = "shortcut_hub_overlay"
internal const val OVERLAY_PREFS_KEY_STATE = "overlay_state"
internal const val TILE_TYPE_APP = "app"
internal const val TILE_TYPE_INTENT = "intent"
internal const val TEXT_SCALE_STEP = 0.1f
internal const val TEXT_SCALE_MIN = 0.5f
internal const val TEXT_SCALE_MAX = 3.0f
internal const val PANEL_HANDLE_SIZE_DP = 44
internal const val DPAD_SIZE_DP = 156
internal const val DPAD_BUTTON_SIZE_DP = 40

// ── Data model ───────────────────────────────────────────────────────────────

internal data class LaunchableApp(
    val label: String,
    val componentName: ComponentName,
) {
    val packageName: String get() = componentName.packageName
}

internal sealed class TileState {
    abstract val id: Int
    abstract val row: Int
    abstract val column: Int
    abstract val rowSpan: Int
    abstract val columnSpan: Int
    abstract val customLabel: String?
    abstract val customFontUri: String?
    abstract val customFontName: String?
    abstract val customTextScale: Float?
    abstract val displayLabel: String
}

internal data class AppTileState(
    override val id: Int,
    override val row: Int,
    override val column: Int,
    override val rowSpan: Int = 1,
    override val columnSpan: Int = 1,
    val app: LaunchableApp,
    override val customLabel: String? = null,
    override val customFontUri: String? = null,
    override val customFontName: String? = null,
    override val customTextScale: Float? = null,
) : TileState() {
    override val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: app.label
}

internal enum class IntentType { ACTIVITY, BROADCAST_RECEIVER, SERVICE }

internal data class IntentTileState(
    override val id: Int,
    override val row: Int,
    override val column: Int,
    override val rowSpan: Int = 1,
    override val columnSpan: Int = 1,
    val intentAction: String,
    val intentType: IntentType = IntentType.ACTIVITY,
    val intentPackage: String? = null,
    val intentComponent: String? = null,
    val intentDataUri: String? = null,
    val intentExtras: Map<String, String> = emptyMap(),
    override val customLabel: String? = null,
    override val customFontUri: String? = null,
    override val customFontName: String? = null,
    override val customTextScale: Float? = null,
) : TileState() {
    override val displayLabel: String
        get() = customLabel?.takeIf { it.isNotBlank() } ?: intentAction
}

internal fun TileState.copyWithLabel(label: String?): TileState = when (this) {
    is AppTileState -> copy(customLabel = label)
    is IntentTileState -> copy(customLabel = label)
}

internal fun TileState.copyWithFont(uri: String?, name: String?): TileState = when (this) {
    is AppTileState -> copy(customFontUri = uri, customFontName = name)
    is IntentTileState -> copy(customFontUri = uri, customFontName = name)
}

internal fun TileState.copyWithTextScale(scale: Float?): TileState = when (this) {
    is AppTileState -> copy(customTextScale = scale)
    is IntentTileState -> copy(customTextScale = scale)
}

internal fun TileState.copyWithPosition(row: Int, column: Int): TileState = when (this) {
    is AppTileState -> copy(row = row, column = column)
    is IntentTileState -> copy(row = row, column = column)
}

internal fun TileState.copyWithSpan(rowSpan: Int, columnSpan: Int): TileState = when (this) {
    is AppTileState -> copy(rowSpan = rowSpan, columnSpan = columnSpan)
    is IntentTileState -> copy(rowSpan = rowSpan, columnSpan = columnSpan)
}

data class TileFontSelection(val tileId: Int, val fontUri: String, val fontName: String)

internal data class OverlayUiState(
    val showGrid: Boolean = false,
    val dpadOffsetX: Float = 24f,
    val dpadOffsetY: Float = 220f,
    val topPanelOffsetX: Float = 16f,
    val topPanelOffsetY: Float = 16f,
    val nextTileId: Int = 1,
    val tiles: List<TileState> = emptyList(),
    val gridRows: Int = 8,
    val gridColumns: Int = 4,
    val defaultTextScale: Float = 1.0f,
    val defaultFontUri: String? = null,
    val defaultFontName: String? = null,
    val defaultTextColorMode: DefaultTextColorMode = DefaultTextColorMode.SYSTEM,
    val defaultTextColorHex: String? = null,
    val hapticFeedbackEnabled: Boolean = true,
    val panelHandleLocked: Boolean = false,
    val overlayBackgroundAlpha: Float = 0.33f,
    val showOverLockscreen: Boolean = false,
)

// ── Utility ──────────────────────────────────────────────────────────────────

internal fun findTile(tiles: List<TileState>, id: Int?): TileState? =
    tiles.firstOrNull { it.id == id }

// ── Overlay composable ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OverlayContent(
    initialState: OverlayUiState,
    preloadedFonts: Map<String, FontFamily?>,
    tileFontEvents: Flow<TileFontSelection>,
    loadLaunchableApps: () -> List<LaunchableApp>,
    resolveCustomPackage: (String) -> LaunchableApp?,
    loadFontFamily: (String?) -> FontFamily?,
    openTileFontPicker: (Int) -> Unit,
    launchApp: (LaunchableApp) -> Unit,
    launchIntent: (IntentTileState) -> Unit,
    onPersist: (OverlayUiState) -> Unit,
    onDismiss: () -> Unit,
) {
    // ── State ────────────────────────────────────────────────────────────────
    val tiles = remember { mutableStateListOf<TileState>().apply { addAll(initialState.tiles) } }
    var nextTileId by remember { mutableIntStateOf(initialState.nextTileId) }
    var showGrid by remember { mutableStateOf(initialState.showGrid) }
    var selectedTileId by remember { mutableStateOf<Int?>(null) }
    var dpadOffsetX by remember { mutableFloatStateOf(initialState.dpadOffsetX) }
    var dpadOffsetY by remember { mutableFloatStateOf(initialState.dpadOffsetY) }
    var topPanelOffsetX by remember { mutableFloatStateOf(initialState.topPanelOffsetX) }
    var topPanelOffsetY by remember { mutableFloatStateOf(initialState.topPanelOffsetY) }
    var isPanelVisible by remember { mutableStateOf(false) }

    // App chooser state
    var choosingAppForTileId by remember { mutableStateOf<Int?>(null) }
    var pendingNewTileCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var appSearchQuery by remember { mutableStateOf("") }
    var customPackageName by remember { mutableStateOf("") }
    var chooserError by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var hasLoadedApps by remember { mutableStateOf(false) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var appsLoadError by remember { mutableStateOf<String?>(null) }

    // Intent form state
    var pendingNewIntentCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editingIntentTileId by remember { mutableStateOf<Int?>(null) }
    var intentActionDraft by remember { mutableStateOf("") }
    var intentTypeDraft by remember { mutableStateOf(IntentType.ACTIVITY) }
    var intentPackageDraft by remember { mutableStateOf("") }
    var intentComponentDraft by remember { mutableStateOf("") }
    var intentDataUriDraft by remember { mutableStateOf("") }
    val intentExtrasDraft = remember { mutableStateListOf<Pair<String, String>>() }
    var intentFormError by remember { mutableStateOf<String?>(null) }

    // Font menu state
    var fontMenuTileId by remember { mutableStateOf<Int?>(null) }
    var tileLabelDraft by remember { mutableStateOf("") }

    // Font families
    var defaultFontFamily by remember { mutableStateOf(preloadedFonts[initialState.defaultFontUri]) }

    val gridRows = initialState.gridRows
    val gridColumns = initialState.gridColumns
    val defaultTextScale = initialState.defaultTextScale
    val defaultTextColor = resolveDefaultTileTextColor(
        mode = initialState.defaultTextColorMode,
        hex = initialState.defaultTextColorHex,
        fallback = MaterialTheme.colorScheme.onSurface,
    )

    val isChooserVisible = choosingAppForTileId != null || pendingNewTileCell != null
    val isIntentFormVisible = pendingNewIntentCell != null || editingIntentTileId != null

    val filteredApps = remember(apps, appSearchQuery) {
        val query = appSearchQuery.trim()
        if (query.isEmpty()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun persist() {
        onPersist(
            OverlayUiState(
                showGrid = showGrid,
                dpadOffsetX = dpadOffsetX,
                dpadOffsetY = dpadOffsetY,
                topPanelOffsetX = topPanelOffsetX,
                topPanelOffsetY = topPanelOffsetY,
                nextTileId = nextTileId,
                tiles = tiles.toList(),
                gridRows = gridRows,
                gridColumns = gridColumns,
                defaultTextScale = defaultTextScale,
                defaultFontUri = initialState.defaultFontUri,
                defaultFontName = initialState.defaultFontName,
                defaultTextColorMode = initialState.defaultTextColorMode,
                defaultTextColorHex = initialState.defaultTextColorHex,
            ),
        )
    }

    fun overlapsExisting(row: Int, column: Int, rowSpan: Int, columnSpan: Int, excludedTileId: Int? = null): Boolean {
        val rowEnd = row + rowSpan
        val colEnd = column + columnSpan
        return tiles.any { tile ->
            if (tile.id == excludedTileId) return@any false
            row < tile.row + tile.rowSpan && rowEnd > tile.row &&
                column < tile.column + tile.columnSpan && colEnd > tile.column
        }
    }

    fun findFirstOpenCell(): Pair<Int, Int>? {
        for (r in 0 until gridRows) {
            for (c in 0 until gridColumns) {
                if (!overlapsExisting(r, c, 1, 1)) return r to c
            }
        }
        return null
    }

    fun moveSelected(rowDelta: Int, columnDelta: Int) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val newRow = (current.row + rowDelta).coerceIn(0, gridRows - 1)
        val newCol = (current.column + columnDelta).coerceIn(0, gridColumns - 1)
        if (newRow == current.row && newCol == current.column) return
        if (newRow + current.rowSpan > gridRows || newCol + current.columnSpan > gridColumns) return
        if (overlapsExisting(newRow, newCol, current.rowSpan, current.columnSpan, current.id)) return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithPosition(newRow, newCol); persist() }
    }

    fun resizeSelected(rowSpanDelta: Int, columnSpanDelta: Int) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val newRowSpan = (current.rowSpan + rowSpanDelta).coerceAtLeast(1)
        val newColSpan = (current.columnSpan + columnSpanDelta).coerceAtLeast(1)
        if (newRowSpan == current.rowSpan && newColSpan == current.columnSpan) return
        if (current.row + newRowSpan > gridRows || current.column + newColSpan > gridColumns) return
        if (overlapsExisting(current.row, current.column, newRowSpan, newColSpan, current.id)) return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithSpan(newRowSpan, newColSpan); persist() }
    }

    fun adjustTextScale(delta: Float) {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val newScale = ((current.customTextScale ?: defaultTextScale) + delta)
            .coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithTextScale(newScale); persist() }
    }

    fun resetTextScale() {
        val id = selectedTileId ?: return
        val current = findTile(tiles, id) ?: return
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) { tiles[index] = current.copyWithTextScale(null); persist() }
    }

    fun closeChooser() {
        choosingAppForTileId = null
        pendingNewTileCell = null
        appSearchQuery = ""
        customPackageName = ""
        chooserError = null
        appsLoadError = null
    }

    fun applyAppSelection(app: LaunchableApp) {
        val editId = choosingAppForTileId
        if (editId != null) {
            val index = tiles.indexOfFirst { it.id == editId }
            if (index >= 0) {
                val current = tiles[index] as? AppTileState ?: return
                tiles[index] = current.copy(app = app)
                persist()
            }
        } else {
            val cell = pendingNewTileCell ?: return
            val newId = nextTileId++
            tiles += AppTileState(id = newId, row = cell.first, column = cell.second, app = app)
            selectedTileId = newId
            persist()
        }
        closeChooser()
    }

    fun resolveCustomEntry(input: String): LaunchableApp? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return apps.firstOrNull { it.packageName.equals(trimmed, ignoreCase = true) || it.label.equals(trimmed, ignoreCase = true) }
            ?: resolveCustomPackage(trimmed)
    }

    fun openIntentFormForNew() {
        val cell = findFirstOpenCell() ?: return
        pendingNewIntentCell = cell
        editingIntentTileId = null
        intentActionDraft = ""
        intentTypeDraft = IntentType.ACTIVITY
        intentPackageDraft = ""
        intentComponentDraft = ""
        intentDataUriDraft = ""
        intentExtrasDraft.clear()
        intentFormError = null
    }

    fun openIntentFormForEdit(tileId: Int) {
        val tile = findTile(tiles, tileId) as? IntentTileState ?: return
        editingIntentTileId = tileId
        pendingNewIntentCell = null
        intentActionDraft = tile.intentAction
        intentTypeDraft = tile.intentType
        intentPackageDraft = tile.intentPackage ?: ""
        intentComponentDraft = tile.intentComponent ?: ""
        intentDataUriDraft = tile.intentDataUri ?: ""
        intentExtrasDraft.clear()
        intentExtrasDraft.addAll(tile.intentExtras.entries.map { it.key to it.value })
        intentFormError = null
    }

    fun applyIntentForm() {
        val action = intentActionDraft.trim()
        if (action.isEmpty()) { intentFormError = "Action is required"; return }
        val extras = intentExtrasDraft.filter { (k, _) -> k.isNotBlank() }.toMap()

        val editId = editingIntentTileId
        if (editId != null) {
            val index = tiles.indexOfFirst { it.id == editId }
            if (index >= 0) {
                val current = tiles[index] as? IntentTileState ?: return
                tiles[index] = current.copy(
                    intentAction = action,
                    intentType = intentTypeDraft,
                    intentPackage = intentPackageDraft.trim().ifBlank { null },
                    intentComponent = intentComponentDraft.trim().ifBlank { null },
                    intentDataUri = intentDataUriDraft.trim().ifBlank { null },
                    intentExtras = extras,
                )
                persist()
            }
        } else {
            val cell = pendingNewIntentCell ?: return
            val newId = nextTileId++
            tiles += IntentTileState(
                id = newId,
                row = cell.first,
                column = cell.second,
                intentAction = action,
                intentType = intentTypeDraft,
                intentPackage = intentPackageDraft.trim().ifBlank { null },
                intentComponent = intentComponentDraft.trim().ifBlank { null },
                intentDataUri = intentDataUriDraft.trim().ifBlank { null },
                intentExtras = extras,
            )
            selectedTileId = newId
            persist()
        }
        pendingNewIntentCell = null
        editingIntentTileId = null
        intentFormError = null
    }

    fun closeIntentForm() {
        pendingNewIntentCell = null
        editingIntentTileId = null
        intentFormError = null
    }

    // ── Effects ──────────────────────────────────────────────────────────────

    LaunchedEffect(initialState.defaultFontUri) {
        if (defaultFontFamily == null && initialState.defaultFontUri != null) {
            defaultFontFamily = withContext(Dispatchers.IO) { loadFontFamily(initialState.defaultFontUri) }
        }
    }

    LaunchedEffect(Unit) {
        tileFontEvents.collectLatest { selection ->
            val index = tiles.indexOfFirst { it.id == selection.tileId }
            if (index >= 0) {
                tiles[index] = tiles[index].copyWithFont(selection.fontUri, selection.fontName)
                persist()
            }
            fontMenuTileId = null
        }
    }

    LaunchedEffect(isChooserVisible, hasLoadedApps) {
        if (isChooserVisible && !hasLoadedApps && !isLoadingApps) {
            isLoadingApps = true
            appsLoadError = null
            runCatching { withContext(Dispatchers.IO) { loadLaunchableApps() } }
                .onSuccess { loadedApps -> apps = loadedApps; hasLoadedApps = true }
                .onFailure { appsLoadError = "Unable to load installed apps" }
            isLoadingApps = false
        }
    }

    LaunchedEffect(selectedTileId, tiles.size) {
        tileLabelDraft = findTile(tiles, selectedTileId)?.customLabel.orEmpty()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    val hapticFeedback = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = initialState.overlayBackgroundAlpha))
            .clickable(onClick = onDismiss),
    ) {
        // Panel handle (always visible) + collapsible panel
        Column(
            modifier = Modifier
                .offset { IntOffset(topPanelOffsetX.roundToInt(), topPanelOffsetY.roundToInt()) }
                .wrapContentWidth()
                .wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelHandle(
                currentOffsetX = topPanelOffsetX,
                currentOffsetY = topPanelOffsetY,
                locked = initialState.panelHandleLocked,
                onOffsetChange = { x, y -> topPanelOffsetX = x; topPanelOffsetY = y },
                onDragFinished = { persist() },
                onToggle = { isPanelVisible = !isPanelVisible },
            )
            if (isPanelVisible) {
                Card(
                    modifier = Modifier
                        .wrapContentWidth()
                        .wrapContentHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                    text = "Shortcut Hub",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        val cell = findFirstOpenCell() ?: return@Button
                        pendingNewTileCell = cell
                        choosingAppForTileId = null
                        appSearchQuery = ""
                        customPackageName = ""
                        chooserError = null
                        appsLoadError = null
                    }) { Text("Add") }
                    OutlinedButton(onClick = { openIntentFormForNew() }) { Text("Intent") }
                    OutlinedButton(onClick = { showGrid = !showGrid; persist() }) {
                        Text(if (showGrid) "Grid Off" else "Grid On")
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                }

                val selectedTile = findTile(tiles, selectedTileId)
                if (selectedTile != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (selectedTile) {
                            is AppTileState -> OutlinedButton(onClick = {
                                choosingAppForTileId = selectedTileId
                                pendingNewTileCell = null
                                appSearchQuery = ""
                                customPackageName = ""
                                chooserError = null
                                appsLoadError = null
                            }) { Text("App") }
                            is IntentTileState -> OutlinedButton(onClick = {
                                openIntentFormForEdit(selectedTile.id)
                            }) { Text("Edit") }
                        }
                        OutlinedButton(onClick = { fontMenuTileId = selectedTileId }) { Text("Font") }
                        OutlinedButton(onClick = {
                            val id = selectedTileId ?: return@OutlinedButton
                            tiles.removeAll { it.id == id }
                            selectedTileId = null
                            choosingAppForTileId = null
                            persist()
                        }) { Text("Del") }
                        OutlinedButton(onClick = { selectedTileId = null }) { Text("Exit") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { resizeSelected(0, 1) }) { Text("W+") }
                        OutlinedButton(onClick = { resizeSelected(0, -1) }) { Text("W-") }
                        OutlinedButton(onClick = { resizeSelected(1, 0) }) { Text("H+") }
                        OutlinedButton(onClick = { resizeSelected(-1, 0) }) { Text("H-") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { adjustTextScale(TEXT_SCALE_STEP) }) { Text("S+") }
                        OutlinedButton(onClick = { adjustTextScale(-TEXT_SCALE_STEP) }) { Text("S-") }
                        OutlinedButton(onClick = { resetTextScale() }) { Text("S Reset") }
                    }
                    Text(
                        text = "Size ${selectedTile.columnSpan}×${selectedTile.rowSpan}  " +
                            "Text: ${selectedTile.customTextScale?.let { "%.1f×".format(it) } ?: "default"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = tileLabelDraft,
                        onValueChange = { tileLabelDraft = it },
                        modifier = Modifier.width(240.dp),
                        label = { Text("Rename Tile") },
                        placeholder = { Text("Use app name") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            val id = selectedTileId ?: return@OutlinedButton
                            val index = tiles.indexOfFirst { it.id == id }
                            if (index >= 0) {
                                tiles[index] = tiles[index].copyWithLabel(tileLabelDraft.trim().ifBlank { null })
                                persist()
                            }
                        }) { Text("Save") }
                        OutlinedButton(onClick = {
                            val id = selectedTileId ?: return@OutlinedButton
                            tileLabelDraft = ""
                            val index = tiles.indexOfFirst { it.id == id }
                            if (index >= 0) {
                                tiles[index] = tiles[index].copyWithLabel(null)
                                persist()
                            }
                        }) { Text("Reset") }
                    }
                }
            }
                }
            }
        }

        // Grid + tiles
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            val cellWidth = maxWidth / gridColumns
            val cellHeight = maxHeight / gridRows

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
                Card(
                    modifier = Modifier
                        .offset(x = cellWidth * tile.column, y = cellHeight * tile.row)
                        .size(width = cellWidth * tile.columnSpan, height = cellHeight * tile.rowSpan)
                        .padding(6.dp)
                        .combinedClickable(
                            onClick = {
                                if (initialState.hapticFeedbackEnabled) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                if (selectedTileId == null) {
                                    when (tile) {
                                        is AppTileState -> { launchApp(tile.app); onDismiss() }
                                        is IntentTileState -> { launchIntent(tile); onDismiss() }
                                    }
                                }
                            },
                            onLongClick = {
                                if (initialState.hapticFeedbackEnabled) {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                if (selectedTileId == tile.id) fontMenuTileId = tile.id
                                else selectedTileId = tile.id
                            },
                        ),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ),
                ) {
                    Surface(color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = tile.displayLabel,
                                modifier = Modifier.align(Alignment.Center).padding(8.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize *
                                        (tile.customTextScale ?: defaultTextScale),
                                    fontFamily = rememberTileFontFamily(
                                        fontUri = tile.customFontUri,
                                        preloadedFonts = preloadedFonts,
                                        loadFontFamily = loadFontFamily,
                                    ) ?: defaultFontFamily,
                                ),
                                color = defaultTextColor,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // D-pad
        if (selectedTileId != null) {
            DraggableDpad(
                offsetX = dpadOffsetX,
                offsetY = dpadOffsetY,
                onOffsetChange = { x, y -> dpadOffsetX = x; dpadOffsetY = y },
                onDragFinished = { persist() },
                onMoveUp = { moveSelected(-1, 0) },
                onMoveDown = { moveSelected(1, 0) },
                onMoveLeft = { moveSelected(0, -1) },
                onMoveRight = { moveSelected(0, 1) },
            )
        }

        // App chooser dialog
        if (isChooserVisible) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (choosingAppForTileId != null) "Choose App" else "Add App Tile",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = appSearchQuery,
                            onValueChange = { appSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search installed apps") },
                            singleLine = true,
                        )
                        Text(
                            text = "${filteredApps.size} apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            isLoadingApps -> Text("Loading installed apps...", style = MaterialTheme.typography.bodyMedium)
                            appsLoadError != null -> Text(appsLoadError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            else -> LazyColumn(
                                modifier = Modifier.height(440.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(filteredApps) { app ->
                                    Surface(
                                        onClick = { applyAppSelection(app) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(18.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = customPackageName,
                            onValueChange = { customPackageName = it; chooserError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Package or exact app name") },
                            singleLine = true,
                        )
                        OutlinedButton(onClick = {
                            val resolved = resolveCustomEntry(customPackageName)
                            if (resolved == null) chooserError = "App/package not found or not launchable"
                            else applyAppSelection(resolved)
                        }) { Text("Use App / Package") }
                        chooserError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        OutlinedButton(onClick = ::closeChooser, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                    }
                }
            }
        }

        // Intent tile form dialog
        if (isIntentFormVisible) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (editingIntentTileId != null) "Edit Intent Tile" else "Add Intent Tile",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Type", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IntentType.entries.forEach { type ->
                                val label = when (type) {
                                    IntentType.ACTIVITY -> "Activity"
                                    IntentType.BROADCAST_RECEIVER -> "Broadcast"
                                    IntentType.SERVICE -> "Service"
                                }
                                if (intentTypeDraft == type) {
                                    Button(onClick = {}) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { intentTypeDraft = type }) { Text(label) }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = intentActionDraft,
                            onValueChange = { intentActionDraft = it; intentFormError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Action (required)") },
                            placeholder = { Text("e.g. android.intent.action.VIEW") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intentPackageDraft,
                            onValueChange = { intentPackageDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Package (optional)") },
                            placeholder = { Text("e.g. com.example.app") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intentComponentDraft,
                            onValueChange = { intentComponentDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Component / Class (optional)") },
                            placeholder = { Text("pkg/com.example.Activity") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intentDataUriDraft,
                            onValueChange = { intentDataUriDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Data URI (optional)") },
                            placeholder = { Text("e.g. https://example.com") },
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Extras", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { intentExtrasDraft.add("" to "") }) {
                                Text("+ Add Extra")
                            }
                        }
                        intentExtrasDraft.forEachIndexed { index, (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { intentExtrasDraft[index] = it to value },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Key") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { intentExtrasDraft[index] = key to it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Value") },
                                    singleLine = true,
                                )
                                OutlinedButton(onClick = { intentExtrasDraft.removeAt(index) }) {
                                    Text("×")
                                }
                            }
                        }
                        intentFormError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { applyIntentForm() }, modifier = Modifier.weight(1f)) {
                                Text(if (editingIntentTileId != null) "Save" else "Add")
                            }
                            OutlinedButton(onClick = { closeIntentForm() }, modifier = Modifier.weight(1f)) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }

        // Font menu dialog
        fontMenuTileId?.let { tileId ->
            val tile = findTile(tiles, tileId)
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = RoundedCornerShape(24.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Tile Font", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Current font: ${tile?.customFontName ?: initialState.defaultFontName ?: "Default"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = { openTileFontPicker(tileId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Choose Font Override") }
                        OutlinedButton(
                            onClick = {
                                val index = tiles.indexOfFirst { it.id == tileId }
                                if (index >= 0) { tiles[index] = tiles[index].copyWithFont(null, null); persist() }
                                fontMenuTileId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Clear Font Override") }
                        OutlinedButton(
                            onClick = { fontMenuTileId = null },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Close") }
                    }
                }
            }
        }
    }
}

// ── Support composables ──────────────────────────────────────────────────────

@Composable
internal fun DraggableDpad(
    offsetX: Float,
    offsetY: Float,
    onOffsetChange: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    var displayOffsetX by remember { mutableFloatStateOf(offsetX) }
    var displayOffsetY by remember { mutableFloatStateOf(offsetY) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(offsetX, offsetY, isDragging) {
        if (!isDragging) { displayOffsetX = offsetX; displayOffsetY = offsetY }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(displayOffsetX.roundToInt(), displayOffsetY.roundToInt()) }
            .size(DPAD_SIZE_DP.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val buttonSize = DPAD_BUTTON_SIZE_DP.dp
                val dragBarTop = 10.dp
                val dragBarHeight = 10.dp
                val dragBarSpacing = 12.dp
                val bottomMargin = 8.dp
                val clusterTop = dragBarTop + dragBarHeight + dragBarSpacing
                val clusterHeightRaw = maxHeight - clusterTop - bottomMargin
                val clusterHeight = if (clusterHeightRaw < buttonSize) buttonSize else clusterHeightRaw
                val clusterWidth = maxWidth - 12.dp
                val crossSpan = min(clusterWidth.value, clusterHeight.value).dp
                val arm = ((crossSpan - buttonSize) / 2).coerceAtLeast(0.dp)
                val centerX = maxWidth / 2
                val centerY = clusterTop + (clusterHeight / 2)
                val buttonBaseX = centerX - (buttonSize / 2)
                val buttonBaseY = centerY - (buttonSize / 2)

                DragHandle(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = dragBarTop)
                        .size(width = maxWidth * 0.56f, height = dragBarHeight),
                    currentOffsetX = displayOffsetX,
                    currentOffsetY = displayOffsetY,
                    onDragStarted = { isDragging = true },
                    onOffsetChange = { x, y -> displayOffsetX = x; displayOffsetY = y; onOffsetChange(x, y) },
                    onDragFinished = { isDragging = false; onDragFinished() },
                )
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX, y = buttonBaseY - arm), direction = ArrowDirection.Up, onClick = onMoveUp)
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX - arm, y = buttonBaseY), direction = ArrowDirection.Left, onClick = onMoveLeft)
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX + arm, y = buttonBaseY), direction = ArrowDirection.Right, onClick = onMoveRight)
                DirectionButton(modifier = Modifier.offset(x = buttonBaseX, y = buttonBaseY + arm), direction = ArrowDirection.Down, onClick = onMoveDown)
            }
        }
    }
}

internal enum class ArrowDirection { Up, Down, Left, Right }

@Composable
internal fun DirectionButton(modifier: Modifier = Modifier, direction: ArrowDirection, onClick: () -> Unit) {
    Surface(
        modifier = modifier.size(DPAD_BUTTON_SIZE_DP.dp).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { ArrowIcon(direction = direction) }
    }
}

@Composable
internal fun ArrowIcon(direction: ArrowDirection, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = 3.5f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val shaft = size.minDimension * 0.28f
        val head = size.minDimension * 0.18f
        val (start, end, headA, headB) = when (direction) {
            ArrowDirection.Up -> Quad(Offset(cx, cy + shaft), Offset(cx, cy - shaft), Offset(cx - head, cy - shaft + head), Offset(cx + head, cy - shaft + head))
            ArrowDirection.Down -> Quad(Offset(cx, cy - shaft), Offset(cx, cy + shaft), Offset(cx - head, cy + shaft - head), Offset(cx + head, cy + shaft - head))
            ArrowDirection.Left -> Quad(Offset(cx + shaft, cy), Offset(cx - shaft, cy), Offset(cx - shaft + head, cy - head), Offset(cx - shaft + head, cy + head))
            ArrowDirection.Right -> Quad(Offset(cx - shaft, cy), Offset(cx + shaft, cy), Offset(cx + shaft - head, cy - head), Offset(cx + shaft - head, cy + head))
        }
        drawLine(color, start, end, stroke)
        drawLine(color, end, headA, stroke)
        drawLine(color, end, headB, stroke)
    }
}

private data class Quad(val a: Offset, val b: Offset, val c: Offset, val d: Offset)

@Composable
internal fun DragHandle(
    modifier: Modifier = Modifier,
    currentOffsetX: Float,
    currentOffsetY: Float,
    onDragStarted: () -> Unit,
    onOffsetChange: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
) {
    val latestOffsetX by rememberUpdatedState(currentOffsetX)
    val latestOffsetY by rememberUpdatedState(currentOffsetY)

    Surface(
        modifier = modifier.pointerInput(Unit) {
            var startX = 0f
            var startY = 0f
            var totalX = 0f
            var totalY = 0f
            detectDragGestures(
                onDragStart = { onDragStarted(); startX = latestOffsetX; startY = latestOffsetY; totalX = 0f; totalY = 0f },
                onDragEnd = onDragFinished,
                onDragCancel = onDragFinished,
                onDrag = { change, drag ->
                    change.consume()
                    totalX += drag.x
                    totalY += drag.y
                    onOffsetChange(startX + totalX, startY + totalY)
                },
            )
        },
        color = Color.White.copy(alpha = 0.28f),
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
    ) { Box(modifier = Modifier.fillMaxSize()) }
}

@Composable
internal fun rememberTileFontFamily(
    fontUri: String?,
    preloadedFonts: Map<String, FontFamily?>,
    loadFontFamily: (String?) -> FontFamily?,
): FontFamily? {
    var fontFamily by remember(fontUri) { mutableStateOf(fontUri?.let { preloadedFonts[it] }) }

    LaunchedEffect(fontUri) {
        if (fontFamily == null && fontUri != null) {
            fontFamily = withContext(Dispatchers.IO) { loadFontFamily(fontUri) }
        }
    }

    return fontFamily
}

internal fun resolveDefaultTileTextColor(mode: DefaultTextColorMode, hex: String?, fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    when (mode) {
        DefaultTextColorMode.SYSTEM -> fallback
        DefaultTextColorMode.BLACK -> Color.Black
        DefaultTextColorMode.WHITE -> Color.White
        DefaultTextColorMode.CUSTOM -> normalizeHexColor(hex)?.let { raw ->
            runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull()
        } ?: fallback
    }

@Composable
internal fun PanelHandle(
    currentOffsetX: Float,
    currentOffsetY: Float,
    locked: Boolean,
    onOffsetChange: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onToggle: () -> Unit,
) {
    val latestOffsetX by rememberUpdatedState(currentOffsetX)
    val latestOffsetY by rememberUpdatedState(currentOffsetY)

    Box(
        modifier = Modifier
            .size(PANEL_HANDLE_SIZE_DP.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    if (!locked) {
                        var startX = 0f
                        var startY = 0f
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                startX = latestOffsetX
                                startY = latestOffsetY
                                totalX = 0f
                                totalY = 0f
                            },
                            onDragEnd = onDragFinished,
                            onDragCancel = onDragFinished,
                            onDrag = { change, drag ->
                                change.consume()
                                totalX += drag.x
                                totalY += drag.y
                                onOffsetChange(startX + totalX, startY + totalY)
                            },
                        )
                    }
                },
            color = Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineW = size.width * 0.5f
                val strokePx = 2.dp.toPx()
                val gap = 5.dp.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                for (i in -1..1) {
                    val y = cy + i * gap
                    drawLine(
                        color = Color.White.copy(alpha = 0.75f),
                        start = Offset(cx - lineW / 2f, y),
                        end = Offset(cx + lineW / 2f, y),
                        strokeWidth = strokePx,
                    )
                }
            }
        }
    }
}
