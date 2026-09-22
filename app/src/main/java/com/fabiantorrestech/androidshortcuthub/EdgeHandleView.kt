package com.fabiantorrestech.androidshortcuthub

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import kotlin.math.abs

private const val TAG = "ShortcutHubEdge"
private const val REVEAL_DURATION_MS = 120L
private const val FADE_DURATION_MS = 400L
private const val PEEK_HOLD_MS = 1_200L

/** How fast the bar answers a touch-down; short enough to read as instant. */
private const val TOUCH_IN_MS = 80L

/** How fast the bar retreats after a gesture that did not fire. */
private const val FADE_BACK_MS = 200L

/** Where a bare touch lands on the way from resting to touched opacity, before any travel. */
private const val TOUCH_BASE_FRACTION = 0.4f

/** How much wider than its resting width the bar has grown by the time the gesture fires. */
private const val STRETCH_MAX_FACTOR = 2.5f

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

    /**
     * 0..1: how far the current gesture has got towards firing. Drives the bar's width (drawn in
     * onDraw, so every change invalidates) and, outside preview, its opacity.
     */
    private var progress = 0f

    /** The hold-to-fire fill, or a fade-back. Never both: each cancels the other. */
    private var progressAnimator: ValueAnimator? = null

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

    /** Settles the bar to whatever width and opacity it should sit at when untouched. */
    private fun applyIdleAlpha() {
        animate().cancel()
        removeCallbacks(fadeOutRunnable)
        stopProgressAnimation()
        progress = 0f
        alpha = idleAlpha()
        invalidate()
    }

    private fun idleAlpha(): Float = if (previewMode) config.activeAlpha else restingAlpha()

    /**
     * Opacity for a gesture [p] of the way to firing. A bare touch already shows - part of the way
     * from resting to touched - and it climbs the rest of the way as the gesture nears firing.
     */
    private fun touchedAlpha(p: Float): Float {
        val rest = restingAlpha()
        val base = rest + (activeAlpha() - rest) * TOUCH_BASE_FRACTION
        return base + (activeAlpha() - base) * p
    }

    private fun setProgress(p: Float) {
        if (p == progress) return
        progress = p
        // Width is geometry, not a render property, so unlike alpha it needs a fresh onDraw.
        invalidate()
    }

    /** Shows the gesture [p] of the way to firing, directly: it follows the finger, not a curve. */
    private fun showProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        setProgress(clamped)
        if (!previewMode) {
            animate().cancel()
            alpha = touchedAlpha(clamped)
        }
    }

    private fun onTouchDown() {
        removeCallbacks(fadeOutRunnable)
        stopProgressAnimation()
        setProgress(0f)
        if (!previewMode) animateTo(touchedAlpha(0f), TOUCH_IN_MS)
    }

    /**
     * The gesture ended without firing, or was abandoned: shrink and fade straight back. Prompt on
     * purpose - a bar that lingered after an early release read as the hub being about to open.
     */
    private fun fadeBack() {
        stopProgressAnimation()
        animate().cancel()
        val fromProgress = progress
        val fromAlpha = alpha
        val toAlpha = idleAlpha()
        if (fromProgress == 0f && fromAlpha == toAlpha) return
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_BACK_MS
            addUpdateListener {
                val f = it.animatedFraction
                setProgress(fromProgress * (1f - f))
                alpha = fromAlpha + (toAlpha - fromAlpha) * f
            }
            start()
        }
    }

    /** Hold-to-fire has no travel to follow, so the bar fills over the hold time instead. */
    private fun startHoldFill() {
        stopProgressAnimation()
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = config.longPressMs.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { showProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun stopProgressAnimation() {
        progressAnimator?.cancel()
        progressAnimator = null
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
        // Widens as the gesture nears firing, but never past the touch strip it lives in.
        val stretched = (protrusion * STRETCH_MAX_FACTOR)
            .coerceAtMost(width.toFloat())
            .coerceAtLeast(protrusion)
        val barWidth = protrusion + (stretched - protrusion) * progress

        // The drawn bar hugs the screen edge; the rest of the window is invisible touch area.
        if (side == EdgeSide.LEFT) {
            barRect.set(0f, 0f, barWidth, height.toFloat())
        } else {
            barRect.set(width - barWidth, 0f, width.toFloat(), height.toFloat())
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
                onTouchDown()
                if (config.usesLongPress) {
                    postDelayed(longPressRunnable, config.longPressMs.toLong())
                    startHoldFill()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking || fired) return
                val dx = event.x - downX
                val dy = event.y - downY
                val wandered = abs(dx) > touchSlop || abs(dy) > touchSlop
                if (wandered) {
                    removeCallbacks(longPressRunnable)
                }
                val slop = dp(config.activationSlopDp).toFloat()

                if (config.usesDragAlong) {
                    // Travel along the edge, less any sideways drift. It only nears full as the
                    // fire condition below nears being met, and it falls as the finger heads back
                    // towards where it started - so swiping back visibly backs the bar off.
                    showProgress((abs(dy) - abs(dx)) / slop)
                    if (abs(dy) >= slop && abs(dy) > abs(dx)) {
                        // Travel parallel to the edge. SystemUI cancels Back the moment vertical
                        // travel dominates, so this can never be confused for a back swipe.
                        fire()
                    }
                } else if (config.usesInwardSwipe) {
                    val inward = if (side == EdgeSide.LEFT) dx else -dx
                    if (abs(dy) > abs(dx) && abs(dy) > touchSlop) {
                        cancelGesture()
                        fadeBack()
                    } else {
                        showProgress(inward / slop)
                        if (inward >= slop) fire()
                    }
                } else if (wandered) {
                    // Hold or tap: movement is already fatal to both, so let the bar go now
                    // rather than when the finger finally lifts.
                    cancelGesture()
                    fadeBack()
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
                // Whatever did not fire goes straight back. One that did is about to be hidden
                // by the hub opening; this covers the case where the hub does not open.
                fadeBack()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                fadeBack()
            }
        }
    }

    private fun cancelGesture() {
        removeCallbacks(longPressRunnable)
        stopProgressAnimation()
        tracking = false
    }

    private fun fire() {
        if (fired) return
        fired = true
        tracking = false
        removeCallbacks(longPressRunnable)
        stopProgressAnimation()
        // Land on full for the moment before the hub opens and the handle stands down.
        showProgress(1f)
        // No buzz here: the hub buzzes as it opens (vibrateForHubOpen), for every trigger alike.
        onTrigger?.invoke(source)
    }

    override fun onDetachedFromWindow() {
        // A leaked animator or pending callback on a removed WindowManager view is a slow leak.
        animate().cancel()
        stopProgressAnimation()
        removeCallbacks(longPressRunnable)
        removeCallbacks(fadeOutRunnable)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
