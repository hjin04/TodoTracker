package com.mytodo.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

/** 30분마다 서버에서 할일을 받아 위젯·잠금화면 알림을 최신으로 유지 (앱을 안 열어도) */
public class SyncJob extends JobService {
    static final int ID = 7001;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(ID) != null) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(30 * 60 * 1000L)
            .setPersisted(true)
            .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                SharedPreferences w = TodoWidget.prefs(c);
                String url = w.getString("url", ""), token = w.getString("token", "");
                if (!url.isEmpty()) {
                    String base = url + (url.contains("?") ? "&" : "?") + "token=" + Uri.encode(token) + "&fn=";
                    JSONObject up = new JSONObject(TodoWidget.httpGet(base + "upcoming"));
                    if (up.optBoolean("ok")) Reminders.setUpcoming(c, up.getJSONArray("data").toString());
                    c.sendBroadcast(new android.content.Intent(c, TodoWidget.class).setAction(TodoWidget.ACT_REFRESH));
                }
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
