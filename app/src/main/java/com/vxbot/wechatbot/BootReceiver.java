package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        BotLog.i(context, "boot.receiver", "收到启动广播 action=" + action);
        KeepAliveScheduler.schedule(context);
        MorningGreetingScheduler.schedule(context);
        GarbageCleaner.runIfDue(context, "boot");
        KeepAliveScheduler.startBotService(context, "boot:" + action);
    }
}
