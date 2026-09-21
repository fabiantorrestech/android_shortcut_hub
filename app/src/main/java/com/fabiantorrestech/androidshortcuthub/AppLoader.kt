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

/**
 * Process-level cache of [loadInstalledLaunchableApps].
 *
 * The editor's app chooser used to re-enumerate every installed package each time it opened, which
 * on a device with a few hundred apps is a visible stall every single time. The runtime overlay
 * already avoids this with its own `hasLoadedApps` guard; this gives the editor the same, following
 * the OverlayRuntimeCache precedent (which caches layouts and fonts, but not apps).
 *
 * Held for the life of the process and never invalidated by itself: nothing here observes
 * PACKAGE_ADDED, and a list that is at most one process stale is a fair trade for a picker that
 * never stalls. An app installed while Shortcut Hub is running is picked up on the next launch, or
 * sooner if the hub is switched off and on again ([HubSwitch] clears this).
 */
private object InstalledAppCache {
    @Volatile
    private var cached: List<LaunchableApp>? = null

    fun get(context: Context): List<LaunchableApp> =
        cached ?: loadInstalledLaunchableApps(context).also { cached = it }

    fun clear() {
        cached = null
    }
}

/** Part of [HubSwitch]'s reset. The next picker open enumerates again. */
internal fun clearInstalledAppCache() = InstalledAppCache.clear()

/** Cached [loadInstalledLaunchableApps]. Still enumerates on a cold cache — call off the main thread. */
internal fun loadInstalledLaunchableAppsCached(context: Context): List<LaunchableApp> =
    InstalledAppCache.get(context)
