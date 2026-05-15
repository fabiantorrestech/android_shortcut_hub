package com.fabiantorrestech.androidshortcuthub

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fabiantorrestech.androidshortcuthub.ui.theme.ShortcutHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as? ShortcutHubApplication)?.prepareOverlayRuntimeIfEligible()
        enableEdgeToEdge()
        setContent {
            ShortcutHubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var hasOverlayPermission by remember { mutableStateOf(ShortcutHubOverlayService.canDrawOverlays(context)) }
    var isAccessibilityServiceEnabled by remember { mutableStateOf(isShortcutHubAccessibilityServiceEnabled(context)) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(isIgnoringBatteryOptimizationRestrictions(context)) }
    var areNotificationsEnabled by remember { mutableStateOf(areAppNotificationsEnabled(context)) }
    var hasWriteSettingsPermission by remember { mutableStateOf(android.provider.Settings.System.canWrite(context)) }
    var config by remember { mutableStateOf(ShortcutHubSettings.load(context)) }
    var grayscaleConfig by remember { mutableStateOf(GrayscaleRepository.load(context)) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var autoBackupEnabled by remember { mutableStateOf(BackupPrefs.isEnabled(context)) }
    var autoBackupDirectoryUri by remember { mutableStateOf(BackupPrefs.getDirectoryUri(context)) }
    val coroutineScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val json = withContext(Dispatchers.IO) { BackupManager.buildBackupJson(context) }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(json.toByteArray(Charsets.UTF_8))
                        }
                        true
                    }.getOrDefault(false)
                }
                settingsMessage = if (ok) "Backup saved successfully." else "Backup failed — could not write file."
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreConfirm = true
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistTreeUriPermission(context, uri)
            autoBackupDirectoryUri = uri
            settingsMessage = "Backup folder set."
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = resolveDisplayName(context.contentResolver, uri)
            config = config.copy(
                defaultFontUri = uri.toString(),
                defaultFontName = displayName ?: uri.lastPathSegment ?: "Selected font",
            )
            ShortcutHubSettings.save(context, config)
            settingsMessage = "Default font updated"
        }
    }

    // Separate launcher used by the Layout editor tab to pick custom tile icons.
    // The result is dispatched via the shared overlay icon event flow so the inspector
    // can consume it regardless of which component is currently active.
    var pendingEditorIconTileId by remember { mutableStateOf<Int?>(null) }
    val editorIconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tileId = pendingEditorIconTileId ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = resolveDisplayName(context.contentResolver, uri) ?: uri.lastPathSegment ?: "icon"
            ShortcutHubOverlayService.dispatchTileIconPicked(tileId, uri.toString(), displayName)
        }
        pendingEditorIconTileId = null
    }

    // Separate launcher used by the Layout editor tab to pick custom tile fonts.
    var pendingEditorFontTileId by remember { mutableStateOf<Int?>(null) }
    val editorFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tileId = pendingEditorFontTileId ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = resolveDisplayName(context.contentResolver, uri) ?: uri.lastPathSegment ?: "font"
            ShortcutHubOverlayService.dispatchTileFontPicked(tileId, uri.toString(), displayName)
        }
        pendingEditorFontTileId = null
    }

    // Launcher for the Layout editor's default font picker (appearance popup).
    val editorDefaultFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val displayName = resolveDisplayName(context.contentResolver, uri) ?: uri.lastPathSegment ?: "font"
            OverlayEditorState.dispatchDefaultFontPicked(uri.toString(), displayName)
        }
    }

    val intentDetails = remember(context.packageName) {
        listOf(
            "Action: ${ShortcutHubOverlayService.ACTION_TOGGLE_OVERLAY}",
            "Package: ${context.packageName}",
            "Class: ${context.packageName}.InvokeShortcutHubActivity",
            "Tasker target: Activity",
        )
    }

    DisposableEffect(activity, context) {
        val lifecycle = activity?.lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = ShortcutHubOverlayService.canDrawOverlays(context)
                isAccessibilityServiceEnabled = isShortcutHubAccessibilityServiceEnabled(context)
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizationRestrictions(context)
                areNotificationsEnabled = areAppNotificationsEnabled(context)
                hasWriteSettingsPermission = android.provider.Settings.System.canWrite(context)
                config = ShortcutHubSettings.load(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = {
                showRestoreConfirm = false
                pendingRestoreUri = null
            },
            title = { Text("Restore Backup?") },
            text = {
                Text(
                    "This will overwrite your current settings and layout. " +
                        "Toggle the overlay off and on after restoring to apply layout changes."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val uri = pendingRestoreUri
                    showRestoreConfirm = false
                    pendingRestoreUri = null
                    if (uri != null) {
                        coroutineScope.launch {
                            val json = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)
                                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                                }.getOrNull()
                            }
                            if (json == null) {
                                settingsMessage = "Restore failed — could not read file."
                                return@launch
                            }
                            val ok = withContext(Dispatchers.IO) {
                                BackupManager.restoreFromJson(context, json)
                            }
                            if (ok) {
                                config = ShortcutHubSettings.load(context)
                                settingsMessage = "Restored. Toggle the overlay to apply layout changes."
                            } else {
                                settingsMessage = "Restore failed — invalid backup file."
                            }
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    pendingRestoreUri = null
                }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectedTab != 3) {
            Text(
                text = "Shortcut Hub",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
            ) {
                listOf("Setup", "Behavior", "Grayscale", "Layout", "Backup").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            settingsMessage = null
                        },
                        text = { Text(title) },
                    )
                }
            }
        }
        when (selectedTab) {
            0 -> SetupTab(
                hasOverlayPermission = hasOverlayPermission,
                isAccessibilityServiceEnabled = isAccessibilityServiceEnabled,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                areNotificationsEnabled = areNotificationsEnabled,
                hasWriteSettingsPermission = hasWriteSettingsPermission,
                config = config,
                intentDetails = intentDetails,
                settingsMessage = settingsMessage,
                onToggleOverlay = {
                    if (ShortcutHubOverlayService.canDrawOverlays(context)) {
                        routeShortcutHubToggle(context)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }
                },
                onDismissAccessibilityBanner = {
                    config = config.copy(dismissAccessibilityBanner = true)
                    ShortcutHubSettings.save(context, config)
                    settingsMessage = "Recommendation dismissed"
                },
                onGrantOverlay = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                },
                onGrantAccessibility = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onGrantBattery = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                },
                onGrantNotifications = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                },
                onGrantWriteSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
            1 -> BehaviorTab(
                config = config,
                onConfigChange = { updated ->
                    config = updated
                    ShortcutHubSettings.save(context, updated)
                },
                isAccessibilityServiceEnabled = isAccessibilityServiceEnabled,
            )
            2 -> GrayscaleTab(
                grayscaleConfig = grayscaleConfig,
                onConfigChange = { updated ->
                    grayscaleConfig = updated
                    GrayscaleRepository.save(context, updated)
                },
            )
            3 -> LayoutTab(
                onBack = { selectedTab = 0; settingsMessage = null },
                onOpenFontPicker = { tileId ->
                    pendingEditorFontTileId = tileId
                    editorFontPicker.launch(arrayOf("font/*", "application/octet-stream"))
                },
                onOpenIconPicker = { tileId ->
                    pendingEditorIconTileId = tileId
                    editorIconPicker.launch(arrayOf("image/*"))
                },
                onOpenDefaultFontPicker = {
                    editorDefaultFontPicker.launch(arrayOf("font/*", "application/octet-stream"))
                },
                onSaveSettings = { committedState ->
                    config = config.copy(
                        gridRows = committedState.gridRows,
                        gridColumns = committedState.gridColumns,
                        overlayBackgroundAlpha = committedState.overlayBackgroundAlpha,
                        defaultTextScale = committedState.defaultTextScale,
                        defaultBoldText = committedState.defaultBoldText,
                        defaultFontUri = committedState.defaultFontUri,
                        defaultFontName = committedState.defaultFontName,
                        defaultTextColorMode = committedState.defaultTextColorMode,
                        defaultTextColorHex = committedState.defaultTextColorHex,
                    )
                    ShortcutHubSettings.save(context, config)
                },
            )
            4 -> BackupTab(
                statusMessage = settingsMessage,
                autoBackupEnabled = autoBackupEnabled,
                autoBackupDirectoryUri = autoBackupDirectoryUri,
                onToggleAutoBackup = { enabled ->
                    autoBackupEnabled = enabled
                    BackupPrefs.setEnabled(context, enabled)
                },
                onPickDirectory = { directoryPicker.launch(null) },
                onExport = {
                    settingsMessage = null
                    exportLauncher.launch("shortcut_hub_backup.json")
                },
                onImport = {
                    settingsMessage = null
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                },
            )
        }
    }
}

@Composable
private fun SetupTab(
    hasOverlayPermission: Boolean,
    isAccessibilityServiceEnabled: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    areNotificationsEnabled: Boolean,
    hasWriteSettingsPermission: Boolean,
    config: ShortcutHubConfig,
    intentDetails: List<String>,
    settingsMessage: String?,
    onToggleOverlay: () -> Unit,
    onDismissAccessibilityBanner: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantBattery: () -> Unit,
    onGrantNotifications: () -> Unit,
    onGrantWriteSettings: () -> Unit,
) {
    val shouldShowAccessibilityBanner = !isAccessibilityServiceEnabled && !config.dismissAccessibilityBanner
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onToggleOverlay, modifier = Modifier.fillMaxWidth()) {
            Text("Toggle Overlay")
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                PermissionRow(
                    title = "Draw over other apps",
                    granted = hasOverlayPermission,
                    actionLabel = "Open Settings",
                    onActionClick = onGrantOverlay,
                )
                PermissionRow(
                    title = "Accessibility service",
                    granted = isAccessibilityServiceEnabled,
                    actionLabel = "Open Settings",
                    onActionClick = onGrantAccessibility,
                    badgeText = if (shouldShowAccessibilityBanner) "Recommended" else null,
                    description = if (shouldShowAccessibilityBanner) {
                        "Required for lockscreen widgets and improves lockscreen overlay quality. Other features work without it."
                    } else null,
                    secondaryActionLabel = if (shouldShowAccessibilityBanner) "Dismiss" else null,
                    onSecondaryActionClick = if (shouldShowAccessibilityBanner) onDismissAccessibilityBanner else null,
                )
                PermissionRow(
                    title = "Unrestricted battery",
                    granted = isIgnoringBatteryOptimizations,
                    actionLabel = "Open Settings",
                    onActionClick = onGrantBattery,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "Notifications",
                        granted = areNotificationsEnabled,
                        actionLabel = "Open Settings",
                        onActionClick = onGrantNotifications,
                    )
                }
                PermissionRow(
                    title = "Modify system settings",
                    granted = hasWriteSettingsPermission,
                    actionLabel = "Open Settings",
                    onActionClick = onGrantWriteSettings,
                    description = if (!hasWriteSettingsPermission) "Required for brightness slider" else null,
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Intent Details", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        intentDetails.forEach { line -> Text(line) }
                    }
                }
            }
        }
        HelpCard(
            title = "Key Mapper",
            bodyLines = listOf(
                "Option 1: use Launch app shortcut.",
                "Pick this app, then choose the toggle shortcut.",
                "Option 2: use Send Intent.",
                "Use the intent details above exactly as shown.",
            ),
        )
        HelpCard(
            title = "MacroDroid",
            bodyLines = listOf(
                "Use the Intent action.",
                "Set an explicit component with the package and class above.",
                "Use the action string above.",
            ),
        )
        HelpCard(
            title = "Tasker",
            bodyLines = listOf(
                "Use Send Intent.",
                "Set Action, Package, and Class from the intent details above.",
                "Set Target to Activity.",
            ),
        )
        settingsMessage?.let { Card(modifier = Modifier.fillMaxWidth()) { Text(it, modifier = Modifier.padding(16.dp)) } }
    }
}

@Composable
private fun GridTab(
    config: ShortcutHubConfig,
    settingsMessage: String?,
    onSaveGrid: (Int, Int) -> Unit,
    onSaveOpacity: (Float) -> Unit,
) {
    var rowsInput by remember(config.gridRows) { mutableStateOf(config.gridRows.toString()) }
    var columnsInput by remember(config.gridColumns) { mutableStateOf(config.gridColumns.toString()) }
    var overlayBackgroundAlpha by remember(config.overlayBackgroundAlpha) { mutableStateOf(config.overlayBackgroundAlpha) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Grid Size", style = MaterialTheme.typography.titleMedium)
                Text("Shrinking the grid removes out-of-bounds tiles.")
                OutlinedTextField(
                    value = rowsInput,
                    onValueChange = { rowsInput = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Rows (1–24)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = columnsInput,
                    onValueChange = { columnsInput = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Columns (1–16)") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        val rows = rowsInput.toIntOrNull()?.coerceIn(1, 24) ?: config.gridRows
                        val columns = columnsInput.toIntOrNull()?.coerceIn(1, 16) ?: config.gridColumns
                        rowsInput = rows.toString()
                        columnsInput = columns.toString()
                        onSaveGrid(rows, columns)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Grid Size")
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Background Opacity", style = MaterialTheme.typography.titleMedium)
                Text("${"%.0f".format(overlayBackgroundAlpha * 100)}%")
                Slider(
                    value = overlayBackgroundAlpha,
                    onValueChange = { overlayBackgroundAlpha = it },
                    valueRange = 0f..0.9f,
                )
                OutlinedButton(
                    onClick = { onSaveOpacity(overlayBackgroundAlpha) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Opacity")
                }
            }
        }
        settingsMessage?.let { Card(modifier = Modifier.fillMaxWidth()) { Text(it, modifier = Modifier.padding(16.dp)) } }
    }
}

@Composable
private fun AppearanceTab(
    config: ShortcutHubConfig,
    settingsMessage: String?,
    onSaveTextSize: (Float) -> Unit,
    onToggleBold: (Boolean) -> Unit,
    onPickFont: () -> Unit,
    onClearFont: () -> Unit,
    onSaveTextColor: (DefaultTextColorMode, String?) -> Unit,
) {
    var defaultTextScale by remember(config.defaultTextScale) { mutableStateOf(config.defaultTextScale) }
    var defaultTextColorMode by remember(config.defaultTextColorMode) { mutableStateOf(config.defaultTextColorMode) }
    var defaultTextColorHexInput by remember(config.defaultTextColorHex) { mutableStateOf(config.defaultTextColorHex ?: "") }

    val normalizedColorHex = remember(defaultTextColorHexInput) { normalizeHexColor(defaultTextColorHexInput) }
    val customHexValid = defaultTextColorHexInput.isBlank() || normalizedColorHex != null
    val previewTextColor = when (defaultTextColorMode) {
        DefaultTextColorMode.SYSTEM -> MaterialTheme.colorScheme.onSurface
        DefaultTextColorMode.BLACK -> Color.Black
        DefaultTextColorMode.WHITE -> Color.White
        DefaultTextColorMode.CUSTOM -> normalizedColorHex?.toComposeColor() ?: MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Typography", style = MaterialTheme.typography.titleMedium)
                Text("Text size: ${"%.2f".format(defaultTextScale)}x")
                Slider(value = defaultTextScale, onValueChange = { defaultTextScale = it }, valueRange = 0.5f..3.0f)
                OutlinedButton(onClick = { onSaveTextSize(defaultTextScale) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save Text Size")
                }
                SettingToggleRow(
                    label = "Bold text",
                    checked = config.defaultBoldText,
                    onCheckedChange = onToggleBold,
                )
                Text(
                    text = "Font: ${config.defaultFontName ?: "System default"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onPickFont, modifier = Modifier.weight(1f)) {
                        Text("Choose Font")
                    }
                    OutlinedButton(onClick = onClearFont, modifier = Modifier.weight(1f)) {
                        Text("Clear Font")
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Text Color", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorModeButton(
                        label = "System",
                        selected = defaultTextColorMode == DefaultTextColorMode.SYSTEM,
                        modifier = Modifier.weight(1f),
                        onClick = { defaultTextColorMode = DefaultTextColorMode.SYSTEM },
                    )
                    ColorModeButton(
                        label = "Black",
                        selected = defaultTextColorMode == DefaultTextColorMode.BLACK,
                        modifier = Modifier.weight(1f),
                        onClick = { defaultTextColorMode = DefaultTextColorMode.BLACK },
                    )
                    ColorModeButton(
                        label = "White",
                        selected = defaultTextColorMode == DefaultTextColorMode.WHITE,
                        modifier = Modifier.weight(1f),
                        onClick = { defaultTextColorMode = DefaultTextColorMode.WHITE },
                    )
                    ColorModeButton(
                        label = "Custom",
                        selected = defaultTextColorMode == DefaultTextColorMode.CUSTOM,
                        modifier = Modifier.weight(1f),
                        onClick = { defaultTextColorMode = DefaultTextColorMode.CUSTOM },
                    )
                }
                if (defaultTextColorMode == DefaultTextColorMode.CUSTOM) {
                    OutlinedTextField(
                        value = defaultTextColorHexInput,
                        onValueChange = { defaultTextColorHexInput = it.trim().take(9) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Hex color") },
                        placeholder = { Text("#FFFFFF") },
                        singleLine = true,
                        supportingText = {
                            Text(if (customHexValid) normalizedColorHex ?: "Use #RRGGBB or #AARRGGBB" else "Invalid hex")
                        },
                        isError = !customHexValid,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(modifier = Modifier.size(36.dp), shape = MaterialTheme.shapes.medium, color = previewTextColor) {}
                    Text("Preview", color = previewTextColor, style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedButton(
                    onClick = {
                        if (defaultTextColorMode == DefaultTextColorMode.CUSTOM && normalizedColorHex == null) return@OutlinedButton
                        val hex = if (defaultTextColorMode == DefaultTextColorMode.CUSTOM) normalizedColorHex else null
                        onSaveTextColor(defaultTextColorMode, hex)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Text Color")
                }
            }
        }
        settingsMessage?.let { Card(modifier = Modifier.fillMaxWidth()) { Text(it, modifier = Modifier.padding(16.dp)) } }
    }
}

@Composable
private fun BehaviorTab(
    config: ShortcutHubConfig,
    onConfigChange: (ShortcutHubConfig) -> Unit,
    isAccessibilityServiceEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Behavior", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                SettingToggleRow(
                    label = "Cover status bar",
                    description = if (isAccessibilityServiceEnabled) "Overlay will appear above the status bar"
                                  else "Requires Accessibility Service to be enabled",
                    checked = config.useAccessibilityService,
                    onCheckedChange = { onConfigChange(config.copy(useAccessibilityService = it)) },
                    enabled = isAccessibilityServiceEnabled,
                )
                SettingToggleRow(
                    label = "Show overlay over lockscreen",
                    checked = config.showOverLockscreen,
                    onCheckedChange = { onConfigChange(config.copy(showOverLockscreen = it)) },
                )
                SettingToggleRow(
                    label = "Dismiss overlay when screen turns off",
                    checked = config.dismissOnScreenOff,
                    onCheckedChange = { onConfigChange(config.copy(dismissOnScreenOff = it)) },
                )
                SettingToggleRow(
                    label = "Haptic feedback on tile press",
                    checked = config.hapticFeedbackEnabled,
                    onCheckedChange = { onConfigChange(config.copy(hapticFeedbackEnabled = it)) },
                )
                SettingToggleRow(
                    label = "Lock panel handle position",
                    checked = config.panelHandleLocked,
                    onCheckedChange = { onConfigChange(config.copy(panelHandleLocked = it)) },
                )
                SettingToggleRow(
                    label = "Show overlay menu handle",
                    checked = config.showPanelHandle,
                    onCheckedChange = { onConfigChange(config.copy(showPanelHandle = it)) },
                )
            }
        }
    }
}

@Composable
private fun ColorModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f),
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = if (enabled) 0.6f else 0.38f),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    description: String? = null,
    secondaryActionLabel: String? = null,
    onSecondaryActionClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            PermissionStatusChip(granted = granted)
            badgeText?.let { RecommendationBadge(it) }
        }
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onActionClick, modifier = Modifier.weight(1f)) { Text(actionLabel) }
            if (secondaryActionLabel != null && onSecondaryActionClick != null) {
                TextButton(onClick = onSecondaryActionClick) { Text(secondaryActionLabel) }
            }
        }
    }
}

@Composable
private fun PermissionStatusChip(granted: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (granted) Color(0xFFDBF5E6) else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (granted) Color(0xFF175B35) else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = if (granted) "Granted" else "Not granted",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RecommendationBadge(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = Color(0xFFFFE9B3), contentColor = Color(0xFF5F4300)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HelpCard(title: String, bodyLines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            bodyLines.forEach { Text(it) }
        }
    }
}

@Composable
private fun BackupTab(
    statusMessage: String?,
    autoBackupEnabled: Boolean,
    autoBackupDirectoryUri: Uri?,
    onToggleAutoBackup: (Boolean) -> Unit,
    onPickDirectory: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    var lastAutoBackupMs by remember { mutableStateOf<Long?>(null) }
    var directoryDisplayName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(autoBackupDirectoryUri) {
        lastAutoBackupMs = withContext(Dispatchers.IO) {
            BackupManager.queryAutoBackupLastModifiedMs(context)
        }
        directoryDisplayName = withContext(Dispatchers.IO) {
            BackupPrefs.getDirectoryDisplayName(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Manual Backup & Restore", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Save or load a backup file containing your settings and layout. You choose where the file is saved.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("Create Backup")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from File")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Auto-Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When enabled, your settings and layout are automatically backed up 10 minutes after " +
                        "the last change. The backup is written to a folder you choose, creating a " +
                        "shortcut_hub_autobackup/ subfolder with the backup file inside it. " +
                        "Point a sync service (e.g. DriveSync, FolderSync) at that folder to keep it synchronized.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingToggleRow(
                    label = "Enable auto-backup",
                    checked = autoBackupEnabled,
                    onCheckedChange = onToggleAutoBackup,
                )
                OutlinedButton(onClick = onPickDirectory, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose Backup Folder")
                }
                if (autoBackupDirectoryUri != null) {
                    Text(
                        text = "Folder: ${directoryDisplayName ?: "…"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (lastAutoBackupMs != null)
                            "Last auto-backup: ${formatBackupTimestamp(lastAutoBackupMs!!)}"
                        else
                            "Last auto-backup: Never",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = "No folder selected. Auto-backup will not run until a folder is chosen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        statusMessage?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private fun formatBackupTimestamp(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))

private fun String.toComposeColor(): Color = Color(android.graphics.Color.parseColor(this))

private fun isShortcutHubAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == "${context.packageName}.ShortcutHubAccessibilityService" }
}

private fun isIgnoringBatteryOptimizationRestrictions(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun areAppNotificationsEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    return nm.areNotificationsEnabled()
}

private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

/**
 * Layout tab — wraps [OverlayEditorScreen] with a persistent [OverlayEditorState].
 *
 * Font and icon pickers are delegated to the launchers already registered in [MainScreen]
 * since activity result launchers must be registered before the activity starts.
 */
@Composable
private fun LayoutTab(
    onBack: () -> Unit,
    onOpenFontPicker: (tileId: Int) -> Unit,
    onOpenIconPicker: (tileId: Int) -> Unit,
    onOpenDefaultFontPicker: () -> Unit,
    onSaveSettings: (OverlayUiState) -> Unit,
) {
    val context = LocalContext.current
    // remember { } so that draft state persists while the user switches between tabs
    val editorState = remember {
        OverlayEditorState(OverlayStateRepository.load(context))
    }

    OverlayEditorScreen(
        editorState = editorState,
        onBack = onBack,
        onSave = { committedState ->
            OverlayStateRepository.save(context, committedState)
            onSaveSettings(committedState)
            Toast.makeText(context, "Layout saved. Toggle the overlay to apply.", Toast.LENGTH_SHORT).show()
            onBack()
        },
        onDiscard = { onBack() },
        openFontPicker = onOpenFontPicker,
        openIconPicker = onOpenIconPicker,
        openDefaultFontPicker = onOpenDefaultFontPicker,
        fontEvents = ShortcutHubOverlayService.tileFontSelectionEvents(),
        iconEvents = ShortcutHubOverlayService.tileIconSelectionEvents(),
    )
}

@Composable
private fun GrayscaleTab(
    grayscaleConfig: GrayscaleConfig,
    onConfigChange: (GrayscaleConfig) -> Unit,
) {
    val context = LocalContext.current
    var showAppChooser by remember { mutableStateOf(false) }
    var availableApps by remember { mutableStateOf<List<GrayscaleAppEntry>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }

    val installedPackages = remember {
        context.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
    }

    val activeList = when (grayscaleConfig.activeMode) {
        GrayscaleFilterMode.WHITELIST -> grayscaleConfig.whitelistApps
        GrayscaleFilterMode.BLACKLIST -> grayscaleConfig.blacklistApps
    }
    val activePackages = remember(activeList) { activeList.map { it.packageName }.toSet() }

    val filteredForChooser = remember(availableApps, activePackages, appSearchQuery) {
        val q = appSearchQuery.trim()
        availableApps
            .filter { it.packageName !in activePackages }
            .let { list ->
                if (q.isEmpty()) list
                else list.filter { a ->
                    a.label.contains(q, ignoreCase = true) || a.packageName.contains(q, ignoreCase = true)
                }
            }
    }

    LaunchedEffect(showAppChooser) {
        if (showAppChooser && availableApps.isEmpty() && !isLoadingApps) {
            isLoadingApps = true
            availableApps = withContext(Dispatchers.IO) {
                context.packageManager.getInstalledApplications(0)
                    .mapNotNull { info ->
                        context.packageManager.getLaunchIntentForPackage(info.packageName)
                            ?: return@mapNotNull null
                        val label = context.packageManager.getApplicationLabel(info)
                            ?.toString()?.ifBlank { info.packageName } ?: info.packageName
                        GrayscaleAppEntry(packageName = info.packageName, label = label)
                    }
                    .sortedBy { it.label.lowercase() }
            }
            isLoadingApps = false
        }
    }

    fun updateActiveList(newList: List<GrayscaleAppEntry>) {
        onConfigChange(
            when (grayscaleConfig.activeMode) {
                GrayscaleFilterMode.WHITELIST -> grayscaleConfig.copy(whitelistApps = newList)
                GrayscaleFilterMode.BLACKLIST -> grayscaleConfig.copy(blacklistApps = newList)
            },
        )
    }

    if (showAppChooser) {
        AlertDialog(
            onDismissRequest = { showAppChooser = false; appSearchQuery = "" },
            title = {
                Text("Add to ${if (grayscaleConfig.activeMode == GrayscaleFilterMode.WHITELIST) "Whitelist" else "Blacklist"}")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        label = { Text("Search") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        isLoadingApps -> Text(
                            "Loading apps…",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        filteredForChooser.isEmpty() -> Text(
                            if (availableApps.isEmpty()) "No apps found." else "All apps already added.",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn {
                            items(filteredForChooser, key = { it.packageName }) { app ->
                                TextButton(
                                    onClick = { updateActiveList(activeList + app) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAppChooser = false; appSearchQuery = "" }) { Text("Done") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Enable toggle ────────────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Grayscale Background", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enable grayscale background")
                    Switch(
                        checked = false,
                        onCheckedChange = {},
                        enabled = false,
                    )
                }
                Text(
                    "Coming soon — grayscale background is not yet available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (false) {
            // ── Capture mode ─────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Capture Mode",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = grayscaleConfig.captureMode == GrayscaleMode.SIMPLE,
                            onClick = { onConfigChange(grayscaleConfig.copy(captureMode = GrayscaleMode.SIMPLE)) },
                        )
                        Column {
                            Text("Simple (snapshot)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Takes a one-shot screenshot when the overlay opens. No permission dialog, no battery overhead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = grayscaleConfig.captureMode == GrayscaleMode.ADVANCED,
                            onClick = { onConfigChange(grayscaleConfig.copy(captureMode = GrayscaleMode.ADVANCED)) },
                        )
                        Column {
                            Text("Advanced (live)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Streams the screen at 30 fps so the background updates in real time (e.g. maps, video). Requires a one-time screen-capture permission per app session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Filter mode ──────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Filter Mode",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = grayscaleConfig.activeMode == GrayscaleFilterMode.BLACKLIST,
                            onClick = { onConfigChange(grayscaleConfig.copy(activeMode = GrayscaleFilterMode.BLACKLIST)) },
                        )
                        Column {
                            Text("Blacklist", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Listed apps appear in grayscale; all others keep their colors.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = grayscaleConfig.activeMode == GrayscaleFilterMode.WHITELIST,
                            onClick = { onConfigChange(grayscaleConfig.copy(activeMode = GrayscaleFilterMode.WHITELIST)) },
                        )
                        Column {
                            Text("Whitelist", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Listed apps keep their colors; everything else appears in grayscale.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── App list for active profile ───────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (grayscaleConfig.activeMode == GrayscaleFilterMode.WHITELIST) "Whitelist" else "Blacklist",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedButton(onClick = { showAppChooser = true }) { Text("+ Add App") }
                    }

                    if (activeList.isEmpty()) {
                        Text(
                            if (grayscaleConfig.activeMode == GrayscaleFilterMode.WHITELIST)
                                "No apps added. Add apps that should keep their colors when the overlay is open."
                            else
                                "No apps added. Add apps that should always appear in grayscale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        activeList.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            entry.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (entry.packageName !in installedPackages) {
                                            Text(
                                                "· not installed",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        updateActiveList(activeList.filter { it.packageName != entry.packageName })
                                    },
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ShortcutHubTheme { MainScreen() }
}
