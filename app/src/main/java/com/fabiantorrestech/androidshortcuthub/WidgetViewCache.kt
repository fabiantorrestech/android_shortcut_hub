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
