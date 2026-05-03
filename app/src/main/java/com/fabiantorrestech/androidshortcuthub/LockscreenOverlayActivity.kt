package com.fabiantorrestech.androidshortcuthub

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.lifecycleScope
import com.fabiantorrestech.androidshortcuthub.ui.theme.ShortcutHubTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

class LockscreenOverlayActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LockscreenOverlay"
        private var instanceRef = WeakReference<LockscreenOverlayActivity>(null)

        val isActive: Boolean get() = instanceRef.get()?.let { !it.isFinishing } ?: false

        fun finishIfActive() {
            instanceRef.get()?.finish()
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF &&
                ShortcutHubSettings.load(context).dismissOnScreenOff
            ) {
                finish()
            }
        }
    }

    private val systemUiDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                @Suppress("DEPRECATION")
                val reason = intent.getStringExtra("reason") ?: ""
                if (reason == "homekey" || reason == "recentapps") finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instanceRef = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(systemUiDismissReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            },
        )

        lifecycleScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            val stateStartMs = SystemClock.elapsedRealtime()
            val initialState = withContext(Dispatchers.IO) { loadOverlayState() }
            val stateElapsedMs = SystemClock.elapsedRealtime() - stateStartMs

            val fontStartMs = SystemClock.elapsedRealtime()
            val preloadedFonts: Map<String, FontFamily?> = withContext(Dispatchers.IO) {
                OverlayRuntimeCache.preloadFonts(initialState, ::loadFontFamily)
            }
            val fontElapsedMs = SystemClock.elapsedRealtime() - fontStartMs

            if (isFinishing) return@launch

            setContent {
                ShortcutHubTheme {
                    OverlayContent(
                        initialState = initialState,
                        preloadedFonts = preloadedFonts,
                        tileFontEvents = ShortcutHubOverlayService.tileFontSelectionEvents(),
                        tileIconEvents = ShortcutHubOverlayService.tileIconSelectionEvents(),
                        tileInsertionEvents = BindWidgetActivity.tileInsertionEvents(),
                        loadLaunchableApps = ::loadLaunchableApps,
                        resolveCustomPackage = ::resolveCustomPackage,
                        loadFontFamily = ::loadFontFamily,
                        openTileFontPicker = { tileId ->
                            startActivity(
                                PickTileFontActivity.createIntent(
                                    this@LockscreenOverlayActivity,
                                    tileId,
                                ),
                            )
                        },
                        openTileIconPicker = { tileId ->
                            startActivity(
                                PickTileIconActivity.createIntent(
                                    this@LockscreenOverlayActivity,
                                    tileId,
                                ),
                            )
                        },
                        launchApp = ::launchApp,
                        launchIntent = ::launchIntent,
                        onPersist = ::saveOverlayState,
                        onDismiss = ::finish,
                    )
                }
            }
            Log.d(
                TAG,
                "Overlay shown in ${SystemClock.elapsedRealtime() - startMs}ms " +
                    "(state=${stateElapsedMs}ms, fonts=${fontElapsedMs}ms)",
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        unregisterReceiver(systemUiDismissReceiver)
        if (instanceRef.get() == this) instanceRef = WeakReference(null)
        super.onDestroy()
    }

    private fun loadFontFamily(uriString: String?): FontFamily? {
        val parsedUri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return runCatching {
            contentResolver.openFileDescriptor(parsedUri, "r")?.use { descriptor ->
                FontFamily(Typeface.Builder(descriptor.fileDescriptor).build())
            }
        }.getOrNull()
    }

    private fun loadOverlayState(): OverlayUiState {
        val prefs = getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val config = ShortcutHubSettings.load(this)
        val defaultState = OverlayUiState(
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
        val raw = prefs.getString(OVERLAY_PREFS_KEY_STATE, null) ?: return defaultState

        return OverlayRuntimeCache.getOrLoadState(raw, config) {
            runCatching {
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
                    } else null
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
                            if (component == null && launchIntentUri == null) continue
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
    }

    private fun saveOverlayState(state: OverlayUiState) {
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

        val serialized = root.toString()
        getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(OVERLAY_PREFS_KEY_STATE, serialized)
            .apply()
        OverlayRuntimeCache.updateState(serialized, ShortcutHubSettings.load(this), state)
    }

    private fun loadLaunchableApps(): List<LaunchableApp> =
        (packageManager.getInstalledApplications(0)
            .mapNotNull { appInfo ->
                val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
                    ?: return@mapNotNull null
                val component = launchIntent.component ?: return@mapNotNull null
                LaunchableApp(
                    label = packageManager.getApplicationLabel(appInfo)?.toString().orEmpty()
                        .ifBlank { appInfo.packageName },
                    componentName = component,
                )
            } + loadLauncherWebShortcuts())
            .distinctBy { "${it.label}|${it.packageName}|${it.componentName?.flattenToString().orEmpty()}|${it.launchIntentUri.orEmpty()}" }
            .sortedBy { it.label.lowercase() }

    private fun launchApp(app: LaunchableApp) {
        val component = app.componentName
        if (component != null) {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    this.component = component
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
            return
        }

        val launchUri = app.launchIntentUri ?: return
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(launchUri)).apply {
                app.launchIntentPackage?.let { setPackage(it) }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }

    private fun launchIntent(tile: IntentTileState) {
        val intent = Intent(tile.intentAction).apply {
            tile.intentPackage?.let { setPackage(it) }
            tile.intentComponent?.let { comp ->
                ComponentName.unflattenFromString(comp)?.let { component = it }
            }
            tile.intentDataUri?.let { data = Uri.parse(it) }
            tile.intentExtras.forEach { (k, v) -> putExtra(k, v) }
        }
        runCatching {
            when (tile.intentType) {
                IntentType.ACTIVITY -> startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                IntentType.BROADCAST_RECEIVER -> sendBroadcast(intent)
                IntentType.SERVICE -> startService(intent)
            }
        }
    }

    private fun resolveCustomPackage(packageName: String): LaunchableApp? {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return null

        packageManager.getLaunchIntentForPackage(trimmed)?.component?.let { component ->
            val label = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(trimmed, 0),
                ).toString()
            }.getOrDefault(trimmed)
            return LaunchableApp(label = label.ifBlank { trimmed }, componentName = component)
        }

        val resolveInfo = packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(trimmed),
                0,
            )
            .firstOrNull() ?: return null

        return LaunchableApp(
            label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty().ifBlank { trimmed },
            componentName = ComponentName(
                resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name,
            ),
        )
    }

    private fun loadLauncherWebShortcuts(): List<LaunchableApp> {
        val contentUri = Uri.parse("content://app.cclauncher.shortcuts/pinned")
        return runCatching {
            contentResolver.query(contentUri, null, null, null, null)?.use { cursor ->
                val labelIndex = cursor.getColumnIndex("label")
                val urlIndex = cursor.getColumnIndex("url")
                val browserPackageIndex = cursor.getColumnIndex("browser_package")
                buildList {
                    while (cursor.moveToNext()) {
                        val label = cursor.getString(labelIndex)?.trim().orEmpty()
                        val url = cursor.getString(urlIndex)?.trim().orEmpty()
                        val browserPackage = cursor.getString(browserPackageIndex)?.trim().orEmpty()
                        if (label.isEmpty() || url.isEmpty()) continue
                        add(
                            LaunchableApp(
                                label = label,
                                launchIntentUri = url,
                                launchIntentPackage = browserPackage.ifBlank { null },
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
