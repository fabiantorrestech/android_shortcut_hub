package com.fabiantorrestech.androidshortcuthub

import android.appwidget.AppWidgetHostView
import android.content.Context

class ZeroPaddingWidgetHostView(
    context: Context,
) : AppWidgetHostView(context) {
    init {
        super.setPadding(0, 0, 0, 0)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(0, 0, 0, 0)
    }
}
