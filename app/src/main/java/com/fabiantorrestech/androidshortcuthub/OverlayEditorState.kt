package com.fabiantorrestech.androidshortcuthub

import androidx.compose.runtime.derivedStateOf
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

    /**
     * True when the working state actually differs from [savedState].
     *
     * Derived rather than a flag that each edit sets, so undoing an edit by hand clears it: move a
     * tile and move it back, and the editor is clean again. It also means a write that changes
     * nothing cannot dirty the layout - notably a container editor folding an unchanged child list
     * back into its parent on exit, which used to leave "Unsaved changes" behind after merely
     * opening and closing a widget stack.
     *
     * [nextTileId] is deliberately excluded: it is an internal counter, not something the user can
     * see or revert.
     */
    val hasUnsavedChanges: Boolean by derivedStateOf {
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
    }

    /**
     * Refreshes the saved baseline to [committed] after a successful Save, so the editor can stay
     * open with a clean slate instead of exiting. Per-tile dirty checks now compare against it.
     */
    fun markSaved(committed: OverlayUiState) {
        savedState = committed
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
     * has no saved counterpart, so it is removed. [hasUnsavedChanges] re-derives itself, so the
     * editor correctly stays dirty when other tiles or globals still differ from the baseline.
     */
    fun revertTile(id: Int) {
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            val saved = savedState.tiles.firstOrNull { it.id == id }
            if (saved == null) tiles.removeAt(index) else tiles[index] = saved
        }
        selectedTileId = null
    }

    /**
     * Updates grid dimensions and removes any tiles that fall outside the new bounds.
     */
    fun applyGridSize(rows: Int, cols: Int) {
        gridRows = rows
        gridColumns = cols
        tiles.removeAll { !it.fitsGrid(rows, cols) }
        if (selectedTileId != null && tiles.none { it.id == selectedTileId }) {
            selectedTileId = null
        }
    }

    /**
     * How many tiles [applyGridSize] would delete at this size, so the grid controls can warn
     * before the user commits. Shrinking the grid is otherwise silently destructive, with no undo
     * short of leaving without saving. Shares [fitsGrid] with [applyGridSize] so the count and the
     * deletion can never disagree.
     */
    fun tilesLostByGridSize(rows: Int, cols: Int): Int = tiles.count { !it.fitsGrid(rows, cols) }

    fun updateTile(id: Int, transform: (TileState) -> TileState) {
        val index = tiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            tiles[index] = transform(tiles[index])
        }
    }

    fun deleteTile(id: Int) {
        val removed = tiles.removeAll { it.id == id }
        if (removed) {
            if (selectedTileId == id) selectedTileId = null
        }
    }

    fun addTile(tile: TileState) {
        tiles += tile
    }

    /**
     * Moves [id] one step in ([rowDelta], [colDelta]), jumping over any tiles in the way.
     * Scans from the adjacent cell in that direction until it finds a free slot or hits a wall/corner.
     * Returns false if no valid landing position exists.
     */
    fun moveTile(id: Int, rowDelta: Int, colDelta: Int): Boolean {
        val (row, column) = findMoveTarget(id, rowDelta, colDelta) ?: return false
        updateTile(id) { it.copyWithPosition(row, column) }
        return true
    }

    /**
     * Whether [moveTile] in this direction would land anywhere, without actually moving.
     *
     * Lets the inspector grey out directions that are blocked instead of offering a button that
     * silently does nothing, which is how the arrows behaved: [moveTile] already returned false in
     * that case and every caller discarded it.
     */
    fun canMoveTile(id: Int, rowDelta: Int, colDelta: Int): Boolean =
        findMoveTarget(id, rowDelta, colDelta) != null

    /**
     * The cell [moveTile] would land on, or null when the tile is against a wall with no free slot
     * beyond the tiles in the way. Shared with [canMoveTile] so the enabled state and the move can
     * never disagree about what is possible.
     */
    private fun findMoveTarget(id: Int, rowDelta: Int, colDelta: Int): Pair<Int, Int>? {
        val current = tiles.firstOrNull { it.id == id } ?: return null
        var scanRow = current.row + rowDelta
        var scanCol = current.column + colDelta
        while (true) {
            if (scanRow < 0 || scanCol < 0 ||
                scanRow + current.rowSpan > gridRows ||
                scanCol + current.columnSpan > gridColumns
            ) return null
            if (!overlapsExisting(scanRow, scanCol, current.rowSpan, current.columnSpan, excludeId = id)) {
                return scanRow to scanCol
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
        val (rowSpan, colSpan) = findResizeSpan(id, rowSpanDelta, colSpanDelta) ?: return false
        updateTile(id) { it.copyWithSpan(rowSpan, colSpan) }
        return true
    }

    /** Whether [resizeTile] would succeed, without actually resizing. See [canMoveTile]. */
    fun canResizeTile(id: Int, rowSpanDelta: Int, colSpanDelta: Int): Boolean =
        findResizeSpan(id, rowSpanDelta, colSpanDelta) != null

    /**
     * The span [resizeTile] would apply, or null when the result would be off-grid, overlapping, or
     * no change at all. Resize is anchored top-left and grows down and right only.
     */
    private fun findResizeSpan(id: Int, rowSpanDelta: Int, colSpanDelta: Int): Pair<Int, Int>? {
        val current = tiles.firstOrNull { it.id == id } ?: return null
        val newRowSpan = (current.rowSpan + rowSpanDelta).coerceAtLeast(1)
        val newColSpan = (current.columnSpan + colSpanDelta).coerceAtLeast(1)
        if (newRowSpan == current.rowSpan && newColSpan == current.columnSpan) return null
        if (current.row + newRowSpan > gridRows || current.column + newColSpan > gridColumns) return null
        if (overlapsExisting(current.row, current.column, newRowSpan, newColSpan, excludeId = id)) return null
        return newRowSpan to newColSpan
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

/** True when the tile lies wholly inside a [rows]×[cols] grid. */
private fun TileState.fitsGrid(rows: Int, cols: Int): Boolean =
    row < rows && column < cols && row + rowSpan <= rows && column + columnSpan <= cols

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
