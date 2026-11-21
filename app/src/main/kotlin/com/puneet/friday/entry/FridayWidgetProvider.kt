package com.puneet.friday.entry

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.puneet.friday.R

class FridayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.friday_widget).apply {
                setOnClickPendingIntent(
                    R.id.friday_widget_root,
                    FridayEntryPoints.listenPendingIntent(context, WIDGET_REQUEST_BASE + widgetId),
                )
            }
            manager.updateAppWidget(widgetId, views)
        }
    }

    private companion object {
        const val WIDGET_REQUEST_BASE = 1_000
    }
}
