package com.fabiantorrestech.androidshortcuthub

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for loading and saving the overlay layout state.
 *
 * Storage format v2: a single JSON object under OVERLAY_PREFS_KEY_STATE with:
 *   { "version": 2, "portrait": {...layout...}, "landscape": null | {...layout...} }
 *
 * v1 (old flat format) is automatically migrated to v2 on first load.
 */
internal object OverlayStateRepository {

    // ── Public API ───────────────────────────────────────────────────────────

    /** Load both orientations. Returns (portraitState, landscapeState). */
    fun loadBoth(context: Context): Pair<OverlayUiState, OverlayUiState> {
        val config = ShortcutHubSettings.load(context)
        val (portraitLayout, landscapeLayout) = loadBothLayouts(context, config)
        val portraitState = portraitLayout.mergeWithConfig(config)
        val landscapeState = (landscapeLayout ?: OverlayOrientationLayout(
            gridRows = config.gridRows,
            gridColumns = config.gridColumns,
        )).mergeWithConfig(config)
        return portraitState to landscapeState
    }

    /** Load a single orientation — used as a lightweight backward-compat path. */
    fun load(context: Context): OverlayUiState = loadBoth(context).first

    /** Persist one orientation's layout, preserving the other orientation unchanged. */
    fun saveLayout(context: Context, state: OverlayUiState, orientation: OverlayOrientation) {
        val config = ShortcutHubSettings.load(context)
        val prefs = context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(OVERLAY_PREFS_KEY_STATE, null)
        val root = parseV2Root(raw, config)

        val layoutJson = serializeLayout(state.extractLayout())
        when (orientation) {
            OverlayOrientation.PORTRAIT -> root.put("portrait", layoutJson)
            OverlayOrientation.LANDSCAPE -> root.put("landscape", layoutJson)
        }

        val serialized = root.toString()
        prefs.edit().putString(OVERLAY_PREFS_KEY_STATE, serialized).apply()
        OverlayRuntimeCache.invalidate()
    }

    /** Backward-compat single-arg save — saves portrait only. */
    fun save(context: Context, state: OverlayUiState) = saveLayout(context, state, OverlayOrientation.PORTRAIT)

    // ── Serialization helpers (internal so BackupManager can use them) ────────

    /** Serialize a full OverlayUiState as a v2 root JSON string (portrait only, landscape null). */
    internal fun serializeState(state: OverlayUiState): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("portrait", serializeLayout(state.extractLayout()))
        root.put("landscape", JSONObject.NULL)
        return root.toString()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Parse the raw stored JSON and return a mutable v2 root JSONObject.
     * Performs v1→v2 migration transparently.
     */
    private fun parseV2Root(raw: String?, config: ShortcutHubConfig): JSONObject {
        if (raw == null) {
            return JSONObject().apply {
                put("version", 2)
                put("portrait", serializeLayout(buildDefaultLayout(config)))
                put("landscape", JSONObject.NULL)
            }
        }

        return runCatching {
            val parsed = JSONObject(raw)
            if (parsed.has("version") && parsed.optInt("version") >= 2) {
                // Already v2
                parsed
            } else {
                // v1 flat format — migrate: treat entire JSON as portrait layout
                val portraitLayout = parseTilesAndOffsets(parsed, config)
                JSONObject().apply {
                    put("version", 2)
                    put("portrait", serializeLayout(portraitLayout))
                    put("landscape", JSONObject.NULL)
                }
            }
        }.getOrElse {
            JSONObject().apply {
                put("version", 2)
                put("portrait", serializeLayout(buildDefaultLayout(config)))
                put("landscape", JSONObject.NULL)
            }
        }
    }

    private fun loadBothLayouts(
        context: Context,
        config: ShortcutHubConfig,
    ): Pair<OverlayOrientationLayout, OverlayOrientationLayout?> {
        val prefs = context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(OVERLAY_PREFS_KEY_STATE, null)

        val cached = OverlayRuntimeCache.getCachedLayouts(raw)
        if (cached != null) return cached

        val root = parseV2Root(raw, config)

        val portraitLayout = root.optJSONObject("portrait")
            ?.let { parseTilesAndOffsets(it, config) }
            ?: buildDefaultLayout(config)

        val landscapeLayout = if (root.isNull("landscape")) {
            null
        } else {
            root.optJSONObject("landscape")?.let { parseTilesAndOffsets(it, config) }
        }

        // Persist migration if needed (raw was null or v1)
        if (raw == null || !JSONObject(raw).has("version")) {
            val migrated = root.toString()
            prefs.edit().putString(OVERLAY_PREFS_KEY_STATE, migrated).apply()
            OverlayRuntimeCache.updateLayouts(migrated, portraitLayout, landscapeLayout)
        } else {
            OverlayRuntimeCache.updateLayouts(raw, portraitLayout, landscapeLayout)
        }

        return portraitLayout to landscapeLayout
    }

    private fun buildDefaultLayout(config: ShortcutHubConfig): OverlayOrientationLayout =
        OverlayOrientationLayout(
            gridRows = config.gridRows,
            gridColumns = config.gridColumns,
        )

    private fun serializeLayout(layout: OverlayOrientationLayout): JSONObject = JSONObject().apply {
        put("showGrid", layout.showGrid)
        put("gridRows", layout.gridRows)
        put("gridColumns", layout.gridColumns)
        put("dpadOffsetX", layout.dpadOffsetX.toDouble())
        put("dpadOffsetY", layout.dpadOffsetY.toDouble())
        put("topPanelOffsetX", layout.topPanelOffsetX.toDouble())
        put("topPanelOffsetY", layout.topPanelOffsetY.toDouble())
        put("nextTileId", layout.nextTileId)
        put(
            "tiles",
            JSONArray().also { arr ->
                layout.tiles.forEach { tile ->
                    arr.put(serializeTile(tile))
                }
            },
        )
    }

    private fun serializeTile(tile: TileState): JSONObject = JSONObject().apply {
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
    }

    private fun parseTilesAndOffsets(
        layoutObj: JSONObject,
        config: ShortcutHubConfig,
    ): OverlayOrientationLayout {
        val gridRows = if (layoutObj.has("gridRows")) layoutObj.optInt("gridRows", config.gridRows) else config.gridRows
        val gridColumns = if (layoutObj.has("gridColumns")) layoutObj.optInt("gridColumns", config.gridColumns) else config.gridColumns
        val tilesArray = layoutObj.optJSONArray("tiles") ?: JSONArray()
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
                    item.getDouble("customTextScale").toFloat().coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
                } else null
                val customBoldText = if (item.has("customBoldText")) item.getBoolean("customBoldText") else null

                when (item.optString("type", TILE_TYPE_APP)) {
                    TILE_TYPE_WIDGET -> {
                        val appWidgetId = item.optInt("appWidgetId", -1)
                        val providerComponent = item.optString("providerComponent").takeIf { it.isNotBlank() }
                        if (appWidgetId < 0 || providerComponent == null) continue
                        add(WidgetTileState(id, row, column, rowSpan, columnSpan, appWidgetId, providerComponent, customLabel))
                    }
                    TILE_TYPE_SYSTEM_SLIDER -> {
                        val sliderType = SliderType.entries.firstOrNull { it.name == item.optString("sliderType") } ?: continue
                        val streamMode = StreamMode.entries.firstOrNull { it.name == item.optString("streamMode", StreamMode.DEFAULT.name) } ?: StreamMode.DEFAULT
                        val singleStream = AudioStreamType.entries.firstOrNull { it.name == item.optString("singleStream", AudioStreamType.MUSIC.name) } ?: AudioStreamType.MUSIC
                        val buttonPlacement = SliderButtonPlacement.entries.firstOrNull { it.name == item.optString("buttonPlacement", SliderButtonPlacement.SPLIT.name) } ?: SliderButtonPlacement.SPLIT
                        val notchMode = SliderNotchMode.entries.firstOrNull { it.name == item.optString("notchMode", SliderNotchMode.LOCK_AND_SLIDE.name) } ?: SliderNotchMode.LOCK_AND_SLIDE
                        add(SystemSliderTileState(id, row, column, rowSpan, columnSpan, SystemSliderConfig(sliderType, streamMode, singleStream, buttonPlacement, notchMode, item.optBoolean("showNotches", true), item.optInt("buttonStepSize", 1), item.optBoolean("showOutline", false), item.optBoolean("buttonHapticsEnabled", false), item.optBoolean("notchHapticsEnabled", false)), customLabel))
                    }
                    TILE_TYPE_INTENT -> {
                        val action = item.optString("intentAction").takeIf { it.isNotBlank() } ?: continue
                        val intentType = IntentType.entries.firstOrNull { it.name == item.optString("intentType", IntentType.ACTIVITY.name) } ?: IntentType.ACTIVITY
                        val extrasObj = item.optJSONObject("intentExtras")
                        val extras = buildMap<String, String> { extrasObj?.keys()?.forEach { k -> put(k, extrasObj.getString(k)) } }
                        add(IntentTileState(id, row, column, rowSpan, columnSpan, action, intentType, item.optString("intentPackage").takeIf { it.isNotBlank() }, item.optString("intentComponent").takeIf { it.isNotBlank() }, item.optString("intentDataUri").takeIf { it.isNotBlank() }, extras, customLabel, customFontUri, customFontName, customTextScale, customBoldText))
                    }
                    else -> {
                        val component = item.optString("component").takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString)
                        val launchIntentUri = item.optString("launchIntentUri").takeIf { it.isNotBlank() }
                        val launchIntentPackage = item.optString("launchIntentPackage").takeIf { it.isNotBlank() }
                        add(AppTileState(id, row, column, rowSpan, columnSpan, LaunchableApp(item.getString("label"), component, launchIntentUri, launchIntentPackage), parseAppTileIconConfig(item), customLabel, customFontUri, customFontName, customTextScale, customBoldText))
                    }
                }
            }
        }.filter { tile ->
            tile.row < gridRows && tile.column < gridColumns &&
                tile.row + tile.rowSpan <= gridRows && tile.column + tile.columnSpan <= gridColumns
        }

        return OverlayOrientationLayout(
            showGrid = layoutObj.optBoolean("showGrid", false),
            gridRows = gridRows,
            gridColumns = gridColumns,
            dpadOffsetX = layoutObj.optDouble("dpadOffsetX", 24.0).toFloat(),
            dpadOffsetY = layoutObj.optDouble("dpadOffsetY", 220.0).toFloat(),
            topPanelOffsetX = layoutObj.optDouble("topPanelOffsetX", 16.0).toFloat(),
            topPanelOffsetY = layoutObj.optDouble("topPanelOffsetY", 16.0).toFloat(),
            nextTileId = layoutObj.optInt("nextTileId", tiles.maxOfOrNull { it.id + 1 } ?: 1),
            tiles = tiles,
        )
    }
}
