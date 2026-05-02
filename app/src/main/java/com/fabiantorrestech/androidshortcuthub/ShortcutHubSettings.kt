package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val SETTINGS_PREFS_NAME = "shortcut_hub_settings"
private const val KEY_GRID_ROWS = "grid_rows"
private const val KEY_GRID_COLUMNS = "grid_columns"
private const val KEY_DEFAULT_TEXT_SCALE = "default_text_scale"
private const val KEY_DEFAULT_FONT_URI = "default_font_uri"
private const val KEY_DEFAULT_FONT_NAME = "default_font_name"
private const val KEY_DEFAULT_TEXT_COLOR_MODE = "default_text_color_mode"
private const val KEY_DEFAULT_TEXT_COLOR_HEX = "default_text_color_hex"
private const val KEY_HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
private const val KEY_PANEL_HANDLE_LOCKED = "panel_handle_locked"
private const val KEY_OVERLAY_BG_ALPHA = "overlay_bg_alpha"
private const val KEY_SHOW_OVER_LOCKSCREEN = "show_over_lockscreen"
private const val KEY_DISMISS_ACCESSIBILITY_BANNER = "dismiss_accessibility_banner"
private const val KEY_USE_ACCESSIBILITY_SERVICE = "use_accessibility_service"
private const val KEY_DISMISS_ON_SCREEN_OFF = "dismiss_on_screen_off"

enum class DefaultTextColorMode {
    SYSTEM,
    BLACK,
    WHITE,
    CUSTOM,
}

data class ShortcutHubConfig(
    val gridRows: Int = 8,
    val gridColumns: Int = 4,
    val defaultTextScale: Float = 1.0f,
    val defaultFontUri: String? = null,
    val defaultFontName: String? = null,
    val defaultTextColorMode: DefaultTextColorMode = DefaultTextColorMode.SYSTEM,
    val defaultTextColorHex: String? = null,
    val hapticFeedbackEnabled: Boolean = true,
    val panelHandleLocked: Boolean = false,
    val overlayBackgroundAlpha: Float = 0.33f,
    val showOverLockscreen: Boolean = false,
    val dismissAccessibilityBanner: Boolean = false,
    val useAccessibilityService: Boolean = false,
    val dismissOnScreenOff: Boolean = true,
)

object ShortcutHubSettings {
    fun load(context: Context): ShortcutHubConfig {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        return ShortcutHubConfig(
            gridRows = prefs.getInt(KEY_GRID_ROWS, 8).coerceIn(1, 24),
            gridColumns = prefs.getInt(KEY_GRID_COLUMNS, 4).coerceIn(1, 16),
            defaultTextScale = prefs.getFloat(KEY_DEFAULT_TEXT_SCALE, 1.0f).coerceIn(0.5f, 3.0f),
            defaultFontUri = prefs.getString(KEY_DEFAULT_FONT_URI, null),
            defaultFontName = prefs.getString(KEY_DEFAULT_FONT_NAME, null),
            defaultTextColorMode = prefs.getString(
                KEY_DEFAULT_TEXT_COLOR_MODE,
                DefaultTextColorMode.SYSTEM.name,
            )?.let { raw ->
                DefaultTextColorMode.entries.firstOrNull { it.name == raw }
            } ?: DefaultTextColorMode.SYSTEM,
            defaultTextColorHex = normalizeHexColor(prefs.getString(KEY_DEFAULT_TEXT_COLOR_HEX, null)),
            hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, true),
            panelHandleLocked = prefs.getBoolean(KEY_PANEL_HANDLE_LOCKED, false),
            overlayBackgroundAlpha = prefs.getFloat(KEY_OVERLAY_BG_ALPHA, 0.33f).coerceIn(0f, 0.9f),
            showOverLockscreen = prefs.getBoolean(KEY_SHOW_OVER_LOCKSCREEN, false),
            dismissAccessibilityBanner = prefs.getBoolean(KEY_DISMISS_ACCESSIBILITY_BANNER, false),
            useAccessibilityService = prefs.getBoolean(KEY_USE_ACCESSIBILITY_SERVICE, false),
            dismissOnScreenOff = prefs.getBoolean(KEY_DISMISS_ON_SCREEN_OFF, true),
        )
    }

    fun save(context: Context, config: ShortcutHubConfig) {
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_GRID_ROWS, config.gridRows.coerceIn(1, 24))
            .putInt(KEY_GRID_COLUMNS, config.gridColumns.coerceIn(1, 16))
            .putFloat(KEY_DEFAULT_TEXT_SCALE, config.defaultTextScale.coerceIn(0.5f, 3.0f))
            .putString(KEY_DEFAULT_FONT_URI, config.defaultFontUri)
            .putString(KEY_DEFAULT_FONT_NAME, config.defaultFontName)
            .putString(KEY_DEFAULT_TEXT_COLOR_MODE, config.defaultTextColorMode.name)
            .putString(KEY_DEFAULT_TEXT_COLOR_HEX, normalizeHexColor(config.defaultTextColorHex))
            .putBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, config.hapticFeedbackEnabled)
            .putBoolean(KEY_PANEL_HANDLE_LOCKED, config.panelHandleLocked)
            .putFloat(KEY_OVERLAY_BG_ALPHA, config.overlayBackgroundAlpha.coerceIn(0f, 0.9f))
            .putBoolean(KEY_SHOW_OVER_LOCKSCREEN, config.showOverLockscreen)
            .putBoolean(KEY_DISMISS_ACCESSIBILITY_BANNER, config.dismissAccessibilityBanner)
            .putBoolean(KEY_USE_ACCESSIBILITY_SERVICE, config.useAccessibilityService)
            .putBoolean(KEY_DISMISS_ON_SCREEN_OFF, config.dismissOnScreenOff)
            .apply()
    }

    fun pruneOutOfBoundsTiles(
        context: Context,
        rows: Int,
        columns: Int,
    ) {
        val prefs = context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(OVERLAY_PREFS_KEY_STATE, null) ?: return
        val sanitized = runCatching {
            val root = JSONObject(raw)
            val tilesArray = root.optJSONArray("tiles") ?: JSONArray()
            val filtered = JSONArray()
            for (index in 0 until tilesArray.length()) {
                val item = tilesArray.getJSONObject(index)
                val row = item.optInt("row", Int.MAX_VALUE)
                val column = item.optInt("column", Int.MAX_VALUE)
                val rowSpan = item.optInt("rowSpan", 1).coerceAtLeast(1)
                val columnSpan = item.optInt("columnSpan", 1).coerceAtLeast(1)
                if (row < rows &&
                    column < columns &&
                    row + rowSpan <= rows &&
                    column + columnSpan <= columns
                ) {
                    filtered.put(item)
                }
            }
            root.put("tiles", filtered)
            root.toString()
        }.getOrNull() ?: return

        prefs.edit().putString(OVERLAY_PREFS_KEY_STATE, sanitized).apply()
    }
}

fun normalizeHexColor(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    val body = normalized.removePrefix("#")
    return if (body.length == 6 || body.length == 8) {
        body.uppercase().takeIf { hex -> hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
            ?.let { "#$it" }
    } else {
        null
    }
}
