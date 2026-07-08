package com.vxbot.wechatbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public final class KeepAliveScheduler {
    private static final int REQUEST_CODE = 6601;
    private static final long INTERVAL_MS = 60_000L;

    private KeepAliveScheduler() {
    }

    public static void schedule(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            BotLog.w(context, "keepalive.schedule.fail", "AlarmManager 不可用");
            return;
        }
        long at = SystemClock.elapsedRealtime() + INTERVAL_MS;
        PendingIntent pi = pendingIntent(context);
        if (Build.VERSION.SDK_INT >= 23) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } else {
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
        BotLog.i(context, "keepalive.schedule", "下一次守护 tick " + INTERVAL_MS + "ms");
    }

    public static void cancel(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.cancel(pendingIntent(context));
        }
        BotLog.i(context, "keepalive.cancel", "已取消守护 tick");
    }

    public static void startBotService(Context context, String reason) {
        if (BotRuntimeControls.isPaused(context)) {
            BotLog.i(context, "keepalive.start_service.skip_paused", "机器人已暂停，跳过拉起 BotService reason=" + reason);
            return;
        }
        Intent intent = new Intent(context, BotService.class);
        intent.setAction(BotService.ACTION_START);
        intent.putExtra("reason", reason == null ? "" : reason);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            BotLog.e(context, "keepalive.start_service.fail",
                    "拉起 BotService 失败 reason=" + reason + " error=" + e.getClass().getSimpleName() + " " + e.getMessage());
            return;
        }
        BotLog.i(context, "keepalive.start_service", "已拉起 BotService reason=" + reason);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(KeepAliveReceiver.ACTION_KEEP_ALIVE);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
