package com.mytodo.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 홈 화면 위젯: 오늘 남은 할일 표시 + 체크하면 완료 처리 */
public class TodoWidget extends AppWidgetProvider {
    static final int ROWS = 8;
    static final String ACT_DONE = "com.mytodo.app.DONE";
    static final String ACT_REFRESH = "com.mytodo.app.REFRESH";
    static final String[] WD = {"일", "월", "화", "수", "목", "금", "토"};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("widget", Context.MODE_PRIVATE); }

    /* ───── 앱에서 호출 ───── */
    static void save(Context c, String json) {
        prefs(c).edit().putString("data", json).apply();
        redraw(c);
    }

    static void setConnection(Context c, String url, String token) {
        prefs(c).edit().putString("url", url).putString("token", token).apply();
        if (url == null || url.isEmpty()) prefs(c).edit().remove("data").apply();
        redraw(c);
    }

    /* ───── 시스템 콜백 ───── */
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        redraw(c);
        fetchAsync(c, "widget", null);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        String a = intent.getAction();
        if (ACT_DONE.equals(a)) {
            String id = intent.getStringExtra("id");
            removeLocally(c, id);          // 바로 화면에서 지우고
            fetchAsync(c, "complete", id); // 서버에 완료 저장
        } else if (ACT_REFRESH.equals(a)) {
            fetchAsync(c, "widget", null);
        }
    }

    /* ───── 서버 통신 ───── */
    private void fetchAsync(Context c, String fn, String id) {
        SharedPreferences p = prefs(c);
        String url = p.getString("url", ""), token = p.getString("token", "");
        if (url.isEmpty()) return;
        final PendingResult pr = goAsync();
        final Context ctx = c.getApplicationContext();
        new Thread(() -> {
            try {
                StringBuilder q = new StringBuilder(url)
                    .append(url.contains("?") ? "&" : "?")
                    .append("fn=").append(fn)
                    .append("&token=").append(Uri.encode(token));
                if (id != null) q.append("&id=").append(Uri.encode(id));
                JSONObject res = new JSONObject(httpGet(q.toString()));
                if (res.optBoolean("ok")) save(ctx, res.getJSONObject("data").toString());
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }

    static String httpGet(String u) throws Exception {
        HttpURLConnection con = null;
        for (int i = 0; i < 5; i++) { // Apps Script는 다른 주소로 리다이렉트함
            con = (HttpURLConnection) new URL(u).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) { u = con.getHeaderField("Location"); con.disconnect(); continue; }
            break;
        }
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private static void removeLocally(Context c, String id) {
        try {
            JSONObject d = new JSONObject(prefs(c).getString("data", "{}"));
            JSONArray items = d.optJSONArray("items"), keep = new JSONArray();
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if (!it.optString("id").equals(id)) keep.put(it);
            }
            if (keep.length() < items.length()) d.put("done", d.optInt("done") + 1);
            d.put("items", keep);
            save(c, d.toString());
        } catch (Exception ignored) { }
    }

    /* ───── 그리기 ───── */
    static void redraw(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, TodoWidget.class));
        if (ids.length == 0) return;
        RemoteViews v = build(c);
        for (int wid : ids) m.updateAppWidget(wid, v);
    }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date()); }

    static RemoteViews build(Context c) {
        String pkg = c.getPackageName();
        RemoteViews v = new RemoteViews(pkg, R.layout.widget);
        SharedPreferences p = prefs(c);

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.root, openPi);
        v.setOnClickPendingIntent(R.id.header, openPi);
        Intent refresh = new Intent(c, TodoWidget.class).setAction(ACT_REFRESH);
        v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(c, 1, refresh, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Calendar cal = Calendar.getInstance();
        v.setTextViewText(R.id.date, (cal.get(Calendar.MONTH) + 1) + "월 " + cal.get(Calendar.DAY_OF_MONTH) + "일 " + WD[cal.get(Calendar.DAY_OF_WEEK) - 1] + "요일");

        JSONArray items = new JSONArray();
        String msg = null;
        int total = 0, done = 0;
        if (p.getString("url", "").isEmpty()) {
            msg = "앱을 열어 서버를 연결해 주세요";
        } else {
            try {
                JSONObject d = new JSONObject(p.getString("data", "{}"));
                if (!today().equals(d.optString("date"))) {
                    msg = "↻ 를 눌러 새로고침";
                } else {
                    items = d.optJSONArray("items");
                    if (items == null) items = new JSONArray();
                    total = d.optInt("total");
                    done = d.optInt("done");
                    if (items.length() == 0) msg = total > 0 ? "오늘 할일 완료! 🎉" : "오늘 할일이 없어요";
                }
            } catch (Exception e) { msg = "↻ 를 눌러 새로고침"; }
        }
        v.setTextViewText(R.id.count, total > 0 ? (total - done) + "개 남음" : "");

        for (int i = 0; i < ROWS; i++) {
            int row = c.getResources().getIdentifier("row" + i, "id", pkg);
            if (i >= items.length()) { v.setViewVisibility(row, View.GONE); continue; }
            JSONObject it = items.optJSONObject(i);
            int box = c.getResources().getIdentifier("box" + i, "id", pkg);
            int title = c.getResources().getIdentifier("title" + i, "id", pkg);
            int time = c.getResources().getIdentifier("time" + i, "id", pkg);
            v.setViewVisibility(row, View.VISIBLE);
            v.setTextViewText(title, it.optString("title"));
            String t = it.optString("time");
            v.setTextViewText(time, t);
            v.setViewVisibility(time, t.isEmpty() ? View.GONE : View.VISIBLE);
            int color;
            try { color = Color.parseColor(it.optString("color", "#3D6BF2")); } catch (Exception e) { color = 0xFF3D6BF2; }
            v.setInt(box, "setColorFilter", color);
            Intent di = new Intent(c, TodoWidget.class).setAction(ACT_DONE).putExtra("id", it.optString("id"));
            v.setOnClickPendingIntent(box, PendingIntent.getBroadcast(c, 100 + i, di, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            v.setOnClickPendingIntent(row, openPi);
        }
        int more = items.length() - ROWS;
        v.setViewVisibility(R.id.more, more > 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.more, "+" + more + "개 더");
        v.setViewVisibility(R.id.msg, msg != null ? View.VISIBLE : View.GONE);
        if (msg != null) v.setTextViewText(R.id.msg, msg);
        return v;
    }
}
