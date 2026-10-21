package com.fabiantorrestech.androidshortcuthub

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import kotlin.math.hypot

class ZeroPaddingWidgetHostView(
    context: Context,
) : AppWidgetHostView(context) {

    /**
     * Invoked when a touch on this widget looks like an *activation* — a tap (not a scroll, fling,
     * or long press) that landed on something inside the widget's inflated RemoteViews tree that
     * acts on it. Used to dismiss the overlay when the user actually acts on a widget.
     *
     * Null disables detection entirely, including the touch bookkeeping below.
     */
    var onWidgetActivated: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // AbsListView fires a row click one pressed-state duration after ACTION_UP; doubling it keeps
    // the activation callback safely behind that.
    private val clickSettleDelayMs = ViewConfiguration.getPressedStateDuration().toLong() * 2

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

    /**
     * [WidgetViewCache] reuses this view across hub sessions, so it is re-attached to every new
     * overlay window. On re-attach, AbsListView marks its data as changed and waits for a layout
     * pass to refresh. Compose re-attaches the view without one, so the flag stays set and the list
     * silently ignores every tap: collection widgets (Calendar, Beeminder, ...) stop launching
     * anything after the hub's first open. Forcing a layout on each AdapterView clears it.
     *
     * Our onAttachedToWindow runs before the children's, but the layout request survives until the
     * next layout pass, which runs after they have all attached.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestLayoutOnAdapterViews(this)
    }

    private fun requestLayoutOnAdapterViews(view: View) {
        if (view is AdapterView<*>) view.requestLayout()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) requestLayoutOnAdapterViews(view.getChildAt(i))
        }
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
                // Let the widget consume the event FIRST, then wait for its click to actually run
                // before signalling. Clicks are posted rather than run inside ACTION_UP (list rows
                // wait out the pressed-state duration), and detaching cancels a pending one - so a
                // dismiss without the open/close animation, which removes the window at once, would
                // otherwise swallow the very click it is reacting to.
                val handled = super.dispatchTouchEvent(ev)
                if (tracking && isActivationGesture(ev)) {
                    postDelayed({ onWidgetActivated?.invoke() }, clickSettleDelayMs)
                }
                tracking = false
                return handled
            }

            // A second finger means a pinch/multi-touch, not a tap on a control.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tracking = false
        }

        return super.dispatchTouchEvent(ev)
    }

    /**
     * A tap that stayed put, ended quickly, and landed on something that acts on it. The slop check
     * rules out scrolls, flings, and the widget stack's horizontal pager swipe; the timeout rules
     * out the long press that opens the tile's edit sheet.
     */
    private fun isActivationGesture(ev: MotionEvent): Boolean {
        if (ev.eventTime - downTime >= longPressTimeout) return false
        if (hypot(ev.x - downX, ev.y - downY) >= touchSlop) return false
        return findTapOutcome(this, ev.x.toInt(), ev.y.toInt()) == true
    }

    /**
     * Follows the tap the way Android dispatches it - deepest, topmost view first - to the view
     * that will consume it, and reports whether that view does anything with it. Returns null when
     * nothing in [root]'s subtree consumes the tap. The point is in [root]'s coordinate space.
     *
     * There is no public API to observe that a RemoteViews click was handled
     * (`RemoteViews.InteractionHandler` is hidden), so this hit-test is how we infer that a tap
     * would actually do something. [root] itself is never treated as a target: the overlay sets
     * `isClickable = true` on the host view, which would otherwise match every tap.
     *
     * Stopping at the consumer matters: a clickable view swallows the tap whether or not it acts on
     * it, so a widget root with its own click action never sees taps that land on a list above it.
     */
    private fun findTapOutcome(root: ViewGroup, x: Int, y: Int): Boolean? {
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
            if (child is ViewGroup) {
                findTapOutcome(child, childX, childY)?.let { return it }
            }
            if (child.isClickable) return doesClickableViewAct(child, childX, childY)
        }
        return null
    }

    /**
     * Whether a clickable view that consumes a tap acts on it.
     *
     * - A list (every AbsListView is clickable) acts only when the tap is on a row and the widget
     *   set a row click template. Empty space below the rows does nothing.
     * - A checkbox or switch toggles itself, without a click listener.
     * - Anything else acts only if RemoteViews gave it a click listener; a view marked clickable in
     *   the widget's layout just to block touches does not.
     */
    private fun doesClickableViewAct(view: View, x: Int, y: Int): Boolean = when (view) {
        is AdapterView<*> -> {
            val row = visibleChildAt(view, x, y)
            view.onItemClickListener != null && row != null && rowHasClickAction(row)
        }
        is CompoundButton -> true
        else -> view.hasOnClickListeners()
    }

    private fun visibleChildAt(parent: ViewGroup, x: Int, y: Int): View? {
        val bounds = Rect()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.getHitRect(bounds)
            if (bounds.contains(x, y)) return child
        }
        return null
    }

    /**
     * Whether a list row carries a click action. RemoteViews' row click handler looks for the
     * response stored under the framework's internal `fillInIntent` tag, on the row or any view
     * inside it, and does nothing when there is none (e.g. a header row whose only actions are its
     * own buttons, or an empty-state row). The tag
     * key is not public API, so it is resolved by name; if a future Android renames it, every row
     * counts as actionable - the behaviour before this check existed.
     */
    private fun rowHasClickAction(row: View): Boolean {
        if (fillInIntentTagId == 0) return true
        if (row.getTag(fillInIntentTagId) != null) return true
        if (row is ViewGroup) {
            for (i in 0 until row.childCount) {
                if (rowHasClickAction(row.getChildAt(i))) return true
            }
        }
        return false
    }

    private companion object {
        @SuppressLint("DiscouragedApi")
        val fillInIntentTagId: Int =
            Resources.getSystem().getIdentifier("fillInIntent", "id", "android")
    }
}
