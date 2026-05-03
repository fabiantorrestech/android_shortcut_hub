package com.fabiantorrestech.androidshortcuthub

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the screen-capture session and runs GrayscaleCaptureEngine.
 *
 * Lifecycle: starts when the user first grants screen-capture permission; stays alive across
 * overlay show/hide cycles so the user only needs to grant once per app session.
 * The engine (VirtualDisplay) is paused while the overlay is hidden to save battery.
 *
 * Android 14 constraint: getMediaProjection() requires this service to already be running with
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION. So the Activity forwards the raw grant result here
 * via startWithGrant(), and we call getMediaProjection() inside onStartCommand() — after
 * startForeground() has already been called in onCreate().
 */
class GrayscaleCaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "grayscale_capture"
        const val NOTIFICATION_ID = 2001

        private const val ACTION_DELIVER_GRANT = "com.fabiantorrestech.androidshortcuthub.DELIVER_GRANT"
        private const val ACTION_PAUSE_CAPTURE = "com.fabiantorrestech.androidshortcuthub.PAUSE_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        /** Called by GrayscalePermissionActivity after the user grants screen-capture consent. */
        fun startWithGrant(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, GrayscaleCaptureForegroundService::class.java).apply {
                action = ACTION_DELIVER_GRANT
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Resumes capture using an existing projection (second and subsequent overlay shows).
         * No-op if the service is not running.
         */
        fun resumeCapture(context: Context) {
            val intent = Intent(context, GrayscaleCaptureForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the capture engine but keeps the service alive (projection stays held). */
        fun pauseCapture(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GrayscaleCaptureForegroundService::class.java).apply {
                        action = ACTION_PAUSE_CAPTURE
                    },
                )
            }
        }

        /** Fully stops the service and releases the projection. */
        fun stop(context: Context) {
            context.stopService(Intent(context, GrayscaleCaptureForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var captureEngine: GrayscaleCaptureEngine? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DELIVER_GRANT -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    runCatching { manager.getMediaProjection(resultCode, data) }
                        .getOrNull()
                        ?.let { GrayscaleProjectionBroker.onProjectionGranted(it) }
                }
                startEngineIfNeeded()
            }
            ACTION_PAUSE_CAPTURE -> stopEngine()
            else -> startEngineIfNeeded()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        GrayscaleProjectionBroker.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEngineIfNeeded() {
        if (captureEngine != null) return
        val projection = GrayscaleProjectionBroker.projection.value ?: return
        captureEngine = GrayscaleCaptureEngine(
            projection = projection,
            windowManager = windowManager,
            frameSink = GrayscaleProjectionBroker.frameSink,
        ).also { it.start(serviceScope) }
    }

    private fun stopEngine() {
        captureEngine?.stop()
        captureEngine = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.grayscale_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) },
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.grayscale_notification_title))
            .setContentText(getString(R.string.grayscale_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
