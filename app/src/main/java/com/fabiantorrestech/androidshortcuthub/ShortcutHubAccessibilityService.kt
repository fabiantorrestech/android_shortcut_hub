package com.fabiantorrestech.androidshortcuthub

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ShortcutHubAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ShortcutHubA11y"
        private val toggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        internal var instance: ShortcutHubAccessibilityService? = null
            private set

        fun toggle() {
            toggleRequests.tryEmit(Unit)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isShowingOverlay = false
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiverCompat(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiverCompat(systemUiDismissReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        serviceScope.launch {
            toggleRequests.collect { toggleOverlay() }
        }
        // Pre-warm state and font caches so the first toggle doesn't pay cold I/O costs.
        serviceScope.launch(Dispatchers.IO) {
            val state = OverlayStateRepository.load(this@ShortcutHubAccessibilityService)
            OverlayRuntimeCache.preloadFonts(state, ::loadFontFamily)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (overlayView != null &&
            event?.packageName?.toString() == "com.android.systemui"
        ) {
            dismissOverlay()
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != packageName) {
                _foregroundPackage.value = pkg
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        tearDownServiceState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDownServiceState()
        super.onDestroy()
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun tearDownServiceState() {
        if (isConnected) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            runCatching { unregisterReceiver(systemUiDismissReceiver) }
            isConnected = false
            instance = null
        }
        dismissOverlay()
        serviceScope.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal suspend fun takeGrayscaleSnapshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        }
        return suspendCancellableCoroutine { continuation ->
            @Suppress("NewApi")
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        // getHardwareBitmap() exists at runtime (API 30+) but is missing from
                        // SDK 36 compile stubs, so we access it via reflection.
                        val hardware = runCatching {
                            result.javaClass.getMethod("getHardwareBitmap").invoke(result) as Bitmap
                        }.getOrNull()
                        if (hardware == null) { continuation.resume(null); return }
                        val soft = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.recycle()
                        val gray = Bitmap.createBitmap(soft.width, soft.height, Bitmap.Config.ARGB_8888)
                        Canvas(gray).drawBitmap(soft, 0f, 0f, paint)
                        soft.recycle()
                        continuation.resume(gray)
                    }
                    override fun onFailure(errorCode: Int) { continuation.resume(null) }
                },
            )
        }
    }

    private fun toggleOverlay() {
        if (overlayView == null) {
            showOverlay()
        } else {
            dismissOverlay()
        }
    }

    private fun showOverlay() {
        if (overlayView != null || isShowingOverlay) return
        isShowingOverlay = true

        serviceScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            try {
                // Load state and grayscale config concurrently — they read different prefs files.
                val stateDeferred = async(Dispatchers.IO) {
                    OverlayStateRepository.load(this@ShortcutHubAccessibilityService)
                }
                val grayscaleDeferred = async(Dispatchers.IO) {
                    val loaded = GrayscaleRepository.load(this@ShortcutHubAccessibilityService)
                    if (loaded.enabled) {
                        GrayscaleRepository.syncAppLabels(this@ShortcutHubAccessibilityService, loaded)
                            .also { GrayscaleRepository.save(this@ShortcutHubAccessibilityService, it) }
                    } else {
                        loaded
                    }
                }
                val initialState = stateDeferred.await()
                val grayscaleConfig = grayscaleDeferred.await()

                val grayscaleFrame: Flow<Bitmap?> = when {
                    !grayscaleConfig.enabled -> {
                        GrayscaleCaptureForegroundService.stop(this@ShortcutHubAccessibilityService)
                        MutableStateFlow(null)
                    }
                    grayscaleConfig.captureMode == GrayscaleMode.SIMPLE ->
                        MutableStateFlow(takeGrayscaleSnapshot())
                    else -> {
                        if (GrayscaleProjectionBroker.hasProjection()) {
                            GrayscaleCaptureForegroundService.resumeCapture(this@ShortcutHubAccessibilityService)
                        } else {
                            GrayscaleProjectionBroker.requestProjection(this@ShortcutHubAccessibilityService)
                        }
                        GrayscaleProjectionBroker.frame
                    }
                }

                val preloadedFonts: Map<String, FontFamily?> = withContext(Dispatchers.IO) {
                    OverlayRuntimeCache.preloadFonts(initialState, ::loadFontFamily)
                }

                val lifecycleOwner = OverlayLifecycleOwner().also {
                    it.start()
                    overlayLifecycleOwner = it
                }

                val composeView = ComposeView(this@ShortcutHubAccessibilityService).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(lifecycleOwner)
                    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
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
                                            this@ShortcutHubAccessibilityService,
                                            tileId,
                                        ),
                                    )
                                },
                                openTileIconPicker = { tileId ->
                                    startActivity(
                                        PickTileIconActivity.createIntent(
                                            this@ShortcutHubAccessibilityService,
                                            tileId,
                                        ),
                                    )
                                },
                                launchApp = ::launchApp,
                                launchIntent = ::launchIntent,
                                onPersist = { OverlayStateRepository.save(this@ShortcutHubAccessibilityService, it) },
                                onDismiss = ::dismissOverlay,
                                onKeyboardInputToggle = { needsKeyboard ->
                                    val p = overlayParams ?: return@OverlayContent
                                    val v = overlayView ?: return@OverlayContent
                                    if (needsKeyboard) {
                                        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                                        @Suppress("DEPRECATION")
                                        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                    } else {
                                        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        p.softInputMode = 0
                                    }
                                    if (v.isAttachedToWindow) windowManager.updateViewLayout(v, p)
                                },
                                grayscaleFrame = grayscaleFrame,
                                grayscaleConfig = grayscaleConfig,
                                foregroundPackage = foregroundPackage,
                            )
                        }
                    }
                }

                @Suppress("DEPRECATION")
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                overlayView = composeView
                overlayParams = params
                windowManager.addView(composeView, params)
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
        GrayscaleCaptureForegroundService.pauseCapture(this)
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

    private fun loadFontFamily(uriString: String?): FontFamily? {
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
