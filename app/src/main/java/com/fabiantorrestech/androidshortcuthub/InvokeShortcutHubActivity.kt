package com.fabiantorrestech.androidshortcuthub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class InvokeShortcutHubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mirrors the ordering inside routeShortcutHubToggle: when the accessibility host is the
        // one that will render, SYSTEM_ALERT_WINDOW is irrelevant, so don't bounce the user to a
        // permission screen they never need.
        val usingAccessibilityHost = ShortcutHubSettings.load(this).useAccessibilityService &&
            ShortcutHubAccessibilityService.isConnected

        // While the hub is switched off, routeShortcutHubToggle refuses and says why; sending the
        // user to a permission screen first would be asking them to fix the wrong thing.
        if (HubSwitch.isEnabled(this) &&
            !usingAccessibilityHost &&
            !ShortcutHubOverlayService.canDrawOverlays(this)
        ) {
            Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            finish()
            return
        }

        routeShortcutHubToggle(this)
        finish()
    }
}
