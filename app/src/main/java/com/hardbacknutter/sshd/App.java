package com.hardbacknutter.sshd;

import android.app.Application;

import com.hardbacknutter.util.theme.NightMode;

public class App
        extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        NightMode.init(this);
    }
}
