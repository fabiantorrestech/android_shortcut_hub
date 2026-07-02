package com.fabiantorrestech.androidshortcuthub

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
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
    @Volatile
    private var overlayRuntimePrepared = false

    // Held as a field — SharedPreferences only holds weak references to listeners.
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scheduleAutoBackup()
    }

    override fun onCreate() {
        super.onCreate()
        registerAutoBackupListeners()
    }

    internal fun prepareOverlayRuntimeIfEligible() {
        if (overlayRuntimePrepared) return
        synchronized(this) {
            if (overlayRuntimePrepared) return
            val widgetHost = ShortcutHubWidgetHost.getInstance(this)
            widgetHost.startListening(this)
            cleanOrphanWidgetIds(widgetHost)
            warmOverlayServiceIfEligible()
            overlayRuntimePrepared = true
        }
    }

    private fun registerAutoBackupListeners() {
        getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        getSharedPreferences(GRAYSCALE_PREFS_NAME, Context.MODE_PRIVATE)
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
            val isV2 = root.optInt("version") >= 2
            val widgetIds = mutableSetOf<Int>()

            fun addIfWidget(item: JSONObject) {
                if (item.optString("type") == "widget" && item.has("appWidgetId")) {
                    widgetIds.add(item.getInt("appWidgetId"))
                }
            }

            fun extractFrom(layoutObj: JSONObject?) {
                val arr = layoutObj?.optJSONArray("tiles") ?: return
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    addIfWidget(item)
                    // Widgets nested in a widget stack must also count as "in use"; otherwise the
                    // orphan cleanup deletes their appWidgetIds and they render as "Unavailable".
                    if (item.optString("type") == "widget_stack") {
                        val nested = item.optJSONArray("widgets") ?: continue
                        for (j in 0 until nested.length()) {
                            nested.optJSONObject(j)?.let(::addIfWidget)
                        }
                    }
                }
            }

            if (isV2) {
                extractFrom(root.optJSONObject("portrait"))
                if (!root.isNull("landscape")) extractFrom(root.optJSONObject("landscape"))
            } else {
                extractFrom(root)
            }
            widgetIds
        }.getOrDefault(emptySet())
    }

    private fun warmOverlayServiceIfEligible() {
        val config = ShortcutHubSettings.load(this)
        if (!config.useAccessibilityService && ShortcutHubOverlayService.canDrawOverlays(this)) {
            ShortcutHubOverlayService.prewarm(this)
            // Pre-warm state and font caches so the first toggle doesn't pay cold I/O costs.
            appScope.launch {
                val (portrait, landscape) = OverlayStateRepository.loadBoth(applicationContext)
                OverlayRuntimeCache.preloadFonts(portrait, landscape) { uriString ->
                    val parsedUri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@preloadFonts null
                    runCatching {
                        contentResolver.openFileDescriptor(parsedUri, "r")?.use { descriptor ->
                            FontFamily(Typeface.Builder(descriptor.fileDescriptor).build())
                        }
                    }.getOrNull()
                }
            }
        }
    }
}
