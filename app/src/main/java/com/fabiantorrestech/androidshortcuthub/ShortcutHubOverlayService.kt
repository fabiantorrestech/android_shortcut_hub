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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ShortcutHubOverlayService : Service() {
    companion object {
        const val ACTION_TOGGLE_OVERLAY =
            "com.fabiantorrestech.androidshortcuthub.action.TOGGLE_OVERLAY"
        const val ACTION_PREWARM_OVERLAY =
            "com.fabiantorrestech.androidshortcuthub.action.PREWARM_OVERLAY"
        private const val TAG = "ShortcutHubOverlay"
        private val tileFontResults = MutableSharedFlow<TileFontSelection>(extraBufferCapacity = 1)
        private val tileIconResults = MutableSharedFlow<TileIconSelection>(extraBufferCapacity = 1)

        @Volatile
        var isRunning: Boolean = false
            private set

        fun canDrawOverlays(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun startToggle(context: Context) {
            context.startService(
                Intent(context, ShortcutHubOverlayService::class.java).apply {
                    action = ACTION_TOGGLE_OVERLAY
                },
            )
        }

        fun prewarm(context: Context) {
            context.startService(
                Intent(context, ShortcutHubOverlayService::class.java).apply {
                    action = ACTION_PREWARM_OVERLAY
                },
            )
        }

        fun dispatchTileFontPicked(tileId: Int, uriString: String, fontName: String) {
            tileFontResults.tryEmit(TileFontSelection(tileId, uriString, fontName))
        }

        fun tileFontSelectionEvents(): MutableSharedFlow<TileFontSelection> = tileFontResults

        fun dispatchTileIconPicked(tileId: Int, uriString: String, iconName: String) {
            tileIconResults.tryEmit(TileIconSelection(tileId, uriString, iconName))
        }

        fun tileIconSelectionEvents(): MutableSharedFlow<TileIconSelection> = tileIconResults
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isShowingOverlay = false
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var widgetHostListening = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF &&
                ShortcutHubSettings.load(context).dismissOnScreenOff
            ) {
                dismissOverlay()
            }
        }
    }

    private val systemUiDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                @Suppress("DEPRECATION")
                val reason = intent.getStringExtra("reason") ?: ""
                if (reason == "homekey" || reason == "recentapps") dismissOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(systemUiDismissReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_TOGGLE_OVERLAY) {
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
            ACTION_PREWARM_OVERLAY -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        unregisterReceiver(screenOffReceiver)
        unregisterReceiver(systemUiDismissReceiver)
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
        }
    }

    private fun showOverlay() {
        if (overlayView != null || isShowingOverlay || !canDrawOverlays(this)) return
        isShowingOverlay = true

        serviceScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            try {
                // Load state and grayscale config concurrently — they read different prefs files.
                val stateDeferred = async(Dispatchers.IO) {
                    OverlayStateRepository.loadBoth(this@ShortcutHubOverlayService)
                }
                val grayscaleDeferred = async(Dispatchers.IO) {
                    GrayscaleRepository.load(this@ShortcutHubOverlayService)
                }
                val (portraitState, landscapeState) = stateDeferred.await()
                val grayscaleConfig = grayscaleDeferred.await()
                val simpleGrayscaleFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
                val grayscaleFrame =
                    if (grayscaleConfig.enabled && grayscaleConfig.captureMode == GrayscaleMode.ADVANCED) {
                        GrayscaleProjectionBroker.frame
                    } else {
                        simpleGrayscaleFrame
                    }
                val preloadedFonts = OverlayRuntimeCache.cachedFontsFor(portraitState, landscapeState)
                ensureWidgetHostStartedIfNeeded(portraitState)

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
                                portraitState = portraitState,
                                landscapeState = landscapeState,
                                preloadedFonts = preloadedFonts,
                                tileFontEvents = tileFontSelectionEvents(),
                                tileIconEvents = tileIconSelectionEvents(),
                                tileInsertionEvents = BindWidgetActivity.tileInsertionEvents(),
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
                                openTileIconPicker = { tileId ->
                                    startActivity(
                                        PickTileIconActivity.createIntent(
                                            this@ShortcutHubOverlayService,
                                            tileId,
                                        ),
                                    )
                                },
                                launchApp = ::launchApp,
                                launchIntent = ::launchIntent,
                                onPersist = { state, orientation ->
                                    OverlayStateRepository.saveLayout(this@ShortcutHubOverlayService, state, orientation)
                                },
                                onDismiss = { dismissOverlay() },
                                onKeyboardInputToggle = { needsKeyboard ->
                                    val p = overlayParams ?: return@OverlayContent
                                    val v = overlayView ?: return@OverlayContent
                                    @Suppress("DEPRECATION")
                                    p.softInputMode = if (needsKeyboard) {
                                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                    } else {
                                        0
                                    }
                                    if (v.isAttachedToWindow) windowManager.updateViewLayout(v, p)
                                },
                                grayscaleFrame = grayscaleFrame,
                                grayscaleConfig = grayscaleConfig,
                                foregroundPackage = ShortcutHubAccessibilityService.foregroundPackage,
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
                val windowFlags = if (portraitState.showOverLockscreen) {
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                } else {
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                }

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
                overlayParams = params
                windowManager.addView(composeView, params)
                warmFontsAsync(portraitState, landscapeState)
                initializeGrayscaleAfterFirstPaint(grayscaleConfig, simpleGrayscaleFrame)
                syncGrayscaleLabelsAsync(grayscaleConfig)
                Log.d(TAG, "Overlay shown in ${SystemClock.elapsedRealtime() - startMs}ms")
            } finally {
                isShowingOverlay = false
            }
        }
    }

    private fun dismissOverlay() {
        overlayLifecycleOwner?.destroy()
        overlayLifecycleOwner = null
        overlayView?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        overlayView = null
        overlayParams = null
        stopWidgetHostListeningIfNeeded()
        GrayscaleCaptureForegroundService.pauseCapture(this)
    }

    private fun ensureWidgetHostStartedIfNeeded(state: OverlayUiState) {
        if (widgetHostListening || state.tiles.none { it is WidgetTileState }) return
        ShortcutHubWidgetHost.getInstance(this).startListening(this)
        widgetHostListening = true
    }

    private fun stopWidgetHostListeningIfNeeded() {
        if (!widgetHostListening) return
        ShortcutHubWidgetHost.getInstance(this).stopListening(this)
        widgetHostListening = false
    }

    private fun warmFontsAsync(portraitState: OverlayUiState, landscapeState: OverlayUiState) {
        serviceScope.launch(Dispatchers.IO) {
            OverlayRuntimeCache.preloadFonts(portraitState, landscapeState, ::loadFontFamily)
        }
    }

    private fun initializeGrayscaleAfterFirstPaint(
        grayscaleConfig: GrayscaleConfig,
        grayscaleFrame: MutableStateFlow<android.graphics.Bitmap?>,
    ) {
        if (!grayscaleConfig.enabled) {
            GrayscaleCaptureForegroundService.stop(this)
            grayscaleFrame.value = null
            return
        }

        when (grayscaleConfig.captureMode) {
            GrayscaleMode.SIMPLE -> {
                serviceScope.launch {
                    grayscaleFrame.value = ShortcutHubAccessibilityService.instance?.takeGrayscaleSnapshot()
                }
            }
            GrayscaleMode.ADVANCED -> {
                if (GrayscaleProjectionBroker.hasProjection()) {
                    GrayscaleCaptureForegroundService.resumeCapture(this)
                } else {
                    GrayscaleProjectionBroker.requestProjection(this)
                }
            }
        }
    }

    private fun syncGrayscaleLabelsAsync(grayscaleConfig: GrayscaleConfig) {
        if (!grayscaleConfig.enabled ||
            (grayscaleConfig.whitelistApps.isEmpty() && grayscaleConfig.blacklistApps.isEmpty())
        ) {
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val synced = GrayscaleRepository.syncAppLabels(this@ShortcutHubOverlayService, grayscaleConfig)
            if (synced != grayscaleConfig) {
                GrayscaleRepository.save(this@ShortcutHubOverlayService, synced)
            }
        }
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

    internal fun loadFontFamily(uriString: String?): FontFamily? {
        val parsedUri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return runCatching {
            contentResolver.openFileDescriptor(parsedUri, "r")?.use { descriptor ->
                FontFamily(Typeface.Builder(descriptor.fileDescriptor).build())
            }
        }.getOrNull()
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
