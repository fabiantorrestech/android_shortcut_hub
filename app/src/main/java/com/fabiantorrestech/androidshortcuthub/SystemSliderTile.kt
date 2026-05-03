package com.fabiantorrestech.androidshortcuthub

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val RAIL_PADDING = 24.dp
private val RAIL_WIDTH = 6.dp
private val KNOB_RADIUS = 14.dp
private val NOTCH_LENGTH = 10.dp
private val BUTTON_SIZE = 28.dp
private val SLIDER_OUTLINE_RADIUS = 18.dp

@Composable
internal fun SystemSliderTile(
    config: SystemSliderConfig,
    isMoveMode: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSystemSliderState(config)
    val hapticFeedback = LocalHapticFeedback.current

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
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

                if (isHorizontal) {
                    val startX = railPaddingPx
                    val endX = size.width - railPaddingPx
                    val fillEndX = startX + dragFraction * (endX - startX)
                    drawRect(
                        color = railColor.copy(alpha = 0.3f),
                        topLeft = Offset(startX, cy - railWidthPx / 2f),
                        size = Size(endX - startX, railWidthPx),
                    )
                    drawRect(
                        color = fillColor.copy(alpha = 0.7f),
                        topLeft = Offset(startX, cy - railWidthPx / 2f),
                        size = Size((fillEndX - startX).coerceAtLeast(0f), railWidthPx),
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
                    drawCircle(color = knobColor, radius = knobRadiusPx, center = Offset(fillEndX, cy))
                } else {
                    val startY = size.height - railPaddingPx
                    val endY = railPaddingPx
                    val knobY = startY - dragFraction * (startY - endY)
                    drawRect(
                        color = railColor.copy(alpha = 0.3f),
                        topLeft = Offset(cx - railWidthPx / 2f, endY),
                        size = Size(railWidthPx, startY - endY),
                    )
                    drawRect(
                        color = fillColor.copy(alpha = 0.7f),
                        topLeft = Offset(cx - railWidthPx / 2f, knobY),
                        size = Size(railWidthPx, (startY - knobY).coerceAtLeast(0f)),
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
                    drawCircle(color = knobColor, radius = knobRadiusPx, center = Offset(cx, knobY))
                }
            }

        @Composable
        fun SliderCanvas(sizeModifier: Modifier) {
            Box(modifier = sizeModifier.then(sliderModifier)) {
                if (config.sliderType == SliderType.VOLUME && config.streamMode == StreamMode.PICKER) {
                    OutlinedButton(
                        onClick = {
                            if (config.buttonHapticsEnabled) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                            }
                            state.cycleStream()
                        },
                        modifier = Modifier
                            .align(if (isHorizontal) Alignment.TopCenter else Alignment.CenterStart)
                            .height(22.dp)
                            .width(52.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(text = state.streamLabel(), fontSize = 9.sp, maxLines = 1)
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

        when (config.buttonPlacement) {
            SliderButtonPlacement.NONE -> Box(modifier = Modifier.fillMaxSize().then(outlineModifier)) {
                SliderCanvas(Modifier.fillMaxSize())
            }
            SliderButtonPlacement.SPLIT -> {
                if (isHorizontal) {
                    Row(
                        modifier = Modifier.fillMaxSize().then(outlineModifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HoldableButton(label = "-", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(-config.buttonStepSize)
                        }
                        SliderCanvas(Modifier.weight(1f).fillMaxHeight())
                        HoldableButton(label = "+", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(config.buttonStepSize)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().then(outlineModifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HoldableButton(label = "+", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(config.buttonStepSize)
                        }
                        SliderCanvas(Modifier.fillMaxWidth().weight(1f))
                        HoldableButton(label = "-", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(-config.buttonStepSize)
                        }
                    }
                }
            }
            SliderButtonPlacement.TOP -> {
                Column(
                    modifier = Modifier.fillMaxSize().then(outlineModifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        HoldableButton(label = "+", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(config.buttonStepSize)
                        }
                        HoldableButton(label = "-", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(-config.buttonStepSize)
                        }
                    }
                    SliderCanvas(Modifier.fillMaxWidth().weight(1f))
                }
            }
            SliderButtonPlacement.BOTTOM -> {
                Column(
                    modifier = Modifier.fillMaxSize().then(outlineModifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SliderCanvas(Modifier.fillMaxWidth().weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        HoldableButton(label = "-", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(-config.buttonStepSize)
                        }
                        HoldableButton(label = "+", hapticsEnabled = config.buttonHapticsEnabled) {
                            state.step(config.buttonStepSize)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldableButton(
    label: String,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressing by interactionSource.collectIsPressedAsState()
    val hapticFeedback = LocalHapticFeedback.current

    fun clickWithOptionalHaptic() {
        if (hapticsEnabled) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
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

    OutlinedButton(
        onClick = ::clickWithOptionalHaptic,
        modifier = modifier.size(BUTTON_SIZE),
        interactionSource = interactionSource,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text = label, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
