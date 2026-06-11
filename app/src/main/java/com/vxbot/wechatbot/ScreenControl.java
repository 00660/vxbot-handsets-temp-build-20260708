package com.vxbot.wechatbot;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

public final class ScreenControl {
    private ScreenControl() {
    }

    public static void syncForConfig(Context context, BotConfig config, String reason) {
        if (config == null || !config.enableNoRootKeepAwake || !"shizuku".equals(config.activeMode)) {
            disableKeepAwake(context, reason + ":not-needed");
            return;
        }
        enableDimKeepAwake(context, reason);
    }

    public static void enableDimKeepAwake(Context context, String reason) {
        try {
            Intent intent = new Intent(context, KeepAwakeActivity.class);
            intent.setAction(KeepAwakeActivity.ACTION_KEEP_AWAKE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(intent);
            BotLog.i(context, "screen.dim.enable", "已开启低亮防熄屏 reason=" + reason);
        } catch (Exception e) {
            BotLog.e(context, "screen.dim.enable.fail", "开启低亮防熄屏失败: " + e.getMessage());
        }
    }

    public static void disableKeepAwake(Context context, String reason) {
        try {
            KeepAwakeActivity.finishRunning();
            BotLog.i(context, "screen.dim.disable", "已关闭低亮防熄屏 reason=" + reason);
        } catch (Exception e) {
            BotLog.w(context, "screen.dim.disable.fail", "关闭低亮防熄屏失败: " + e.getMessage());
        }
    }

    public static boolean trySetSystemBrightness(Context context, int brightness, String reason) {
        int value = Math.max(1, Math.min(255, brightness));
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context)) {
                BotLog.w(context, "screen.brightness.skip", "没有 WRITE_SETTINGS，跳过系统亮度写入 reason=" + reason);
                return false;
            }
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            BotLog.i(context, "screen.brightness.set", "已设置系统亮度 value=" + value + " reason=" + reason);
            return true;
        } catch (Exception e) {
            BotLog.w(context, "screen.brightness.fail", "设置系统亮度失败: " + e.getMessage());
            return false;
        }
    }
}
