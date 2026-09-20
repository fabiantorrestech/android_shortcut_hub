package com.fabiantorrestech.androidshortcuthub

import android.util.Log

internal object WidgetBindingCoordinator {
    private val lock = Any()

    private var bindingInProgress: Boolean = false
    private var pendingInsertion: TileInsertionEvent? = null

    fun startBinding() {
        synchronized(lock) {
            Log.d(WIDGET_BIND_TAG, "coordinator.startBinding (was inProgress=$bindingInProgress pending=$pendingInsertion)")
            bindingInProgress = true
            pendingInsertion = null
        }
    }

    fun completeInsertion(event: TileInsertionEvent) {
        synchronized(lock) {
            Log.d(WIDGET_BIND_TAG, "coordinator.completeInsertion event=$event (inProgress=$bindingInProgress)")
            pendingInsertion = event
        }
    }

    fun clear() {
        synchronized(lock) {
            Log.d(
                WIDGET_BIND_TAG,
                "coordinator.clear (discarding inProgress=$bindingInProgress pending=$pendingInsertion)",
                Throwable("clear() call site"),
            )
            bindingInProgress = false
            pendingInsertion = null
        }
    }

    fun consumeCompletedInsertion(): TileInsertionEvent? {
        synchronized(lock) {
            if (!bindingInProgress) {
                Log.d(
                    WIDGET_BIND_TAG,
                    "coordinator.consume -> null (no bind in progress; pending=$pendingInsertion)",
                    Throwable("consume() call site"),
                )
                return null
            }
            val event = pendingInsertion
            if (event == null) {
                Log.d(
                    WIDGET_BIND_TAG,
                    "coordinator.consume -> null (bind in progress but nothing completed)",
                    Throwable("consume() call site"),
                )
                return null
            }
            bindingInProgress = false
            pendingInsertion = null
            Log.d(WIDGET_BIND_TAG, "coordinator.consume -> $event", Throwable("consume() call site"))
            return event
        }
    }
}

/** Shared logcat tag for the whole widget bind → configure → insert chain. */
internal const val WIDGET_BIND_TAG = "WidgetBind"
