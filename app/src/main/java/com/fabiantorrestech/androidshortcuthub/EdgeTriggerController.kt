package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "ShortcutHubEdge"
private const val SYNC_DEBOUNCE_MS = 150L

/**
 * Real display size in pixels, including the status bar area.
 *
 * MATCH_PARENT and the non-"real" metrics resolve against the *available* area, which excludes
 * system bars — so anything positioning a window against a screen edge has to use these instead.
 */
internal fun WindowManager.realDisplaySize(): Pair<Int, Int> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }

/**
 * Owns the edge-handle windows.
 *
 * [sync] is the single reconciler and the only place that touches WindowManager, so every
 * add/remove/update goes through one guarded path. That matters more than usual here: this runs
 * inside the accessibility service, where an uncaught throw kills the process and Android
 * immediately rebinds the service, producing a permanent "app keeps stopping" loop.
 *
 * Suppression detaches the window rather than ignoring events, so while a handle is suppressed it
 * also stops stealing touches from whatever is underneath it.
 */
internal class EdgeTriggerController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
) {

    enum class SuppressReason { HUB_VISIBLE, PER_APP, IMMERSIVE }

    private val handles = mutableMapOf<EdgeSide, EdgeHandleView>()
    private val suppressReasons = mutableSetOf<SuppressReason>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var config: TriggerConfig = TriggerConfig()
    private var blockedPackages: Set<String> = emptySet()
    private var allowedPackages: Set<String> = emptySet()
    private var foregroundJob: Job? = null
    private var isSyncing = false
    private var pendingPeek = false
    private var previewMode = false

    private val syncRunnable = Runnable { sync() }

    fun start(initial: TriggerConfig) {
        config = initial
        recomputeFilters()
        foregroundJob = scope.launch {
            ShortcutHubAccessibilityService.foregroundAppPackage.collect { pkg ->
                runCatching { applyPerAppFilter(pkg) }
                    .onFailure { Log.e(TAG, "Per-app filter failed", it) }
            }
        }
        sync()
    }

    /** Called when the user changes a trigger setting. Peeks so they can see the result. */
    fun reloadConfig(updated: TriggerConfig) {
        config = updated
        recomputeFilters()
        if (!config.suppressWhenImmersive) suppressReasons.remove(SuppressReason.IMMERSIVE)
        applyPerAppFilter(ShortcutHubAccessibilityService.foregroundAppPackage.value)
        pendingPeek = true
        scheduleSync(0L)
    }

    /** Rotation, fold, density or font-scale change: geometry has to be recomputed. */
    fun onConfigurationChanged() = scheduleSync(SYNC_DEBOUNCE_MS)

    /**
     * Turns the Triggers-tab live preview on or off.
     *
     * While on, handles stay attached and fully drawn regardless of suppression — the settings
     * screen is the point of reference, so a per-app rule or a fullscreen heuristic must not make
     * the thing the user is editing vanish.
     */
    fun setPreviewMode(active: Boolean) {
        if (previewMode == active) return
        previewMode = active
        scheduleSync(0L)
    }

    fun setSuppressed(reason: SuppressReason, suppressed: Boolean) {
        val changed = if (suppressed) suppressReasons.add(reason) else suppressReasons.remove(reason)
        if (changed) scheduleSync(0L)
    }

    fun stop() {
        foregroundJob?.cancel()
        foregroundJob = null
        mainHandler.removeCallbacks(syncRunnable)
        handles.keys.toList().forEach { detach(it) }
        suppressReasons.clear()
        previewMode = false
    }

    /**
     * Posted rather than run inline so a reentrant caller — notably the immersive callback, which
     * fires from the window-insets pass triggered by our own addView — can never recurse into
     * [sync] while it is still running.
     */
    private fun scheduleSync(delayMs: Long) {
        mainHandler.removeCallbacks(syncRunnable)
        mainHandler.postDelayed(syncRunnable, delayMs)
    }

    private fun sync() {
        if (isSyncing) return
        isSyncing = true
        try {
            EdgeSide.entries.forEach { side ->
                val handleConfig = config.handleFor(side)
                if (shouldBeAttached(handleConfig)) {
                    attachOrUpdate(side, handleConfig)
                } else {
                    detach(side)
                }
            }
        } catch (t: Throwable) {
            // Roll back to a clean state so the next sync starts fresh rather than re-throwing
            // against half-built window state.
            Log.e(TAG, "Edge trigger sync failed; detaching all handles", t)
            EdgeSide.entries.forEach { runCatching { detach(it) } }
        } finally {
            isSyncing = false
            pendingPeek = false
        }
    }

    private fun shouldBeAttached(handleConfig: EdgeHandleConfig): Boolean =
        config.masterEnabled && handleConfig.enabled &&
            (previewMode || suppressReasons.isEmpty())

    private fun attachOrUpdate(side: EdgeSide, handleConfig: EdgeHandleConfig) {
        val params = buildParams(handleConfig)
        val existing = handles[side]

        if (existing != null) {
            existing.suppressBackGesture = config.suppressBackGesture
            existing.previewMode = previewMode
            existing.config = handleConfig
            // updateViewLayout rather than detach/reattach: no visible flash, and it re-runs
            // onLayout so the gesture-exclusion rects are refreshed.
            runCatching {
                if (existing.isAttachedToWindow) windowManager.updateViewLayout(existing, params)
            }.onFailure { Log.e(TAG, "updateViewLayout failed for $side", it) }
            if (pendingPeek) existing.peek()
            return
        }

        // Note the explicit qualifiers: inside this apply block a bare `config` would resolve to
        // the view's own EdgeHandleConfig property, not the controller's TriggerConfig.
        val view = EdgeHandleView(context, side).apply {
            suppressBackGesture = this@EdgeTriggerController.config.suppressBackGesture
            // Must precede the config assignment: the config setter settles the idle alpha, and
            // that calculation depends on whether preview is active.
            previewMode = this@EdgeTriggerController.previewMode
            config = handleConfig
            onTrigger = { triggerSource ->
                fireShortcutHubTrigger(context, triggerSource, this@EdgeTriggerController.config.refireCooldownMs)
            }
            onImmersiveChanged = { immersive ->
                if (this@EdgeTriggerController.config.suppressWhenImmersive) {
                    setSuppressed(SuppressReason.IMMERSIVE, immersive)
                }
            }
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                handles[side] = view
                if (pendingPeek) view.peek()
            }
            .onFailure { Log.e(TAG, "addView failed for $side", it) }
    }

    private fun detach(side: EdgeSide) {
        val view = handles.remove(side) ?: return
        runCatching {
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        }.onFailure { Log.e(TAG, "removeView failed for $side", it) }
    }

    private fun buildParams(handleConfig: EdgeHandleConfig): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val touchDepthPx = (handleConfig.touchDepthDp * density).roundToInt().coerceAtLeast(1)
        val lengthPx = (handleConfig.lengthDp * density).roundToInt().coerceAtLeast(1)
        val (_, displayHeight) = windowManager.realDisplaySize()
        val maxY = (displayHeight - lengthPx).coerceAtLeast(0)

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (config.showOnLockscreen) {
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        }

        return WindowManager.LayoutParams(
            touchDepthPx,
            lengthPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            // Deliberately NOT FLAG_NOT_TOUCHABLE (the window must receive touches) and
            // deliberately no LayoutParams.alpha — invisibility is drawn by the view, because a
            // zero-alpha window counts as "invisible" for Android's touch-obscuring rules.
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or
                (if (handleConfig.side == EdgeSide.LEFT) Gravity.START else Gravity.END)
            x = 0
            y = (maxY * handleConfig.verticalBiasFraction).roundToInt().coerceIn(0, maxY)
        }
    }

    private fun recomputeFilters() {
        blockedPackages = config.blockApps.mapTo(mutableSetOf()) { it.packageName }
        allowedPackages = config.allowApps.mapTo(mutableSetOf()) { it.packageName }
    }

    private fun applyPerAppFilter(packageName: String?) {
        val blocked = when (config.filterMode) {
            TriggerFilterMode.BLACKLIST -> packageName != null && packageName in blockedPackages
            TriggerFilterMode.WHITELIST -> packageName != null && packageName !in allowedPackages
        }
        setSuppressed(SuppressReason.PER_APP, blocked)
    }
}
