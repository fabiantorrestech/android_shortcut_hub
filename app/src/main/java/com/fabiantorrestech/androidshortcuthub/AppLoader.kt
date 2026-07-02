package com.fabiantorrestech.androidshortcuthub

import android.content.Context

/**
 * Loads every installed app that exposes a launcher entry, as [LaunchableApp]s sorted by label.
 *
 * Used by the in-app layout editor's "Change app" chooser so the user can search and pick ANY
 * installed app (previously the chooser only listed apps already placed on the grid, which meant an
 * empty list inside a fresh scrollbox). Mirrors the app enumeration the runtime services already do.
 *
 * Enumerating packages can be slow — call this off the main thread.
 */
internal fun loadInstalledLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .mapNotNull { appInfo ->
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName) ?: return@mapNotNull null
            val component = launchIntent.component ?: return@mapNotNull null
            LaunchableApp(
                label = pm.getApplicationLabel(appInfo)?.toString().orEmpty().ifBlank { appInfo.packageName },
                componentName = component,
            )
        }
        .distinctBy { "${it.label}|${it.componentName?.flattenToString().orEmpty()}" }
        .sortedBy { it.label.lowercase() }
}
