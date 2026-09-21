package com.fabiantorrestech.androidshortcuthub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ShortcutHubTrigger"

private const val ASSIST_ALIAS_CLASS =
    "com.fabiantorrestech.androidshortcuthub.AssistTriggerAlias"

private val lastFireMs = AtomicLong(0L)

/**
 * The single funnel every built-in trigger fires through.
 *
 * Exists for three reasons:
 *  - one place for the re-fire cooldown, so a bouncy gesture or a double-tapped accessibility
 *    button cannot toggle the hub open and straight back shut;
 *  - one place for the logging, so `adb logcat -s ShortcutHubTrigger` shows which affordance
 *    fired;
 *  - one [runCatching], so no trigger can ever throw on the accessibility service's main thread.
 *    An uncaught throw there kills the process, and Android immediately rebinds the service —
 *    the permanent "app keeps stopping" loop this codebase has already fixed twice.
 */
internal fun fireShortcutHubTrigger(
    context: Context,
    source: TriggerSource,
    cooldownMs: Int = 350,
) {
    val now = SystemClock.uptimeMillis()
    val previous = lastFireMs.get()
    if (previous != 0L && now - previous < cooldownMs) {
        Log.d(TAG, "trigger $source suppressed by ${cooldownMs}ms cooldown")
        return
    }
    if (!lastFireMs.compareAndSet(previous, now)) {
        Log.d(TAG, "trigger $source lost the cooldown race")
        return
    }

    Log.d(TAG, "trigger fired: $source")
    runCatching { routeShortcutHubToggle(context) }
        .onFailure { Log.e(TAG, "trigger $source failed to route", it) }
}

/**
 * Turns the ASSIST activity-alias on or off.
 *
 * The alias ships disabled in the manifest, so until this is called with `true` Shortcut Hub does
 * not appear in the system's digital-assistant picker at all. That is the whole reason the assist
 * trigger is an alias rather than an extra intent-filter: a filter declared in the manifest
 * cannot be withdrawn at runtime.
 */
internal fun setAssistAliasEnabled(context: Context, enabled: Boolean) {
    runCatching {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, ASSIST_ALIAS_CLASS),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }.onFailure { Log.e(TAG, "Assist alias toggle failed", it) }
}

/**
 * Opens the system screen where the digital assistant is chosen. Enabling the alias only makes
 * Shortcut Hub *eligible*; the user still has to select it.
 */
internal fun openAssistantPicker(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val launched = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (launched) return
    }
    Log.e(TAG, "No settings activity accepted the assistant-picker intent")
}
