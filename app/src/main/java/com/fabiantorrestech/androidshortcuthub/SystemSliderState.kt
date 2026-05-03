package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal class SystemSliderState(
    private val context: Context,
    val config: SystemSliderConfig,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val contentResolver = context.contentResolver

    var currentValue by mutableIntStateOf(0)
        private set
    var maxValue by mutableIntStateOf(1)
        private set

    // The stream currently being controlled (relevant when streamMode == ACTIVE or PICKER)
    var activeStream by mutableIntStateOf(resolveDefaultStream())
        private set

    private var observers: List<ContentObserver> = emptyList()

    fun start() {
        refresh()
        val handler = Handler(Looper.getMainLooper())
        val newObservers = mutableListOf<ContentObserver>()

        when (config.sliderType) {
            SliderType.BRIGHTNESS -> {
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) { refresh() }
                }
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    observer,
                )
                newObservers += observer
            }
            SliderType.VOLUME -> {
                // Watch all volume streams; refresh on any change
                val streamUris = listOf(
                    "volume_music",
                    "volume_ring",
                    "volume_alarm",
                    "volume_notification",
                )
                streamUris.forEach { key ->
                    val observer = object : ContentObserver(handler) {
                        override fun onChange(selfChange: Boolean) {
                            if (config.streamMode == StreamMode.ACTIVE) updateActiveStream()
                            refresh()
                        }
                    }
                    runCatching {
                        contentResolver.registerContentObserver(
                            Settings.System.getUriFor(key),
                            false,
                            observer,
                        )
                        newObservers += observer
                    }
                }
            }
        }
        observers = newObservers
    }

    fun stop() {
        observers.forEach { contentResolver.unregisterContentObserver(it) }
        observers = emptyList()
    }

    fun setStream(stream: Int) {
        activeStream = stream
        refresh()
    }

    fun setValue(value: Int) {
        val clamped = value.coerceIn(0, maxValue)
        when (config.sliderType) {
            SliderType.VOLUME -> audioManager.setStreamVolume(activeStream, clamped, 0)
            SliderType.BRIGHTNESS -> {
                runCatching {
                    // Must switch to manual mode first — auto-brightness will override our writes otherwise
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    )
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
                }
            }
        }
        currentValue = clamped
    }

    fun step(delta: Int) = setValue(currentValue + delta)

    fun streamLabel(): String = when (activeStream) {
        AudioManager.STREAM_MUSIC -> "Media"
        AudioManager.STREAM_RING -> "Ring"
        AudioManager.STREAM_ALARM -> "Alarm"
        AudioManager.STREAM_NOTIFICATION -> "Notif"
        else -> "Vol"
    }

    fun cycleStream() {
        val streams = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
        )
        val idx = streams.indexOf(activeStream)
        activeStream = streams[(idx + 1) % streams.size]
        refresh()
    }

    private fun refresh() {
        when (config.sliderType) {
            SliderType.VOLUME -> {
                maxValue = audioManager.getStreamMaxVolume(activeStream)
                currentValue = audioManager.getStreamVolume(activeStream)
            }
            SliderType.BRIGHTNESS -> {
                maxValue = 255
                currentValue = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrDefault(128)
            }
        }
    }

    private fun updateActiveStream() {
        // Best-effort: find which stream changed most recently by checking all streams.
        // We pick the stream whose current volume differs from what we last cached, if any;
        // otherwise keep current activeStream.
        val streams = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
        )
        val changedStream = streams.firstOrNull { stream ->
            audioManager.getStreamVolume(stream) != if (stream == activeStream) currentValue else audioManager.getStreamVolume(stream)
        }
        if (changedStream != null) activeStream = changedStream
    }

    private fun resolveDefaultStream(): Int = when (config.streamMode) {
        StreamMode.SINGLE, StreamMode.DEFAULT -> config.singleStream.toAudioManagerStream()
        StreamMode.PICKER -> config.singleStream.toAudioManagerStream()
        StreamMode.ACTIVE -> AudioManager.STREAM_MUSIC
    }
}

internal fun AudioStreamType.toAudioManagerStream(): Int = when (this) {
    AudioStreamType.MUSIC -> AudioManager.STREAM_MUSIC
    AudioStreamType.RING -> AudioManager.STREAM_RING
    AudioStreamType.ALARM -> AudioManager.STREAM_ALARM
    AudioStreamType.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
}

@Composable
internal fun rememberSystemSliderState(config: SystemSliderConfig): SystemSliderState {
    val context = LocalContext.current
    val state = remember(config.sliderType, config.streamMode, config.singleStream) {
        SystemSliderState(context, config)
    }
    DisposableEffect(state) {
        state.start()
        onDispose { state.stop() }
    }
    return state
}
