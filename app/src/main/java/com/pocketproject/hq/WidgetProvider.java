package com.pocketproject.hq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import org.json.JSONObject;

/** Zero-network local home widget: updated whenever the local dashboard saves. */
public final class WidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "pockethq.widget.snapshot.v1";
    private static final String FOCUS = "focus";
    private static final String NEXT = "next";
    private static final String STATS = "stats";

    public static void updateSnapshot(Context context, String input) throws Exception {
        JSONObject state = new JSONObject(input);
        String focus = bounded(state.optString("focusName", "No current focus"), 65);
        String next = bounded(state.optString("nextAction", "Open Pocket HQ to set the next action"), 210);
        int active = clamp(state.optInt("active", 0));
        int queued = clamp(state.optInt("queued", 0));
        int done = clamp(state.optInt("done", 0));
        String counts = active + " active  ·  " + queued + " queued  ·  " + done + " done";
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.edit().putString(FOCUS, focus).putString(NEXT, next)
                .putString(STATS, counts).commit()) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, WidgetProvider.class);
        for (int id : manager.getAppWidgetIds(component)) {
            updateOne(context, manager, id, prefs);
        }
    }
    private static String bounded(String value, int limit) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit - 1) + "…";
    }
    private static int clamp(int n) { return Math.max(0, Math.min(9999, n)); }
    private static void updateOne(Context context, AppWidgetManager manager, int id, SharedPreferences prefs) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_pocket_hq);
        v.setTextViewText(R.id.widget_focus, prefs.getString(FOCUS, "Open Pocket HQ"));
        v.setTextViewText(R.id.widget_next, prefs.getString(NEXT, "Open the app once to load your current focus."));
        v.setTextViewText(R.id.widget_stats, prefs.getString(STATS, "Offline · No credits"));
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent action = PendingIntent.getActivity(context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_container, action);
        manager.updateAppWidget(id, v);
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int id : ids) updateOne(context, manager, id, prefs);
    }
}
