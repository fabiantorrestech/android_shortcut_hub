package com.fabiantorrestech.androidshortcuthub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TARGET_FPS = 30

class GrayscaleCaptureEngine(
    private val projection: MediaProjection,
    private val windowManager: WindowManager,
    private val frameSink: MutableStateFlow<Bitmap?>,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val grayscalePaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
    }

    fun start(scope: CoroutineScope) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val frameIntervalMs = 1000L / TARGET_FPS

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // Android 14+ requires a registered callback before createVirtualDisplay().
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }.also { projection.registerCallback(it, Handler(Looper.getMainLooper())) }

        virtualDisplay = projection.createVirtualDisplay(
            "GrayscaleCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        captureJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val frameStart = System.currentTimeMillis()
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val raw = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
                    )
                    raw.copyPixelsFromBuffer(buffer)
                    image.close()

                    val source = if (rowPadding == 0) raw
                    else Bitmap.createBitmap(raw, 0, 0, width, height).also { raw.recycle() }

                    val grayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(grayscale).drawBitmap(source, 0f, 0f, grayscalePaint)
                    source.recycle()

                    frameSink.value = grayscale
                }
                val elapsed = System.currentTimeMillis() - frameStart
                val remaining = frameIntervalMs - elapsed
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projectionCallback?.let { projection.unregisterCallback(it) }
        projectionCallback = null
        frameSink.value = null
    }
}
