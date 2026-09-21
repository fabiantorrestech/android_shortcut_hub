package com.fabiantorrestech.androidshortcuthub

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

class InvokeShortcutHubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        routeShortcutHubToggle(context)
    }
}

internal fun routeShortcutHubToggle(context: Context) {
    val startMs = SystemClock.elapsedRealtime()
    val config = ShortcutHubSettings.load(context)

    // The accessibility host renders into a TYPE_ACCESSIBILITY_OVERLAY window, for which the
    // accessibility binding itself IS the permission — SYSTEM_ALERT_WINDOW is irrelevant. So this
    // branch has to come BEFORE the canDrawOverlays gate. With the checks the other way round, a
    // user running accessibility-only (overlay permission never granted) got a toast and nothing
    // else on every invocation, which would have made every edge-handle gesture toast instead of
    // opening the hub.
    if (config.useAccessibilityService && ShortcutHubAccessibilityService.isConnected) {
        Log.d("ShortcutHubRoute", "Toggle routed to accessibility service in ${SystemClock.elapsedRealtime() - startMs}ms")
        ShortcutHubAccessibilityService.toggle()
        return
    }

    if (!ShortcutHubOverlayService.canDrawOverlays(context)) {
        Toast.makeText(context, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
        return
    }

    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (km.isKeyguardLocked && config.showOverLockscreen) {
        Log.d("ShortcutHubRoute", "Toggle routed to lockscreen activity in ${SystemClock.elapsedRealtime() - startMs}ms")
        if (LockscreenOverlayActivity.isActive) {
            LockscreenOverlayActivity.finishIfActive()
        } else {
            context.startActivity(
                Intent(context, LockscreenOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    } else {
        Log.d("ShortcutHubRoute", "Toggle routed to overlay service in ${SystemClock.elapsedRealtime() - startMs}ms")
        ShortcutHubOverlayService.startToggle(context)
    }
}
