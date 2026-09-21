package com.fabiantorrestech.androidshortcuthub

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Turns the "Component / Class" field into a [ComponentName].
 *
 * Accepts either a flattened `package/class` string or a bare class name, in which case the
 * tile's Package field supplies the package half. That second form matters because the app's own
 * Intent Details card hands the user a bare class name, and pasting it straight into the
 * component field used to produce a value that [ComponentName.unflattenFromString] rejected —
 * which the launch path then dropped silently, leaving an intent with no component at all.
 */
internal fun resolveIntentComponentName(
    rawComponent: String?,
    rawPackage: String?,
): ComponentName? {
    val component = rawComponent?.trim().orEmpty()
    if (component.isEmpty()) return null
    if (component.contains('/')) return ComponentName.unflattenFromString(component)

    val packageName = rawPackage?.trim().orEmpty()
    if (packageName.isEmpty()) return null
    // unflattenFromString also handles the ".RelativeClass" shorthand for us.
    return ComponentName.unflattenFromString("$packageName/$component")
}

/**
 * Normalised `package/class` text to store, so a tile saved from a bare class name round-trips
 * as something unambiguous. Falls back to the raw text when it cannot be resolved.
 */
internal fun normalizeIntentComponent(rawComponent: String?, rawPackage: String?): String? {
    val trimmed = rawComponent?.trim()?.ifBlank { null } ?: return null
    return resolveIntentComponentName(trimmed, rawPackage)?.flattenToString() ?: trimmed
}

/**
 * Describes what is wrong with an intent target, or null when it looks dispatchable.
 *
 * Checks the component actually exists *as the selected type*, which catches both a mistyped
 * class and the commoner mistake of pointing an Activity-type tile at a BroadcastReceiver.
 *
 * Deliberately returns null when the target package is not visible to us: Android 11+ package
 * visibility means `getActivityInfo` and friends throw for packages outside our `<queries>`
 * filter, and reporting those as "not found" would be a false alarm. Better to stay quiet than
 * to block a save that would have worked.
 */
internal fun describeIntentTargetProblem(
    context: Context,
    rawComponent: String?,
    rawPackage: String?,
    type: IntentType,
): String? {
    val raw = rawComponent?.trim().orEmpty()
    if (raw.isEmpty()) return null // the component field is optional

    val component = resolveIntentComponentName(raw, rawPackage)
        ?: return if (raw.contains('/')) {
            "\"$raw\" isn't a valid package/class name."
        } else {
            "\"$raw\" has no package. Either fill in the Package field or write it as " +
                "package/Class."
        }

    val packageManager = context.packageManager
    val packageVisible = runCatching {
        packageManager.getPackageInfo(component.packageName, 0)
    }.isSuccess
    if (!packageVisible) return null

    val found = runCatching {
        when (type) {
            IntentType.ACTIVITY -> packageManager.getActivityInfo(component, 0)
            IntentType.BROADCAST_RECEIVER -> packageManager.getReceiverInfo(component, 0)
            IntentType.SERVICE -> packageManager.getServiceInfo(component, 0)
        }
    }.isSuccess
    if (found) return null

    val kind = when (type) {
        IntentType.ACTIVITY -> "activity"
        IntentType.BROADCAST_RECEIVER -> "broadcast receiver"
        IntentType.SERVICE -> "service"
    }
    return "${component.packageName} has no $kind named ${component.className}. " +
        "Check the class name, and check the Type buttons above match what it really is."
}
