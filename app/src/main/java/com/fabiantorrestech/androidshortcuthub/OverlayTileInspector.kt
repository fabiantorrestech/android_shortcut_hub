package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    loadLaunchableApps: () -> List<LaunchableApp>,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
    fontEvents: Flow<TileFontSelection>,
    iconEvents: Flow<TileIconSelection>,
    modifier: Modifier = Modifier,
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
        }
        Text(typeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Size ${tile.columnSpan}×${tile.rowSpan}  at (${tile.row}, ${tile.column})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Rename ───────────────────────────────────────────────────────────
        var labelDraft by remember(tile.id, tile.customLabel) { mutableStateOf(tile.customLabel ?: "") }
        OutlinedTextField(
            value = labelDraft,
            onValueChange = { labelDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom label") },
            placeholder = { Text("Use default name") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                editorState.updateTile(tile.id) { it.copyWithLabel(labelDraft.trim().ifBlank { null }) }
            }) { Text("Save name") }
            OutlinedButton(onClick = {
                labelDraft = ""
                editorState.updateTile(tile.id) { it.copyWithLabel(null) }
            }) { Text("Reset name") }
        }

        // ── Move ─────────────────────────────────────────────────────────────
        Text("Move", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { editorState.moveTile(tile.id, -1, 0) }) { Text("↑") }
            OutlinedButton(onClick = { editorState.moveTile(tile.id, 1, 0) }) { Text("↓") }
            OutlinedButton(onClick = { editorState.moveTile(tile.id, 0, -1) }) { Text("←") }
            OutlinedButton(onClick = { editorState.moveTile(tile.id, 0, 1) }) { Text("→") }
        }

        // ── Resize ───────────────────────────────────────────────────────────
        Text("Span", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { editorState.resizeTile(tile.id, 1, 0) }) { Text("H+") }
            OutlinedButton(onClick = { editorState.resizeTile(tile.id, -1, 0) }) { Text("H-") }
            OutlinedButton(onClick = { editorState.resizeTile(tile.id, 0, 1) }) { Text("W+") }
            OutlinedButton(onClick = { editorState.resizeTile(tile.id, 0, -1) }) { Text("W-") }
        }

        // ── Text scale + Bold (AppTile + IntentTile only) ────────────────────
        if (tile !is WidgetTileState && tile !is SystemSliderTileState) {
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
                    loadLaunchableApps = loadLaunchableApps,
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
            }
            is SystemSliderTileState -> {
                SliderConfigControls(
                    config = tile.config,
                    onConfigChange = { newConfig ->
                        editorState.updateTile(tile.id) { (it as SystemSliderTileState).copy(config = newConfig) }
                    },
                )
            }
        }

        // ── Delete ────────────────────────────────────────────────────────────
        Button(
            onClick = { editorState.deleteTile(tile.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("Delete tile") }
    }
}

// ── App tile sub-inspector ───────────────────────────────────────────────────

@Composable
private fun AppTileInspectorControls(
    tile: AppTileState,
    editorState: OverlayEditorState,
    loadLaunchableApps: () -> List<LaunchableApp>,
    openFontPicker: (tileId: Int) -> Unit,
    openIconPicker: (tileId: Int) -> Unit,
) {
    var showAppChooser by remember { mutableStateOf(false) }
    var showMaterialIconPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showAppChooser = true },
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
        onChooseMaterialIcon = { showMaterialIconPicker = true },
    )

    if (showMaterialIconPicker) {
        InlineMaterialIconPicker(
            currentKey = tile.iconConfig.materialIconKey,
            onPick = { key ->
                editorState.updateTile(tile.id) { t ->
                    (t as AppTileState).copy(
                        iconConfig = t.iconConfig.copy(
                            source = AppTileIconSource.MATERIAL,
                            materialIconKey = key,
                        ),
                    )
                }
                showMaterialIconPicker = false
            },
            onDismiss = { showMaterialIconPicker = false },
        )
    }

    if (showAppChooser) {
        InlineAppChooser(
            loadLaunchableApps = loadLaunchableApps,
            onAppSelected = { app ->
                editorState.updateTile(tile.id) { (it as AppTileState).copy(app = app) }
                showAppChooser = false
            },
            onDismiss = { showAppChooser = false },
        )
    }
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

// ── Inline material icon picker ───────────────────────────────────────────────

@Composable
private fun InlineMaterialIconPicker(
    currentKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Material Icon: ${materialIconForKey(currentKey)?.label ?: "None"}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(materialIconOptions, key = { it.key }) { option ->
                OutlinedButton(
                    onClick = { onPick(option.key) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(imageVector = option.imageVector, contentDescription = option.label)
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
    }
}

// ── Inline app chooser ────────────────────────────────────────────────────────

@Composable
private fun InlineAppChooser(
    loadLaunchableApps: () -> List<LaunchableApp>,
    onAppSelected: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var customPackageInput by remember { mutableStateOf("") }
    var customPackageError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        isLoading = true
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadLaunchableApps() } }
            .onSuccess { apps = it }
        isLoading = false
    }

    val filtered = remember(apps, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) apps else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose app", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search") },
            singleLine = true,
        )
        if (isLoading) {
            Text("Loading apps...", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered.take(30)) { app ->
                    OutlinedButton(
                        onClick = { onAppSelected(app) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                app.componentName?.packageName ?: app.launchIntentPackage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Manual package name entry
        Text(
            "Or enter a package name",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = customPackageInput,
            onValueChange = { customPackageInput = it; customPackageError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Package name") },
            placeholder = { Text("com.example.app") },
            singleLine = true,
            isError = customPackageError != null,
            supportingText = customPackageError?.let { { Text(it) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val pkg = customPackageInput.trim()
                    if (pkg.isEmpty()) { customPackageError = "Enter a package name"; return@OutlinedButton }
                    val pm = context.packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    val component = launchIntent?.component
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrNull()
                    if (label == null && component == null) {
                        customPackageError = "Package not found on this device"
                        return@OutlinedButton
                    }
                    onAppSelected(LaunchableApp(label = label ?: pkg, componentName = component))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Use package") }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}

// ── Inline intent form ────────────────────────────────────────────────────────

@Composable
private fun InlineIntentForm(
    initial: IntentTileState,
    onSave: (IntentTileState) -> Unit,
    onDismiss: () -> Unit,
) {
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
            label = { Text("Component (optional)") },
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
                    onSave(
                        initial.copy(
                            intentAction = trimmedAction,
                            intentType = intentType,
                            intentPackage = pkg.trim().ifBlank { null },
                            intentComponent = component.trim().ifBlank { null },
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
