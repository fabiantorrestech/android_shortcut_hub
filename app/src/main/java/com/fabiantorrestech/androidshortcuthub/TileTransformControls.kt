package com.fabiantorrestech.androidshortcuthub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Position and size controls for the selected tile.
 *
 * Replaces two flat rows of text buttons. Movement was `↑ ↓ ← →` laid out left to right, so the
 * button for "left" was third along and direction had no relationship to position on screen. Size
 * was `H+ H- W+ W-`, cryptic, height-first, and contradicting the header directly above it, which
 * reads WxH - as does the runtime overlay's own sheet.
 *
 * Movement is now a d-pad laid out as a cross, and size is two labelled steppers, width first.
 * Both hold to repeat, which matters because the grid goes up to 24x16 and crossing it a tap at a
 * time is not reasonable. Directions that cannot move are disabled rather than silently doing
 * nothing, which is how they behaved before: the underlying calls already reported failure and
 * every caller discarded it.
 */
@Composable
internal fun TileTransformControls(
    editorState: OverlayEditorState,
    tile: TileState,
) {
    val id = tile.id

    Text(
        "Position",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── D-pad: arrows sit where they point ──────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RepeatingStepButton(
                icon = Icons.Default.KeyboardArrowUp,
                label = "Move up",
                enabled = editorState.canMoveTile(id, -1, 0),
                onStep = { editorState.moveTile(id, -1, 0) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RepeatingStepButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    label = "Move left",
                    enabled = editorState.canMoveTile(id, 0, -1),
                    onStep = { editorState.moveTile(id, 0, -1) },
                )
                Spacer(Modifier.width(DPAD_BUTTON_SIZE))
                RepeatingStepButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    label = "Move right",
                    enabled = editorState.canMoveTile(id, 0, 1),
                    onStep = { editorState.moveTile(id, 0, 1) },
                )
            }
            RepeatingStepButton(
                icon = Icons.Default.KeyboardArrowDown,
                label = "Move down",
                enabled = editorState.canMoveTile(id, 1, 0),
                onStep = { editorState.moveTile(id, 1, 0) },
            )
        }

        // ── Size: width first, to match the WxH the header reports ──────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpanStepper(
                label = "Width",
                value = tile.columnSpan,
                canDecrease = editorState.canResizeTile(id, 0, -1),
                canIncrease = editorState.canResizeTile(id, 0, 1),
                onDecrease = { editorState.resizeTile(id, 0, -1) },
                onIncrease = { editorState.resizeTile(id, 0, 1) },
            )
            SpanStepper(
                label = "Height",
                value = tile.rowSpan,
                canDecrease = editorState.canResizeTile(id, -1, 0),
                canIncrease = editorState.canResizeTile(id, 1, 0),
                onDecrease = { editorState.resizeTile(id, -1, 0) },
                onIncrease = { editorState.resizeTile(id, 1, 0) },
            )
        }
    }
}

/**
 * `Width  [-] 3 [+]`.
 *
 * Shared with the runtime overlay's own edit sheet so the two inspectors present size the same way.
 * That sheet drives the live overlay through callbacks and has no cheap way to ask whether a resize
 * would fit, so the enabled flags default to true there and it simply no-ops as it always has.
 */
@Composable
internal fun SpanStepper(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(52.dp),
        )
        RepeatingStepButton(
            icon = Icons.Default.Remove,
            label = "Decrease $label",
            enabled = canDecrease,
            onStep = onDecrease,
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        RepeatingStepButton(
            icon = Icons.Default.Add,
            label = "Increase $label",
            enabled = canIncrease,
            onStep = onIncrease,
        )
    }
}

/**
 * A round step button that fires once on tap and then repeats while held.
 *
 * Deliberately not an IconButton with an onClick: the press gesture has to own both the tap and the
 * hold, or a tap ends up firing twice - once from the repeat handler and again from the button's
 * own click on release. Accessibility semantics are supplied by hand for the same reason.
 */
@Composable
private fun RepeatingStepButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outline.copy(alpha = DISABLED_ALPHA),
        ),
        modifier = Modifier
            .size(DPAD_BUTTON_SIZE)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick(label) { step(); true } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        step()
                        // Null means the finger was still down when the timeout elapsed, i.e. this
                        // is a hold rather than a tap, so start repeating.
                        if (withTimeoutOrNull(REPEAT_DELAY_MS) { tryAwaitRelease() } == null) {
                            while (withTimeoutOrNull(REPEAT_INTERVAL_MS) { tryAwaitRelease() } == null) {
                                step()
                            }
                        }
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = contentColor)
        }
    }
}

private val DPAD_BUTTON_SIZE = 44.dp
private const val DISABLED_ALPHA = 0.38f

/** How long a press must be held before it counts as a hold rather than a tap. */
private const val REPEAT_DELAY_MS = 400L

/**
 * Gap between repeats. Unhurried on purpose: a move jumps over any tiles in the way rather than
 * advancing one cell, so a single repeat can cover a lot of grid.
 */
private const val REPEAT_INTERVAL_MS = 120L
