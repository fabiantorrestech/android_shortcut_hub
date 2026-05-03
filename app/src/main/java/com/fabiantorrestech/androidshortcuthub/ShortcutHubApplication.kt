package com.fabiantorrestech.androidshortcuthub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val AUTO_BACKUP_DEBOUNCE_MS = 10 * 60 * 1000L // 10 minutes

class ShortcutHubApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoBackupJob: Job? = null

    // Held as a field — SharedPreferences only holds weak references to listeners.
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scheduleAutoBackup()
    }

    override fun onCreate() {
        super.onCreate()
        val widgetHost = ShortcutHubWidgetHost.getInstance(this)
        widgetHost.startListening(this)
        cleanOrphanWidgetIds(widgetHost)
        warmOverlayServiceIfEligible()
        registerAutoBackupListeners()
    }

    private fun registerAutoBackupListeners() {
        getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    private fun scheduleAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = appScope.launch {
            delay(AUTO_BACKUP_DEBOUNCE_MS)
            runCatching { BackupManager.writeAutoBackup(applicationContext) }
        }
    }

    private fun cleanOrphanWidgetIds(widgetHost: ShortcutHubWidgetHost) {
        val persistedIds = loadPersistedWidgetIds()
        widgetHost.appWidgetIds
            .filterNot { it in persistedIds }
            .forEach(widgetHost::deleteAppWidgetId)
    }

    private fun loadPersistedWidgetIds(): Set<Int> {
        val raw = getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(OVERLAY_PREFS_KEY_STATE, null)
            ?: return emptySet()

        return runCatching {
            val root = JSONObject(raw)
            val tilesArray = root.optJSONArray("tiles") ?: return@runCatching emptySet()
            buildSet {
                for (index in 0 until tilesArray.length()) {
                    val item = tilesArray.optJSONObject(index) ?: continue
                    if (item.optString("type") == "widget" && item.has("appWidgetId")) {
                        add(item.getInt("appWidgetId"))
                    }
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun warmOverlayServiceIfEligible() {
        val config = ShortcutHubSettings.load(this)
        if (!config.useAccessibilityService && ShortcutHubOverlayService.canDrawOverlays(this)) {
            ShortcutHubOverlayService.prewarm(this)
        }
    }
}
