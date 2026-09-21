package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "ShortcutHubSwitch"

/**
 * The app-wide on/off switch at the top of the main screen.
 *
 * Off means off: nothing can open the hub, the edge handles come down, the overlay service is
 * stopped and the accessibility service stops receiving events. It is also the way out of a stuck
 * hub. Every flip, in either direction, throws the runtime state away - the layout, font, widget,
 * slider and app caches, the widget-bind handshake, the trigger cooldown, and the edge-handle
 * controller along with its suppression latches - and switching on rebuilds all of it from scratch.
 *
 * Kept in its own prefs file, like [BackupPrefs], so a backup restore never flips it and toggling it
 * never schedules an auto-backup. The file is also excluded from Android's cloud backup (see
 * res/xml/backup_rules.xml), so a reinstall never comes up silently switched off.
 */
internal object HubSwitch {
    private const val PREFS_NAME = "shortcut_hub_switch"
    private const val KEY_ENABLED = "hub_enabled"

    /** Cheap enough for every trigger path: SharedPreferences keeps the file in memory once read. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /** Main thread only - it drives both services and the lockscreen activity directly. */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled == isEnabled(context)) return
        val app = context.applicationContext
        // Written first, so every gate is already closed (or open) before anything else moves.
        // apply() updates the in-memory copy synchronously; only the disk write is deferred.
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.d(TAG, "Hub switched ${if (enabled) "on" else "off"}")
        if (enabled) {
            // Reset on the way up too: the layout editor stays usable while the hub is off, and it
            // refills the layout and widget caches as it goes.
            resetRuntimeState(app)
            startUp(app)
        } else {
            shutDown(app)
            resetRuntimeState(app)
        }
    }

    private fun shutDown(app: Context) {
        step("accessibility dormant") { ShortcutHubAccessibilityService.instance?.enterDormant() }
        // onDestroy removes the overlay, unregisters the receivers, cancels any show still loading
        // and stops widget listening. START_STICKY does not bring it back: that only follows the
        // process dying while the service is still started.
        step("overlay service stop") {
            app.stopService(Intent(app, ShortcutHubOverlayService::class.java))
        }
        step("lockscreen finish") { LockscreenOverlayActivity.finishIfActive() }
    }

    private fun startUp(app: Context) {
        step("accessibility wake") { ShortcutHubAccessibilityService.instance?.leaveDormant() }
        step("overlay pre-warm") { (app as? ShortcutHubApplication)?.warmOverlayServiceIfEligible() }
    }

    /**
     * Empties every piece of process-wide hub state that could be holding a stuck or stale value.
     * Everything here refills itself lazily the next time the hub opens.
     *
     * Deliberately left alone: the widget host instance (a second host on the same id would
     * silence the first), the Application's scope and pending auto-backup, and the one-time orphan
     * widget cleanup, which could delete a widget that so far exists only in the editor's unsaved
     * draft.
     */
    private fun resetRuntimeState(app: Context) {
        step("widget bind handshake") { WidgetBindingCoordinator.clear() }
        step("widget views") { WidgetViewCache.clear() }
        step("widget host") { ShortcutHubWidgetHost.getInstance(app).resetListening() }
        step("sliders") { SystemSliderState.clearInstances() }
        step("layouts and fonts") { OverlayRuntimeCache.clear() }
        step("installed apps") { clearInstalledAppCache() }
        step("trigger cooldown") { resetTriggerCooldown() }
    }

    /** One failed step must not stop the rest: a partial reset is still better than none. */
    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.e(TAG, "Hub switch step failed: $name", it) }
    }
}
