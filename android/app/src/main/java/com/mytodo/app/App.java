package com.mytodo.app;

import android.app.Application;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
    }
}
