package com.vxbot.wechatbot;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class BotLog {
    public static final String ACTION_LOG_CHANGED = "com.vxbot.wechatbot.LOG_CHANGED";

    private BotLog() {
    }

    public static synchronized void i(Context context, String tag, String message) {
        write(context, "INFO", tag, message);
    }

    public static synchronized void w(Context context, String tag, String message) {
        write(context, "WARN", tag, message);
    }

    public static synchronized void e(Context context, String tag, String message) {
        write(context, "ERROR", tag, message);
    }

    public static synchronized void write(Context context, String level, String tag, String message) {
        try {
            File file = logFile(context);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
            String line = ts + " " + level + " " + tag + " " + safe(message) + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            trimIfNeeded(file);
            context.sendBroadcast(new android.content.Intent(ACTION_LOG_CHANGED));
        } catch (Exception ignored) {
        }
    }

    public static synchronized String readTail(Context context, int maxChars) {
        try {
            File file = logFile(context);
            if (!file.exists()) {
                return "暂无运行日志";
            }
            long length = file.length();
            int bytes = (int) Math.min(length, Math.max(maxChars, 4096));
            byte[] buffer = new byte[bytes];
            try (FileInputStream in = new FileInputStream(file)) {
                if (in.skip(Math.max(0, length - bytes)) < 0) {
                    return "";
                }
                int read = in.read(buffer);
                if (read <= 0) {
                    return "";
                }
                return new String(buffer, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    public static synchronized String readTailNewestFirst(Context context, int maxChars) {
        String tail = readTail(context, maxChars);
        if (tail.indexOf('\n') < 0) {
            return tail;
        }
        String[] lines = tail.split("\\r?\\n");
        StringBuilder reversed = new StringBuilder(tail.length());
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isEmpty()) {
                continue;
            }
            reversed.append(lines[i]);
            if (i > 0) {
                reversed.append('\n');
            }
        }
        return reversed.toString();
    }

    public static File logFile(Context context) {
        return new File(context.getFilesDir(), "bot.log");
    }

    private static String safe(String message) {
        return message == null ? "" : message.replace("\r", " ").replace("\n", " ");
    }

    private static void trimIfNeeded(File file) {
        if (file.length() < 512 * 1024) {
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            long skip = Math.max(0, file.length() - 256 * 1024);
            if (in.skip(skip) < 0) {
                return;
            }
            byte[] rest = new byte[(int) (file.length() - skip)];
            int read = in.read(rest);
            if (read > 0) {
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(rest, 0, read);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
