package com.fabiantorrestech.androidshortcuthub

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val SLIDER_CHIP_HEIGHT = 36.dp

private val RAIL_PADDING = 24.dp
private val RAIL_WIDTH = 8.dp
private val KNOB_RADIUS = 14.dp
private val NOTCH_LENGTH = 10.dp
private val BUTTON_HEIGHT = 40.dp
private val SLIDER_OUTLINE_RADIUS = 18.dp

private fun sliderIcon(sliderType: SliderType, isIncrement: Boolean): ImageVector = when (sliderType) {
    SliderType.VOLUME     -> if (isIncrement) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeDown
    SliderType.BRIGHTNESS -> if (isIncrement) Icons.Rounded.BrightnessHigh else Icons.Rounded.BrightnessLow
}

@Composable
internal fun SystemSliderTile(
    config: SystemSliderConfig,
    isMoveMode: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    tileId: Int = -1,
    onPickerOpen: ((Int) -> Unit)? = null,
    onFanOutOpen: ((Int) -> Unit)? = null,
    pendingStreamForTile: Pair<Int, Int>? = null,
    onStreamConsumed: (() -> Unit)? = null,
) {
    val state = rememberSystemSliderState(config)
    val view = LocalView.current

    LaunchedEffect(pendingStreamForTile) {
        val pending = pendingStreamForTile ?: return@LaunchedEffect
        if (pending.first == tileId) {
            state.selectStream(pending.second)
            onStreamConsumed?.invoke()
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val isHorizontal = maxWidth > maxHeight

        val railColor = MaterialTheme.colorScheme.outline
        val fillColor = MaterialTheme.colorScheme.primary
        val knobColor = MaterialTheme.colorScheme.primary
        val notchColor = MaterialTheme.colorScheme.outlineVariant

        val totalSteps = state.maxValue.coerceAtLeast(1)
        val fraction = state.currentValue.toFloat() / totalSteps

        fun snapToStep(raw: Float): Int = (raw * totalSteps).roundToInt().coerceIn(0, totalSteps)

        var isDragging by remember { mutableStateOf(false) }
        var dragFraction by remember { mutableFloatStateOf(fraction) }
        var lastHapticStep by remember { mutableIntStateOf(snapToStep(fraction)) }

        // Sync with external changes (ContentObserver / hardware buttons) only while not dragging
        LaunchedEffect(fraction) {
            if (!isDragging) dragFraction = fraction
        }

        val sliderModifier = (
            if (!isMoveMode) Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPress() })
                }
                .pointerInput(isHorizontal, totalSteps, config.notchMode, config.showNotches, config.notchHapticsEnabled) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            lastHapticStep = snapToStep(dragFraction)
                        },
                        onDragEnd = {
                            state.setValue(snapToStep(dragFraction))
                            isDragging = false
                        },
                        onDragCancel = { isDragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        val railPx = if (isHorizontal) {
                            size.width - 2f * RAIL_PADDING.toPx()
                        } else {
                            size.height - 2f * RAIL_PADDING.toPx()
                        }
                        val delta = if (isHorizontal) dragAmount.x else -dragAmount.y
                        val newRaw = (dragFraction + delta / railPx).coerceIn(0f, 1f)
                        val snappedStep = snapToStep(newRaw)
                        dragFraction = when (config.notchMode) {
                            SliderNotchMode.LOCK_ONLY -> snappedStep.toFloat() / totalSteps
                            else -> newRaw
                        }
                        if (config.showNotches && config.notchHapticsEnabled && snappedStep != lastHapticStep) {
                            view.performHapticForcefully(HapticFeedbackType.TextHandleMove)
                            lastHapticStep = snappedStep
                        }
                        if (config.notchMode != SliderNotchMode.LOCK_ONLY) {
                            state.setValue(
                                if (config.notchMode == SliderNotchMode.LOCK_AND_SLIDE) snapToStep(dragFraction)
                                else (dragFraction * totalSteps).roundToInt().coerceIn(0, totalSteps)
                            )
                        }
                    }
                }
            else Modifier
        ).drawBehind {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val railPaddingPx = RAIL_PADDING.toPx()
                val railWidthPx = RAIL_WIDTH.toPx()
                val knobRadiusPx = KNOB_RADIUS.toPx()
                val notchLenPx = NOTCH_LENGTH.toPx()

                val trackCorner = CornerRadius(railWidthPx / 2f)
                val knobShadowRadiusPx = knobRadiusPx + 3.dp.toPx()

                if (isHorizontal) {
                    val startX = railPaddingPx
                    val endX = size.width - railPaddingPx
                    val fillEndX = startX + dragFraction * (endX - startX)
                    drawRoundRect(
                        color = railColor.copy(alpha = 0.25f),
                        topLeft = Offset(startX, cy - railWidthPx / 2f),
                        size = Size(endX - startX, railWidthPx),
                        cornerRadius = trackCorner,
                    )
                    drawRoundRect(
                        color = fillColor.copy(alpha = 0.85f),
                        topLeft = Offset(startX, cy - railWidthPx / 2f),
                        size = Size((fillEndX - startX).coerceAtLeast(0f), railWidthPx),
                        cornerRadius = trackCorner,
                    )
                    if (config.showNotches) {
                        for (step in 0..totalSteps) {
                            val nx = startX + step.toFloat() / totalSteps * (endX - startX)
                            drawLine(
                                color = notchColor,
                                start = Offset(nx, cy - notchLenPx / 2f),
                                end = Offset(nx, cy + notchLenPx / 2f),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    drawCircle(color = railColor.copy(alpha = 0.2f), radius = knobShadowRadiusPx, center = Offset(fillEndX, cy))
                    drawCircle(color = knobColor, radius = knobRadiusPx, center = Offset(fillEndX, cy))
                } else {
                    val startY = size.height - railPaddingPx
                    val endY = railPaddingPx
                    val knobY = startY - dragFraction * (startY - endY)
                    drawRoundRect(
                        color = railColor.copy(alpha = 0.25f),
                        topLeft = Offset(cx - railWidthPx / 2f, endY),
                        size = Size(railWidthPx, startY - endY),
                        cornerRadius = trackCorner,
                    )
                    drawRoundRect(
                        color = fillColor.copy(alpha = 0.85f),
                        topLeft = Offset(cx - railWidthPx / 2f, knobY),
                        size = Size(railWidthPx, (startY - knobY).coerceAtLeast(0f)),
                        cornerRadius = trackCorner,
                    )
                    if (config.showNotches) {
                        for (step in 0..totalSteps) {
                            val ny = startY - step.toFloat() / totalSteps * (startY - endY)
                            drawLine(
                                color = notchColor,
                                start = Offset(cx - notchLenPx / 2f, ny),
                                end = Offset(cx + notchLenPx / 2f, ny),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    drawCircle(color = railColor.copy(alpha = 0.2f), radius = knobShadowRadiusPx, center = Offset(cx, knobY))
                    drawCircle(color = knobColor, radius = knobRadiusPx, center = Offset(cx, knobY))
                }
            }

        @Composable
        fun SliderCanvas(sizeModifier: Modifier) {
            Box(modifier = sizeModifier.then(sliderModifier)) {
                if (config.sliderType == SliderType.VOLUME) {
                    when (config.streamMode) {
                        StreamMode.PICKER -> {
                            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                                AssistChip(
                                    onClick = { onPickerOpen?.invoke(tileId) },
                                    label = { Text(state.streamLabel(), fontSize = 9.sp, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Rounded.UnfoldMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                )
                            }
                        }
                        StreamMode.FAN_OUT -> {
                            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                                AssistChip(
                                    onClick = { onFanOutOpen?.invoke(tileId) },
                                    label = { Text("Channels", fontSize = 9.sp, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Rounded.Tune,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        val outlineModifier = if (config.showOutline) {
            Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(SLIDER_OUTLINE_RADIUS),
            )
        } else {
            Modifier
        }
        val containerModifier = Modifier.fillMaxSize()
            .clip(RoundedCornerShape(SLIDER_OUTLINE_RADIUS))
            .then(outlineModifier)

        when (config.buttonPlacement) {
            SliderButtonPlacement.NONE -> Box(modifier = containerModifier) {
                SliderCanvas(Modifier.fillMaxSize())
            }
            SliderButtonPlacement.SPLIT -> {
                if (isHorizontal) {
                    Row(
                        modifier = containerModifier,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, false),
                            modifier = Modifier.fillMaxHeight().width(BUTTON_HEIGHT),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(-config.buttonStepSize) }
                        SliderCanvas(Modifier.weight(1f).fillMaxHeight())
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, true),
                            modifier = Modifier.fillMaxHeight().width(BUTTON_HEIGHT),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(config.buttonStepSize) }
                    }
                } else {
                    Column(
                        modifier = containerModifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, true),
                            modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(config.buttonStepSize) }
                        SliderCanvas(Modifier.fillMaxWidth().weight(1f))
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, false),
                            modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(-config.buttonStepSize) }
                    }
                }
            }
            SliderButtonPlacement.TOP -> {
                Column(
                    modifier = containerModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT)) {
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, false),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(-config.buttonStepSize) }
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, true),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(config.buttonStepSize) }
                    }
                    SliderCanvas(Modifier.fillMaxWidth().weight(1f))
                }
            }
            SliderButtonPlacement.BOTTOM -> {
                Column(
                    modifier = containerModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SliderCanvas(Modifier.fillMaxWidth().weight(1f))
                    Row(modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT)) {
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, false),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(-config.buttonStepSize) }
                        HoldableButton(
                            icon = sliderIcon(config.sliderType, true),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            hapticsEnabled = config.buttonHapticsEnabled,
                        ) { state.step(config.buttonStepSize) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FanOutPanel(
    streams: List<Pair<Int, Pair<ImageVector, String>>>,
    panelHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val colWidth = 52.dp
    val iconAndLabelHeight = 44.dp
    val sliderLength = (panelHeight - iconAndLabelHeight - 16.dp).coerceAtLeast(40.dp)

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier.height(panelHeight).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            streams.forEach { (stream, pair) ->
                val (icon, label) = pair
                val maxVol = remember(stream) { audioManager.getStreamMaxVolume(stream).coerceAtLeast(1) }
                var vol by remember(stream) { mutableIntStateOf(audioManager.getStreamVolume(stream)) }

                Column(
                    modifier = Modifier.width(colWidth).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Slider(
                            value = vol.toFloat() / maxVol,
                            onValueChange = { newVal ->
                                val newVol = (newVal * maxVol).roundToInt().coerceIn(0, maxVol)
                                audioManager.setStreamVolume(stream, newVol, 0)
                                vol = newVol
                            },
                            modifier = Modifier
                                .requiredWidth(sliderLength)
                                .graphicsLayer { rotationZ = -90f },
                        )
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldableButton(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressing by interactionSource.collectIsPressedAsState()
    val view = LocalView.current

    fun clickWithOptionalHaptic() {
        if (hapticsEnabled) {
            view.performHapticForcefully(HapticFeedbackType.KeyboardTap)
        }
        onClick()
    }

    LaunchedEffect(pressing) {
        if (pressing) {
            delay(400L)
            while (pressing) {
                clickWithOptionalHaptic()
                delay(150L)
            }
        }
    }

    Surface(
        onClick = ::clickWithOptionalHaptic,
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
