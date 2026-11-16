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
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (home.getHost() != null && home.getHost().equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(this, this), "TodoAndroid");

        SyncJob.schedule(this);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(BuildConfig.APP_URL);
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
        public String takePendingDone() { return TodoWidget.takePending(ctx); }

        @JavascriptInterface
        public String widgetStatus() { return TodoWidget.status(ctx); }

        /* ───── 잠금화면 알림 ───── */
        @JavascriptInterface
        public void setUpcoming(String json) { Reminders.setUpcoming(ctx, json); }

        @JavascriptInterface
        public String getReminderPrefs() { return Reminders.getPrefs(ctx); }

        @JavascriptInterface
        public void setReminderPrefs(String json) { Reminders.setPrefs(ctx, json); }

        @JavascriptInterface
        public void requestNotifyPermission() {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.runOnUiThread(() -> act.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 11));
            }
        }

        /** 앱 테마(흰색/검정)에 맞춰 상단·하단 바 색 변경 */
        @JavascriptInterface
        public void setTheme(boolean dark) {
            act.runOnUiThread(() -> {
                android.view.Window w = act.getWindow();
                int bg = dark ? 0xFF000000 : 0xFFFFFFFF;
                w.setStatusBarColor(bg);
                w.setNavigationBarColor(bg);
                View d = w.getDecorView();
                int f = d.getSystemUiVisibility();
                int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                d.setSystemUiVisibility(dark ? (f & ~light) : (f | light));
                d.setBackgroundColor(bg);
            });
        }

        @JavascriptInterface
        public void updateWidget(String json) { TodoWidget.save(ctx, json); }

        @JavascriptInterface
        public void setConnection(String url, String token) { TodoWidget.setConnection(ctx, url, token); }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("todo", text));
            new android.os.Handler(ctx.getMainLooper()).post(() ->
                Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public boolean isApp() { return true; }
    }
}
