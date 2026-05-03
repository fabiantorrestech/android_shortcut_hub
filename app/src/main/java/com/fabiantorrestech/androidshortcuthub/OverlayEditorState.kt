package com.fabiantorrestech.androidshortcuthub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Mutable draft state for the in-app layout editor.
 *
 * Call [commit] to produce an [OverlayUiState] ready to be persisted.
 * Call [reset] to discard all unsaved changes.
 *
 * Grid dimensions come from [savedState] and are fixed for the lifetime of the editor session
 * (the user must go to the Grid tab to change them, then re-open the editor).
 */
internal class OverlayEditorState(val savedState: OverlayUiState) {

    val gridRows: Int = savedState.gridRows
    val gridColumns: Int = savedState.gridColumns

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
    )

    /**
     * Discards all working changes and restores the state to [savedState].
     */
    fun reset() {
        tiles.clear()
        tiles.addAll(savedState.tiles)
        nextTileId = savedState.nextTileId
        selectedTileId = null
        hasUnsavedChanges = false
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
     * Moves [id] by ([rowDelta], [colDelta]) cells.
     * Returns false if the move would go out-of-bounds or overlap another tile.
     */
    fun moveTile(id: Int, rowDelta: Int, colDelta: Int): Boolean {
        val current = tiles.firstOrNull { it.id == id } ?: return false
        val newRow = (current.row + rowDelta).coerceIn(0, gridRows - 1)
        val newCol = (current.column + colDelta).coerceIn(0, gridColumns - 1)
        if (newRow == current.row && newCol == current.column) return false
        if (newRow + current.rowSpan > gridRows || newCol + current.columnSpan > gridColumns) return false
        if (overlapsExisting(newRow, newCol, current.rowSpan, current.columnSpan, excludeId = id)) return false
        updateTile(id) { it.copyWithPosition(newRow, newCol) }
        return true
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
}
