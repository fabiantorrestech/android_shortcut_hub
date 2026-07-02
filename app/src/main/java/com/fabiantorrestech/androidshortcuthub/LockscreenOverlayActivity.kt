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
    private var widgetHostListening = false

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
            val (portraitState, landscapeState) = withContext(Dispatchers.IO) {
                OverlayStateRepository.loadBoth(this@LockscreenOverlayActivity)
            }
            val preloadedFonts = OverlayRuntimeCache.cachedFontsFor(portraitState, landscapeState)

            if (isFinishing) return@launch

            ensureWidgetHostStartedIfNeeded(portraitState)
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
                        onPersist = { state, orientation ->
                            OverlayStateRepository.saveLayout(this@LockscreenOverlayActivity, state, orientation)
                        },
                        onDismiss = ::dismissOverlay,
                    )
                }
            }
            warmFontsAsync(portraitState, landscapeState)
            Log.d(
                TAG,
                "Overlay shown in ${SystemClock.elapsedRealtime() - startMs}ms",
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        unregisterReceiver(systemUiDismissReceiver)
        stopWidgetHostListeningIfNeeded()
        if (instanceRef.get() == this) instanceRef = WeakReference(null)
        super.onDestroy()
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
        lifecycleScope.launch(Dispatchers.IO) {
            OverlayRuntimeCache.preloadFonts(portraitState, landscapeState, ::loadFontFamily)
        }
    }

    private fun loadFontFamily(uriString: String?): FontFamily? {
        val parsedUri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        return runCatching {
            contentResolver.openFileDescriptor(parsedUri, "r")?.use { descriptor ->
                FontFamily(Typeface.Builder(descriptor.fileDescriptor).build())
            }
        }.getOrNull()
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

    // Set while a keyguard-dismiss prompt is showing for an unlock-to-launch tile.
    // Suppresses the immediate finish() that onTileTap requests, so this activity stays
    // alive to host the KeyguardManager.requestDismissKeyguard callback. The callback
    // finishes us once auth completes (or is cancelled).
    private var unlockInProgress = false

    private fun dismissOverlay() {
        if (unlockInProgress) return
        finish()
    }

    /**
     * Runs [dispatch] once the keyguard is dismissed. This activity is already a visible
     * showWhenLocked activity, so it can call requestDismissKeyguard on itself directly —
     * no trampoline hop, which avoids the task/affinity race that previously dropped the
     * launch. Secure locks show the PIN/pattern/biometric prompt; insecure locks just
     * dismiss. When already unlocked, [dispatch] runs immediately and the normal
     * onTileTap dismiss handles cleanup.
     */
    private fun unlockThenDispatch(dispatch: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            runCatching(dispatch).onFailure { Log.e(TAG, "dispatch failed (not locked)", it) }
            return
        }
        unlockInProgress = true
        km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                runCatching(dispatch).onFailure { Log.e(TAG, "dispatch failed after unlock", it) }
                unlockInProgress = false
                finish()
            }
            override fun onDismissCancelled() {
                unlockInProgress = false
                finish()
            }
            override fun onDismissError() {
                unlockInProgress = false
                finish()
            }
        })
    }

    private fun launchApp(app: LaunchableApp) {
        val target = buildAppLaunchIntent(app) ?: run {
            Log.e(TAG, "launchApp: buildAppLaunchIntent returned null for '${app.label}' pkg=${app.componentName?.packageName}")
            return
        }
        // Opening an app inherently requires the device unlocked, so always route through unlock.
        unlockThenDispatch { startActivity(target) }
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
        val dispatch: () -> Unit = {
            when (tile.intentType) {
                IntentType.ACTIVITY -> startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                IntentType.BROADCAST_RECEIVER -> sendBroadcast(intent)
                IntentType.SERVICE -> startService(intent)
            }
        }
        if (tile.unlockToLaunch) {
            unlockThenDispatch(dispatch)
        } else {
            runCatching(dispatch).onFailure { Log.e(TAG, "launchIntent dispatch failed", it) }
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
