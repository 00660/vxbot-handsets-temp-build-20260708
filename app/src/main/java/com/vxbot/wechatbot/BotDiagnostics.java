package com.vxbot.wechatbot;

import android.content.Context;
import android.provider.Settings;

import java.net.HttpURLConnection;
import java.net.URL;

public final class BotDiagnostics {
    private BotDiagnostics() {
    }

    public static String report(Context context, BotConfig config) {
        boolean hs = new HsClient(config.hsPort).ping();
        String listeners = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        boolean notification = listeners != null && listeners.contains(context.getPackageName());
        String accessibility = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean accessibilityEnabled = accessibility != null && accessibility.contains(context.getPackageName());
        int imageCode = getCode(config.imageEndpoint.replaceAll("/+$", "") + "/models", config.imageApiKey);
        int chatCode = getCode(chatModelsUrl(config.chatEndpoint), config.apiKey);
        return "自检结果：\n"
                + "机器人服务：" + (BotService.isRunning() ? "正常" : "未运行") + "\n"
                + "HS：" + (hs ? "正常" : "异常") + "\n"
                + "通知监听：" + (notification ? "正常" : "未启用") + "\n"
                + "辅助功能：" + (accessibilityEnabled ? "正常" : "未启用") + "\n"
                + "文字上游：" + codeLabel(chatCode) + "\n"
                + "图片上游：" + codeLabel(imageCode) + "\n"
                + "提醒任务：" + ReminderManager.pendingCount(context) + " 个\n"
                + "任务状态：" + TaskController.status();
    }

    private static String chatModelsUrl(String endpoint) {
        String value = endpoint == null ? "" : endpoint.replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        return value + "/models";
    }

    private static int getCode(String endpoint, String key) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            if (key != null && !key.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            }
            return conn.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String codeLabel(int code) {
        if (code >= 200 && code < 300) return "正常（HTTP " + code + "）";
        if (code < 0) return "连接失败";
        return "异常（HTTP " + code + "）";
    }
}
