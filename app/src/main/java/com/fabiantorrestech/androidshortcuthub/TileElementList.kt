package com.fabiantorrestech.androidshortcuthub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Every tile in the current layout, as a list.
 *
 * Two problems it solves. Tiles that render as little or nothing were effectively unreachable -
 * you could only select one by hitting its footprint in the preview, and a 1x1 tile on a 24x16
 * grid is a very small target. And the custom name on a widget, scrollbox or widget stack never
 * appeared anywhere, which is why naming one felt pointless; here the name is what identifies it,
 * so it finally does something.
 *
 * Selecting a row selects that tile and closes the list, dropping the user back in the editor with
 * the inspector already pointed at it.
 */
@Composable
internal fun TileElementList(
    editorState: OverlayEditorState,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    // Reading order rather than insertion order: top-left to bottom-right is how the user sees the
    // grid, so it is how the list should be arranged.
    val tiles = editorState.tiles.sortedWith(compareBy({ it.row }, { it.column }))

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (tiles.isEmpty()) "Elements" else "Elements (${tiles.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (tiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "This layout has no tiles yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tiles, key = { it.id }) { tile ->
                ElementRow(
                    tile = tile,
                    selected = tile.id == editorState.selectedTileId,
                    onClick = {
                        editorState.selectedTileId = tile.id
                        onBack()
                    },
                )
            }
        }
    }
}

@Composable
private fun ElementRow(
    tile: TileState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = tile.elementIcon(),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tile.displayLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${tile.elementTypeName()} · ${tile.columnSpan}×${tile.rowSpan} at (${tile.row}, ${tile.column})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun TileState.elementIcon(): ImageVector = when (this) {
    is AppTileState -> Icons.Rounded.Android
    is IntentTileState -> Icons.AutoMirrored.Rounded.Send
    is WidgetTileState -> Icons.Rounded.Widgets
    is ScrollBoxTileState -> Icons.Rounded.UnfoldMore
    is WidgetStackTileState -> Icons.Rounded.SwapHoriz
    is SystemSliderTileState ->
        if (config.sliderType == SliderType.VOLUME) Icons.AutoMirrored.Rounded.VolumeUp
        else Icons.Rounded.Brightness6
}

private fun TileState.elementTypeName(): String = when (this) {
    is AppTileState -> "App"
    is IntentTileState -> "Intent"
    is WidgetTileState -> "Widget"
    is ScrollBoxTileState -> "Scrollbox · ${children.size} inside"
    is WidgetStackTileState -> "Widget stack · ${widgets.size} inside"
    is SystemSliderTileState ->
        if (config.sliderType == SliderType.VOLUME) "Volume slider" else "Brightness slider"
}
