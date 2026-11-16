package com.mytodo.app;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrashTest {
    @Test public void launchActivity() {
        Robolectric.buildActivity(MainActivity.class).setup().pause().resume();
    }

    @Test public void bridgeAndReminders() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        MainActivity.Bridge b = new MainActivity.Bridge(a, a);
        b.setConnection("https://script.google.com/macros/s/X/exec", "tok");
        b.updateWidget("{\"date\":\"2026-11-16\",\"total\":3,\"done\":1,\"items\":[{\"id\":\"a\",\"title\":\"x\",\"time\":\"\",\"color\":\"#5CE1E6\",\"cat\":\"과제\"}]}");
        b.widgetStatus(); b.takePendingDone(); b.getReminderPrefs();
        b.setReminderPrefs("{\"enabled\":true,\"offset\":10,\"due\":\"both\",\"morning\":true}");
        b.setUpcoming("[{\"id\":\"1\",\"title\":\"학원\",\"date\":\"2026-11-17\",\"time\":\"08:00\",\"alarm\":\"\",\"due\":\"\",\"cat\":\"공부\"},"
            + "{\"id\":\"2\",\"title\":\"레포트\",\"date\":\"2026-11-16\",\"time\":\"\",\"alarm\":\"30\",\"due\":\"2026-11-20\",\"cat\":\"과제\"},"
            + "{\"id\":\"3\",\"title\":\"n\",\"date\":\"2026-11-18\",\"time\":null,\"alarm\":null,\"due\":null,\"cat\":\"x\"}]");
        b.setTheme(true); b.setTheme(false);
        shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @Test public void receivers() {
        Context c = RuntimeEnvironment.getApplication();
        new ReminderReceiver().onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        new ReminderReceiver().onReceive(c, new Intent(ReminderReceiver.ACT_FIRE).putExtra("title", "t").putExtra("text", "x").putExtra("task", "1"));
        new ReminderReceiver().onReceive(c, new Intent(ReminderReceiver.ACT_DONE).putExtra("task", "1").putExtra("nid", 3));
        ShadowAppWidgetManager sw = shadowOf(AppWidgetManager.getInstance(c));
        sw.createWidget(TodoWidget.class, R.layout.widget);
        new TodoWidget().onReceive(c, new Intent(TodoWidget.ACT_REFRESH));
        TodoWidget.redraw(c);
    }
}
