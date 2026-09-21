package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal const val TRIGGER_PREFS_NAME = "shortcut_hub_triggers"
internal const val TRIGGER_PREFS_KEY_CONFIG = "trigger_config"

/**
 * Persists [TriggerConfig] as a single JSON blob in its own prefs file.
 *
 * Every bound check lives in [parseConfig], so a hand-edited or corrupt backup can never produce
 * a zero-size or full-screen trigger window. A parse failure falls back to defaults rather than
 * throwing, because this is read from the accessibility service, where an uncaught throw means a
 * rebind loop.
 */
object TriggerRepository {

    fun load(context: Context): TriggerConfig {
        val raw = context.getSharedPreferences(TRIGGER_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(TRIGGER_PREFS_KEY_CONFIG, null) ?: return TriggerConfig()
        return parseConfig(raw)
    }

    fun save(context: Context, config: TriggerConfig) {
        context.getSharedPreferences(TRIGGER_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(TRIGGER_PREFS_KEY_CONFIG, serializeConfig(config))
            .apply()
    }

    /**
     * Refreshes display labels for apps that are still installed.
     * Keeps uninstalled apps in the lists with their last known label.
     * Does NOT auto-add newly installed apps to either list.
     */
    fun syncAppLabels(context: Context, config: TriggerConfig): TriggerConfig {
        val installedLabels: Map<String, String> = context.packageManager
            .getInstalledApplications(0)
            .associate { info ->
                info.packageName to (
                    context.packageManager.getApplicationLabel(info)
                        ?.toString()
                        ?.ifBlank { info.packageName }
                        ?: info.packageName
                    )
            }

        fun syncList(list: List<TriggerAppEntry>): List<TriggerAppEntry> =
            list.map { entry ->
                val freshLabel = installedLabels[entry.packageName]
                if (freshLabel != null && freshLabel != entry.label) entry.copy(label = freshLabel)
                else entry
            }

        return config.copy(
            blockApps = syncList(config.blockApps),
            allowApps = syncList(config.allowApps),
        )
    }

    internal fun serializeConfig(config: TriggerConfig): String =
        JSONObject().apply {
            put("masterEnabled", config.masterEnabled)
            put("leftHandle", serializeHandle(config.leftHandle))
            put("rightHandle", serializeHandle(config.rightHandle))
            put("suppressBackGesture", config.suppressBackGesture)
            put("showOnLockscreen", config.showOnLockscreen)
            put("suppressWhenImmersive", config.suppressWhenImmersive)
            put("hapticOnFire", config.hapticOnFire)
            put("refireCooldownMs", config.refireCooldownMs)
            put("filterMode", config.filterMode.name)
            put("blockApps", serializeAppList(config.blockApps))
            put("allowApps", serializeAppList(config.allowApps))
            put("assistGestureEnabled", config.assistGestureEnabled)
            put("livePreviewEnabled", config.livePreviewEnabled)
        }.toString()

    private fun serializeHandle(handle: EdgeHandleConfig): JSONObject =
        JSONObject().apply {
            put("enabled", handle.enabled)
            put("touchDepthDp", handle.touchDepthDp)
            put("protrusionDp", handle.protrusionDp)
            put("lengthDp", handle.lengthDp)
            put("verticalBiasFraction", handle.verticalBiasFraction.toDouble())
            put("activation", handle.activation.name)
            put("activationSlopDp", handle.activationSlopDp)
            put("longPressMs", handle.longPressMs)
            put("visibility", handle.visibility.name)
            put("revealLingerMs", handle.revealLingerMs)
            put("restingAlpha", handle.restingAlpha.toDouble())
            put("activeAlpha", handle.activeAlpha.toDouble())
            handle.colorHex?.let { put("colorHex", it) }
            put("cornerRadiusDp", handle.cornerRadiusDp)
        }

    private fun serializeAppList(apps: List<TriggerAppEntry>): JSONArray =
        JSONArray().also { arr ->
            apps.forEach { entry ->
                arr.put(
                    JSONObject().apply {
                        put("packageName", entry.packageName)
                        put("label", entry.label)
                    },
                )
            }
        }

    private fun parseConfig(raw: String): TriggerConfig = runCatching {
        val root = JSONObject(raw)
        val defaults = TriggerConfig()
        TriggerConfig(
            masterEnabled = root.optBoolean("masterEnabled", false),
            leftHandle = parseHandle(root.optJSONObject("leftHandle"), defaults.leftHandle),
            rightHandle = parseHandle(root.optJSONObject("rightHandle"), defaults.rightHandle),
            suppressBackGesture = root.optBoolean("suppressBackGesture", true),
            showOnLockscreen = root.optBoolean("showOnLockscreen", true),
            suppressWhenImmersive = root.optBoolean("suppressWhenImmersive", false),
            hapticOnFire = root.optBoolean("hapticOnFire", true),
            refireCooldownMs = root.optInt("refireCooldownMs", 350).coerceIn(0, 5_000),
            filterMode = TriggerFilterMode.entries.firstOrNull {
                it.name == root.optString("filterMode")
            } ?: TriggerFilterMode.BLACKLIST,
            blockApps = parseAppList(root.optJSONArray("blockApps")),
            allowApps = parseAppList(root.optJSONArray("allowApps")),
            assistGestureEnabled = root.optBoolean("assistGestureEnabled", false),
            livePreviewEnabled = root.optBoolean("livePreviewEnabled", true),
        )
    }.getOrDefault(TriggerConfig())

    private fun parseHandle(obj: JSONObject?, fallback: EdgeHandleConfig): EdgeHandleConfig {
        obj ?: return fallback
        return EdgeHandleConfig(
            side = fallback.side,
            enabled = obj.optBoolean("enabled", fallback.enabled),
            touchDepthDp = obj.optInt("touchDepthDp", fallback.touchDepthDp).coerceIn(6, 48),
            protrusionDp = obj.optInt("protrusionDp", fallback.protrusionDp).coerceIn(1, 24),
            lengthDp = obj.optInt("lengthDp", fallback.lengthDp).coerceIn(48, 200),
            verticalBiasFraction = obj.optDouble(
                "verticalBiasFraction",
                fallback.verticalBiasFraction.toDouble(),
            ).toFloat().coerceIn(0f, 1f),
            activation = EdgeActivation.entries.firstOrNull {
                it.name == obj.optString("activation")
            } ?: fallback.activation,
            activationSlopDp = obj.optInt("activationSlopDp", fallback.activationSlopDp)
                .coerceIn(8, 120),
            longPressMs = obj.optInt("longPressMs", fallback.longPressMs).coerceIn(80, 1_000),
            visibility = EdgeVisibility.entries.firstOrNull {
                it.name == obj.optString("visibility")
            } ?: fallback.visibility,
            revealLingerMs = obj.optInt("revealLingerMs", fallback.revealLingerMs)
                .coerceIn(0, 15_000),
            restingAlpha = obj.optDouble("restingAlpha", fallback.restingAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            activeAlpha = obj.optDouble("activeAlpha", fallback.activeAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            colorHex = normalizeHexColor(obj.optString("colorHex", "")),
            cornerRadiusDp = obj.optInt("cornerRadiusDp", fallback.cornerRadiusDp)
                .coerceIn(0, 32),
        )
    }

    private fun parseAppList(arr: JSONArray?): List<TriggerAppEntry> {
        arr ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
                add(TriggerAppEntry(packageName = pkg, label = obj.optString("label").ifBlank { pkg }))
            }
        }
    }
}
