package com.vxbot.wechatbot;

import java.net.HttpURLConnection;

public final class TaskController {
    private static final Object LOCK = new Object();
    private static HttpURLConnection connection;
    private static String label = "";
    private static long startedAt;
    private static boolean cancelled;

    private TaskController() {
    }

    public static void register(String taskLabel, HttpURLConnection activeConnection) {
        synchronized (LOCK) {
            label = taskLabel == null ? "" : taskLabel;
            connection = activeConnection;
            startedAt = System.currentTimeMillis();
            cancelled = false;
        }
    }

    public static void clear(HttpURLConnection activeConnection) {
        synchronized (LOCK) {
            if (connection == activeConnection) {
                connection = null;
                label = "";
                startedAt = 0L;
            }
        }
    }

    public static boolean cancel() {
        synchronized (LOCK) {
            if (connection == null) {
                return false;
            }
            connection.disconnect();
            cancelled = true;
            connection = null;
            label = "";
            startedAt = 0L;
            return true;
        }
    }

    public static boolean consumeCancelled() {
        synchronized (LOCK) {
            boolean value = cancelled;
            cancelled = false;
            return value;
        }
    }

    public static String status() {
        synchronized (LOCK) {
            if (connection == null) {
                return "当前没有可取消的网络任务";
            }
            long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
            return "正在执行：" + label + "，已运行 " + seconds + " 秒";
        }
    }
}
