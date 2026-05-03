package com.fabiantorrestech.androidshortcuthub

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for loading and saving the overlay layout state.
 * Replaces the duplicated loadOverlayState/saveOverlayState functions that
 * previously lived in ShortcutHubOverlayService, ShortcutHubAccessibilityService,
 * and LockscreenOverlayActivity.
 */
internal object OverlayStateRepository {

    fun load(context: Context): OverlayUiState {
        val prefs = context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val config = ShortcutHubSettings.load(context)
        val defaultState = buildDefaultState(config)
        val raw = prefs.getString(OVERLAY_PREFS_KEY_STATE, null) ?: return defaultState

        return OverlayRuntimeCache.getOrLoadState(raw, config) {
            parseState(raw, config, defaultState)
        }
    }

    fun save(context: Context, state: OverlayUiState) {
        val serialized = serializeState(state)
        context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(OVERLAY_PREFS_KEY_STATE, serialized)
            .apply()
        OverlayRuntimeCache.updateState(serialized, ShortcutHubSettings.load(context), state)
    }

    /**
     * Exposed internally so BackupManager can use it to get the serialized overlay JSON
     * without duplicating serialization logic.
     */
    internal fun serializeState(state: OverlayUiState): String {
        val root = JSONObject().apply {
            put("showGrid", state.showGrid)
            put("dpadOffsetX", state.dpadOffsetX.toDouble())
            put("dpadOffsetY", state.dpadOffsetY.toDouble())
            put("topPanelOffsetX", state.topPanelOffsetX.toDouble())
            put("topPanelOffsetY", state.topPanelOffsetY.toDouble())
            put("nextTileId", state.nextTileId)
            put(
                "tiles",
                JSONArray().also { arr ->
                    state.tiles.forEach { tile ->
                        arr.put(
                            JSONObject().apply {
                                put("id", tile.id)
                                put("row", tile.row)
                                put("column", tile.column)
                                put("rowSpan", tile.rowSpan)
                                put("columnSpan", tile.columnSpan)
                                tile.customLabel?.let { put("customLabel", it) }
                                tile.customFontUri?.let { put("customFontUri", it) }
                                tile.customFontName?.let { put("customFontName", it) }
                                tile.customTextScale?.let { put("customTextScale", it.toDouble()) }
                                tile.customBoldText?.let { put("customBoldText", it) }
                                when (tile) {
                                    is AppTileState -> {
                                        put("type", TILE_TYPE_APP)
                                        put("label", tile.app.label)
                                        tile.app.componentName?.let { put("component", it.flattenToString()) }
                                        tile.app.launchIntentUri?.let { put("launchIntentUri", it) }
                                        tile.app.launchIntentPackage?.let { put("launchIntentPackage", it) }
                                        putAppTileIconConfig(tile.iconConfig)
                                    }
                                    is WidgetTileState -> {
                                        put("type", TILE_TYPE_WIDGET)
                                        put("appWidgetId", tile.appWidgetId)
                                        put("providerComponent", tile.providerComponent)
                                    }
                                    is SystemSliderTileState -> {
                                        put("type", TILE_TYPE_SYSTEM_SLIDER)
                                        put("sliderType", tile.config.sliderType.name)
                                        put("streamMode", tile.config.streamMode.name)
                                        put("singleStream", tile.config.singleStream.name)
                                        put("buttonPlacement", tile.config.buttonPlacement.name)
                                        put("notchMode", tile.config.notchMode.name)
                                        put("showNotches", tile.config.showNotches)
                                        put("buttonStepSize", tile.config.buttonStepSize)
                                        put("showOutline", tile.config.showOutline)
                                        put("buttonHapticsEnabled", tile.config.buttonHapticsEnabled)
                                        put("notchHapticsEnabled", tile.config.notchHapticsEnabled)
                                    }
                                    is IntentTileState -> {
                                        put("type", TILE_TYPE_INTENT)
                                        put("intentAction", tile.intentAction)
                                        put("intentType", tile.intentType.name)
                                        tile.intentPackage?.let { put("intentPackage", it) }
                                        tile.intentComponent?.let { put("intentComponent", it) }
                                        tile.intentDataUri?.let { put("intentDataUri", it) }
                                        if (tile.intentExtras.isNotEmpty()) {
                                            put(
                                                "intentExtras",
                                                JSONObject().also { obj ->
                                                    tile.intentExtras.forEach { (k, v) -> obj.put(k, v) }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun buildDefaultState(config: ShortcutHubConfig): OverlayUiState = OverlayUiState(
        gridRows = config.gridRows,
        gridColumns = config.gridColumns,
        defaultTextScale = config.defaultTextScale,
        defaultBoldText = config.defaultBoldText,
        defaultFontUri = config.defaultFontUri,
        defaultFontName = config.defaultFontName,
        defaultTextColorMode = config.defaultTextColorMode,
        defaultTextColorHex = config.defaultTextColorHex,
        hapticFeedbackEnabled = config.hapticFeedbackEnabled,
        panelHandleLocked = config.panelHandleLocked,
        showPanelHandle = config.showPanelHandle,
        overlayBackgroundAlpha = config.overlayBackgroundAlpha,
        showOverLockscreen = config.showOverLockscreen,
    )

    private fun parseState(
        raw: String,
        config: ShortcutHubConfig,
        defaultState: OverlayUiState,
    ): OverlayUiState = runCatching {
        val root = JSONObject(raw)
        val tilesArray = root.optJSONArray("tiles") ?: JSONArray()
        val tiles = buildList<TileState> {
            for (i in 0 until tilesArray.length()) {
                val item = tilesArray.getJSONObject(i)
                val id = item.getInt("id")
                val row = item.getInt("row")
                val column = item.getInt("column")
                val rowSpan = item.optInt("rowSpan", 1).coerceAtLeast(1)
                val columnSpan = item.optInt("columnSpan", 1).coerceAtLeast(1)
                val customLabel = item.optString("customLabel").takeIf { it.isNotBlank() }
                val customFontUri = item.optString("customFontUri").takeIf { it.isNotBlank() }
                val customFontName = item.optString("customFontName").takeIf { it.isNotBlank() }
                val customTextScale = if (item.has("customTextScale")) {
                    item.getDouble("customTextScale").toFloat()
                        .coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
                } else {
                    null
                }
                val customBoldText = if (item.has("customBoldText")) {
                    item.getBoolean("customBoldText")
                } else {
                    null
                }

                when (item.optString("type", TILE_TYPE_APP)) {
                    TILE_TYPE_WIDGET -> {
                        val appWidgetId = item.optInt("appWidgetId", -1)
                        val providerComponent = item.optString("providerComponent").takeIf { it.isNotBlank() }
                        if (appWidgetId < 0 || providerComponent == null) continue
                        add(
                            WidgetTileState(
                                id = id,
                                row = row,
                                column = column,
                                rowSpan = rowSpan,
                                columnSpan = columnSpan,
                                appWidgetId = appWidgetId,
                                providerComponent = providerComponent,
                                customLabel = customLabel,
                            ),
                        )
                    }
                    TILE_TYPE_SYSTEM_SLIDER -> {
                        val sliderType = SliderType.entries.firstOrNull {
                            it.name == item.optString("sliderType")
                        } ?: continue
                        val streamMode = StreamMode.entries.firstOrNull {
                            it.name == item.optString("streamMode", StreamMode.DEFAULT.name)
                        } ?: StreamMode.DEFAULT
                        val singleStream = AudioStreamType.entries.firstOrNull {
                            it.name == item.optString("singleStream", AudioStreamType.MUSIC.name)
                        } ?: AudioStreamType.MUSIC
                        val buttonPlacement = SliderButtonPlacement.entries.firstOrNull {
                            it.name == item.optString("buttonPlacement", SliderButtonPlacement.SPLIT.name)
                        } ?: SliderButtonPlacement.SPLIT
                        val notchMode = SliderNotchMode.entries.firstOrNull {
                            it.name == item.optString("notchMode", SliderNotchMode.LOCK_AND_SLIDE.name)
                        } ?: SliderNotchMode.LOCK_AND_SLIDE
                        val showNotches = item.optBoolean("showNotches", true)
                        val buttonStepSize = item.optInt("buttonStepSize", 1)
                        val showOutline = item.optBoolean("showOutline", false)
                        val buttonHapticsEnabled = item.optBoolean("buttonHapticsEnabled", false)
                        val notchHapticsEnabled = item.optBoolean("notchHapticsEnabled", false)
                        add(
                            SystemSliderTileState(
                                id = id,
                                row = row,
                                column = column,
                                rowSpan = rowSpan,
                                columnSpan = columnSpan,
                                config = SystemSliderConfig(
                                    sliderType = sliderType,
                                    streamMode = streamMode,
                                    singleStream = singleStream,
                                    buttonPlacement = buttonPlacement,
                                    notchMode = notchMode,
                                    showNotches = showNotches,
                                    buttonStepSize = buttonStepSize,
                                    showOutline = showOutline,
                                    buttonHapticsEnabled = buttonHapticsEnabled,
                                    notchHapticsEnabled = notchHapticsEnabled,
                                ),
                                customLabel = customLabel,
                            ),
                        )
                    }
                    TILE_TYPE_INTENT -> {
                        val action = item.optString("intentAction").takeIf { it.isNotBlank() }
                            ?: continue
                        val intentType = IntentType.entries.firstOrNull {
                            it.name == item.optString("intentType", IntentType.ACTIVITY.name)
                        } ?: IntentType.ACTIVITY
                        val extrasObj = item.optJSONObject("intentExtras")
                        val extras = buildMap<String, String> {
                            extrasObj?.keys()?.forEach { k -> put(k, extrasObj.getString(k)) }
                        }
                        add(
                            IntentTileState(
                                id = id,
                                row = row,
                                column = column,
                                rowSpan = rowSpan,
                                columnSpan = columnSpan,
                                intentAction = action,
                                intentType = intentType,
                                intentPackage = item.optString("intentPackage").takeIf { it.isNotBlank() },
                                intentComponent = item.optString("intentComponent").takeIf { it.isNotBlank() },
                                intentDataUri = item.optString("intentDataUri").takeIf { it.isNotBlank() },
                                intentExtras = extras,
                                customLabel = customLabel,
                                customFontUri = customFontUri,
                                customFontName = customFontName,
                                customTextScale = customTextScale,
                                customBoldText = customBoldText,
                            ),
                        )
                    }
                    else -> {
                        val component = item.optString("component").takeIf { it.isNotBlank() }
                            ?.let(ComponentName::unflattenFromString)
                        val launchIntentUri = item.optString("launchIntentUri").takeIf { it.isNotBlank() }
                        val launchIntentPackage = item.optString("launchIntentPackage").takeIf { it.isNotBlank() }
                        add(
                            AppTileState(
                                id = id,
                                row = row,
                                column = column,
                                rowSpan = rowSpan,
                                columnSpan = columnSpan,
                                app = LaunchableApp(
                                    label = item.getString("label"),
                                    componentName = component,
                                    launchIntentUri = launchIntentUri,
                                    launchIntentPackage = launchIntentPackage,
                                ),
                                iconConfig = parseAppTileIconConfig(item),
                                customLabel = customLabel,
                                customFontUri = customFontUri,
                                customFontName = customFontName,
                                customTextScale = customTextScale,
                                customBoldText = customBoldText,
                            ),
                        )
                    }
                }
            }
        }.filter { tile ->
            tile.row < config.gridRows &&
                tile.column < config.gridColumns &&
                tile.row + tile.rowSpan <= config.gridRows &&
                tile.column + tile.columnSpan <= config.gridColumns
        }

        OverlayUiState(
            showGrid = root.optBoolean("showGrid", false),
            dpadOffsetX = root.optDouble("dpadOffsetX", 24.0).toFloat(),
            dpadOffsetY = root.optDouble("dpadOffsetY", 220.0).toFloat(),
            topPanelOffsetX = root.optDouble("topPanelOffsetX", 16.0).toFloat(),
            topPanelOffsetY = root.optDouble("topPanelOffsetY", 16.0).toFloat(),
            nextTileId = root.optInt("nextTileId", tiles.maxOfOrNull { it.id + 1 } ?: 1),
            tiles = tiles,
            gridRows = config.gridRows,
            gridColumns = config.gridColumns,
            defaultTextScale = config.defaultTextScale,
            defaultBoldText = config.defaultBoldText,
            defaultFontUri = config.defaultFontUri,
            defaultFontName = config.defaultFontName,
            defaultTextColorMode = config.defaultTextColorMode,
            defaultTextColorHex = config.defaultTextColorHex,
            hapticFeedbackEnabled = config.hapticFeedbackEnabled,
            panelHandleLocked = config.panelHandleLocked,
            showPanelHandle = config.showPanelHandle,
            overlayBackgroundAlpha = config.overlayBackgroundAlpha,
            showOverLockscreen = config.showOverLockscreen,
        )
    }.getOrDefault(defaultState)
}
