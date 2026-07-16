package com.vxbot.wechatbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReminderManager {
    private static final String PREFS = "reminders";
    private static final String KEY_ITEMS = "items";

    private ReminderManager() {
    }

    public static String createOrList(Context context, WxMessage message, String command) {
        String compact = command == null ? "" : command.replaceAll("\\s+", "").trim();
        if (compact.matches("^(查看|查询|我的|群里)?提醒(列表)?$")) {
            return list(context, message.sessionName);
        }
        Parsed parsed = parse(command);
        if (parsed == null) {
            return "提醒时间没看懂。可以说：10分钟后提醒我喝水，或明天9点提醒群里开会。";
        }
        String id = "r" + System.currentTimeMillis();
        try {
            JSONArray items = items(context);
            items.put(new JSONObject()
                    .put("id", id)
                    .put("session", message.sessionName)
                    .put("sender", message.senderName)
                    .put("text", parsed.text)
                    .put("triggerAt", parsed.triggerAt));
            save(context, items);
            schedule(context, id, parsed.triggerAt);
            return "提醒已记下：" + format(parsed.triggerAt) + "，" + parsed.text;
        } catch (Exception e) {
            BotLog.e(context, "reminder.create.fail", e.getMessage());
            return "提醒保存失败了：" + e.getMessage();
        }
    }

    public static Reminder consume(Context context, String id) {
        try {
            JSONArray current = items(context);
            JSONArray next = new JSONArray();
            Reminder found = null;
            for (int i = 0; i < current.length(); i++) {
                JSONObject row = current.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                if (id.equals(row.optString("id"))) {
                    found = new Reminder(row.optString("session"), row.optString("sender"), row.optString("text"));
                } else {
                    next.put(row);
                }
            }
            save(context, next);
            return found;
        } catch (Exception e) {
            BotLog.e(context, "reminder.consume.fail", e.getMessage());
            return null;
        }
    }

    public static void rescheduleAll(Context context) {
        long now = System.currentTimeMillis();
        JSONArray current = items(context);
        for (int i = 0; i < current.length(); i++) {
            JSONObject row = current.optJSONObject(i);
            if (row == null) {
                continue;
            }
            long triggerAt = row.optLong("triggerAt", 0L);
            if (triggerAt > now) {
                schedule(context, row.optString("id"), triggerAt);
            }
        }
    }

    public static int pendingCount(Context context) {
        return items(context).length();
    }

    private static String list(Context context, String session) {
        JSONArray current = items(context);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < current.length(); i++) {
            JSONObject row = current.optJSONObject(i);
            if (row == null || !session.equals(row.optString("session"))) {
                continue;
            }
            out.append("\n").append(++count).append(". ")
                    .append(format(row.optLong("triggerAt"))).append(" ")
                    .append(row.optString("text"));
        }
        return count == 0 ? "当前群没有待执行提醒。" : "当前群提醒：" + out;
    }

    private static Parsed parse(String source) {
        String text = source == null ? "" : source.trim();
        Matcher relative = Pattern.compile("(\\d{1,4})\\s*(分钟|小时|天)后提醒(?:我|群里)?[，,:：]?\\s*(.+)").matcher(text);
        if (relative.find()) {
            long amount = Long.parseLong(relative.group(1));
            String unit = relative.group(2);
            long multiplier = "分钟".equals(unit) ? 60000L : ("小时".equals(unit) ? 3600000L : 86400000L);
            return new Parsed(System.currentTimeMillis() + amount * multiplier, cleanBody(relative.group(3)));
        }
        Matcher clock = Pattern.compile("(今天|明天|后天)?\\s*(上午|下午|晚上)?\\s*(\\d{1,2})\\s*(?:点|[:：])\\s*(\\d{1,2})?分?\\s*提醒(?:我|群里)?[，,:：]?\\s*(.+)").matcher(text);
        if (!clock.find()) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        String day = clock.group(1);
        if ("明天".equals(day)) calendar.add(Calendar.DAY_OF_YEAR, 1);
        if ("后天".equals(day)) calendar.add(Calendar.DAY_OF_YEAR, 2);
        int hour = Integer.parseInt(clock.group(3));
        String period = clock.group(2);
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) hour += 12;
        calendar.set(Calendar.HOUR_OF_DAY, Math.min(23, hour));
        calendar.set(Calendar.MINUTE, clock.group(4) == null ? 0 : Math.min(59, Integer.parseInt(clock.group(4))));
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (day == null && calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return new Parsed(calendar.getTimeInMillis(), cleanBody(clock.group(5)));
    }

    private static String cleanBody(String value) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? "该办的事别忘了" : text;
    }

    private static void schedule(Context context, String id, long triggerAt) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null || id == null || id.isEmpty()) {
            return;
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, id));
        BotLog.i(context, "reminder.schedule", "id=" + id + " triggerAt=" + format(triggerAt));
    }

    private static PendingIntent pendingIntent(Context context, String id) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra("id", id);
        return PendingIntent.getBroadcast(context, id.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static JSONArray items(Context context) {
        try {
            return new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray items) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, items.toString()).apply();
    }

    private static String format(long time) {
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(time));
    }

    public static final class Reminder {
        public final String session;
        public final String sender;
        public final String text;

        Reminder(String session, String sender, String text) {
            this.session = session;
            this.sender = sender;
            this.text = text;
        }
    }

    private static final class Parsed {
        final long triggerAt;
        final String text;

        Parsed(long triggerAt, String text) {
            this.triggerAt = triggerAt;
            this.text = text;
        }
    }
}
