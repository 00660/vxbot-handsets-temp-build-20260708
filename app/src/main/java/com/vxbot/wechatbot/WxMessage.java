package com.vxbot.wechatbot;

import android.app.PendingIntent;

public final class WxMessage {
    public final String sessionName;
    public final String senderName;
    public final String text;
    public final String rawTitle;
    public final String rawContent;
    public final String notificationKey;
    public final long postTime;
    public final PendingIntent contentIntent;

    public WxMessage(String sessionName, String senderName, String text, long postTime, PendingIntent contentIntent) {
        this(sessionName, senderName, text, "", "", "", postTime, contentIntent);
    }

    public WxMessage(String sessionName, String senderName, String text, String rawTitle, String rawContent,
                     String notificationKey, long postTime, PendingIntent contentIntent) {
        this.sessionName = clean(sessionName);
        this.senderName = clean(senderName);
        this.text = clean(text);
        this.rawTitle = clean(rawTitle);
        this.rawContent = clean(rawContent);
        this.notificationKey = clean(notificationKey);
        this.postTime = postTime;
        this.contentIntent = contentIntent;
    }

    public String key() {
        return sessionName + "|" + senderName + "|" + text + "|" + postTime;
    }

    public String display() {
        return "群=" + sessionName + " / 人=" + senderName + " / text=" + text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
