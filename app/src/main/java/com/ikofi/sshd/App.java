package com.ikofi.sshd;

import android.app.Application;

import com.ikofi.util.theme.NightMode;

public class App
        extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        NightMode.init(this);
    }
}
