package com.fabiantorrestech.androidshortcuthub

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class InvokeShortcutHubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        routeShortcutHubToggle(context)
    }
}

internal fun routeShortcutHubToggle(context: Context) {
    if (!ShortcutHubOverlayService.canDrawOverlays(context)) {
        Toast.makeText(context, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
        return
    }

    val config = ShortcutHubSettings.load(context)
    if (config.useAccessibilityService && ShortcutHubAccessibilityService.isConnected) {
        ShortcutHubAccessibilityService.toggle()
        return
    }

    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (km.isKeyguardLocked && config.showOverLockscreen) {
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
        ShortcutHubOverlayService.startToggle(context)
    }
}
