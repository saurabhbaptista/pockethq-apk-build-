package com.pocketproject.hq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "pockethq.widget.snapshot.v2";
    private static final String SNAPSHOT = "latest";
    private static final int[] PROJECT_ROWS = {
        R.id.radar_row_1, R.id.radar_row_2, R.id.radar_row_3, R.id.radar_row_4
    };
    private static final int[] PROJECT_NAMES = {
        R.id.radar_name_1, R.id.radar_name_2, R.id.radar_name_3, R.id.radar_name_4
    };
    private static final int[] PROJECT_ACTIONS = {
        R.id.radar_next_1, R.id.radar_next_2, R.id.radar_next_3, R.id.radar_next_4
    };

    public static void updateSnapshot(Context context, String json) throws Exception {
        if (json == null || json.length() > 5000) return;
        JSONObject s = new JSONObject(json);
        String safe = s.toString();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.edit().putString(SNAPSHOT, safe).commit()) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, WidgetProvider.class))) {
            updateOne(context, manager, id, p);
        }
    }

    private static String limit(String raw, int max) {
        if (raw == null) return "";
        String result = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return result.length() <= max ? result : result.substring(0, max - 1) + "\u2026";
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(9999, value));
    }

    private static void updateOne(Context c, AppWidgetManager manager, int id, SharedPreferences p) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_pocket_hq);
        JSONObject s;
        try {
            s = new JSONObject(p.getString(SNAPSHOT, "{}"));
        } catch (Exception ex) {
            s = new JSONObject();
        }
        String focus = limit(s.optString("focusName", "Open Pocket HQ"), 60);
        String next = limit(s.optString("nextAction", "Open the app to set your next action."), 220);
        String status = limit(s.optString("focusStatus", "LOCAL"), 24);
        v.setTextViewText(R.id.widget_focus, focus);
        v.setTextViewText(R.id.widget_next, next);
        v.setTextViewText(R.id.widget_focus_status, status.toUpperCase(java.util.Locale.ROOT));
        v.setTextViewText(R.id.widget_active, String.valueOf(clamp(s.optInt("active", 0))));
        v.setTextViewText(R.id.widget_queued, String.valueOf(clamp(s.optInt("queued", 0))));
        v.setTextViewText(R.id.widget_done, String.valueOf(clamp(s.optInt("done", 0))));
        String doing = s.optString("doingBuild", "");
        String queued = s.optString("queuedBuild", "");
        String buildTitle = doing.length() > 0 ? doing
            : (queued.length() > 0 ? queued : "No build selected");
        v.setTextViewText(R.id.widget_build, limit(buildTitle, 85));
        v.setTextViewText(R.id.widget_build_status,
            doing.length() > 0 ? "IN PROGRESS" : "UP NEXT");
        v.setTextViewText(R.id.widget_upcoming,
            limit(s.optString("followingBuild", "Nothing else in the queue"), 85));
        v.setTextViewText(R.id.widget_recent,
            limit(s.optString("recentActivity", "No recent activity"), 125));
        String updated = limit(s.optString("lastUpdated", "Open app to refresh"), 55);
        v.setTextViewText(R.id.widget_updated, "LOCAL SNAPSHOT  \u00B7  " + updated);

        Bundle opts = manager.getAppWidgetOptions(id);
        int height = Math.max(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0));
        boolean medium = height >= 225;
        boolean large = height >= 345;
        boolean veryLarge = height >= 480;
        v.setViewVisibility(R.id.widget_build_section, medium ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.widget_upcoming_section, large ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.widget_radar_section, large ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.widget_recent_section, veryLarge ? View.VISIBLE : View.GONE);
        JSONArray others = s.optJSONArray("otherProjects");
        int visibleCount = others == null ? 0 : Math.min(veryLarge ? 4 : 2, others.length());
        for (int index = 0; index < 4; index++) {
            if (index < visibleCount) {
                JSONObject item = others.optJSONObject(index);
                if (item != null) {
                    String name = limit(item.optString("name", "Project"), 34);
                    String itemStatus = limit(item.optString("status", ""), 14);
                    String action = limit(item.optString("next", "No action defined"), 90);
                    v.setTextViewText(PROJECT_NAMES[index], name + "  \u00B7  " + itemStatus);
                    v.setTextViewText(PROJECT_ACTIONS[index], action);
                    v.setViewVisibility(PROJECT_ROWS[index], View.VISIBLE);
                } else {
                    v.setViewVisibility(PROJECT_ROWS[index], View.GONE);
                }
            } else {
                v.setViewVisibility(PROJECT_ROWS[index], View.GONE);
            }
        }
        Intent intent = new Intent(c, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent click = PendingIntent.getActivity(c, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_container, click);
        manager.updateAppWidget(id, v);
    }

    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int id : ids) updateOne(c, manager, id, p);
    }
    @Override public void onAppWidgetOptionsChanged(Context c,
            AppWidgetManager manager, int id, Bundle options) {
        super.onAppWidgetOptionsChanged(c, manager, id, options);
        updateOne(c, manager, id, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }
}
