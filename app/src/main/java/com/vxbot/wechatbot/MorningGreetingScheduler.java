package com.vxbot.wechatbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

public final class MorningGreetingScheduler {
    private static final int REQUEST_CODE = 6611;
    private static final int HOUR = 8;
    private static final int MINUTE = 30;

    private MorningGreetingScheduler() {
    }

    public static void schedule(Context context) {
        if (context == null) {
            return;
        }
        BotConfig config = BotConfig.load(context);
        if (!config.enableMorningGreeting) {
            cancel(context);
            return;
        }
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            BotLog.w(context, "morning.schedule.fail", "AlarmManager 不可用");
            return;
        }
        long triggerAt = nextMorningAt();
        PendingIntent pi = pendingIntent(context);
        if (Build.VERSION.SDK_INT >= 23) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        BotLog.i(context, "morning.schedule", "下一次早安问好 "
                + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(new java.util.Date(triggerAt)));
    }

    public static void cancel(Context context) {
        if (context == null) {
            return;
        }
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.cancel(pendingIntent(context));
        }
        BotLog.i(context, "morning.cancel", "已取消早安问好定时");
    }

    private static long nextMorningAt() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, HOUR);
        calendar.set(Calendar.MINUTE, MINUTE);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, MorningGreetingReceiver.class);
        intent.setAction(MorningGreetingReceiver.ACTION_MORNING_GREETING);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
