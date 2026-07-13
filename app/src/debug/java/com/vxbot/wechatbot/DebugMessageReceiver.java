package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class DebugMessageReceiver extends BroadcastReceiver {
    public static final String ACTION_DEBUG_INJECT_MESSAGE =
            "com.vxbot.wechatbot.DEBUG_INJECT_MESSAGE";

    @Override
    public void onReceive(Context context, Intent source) {
        if (context == null || source == null || !ACTION_DEBUG_INJECT_MESSAGE.equals(source.getAction())) {
            return;
        }
        String sessionName = clean(source.getStringExtra("sessionName"));
        String senderName = clean(source.getStringExtra("senderName"));
        String text = clean(source.getStringExtra("text"));
        if (sessionName.isEmpty() || text.isEmpty()) {
            BotLog.e(context, "debug.message.reject", "调试消息缺少 sessionName 或 text");
            return;
        }
        if (senderName.isEmpty()) {
            senderName = "ADB调试";
        }
        if (source.getBooleanExtra("mention", true)) {
            BotConfig config = BotConfig.load(context);
            String botName = config.primaryBotName();
            if (!config.isBotMentioned(text)) {
                text = "@" + botName + " " + text;
            }
        }
        long now = System.currentTimeMillis();
        Intent target = new Intent(context, BotService.class)
                .setAction(BotService.ACTION_HANDLE_NOTIFICATION)
                .putExtra("sessionName", sessionName)
                .putExtra("senderName", senderName)
                .putExtra("text", text)
                .putExtra("rawTitle", sessionName)
                .putExtra("rawContent", senderName + "：" + text)
                .putExtra("notificationKey", "debug-adb-" + now)
                .putExtra("postTime", now);
        BotLog.i(context, "debug.message.inject",
                "注入调试群消息 group=" + sessionName + " sender=" + senderName + " text=" + text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(target);
        } else {
            context.startService(target);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
