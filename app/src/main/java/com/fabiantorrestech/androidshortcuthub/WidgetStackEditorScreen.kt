package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    // When set, the next completed bind replaces widgets[index] instead of appending (Refresh).
    var pendingRefreshIndex by remember(widgetStackId) { mutableStateOf<Int?>(null) }
    // When set, the "re-create this widget?" confirm dialog is shown for that index.
    var refreshConfirmIndex by remember(widgetStackId) { mutableStateOf<Int?>(null) }

    // Deterministic result delivery for widget binds. Unlike an ON_RESUME observer, this callback
    // fires exactly once when BindWidgetActivity finishes — no matter how many config screens it
    // launched internally — and only for the launch WE started. The typed insertion is read from the
    // coordinator (avoids serializing the payload through the Intent).
    val addWidgetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val insertion = WidgetBindingCoordinator.consumeCompletedInsertion()
        val refreshIndex = pendingRefreshIndex
        if (insertion is TileInsertionEvent.WidgetAdded) {
            if (refreshIndex != null) {
                // Refresh: replace in place. The old appWidgetId is intentionally NOT deleted here so a
                // main-editor Discard can still restore it; the superseded id is reaped by orphan-cleanup.
                if (refreshIndex in widgets.indices) {
                    widgets[refreshIndex] = widgets[refreshIndex].copy(
                        appWidgetId = insertion.selection.appWidgetId,
                        providerComponent = insertion.selection.providerComponent,
                    )
                }
            } else {
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
        }
        // Clear on every result so a cancelled Refresh doesn't turn the next Add into a replace.
        pendingRefreshIndex = null
    }

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
        addWidgetLauncher.launch(
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

    // Refresh a widget in place: re-bind the SAME provider (skipping the picker via direct-bind) so
    // the completed insertion replaces widgets[index] rather than appending.
    fun launchRefresh(index: Int) {
        pendingRefreshIndex = index
        WidgetBindingCoordinator.startBinding()
        addWidgetLauncher.launch(
            BindWidgetActivity.createIntent(
                context,
                parentEditorState.gridRows,
                parentEditorState.gridColumns,
                hasVolumeSlider = true,
                hasBrightnessSlider = true,
                autoToggleOverlay = false,
                directProviderComponent = widgets[index].providerComponent,
            ),
        )
    }

    fun onRefreshClicked(index: Int) {
        // Only confirm when the widget is currently live (re-creating it may reset its settings);
        // a broken/unavailable widget has nothing to lose, so refresh it straight away.
        val live = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgets[index].appWidgetId) != null
        if (live) refreshConfirmIndex = index else launchRefresh(index)
    }

    val confirmIndex = refreshConfirmIndex
    if (confirmIndex != null) {
        AlertDialog(
            onDismissRequest = { refreshConfirmIndex = null },
            title = { Text("Re-create widget?") },
            text = { Text("This re-creates the widget in the same slot and may reset its current settings.") },
            confirmButton = {
                TextButton(onClick = {
                    refreshConfirmIndex = null
                    launchRefresh(confirmIndex)
                }) { Text("Re-create") }
            },
            dismissButton = {
                TextButton(onClick = { refreshConfirmIndex = null }) { Text("Cancel") }
            },
        )
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
                        val info = remember(widget.appWidgetId) {
                            AppWidgetManager.getInstance(context).getAppWidgetInfo(widget.appWidgetId)
                        }
                        val label = remember(widget.providerComponent, info != null) {
                            widgetProviderLabel(context, widget.providerComponent, available = info != null)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "page ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { move(index, -1) }, enabled = index > 0) { Text("↑") }
                        OutlinedButton(onClick = { move(index, 1) }, enabled = index < widgets.size - 1) { Text("↓") }
                        Box {
                            var menuExpanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                if (info?.configure != null) {
                                    DropdownMenuItem(
                                        text = { Text("Configure") },
                                        onClick = {
                                            menuExpanded = false
                                            activity?.let { act ->
                                                ShortcutHubWidgetHost.getInstance(context)
                                                    .startAppWidgetConfigureActivityForResult(
                                                        act,
                                                        widget.appWidgetId,
                                                        0,
                                                        CONFIGURE_WIDGET_REQUEST_CODE,
                                                        null,
                                                    )
                                            }
                                        },
                                    )
                                }
                                if (label.providerInstalled) {
                                    DropdownMenuItem(
                                        text = { Text("Refresh") },
                                        onClick = {
                                            menuExpanded = false
                                            onRefreshClicked(index)
                                        },
                                    )
                                }
                                HorizontalDivider()
                                // Per-widget override of the app-level "dismiss on widget
                                // activity" setting. Marked with a leading dot when active.
                                WidgetDismissMode.entries.forEach { mode ->
                                    val label = when (mode) {
                                        WidgetDismissMode.DEFAULT -> "Dismiss: use default"
                                        WidgetDismissMode.ALWAYS -> "Dismiss: always"
                                        WidgetDismissMode.NEVER -> "Dismiss: never"
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (widget.dismissOnActivity == mode) "●  $label" else "○  $label")
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            widgets[index] = widgets[index].copy(dismissOnActivity = mode)
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Remove") },
                                    onClick = {
                                        menuExpanded = false
                                        widgets.removeAt(index)
                                    },
                                )
                            }
                        }
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
