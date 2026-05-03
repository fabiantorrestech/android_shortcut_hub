package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that owns the MediaProjection grant across overlay show/hide cycles,
 * and exposes the latest grayscale bitmap frame to any observer.
 */
object GrayscaleProjectionBroker {

    private val _projection = MutableStateFlow<MediaProjection?>(null)
    val projection: StateFlow<MediaProjection?> = _projection.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    // GrayscaleCaptureEngine writes here directly.
    internal val frameSink: MutableStateFlow<Bitmap?> = _frame

    fun onProjectionGranted(projection: MediaProjection) {
        _projection.value?.stop()
        _projection.value = projection
    }

    fun release() {
        _projection.value?.stop()
        _projection.value = null
        _frame.value = null
    }

    fun hasProjection(): Boolean = _projection.value != null

    fun requestProjection(context: Context) {
        context.startActivity(
            Intent(context, GrayscalePermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }
}
