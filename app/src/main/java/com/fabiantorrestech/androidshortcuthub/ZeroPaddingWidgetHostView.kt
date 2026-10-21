package com.fabiantorrestech.androidshortcuthub

import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView

class ZeroPaddingWidgetHostView private constructor(
    private val launchWatcher: LaunchWatchingContext,
) : AppWidgetHostView(launchWatcher) {

    constructor(context: Context) : this(LaunchWatchingContext(context))

    /**
     * Invoked when a tap on this widget opens an app (launches an activity). Taps the widget
     * handles itself - a broadcast that toggles a view, refreshes, or pages its content - do not
     * count. Used to dismiss the overlay once the user has left for another app.
     */
    var onWidgetActivated: (() -> Unit)? = null

    init {
        super.setPadding(0, 0, 0, 0)
        launchWatcher.onLaunch = { opensApp ->
            // Posted: the launch has already been sent, and the dismiss must not tear the window
            // down underneath the click handler that is still on the stack.
            if (opensApp) onWidgetActivated?.let { post(it) }
        }
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

    /**
     * The context this host view inflates the widget with. Every RemoteViews click - a plain
     * button, a list row, a checkbox - ends in `view.context.startIntentSender(...)`, and the
     * inflated views' contexts wrap this one, so each launch passes through here. That tells us
     * exactly what a tap did, which no public listener on the widget's views does.
     */
    private class LaunchWatchingContext(base: Context) : ContextWrapper(base) {
        /** Called after each launch the widget sends; true when it opens an activity. */
        var onLaunch: ((Boolean) -> Unit)? = null

        override fun startIntentSender(
            intent: IntentSender,
            fillInIntent: Intent?,
            flagsMask: Int,
            flagsValues: Int,
            extraFlags: Int,
            options: Bundle?,
        ) {
            super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options)
            reportLaunch(intent, fillInIntent)
        }

        override fun startIntentSender(
            intent: IntentSender,
            fillInIntent: Intent?,
            flagsMask: Int,
            flagsValues: Int,
            extraFlags: Int,
        ) {
            super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags)
            reportLaunch(intent, fillInIntent)
        }

        private fun reportLaunch(intent: IntentSender, fillInIntent: Intent?) {
            val callback = onLaunch ?: return
            val opensApp = opensApp(intent, fillInIntent)
            Log.d(TAG, "Widget launch from ${intent.creatorPackage}: opensApp=$opensApp")
            callback(opensApp)
        }
    }

    private companion object {
        const val TAG = "WidgetLaunch"

        // Glance (Jetpack's widget toolkit) routes every tap inside a lazy list through one
        // activity "trampoline", because a list shares a single click template. The fill-in intent
        // names what the tap really does; only ACTIVITY opens an app. Other values: BROADCAST,
        // SERVICE, FOREGROUND_SERVICE, CALLBACK.
        const val GLANCE_SCHEME = "glance-action"
        const val GLANCE_ACTION_TYPE = "ACTION_TYPE"
        const val GLANCE_TYPE_ACTIVITY = "ACTIVITY"

        fun opensApp(sender: IntentSender, fillInIntent: Intent?): Boolean {
            glanceActionType(fillInIntent)?.let { return it == GLANCE_TYPE_ACTIVITY }
            // Before Android 12 there is no public way to ask; count every launch, which is how the
            // hub behaved before this check existed.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return toPendingIntent(sender)?.isActivity ?: true
        }

        fun glanceActionType(fillInIntent: Intent?): String? {
            if (fillInIntent?.data?.scheme != GLANCE_SCHEME) return null
            // The extras hold the widget's own target intent; unparcelling them must not take
            // the click down if something in there cannot be read.
            return runCatching { fillInIntent.getStringExtra(GLANCE_ACTION_TYPE) }.getOrNull()
        }

        /**
         * An IntentSender and a PendingIntent are the same binder token, and both parcel as just
         * that token, so a round trip through a Parcel recovers the PendingIntent - whose
         * [PendingIntent.isActivity] says what kind of launch it is.
         */
        fun toPendingIntent(sender: IntentSender): PendingIntent? {
            val parcel = Parcel.obtain()
            return try {
                sender.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                PendingIntent.readPendingIntentOrNullFromParcel(parcel)
            } catch (e: RuntimeException) {
                null
            } finally {
                parcel.recycle()
            }
        }
    }
}
