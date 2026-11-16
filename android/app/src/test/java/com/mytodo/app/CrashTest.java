package com.mytodo.app;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrashTest {
    static final String BAD = "[{\"id\":\"1\",\"title\":\"학원\",\"date\":\"2099-11-17\",\"time\":\"08:00\",\"alarm\":\"\",\"due\":\"\",\"cat\":\"공부\"},"
        + "{\"id\":\"2\",\"title\":\"레포트\",\"date\":\"2099-11-16\",\"time\":\"\",\"alarm\":\"30\",\"due\":\"2099-11-20\",\"cat\":\"과제\"},"
        + "{\"id\":\"3\",\"title\":null,\"date\":\"2099-11-18\",\"time\":null,\"alarm\":null,\"due\":null,\"cat\":null},"
        + "{\"id\":\"4\",\"title\":\"x\",\"date\":\"2099-11-18\",\"time\":\"8시\",\"alarm\":\"abc\",\"due\":\"nope\",\"cat\":\"x\"},"
        + "{\"id\":\"5\",\"title\":\"y\",\"date\":null,\"time\":\"25:99\",\"alarm\":10,\"due\":\"\",\"cat\":\"x\"},"
        + "null, 7, \"str\"]";

    @Test public void launchActivity() {
        Robolectric.buildActivity(MainActivity.class).setup().pause().resume();
    }

    @Test public void bridgeWithBadData() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        MainActivity.Bridge b = new MainActivity.Bridge(a, a);
        b.setConnection("https://script.google.com/macros/s/X/exec", "tok");
        b.updateWidget("{\"date\":\"2026-11-16\",\"total\":3,\"done\":1,\"items\":[{\"id\":\"a\",\"title\":\"x\",\"time\":\"\",\"color\":\"#5CE1E6\",\"cat\":\"과제\"}]}");
        b.updateWidget("garbage");
        b.widgetStatus(); b.takePendingDone(); b.getReminderPrefs();
        b.setReminderPrefs("{\"enabled\":true,\"offset\":10,\"due\":\"both\",\"morning\":true}");
        b.setUpcoming("not json");
        b.setReminderPrefs("garbage");
        b.setUpcoming(BAD);
        b.setTheme(true); b.setTheme(false); b.copy("x"); b.errorReport();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        int n = Reminders.compute(a).size();
        assertTrue("good items must still be scheduled: " + n, n >= 5);
        assertEquals(n, Reminders.prefs(a).getInt("count", 0));
    }

    @Test public void afterUpdateWithBadStoredData() {
        Context c = RuntimeEnvironment.getApplication();
        Reminders.prefs(c).edit().putBoolean("enabled", true).putString("upcoming", BAD).commit();
        new ReminderReceiver().onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        new ReminderReceiver().onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
        new ReminderReceiver().onReceive(c, new Intent(ReminderReceiver.ACT_FIRE).putExtra("title", "t").putExtra("text", "x").putExtra("task", "1"));
        new ReminderReceiver().onReceive(c, new Intent(ReminderReceiver.ACT_FIRE));
        new ReminderReceiver().onReceive(c, new Intent(ReminderReceiver.ACT_DONE).putExtra("task", "1").putExtra("nid", 3));
        ShadowAppWidgetManager sw = shadowOf(AppWidgetManager.getInstance(c));
        sw.createWidget(TodoWidget.class, R.layout.widget);
        new TodoWidget().onReceive(c, new Intent(TodoWidget.ACT_REFRESH));
        new TodoWidget().onReceive(c, new Intent(TodoWidget.ACT_DONE));
        TodoWidget.redraw(c);
        Robolectric.buildActivity(MainActivity.class).setup();
    }

    @Test public void crashIsRecordedAndShownNextLaunch() {
        Context c = RuntimeEnvironment.getApplication();
        Crash.install(c);
        Thread.getDefaultUncaughtExceptionHandler();
        Crash.prefs(c).edit().putString("fatal", "test trace").commit();
        Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals("", Crash.prefs(c).getString("fatal", ""));
        assertTrue(Crash.report(c).contains("test trace"));
    }
}
