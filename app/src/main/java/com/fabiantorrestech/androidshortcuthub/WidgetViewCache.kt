package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.ViewGroup

object WidgetViewCache {
    private val cachedViews = HashMap<Int, AppWidgetHostView>()

    fun getOrCreate(
        context: Context,
        appWidgetId: Int,
        providerInfo: AppWidgetProviderInfo,
    ): AppWidgetHostView {
        val view = cachedViews.getOrPut(appWidgetId) {
            val providerContext = createProviderContext(context, providerInfo)
            ShortcutHubWidgetHost.getInstance(context).createView(
                providerContext,
                appWidgetId,
                providerInfo,
            )
        }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun remove(appWidgetId: Int) {
        cachedViews.remove(appWidgetId)
    }

    /**
     * Drops every cached view, for [HubSwitch]'s reset. Main thread only, and only once no hub is on
     * screen: a view still attached somewhere would stop updating, because the next [getOrCreate]
     * for its id registers a replacement with the host.
     *
     * The callbacks are cleared on the way out because each one captures the overlay that last
     * showed the view, which would otherwise stay reachable for as long as the view does.
     */
    fun clear() {
        cachedViews.values.forEach { view ->
            (view as? ZeroPaddingWidgetHostView)?.onWidgetActivated = null
            view.setOnLongClickListener(null)
            (view.parent as? ViewGroup)?.removeView(view)
        }
        cachedViews.clear()
    }

    private fun createProviderContext(
        context: Context,
        providerInfo: AppWidgetProviderInfo,
    ): Context {
        return runCatching<Context> {
            context.createPackageContext(
                providerInfo.provider.packageName,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE,
            )
        }.getOrDefault(context)
    }
}
