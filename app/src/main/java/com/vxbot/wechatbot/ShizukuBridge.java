package com.vxbot.wechatbot;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;

public final class ShizukuBridge {
    private static final int REQUEST_CODE = 6601;

    private ShizukuBridge() {
    }

    public static boolean available() {
        try {
            Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
            Method method = cls.getMethod("pingBinder");
            Object result = method.invoke(null);
            return Boolean.TRUE.equals(result);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
            Method method = cls.getMethod("checkSelfPermission");
            Object result = method.invoke(null);
            return result instanceof Integer && ((Integer) result) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String requestPermission() {
        try {
            Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
            Method method = cls.getMethod("requestPermission", int.class);
            method.invoke(null, REQUEST_CODE);
            return "已向 Shizuku 发起授权请求";
        } catch (Exception e) {
            return "Shizuku 授权请求失败: " + e.getMessage();
        }
    }

    public static Process startProcess(String command) throws Exception {
        Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
        Method method = cls.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Object result = method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        if (result instanceof Process) {
            return (Process) result;
        }
        throw new IllegalStateException("Shizuku newProcess 返回类型异常: "
                + (result == null ? "null" : result.getClass().getName()));
    }
}
