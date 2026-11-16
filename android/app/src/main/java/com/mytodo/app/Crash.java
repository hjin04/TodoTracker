package com.mytodo.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 오류 기록: 앱이 꺼지지 않게 잡아 둔 오류와, 갑자기 꺼진 경우의 오류를 저장해 다음 실행 때 보여줌 */
public class Crash {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("crash", Context.MODE_PRIVATE); }

    static String trace(Throwable e) {
        StringWriter w = new StringWriter();
        e.printStackTrace(new PrintWriter(w));
        String t = w.toString();
        return t.length() > 6000 ? t.substring(0, 6000) : t;
    }

    static String header(String where) {
        return "[" + new SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(new Date()) + "] " + where + " · 버전 "
            + BuildConfig.VERSION_NAME + " · Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MODEL + "\n";
    }

    /** 잡아서 처리한 오류 (앱은 계속 동작) */
    static void note(Context c, String where, Throwable e) {
        try { prefs(c).edit().putString("handled", header(where) + trace(e)).apply(); } catch (Throwable ignored) { }
    }

    /** 앱 전체에서 처리 못 한 오류 → 저장 후 원래대로 종료 */
    static void install(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Handler) return;
        Thread.setDefaultUncaughtExceptionHandler(new Handler(app, prev));
    }

    static class Handler implements Thread.UncaughtExceptionHandler {
        final Context app; final Thread.UncaughtExceptionHandler prev;
        Handler(Context a, Thread.UncaughtExceptionHandler p) { app = a; prev = p; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try { prefs(app).edit().putString("fatal", header("앱 종료 (" + t.getName() + ")") + trace(e)).commit(); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    /** 지난번 갑자기 꺼진 기록을 한 번 꺼내고 지움 */
    static String takeFatal(Context c) {
        String v = prefs(c).getString("fatal", "");
        if (!v.isEmpty()) prefs(c).edit().remove("fatal").putString("lastFatal", v).apply();
        return v;
    }

    static String report(Context c) {
        SharedPreferences p = prefs(c);
        String a = p.getString("lastFatal", ""), b = p.getString("handled", "");
        return (a.isEmpty() ? "" : "■ 마지막 앱 종료 오류\n" + a + "\n") + (b.isEmpty() ? "" : "■ 마지막으로 잡은 오류\n" + b);
    }

    static void clear(Context c) { prefs(c).edit().clear().apply(); }
}
