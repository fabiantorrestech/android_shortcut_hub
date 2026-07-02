package com.fabiantorrestech.androidshortcuthub

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Full-screen editor for a single [WidgetStackTileState], opened from the main layout editor when a
 * widget-stack tile is selected.
 *
 * Manages an ordered, reorderable list of the stack's widgets (list order = swipe order), reuses the
 * existing [BindWidgetActivity] flow to add widgets, and edits the page-dots + auto-rotate options.
 * Adding a widget is consumed here via an ON_RESUME observer so the bound widget lands in this stack
 * rather than the top-level grid. Changes are folded back into [parentEditorState] on exit; the main
 * editor's overall Save / Discard governs final persistence.
 */
@Composable
internal fun WidgetStackEditorScreen(
    parentEditorState: OverlayEditorState,
    widgetStackId: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val stack = parentEditorState.tiles.firstOrNull { it.id == widgetStackId } as? WidgetStackTileState
    if (stack == null) {
        LaunchedEffect(widgetStackId) { onBack() }
        return
    }

    val widgets = remember(widgetStackId) { mutableStateListOf<WidgetTileState>().apply { addAll(stack.widgets) } }
    var showPageIndicator by remember(widgetStackId) { mutableStateOf(stack.showPageIndicator) }
    var autoRotate by remember(widgetStackId) { mutableStateOf(stack.autoRotate) }
    var autoRotateSeconds by remember(widgetStackId) { mutableIntStateOf(stack.autoRotateSeconds) }

    fun commitAndExit() {
        parentEditorState.updateTile(widgetStackId) { current ->
            (current as? WidgetStackTileState)?.copy(
                widgets = widgets.toList(),
                showPageIndicator = showPageIndicator,
                autoRotate = autoRotate,
                autoRotateSeconds = autoRotateSeconds,
            ) ?: current
        }
        onBack()
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target in widgets.indices) {
            val moved = widgets.removeAt(index)
            widgets.add(target, moved)
        }
    }

    fun launchAddWidget() {
        WidgetBindingCoordinator.startBinding()
        context.startActivity(
            // hasVolumeSlider / hasBrightnessSlider = true disables the slider options, so this is a
            // widget-only flow. autoToggleOverlay = false keeps the overlay from being invoked.
            BindWidgetActivity.createIntent(
                context,
                parentEditorState.gridRows,
                parentEditorState.gridColumns,
                hasVolumeSlider = true,
                hasBrightnessSlider = true,
                autoToggleOverlay = false,
            ),
        )
    }

    // Consume a completed widget bind on resume → append to this stack (independent widget instance).
    DisposableEffect(activity) {
        val lifecycle = activity?.lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val insertion = WidgetBindingCoordinator.consumeCompletedInsertion()
                if (insertion is TileInsertionEvent.WidgetAdded) {
                    val newId = (widgets.maxOfOrNull { it.id } ?: 0) + 1
                    widgets.add(
                        WidgetTileState(
                            id = newId,
                            row = 0,
                            column = 0,
                            rowSpan = 1,
                            columnSpan = 1,
                            appWidgetId = insertion.selection.appWidgetId,
                            providerComponent = insertion.selection.providerComponent,
                        ),
                    )
                }
                // A SystemSliderAdded event is ignored — a widget stack holds widgets only.
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { commitAndExit() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done editing widget stack")
            }
            Text(
                text = stack.displayLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            Text(
                text = if (widgets.size == 1) "1 widget" else "${widgets.size} widgets",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        // ── Options ──────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show page dots", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showPageIndicator, onCheckedChange = { showPageIndicator = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-rotate", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = autoRotate, onCheckedChange = { autoRotate = it })
            }
            if (autoRotate) {
                Text(
                    "Rotate every ${autoRotateSeconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = autoRotateSeconds.toFloat(),
                    onValueChange = { autoRotateSeconds = it.toInt().coerceIn(3, 60) },
                    valueRange = 3f..60f,
                )
            }
        }

        // ── Widget list (reorderable) ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (widgets.isEmpty()) {
                Text(
                    text = "No widgets yet. Tap “+ Add widget” to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                widgets.forEachIndexed { index, widget ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = widget.providerComponent.substringAfterLast("."),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                text = "page ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { move(index, -1) }, enabled = index > 0) { Text("↑") }
                        OutlinedButton(onClick = { move(index, 1) }, enabled = index < widgets.size - 1) { Text("↓") }
                        OutlinedButton(onClick = { widgets.removeAt(index) }) { Text("✕") }
                    }
                }
            }
        }

        // ── Add + Done ───────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { launchAddWidget() }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add widget")
            }
            Button(onClick = { commitAndExit() }, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}
