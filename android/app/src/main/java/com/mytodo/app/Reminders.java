package com.mytodo.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 잠금화면 알림: 앞으로 8일 할일을 받아 알림 시각을 계산하고 예약 */
public class Reminders {
    static final String CHANNEL = "reminders";
    static final int MAX = 60;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reminders", Context.MODE_PRIVATE); }

    static String getPrefs(Context c) {
        SharedPreferences p = prefs(c);
        try {
            return new JSONObject().put("enabled", p.getBoolean("enabled", false)).put("offset", p.getInt("offset", -1))
                .put("due", p.getString("due", "eve")).put("morning", p.getBoolean("morning", false)).toString();
        } catch (Exception e) { return "{}"; }
    }

    static void setPrefs(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            prefs(c).edit().putBoolean("enabled", o.optBoolean("enabled")).putInt("offset", o.optInt("offset", -1))
                .putString("due", o.optString("due", "eve")).putBoolean("morning", o.optBoolean("morning")).apply();
        } catch (Exception ignored) { }
        reschedule(c);
        if (prefs(c).getBoolean("enabled", false)) SyncJob.schedule(c);
    }

    /** 앱/서버에서 받은 할일 목록 저장 후 재예약 */
    static void setUpcoming(Context c, String json) {
        prefs(c).edit().putString("upcoming", json).apply();
        reschedule(c);
    }

    static class Item { long at; String title, text, taskId; int kind; }

    static synchronized void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences p = prefs(c);
        // 이전 예약 모두 취소
        int prev = p.getInt("count", 0);
        for (int i = 0; i < prev; i++) am.cancel(pi(c, i, null));
        p.edit().putInt("count", 0).apply();
        if (!p.getBoolean("enabled", false)) return;

        List<Item> items = compute(c);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        int n = 0;
        for (Item it : items) {
            if (n >= MAX) break;
            PendingIntent op = pi(c, n, it);
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, op);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, op);
            n++;
        }
        p.edit().putInt("count", n).apply();
    }

    private static PendingIntent pi(Context c, int slot, Item it) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ReminderReceiver.ACT_FIRE);
        if (it != null) i.putExtra("title", it.title).putExtra("text", it.text).putExtra("task", it.taskId).putExtra("kind", it.kind);
        return PendingIntent.getBroadcast(c, 5000 + slot, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static long at(String date, int h, int m) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).parse(date);
            Calendar cal = Calendar.getInstance();
            cal.setTime(d);
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) { return 0; }
    }

    static String addDays(String date, int n) {
        long t = at(date, 12, 0) + n * 86400000L;
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date(t));
    }

    static List<Item> compute(Context c) {
        SharedPreferences p = prefs(c);
        List<Item> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        int defOffset = p.getInt("offset", -1);
        String dueMode = p.getString("due", "eve");
        JSONArray list;
        try { list = new JSONArray(p.getString("upcoming", "[]")); } catch (Exception e) { list = new JSONArray(); }
        java.util.Map<String, List<String>> byDay = new java.util.TreeMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject t = list.optJSONObject(i);
            if (t == null) continue;
            String title = t.optString("title"), date = t.optString("date"), time = t.optString("time"), due = t.optString("due"), cat = t.optString("cat");
            String al = t.optString("alarm");
            int off = al.isEmpty() ? (time.isEmpty() ? -1 : defOffset) : Integer.parseInt(al);
            if (off >= 0) {
                int h = 8, m = 0;
                if (!time.isEmpty()) { String[] hm = time.split(":"); h = Integer.parseInt(hm[0]); m = Integer.parseInt(hm[1]); }
                Item it = new Item();
                it.at = at(date, h, m) - off * 60000L;
                it.title = "⏰ " + title;
                it.text = (time.isEmpty() ? "" : time + " · ") + cat + (off > 0 ? " · " + offText(off) : "");
                it.taskId = t.optString("id"); it.kind = 1;
                if (it.at > now) out.add(it);
            }
            if (!due.isEmpty() && !"off".equals(dueMode)) {
                if ("eve".equals(dueMode) || "both".equals(dueMode)) {
                    Item it = new Item(); it.at = at(addDays(due, -1), 21, 0); it.title = "📌 내일 마감"; it.text = title + " · " + cat; it.taskId = t.optString("id"); it.kind = 2;
                    if (it.at > now) out.add(it);
                }
                if ("day".equals(dueMode) || "both".equals(dueMode)) {
                    Item it = new Item(); it.at = at(due, 9, 0); it.title = "📌 오늘 마감"; it.text = title + " · " + cat; it.taskId = t.optString("id"); it.kind = 2;
                    if (it.at > now) out.add(it);
                }
            }
            if (!byDay.containsKey(date)) byDay.put(date, new ArrayList<>());
            byDay.get(date).add(title);
        }
        if (p.getBoolean("morning", false)) {
            for (java.util.Map.Entry<String, List<String>> e : byDay.entrySet()) {
                Item it = new Item(); it.at = at(e.getKey(), 8, 0); it.kind = 3; it.taskId = "";
                it.title = "☀️ 오늘 할일 " + e.getValue().size() + "개";
                it.text = android.text.TextUtils.join(", ", e.getValue());
                if (it.at > now) out.add(it);
            }
        }
        out.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    static String offText(int m) {
        if (m >= 1440 && m % 1440 == 0) return (m / 1440) + "일 뒤";
        if (m >= 60 && m % 60 == 0) return (m / 60) + "시간 뒤";
        return m + "분 뒤";
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "할일 알림", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("할일 시간, 기한 임박, 아침 요약 알림");
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }
}
