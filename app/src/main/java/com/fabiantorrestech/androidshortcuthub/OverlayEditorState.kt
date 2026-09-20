package com.fabiantorrestech.androidshortcuthub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Mutable draft state for the in-app layout editor.
 *
 * Call [commit] to produce an [OverlayUiState] ready to be persisted.
 * Call [reset] to discard all unsaved changes.
 */
internal class OverlayEditorState(initialSavedState: OverlayUiState) {

    companion object {
        private val defaultFontResults = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
        fun dispatchDefaultFontPicked(uri: String, name: String) { defaultFontResults.tryEmit(uri to name) }
        fun defaultFontEvents(): MutableSharedFlow<Pair<String, String>> = defaultFontResults
    }

    /**
     * The last-saved baseline. Mutable via [markSaved] so a Save can refresh it in place
     * ("save and stay" — Save no longer exits the editor) and so per-tile dirty checks always
     * compare against the latest saved snapshot.
     */
    var savedState: OverlayUiState by mutableStateOf(initialSavedState)
        private set

    var gridRows: Int by mutableIntStateOf(savedState.gridRows)
    var gridColumns: Int by mutableIntStateOf(savedState.gridColumns)
    var overlayBackgroundAlpha: Float by mutableFloatStateOf(savedState.overlayBackgroundAlpha)

    var defaultTextScale: Float by mutableFloatStateOf(savedState.defaultTextScale)
    var defaultBoldText: Boolean by mutableStateOf(savedState.defaultBoldText)
    var defaultFontUri: String? by mutableStateOf(savedState.defaultFontUri)
    var defaultFontName: String? by mutableStateOf(savedState.defaultFontName)
    var defaultTextColorMode: DefaultTextColorMode by mutableStateOf(savedState.defaultTextColorMode)
    var defaultTextColorHex: String? by mutableStateOf(savedState.defaultTextColorHex)

    val tiles = mutableStateListOf<TileState>().apply { addAll(savedState.tiles) }
    var selectedTileId by mutableStateOf<Int?>(null)
    var nextTileId by mutableIntStateOf(savedState.nextTileId)
    var hasUnsavedChanges by mutableStateOf(false)

    // ── Core operations ──────────────────────────────────────────────────────

    /**
     * Builds an [OverlayUiState] from the current working state, preserving all
     * non-tile fields (offsets, flags, etc.) from [savedState].
     */
    fun commit(): OverlayUiState = savedState.copy(
        tiles = tiles.toList(),
        nextTileId = nextTileId,
        gridRows = gridRows,
        gridColumns = gridColumns,
        overlayBackgroundAlpha = overlayBackgroundAlpha,
        defaultTextScale = defaultTextScale,
        defaultBoldText = defaultBoldText,
        defaultFontUri = defaultFontUri,
        defaultFontName = defaultFontName,
        defaultTextColorMode = defaultTextColorMode,
        defaultTextColorHex = defaultTextColorHex,
    )

    /**
     * Discards all working changes and restores the state to [savedState].
     */
    fun reset() {
        tiles.clear()
        tiles.addAll(savedState.tiles)
        nextTileId = savedState.nextTileId
        selectedTileId = null
        gridRows = savedState.gridRows
        gridColumns = savedState.gridColumns
        overlayBackgroundAlpha = savedState.overlayBackgroundAlpha
        defaultTextScale = savedState.defaultTextScale
        defaultBoldText = savedState.defaultBoldText
        defaultFontUri = savedState.defaultFontUri
        defaultFontName = savedState.defaultFontName
        defaultTextColorMode = savedState.defaultTextColorMode
        defaultTextColorHex = savedState.defaultTextColorHex
        hasUnsavedChanges = false
    }

    /**
     * Refreshes the saved baseline to [committed] after a successful Save, so the editor can stay
     * open with a clean slate instead of exiting. Per-tile dirty checks now compare against it.
     */
    fun markSaved(committed: OverlayUiState) {
        savedState = committed
        hasUnsavedChanges = false
    }

    /**
     * True if tile [id] differs from its last-saved version, or was added since the last save (no
     * saved counterpart). Drives the per-tile Cancel button's visibility.
     */
    fun isTileDirty(id: Int): Boolean {
        val working = tiles.firstOrNull { it.id == id } ?: return false
        val saved = savedState.tiles.firstOrNull { it.id == id } ?: return true
        return working != saved
    }

    /**
     * Reverts tile [id] to its last-saved state and deselects it. A tile added since the last save
     * has no saved counterpart, so it is removed. Recomputes [hasUnsavedChanges] afterward because
     * other tiles/globals may still differ from the baseline.
     */
    fun revertTile(id: Int) {
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            val saved = savedState.tiles.firstOrNull { it.id == id }
            if (saved == null) tiles.removeAt(index) else tiles[index] = saved
        }
        selectedTileId = null
        recomputeDirty()
    }

    /**
     * Re-derives [hasUnsavedChanges] by comparing the working state against [savedState]. Used after
     * a partial revert; the internal [nextTileId] counter is intentionally excluded (not user-visible).
     */
    fun recomputeDirty() {
        hasUnsavedChanges =
            tiles.toList() != savedState.tiles ||
            gridRows != savedState.gridRows ||
            gridColumns != savedState.gridColumns ||
            overlayBackgroundAlpha != savedState.overlayBackgroundAlpha ||
            defaultTextScale != savedState.defaultTextScale ||
            defaultBoldText != savedState.defaultBoldText ||
            defaultFontUri != savedState.defaultFontUri ||
            defaultFontName != savedState.defaultFontName ||
            defaultTextColorMode != savedState.defaultTextColorMode ||
            defaultTextColorHex != savedState.defaultTextColorHex
    }

    /**
     * Updates grid dimensions and removes any tiles that fall outside the new bounds.
     */
    fun applyGridSize(rows: Int, cols: Int) {
        gridRows = rows
        gridColumns = cols
        tiles.removeAll { tile ->
            tile.row >= rows ||
                tile.column >= cols ||
                tile.row + tile.rowSpan > rows ||
                tile.column + tile.columnSpan > cols
        }
        if (selectedTileId != null && tiles.none { it.id == selectedTileId }) {
            selectedTileId = null
        }
        hasUnsavedChanges = true
    }

    fun updateTile(id: Int, transform: (TileState) -> TileState) {
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            tiles[index] = transform(tiles[index])
            hasUnsavedChanges = true
        }
    }

    fun deleteTile(id: Int) {
        val removed = tiles.removeAll { it.id == id }
        if (removed) {
            if (selectedTileId == id) selectedTileId = null
            hasUnsavedChanges = true
        }
    }

    fun addTile(tile: TileState) {
        tiles += tile
        hasUnsavedChanges = true
    }

    /**
     * Moves [id] one step in ([rowDelta], [colDelta]), jumping over any tiles in the way.
     * Scans from the adjacent cell in that direction until it finds a free slot or hits a wall/corner.
     * Returns false if no valid landing position exists.
     */
    fun moveTile(id: Int, rowDelta: Int, colDelta: Int): Boolean {
        val current = tiles.firstOrNull { it.id == id } ?: return false
        var scanRow = current.row + rowDelta
        var scanCol = current.column + colDelta
        while (true) {
            if (scanRow < 0 || scanCol < 0 ||
                scanRow + current.rowSpan > gridRows ||
                scanCol + current.columnSpan > gridColumns
            ) return false
            if (!overlapsExisting(scanRow, scanCol, current.rowSpan, current.columnSpan, excludeId = id)) {
                updateTile(id) { it.copyWithPosition(scanRow, scanCol) }
                return true
            }
            scanRow += rowDelta
            scanCol += colDelta
        }
    }

    /**
     * Changes the span of [id] by ([rowSpanDelta], [colSpanDelta]).
     * Returns false if the resulting span would be invalid or overlapping.
     */
    fun resizeTile(id: Int, rowSpanDelta: Int, colSpanDelta: Int): Boolean {
        val current = tiles.firstOrNull { it.id == id } ?: return false
        val newRowSpan = (current.rowSpan + rowSpanDelta).coerceAtLeast(1)
        val newColSpan = (current.columnSpan + colSpanDelta).coerceAtLeast(1)
        if (newRowSpan == current.rowSpan && newColSpan == current.columnSpan) return false
        if (current.row + newRowSpan > gridRows || current.column + newColSpan > gridColumns) return false
        if (overlapsExisting(current.row, current.column, newRowSpan, newColSpan, excludeId = id)) return false
        updateTile(id) { it.copyWithSpan(newRowSpan, newColSpan) }
        return true
    }

    /**
     * Returns true if the given rectangle would overlap any existing tile (excluding [excludeId]).
     */
    fun overlapsExisting(
        row: Int,
        col: Int,
        rowSpan: Int,
        colSpan: Int,
        excludeId: Int? = null,
    ): Boolean {
        val rowEnd = row + rowSpan
        val colEnd = col + colSpan
        return tiles.any { tile ->
            if (tile.id == excludeId) return@any false
            row < tile.row + tile.rowSpan &&
                rowEnd > tile.row &&
                col < tile.column + tile.columnSpan &&
                colEnd > tile.column
        }
    }

    /**
     * Scans the grid top-left to bottom-right to find the first open cell large enough for
     * a tile of ([rowSpan], [colSpan]). Returns null if the grid is full.
     */
    fun findFirstOpenCell(rowSpan: Int = 1, colSpan: Int = 1): Pair<Int, Int>? {
        for (r in 0 until gridRows) {
            for (c in 0 until gridColumns) {
                if (r + rowSpan <= gridRows &&
                    c + colSpan <= gridColumns &&
                    !overlapsExisting(r, c, rowSpan, colSpan)
                ) {
                    return r to c
                }
            }
        }
        return null
    }

    /**
     * Finds a placement for a plain widget at [preferredRowSpan]×[preferredColSpan], shrinking the
     * larger dimension one step at a time until something fits (down to 1×1) so a fragmented grid
     * still accepts a widget instead of silently rejecting it. Returns (topLeftCell, rowSpan,
     * colSpan), or null only when nothing fits at all. Callers select the placed widget so it can
     * be resized/moved.
     *
     * The defaults are deliberately small; callers that know the provider should pass the span from
     * [resolveWidgetSpanForGrid] instead. A fixed 2×2 is a sliver on a large grid (e.g. 24×16).
     */
    fun findWidgetPlacement(
        preferredRowSpan: Int = 2,
        preferredColSpan: Int = 2,
    ): Triple<Pair<Int, Int>, Int, Int>? {
        var rowSpan = preferredRowSpan.coerceIn(1, gridRows)
        var colSpan = preferredColSpan.coerceIn(1, gridColumns)
        while (true) {
            findFirstOpenCell(rowSpan, colSpan)?.let { cell -> return Triple(cell, rowSpan, colSpan) }
            if (rowSpan <= 1 && colSpan <= 1) return null
            // Shrink whichever dimension is currently larger, so the footprint stays close to the
            // widget's declared aspect ratio as it degrades.
            if (rowSpan >= colSpan && rowSpan > 1) rowSpan-- else colSpan--
        }
    }
}

/**
 * Converts a widget provider's declared minimum size (dp) into a grid span for a
 * [gridRows]×[gridColumns] grid drawn over a [screenWidthDp]×[screenHeightDp] screen.
 *
 * Mirrors the runtime overlay's sizing (`resolveNativeWidgetSpan` in OverlayUI.kt) so a widget added
 * from the layout editor gets the same footprint it would get when added from the live overlay.
 * The 32dp inset matches the overlay's horizontal/vertical padding.
 */
internal fun resolveWidgetSpanForGrid(
    minWidthDp: Int,
    minHeightDp: Int,
    screenWidthDp: Int,
    screenHeightDp: Int,
    gridRows: Int,
    gridColumns: Int,
): Pair<Int, Int> {
    if (gridRows < 1 || gridColumns < 1) return 1 to 1
    val availableWidthDp = (screenWidthDp - 32).coerceAtLeast(1)
    val availableHeightDp = (screenHeightDp - 32).coerceAtLeast(1)
    val cellWidthDp = availableWidthDp.toFloat() / gridColumns.toFloat()
    val cellHeightDp = availableHeightDp.toFloat() / gridRows.toFloat()
    val rowSpan = kotlin.math.ceil(minHeightDp.coerceAtLeast(1).toFloat() / cellHeightDp)
        .toInt()
        .coerceIn(1, gridRows)
    val colSpan = kotlin.math.ceil(minWidthDp.coerceAtLeast(1).toFloat() / cellWidthDp)
        .toInt()
        .coerceIn(1, gridColumns)
    return rowSpan to colSpan
}
