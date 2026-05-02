package com.fabiantorrestech.androidshortcuthub

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fabiantorrestech.androidshortcuthub.ui.theme.ShortcutHubTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ShortcutHubOverlayService : Service() {
    companion object {
        const val ACTION_TOGGLE_OVERLAY =
            "com.fabiantorrestech.androidshortcuthub.action.TOGGLE_OVERLAY"
        private val tileFontResults = MutableSharedFlow<TileFontSelection>(extraBufferCapacity = 1)

        fun canDrawOverlays(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun startToggle(context: Context) {
            context.startService(
                Intent(context, ShortcutHubOverlayService::class.java).apply {
                    action = ACTION_TOGGLE_OVERLAY
                },
            )
        }

        fun dispatchTileFontPicked(tileId: Int, uriString: String, fontName: String) {
            tileFontResults.tryEmit(TileFontSelection(tileId, uriString, fontName))
        }

        fun tileFontSelectionEvents(): MutableSharedFlow<TileFontSelection> = tileFontResults
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isShowingOverlay = false
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF &&
                ShortcutHubSettings.load(context).dismissOnScreenOff
            ) {
                dismissOverlay()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_TOGGLE_OVERLAY) {
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        serviceScope.cancel()
        dismissOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toggleOverlay() {
        if (overlayView == null) {
            showOverlay()
        } else {
            dismissOverlay()
            stopSelf()
        }
    }

    private fun showOverlay() {
        if (overlayView != null || isShowingOverlay || !canDrawOverlays(this)) return
        isShowingOverlay = true

        serviceScope.launch {
            try {
                val initialState = loadOverlayState()

                // Preload every font URI before first render so tiles appear with the correct font immediately.
                val preloadedFonts: Map<String, FontFamily?> = withContext(Dispatchers.IO) {
                    buildSet<String> {
                        initialState.defaultFontUri?.let { add(it) }
                        initialState.tiles.forEach { it.customFontUri?.let { uri -> add(uri) } }
                    }.associateWith { uri -> loadFontFamily(uri) }
                }

                val lifecycleOwner = OverlayLifecycleOwner().also {
                    it.start()
                    overlayLifecycleOwner = it
                }

                val composeView = ComposeView(this@ShortcutHubOverlayService).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(lifecycleOwner)
                    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                    setContent {
                        ShortcutHubTheme {
                            OverlayContent(
                                initialState = initialState,
                                preloadedFonts = preloadedFonts,
                                tileFontEvents = tileFontSelectionEvents(),
                                loadLaunchableApps = ::loadLaunchableApps,
                                resolveCustomPackage = ::resolveCustomPackage,
                                loadFontFamily = ::loadFontFamily,
                                openTileFontPicker = { tileId ->
                                    startActivity(
                                        PickTileFontActivity.createIntent(
                                            this@ShortcutHubOverlayService,
                                            tileId,
                                        ),
                                    )
                                },
                                launchApp = ::launchApp,
                                launchIntent = ::launchIntent,
                                onPersist = ::saveOverlayState,
                                onDismiss = {
                                    dismissOverlay()
                                    stopSelf()
                                },
                            )
                        }
                    }
                }

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                @Suppress("DEPRECATION")
                val windowFlags = if (initialState.showOverLockscreen) {
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                } else 0

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    windowFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                overlayView = composeView
                windowManager.addView(composeView, params)
            } finally {
                isShowingOverlay = false
            }
        }
    }

    private fun dismissOverlay() {
        overlayLifecycleOwner?.destroy()
        overlayLifecycleOwner = null
        overlayView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        overlayView = null
    }

    private fun loadLaunchableApps(): List<LaunchableApp> =
        packageManager.getInstalledApplications(0)
            .mapNotNull { appInfo ->
                val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
                    ?: return@mapNotNull null
                val component = launchIntent.component ?: return@mapNotNull null
                LaunchableApp(
                    label = packageManager.getApplicationLabel(appInfo)?.toString().orEmpty()
                        .ifBlank { appInfo.packageName },
                    componentName = component,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    private fun launchApp(app: LaunchableApp) {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = app.componentName
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

    internal fun loadFontFamily(uriString: String?): FontFamily? {
        val parsedUri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return runCatching {
            contentResolver.openFileDescriptor(parsedUri, "r")?.use { descriptor ->
                FontFamily(Typeface.Builder(descriptor.fileDescriptor).build())
            }
        }.getOrNull()
    }

    internal fun loadOverlayState(): OverlayUiState {
        val prefs = getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val config = ShortcutHubSettings.load(this)
        val defaultState = OverlayUiState(
            gridRows = config.gridRows,
            gridColumns = config.gridColumns,
            defaultTextScale = config.defaultTextScale,
            defaultFontUri = config.defaultFontUri,
            defaultFontName = config.defaultFontName,
            defaultTextColorMode = config.defaultTextColorMode,
            defaultTextColorHex = config.defaultTextColorHex,
            hapticFeedbackEnabled = config.hapticFeedbackEnabled,
            panelHandleLocked = config.panelHandleLocked,
            overlayBackgroundAlpha = config.overlayBackgroundAlpha,
            showOverLockscreen = config.showOverLockscreen,
        )
        val raw = prefs.getString(OVERLAY_PREFS_KEY_STATE, null) ?: return defaultState

        return runCatching {
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

                    when (item.optString("type", TILE_TYPE_APP)) {
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
                                ),
                            )
                        }
                        else -> {
                            val component = ComponentName.unflattenFromString(
                                item.getString("component"),
                            ) ?: continue
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
                                    ),
                                    customLabel = customLabel,
                                    customFontUri = customFontUri,
                                    customFontName = customFontName,
                                    customTextScale = customTextScale,
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
                defaultFontUri = config.defaultFontUri,
                defaultFontName = config.defaultFontName,
                defaultTextColorMode = config.defaultTextColorMode,
                defaultTextColorHex = config.defaultTextColorHex,
                hapticFeedbackEnabled = config.hapticFeedbackEnabled,
                panelHandleLocked = config.panelHandleLocked,
                overlayBackgroundAlpha = config.overlayBackgroundAlpha,
                showOverLockscreen = config.showOverLockscreen,
            )
        }.getOrDefault(defaultState)
    }

    internal fun saveOverlayState(state: OverlayUiState) {
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
                                when (tile) {
                                    is AppTileState -> {
                                        put("type", TILE_TYPE_APP)
                                        put("label", tile.app.label)
                                        put("component", tile.app.componentName.flattenToString())
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

        getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(OVERLAY_PREFS_KEY_STATE, root.toString())
            .apply()
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun start() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
