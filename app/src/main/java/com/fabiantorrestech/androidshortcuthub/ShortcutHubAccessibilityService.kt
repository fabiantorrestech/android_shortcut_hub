package com.fabiantorrestech.androidshortcuthub

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class ShortcutHubAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ShortcutHubA11y"
        internal const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /**
         * Packages that are chrome rather than a foreground app. SystemUI raises window events for
         * every shade pull, notification and lockscreen appearance, so anything keying off "which
         * app is in front" has to exclude them or it will believe the user left the app.
         */
        private val NON_APP_PACKAGES = setOf(SYSTEM_UI_PACKAGE, "android")

        /**
         * How long after the overlay is added to ignore SystemUI window events. Triggers that live
         * in SystemUI (notably the floating accessibility button) raise a window event at almost
         * exactly the moment the overlay appears, which would otherwise make the hub dismiss
         * itself the instant it opened.
         */
        private const val SYSTEM_UI_DISMISS_GRACE_MS = 700L

        private val toggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val _foregroundAppPackage = MutableStateFlow<String?>(null)

        /**
         * The foreground app, for per-app trigger suppression. Never reports SystemUI or the
         * framework, so it does not change the moment the user pulls down the notification shade.
         */
        val foregroundAppPackage: StateFlow<String?> = _foregroundAppPackage.asStateFlow()

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        internal var instance: ShortcutHubAccessibilityService? = null
            private set

        fun toggle() {
            toggleRequests.tryEmit(Unit)
        }

        /**
         * Hides the edge handles while the hub is on screen.
         *
         * Static because the hub may instead be hosted by [ShortcutHubOverlayService] or
         * [LockscreenOverlayActivity], and the handles are accessibility overlays, which stack
         * above both of those window types.
         */
        fun setHubVisible(visible: Boolean) {
            instance?.edgeTriggerController?.setSuppressed(
                EdgeTriggerController.SuppressReason.HUB_VISIBLE,
                visible,
            )
        }

        /**
         * Drives the Triggers-tab live preview. Called from the settings UI, which scopes it to
         * that tab being on screen and the app being in the foreground.
         */
        fun setEdgePreviewMode(active: Boolean) {
            instance?.edgeTriggerController?.setPreviewMode(active)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isShowingOverlay = false
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var widgetHostListening = false

    /** The show still loading its layout, so [enterDormant] can stop it before it adds a window. */
    private var showJob: Job? = null

    /** The event mask from the service config, put back when the hub is switched on again. */
    private var declaredEventTypes = 0

    /** See [SYSTEM_UI_DISMISS_GRACE_MS]. Volatile because it is read from onAccessibilityEvent. */
    @Volatile
    private var suppressSystemUiDismissUntilMs = 0L

    /**
     * Cached so nothing on an input path ever does a blocking prefs read. Refreshed by
     * [triggerPrefsListener].
     */
    @Volatile
    internal var triggerConfig: TriggerConfig = TriggerConfig()
        private set

    // Held as a field — SharedPreferences only keeps weak references to its listeners.
    // Hops to the main thread because serviceScope is Dispatchers.Main and every WindowManager
    // call the controller makes has to happen there.
    private val triggerPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        serviceScope.launch { onTriggerPrefsChanged() }
    }

    private var accessibilityButtonCallback:
        AccessibilityButtonController.AccessibilityButtonCallback? = null

    private var edgeTriggerController: EdgeTriggerController? = null

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
        declaredEventTypes = serviceInfo?.eventTypes ?: 0
        triggerConfig = loadTriggerConfig()
        runCatching {
            getSharedPreferences(TRIGGER_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(triggerPrefsListener)
        }.onFailure { Log.e(TAG, "Trigger prefs listener registration failed", it) }
        registerAccessibilityButton()
        registerReceiverCompat(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiverCompat(systemUiDismissReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        serviceScope.launch {
            toggleRequests.collect { toggleOverlay() }
        }
        // Read here as well as applied live, because this is what makes the switch hold across a
        // reboot: Android reconnects the service at boot without any other app code having run.
        if (HubSwitch.isEnabled(this)) {
            startHubMachinery()
        } else {
            Log.d(TAG, "Connected with the hub switched off; staying dormant")
            setEventDelivery(false)
        }
    }

    /** What the hub switch turns off: the edge handles, and the cache pre-warm behind the hub. */
    private fun startHubMachinery() {
        edgeTriggerController = EdgeTriggerController(this, windowManager, serviceScope).also {
            runCatching { it.start(triggerConfig) }
                .onFailure { e -> Log.e(TAG, "Edge trigger start failed", e) }
        }
        // Pre-warm state and font caches so the first toggle doesn't pay cold I/O costs.
        // Guarded: this is only an optimisation, but an uncaught throw here kills the process, and
        // Android immediately rebinds the crashed accessibility service — a permanent crash loop.
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val (portrait, landscape) = OverlayStateRepository.loadBoth(this@ShortcutHubAccessibilityService)
                OverlayRuntimeCache.preloadFonts(portrait, landscape, ::loadFontFamily)
            }.onFailure { Log.e(TAG, "Overlay pre-warm failed", it) }
        }
    }

    /**
     * The hub was switched off.
     *
     * Not [tearDownServiceState]: that cancels serviceScope, which cannot be restarted, and the
     * service has to be able to wake again without the user re-enabling it in Android's settings.
     * So the scope, the receivers, the button callback and the toggle collector all stay - they are
     * inert while routeShortcutHubToggle refuses every toggle - and everything that does work or
     * holds state goes.
     */
    internal fun enterDormant() {
        showJob?.cancel()
        showJob = null
        // Normally reset by the show's own finally, which never runs for a job cancelled before it
        // started.
        isShowingOverlay = false
        dismissOverlay()
        suppressSystemUiDismissUntilMs = 0L
        // Dropped rather than just stopped: a stopped controller re-attaches its handles on the next
        // setSuppressed, setPreviewMode, reloadConfig or rotation, and every caller already goes
        // through ?. Dropping it also discards its suppression latches and preview mode.
        runCatching { edgeTriggerController?.stop() }
            .onFailure { Log.e(TAG, "Edge trigger stop failed", it) }
        edgeTriggerController = null
        _foregroundAppPackage.value = null
        setEventDelivery(false)
        Log.d(TAG, "Dormant")
    }

    /** The hub was switched back on: rebuild what [enterDormant] took down, from scratch. */
    internal fun leaveDormant() {
        runCatching { edgeTriggerController?.stop() }
        edgeTriggerController = null
        setEventDelivery(true)
        triggerConfig = loadTriggerConfig()
        startHubMachinery()
        Log.d(TAG, "Awake")
    }

    /**
     * Window events only drive dismiss-on-SystemUI and per-app handle filtering, and neither applies
     * while the hub is off, so switching off stops them reaching the process at all. Only the event
     * mask changes: flagRequestAccessibilityButton comes from the service config and is not
     * changeable at runtime, so the accessibility shortcut keeps arriving - and gets told the hub
     * is off, by routeShortcutHubToggle.
     */
    private fun setEventDelivery(enabled: Boolean) {
        runCatching {
            val info = serviceInfo ?: return
            info.eventTypes = if (enabled) declaredEventTypes else 0
            serviceInfo = info
        }.onFailure { Log.e(TAG, "Event delivery change failed", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()

        if (overlayView != null &&
            pkg == SYSTEM_UI_PACKAGE &&
            SystemClock.uptimeMillis() >= suppressSystemUiDismissUntilMs
        ) {
            dismissOverlay()
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!pkg.isNullOrBlank() && pkg != packageName && pkg !in NON_APP_PACKAGES) {
                _foregroundAppPackage.value = pkg
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

    private fun loadTriggerConfig(): TriggerConfig =
        runCatching { TriggerRepository.load(this) }
            .onFailure { Log.e(TAG, "Trigger config load failed", it) }
            .getOrDefault(TriggerConfig())

    private fun onTriggerPrefsChanged() {
        val loaded = loadTriggerConfig()
        triggerConfig = loaded
        runCatching { edgeTriggerController?.reloadConfig(loaded) }
            .onFailure { Log.e(TAG, "Edge trigger reload failed", it) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation, fold, density and font-scale changes all invalidate the handle geometry.
        runCatching { edgeTriggerController?.onConfigurationChanged() }
            .onFailure { Log.e(TAG, "Edge trigger reconfigure failed", it) }
    }

    /**
     * Subscribes to the system accessibility button.
     *
     * Because the service declares flagRequestAccessibilityButton (see
     * res/xml/accessibility_service_config.xml) this one callback also receives the volume-key
     * accessibility shortcut and the multi-finger swipe gesture, not just taps on the floating or
     * navigation-bar button. The system owns the affordance entirely, which is why it costs no
     * screen real estate and cannot collide with navigation gestures.
     */
    private fun registerAccessibilityButton() {
        val controller = runCatching { accessibilityButtonController }.getOrNull() ?: return
        val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                // Deliberately NOT gated on an in-app toggle. Assigning the shortcut in Android's
                // own accessibility settings is already the user's opt-in, and a second in-app
                // switch just produced a shortcut that buzzed and then did nothing.
                fireShortcutHubTrigger(
                    this@ShortcutHubAccessibilityService,
                    TriggerSource.A11Y_BUTTON,
                    triggerConfig.refireCooldownMs,
                )
            }

            override fun onAvailabilityChanged(
                controller: AccessibilityButtonController,
                available: Boolean,
            ) {
                // Some devices never render a software accessibility affordance, and a foreground
                // app hiding navigation can take it away temporarily.
                Log.d(TAG, "Accessibility button availability changed: $available")
            }
        }
        runCatching { controller.registerAccessibilityButtonCallback(callback) }
            .onSuccess { accessibilityButtonCallback = callback }
            .onFailure { Log.e(TAG, "Accessibility button registration failed", it) }
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
        accessibilityButtonCallback?.let { callback ->
            runCatching {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(callback)
            }
        }
        accessibilityButtonCallback = null
        runCatching {
            getSharedPreferences(TRIGGER_PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(triggerPrefsListener)
        }
        runCatching { edgeTriggerController?.stop() }
            .onFailure { Log.e(TAG, "Edge trigger teardown failed", it) }
        edgeTriggerController = null
        dismissOverlay()
        serviceScope.cancel()
    }


    private fun toggleOverlay() {
        if (overlayView == null) {
            showOverlay()
        } else {
            dismissOverlay()
        }
    }

    private fun showOverlay() {
        if (overlayView != null || isShowingOverlay || !HubSwitch.isEnabled(this)) return
        isShowingOverlay = true

        // Arm the grace window before the async state load, so a SystemUI-hosted trigger (the
        // floating accessibility button) can't have its own window event dismiss the overlay it
        // just asked for. Re-armed again just before addView so a slow cold load doesn't eat it.
        suppressSystemUiDismissUntilMs = SystemClock.uptimeMillis() + SYSTEM_UI_DISMISS_GRACE_MS

        // The handles are accessibility overlays too, so without this they would float on top of
        // the hub they just opened.
        setHubVisible(true)

        showJob = serviceScope.launch {
            val startMs = SystemClock.elapsedRealtime()
            try {
                val (portraitState, landscapeState) = withContext(Dispatchers.IO) {
                    OverlayStateRepository.loadBoth(this@ShortcutHubAccessibilityService)
                }
                val preloadedFonts = OverlayRuntimeCache.cachedFontsFor(portraitState, landscapeState)
                ensureWidgetHostStartedIfNeeded(portraitState)

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
                                portraitState = portraitState,
                                landscapeState = landscapeState,
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
                                onPersist = { state, orientation ->
                                    OverlayStateRepository.saveLayout(this@ShortcutHubAccessibilityService, state, orientation)
                                },
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
                            )
                        }
                    }
                }

                // Use real display pixel dimensions so the window covers the full screen
                // including the status bar region. MATCH_PARENT is resolved against the
                // "available" area which excludes the status bar.
                val (displayWidth, displayHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    bounds.width() to bounds.height()
                } else {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    metrics.widthPixels to metrics.heightPixels
                }

                @Suppress("DEPRECATION")
                val params = WindowManager.LayoutParams(
                    displayWidth,
                    displayHeight,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }

                overlayView = composeView
                overlayParams = params
                suppressSystemUiDismissUntilMs =
                    SystemClock.uptimeMillis() + SYSTEM_UI_DISMISS_GRACE_MS
                windowManager.addView(composeView, params)
                warmFontsAsync(portraitState, landscapeState)
                Log.d(TAG, "Overlay shown in ${SystemClock.elapsedRealtime() - startMs}ms")
            } catch (e: CancellationException) {
                // Cancelled by enterDormant or teardown, both of which clean up themselves. A
                // rollback here as well could run after a newer show and tear that one down.
                throw e
            } catch (e: Exception) {
                // Without this catch an exception here (e.g. addView, font loading, or the first
                // Compose composition) propagates uncaught on the main thread and crashes the
                // process. Because Android auto-rebinds a crashed accessibility service, that turns
                // into a continuous "app keeps stopping" loop. Log and roll back any half-built
                // overlay state so the next toggle starts clean instead of re-crashing or wedging.
                Log.e(TAG, "showOverlay failed", e)
                setHubVisible(false)
                overlayLifecycleOwner?.destroy()
                overlayLifecycleOwner = null
                overlayView?.let {
                    runCatching { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
                }
                overlayView = null
                overlayParams = null
                // The layout may already have started the widget host; left alone, it would keep
                // listening with nothing on screen until some later successful dismiss.
                stopWidgetHostListeningIfNeeded()
            } finally {
                isShowingOverlay = false
            }
        }
    }

    private fun dismissOverlay() {
        setHubVisible(false)
        overlayLifecycleOwner?.destroy()
        overlayLifecycleOwner = null
        overlayView?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        overlayView = null
        overlayParams = null
        stopWidgetHostListeningIfNeeded()
    }

    private fun ensureWidgetHostStartedIfNeeded(state: OverlayUiState) {
        if (widgetHostListening || !state.tiles.hasAnyWidget()) return
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
        val target = buildAppLaunchIntent(app) ?: return
        // Opening an app inherently needs the device unlocked → always go through unlock.
        launchViaUnlock(KeyguardUnlockTrampolineActivity.createIntent(this, target))
    }

    private fun launchIntent(tile: IntentTileState) {
        val intent = Intent(tile.intentAction).apply {
            tile.intentPackage?.let { setPackage(it) }
            // Resolves a bare class name against the tile's package too; previously
            // anything without a "/" was silently discarded, leaving an implicit intent.
            resolveIntentComponentName(tile.intentComponent, tile.intentPackage)?.let {
                component = it
            }
            tile.intentDataUri?.let { data = Uri.parse(it) }
            tile.intentExtras.forEach { (k, v) -> putExtra(k, v) }
        }
        // The trampoline exists only to dismiss a locked keyguard (this service is not an
        // Activity, so it cannot call requestDismissKeyguard itself). When the device is
        // already unlocked it serves no purpose, and its opaque black frame visibly dismisses
        // the app underneath before the (translucent) target appears. Only hop through it when
        // the keyguard is actually locked; otherwise dispatch directly.
        val keyguardLocked =
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (tile.unlockToLaunch && keyguardLocked) {
            val trampoline = when (tile.intentType) {
                IntentType.ACTIVITY ->
                    KeyguardUnlockTrampolineActivity.createIntent(this, intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                IntentType.BROADCAST_RECEIVER ->
                    KeyguardUnlockTrampolineActivity.createBroadcastIntent(this, intent)
                IntentType.SERVICE ->
                    KeyguardUnlockTrampolineActivity.createServiceIntent(this, intent)
            }
            launchViaUnlock(trampoline)
        } else {
            runCatching {
                when (tile.intentType) {
                    IntentType.ACTIVITY -> startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    IntentType.BROADCAST_RECEIVER -> sendBroadcast(intent)
                    IntentType.SERVICE -> startService(intent)
                }
            }.onFailure { Log.e(TAG, "launchIntent dispatch failed", it) }
        }
    }

    private fun buildAppLaunchIntent(app: LaunchableApp): Intent? {
        val component = app.componentName
        if (component != null) {
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                this.component = component
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        val launchUri = app.launchIntentUri ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(launchUri)).apply {
            app.launchIntentPackage?.let { setPackage(it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Dismisses the overlay and starts [trampolineIntent] (a KeyguardUnlockTrampolineActivity
     * intent). The trampoline is an Activity, so it can call requestDismissKeyguard to show the
     * PIN/biometric prompt and then dispatch the real launch from a foreground activity context.
     * This service is NOT an Activity, and deferring the launch to a background broadcast
     * (the old userPresentReceiver approach) is blocked by Background-Activity-Launch
     * restrictions on Android 10+, so the launch silently never happened.
     */
    private fun launchViaUnlock(trampolineIntent: Intent) {
        dismissOverlay()
        runCatching { startActivity(trampolineIntent) }
            .onFailure { Log.e(TAG, "Failed to start unlock trampoline", it) }
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
