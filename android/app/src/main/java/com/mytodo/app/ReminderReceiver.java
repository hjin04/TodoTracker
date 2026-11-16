package com.mytodo.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 예약된 시각에 잠금화면 알림 표시 / 알림의 [완료] 버튼 처리 / 재부팅 후 재예약 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACT_FIRE = "com.mytodo.app.REMIND";
    static final String ACT_DONE = "com.mytodo.app.REMIND_DONE";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Reminders.reschedule(c);
            SyncJob.schedule(c);
            return;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (ACT_DONE.equals(a)) {
            String id = intent.getStringExtra("task");
            nm.cancel(intent.getIntExtra("nid", 0));
            if (id != null && !id.isEmpty()) {
                TodoWidget.addPending(c, id);
                c.sendBroadcast(new Intent(c, TodoWidget.class).setAction(TodoWidget.ACT_REFRESH));
            }
            return;
        }
        if (!ACT_FIRE.equals(a)) return;
        Reminders.ensureChannel(c);
        String title = intent.getStringExtra("title"), text = intent.getStringExtra("text"), task = intent.getStringExtra("task");
        int nid = (int) (System.currentTimeMillis() % 100000);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(c, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF3D6BF2)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (task != null && !task.isEmpty()) {
            Intent done = new Intent(c, ReminderReceiver.class).setAction(ACT_DONE).putExtra("task", task).putExtra("nid", nid);
            b.addAction(new Notification.Action.Builder(null, "완료",
                PendingIntent.getBroadcast(c, nid, done, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        try { nm.notify(nid, b.build()); } catch (SecurityException ignored) { }
    }
}
