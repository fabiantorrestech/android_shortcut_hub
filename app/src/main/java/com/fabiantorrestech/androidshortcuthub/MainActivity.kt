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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fabiantorrestech.androidshortcuthub.ui.theme.ShortcutHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShortcutHubTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scrollState = rememberScrollState()
    var hasOverlayPermission by remember {
        mutableStateOf(ShortcutHubOverlayService.canDrawOverlays(context))
    }
    var isAccessibilityServiceEnabled by remember {
        mutableStateOf(isShortcutHubAccessibilityServiceEnabled(context))
    }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizationRestrictions(context))
    }
    var areNotificationsEnabled by remember {
        mutableStateOf(areAppNotificationsEnabled(context))
    }
    var config by remember { mutableStateOf(ShortcutHubSettings.load(context)) }
    var rowsInput by remember(config.gridRows) { mutableStateOf(config.gridRows.toString()) }
    var columnsInput by remember(config.gridColumns) { mutableStateOf(config.gridColumns.toString()) }
    var defaultTextScale by remember(config.defaultTextScale) { mutableStateOf(config.defaultTextScale) }
    var defaultTextColorMode by remember(config.defaultTextColorMode) {
        mutableStateOf(config.defaultTextColorMode)
    }
    var defaultTextColorHexInput by remember(config.defaultTextColorHex) {
        mutableStateOf(config.defaultTextColorHex ?: "")
    }
    var hapticFeedbackEnabled by remember(config.hapticFeedbackEnabled) { mutableStateOf(config.hapticFeedbackEnabled) }
    var panelHandleLocked by remember(config.panelHandleLocked) { mutableStateOf(config.panelHandleLocked) }
    var overlayBackgroundAlpha by remember(config.overlayBackgroundAlpha) { mutableStateOf(config.overlayBackgroundAlpha) }
    var showOverLockscreen by remember(config.showOverLockscreen) { mutableStateOf(config.showOverLockscreen) }
    var useAccessibilityService by remember(config.useAccessibilityService) { mutableStateOf(config.useAccessibilityService) }
    var dismissOnScreenOff by remember(config.dismissOnScreenOff) { mutableStateOf(config.dismissOnScreenOff) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
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
    val intentDetails = remember(context.packageName) {
        listOf(
            "Action: ${ShortcutHubOverlayService.ACTION_TOGGLE_OVERLAY}",
            "Package: ${context.packageName}",
            "Class: ${context.packageName}.InvokeShortcutHubActivity",
            "Tasker target: Activity",
        )
    }
    val normalizedColorHex = remember(defaultTextColorHexInput) {
        normalizeHexColor(defaultTextColorHexInput)
    }
    val customHexValid = defaultTextColorHexInput.isBlank() || normalizedColorHex != null
    val previewTextColor = when (defaultTextColorMode) {
        DefaultTextColorMode.SYSTEM -> MaterialTheme.colorScheme.onSurface
        DefaultTextColorMode.BLACK -> Color.Black
        DefaultTextColorMode.WHITE -> Color.White
        DefaultTextColorMode.CUSTOM -> normalizedColorHex?.toComposeColor() ?: MaterialTheme.colorScheme.onSurface
    }
    val shouldShowAccessibilityBanner = !isAccessibilityServiceEnabled && !config.dismissAccessibilityBanner

    DisposableEffect(activity, context) {
        val lifecycle = activity?.lifecycle ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = ShortcutHubOverlayService.canDrawOverlays(context)
                isAccessibilityServiceEnabled = isShortcutHubAccessibilityServiceEnabled(context)
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizationRestrictions(context)
                areNotificationsEnabled = areAppNotificationsEnabled(context)
                config = ShortcutHubSettings.load(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Shortcut Hub",
            style = MaterialTheme.typography.headlineMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Permissions", style = MaterialTheme.typography.titleMedium)
                Text(text = "Grant required access here. Accessibility is optional, but it improves the lockscreen path and is required for future lockscreen widgets.")
                PermissionRow(
                    title = "Draw over other apps",
                    granted = hasOverlayPermission,
                    actionLabel = "Open Settings",
                    onActionClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
                PermissionRow(
                    title = "Accessibility service",
                    granted = isAccessibilityServiceEnabled,
                    actionLabel = "Open Settings",
                    onActionClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    badgeText = if (shouldShowAccessibilityBanner) "Recommended" else null,
                    description = if (shouldShowAccessibilityBanner) {
                        "Accessibility service is required for lockscreen widgets and improves lockscreen overlay quality. Other features work without it."
                    } else {
                        null
                    },
                    secondaryActionLabel = if (shouldShowAccessibilityBanner) "Dismiss" else null,
                    onSecondaryActionClick = if (shouldShowAccessibilityBanner) {
                        {
                            config = config.copy(dismissAccessibilityBanner = true)
                            ShortcutHubSettings.save(context, config)
                            settingsMessage = "Accessibility recommendation dismissed"
                        }
                    } else {
                        null
                    },
                )
                PermissionRow(
                    title = "Unrestricted battery",
                    granted = isIgnoringBatteryOptimizations,
                    actionLabel = "Open Settings",
                    onActionClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "Notifications",
                        granted = areNotificationsEnabled,
                        actionLabel = "Open Settings",
                        onActionClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                },
                            )
                        },
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Phase 0", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (hasOverlayPermission) {
                        "Overlay permission granted"
                    } else {
                        "Grant overlay permission in the Permissions section before toggling the hub"
                    },
                )
                OutlinedButton(
                    onClick = {
                        if (ShortcutHubOverlayService.canDrawOverlays(context)) {
                            routeShortcutHubToggle(context)
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Toggle Overlay")
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Grid Settings", style = MaterialTheme.typography.titleMedium)
                Text(text = "Set overlay grid size here. Shrinking the grid removes out-of-bounds tiles.")
                OutlinedTextField(
                    value = rowsInput,
                    onValueChange = { rowsInput = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Rows (1-24)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = columnsInput,
                    onValueChange = { columnsInput = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Columns (1-16)") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        val rows = rowsInput.toIntOrNull()?.coerceIn(1, 24) ?: config.gridRows
                        val columns = columnsInput.toIntOrNull()?.coerceIn(1, 16) ?: config.gridColumns
                        config = config.copy(
                            gridRows = rows,
                            gridColumns = columns,
                        )
                        rowsInput = rows.toString()
                        columnsInput = columns.toString()
                        ShortcutHubSettings.save(context, config)
                        ShortcutHubSettings.pruneOutOfBoundsTiles(context, rows, columns)
                        settingsMessage = "Grid settings saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Grid Settings")
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Default Tile Typography", style = MaterialTheme.typography.titleMedium)
                Text(text = "Default text size: ${"%.2f".format(defaultTextScale)}x")
                Slider(
                    value = defaultTextScale,
                    onValueChange = { defaultTextScale = it },
                    valueRange = 0.5f..3.0f,
                )
                OutlinedButton(
                    onClick = {
                        config = config.copy(defaultTextScale = defaultTextScale)
                        ShortcutHubSettings.save(context, config)
                        settingsMessage = "Default text size saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Default Text Size")
                }
                Text(
                    text = "Default font: ${config.defaultFontName ?: "System default"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose Default Font File")
                }
                OutlinedButton(
                    onClick = {
                        config = config.copy(defaultFontUri = null, defaultFontName = null)
                        ShortcutHubSettings.save(context, config)
                        settingsMessage = "Default font cleared"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear Default Font")
                }
                Text(
                    text = "Default text color",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                        onValueChange = { input ->
                            defaultTextColorHexInput = input.trim().take(9)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom hex color") },
                        placeholder = { Text("#FFFFFF") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                if (customHexValid) {
                                    normalizedColorHex ?: "Use #RRGGBB or #AARRGGBB"
                                } else {
                                    "Invalid hex. Use #RRGGBB or #AARRGGBB"
                                },
                            )
                        },
                        isError = !customHexValid,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = previewTextColor,
                    ) {}
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Preview",
                            color = previewTextColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (defaultTextColorMode == DefaultTextColorMode.CUSTOM && normalizedColorHex == null) {
                            settingsMessage = "Enter a valid hex color first"
                            return@OutlinedButton
                        }
                        config = config.copy(
                            defaultTextColorMode = defaultTextColorMode,
                            defaultTextColorHex = if (defaultTextColorMode == DefaultTextColorMode.CUSTOM) {
                                normalizedColorHex
                            } else {
                                null
                            },
                        )
                        if (defaultTextColorMode != DefaultTextColorMode.CUSTOM) {
                            defaultTextColorHexInput = config.defaultTextColorHex ?: ""
                        }
                        ShortcutHubSettings.save(context, config)
                        settingsMessage = "Default text color saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Default Text Color")
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Overlay Behavior", style = MaterialTheme.typography.titleMedium)
                SettingToggleRow(
                    label = "Haptic feedback on tile press",
                    checked = hapticFeedbackEnabled,
                    onCheckedChange = {
                        hapticFeedbackEnabled = it
                        config = config.copy(hapticFeedbackEnabled = it)
                        ShortcutHubSettings.save(context, config)
                    },
                )
                SettingToggleRow(
                    label = "Use accessibility service when available",
                    checked = useAccessibilityService,
                    onCheckedChange = {
                        useAccessibilityService = it
                        config = config.copy(useAccessibilityService = it)
                        ShortcutHubSettings.save(context, config)
                    },
                )
                SettingToggleRow(
                    label = "Lock panel handle position",
                    checked = panelHandleLocked,
                    onCheckedChange = {
                        panelHandleLocked = it
                        config = config.copy(panelHandleLocked = it)
                        ShortcutHubSettings.save(context, config)
                    },
                )
                SettingToggleRow(
                    label = "Show overlay over lockscreen",
                    checked = showOverLockscreen,
                    onCheckedChange = {
                        showOverLockscreen = it
                        config = config.copy(showOverLockscreen = it)
                        ShortcutHubSettings.save(context, config)
                    },
                )
                SettingToggleRow(
                    label = "Dismiss overlay when screen turns off",
                    checked = dismissOnScreenOff,
                    onCheckedChange = {
                        dismissOnScreenOff = it
                        config = config.copy(dismissOnScreenOff = it)
                        ShortcutHubSettings.save(context, config)
                    },
                )
                Text(text = "Background opacity: ${"%.0f".format(overlayBackgroundAlpha * 100)}%")
                Slider(
                    value = overlayBackgroundAlpha,
                    onValueChange = { overlayBackgroundAlpha = it },
                    valueRange = 0f..0.9f,
                )
                OutlinedButton(
                    onClick = {
                        config = config.copy(overlayBackgroundAlpha = overlayBackgroundAlpha)
                        ShortcutHubSettings.save(context, config)
                        settingsMessage = "Overlay settings saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Opacity")
                }
            }
        }
        settingsMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Intent Details", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        intentDetails.forEach { line ->
                            Text(text = line)
                        }
                    }
                }
            }
        }
        HelpCard(
            title = "Key Mapper",
            bodyLines = listOf(
                "Option 1: use Launch app shortcut.",
                "Pick this app, then choose ${context.getString(R.string.shortcut_toggle_long_label)}.",
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
        Button(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label)
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            PermissionStatusChip(granted = granted)
            badgeText?.let { text ->
                RecommendationBadge(text = text)
            }
        }
        description?.let { body ->
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onActionClick,
                modifier = Modifier.weight(1f),
            ) {
                Text(actionLabel)
            }
            if (secondaryActionLabel != null && onSecondaryActionClick != null) {
                TextButton(onClick = onSecondaryActionClick) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusChip(granted: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (granted) {
            Color(0xFFDBF5E6)
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (granted) {
            Color(0xFF175B35)
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
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
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color(0xFFFFE9B3),
        contentColor = Color(0xFF5F4300),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun String.toComposeColor(): Color {
    val parsed = android.graphics.Color.parseColor(this)
    return Color(parsed)
}

private fun isShortcutHubAccessibilityServiceEnabled(context: Context): Boolean {
    val accessibilityManager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return accessibilityManager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName &&
                info.resolveInfo.serviceInfo.name == "${context.packageName}.ShortcutHubAccessibilityService"
        }
}

private fun isIgnoringBatteryOptimizationRestrictions(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun areAppNotificationsEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
    }
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
    return notificationManager.areNotificationsEnabled()
}

private fun resolveDisplayName(
    contentResolver: ContentResolver,
    uri: Uri,
): String? {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}

@Composable
private fun HelpCard(
    title: String,
    bodyLines: List<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            bodyLines.forEach { line ->
                Text(text = line)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ShortcutHubTheme {
        MainScreen()
    }
}
