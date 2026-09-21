package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Inspector panel shown in the editor when a tile is selected.
 *
 * Provides controls for:
 * - Renaming a tile
 * - Moving and resizing it
 * - Per-tile text scale and bold (AppTile + IntentTile)
 * - Type-specific controls (app icon, intent edit, widget configure, slider config)
 * - Deleting the tile
 */
@Composable
internal fun OverlayTileInspector(
    editorState: OverlayEditorState,
    onConfigureWidget: (appWidgetId: Int) -> Unit,
    onPickApp: (tileId: Int) -> Unit,
    onPickMaterialIcon: (tileId: Int) -> Unit,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
    fontEvents: Flow<TileFontSelection>,
    iconEvents: Flow<TileIconSelection>,
    modifier: Modifier = Modifier,
    onEditScrollBox: (tileId: Int) -> Unit = {},
    onEditWidgetStack: (tileId: Int) -> Unit = {},
) {
    val context = LocalContext.current
    val selectedId = editorState.selectedTileId
    val tile = selectedId?.let { id -> editorState.tiles.firstOrNull { it.id == id } }

    // Consume font/icon pick events from the picker activities
    LaunchedEffect(Unit) {
        fontEvents.collectLatest { selection ->
            editorState.updateTile(selection.tileId) { it.copyWithFont(selection.fontUri, selection.fontName) }
        }
    }
    LaunchedEffect(Unit) {
        iconEvents.collectLatest { selection ->
            editorState.updateTile(selection.tileId) { current ->
                (current as? AppTileState)?.copy(
                    iconConfig = current.iconConfig.copy(
                        source = AppTileIconSource.CUSTOM,
                        customIconUri = selection.iconUri,
                        customIconName = selection.iconName,
                    ),
                ) ?: current
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (tile == null) {
            Text(
                text = "Tap a tile to select it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
            )
            return@Column
        }

        // ── Header ───────────────────────────────────────────────────────────
        val typeLabel = when (tile) {
            is AppTileState -> "App — ${tile.app.label}"
            is IntentTileState -> "Intent — ${tile.displayLabel}"
            is WidgetTileState -> "Widget — ${tile.displayLabel}"
            is SystemSliderTileState -> "${tile.config.sliderType.name.lowercase().replaceFirstChar { it.uppercase() }} Slider"
            is ScrollBoxTileState -> "Scrollbox — ${tile.displayLabel}"
            is WidgetStackTileState -> "Widget Stack — ${tile.displayLabel}"
        }
        Text(typeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Size ${tile.columnSpan}×${tile.rowSpan}  at (${tile.row}, ${tile.column})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Per-tile undo. It used to sit in the editor's bottom bar labelled "Cancel", next to
        // Save, where it read as "cancel the save" rather than "undo this one tile". It belongs
        // with the tile it acts on, and only shows when that tile differs from its saved version.
        if (editorState.isTileDirty(tile.id)) {
            TextButton(
                onClick = { editorState.revertTile(tile.id) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) { Text("Revert this tile") }
        }

        // ── Name ─────────────────────────────────────────────────────────────
        // The field used to be a single generic "Custom label" offered to every type except
        // sliders, which was misleading in both directions. Widgets, scrollboxes and widget stacks
        // never draw their label on the overlay, so naming one looked like it did nothing; sliders
        // *do* draw theirs in move mode but had no field at all. The label is still doing real work
        // for the types that do not render it - it is the tile's contentDescription for TalkBack,
        // and it names the tile in the editor - so it is kept and described honestly instead.
        var labelDraft by remember(tile.id) { mutableStateOf(tile.customLabel ?: "") }
        val labelIsDrawnOnTile = tile is AppTileState || tile is IntentTileState
        val appLabelHidden = tile is AppTileState &&
            tile.iconConfig.source != AppTileIconSource.NONE &&
            !tile.iconConfig.showLabel
        OutlinedTextField(
            value = labelDraft,
            onValueChange = {
                labelDraft = it
                editorState.updateTile(tile.id) { t -> t.copyWithLabel(it.trim().ifBlank { null }) }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (labelIsDrawnOnTile) "Label" else "Name") },
            placeholder = { Text("Use default name") },
            singleLine = true,
            supportingText = {
                Text(
                    when {
                        appLabelHidden ->
                            "Currently hidden — turn on \"Show label\" below to draw it on the tile."
                        labelIsDrawnOnTile -> "Shown on the tile."
                        else -> "Used in the editor and read out by TalkBack. Not drawn on the tile."
                    },
                )
            },
        )

        // ── Position and size ────────────────────────────────────────────────
        TileTransformControls(editorState = editorState, tile = tile)

        // ── Text scale + Bold (AppTile + IntentTile only) ────────────────────
        if (tile !is WidgetTileState && tile !is SystemSliderTileState && tile !is ScrollBoxTileState && tile !is WidgetStackTileState) {
            val scaleLabel = tile.customTextScale?.let { "${"%.1f".format(it)}×" } ?: "default"
            Text(
                "Text Scale ($scaleLabel)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    editorState.updateTile(tile.id) { t ->
                        val next = ((t.customTextScale ?: editorState.defaultTextScale) + TEXT_SCALE_STEP)
                            .coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
                        t.copyWithTextScale(next)
                    }
                }) { Text("S+") }
                OutlinedButton(onClick = {
                    editorState.updateTile(tile.id) { t ->
                        val next = ((t.customTextScale ?: editorState.defaultTextScale) - TEXT_SCALE_STEP)
                            .coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
                        t.copyWithTextScale(next)
                    }
                }) { Text("S-") }
                OutlinedButton(onClick = {
                    editorState.updateTile(tile.id) { it.copyWithTextScale(null) }
                }) { Text("S Reset") }
            }

            val boldLabel = tile.customBoldText?.let { if (it) "On" else "Off" } ?: "Default"
            Text(
                "Bold ($boldLabel)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { editorState.updateTile(tile.id) { it.copyWithBoldText(true) } }) { Text("Bold On") }
                OutlinedButton(onClick = { editorState.updateTile(tile.id) { it.copyWithBoldText(false) } }) { Text("Bold Off") }
                OutlinedButton(onClick = { editorState.updateTile(tile.id) { it.copyWithBoldText(null) } }) { Text("Bold Reset") }
            }
        }

        // ── Type-specific controls ────────────────────────────────────────────
        when (tile) {
            is AppTileState -> {
                AppTileInspectorControls(
                    tile = tile,
                    editorState = editorState,
                    onPickApp = onPickApp,
                    onPickMaterialIcon = onPickMaterialIcon,
                    openFontPicker = openFontPicker,
                    openIconPicker = openIconPicker,
                )
            }
            is IntentTileState -> {
                IntentTileInspectorControls(
                    tile = tile,
                    editorState = editorState,
                    openFontPicker = openFontPicker,
                )
            }
            is WidgetTileState -> {
                val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
                val hasConfig = remember(tile.appWidgetId) {
                    appWidgetManager.getAppWidgetInfo(tile.appWidgetId)?.configure != null
                }
                if (hasConfig) {
                    OutlinedButton(
                        onClick = { onConfigureWidget(tile.appWidgetId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Configure widget...") }
                }

                WidgetDismissModeControls(
                    mode = tile.dismissOnActivity,
                    onModeChange = { newMode ->
                        editorState.updateTile(tile.id) { (it as WidgetTileState).copy(dismissOnActivity = newMode) }
                    },
                )
            }
            is SystemSliderTileState -> {
                SliderConfigControls(
                    config = tile.config,
                    onConfigChange = { newConfig ->
                        editorState.updateTile(tile.id) { (it as SystemSliderTileState).copy(config = newConfig) }
                    },
                )
            }
            is ScrollBoxTileState -> {
                OutlinedButton(
                    onClick = { onEditScrollBox(tile.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Edit contents  ›") }

                Text("Scroll direction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val vertical = tile.scrollDirection == ScrollDirection.VERTICAL
                    ScrollBoxSelectButton("↕ Vertical", vertical) {
                        editorState.updateTile(tile.id) { t ->
                            val sb = t as ScrollBoxTileState
                            sb.copy(
                                scrollDirection = ScrollDirection.VERTICAL,
                                scrollbarEdge = if (sb.scrollbarEdge == ScrollbarEdge.TOP || sb.scrollbarEdge == ScrollbarEdge.BOTTOM) ScrollbarEdge.RIGHT else sb.scrollbarEdge,
                            )
                        }
                    }
                    ScrollBoxSelectButton("↔ Horizontal", !vertical) {
                        editorState.updateTile(tile.id) { t ->
                            val sb = t as ScrollBoxTileState
                            sb.copy(
                                scrollDirection = ScrollDirection.HORIZONTAL,
                                scrollbarEdge = if (sb.scrollbarEdge == ScrollbarEdge.LEFT || sb.scrollbarEdge == ScrollbarEdge.RIGHT) ScrollbarEdge.BOTTOM else sb.scrollbarEdge,
                            )
                        }
                    }
                }

                Text("Scrollbar edge", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val edges = if (tile.scrollDirection == ScrollDirection.VERTICAL) {
                        listOf("Left" to ScrollbarEdge.LEFT, "Right" to ScrollbarEdge.RIGHT)
                    } else {
                        listOf("Top" to ScrollbarEdge.TOP, "Bottom" to ScrollbarEdge.BOTTOM)
                    }
                    edges.forEach { (label, edge) ->
                        ScrollBoxSelectButton(label, tile.scrollbarEdge == edge) {
                            editorState.updateTile(tile.id) { t -> (t as ScrollBoxTileState).copy(scrollbarEdge = edge) }
                        }
                    }
                }
            }
            is WidgetStackTileState -> {
                OutlinedButton(
                    onClick = { onEditWidgetStack(tile.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Edit stack  ›") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show page dots", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = tile.showPageIndicator,
                        onCheckedChange = { checked ->
                            editorState.updateTile(tile.id) { t -> (t as WidgetStackTileState).copy(showPageIndicator = checked) }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Auto-rotate", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = tile.autoRotate,
                        onCheckedChange = { checked ->
                            editorState.updateTile(tile.id) { t -> (t as WidgetStackTileState).copy(autoRotate = checked) }
                        },
                    )
                }
                // The interval itself is set in the stack editor, alongside the widgets it pages
                // through. Say what it currently is so the switch is not an opaque on/off.
                if (tile.autoRotate) {
                    Text(
                        "Every ${tile.autoRotateSeconds}s — change in \"Edit stack\".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Delete ────────────────────────────────────────────────────────────
        // Confirmed, because deleting a container throws away everything inside it and there is no
        // undo short of leaving the editor without saving.
        var confirmDelete by remember(tile.id) { mutableStateOf(false) }
        Button(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Delete tile") }

        if (confirmDelete) {
            val childCount = when (tile) {
                is ScrollBoxTileState -> tile.children.size
                is WidgetStackTileState -> tile.widgets.size
                else -> 0
            }
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${tile.displayLabel}?") },
                text = {
                    Text(
                        if (childCount > 0) {
                            "This also deletes the $childCount ${if (childCount == 1) "tile" else "tiles"} inside it."
                        } else {
                            "This removes the tile from the layout."
                        },
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmDelete = false
                            editorState.deleteTile(tile.id)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * Three-way "dismiss the overlay when this widget is used" control, shared by the tile inspector and
 * the widget-stack editor. The "Use default" label spells out the current app-level setting so the
 * option isn't opaque.
 */
@Composable
internal fun WidgetDismissModeControls(
    mode: WidgetDismissMode,
    onModeChange: (WidgetDismissMode) -> Unit,
) {
    val context = LocalContext.current
    val globalDefault = remember { ShortcutHubSettings.load(context).dismissOnWidgetActivity }

    Text(
        "Dismiss on activity",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ScrollBoxSelectButton(
            label = if (globalDefault) "Default (On)" else "Default (Off)",
            selected = mode == WidgetDismissMode.DEFAULT,
        ) { onModeChange(WidgetDismissMode.DEFAULT) }
        ScrollBoxSelectButton("Always", mode == WidgetDismissMode.ALWAYS) {
            onModeChange(WidgetDismissMode.ALWAYS)
        }
        ScrollBoxSelectButton("Never", mode == WidgetDismissMode.NEVER) {
            onModeChange(WidgetDismissMode.NEVER)
        }
    }
    Text(
        "Tapping a button or row inside the widget closes the overlay. Scrolling doesn't.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Toggle-style OutlinedButton used by the scrollbox inspector for direction / edge selection. */
@Composable
private fun ScrollBoxSelectButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ── App tile sub-inspector ───────────────────────────────────────────────────

@Composable
private fun AppTileInspectorControls(
    tile: AppTileState,
    editorState: OverlayEditorState,
    onPickApp: (tileId: Int) -> Unit,
    onPickMaterialIcon: (tileId: Int) -> Unit,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
) {

    OutlinedButton(
        onClick = { onPickApp(tile.id) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Change app") }

    OutlinedButton(
        onClick = { openFontPicker(tile.id) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Font: ${tile.customFontName ?: "Default"}") }

    AppTileIconControls(
        config = tile.iconConfig,
        onConfigChange = { newConfig ->
            editorState.updateTile(tile.id) { (it as AppTileState).copy(iconConfig = newConfig) }
        },
        onIconScaleUp = {
            editorState.updateTile(tile.id) { t ->
                val app = t as AppTileState
                val newScale = ((app.iconConfig.iconScale ?: 1f) + ICON_SCALE_STEP).coerceIn(ICON_SCALE_MIN, ICON_SCALE_MAX)
                app.copy(iconConfig = app.iconConfig.copy(iconScale = newScale))
            }
        },
        onIconScaleDown = {
            editorState.updateTile(tile.id) { t ->
                val app = t as AppTileState
                val newScale = ((app.iconConfig.iconScale ?: 1f) - ICON_SCALE_STEP).coerceIn(ICON_SCALE_MIN, ICON_SCALE_MAX)
                app.copy(iconConfig = app.iconConfig.copy(iconScale = newScale))
            }
        },
        onIconScaleReset = {
            editorState.updateTile(tile.id) { t ->
                (t as AppTileState).copy(iconConfig = t.iconConfig.copy(iconScale = null))
            }
        },
        onPickCustomIcon = { openIconPicker(tile.id) },
        onChooseMaterialIcon = { onPickMaterialIcon(tile.id) },
    )

}

// ── Intent tile sub-inspector ────────────────────────────────────────────────

@Composable
private fun IntentTileInspectorControls(
    tile: IntentTileState,
    editorState: OverlayEditorState,
    openFontPicker: (tileId: Int) -> Unit,
) {
    var showForm by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { openFontPicker(tile.id) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Font: ${tile.customFontName ?: "Default"}") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Require unlock to launch", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = tile.unlockToLaunch,
            onCheckedChange = { enabled ->
                editorState.updateTile(tile.id) { (it as IntentTileState).copy(unlockToLaunch = enabled) }
            },
        )
    }

    OutlinedButton(
        onClick = { showForm = true },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Edit intent...") }

    if (showForm) {
        InlineIntentForm(
            initial = tile,
            onSave = { updated ->
                editorState.updateTile(tile.id) { updated }
                showForm = false
            },
            onDismiss = { showForm = false },
        )
    }
}


// ── Inline app chooser ────────────────────────────────────────────────────────


// ── Inline intent form ────────────────────────────────────────────────────────

@Composable
private fun InlineIntentForm(
    initial: IntentTileState,
    onSave: (IntentTileState) -> Unit,
    onDismiss: () -> Unit,
) {
    // Back closes the form rather than falling through to the editor behind it. Matches the
    // form's own Cancel, so an in-progress edit is dropped the same way either route.
    BackHandler { onDismiss() }

    var action by remember { mutableStateOf(initial.intentAction) }
    var intentType by remember { mutableStateOf(initial.intentType) }
    var pkg by remember { mutableStateOf(initial.intentPackage ?: "") }
    var component by remember { mutableStateOf(initial.intentComponent ?: "") }
    var dataUri by remember { mutableStateOf(initial.intentDataUri ?: "") }
    val extras = remember {
        mutableStateListOf<Pair<String, String>>().apply { addAll(initial.intentExtras.entries.map { it.key to it.value }) }
    }
    var newExtraKey by remember { mutableStateOf("") }
    var newExtraValue by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val componentCheckContext = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Edit intent", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        Text("Type", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IntentType.entries.forEach { type ->
                val label = when (type) {
                    IntentType.ACTIVITY -> "Activity"
                    IntentType.BROADCAST_RECEIVER -> "Broadcast"
                    IntentType.SERVICE -> "Service"
                }
                if (intentType == type) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { intentType = type }) { Text(label) }
                }
            }
        }

        OutlinedTextField(
            value = action,
            onValueChange = { action = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Action (required)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = pkg,
            onValueChange = { pkg = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Package (optional)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = component,
            onValueChange = { component = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Component / Class (optional)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = dataUri,
            onValueChange = { dataUri = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Data URI (optional)") },
            singleLine = true,
        )

        // ── Extras ───────────────────────────────────────────────────────────
        Text(
            "Extras",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        extras.forEachIndexed { index, (key, value) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$key = $value",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { extras.removeAt(index) }) { Text("✕") }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newExtraKey,
                onValueChange = { newExtraKey = it },
                modifier = Modifier.weight(1f),
                label = { Text("Key") },
                singleLine = true,
            )
            OutlinedTextField(
                value = newExtraValue,
                onValueChange = { newExtraValue = it },
                modifier = Modifier.weight(1f),
                label = { Text("Value") },
                singleLine = true,
            )
            OutlinedButton(onClick = {
                val k = newExtraKey.trim()
                if (k.isNotEmpty()) {
                    extras.removeAll { it.first == k }
                    extras.add(k to newExtraValue.trim())
                    newExtraKey = ""
                    newExtraValue = ""
                }
            }) { Text("+") }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val trimmedAction = action.trim()
                    if (trimmedAction.isEmpty()) { error = "Action is required"; return@Button }
                    val componentProblem = describeIntentTargetProblem(
                        context = componentCheckContext,
                        rawComponent = component,
                        rawPackage = pkg,
                        type = intentType,
                    )
                    if (componentProblem != null) { error = componentProblem; return@Button }
                    onSave(
                        initial.copy(
                            intentAction = trimmedAction,
                            intentType = intentType,
                            intentPackage = pkg.trim().ifBlank { null },
                            intentComponent = normalizeIntentComponent(component, pkg),
                            intentDataUri = dataUri.trim().ifBlank { null },
                            intentExtras = extras.filter { it.first.isNotBlank() }.toMap(),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}
