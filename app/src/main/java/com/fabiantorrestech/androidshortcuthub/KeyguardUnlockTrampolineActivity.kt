package com.fabiantorrestech.androidshortcuthub

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Transparent trampoline that calls [KeyguardManager.requestDismissKeyguard] and, on success,
 * starts the bundled [EXTRA_LAUNCH_INTENT]. Used by the accessibility-service overlay (which is
 * not an Activity and cannot call requestDismissKeyguard directly) and by
 * [LockscreenOverlayActivity] for consistency.
 */
class KeyguardUnlockTrampolineActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_LAUNCH_INTENT = "launch_intent"

        fun createIntent(context: Context, launchIntent: Intent): Intent =
            Intent(context, KeyguardUnlockTrampolineActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_INTENT, launchIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        @Suppress("DEPRECATION")
        val launchIntent: Intent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT)
        }

        if (launchIntent == null) {
            finish()
            return
        }

        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    runCatching { startActivity(launchIntent) }
                    finish()
                }
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            runCatching { startActivity(launchIntent) }
            finish()
        }
    }
}
