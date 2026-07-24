package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;

final class PendingReplyStore {
    private static final String PREFS = "pending_reply";
    private static final String KEY_SESSION = "session";
    private static final String KEY_SENDER = "sender";
    private static final String KEY_TEXT = "text";
    private static final String KEY_RAW_TITLE = "raw_title";
    private static final String KEY_RAW_CONTENT = "raw_content";
    private static final String KEY_NOTIFICATION = "notification";
    private static final String KEY_POST_TIME = "post_time";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;

    private PendingReplyStore() {
    }

    static void save(Context context, WxMessage message) {
        if (context == null || message == null) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_SESSION, message.sessionName)
                .putString(KEY_SENDER, message.senderName)
                .putString(KEY_TEXT, message.text)
                .putString(KEY_RAW_TITLE, message.rawTitle)
                .putString(KEY_RAW_CONTENT, message.rawContent)
                .putString(KEY_NOTIFICATION, message.notificationKey)
                .putLong(KEY_POST_TIME, message.postTime)
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .commit();
    }

    static WxMessage load(Context context) {
        if (context == null) {
            return null;
        }
        SharedPreferences prefs = prefs(context);
        long savedAt = prefs.getLong(KEY_SAVED_AT, 0L);
        if (savedAt <= 0L) {
            return null;
        }
        if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
            clear(context);
            return null;
        }
        String session = prefs.getString(KEY_SESSION, "");
        String sender = prefs.getString(KEY_SENDER, "");
        String text = prefs.getString(KEY_TEXT, "");
        if (session == null || session.trim().isEmpty() || text == null || text.trim().isEmpty()) {
            clear(context);
            return null;
        }
        String notification = prefs.getString(KEY_NOTIFICATION, "");
        return new WxMessage(session, sender, text,
                prefs.getString(KEY_RAW_TITLE, ""), prefs.getString(KEY_RAW_CONTENT, ""),
                "recovery-" + (notification == null ? "" : notification),
                prefs.getLong(KEY_POST_TIME, savedAt), null);
    }

    static void clearIfMatches(Context context, WxMessage message) {
        if (context == null || message == null) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String session = prefs.getString(KEY_SESSION, "");
        String sender = prefs.getString(KEY_SENDER, "");
        String text = prefs.getString(KEY_TEXT, "");
        long postTime = prefs.getLong(KEY_POST_TIME, 0L);
        if (message.sessionName.equals(session)
                && message.senderName.equals(sender)
                && message.text.equals(text)
                && message.postTime == postTime) {
            clear(context);
        }
    }

    static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
