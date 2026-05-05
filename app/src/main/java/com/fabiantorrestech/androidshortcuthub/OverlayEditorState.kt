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
internal class OverlayEditorState(val savedState: OverlayUiState) {

    companion object {
        private val defaultFontResults = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
        fun dispatchDefaultFontPicked(uri: String, name: String) { defaultFontResults.tryEmit(uri to name) }
        fun defaultFontEvents(): MutableSharedFlow<Pair<String, String>> = defaultFontResults
    }

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
}
