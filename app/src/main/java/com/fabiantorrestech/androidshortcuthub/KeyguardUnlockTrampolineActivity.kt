package com.fabiantorrestech.androidshortcuthub

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Transparent trampoline that calls [KeyguardManager.requestDismissKeyguard] and, on success,
 * dispatches the bundled [EXTRA_LAUNCH_INTENT] according to [EXTRA_DISPATCH_TYPE].
 * Used by the accessibility-service overlay (which is not an Activity and cannot call
 * requestDismissKeyguard directly) and by [LockscreenOverlayActivity] for consistency.
 */
class KeyguardUnlockTrampolineActivity : ComponentActivity() {

    companion object {
        private const val TAG = "KeyguardUnlockTrampoline"
        // Fallback finish() if the launched activity never comes to the foreground.
        private const val LAUNCH_WATCHDOG_MS = 2000L
        private const val EXTRA_LAUNCH_INTENT = "launch_intent"
        private const val EXTRA_DISPATCH_TYPE = "dispatch_type"

        private const val DISPATCH_ACTIVITY = "activity"
        private const val DISPATCH_BROADCAST = "broadcast"
        private const val DISPATCH_SERVICE = "service"

        fun createIntent(context: Context, launchIntent: Intent): Intent =
            createIntent(context, launchIntent, DISPATCH_ACTIVITY)

        fun createBroadcastIntent(context: Context, launchIntent: Intent): Intent =
            createIntent(context, launchIntent, DISPATCH_BROADCAST)

        fun createServiceIntent(context: Context, launchIntent: Intent): Intent =
            createIntent(context, launchIntent, DISPATCH_SERVICE)

        private fun createIntent(context: Context, launchIntent: Intent, dispatchType: String): Intent =
            Intent(context, KeyguardUnlockTrampolineActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_INTENT, launchIntent)
                putExtra(EXTRA_DISPATCH_TYPE, dispatchType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private var pendingLaunchIntent: Intent? = null
    private var dispatchType: String = DISPATCH_ACTIVITY
    private var keyguardDismissRequested = false
    private var activityLaunchPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        @Suppress("DEPRECATION")
        pendingLaunchIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT)
        }

        if (pendingLaunchIntent == null) {
            finish()
            return
        }

        dispatchType = intent.getStringExtra(EXTRA_DISPATCH_TYPE) ?: DISPATCH_ACTIVITY
    }

    // requestDismissKeyguard requires the activity to be visible on screen.
    // Calling it from onCreate() (before the window is shown) causes it to be silently
    // ignored, so the unlock UI never appears. onResume() guarantees visibility.
    override fun onResume() {
        super.onResume()
        if (keyguardDismissRequested) return
        keyguardDismissRequested = true
        val launchIntent = pendingLaunchIntent ?: return
        val km = getSystemService(KeyguardManager::class.java)
        Log.d(TAG, "onResume: dispatchType=$dispatchType isKeyguardLocked=${km.isKeyguardLocked}")
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d(TAG, "onDismissSucceeded → dispatching")
                    dispatchAndFinish(launchIntent, dispatchType)
                }
                override fun onDismissCancelled() {
                    Log.d(TAG, "onDismissCancelled")
                    finish()
                }
                override fun onDismissError() {
                    Log.w(TAG, "onDismissError")
                    finish()
                }
            })
        } else {
            dispatchAndFinish(launchIntent, dispatchType)
        }
    }

    private fun dispatchAndFinish(launchIntent: Intent, dispatchType: String) {
        val launched = dispatch(launchIntent, dispatchType)
        if (dispatchType == DISPATCH_ACTIVITY && launched) {
            // Don't finish() now. Finishing the instant the keyguard reports "dismissed" leaves a
            // frame with no foreground activity during the keyguard's dismiss animation, so the
            // lock re-asserts and the target lands behind it again ("almost unlocks, then reverts").
            // Keep this transparent showWhenLocked activity in front until the launched activity
            // actually covers it (onStop), which holds the device unlocked through the transition.
            // A watchdog finishes us if the launch never foregrounds.
            activityLaunchPending = true
            window.decorView.postDelayed({ if (!isFinishing) finish() }, LAUNCH_WATCHDOG_MS)
        } else {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // The launched target has come to the foreground (covering us) → safe to finish now.
        if (activityLaunchPending && !isFinishing) {
            activityLaunchPending = false
            finish()
        }
    }

    /** Returns true if the dispatch (startActivity/sendBroadcast/startService) succeeded. */
    private fun dispatch(launchIntent: Intent, dispatchType: String): Boolean =
        runCatching {
            when (dispatchType) {
                DISPATCH_BROADCAST -> sendBroadcast(launchIntent)
                DISPATCH_SERVICE -> startService(launchIntent)
                else -> startActivity(launchIntent)
            }
        }.onFailure { Log.e(TAG, "dispatch failed (type=$dispatchType)", it) }
            .onSuccess { Log.d(TAG, "dispatch OK (type=$dispatchType)") }
            .isSuccess
}
