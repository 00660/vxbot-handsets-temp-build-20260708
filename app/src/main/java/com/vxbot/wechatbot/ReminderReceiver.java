package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "com.vxbot.wechatbot.REMINDER_FIRE_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent == null ? "" : intent.getStringExtra("id");
        ReminderManager.Reminder reminder = ReminderManager.consume(context, id == null ? "" : id);
        if (reminder == null) {
            BotLog.w(context, "reminder.fire.missing", "id=" + id);
            return;
        }
        Intent service = new Intent(context, BotService.class)
                .setAction(BotService.ACTION_REMINDER_FIRE)
                .putExtra("sessionName", reminder.session)
                .putExtra("senderName", reminder.sender)
                .putExtra("text", reminder.text);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
