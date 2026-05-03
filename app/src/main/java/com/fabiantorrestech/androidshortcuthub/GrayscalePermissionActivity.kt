package com.fabiantorrestech.androidshortcuthub

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Transparent one-shot Activity that shows the system screen-capture consent dialog.
 * On grant, forwards the raw result to GrayscaleCaptureForegroundService so it can call
 * getMediaProjection() itself — which requires the foreground service to already be running.
 */
class GrayscalePermissionActivity : Activity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Required for screen-capture result handling on all API levels")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            GrayscaleCaptureForegroundService.startWithGrant(this, resultCode, data)
        }
        finish()
    }
}
