package com.vxbot.wechatbot;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class WxNotificationListener extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !"com.tencent.mm".equals(sbn.getPackageName())) {
            return;
        }
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }
        Bundle extras = notification.extras;
        String title = valueOf(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = valueOf(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText = valueOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String subText = valueOf(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String summary = valueOf(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        String lines = linesOf(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES));
        String content = firstNonEmpty(bigText, text, lines, subText, summary);
        String rawContent = joinNonEmpty(text, bigText, lines, subText, summary);
        if (title.isEmpty() && rawContent.isEmpty()) {
            return;
        }
        Parsed parsed = parse(title, content);
        Intent intent = new Intent(this, BotService.class);
        intent.setAction(BotService.ACTION_HANDLE_NOTIFICATION);
        intent.putExtra("sessionName", parsed.sessionName);
        intent.putExtra("senderName", parsed.senderName);
        intent.putExtra("text", parsed.text);
        intent.putExtra("rawTitle", title);
        intent.putExtra("rawContent", rawContent.isEmpty() ? content : rawContent);
        intent.putExtra("notificationKey", sbn.getKey());
        intent.putExtra("postTime", sbn.getPostTime());
        PendingIntent pendingIntent = notification.contentIntent;
        if (pendingIntent != null) {
            intent.putExtra("contentIntent", pendingIntent);
        }
        startForegroundService(intent);
    }

    private static Parsed parse(String title, String content) {
        String cleaned = stripCountPrefix(content);
        int p1 = cleaned.indexOf(':');
        int p2 = cleaned.indexOf('：');
        int idx = p1 >= 0 && p2 >= 0 ? Math.min(p1, p2) : Math.max(p1, p2);
        if (idx > 0 && idx < 32) {
            String sender = cleaned.substring(0, idx).trim();
            String text = cleaned.substring(idx + 1).trim();
            return new Parsed(title.trim(), sender, text);
        }
        return new Parsed(title.trim(), title.trim(), cleaned.trim());
    }

    private static String stripCountPrefix(String text) {
        String value = text == null ? "" : text.trim();
        int idx = value.indexOf(']');
        if (idx > 0 && idx < 8 && value.charAt(0) == '[') {
            return value.substring(idx + 1).trim();
        }
        return value;
    }

    private static String valueOf(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String linesOf(CharSequence[] lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence line : lines) {
            String text = valueOf(line).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String joinNonEmpty(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (text.isEmpty() || containsLine(sb, text)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private static boolean containsLine(StringBuilder sb, String text) {
        if (sb.length() == 0) {
            return false;
        }
        String value = sb.toString();
        return value.equals(text) || value.startsWith(text + "\n") || value.endsWith("\n" + text)
                || value.contains("\n" + text + "\n");
    }

    private static final class Parsed {
        final String sessionName;
        final String senderName;
        final String text;

        Parsed(String sessionName, String senderName, String text) {
            this.sessionName = sessionName;
            this.senderName = senderName;
            this.text = text;
        }
    }
}
