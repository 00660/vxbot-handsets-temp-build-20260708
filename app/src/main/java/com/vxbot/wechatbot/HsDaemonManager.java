package com.vxbot.wechatbot;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class HsDaemonManager {
    private static final String PREF_RUNTIME_STATE = "hsRuntimeState";
    private static final String PREF_RUNTIME_AT = "hsRuntimeAt";
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

    private Process process;

    public synchronized boolean ensureRunning(Context context, BotConfig config) {
        HsClient client = new HsClient(config.hsPort);
        if (client.ping()) {
            BotLog.i(context, "hs.ping", "daemon 已运行 port=" + config.hsPort);
            recordRuntime(context, "daemon 已响应 port=" + config.hsPort);
            return true;
        }
        try {
            deleteStaleHsJar(context);
            String apkPath = context.getPackageCodePath();
            String command = "CLASSPATH=" + shellQuote(apkPath)
                    + " app_process / dev.handsets.daemon.Main --port=" + config.hsPort;
            BotLog.i(context, "hs.classpath", "使用 APK dex 启动 hs daemon path=" + apkPath);
            if ("shizuku".equals(config.activeMode)) {
                if (!ensureAccessibilityEnabled(context)) {
                    return false;
                }
                if (!ShizukuBridge.available()) {
                    BotLog.e(context, "hs.start", "Shizuku 未运行或未授权");
                    recordRuntime(context, "启动失败: Shizuku 未运行或未授权");
                    return false;
                }
                if (!ShizukuBridge.hasPermission()) {
                    BotLog.e(context, "hs.start", "Shizuku 权限未授权");
                    recordRuntime(context, "启动失败: Shizuku 权限未授权");
                    return false;
                }
                process = startShizukuProcess(context, client, command);
                BotLog.i(context, "hs.start", "已通过 Shizuku 启动 hs daemon");
            } else {
                Process rootProcess = startRootProcess(context, client, command);
                if (rootProcess == null) {
                    BotLog.w(context, "hs.start", "root 模式所有 su 启动失败，尝试回退到 Shizuku");
                    if (!ensureAccessibilityEnabled(context)) {
                        return false;
                    }
                    if (!ShizukuBridge.available()) {
                        BotLog.e(context, "hs.start", "Shizuku 未运行，无法回退启动 hs");
                        recordRuntime(context, "启动失败: root 与 Shizuku 均不可用");
                        return false;
                    }
                    if (!ShizukuBridge.hasPermission()) {
                        BotLog.e(context, "hs.start", "Shizuku 权限未授权，无法回退启动 hs");
                        recordRuntime(context, "启动失败: Shizuku 权限未授权");
                        return false;
                    }
                    process = startShizukuProcess(context, client, command);
                    BotLog.i(context, "hs.start", "已通过 Shizuku 回退启动 hs daemon");
                } else {
                    process = rootProcess;
                }
            }
            boolean ok = waitForReady(client, 15000L);
            BotLog.write(context, ok ? "INFO" : "ERROR", "hs.ready", ok ? "hs daemon 已就绪" : "hs daemon 未响应");
            recordRuntime(context, ok ? "daemon 已就绪 port=" + config.hsPort : "启动后端口未响应 port=" + config.hsPort);
            return ok;
        } catch (Exception e) {
            BotLog.e(context, "hs.start", "启动 hs 失败: " + e.getMessage());
            recordRuntime(context, "启动异常: " + e.getMessage());
            return false;
        }
    }

    public synchronized void stop(Context context, BotConfig config) {
        try {
            new HsClient(config.hsPort).command("quit");
            BotLog.i(context, "hs.stop", "已发送 quit");
        } catch (Exception ignored) {
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        recordRuntime(context, "已停止");
    }

    public static String runtimeStatus(Context context) {
        String state = BotConfig.prefs(context).getString(PREF_RUNTIME_STATE, "未启动");
        long at = BotConfig.prefs(context).getLong(PREF_RUNTIME_AT, 0L);
        if (at <= 0L) {
            return state;
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - at) / 1000L);
        return state + " / " + seconds + "秒前";
    }

    private static void recordRuntime(Context context, String state) {
        BotConfig.prefs(context).edit()
                .putString(PREF_RUNTIME_STATE, state)
                .putLong(PREF_RUNTIME_AT, System.currentTimeMillis())
                .apply();
    }

    private static void deleteStaleHsJar(Context context) {
        File stale = new File(context.getFilesDir(), "hs.jar");
        if (!stale.exists()) {
            return;
        }
        if (stale.delete()) {
            BotLog.i(context, "hs.stale_jar.clean", "已删除旧 hs.jar 残留 " + stale.getAbsolutePath());
        } else {
            BotLog.w(context, "hs.stale_jar.clean.fail", "旧 hs.jar 残留删除失败 " + stale.getAbsolutePath());
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Process startRootProcess(Context context, HsClient client, String command) {
        int total = SU_CANDIDATES.length + 1;
        for (int i = 0; i < total; i++) {
            String su;
            String[] args;
            if (i == 0) {
                su = "PATH:su";
                args = new String[]{"/system/bin/sh", "-c", "su -c " + shellQuote(command)};
            } else {
                su = SU_CANDIDATES[i - 1];
                args = new String[]{su, "-c", command};
            }
            try {
                BotLog.i(context, "hs.root.try", "尝试 root 启动 su=" + su);
                Process p = Runtime.getRuntime().exec(args);
                Thread.sleep(500L);
                if (client.ping()) {
                    BotLog.i(context, "hs.root.ready", "root 启动成功 su=" + su);
                    return p;
                }
                try {
                    int exit = p.exitValue();
                    String stdout = readProcessStream(p.getInputStream());
                    String stderr = readProcessStream(p.getErrorStream());
                    BotLog.w(context, "hs.root.exit", "su 启动后立即退出 su=" + su
                            + " exit=" + exit
                            + " stdout=" + trimForLog(stdout)
                            + " stderr=" + trimForLog(stderr));
                    p.destroy();
                } catch (IllegalThreadStateException running) {
                    BotLog.i(context, "hs.root.started", "root 启动命令已投递 su=" + su);
                    return p;
                }
            } catch (IOException e) {
                BotLog.w(context, "hs.root.fail", "su 执行失败 su=" + su + " error=" + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                BotLog.e(context, "hs.root.fail", "su 启动等待被中断 su=" + su);
                return null;
            } catch (Exception e) {
                BotLog.w(context, "hs.root.fail", "su 启动异常 su=" + su + " error=" + e.getMessage());
            }
        }
        return null;
    }

    private static Process startShizukuProcess(Context context, HsClient client, String command) throws Exception {
        BotLog.i(context, "hs.shizuku.try", "尝试 Shizuku 启动 hs daemon");
        Process p = ShizukuBridge.startProcess(command);
        Thread.sleep(700L);
        if (client.ping()) {
            BotLog.i(context, "hs.shizuku.ready", "Shizuku 启动后端口已响应");
            return p;
        }
        try {
            int exit = p.exitValue();
            String stdout = readProcessStream(p.getInputStream());
            String stderr = readProcessStream(p.getErrorStream());
            BotLog.w(context, "hs.shizuku.exit", "Shizuku 启动后立即退出"
                    + " exit=" + exit
                    + " stdout=" + trimForLog(stdout)
                    + " stderr=" + trimForLog(stderr));
        } catch (IllegalThreadStateException running) {
            BotLog.i(context, "hs.shizuku.started", "Shizuku 启动命令已投递，等待端口响应");
        } catch (RuntimeException running) {
            String message = running.getMessage();
            if (message != null && message.contains("process hasn't exited")) {
                BotLog.i(context, "hs.shizuku.started", "Shizuku 进程仍在运行，等待端口响应");
            } else {
                throw running;
            }
        }
        return p;
    }

    private static boolean ensureAccessibilityEnabled(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String full = context.getPackageName() + "/" + BotAccessibilityService.class.getName();
        String shortName = context.getPackageName() + "/.BotAccessibilityService";
        if (flat != null && (flat.contains(full) || flat.contains(shortName))) {
            return true;
        }
        BotLog.w(context, "permission.accessibility.required", "无 root/Shizuku 模式需要先开启辅助功能服务");
        recordRuntime(context, "启动失败: 辅助功能未开启");
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            BotLog.e(context, "permission.accessibility.open.fail", e.getMessage());
        }
        return false;
    }

    private static boolean waitForReady(HsClient client, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.ping()) {
                return true;
            }
            Thread.sleep(400L);
        }
        return client.ping();
    }

    private static String readProcessStream(InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        int total = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (total < 4096 && (read = in.read(buffer)) >= 0) {
            int allowed = Math.min(read, 4096 - total);
            out.write(buffer, 0, allowed);
            total += allowed;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trimForLog(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 300 ? normalized.substring(0, 300) : normalized;
    }
}
