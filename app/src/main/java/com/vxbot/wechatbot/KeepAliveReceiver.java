package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class KeepAliveReceiver extends BroadcastReceiver {
    public static final String ACTION_KEEP_ALIVE = "com.vxbot.wechatbot.KEEP_ALIVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        BotLog.i(context, "keepalive.tick", "收到守护 tick");
        KeepAliveScheduler.schedule(context);
        GarbageCleaner.runIfDue(context, "watchdog");
        KeepAliveScheduler.startBotService(context, "watchdog");
    }
}
