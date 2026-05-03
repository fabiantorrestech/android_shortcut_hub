package com.fabiantorrestech.androidshortcuthub

internal object WidgetBindingCoordinator {
    private val lock = Any()

    private var bindingInProgress: Boolean = false
    private var pendingInsertion: TileInsertionEvent? = null

    fun startBinding() {
        synchronized(lock) {
            bindingInProgress = true
            pendingInsertion = null
        }
    }

    fun completeInsertion(event: TileInsertionEvent) {
        synchronized(lock) {
            pendingInsertion = event
        }
    }

    fun clear() {
        synchronized(lock) {
            bindingInProgress = false
            pendingInsertion = null
        }
    }

    fun consumeCompletedInsertion(): TileInsertionEvent? {
        synchronized(lock) {
            if (!bindingInProgress) return null
            val event = pendingInsertion ?: return null
            bindingInProgress = false
            pendingInsertion = null
            return event
        }
    }
}
