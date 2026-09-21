package com.fabiantorrestech.androidshortcuthub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

/**
 * Settings for the ways the hub can be summoned.
 *
 * Lives in its own file because MainActivity.kt is already ~1400 lines, following the same split
 * as OverlayEditorScreen.kt. It reuses SettingToggleRow / PermissionRow from MainActivity rather
 * than duplicating them.
 */
@Composable
internal fun TriggersTab(
    hubEnabled: Boolean,
    triggerConfig: TriggerConfig,
    isAccessibilityServiceEnabled: Boolean,
    onTriggerConfigChange: (TriggerConfig) -> Unit,
    onGrantAccessibility: () -> Unit,
) {
    // The preview is scoped to this tab being on screen AND the app being in the foreground.
    // onDispose covers leaving the tab (the tab dispatch swaps this composable out), and the
    // lifecycle observer covers leaving the app, after which the handles return to whatever
    // behaviour their settings call for. Re-entering on this tab brings it back.
    //
    // hubEnabled is a key as much as a condition: switching the hub back on builds a brand-new
    // handle controller, and the change of key is what re-sends the preview request to it.
    val previewWanted = hubEnabled &&
        triggerConfig.livePreviewEnabled &&
        triggerConfig.masterEnabled &&
        isAccessibilityServiceEnabled
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, previewWanted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    ShortcutHubAccessibilityService.setEdgePreviewMode(previewWanted)
                Lifecycle.Event.ON_PAUSE ->
                    ShortcutHubAccessibilityService.setEdgePreviewMode(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The tab can be opened while already resumed, so ON_RESUME may never fire.
        ShortcutHubAccessibilityService.setEdgePreviewMode(previewWanted)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ShortcutHubAccessibilityService.setEdgePreviewMode(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RequirementCard(isAccessibilityServiceEnabled, onGrantAccessibility)

        OpenFeedbackCard(
            triggerConfig = triggerConfig,
            onTriggerConfigChange = onTriggerConfigChange,
        )

        AccessibilityShortcutCard(
            enabled = isAccessibilityServiceEnabled,
            onGrantAccessibility = onGrantAccessibility,
        )

        EdgeHandleGlobalCard(
            triggerConfig = triggerConfig,
            enabled = isAccessibilityServiceEnabled,
            onTriggerConfigChange = onTriggerConfigChange,
        )

        EdgeSide.entries.forEach { side ->
            EdgeHandleCard(
                handle = triggerConfig.handleFor(side),
                enabled = isAccessibilityServiceEnabled && triggerConfig.masterEnabled,
                onHandleChange = { onTriggerConfigChange(triggerConfig.withHandle(it)) },
            )
        }

        if (triggerConfig.masterEnabled) {
            PerAppCard(
                triggerConfig = triggerConfig,
                enabled = isAccessibilityServiceEnabled,
                onTriggerConfigChange = onTriggerConfigChange,
            )
        }

        AssistGestureCard(
            triggerConfig = triggerConfig,
            onTriggerConfigChange = onTriggerConfigChange,
        )
    }
}

/**
 * Per-app allow/block list for the edge handles.
 *
 * Note this suppresses the handle by *detaching the window*, not by ignoring the gesture — so in
 * a blocked app the strip also stops swallowing touches that belong to that app.
 */
@Composable
private fun PerAppCard(
    triggerConfig: TriggerConfig,
    enabled: Boolean,
    onTriggerConfigChange: (TriggerConfig) -> Unit,
) {
    var showChooser by remember { mutableStateOf(false) }

    val isAllowlist = triggerConfig.filterMode == TriggerFilterMode.WHITELIST
    val activeList = if (isAllowlist) triggerConfig.allowApps else triggerConfig.blockApps

    fun updateList(newList: List<TriggerAppEntry>) {
        onTriggerConfigChange(
            if (isAllowlist) {
                triggerConfig.copy(allowApps = newList)
            } else {
                triggerConfig.copy(blockApps = newList)
            },
        )
    }

    if (showChooser) {
        AppChooserDialog(
            alreadyChosen = activeList,
            onPick = { updateList(activeList + it) },
            onDismiss = { showChooser = false },
        )
    }

    SettingsCard("Per-app rules") {
        ChoiceRow(
            label = "Mode",
            options = listOf(
                TriggerFilterMode.BLACKLIST to "Block in these apps",
                TriggerFilterMode.WHITELIST to "Only in these apps",
            ),
            selected = triggerConfig.filterMode,
            onSelect = { onTriggerConfigChange(triggerConfig.copy(filterMode = it)) },
            enabled = enabled,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isAllowlist) "Allowed apps" else "Blocked apps",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            OutlinedButton(onClick = { showChooser = true }, enabled = enabled) {
                Text("+ Add app")
            }
        }

        if (activeList.isEmpty()) {
            Caption(
                if (isAllowlist) {
                    "No apps added, so the handles are currently suppressed everywhere. Add the " +
                        "apps where you want them to work."
                } else {
                    "No apps added — the handles work everywhere."
                },
            )
        } else {
            activeList.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                    }
                    TextButton(
                        onClick = { updateList(activeList.filterNot { it.packageName == app.packageName }) },
                        enabled = enabled,
                    ) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun AppChooserDialog(
    alreadyChosen: List<TriggerAppEntry>,
    onPick: (TriggerAppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<List<TriggerAppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        available = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getInstalledApplications(0)
                    .mapNotNull { info ->
                        context.packageManager.getLaunchIntentForPackage(info.packageName)
                            ?: return@mapNotNull null
                        val label = context.packageManager.getApplicationLabel(info)
                            ?.toString()?.ifBlank { info.packageName } ?: info.packageName
                        TriggerAppEntry(packageName = info.packageName, label = label)
                    }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    val chosenPackages = remember(alreadyChosen) { alreadyChosen.mapTo(mutableSetOf()) { it.packageName } }
    val filtered = available.filter {
        it.packageName !in chosenPackages &&
            (query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Text("Loading apps…", modifier = Modifier.padding(top = 8.dp))
                    filtered.isEmpty() -> Caption(
                        if (available.isEmpty()) "No apps found." else "All apps already added.",
                    )
                    else -> LazyColumn {
                        items(filtered, key = { it.packageName }) { app ->
                            TextButton(
                                onClick = { onPick(app) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalContentColor.current.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * The buzz on opening. Not gated on the accessibility service: Key Mapper, Tasker, assist and the
 * launcher shortcut all open the hub without it.
 */
@Composable
private fun OpenFeedbackCard(
    triggerConfig: TriggerConfig,
    onTriggerConfigChange: (TriggerConfig) -> Unit,
) {
    SettingsCard("When the hub opens") {
        SettingToggleRow(
            label = "Vibrate when the hub opens",
            description = "Every trigger: edge handles, the accessibility shortcut, Key Mapper " +
                "or Tasker, assist, and the launcher shortcut. Works even with Android's Touch " +
                "feedback turned off.",
            checked = triggerConfig.vibrateOnOpen,
            onCheckedChange = { onTriggerConfigChange(triggerConfig.copy(vibrateOnOpen = it)) },
        )
        SettingToggleRow(
            label = "Vibrate even in silent mode",
            description = if (triggerConfig.vibrateInSilentMode) {
                "Vibrates whatever the sound mode."
            } else {
                "Follows your sound mode: vibrates in Ring and Vibrate, stays still in Silent."
            },
            checked = triggerConfig.vibrateInSilentMode,
            onCheckedChange = {
                onTriggerConfigChange(triggerConfig.copy(vibrateInSilentMode = it))
            },
            enabled = triggerConfig.vibrateOnOpen,
        )
    }
}

@Composable
private fun AssistGestureCard(
    triggerConfig: TriggerConfig,
    onTriggerConfigChange: (TriggerConfig) -> Unit,
) {
    val context = LocalContext.current
    SettingsCard("Assist gesture") {
        Text(
            "Lets Shortcut Hub be picked as the device's digital assistant, so the gesture you " +
                "already use for the assistant — long-press power, or the corner swipe — opens " +
                "the hub instead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SettingToggleRow(
            label = "Offer Shortcut Hub as the assistant",
            description = "Unlike the other triggers this one does not need the accessibility " +
                "service.",
            checked = triggerConfig.assistGestureEnabled,
            onCheckedChange = { wanted ->
                setAssistAliasEnabled(context, wanted)
                onTriggerConfigChange(triggerConfig.copy(assistGestureEnabled = wanted))
            },
        )
        if (triggerConfig.assistGestureEnabled) {
            Caption(
                "This only makes Shortcut Hub selectable — you still have to choose it as the " +
                    "digital assistant, which replaces whatever the gesture opens today.",
            )
            OutlinedButton(
                onClick = { openAssistantPicker(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose the assistant app") }
        }
    }
}

@Composable
private fun RequirementCard(
    isAccessibilityServiceEnabled: Boolean,
    onGrantAccessibility: () -> Unit,
) {
    SettingsCard("Requirement") {
        Text(
            "Every built-in trigger is driven by the accessibility service, because it is the " +
                "only part of Shortcut Hub that stays running without a permanent notification.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PermissionRow(
            title = "Accessibility Service",
            granted = isAccessibilityServiceEnabled,
            actionLabel = "Open Settings",
            onActionClick = onGrantAccessibility,
            description = if (isAccessibilityServiceEnabled) {
                null
            } else {
                "Enable Shortcut Hub under Settings › Accessibility › Installed apps."
            },
        )
        Caption(
            "After updating the app you may need to turn the accessibility service off and on " +
                "again — Android re-asks for consent whenever a service's capabilities change.",
        )
    }
}

@Composable
private fun AccessibilityShortcutCard(
    enabled: Boolean,
    onGrantAccessibility: () -> Unit,
) {
    SettingsCard("Accessibility shortcut (optional)") {
        Text(
            "Android can summon the hub on its own, with an affordance it draws and manages — so " +
                "it never collides with navigation gestures. This is entirely optional: assign a " +
                "shortcut in Android's settings if you want it, or ignore it and use an edge " +
                "handle instead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Caption(
            "Under Settings › Accessibility › Shortcut Hub › Shortcut you can pick a floating " +
                "button (draggable, resizable, and able to fade when unused), a navigation-bar " +
                "button, a volume-key hold, or a two-finger swipe up. Whichever you choose opens " +
                "the hub — there is nothing to switch on here.",
        )
        OutlinedButton(
            onClick = onGrantAccessibility,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open accessibility settings") }
        if (!enabled) {
            Caption("Requires the accessibility service to be enabled.")
        }
    }
}

@Composable
private fun EdgeHandleGlobalCard(
    triggerConfig: TriggerConfig,
    enabled: Boolean,
    onTriggerConfigChange: (TriggerConfig) -> Unit,
) {
    SettingsCard("Edge handles") {
        SettingToggleRow(
            label = "Enable edge handles",
            description = if (enabled) {
                "A thin strip on the screen edge that opens the hub."
            } else {
                "Requires the accessibility service to be enabled"
            },
            checked = triggerConfig.masterEnabled,
            onCheckedChange = { onTriggerConfigChange(triggerConfig.copy(masterEnabled = it)) },
            enabled = enabled,
        )
        Caption(
            "An edge handle always consumes the touches that land on it, even when it is set to " +
                "be invisible. Anything underneath that strip — a scrollbar, a drawer edge, a " +
                "game control — will stop receiving them. Keep the touch depth small if that " +
                "matters to you.",
        )
        if (triggerConfig.masterEnabled) {
            SettingToggleRow(
                label = "Toggle Live Preview",
                description = "Holds the handles fully visible while this tab is open, so you " +
                    "can see your changes land. Turns itself off when you leave the tab or the " +
                    "app.",
                checked = triggerConfig.livePreviewEnabled,
                onCheckedChange = {
                    onTriggerConfigChange(triggerConfig.copy(livePreviewEnabled = it))
                },
                enabled = enabled,
            )
            SettingToggleRow(
                label = "Work on the lock screen",
                checked = triggerConfig.showOnLockscreen,
                onCheckedChange = {
                    onTriggerConfigChange(triggerConfig.copy(showOnLockscreen = it))
                },
                enabled = enabled,
            )
            SettingToggleRow(
                label = "Hide in fullscreen apps",
                description = "Experimental — detects fullscreen by watching the system bars.",
                checked = triggerConfig.suppressWhenImmersive,
                onCheckedChange = {
                    onTriggerConfigChange(triggerConfig.copy(suppressWhenImmersive = it))
                },
                enabled = enabled,
            )
            SettingToggleRow(
                label = "Protect swipe-in from the Back gesture",
                description = "Only affects the Swipe inward mode. Other modes cannot be " +
                    "mistaken for Back, so they never need this.",
                checked = triggerConfig.suppressBackGesture,
                onCheckedChange = {
                    onTriggerConfigChange(triggerConfig.copy(suppressBackGesture = it))
                },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun EdgeHandleCard(
    handle: EdgeHandleConfig,
    enabled: Boolean,
    onHandleChange: (EdgeHandleConfig) -> Unit,
) {
    val sideName = if (handle.side == EdgeSide.LEFT) "Left edge" else "Right edge"
    SettingsCard(sideName) {
        SettingToggleRow(
            label = "Show a handle on this edge",
            checked = handle.enabled,
            onCheckedChange = { onHandleChange(handle.copy(enabled = it)) },
            enabled = enabled,
        )

        if (!handle.enabled) return@SettingsCard

        ChoiceRow(
            label = "Activation",
            options = listOf(
                EdgeActivation.DRAG_ALONG to "Drag along",
                EdgeActivation.TAP to "Tap",
                EdgeActivation.DRAG_OR_TAP to "Drag or tap",
                EdgeActivation.LONG_PRESS to "Hold",
                EdgeActivation.SWIPE_IN to "Swipe inward",
            ),
            selected = handle.activation,
            onSelect = { onHandleChange(handle.copy(activation = it)) },
            enabled = enabled,
        )
        Caption(activationHint(handle.activation))

        ChoiceRow(
            label = "Appearance",
            options = listOf(
                EdgeVisibility.REVEAL_ON_CONTACT to "Reveal on touch",
                EdgeVisibility.INVISIBLE to "Always hidden",
                EdgeVisibility.DIM to "Dim, brighten on touch",
                EdgeVisibility.ALWAYS_VISIBLE to "Always visible",
            ),
            selected = handle.visibility,
            onSelect = { onHandleChange(handle.copy(visibility = it)) },
            enabled = enabled,
        )

        SliderRow(
            label = "Position down the edge",
            value = handle.verticalBiasFraction,
            range = 0f..1f,
            enabled = enabled,
            valueLabel = { "${(it * 100).roundToInt()}%" },
            onCommit = { onHandleChange(handle.copy(verticalBiasFraction = it)) },
        )
        SliderRow(
            label = "Length",
            value = handle.lengthDp.toFloat(),
            range = 48f..200f,
            enabled = enabled,
            valueLabel = { "${it.roundToInt()} dp" },
            onCommit = { onHandleChange(handle.copy(lengthDp = it.roundToInt())) },
        )
        SliderRow(
            label = "How far it sticks out",
            value = handle.protrusionDp.toFloat(),
            range = 1f..24f,
            enabled = enabled,
            valueLabel = { "${it.roundToInt()} dp" },
            onCommit = { onHandleChange(handle.copy(protrusionDp = it.roundToInt())) },
        )
        SliderRow(
            label = "Touch zone depth",
            value = handle.touchDepthDp.toFloat(),
            range = 6f..48f,
            enabled = enabled,
            valueLabel = { "${it.roundToInt()} dp" },
            onCommit = { onHandleChange(handle.copy(touchDepthDp = it.roundToInt())) },
        )
        Caption(
            "The touch zone is usually wider than the visible bar, so the handle appears as your " +
                "thumb reaches the edge rather than only when it lands exactly on the bar.",
        )
        SliderRow(
            label = "Travel needed to fire",
            value = handle.activationSlopDp.toFloat(),
            range = 8f..120f,
            enabled = enabled,
            valueLabel = { "${it.roundToInt()} dp" },
            onCommit = { onHandleChange(handle.copy(activationSlopDp = it.roundToInt())) },
        )

        if (handle.visibility != EdgeVisibility.INVISIBLE) {
            if (handle.visibility != EdgeVisibility.ALWAYS_VISIBLE) {
                SliderRow(
                    label = "Stay visible after touch",
                    value = handle.revealLingerMs.toFloat(),
                    range = 0f..8000f,
                    enabled = enabled,
                    valueLabel = { "${(it / 1000f * 10).roundToInt() / 10f} s" },
                    onCommit = { onHandleChange(handle.copy(revealLingerMs = it.roundToInt())) },
                )
            }
            if (handle.visibility == EdgeVisibility.DIM ||
                handle.visibility == EdgeVisibility.ALWAYS_VISIBLE
            ) {
                SliderRow(
                    label = "Resting opacity",
                    value = handle.restingAlpha,
                    range = 0f..1f,
                    enabled = enabled,
                    valueLabel = { "${(it * 100).roundToInt()}%" },
                    onCommit = { onHandleChange(handle.copy(restingAlpha = it)) },
                )
            }
            SliderRow(
                label = "Opacity while touched",
                value = handle.activeAlpha,
                range = 0f..1f,
                enabled = enabled,
                valueLabel = { "${(it * 100).roundToInt()}%" },
                onCommit = { onHandleChange(handle.copy(activeAlpha = it)) },
            )
        }
    }
}

private fun activationHint(activation: EdgeActivation): String = when (activation) {
    EdgeActivation.DRAG_ALONG ->
        "Drag up or down along the edge. Android only treats an edge swipe as Back when it " +
            "travels inward, so this can never be mistaken for Back."
    EdgeActivation.TAP ->
        "A single tap on the strip. Back needs travel before it arms, so a tap is safe too."
    EdgeActivation.DRAG_OR_TAP ->
        "Either a tap or a drag along the edge."
    EdgeActivation.LONG_PRESS ->
        "Press and hold. The safest option, at the cost of the wait on every invocation."
    EdgeActivation.SWIPE_IN ->
        "Swipe inward from the edge. This is the same motion as the system Back gesture, so it " +
            "relies on Android honouring the gesture-exclusion request. If Back keeps firing " +
            "instead, use Drag along."
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = 0.6f),
    )
}

/**
 * Enum picker rendered as a wrapped grid of buttons, matching the filled-means-selected idiom
 * MainActivity already uses for text-colour modes.
 */
@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { (value, text) ->
                    if (value == selected) {
                        Button(
                            onClick = { onSelect(value) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text(text) }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(value) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text(text) }
                    }
                }
                // Keeps a lone trailing button the same width as the ones above it.
                if (rowOptions.size == 1) Column(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

/**
 * Slider that tracks locally while dragging and only persists on release, so every pixel of drag
 * does not trigger a SharedPreferences write and a window resync.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueLabel: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    LocalContentColor.current
                } else {
                    LocalContentColor.current.copy(alpha = 0.38f)
                },
            )
            Text(
                valueLabel(live),
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live) },
            valueRange = range,
            enabled = enabled,
        )
    }
}
