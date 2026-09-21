package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class ShortcutHubWidgetHost private constructor(
    context: Context,
) : AppWidgetHost(context.applicationContext, HOST_ID) {
    private val listeners = linkedSetOf<Any>()

    fun startListening(listener: Any) {
        val wasEmpty = listeners.isEmpty()
        listeners += listener
        if (wasEmpty) {
            runCatching { super.startListening() }
        }
    }

    fun stopListening(listener: Any) {
        listeners -= listener
        if (listeners.isEmpty()) {
            runCatching { super.stopListening() }
        }
    }

    /**
     * [HubSwitch]'s reset. Every hub host has been dismissed by the time this runs, so each should
     * already have stopped listening; this makes certain, dropping any listener a failed show
     * leaked, and forgets the views so the next open builds fresh ones. A host that is still
     * finishing and stops listening afterwards is harmless - removing an absent listener is a no-op.
     */
    fun resetListening() {
        listeners.clear()
        runCatching { super.stopListening() }
        runCatching { clearViews() }
    }

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView {
        return ZeroPaddingWidgetHostView(context)
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        WidgetViewCache.remove(appWidgetId)
        super.deleteAppWidgetId(appWidgetId)
    }

    companion object {
        private const val HOST_ID = 1

        @Volatile
        private var instance: ShortcutHubWidgetHost? = null

        fun getInstance(context: Context): ShortcutHubWidgetHost {
            return instance ?: synchronized(this) {
                instance ?: ShortcutHubWidgetHost(context).also { instance = it }
            }
        }
    }
}
