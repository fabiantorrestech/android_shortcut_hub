package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal const val GRAYSCALE_PREFS_NAME = "shortcut_hub_grayscale"
internal const val GRAYSCALE_PREFS_KEY_CONFIG = "grayscale_config"

object GrayscaleRepository {

    fun load(context: Context): GrayscaleConfig {
        val raw = context.getSharedPreferences(GRAYSCALE_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(GRAYSCALE_PREFS_KEY_CONFIG, null) ?: return GrayscaleConfig()
        return parseConfig(raw)
    }

    fun save(context: Context, config: GrayscaleConfig) {
        context.getSharedPreferences(GRAYSCALE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(GRAYSCALE_PREFS_KEY_CONFIG, serializeConfig(config))
            .apply()
    }

    /**
     * Refreshes display labels for apps that are still installed.
     * Keeps uninstalled apps in the lists with their last known label.
     * Does NOT auto-add newly installed apps to either list.
     */
    fun syncAppLabels(context: Context, config: GrayscaleConfig): GrayscaleConfig {
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

        fun syncList(list: List<GrayscaleAppEntry>): List<GrayscaleAppEntry> =
            list.map { entry ->
                val freshLabel = installedLabels[entry.packageName]
                if (freshLabel != null && freshLabel != entry.label) entry.copy(label = freshLabel)
                else entry
            }

        return config.copy(
            whitelistApps = syncList(config.whitelistApps),
            blacklistApps = syncList(config.blacklistApps),
        )
    }

    internal fun serializeConfig(config: GrayscaleConfig): String =
        JSONObject().apply {
            put("enabled", config.enabled)
            put("captureMode", config.captureMode.name)
            put("activeMode", config.activeMode.name)
            put("whitelistApps", serializeAppList(config.whitelistApps))
            put("blacklistApps", serializeAppList(config.blacklistApps))
        }.toString()

    private fun serializeAppList(apps: List<GrayscaleAppEntry>): JSONArray =
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

    private fun parseConfig(raw: String): GrayscaleConfig = runCatching {
        val root = JSONObject(raw)
        GrayscaleConfig(
            enabled = root.optBoolean("enabled", false),
            captureMode = GrayscaleMode.entries.firstOrNull {
                it.name == root.optString("captureMode")
            } ?: GrayscaleMode.SIMPLE,
            activeMode = GrayscaleFilterMode.entries.firstOrNull {
                it.name == root.optString("activeMode")
            } ?: GrayscaleFilterMode.BLACKLIST,
            whitelistApps = parseAppList(root.optJSONArray("whitelistApps")),
            blacklistApps = parseAppList(root.optJSONArray("blacklistApps")),
        )
    }.getOrDefault(GrayscaleConfig())

    private fun parseAppList(arr: JSONArray?): List<GrayscaleAppEntry> {
        arr ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: continue
                add(GrayscaleAppEntry(packageName = pkg, label = obj.optString("label").ifBlank { pkg }))
            }
        }
    }
}
