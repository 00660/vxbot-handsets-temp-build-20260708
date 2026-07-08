package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class MorningGreetingReceiver extends BroadcastReceiver {
    public static final String ACTION_MORNING_GREETING = "com.vxbot.wechatbot.MORNING_GREETING_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        BotLog.i(context, "morning.tick", "收到早安问好 tick");
        MorningGreetingScheduler.schedule(context);
        if (BotRuntimeControls.isPaused(context)) {
            BotLog.i(context, "morning.skip.paused", "机器人已暂停，跳过早安问好 tick");
            return;
        }
        Intent service = new Intent(context, BotService.class);
        service.setAction(BotService.ACTION_MORNING_GREETING);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException e) {
            BotLog.e(context, "morning.start_service.fail",
                    "早安问好拉起服务失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
