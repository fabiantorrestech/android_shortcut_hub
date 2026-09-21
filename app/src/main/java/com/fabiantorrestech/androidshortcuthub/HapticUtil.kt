package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

private const val TAG = "ShortcutHubHaptics"

private fun Context.defaultVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }

/**
 * The buzz for the hub opening, whichever trigger opened it.
 *
 * Deliberately not [performHapticForcefully]: see the note there - a click with no declared usage
 * is filed as touch feedback, so it goes quiet whenever the system Touch feedback switch is off.
 * This declares USAGE_ALARM instead, because it is the one usage that clears everything in the way:
 *  - the Touch feedback switch does not govern it (alarm vibration has its own setting);
 *  - Android lets it through from an app in the background, which is where a Key Mapper broadcast
 *    or the overlay-service host can be when this runs;
 *  - ringer mode does not affect it - so silent mode is honoured here instead, explicitly, unless
 *    the user asked to ignore it.
 */
internal fun vibrateForHubOpen(context: Context, config: TriggerConfig) {
    if (!config.vibrateOnOpen) return
    // Runs on the accessibility service's main thread, where a throw kills the process and Android
    // rebinds the service straight into a crash loop. A missed buzz is the worst case allowed.
    runCatching {
        if (!config.vibrateInSilentMode) {
            val ringerMode = context.getSystemService(AudioManager::class.java)?.ringerMode
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) return
        }
        val vibrator = context.defaultVibrator()?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }.onFailure { Log.e(TAG, "Hub-open vibration failed", it) }
}

fun View.performHapticForcefully(type: HapticFeedbackType) {
    val vibrator = context.defaultVibrator() ?: return

    val effectId = when (type) {
        HapticFeedbackType.LongPress      -> VibrationEffect.EFFECT_HEAVY_CLICK
        HapticFeedbackType.TextHandleMove -> VibrationEffect.EFFECT_TICK
        else                              -> VibrationEffect.EFFECT_CLICK
    }
    val effect = VibrationEffect.createPredefined(effectId)

    // Called without attributes - but that does NOT escape the Touch feedback switch, as this
    // once assumed. Since Android 13, VibrationAttributes' haptic-feedback heuristic files a
    // predefined click with no usage as USAGE_TOUCH, so this is muted whenever Touch feedback is
    // off. vibrateForHubOpen above is the version that gets through.
    @Suppress("DEPRECATION")
    vibrator.vibrate(effect)
}
