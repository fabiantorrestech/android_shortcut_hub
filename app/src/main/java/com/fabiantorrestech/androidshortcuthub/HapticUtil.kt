package com.fabiantorrestech.androidshortcuthub

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

fun View.performHapticForcefully(type: HapticFeedbackType) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return

    val effectId = when (type) {
        HapticFeedbackType.LongPress      -> VibrationEffect.EFFECT_HEAVY_CLICK
        HapticFeedbackType.TextHandleMove -> VibrationEffect.EFFECT_TICK
        else                              -> VibrationEffect.EFFECT_CLICK
    }
    val effect = VibrationEffect.createPredefined(effectId)

    // Call without VibrationAttributes so Android assigns USAGE_UNKNOWN, which bypasses
    // the "Touch feedback" system toggle while still respecting true silent/vibrate mode.
    // The attributed USAGE_TOUCH overload would be muted whenever the user disables touch
    // vibration in system settings — defeating the purpose of this function.
    @Suppress("DEPRECATION")
    vibrator.vibrate(effect)
}
