package com.vxbot.wechatbot;

import android.app.Application;

public final class BotApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        HidBleClient.initialize(this);
    }
}
