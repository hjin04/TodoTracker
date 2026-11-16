package com.mytodo.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/** 앱 화면 = GitHub Pages에 올라간 웹앱을 그대로 띄우고, 위젯과 데이터를 주고받음 */
public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.bg));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100); // 휴대폰 글자 크기 설정과 상관없이 화면 배치 유지

        final Uri home = Uri.parse(BuildConfig.APP_URL);
        web.setWebViewClient(new WebViewClient() {
            /** 화면(웹) 프로세스가 죽어도 앱 전체가 꺼지지 않게 다시 그림 */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Crash.note(MainActivity.this, "화면 프로세스 종료 (crash=" + detail.didCrash() + ")", new Exception("render process gone"));
                recreate();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (home.getHost() != null && home.getHost().equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(this, this), "TodoAndroid");

        try { SyncJob.schedule(this); } catch (Throwable e) { Crash.note(this, "동기화 예약", e); }
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(BuildConfig.APP_URL);

        // 지난번에 앱이 갑자기 꺼졌다면 오류 내용을 보여주고 복사할 수 있게
        final String fatal = Crash.takeFatal(this);
        if (!fatal.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("지난번 앱이 갑자기 종료됐어요")
                .setMessage("아래 내용을 복사해 개발자(Claude)에게 보내 주세요.\n\n" + fatal)
                .setPositiveButton("복사", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("오류", fatal));
                    Toast.makeText(this, "복사했어요", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("닫기", null)
                .show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // 위젯에서 체크한 내용 등을 바로 반영
        web.evaluateJavascript("window.onAppResume ? onAppResume() : (window.refresh && refresh())", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.handleBack && handleBack()) ? '1' : '0'", v -> {
            if (v != null && v.contains("1")) return;
            if (web.canGoBack()) web.goBack();
            else MainActivity.super.onBackPressed();
        });
    }

    /** 웹앱(JS)에서 window.TodoAndroid.xxx() 로 호출 */
    static class Bridge {
        private final Context ctx;
        private final Activity act;
        Bridge(Context c, Activity a) { ctx = c.getApplicationContext(); act = a; }

        /** 위젯에서 체크했지만 아직 앱에 전달 안 된 할일 → 앱이 받아서 저장 */
        @JavascriptInterface
        public String takePendingDone() { try { return TodoWidget.takePending(ctx); } catch (Throwable e) { Crash.note(ctx, "위젯 체크 전달", e); return "[]"; } }

        @JavascriptInterface
        public String widgetStatus() { try { return TodoWidget.status(ctx); } catch (Throwable e) { return "확인 실패"; } }

        /** 설정 화면용: 최근 오류 기록 */
        @JavascriptInterface
        public String errorReport() { try { return Crash.report(ctx); } catch (Throwable e) { return ""; } }

        @JavascriptInterface
        public void clearErrors() { Crash.clear(ctx); }

        /* ───── 잠금화면 알림 ───── */
        @JavascriptInterface
        public void setUpcoming(String json) { try { Reminders.setUpcoming(ctx, json); } catch (Throwable e) { Crash.note(ctx, "할일 목록 받기", e); } }

        @JavascriptInterface
        public String getReminderPrefs() { try { return Reminders.getPrefs(ctx); } catch (Throwable e) { return "{}"; } }

        @JavascriptInterface
        public void setReminderPrefs(String json) { try { Reminders.setPrefs(ctx, json); } catch (Throwable e) { Crash.note(ctx, "알림 설정", e); } }

        @JavascriptInterface
        public void requestNotifyPermission() {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.runOnUiThread(() -> { try { act.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 11); } catch (Throwable e) { Crash.note(ctx, "알림 권한", e); } });
            }
        }

        /** 앱 테마(흰색/검정)에 맞춰 상단·하단 바 색 변경 */
        @JavascriptInterface
        public void setTheme(boolean dark) {
            act.runOnUiThread(() -> { try {
                android.view.Window w = act.getWindow();
                int bg = dark ? 0xFF000000 : 0xFFFFFFFF;
                w.setStatusBarColor(bg);
                w.setNavigationBarColor(bg);
                View d = w.getDecorView();
                int f = d.getSystemUiVisibility();
                int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                d.setSystemUiVisibility(dark ? (f & ~light) : (f | light));
                d.setBackgroundColor(bg);
            } catch (Throwable e) { Crash.note(ctx, "테마", e); } });
        }

        @JavascriptInterface
        public void updateWidget(String json) { try { TodoWidget.save(ctx, json); } catch (Throwable e) { Crash.note(ctx, "위젯 갱신", e); } }

        @JavascriptInterface
        public void setConnection(String url, String token) { try { TodoWidget.setConnection(ctx, url, token); } catch (Throwable e) { Crash.note(ctx, "연결 저장", e); } }

        @JavascriptInterface
        public void copy(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("todo", text));
                new android.os.Handler(ctx.getMainLooper()).post(() ->
                    Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show());
            } catch (Throwable e) { Crash.note(ctx, "복사", e); }
        }

        @JavascriptInterface
        public boolean isApp() { return true; }
    }
}
