package com.vxbot.wechatbot;

import android.content.Context;
import android.content.Intent;

public final class BotRuntimeControls {
    public static final String ACTION_CHANGED = "com.vxbot.wechatbot.RUNTIME_CONTROLS_CHANGED";
    private static final String PREF_BOT_PAUSED = "botPaused";

    private BotRuntimeControls() {
    }

    public static boolean isPaused(Context context) {
        return BotConfig.prefs(context).getBoolean(PREF_BOT_PAUSED, false);
    }

    public static boolean togglePaused(Context context) {
        boolean next = !isPaused(context);
        setPaused(context, next, "overlay");
        return next;
    }

    public static void setPaused(Context context, boolean paused, String reason) {
        BotConfig.prefs(context).edit().putBoolean(PREF_BOT_PAUSED, paused).apply();
        BotLog.i(context, paused ? "runtime.pause" : "runtime.resume",
                "机器人" + (paused ? "已暂停" : "已继续") + " reason=" + reason);
        context.sendBroadcast(new Intent(ACTION_CHANGED));
    }
}
