package com.fabiantorrestech.androidshortcuthub

import android.accessibilityservice.AccessibilityServiceInfo
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
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

/**
 * The app's top-level tabs, in display order.
 *
 * Named rather than indexed: tabs used to be raw Ints, with the layout editor hard-coded as 3 in
 * two places and a comment warning that inserting a tab would silently break both. Removing the
 * Grayscale tab did shift every index after it - with names, nothing else had to change.
 */
private enum class MainTab(val title: String) {
    SETUP("Setup"),
    BEHAVIOR("Behavior"),
    LAYOUT("Layout"),
    BACKUP("Backup"),
    TRIGGERS("Triggers"),
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var hasOverlayPermission by remember { mutableStateOf(ShortcutHubOverlayService.canDrawOverlays(context)) }
    var isAccessibilityServiceEnabled by remember { mutableStateOf(isShortcutHubAccessibilityServiceEnabled(context)) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(isIgnoringBatteryOptimizationRestrictions(context)) }
    var hasWriteSettingsPermission by remember { mutableStateOf(android.provider.Settings.System.canWrite(context)) }
    var config by remember { mutableStateOf(ShortcutHubSettings.load(context)) }
    var triggerConfig by remember { mutableStateOf(TriggerRepository.load(context)) }
    var hubEnabled by remember { mutableStateOf(HubSwitch.isEnabled(context)) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.SETUP) }

    /**
     * Tabs the user came through, most recent last, so system back unwinds one level per press
     * instead of finishing the activity. [navigateToTab] drops any earlier visit to the tab being
     * left before pushing it, which keeps the stack bounded by the tab count however long the
     * session runs, and means back never walks the same tab twice in a row.
     */
    val tabBackStack = rememberSaveable(
        // Saved by name rather than by the enum itself: a String is unambiguously Bundle-safe.
        saver = listSaver(
            save = { stack -> stack.map { it.name } },
            restore = { names -> names.map(MainTab::valueOf).toMutableStateList() },
        ),
    ) { mutableStateListOf<MainTab>() }

    fun navigateToTab(tab: MainTab) {
        if (tab == selectedTab) return
        tabBackStack.remove(selectedTab)
        tabBackStack.add(selectedTab)
        selectedTab = tab
        settingsMessage = null
    }

    // Disabled once the stack is empty, i.e. the user is where they started, so back then leaves
    // the app as expected rather than trapping them.
    BackHandler(enabled = tabBackStack.isNotEmpty()) {
        selectedTab = tabBackStack.removeAt(tabBackStack.lastIndex)
        settingsMessage = null
    }
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
        val invokeClass = "${context.packageName}.InvokeShortcutHubActivity"
        listOf(
            "Action" to ShortcutHubOverlayService.ACTION_TOGGLE_OVERLAY,
            "Package" to context.packageName,
            "Class" to invokeClass,
            // The flattened form, because Shortcut Hub's own Component / Class field wants
            // package/class — pasting the bare class above into it is a common mistake.
            "Component" to "${context.packageName}/$invokeClass",
            "Tasker target" to "Activity",
        )
    }

    DisposableEffect(activity, context) {
        val lifecycle = activity?.lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = ShortcutHubOverlayService.canDrawOverlays(context)
                isAccessibilityServiceEnabled = isShortcutHubAccessibilityServiceEnabled(context)
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizationRestrictions(context)
                hasWriteSettingsPermission = android.provider.Settings.System.canWrite(context)
                config = ShortcutHubSettings.load(context)
                triggerConfig = TriggerRepository.load(context)
                hubEnabled = HubSwitch.isEnabled(context)
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
        if (selectedTab != MainTab.LAYOUT) {
            Text(
                text = "Shortcut Hub",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            HubSwitchBar(
                enabled = hubEnabled,
                onEnabledChange = { on ->
                    HubSwitch.setEnabled(context, on)
                    hubEnabled = on
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 0.dp,
            ) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { navigateToTab(tab) },
                        text = { Text(tab.title) },
                    )
                }
            }
        }
        when (selectedTab) {
            MainTab.SETUP -> SetupTab(
                hubEnabled = hubEnabled,
                hasOverlayPermission = hasOverlayPermission,
                isAccessibilityServiceEnabled = isAccessibilityServiceEnabled,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
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
                onGrantBattery = { openBatteryOptimizationSettings(context) },
                onGrantWriteSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
            MainTab.BEHAVIOR -> BehaviorTab(
                config = config,
                onConfigChange = { updated ->
                    config = updated
                    ShortcutHubSettings.save(context, updated)
                },
                isAccessibilityServiceEnabled = isAccessibilityServiceEnabled,
            )
            MainTab.LAYOUT -> LayoutTab(
                // Returns to whichever tab the editor was opened from, rather than always Setup.
                onBack = {
                    selectedTab =
                        if (tabBackStack.isEmpty()) MainTab.SETUP else tabBackStack.removeAt(tabBackStack.lastIndex)
                    settingsMessage = null
                },
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
            MainTab.BACKUP -> BackupTab(
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
            MainTab.TRIGGERS -> TriggersTab(
                hubEnabled = hubEnabled,
                triggerConfig = triggerConfig,
                isAccessibilityServiceEnabled = isAccessibilityServiceEnabled,
                onTriggerConfigChange = { updated ->
                    triggerConfig = updated
                    TriggerRepository.save(context, updated)
                },
                onGrantAccessibility = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
        }
    }
}

@Composable
private fun SetupTab(
    hubEnabled: Boolean,
    hasOverlayPermission: Boolean,
    isAccessibilityServiceEnabled: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    hasWriteSettingsPermission: Boolean,
    config: ShortcutHubConfig,
    intentDetails: List<Pair<String, String>>,
    settingsMessage: String?,
    onToggleOverlay: () -> Unit,
    onDismissAccessibilityBanner: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantBattery: () -> Unit,
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
        OutlinedButton(
            onClick = onToggleOverlay,
            enabled = hubEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
                        intentDetails.forEach { (label, value) ->
                            CopyableDetailRow(label = label, value = value)
                        }
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
                    label = "Open/close animation",
                    checked = config.launchAnimationEnabled,
                    onCheckedChange = { onConfigChange(config.copy(launchAnimationEnabled = it)) },
                )
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
                    label = "Dismiss overlay on widget activity",
                    description = "Tapping something inside a widget closes the overlay. " +
                        "Scrolling and swiping don't count. Individual widgets can override this.",
                    checked = config.dismissOnWidgetActivity,
                    onCheckedChange = { onConfigChange(config.copy(dismissOnWidgetActivity = it)) },
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

/**
 * A label / value line with a copy button.
 *
 * The value is also wrapped in a SelectionContainer so a partial copy is still possible — these
 * strings are long, and sometimes only the tail is wanted.
 */
@Composable
internal fun CopyableDetailRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(value))
                // Android 13+ shows its own clipboard confirmation, so a second toast would
                // just stack on top of the system one.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

/**
 * The master switch, in the style of Android Settings' main switch: one full-width bar that reads
 * as the state of the whole app rather than as one setting among many. See [HubSwitch] for what
 * turning it off actually does.
 */
@Composable
private fun HubSwitchBar(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "hubSwitchContainer",
    )
    // Surface clips its content to the shape, which keeps the ripple inside the pill.
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use Shortcut Hub", style = MaterialTheme.typography.titleMedium)
                if (!enabled) {
                    Text(
                        "Off — nothing can open the hub, and nothing runs in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            // The whole bar is the toggle, so the switch is only its indicator.
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
internal fun SettingToggleRow(
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
internal fun PermissionRow(
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
internal fun PermissionStatusChip(granted: Boolean) {
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

/**
 * Opens the battery-optimisation exemption prompt, falling back to the full list screen.
 *
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS needs the matching permission declared, and even
 * then some OEM builds refuse to resolve it. The list screen (ACTION_IGNORE_BATTERY_OPTIMIZATION_
 * SETTINGS) needs no permission and is always present, so it is the safety net — the user just
 * has to find Shortcut Hub in it themselves.
 */
private fun openBatteryOptimizationSettings(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (runCatching { context.startActivity(direct); true }.getOrDefault(false)) return

    val listScreen = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (runCatching { context.startActivity(listScreen); true }.getOrDefault(false)) return

    Toast.makeText(
        context,
        "Couldn't open battery settings — find Shortcut Hub under Settings › Apps › Battery.",
        Toast.LENGTH_LONG,
    ).show()
}

private fun isIgnoringBatteryOptimizationRestrictions(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
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
    // Scoped to this composition, which means leaving the Layout tab discards the draft. The
    // editor guards that with its own "Save changes?" prompt on the way out rather than relying on
    // the state surviving, so back is the only route here and it always asks first.
    val (portraitEditorState, landscapeEditorState) = remember {
        val (portrait, landscape) = OverlayStateRepository.loadBoth(context)
        OverlayEditorState(portrait) to OverlayEditorState(landscape)
    }

    OverlayEditorScreen(
        portraitEditorState = portraitEditorState,
        landscapeEditorState = landscapeEditorState,
        onBack = onBack,
        onSave = { portraitCommitted, landscapeCommitted ->
            OverlayStateRepository.saveLayout(context, portraitCommitted, OverlayOrientation.PORTRAIT)
            OverlayStateRepository.saveLayout(context, landscapeCommitted, OverlayOrientation.LANDSCAPE)
            // Appearance settings are per-app rather than per-layout, so only one commit needs to
            // supply them. Safe because the editor runs adoptGlobalsFrom across both states before
            // committing, leaving the two identical in exactly those fields.
            onSaveSettings(portraitCommitted)
            Toast.makeText(context, "Layout saved. Toggle the overlay to apply.", Toast.LENGTH_SHORT).show()
        },
        openFontPicker = onOpenFontPicker,
        openIconPicker = onOpenIconPicker,
        openDefaultFontPicker = onOpenDefaultFontPicker,
        fontEvents = ShortcutHubOverlayService.tileFontSelectionEvents(),
        iconEvents = ShortcutHubOverlayService.tileIconSelectionEvents(),
    )
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ShortcutHubTheme { MainScreen() }
}
