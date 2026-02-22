package com.safeher.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

public class SosWidget extends AppWidgetProvider {

    public static final String ACTION_WIDGET_SOS     = "com.safeher.app.WIDGET_SOS";
    public static final String ACTION_WIDGET_STOP    = "com.safeher.app.WIDGET_STOP";
    public static final String ACTION_WIDGET_REFRESH = "com.safeher.app.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateWidget(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String action = intent.getAction();

        if (ACTION_WIDGET_SOS.equals(action)) {
            // Start SosService and trigger SOS immediately
            Intent sosSvc = new Intent(ctx, SosService.class);
            sosSvc.setAction(SosService.ACTION_TRIGGER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(sosSvc);
            else
                ctx.startService(sosSvc);

        } else if (ACTION_WIDGET_STOP.equals(action)) {
            Intent svc = new Intent(ctx, SosService.class);
            svc.setAction(SosService.ACTION_STOP_ALARM);
            ctx.startService(svc);
        }

        // Refresh widget UI
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, SosWidget.class));
        for (int id : ids) updateWidget(ctx, mgr, id);
    }

    /** Called from SosService when alarm state changes. */
    public static void forceRefresh(Context ctx) {
        Intent i = new Intent(ctx, SosWidget.class);
        i.setAction(ACTION_WIDGET_REFRESH);
        ctx.sendBroadcast(i);
    }

    static void updateWidget(Context ctx, AppWidgetManager mgr, int widgetId) {
        boolean alarmOn = SosService.isAlarmActive;
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_sos);

        // Label
        views.setTextViewText(R.id.tvWidgetLabel,
                alarmOn ? "🔴 SOS ACTIVE" : "SaveSouls 🛡️");

        // Background
        views.setInt(R.id.widgetRoot, "setBackgroundResource",
                alarmOn ? R.drawable.widget_bg_alarm : R.drawable.widget_bg);

        // SOS button
        views.setTextViewText(R.id.widgetBtnSos, alarmOn ? "🆘 ACTIVE" : "🆘  SOS");
        views.setInt(R.id.widgetBtnSos, "setBackgroundResource",
                alarmOn ? R.drawable.widget_sos_btn_active : R.drawable.widget_sos_btn);

        Intent sosIntent = new Intent(ctx, SosWidget.class);
        sosIntent.setAction(ACTION_WIDGET_SOS);
        PendingIntent sosPi = PendingIntent.getBroadcast(ctx, 10, sosIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widgetBtnSos, sosPi);

        // Stop button
        Intent stopIntent = new Intent(ctx, SosWidget.class);
        stopIntent.setAction(ACTION_WIDGET_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(ctx, 11, stopIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widgetBtnStop, stopPi);

        mgr.updateAppWidget(widgetId, views);
    }
}
