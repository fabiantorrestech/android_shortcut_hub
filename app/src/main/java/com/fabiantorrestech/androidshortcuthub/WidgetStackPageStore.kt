package com.fabiantorrestech.androidshortcuthub

import android.content.Context

/**
 * Remembers which widget each [WidgetStackTileState] was showing when the hub closed, and drives
 * "rotate on open": every [WidgetStackTileState.rotateOnOpenEvery] opens, the stack starts one
 * widget further along.
 *
 * Kept out of the layout JSON on purpose: it changes on every swipe and every open, and writing the
 * whole layout for that would be wasteful and would make the editor's Save / Discard meaningless
 * for it. Stored per stack tile id; a page past the end (widgets removed since) is clamped.
 */
internal object WidgetStackPageStore {
    private const val PREFS_NAME = "shortcut_hub_widget_stack_pages"
    private fun pageKey(stackId: Int) = "page_$stackId"
    private fun opensKey(stackId: Int) = "opens_$stackId"

    // Hub sessions are numbered per process so a stack counts one open per session, even when it
    // leaves and re-enters composition mid-session (move mode, rotation).
    private var lastSessionId = 0
    private val countedSession = HashMap<Int, Int>()

    /** Main thread only. Called once per hub open, by the root of the overlay's composition. */
    fun beginHubSession(): Int = ++lastSessionId

    /**
     * The page [stack] should open on. The first call for a stack in a session counts as an open
     * and, when rotate-on-open is due, advances the stored page; later calls in the same session
     * just return the stored page.
     */
    fun pageForOpen(context: Context, stack: WidgetStackTileState, sessionId: Int?): Int {
        val size = stack.widgets.size
        if (size == 0) return 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPage = prefs.getInt(pageKey(stack.id), 0).coerceIn(0, size - 1)

        if (sessionId == null || countedSession[stack.id] == sessionId) return storedPage
        countedSession[stack.id] = sessionId
        if (!stack.rotateOnOpen || size < 2) return storedPage

        val opens = prefs.getInt(opensKey(stack.id), 0) + 1
        if (opens < stack.rotateOnOpenEvery) {
            prefs.edit().putInt(opensKey(stack.id), opens).apply()
            return storedPage
        }
        val nextPage = (storedPage + 1) % size
        prefs.edit()
            .putInt(pageKey(stack.id), nextPage)
            .putInt(opensKey(stack.id), 0)
            .apply()
        return nextPage
    }

    /** The stack settled on [page], by any means: swipe, auto-rotate, or restore. */
    fun savePage(context: Context, stackId: Int, page: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(pageKey(stackId), 0) == page) return
        prefs.edit().putInt(pageKey(stackId), page).apply()
    }

    /** A manual swipe picks the widget to keep, so the rotate-on-open count starts over from it. */
    fun resetOpenCount(context: Context, stackId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(opensKey(stackId), 0).apply()
    }
}
