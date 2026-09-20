package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.hypot

class ZeroPaddingWidgetHostView(
    context: Context,
) : AppWidgetHostView(context) {

    /**
     * Invoked when a touch on this widget looks like an *activation* — a tap (not a scroll, fling,
     * or long press) that landed on something clickable inside the widget's inflated RemoteViews
     * tree. Used to dismiss the overlay when the user actually acts on a widget.
     *
     * Null disables detection entirely, including the touch bookkeeping below.
     */
    var onWidgetActivated: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tracking = false

    init {
        super.setPadding(0, 0, 0, 0)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(0, 0, 0, 0)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onWidgetActivated == null) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                tracking = true
            }

            MotionEvent.ACTION_UP -> {
                // Let the widget consume the event FIRST. Dismissing tears down the overlay window,
                // so firing the callback before this would cancel the widget's own click delivery.
                val handled = super.dispatchTouchEvent(ev)
                if (tracking && isActivationGesture(ev)) onWidgetActivated?.invoke()
                tracking = false
                return handled
            }

            // A second finger means a pinch/multi-touch, not a tap on a control.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
        }

        return super.dispatchTouchEvent(ev)
    }

    /**
     * A tap that stayed put, ended quickly, and landed on a clickable target. The slop check rules
     * out scrolls, flings, and the widget stack's horizontal pager swipe; the timeout rules out the
     * long press that opens the tile's edit sheet.
     */
    private fun isActivationGesture(ev: MotionEvent): Boolean {
        if (ev.eventTime - downTime >= longPressTimeout) return false
        if (hypot(ev.x - downX, ev.y - downY) >= touchSlop) return false
        return hasClickableTargetAt(this, ev.x.toInt(), ev.y.toInt())
    }

    /**
     * Depth-first search for a clickable descendant whose visible bounds contain the point, in this
     * view's coordinate space.
     *
     * There is no public API to observe that a RemoteViews click was handled
     * (`RemoteViews.InteractionHandler` is hidden), so this hit-test is how we infer that a tap
     * would actually do something. [root] itself is never treated as a target: the overlay sets
     * `isClickable = true` on the host view, which would otherwise match every tap.
     */
    private fun hasClickableTargetAt(root: ViewGroup, x: Int, y: Int): Boolean {
        val bounds = Rect()
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            // getHitRect accounts for translation, so derive the child-local point from it rather
            // than from child.left/top, which don't.
            child.getHitRect(bounds)
            if (!bounds.contains(x, y)) continue

            val childX = x - bounds.left + child.scrollX
            val childY = y - bounds.top + child.scrollY
            if (child is ViewGroup && hasClickableTargetAt(child, childX, childY)) return true
            if (child.isClickable) return true
        }
        return false
    }
}
