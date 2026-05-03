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
            val initialState = withContext(Dispatchers.IO) { OverlayStateRepository.load(this@LockscreenOverlayActivity) }
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
                        onPersist = { OverlayStateRepository.save(this@LockscreenOverlayActivity, it) },
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
