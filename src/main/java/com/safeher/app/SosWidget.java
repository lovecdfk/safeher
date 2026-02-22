package com.safeher.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

public class SosWidget extends AppWidgetProvider {

    private static final String ACTION_WIDGET_SOS = "com.safeher.app.WIDGET_SOS";
    private static final String ACTION_STOP       = "com.safeher.app.STOP_ALARM";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateWidget(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_WIDGET_SOS.equals(intent.getAction())) {
            // Start service and trigger SOS
            Intent svc = new Intent(ctx, SosService.class);
            svc.setAction(SosService.ACTION_TRIGGER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } else if (ACTION_STOP.equals(intent.getAction())) {
            Intent svc = new Intent(ctx, SosService.class);
            svc.setAction(SosService.ACTION_STOP_ALARM);
            ctx.startService(svc);
        }
    }

    static void updateWidget(Context ctx, AppWidgetManager mgr, int widgetId) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_sos);

        // SOS button tap → trigger SOS
        Intent sosIntent = new Intent(ctx, SosWidget.class);
        sosIntent.setAction(ACTION_WIDGET_SOS);
        PendingIntent sosPi = PendingIntent.getBroadcast(ctx, 0, sosIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widgetBtnSos, sosPi);

        // Stop button tap → stop alarm
        Intent stopIntent = new Intent(ctx, SosWidget.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(ctx, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widgetBtnStop, stopPi);

        mgr.updateAppWidget(widgetId, views);
    }
}
