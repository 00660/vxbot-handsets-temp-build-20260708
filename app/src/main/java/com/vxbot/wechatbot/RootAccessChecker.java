package com.vxbot.wechatbot;

import java.io.File;
import java.util.concurrent.TimeUnit;

final class RootAccessChecker {
    private static final String[] SU_CANDIDATES = new String[]{
            "/product/bin/su",
            "/proc/1/root/product/bin/su",
            "/proc/1/root/debug_ramdisk/su",
            "/debug_ramdisk/su",
            "/data/adb/magisk/su",
            "/sbin/su",
            "/system/xbin/su",
            "/system/bin/su",
            "/vendor/bin/su",
            "su"
    };

    private RootAccessChecker() {
    }

    static boolean hasRootPermission() {
        for (String su : SU_CANDIDATES) {
            if (!"su".equals(su) && !new File(su).exists()) {
                continue;
            }
            Process process = null;
            try {
                process = new ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start();
                if (process.waitFor(1200, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return false;
    }
}
