package com.fabiantorrestech.androidshortcuthub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import kotlin.math.abs

private const val TAG = "ShortcutHubEdge"
private const val REVEAL_DURATION_MS = 120L
private const val FADE_DURATION_MS = 400L
private const val PEEK_HOLD_MS = 1_200L

/**
 * The thin strip parked against a screen edge that summons the hub.
 *
 * Written as a plain [View] rather than a ComposeView on purpose. This window is resident for the
 * whole session, so a Compose host would keep a live composition, recomposer and lifecycle owner
 * alive the entire time, and would need a third verbatim copy of OverlayLifecycleOwner (it is
 * already duplicated across both overlay hosts). A rounded bar with an animated alpha is a few
 * dozen lines of onDraw, and onTouchEvent hands us MotionEvent directly, which is what a gesture
 * state machine actually wants.
 *
 * Note that view alpha is used for invisibility rather than window alpha: a view with alpha 0
 * still receives touches, whereas a window with alpha 0 counts as "invisible" for Android's
 * touch-obscuring rules and invites OEM-specific input weirdness.
 */
@SuppressLint("ViewConstructor")
internal class EdgeHandleView(
    context: Context,
    private val side: EdgeSide,
) : View(context) {

    var onTrigger: ((TriggerSource) -> Unit)? = null
    var onImmersiveChanged: ((Boolean) -> Unit)? = null
    var suppressBackGesture: Boolean = true

    /**
     * Authoring aid for the Triggers tab: pins the bar fully drawn and disables every fade, so
     * the user can see geometry and colour changes land in real time. Shows the bar even when the
     * configured visibility is [EdgeVisibility.INVISIBLE] — that is the whole point of a preview.
     */
    var previewMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyIdleAlpha()
        }

    var config: EdgeHandleConfig = EdgeHandleConfig(side)
        set(value) {
            field = value
            applyColor()
            // Snap to the new resting alpha, otherwise switching (say) Always visible -> Always
            // hidden would leave the bar sitting at its old opacity until the next touch.
            // Skipped mid-gesture so a config change can't blank a handle under the user's finger.
            if (!tracking) applyIdleAlpha()
            requestLayout()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false
    private var fired = false

    private val longPressRunnable = Runnable {
        if (tracking && !fired) fire()
    }
    private val fadeOutRunnable = Runnable { animateTo(restingAlpha()) }

    private val source: TriggerSource =
        if (side == EdgeSide.LEFT) TriggerSource.EDGE_LEFT else TriggerSource.EDGE_RIGHT

    init {
        applyColor()
        alpha = restingAlpha()
    }

    private fun applyColor() {
        // White is the fallback rather than a theme lookup because this view is created from a
        // service context, where the Compose colour scheme is not available.
        paint.color = config.colorHex
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            ?: Color.WHITE
    }

    private fun restingAlpha(): Float = when (config.visibility) {
        EdgeVisibility.INVISIBLE, EdgeVisibility.REVEAL_ON_CONTACT -> 0f
        EdgeVisibility.DIM, EdgeVisibility.ALWAYS_VISIBLE -> config.restingAlpha
    }

    private fun activeAlpha(): Float = when (config.visibility) {
        EdgeVisibility.INVISIBLE -> 0f
        else -> config.activeAlpha
    }

    private fun animateTo(target: Float, durationMs: Long = FADE_DURATION_MS) {
        animate().cancel()
        if (target == alpha) return
        animate().alpha(target).setDuration(durationMs).start()
    }

    /** Settles the bar to whatever opacity it should sit at when untouched. */
    private fun applyIdleAlpha() {
        animate().cancel()
        removeCallbacks(fadeOutRunnable)
        alpha = if (previewMode) config.activeAlpha else restingAlpha()
        invalidate()
    }

    private fun reveal() {
        if (previewMode) return
        removeCallbacks(fadeOutRunnable)
        animateTo(activeAlpha(), REVEAL_DURATION_MS)
    }

    private fun scheduleFadeOut() {
        if (previewMode) return
        removeCallbacks(fadeOutRunnable)
        postDelayed(fadeOutRunnable, config.revealLingerMs.toLong())
    }

    /**
     * Briefly shows the handle so the user can see where it landed after changing a setting.
     * Only called on an explicit config change — never from a rotation resync, which would make
     * the handle flash every time the device turns.
     */
    fun peek() {
        // In preview the bar is already pinned visible, so a peek would only fade it back out.
        if (previewMode) return
        if (config.visibility == EdgeVisibility.INVISIBLE) return
        removeCallbacks(fadeOutRunnable)
        animateTo(activeAlpha(), REVEAL_DURATION_MS)
        postDelayed(fadeOutRunnable, PEEK_HOLD_MS)
    }

    override fun onDraw(canvas: Canvas) {
        // Deliberately no early return for a transparent bar. Alpha is a render property: animating
        // it re-composites the drawing recorded here but never calls onDraw again. This used to
        // skip drawing at alpha 0, which is exactly where "Reveal on touch" rests, so the recorded
        // drawing was empty and every reveal faded in nothing - the handle never showed when
        // touched. (The Triggers-tab preview hid it, because entering preview invalidates at full
        // alpha.) A view at alpha 0 costs nothing to composite anyway.
        val protrusion = dp(config.protrusionDp).toFloat()
        if (protrusion <= 0f || width == 0 || height == 0) return

        // The drawn bar hugs the screen edge; the rest of the window is invisible touch area.
        if (side == EdgeSide.LEFT) {
            barRect.set(0f, 0f, protrusion, height.toFloat())
        } else {
            barRect.set(width - protrusion, 0f, width.toFloat(), height.toFloat())
        }
        val radius = dp(config.cornerRadiusDp).toFloat()
        canvas.drawRoundRect(barRect, radius, radius, paint)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyGestureExclusion()
    }

    /**
     * Opts this strip out of the system Back gesture.
     *
     * Only meaningful for [EdgeActivation.SWIPE_IN]: SystemUI's back detector is a global input
     * monitor, so it sees the touch and can steal the gesture even though this window sits above
     * it and consumed the event. Exclusion rects are the only way to stop that. Every other
     * activation mode is already Back-safe by construction, so it does not pay the 200dp-per-edge
     * exclusion budget it would otherwise consume.
     */
    private fun applyGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            systemGestureExclusionRects =
                if (suppressBackGesture && config.usesInwardSwipe && width > 0 && height > 0) {
                    listOf(Rect(0, 0, width, height))
                } else {
                    // The explicit clear matters — a stale rect would keep suppressing Back.
                    emptyList()
                }
        }.onFailure { Log.e(TAG, "Gesture exclusion update failed", it) }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val immersive = !insets.isVisible(WindowInsets.Type.statusBars()) &&
                    !insets.isVisible(WindowInsets.Type.navigationBars())
                onImmersiveChanged?.invoke(immersive)
            }.onFailure { Log.e(TAG, "Immersive detection failed", it) }
        }
        return super.onApplyWindowInsets(insets)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A throw here would kill the process on the user's next accidental brush against the
        // edge, and Android would rebind the crashed accessibility service straight into a loop.
        return try {
            handleTouch(event)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Edge handle touch failed", t)
            tracking = false
            true
        }
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                tracking = true
                fired = false
                reveal()
                if (config.usesLongPress) {
                    postDelayed(longPressRunnable, config.longPressMs.toLong())
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking || fired) return
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    removeCallbacks(longPressRunnable)
                }
                val slop = dp(config.activationSlopDp).toFloat()

                if (config.usesDragAlong && abs(dy) >= slop && abs(dy) > abs(dx)) {
                    // Travel parallel to the edge. SystemUI cancels Back the moment vertical
                    // travel dominates, so this can never be confused for a back swipe.
                    fire()
                } else if (config.usesInwardSwipe) {
                    val inward = if (side == EdgeSide.LEFT) dx else -dx
                    if (abs(dy) > abs(dx) && abs(dy) > touchSlop) {
                        cancelGesture()
                    } else if (inward >= slop) {
                        fire()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val movedLittle = abs(event.x - downX) <= touchSlop &&
                    abs(event.y - downY) <= touchSlop
                val quick = event.eventTime - downTime < config.longPressMs
                if (!fired && tracking && config.usesTap && movedLittle && quick) {
                    fire()
                }
                tracking = false
                scheduleFadeOut()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                scheduleFadeOut()
            }
        }
    }

    private fun cancelGesture() {
        removeCallbacks(longPressRunnable)
        tracking = false
    }

    private fun fire() {
        if (fired) return
        fired = true
        tracking = false
        removeCallbacks(longPressRunnable)
        // No buzz here: the hub buzzes as it opens (vibrateForHubOpen), for every trigger alike.
        onTrigger?.invoke(source)
    }

    override fun onDetachedFromWindow() {
        // A leaked animator or pending callback on a removed WindowManager view is a slow leak.
        animate().cancel()
        removeCallbacks(longPressRunnable)
        removeCallbacks(fadeOutRunnable)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
