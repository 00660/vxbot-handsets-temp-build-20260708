package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

final class VmicInjector {
    private static final String VMIC_PLAY = "/vendor/bin/vmic_play";
    private static final String VMIC_PLAY_MODULE = "/data/adb/modules/mido_vmic_hal/system/vendor/bin/vmic_play";
    private static final String VMIC_PUSH = "/vendor/bin/vmic_push";
    private static final String VMIC_PUSH_INSTANTNOODLEP_MODULE =
            "/data/adb/modules/instantnoodlep_vmic/system/vendor/bin/vmic_push";

    private VmicInjector() {
    }

    static boolean injectFile(Context context, File file, int timeoutMs, String reason) {
        if (file == null || !file.isFile() || file.length() <= 44) {
            BotLog.w(context, "vmic.inject.skip", "reason=" + reason + " invalid file");
            return false;
        }
        Helper helper = findPlayableHelper(context);
        if (helper.path.isEmpty()) {
            BotLog.w(context, "vmic.inject.unavailable", "reason=" + reason
                    + " helper missing: " + VMIC_PLAY + " / " + VMIC_PUSH);
            return false;
        }
        int waitMs = Math.max(8000, timeoutMs);
        String command = shellQuote(helper.path) + " " + shellQuote(file.getAbsolutePath()) + " 48000 1";
        ShellResult result = runRoot(command, waitMs);
        if (result.code == 0) {
            BotLog.i(context, "vmic.inject.done", "reason=" + reason
                    + " helper=" + helper.name
                    + " file=" + file.getAbsolutePath()
                    + " size=" + file.length()
                    + " elapsedMs=" + result.elapsedMs
                    + " out=" + trim(result.output));
            return true;
        }
        BotLog.w(context, "vmic.inject.fail", "reason=" + reason
                + " helper=" + helper.name
                + " code=" + result.code
                + " elapsedMs=" + result.elapsedMs
                + " out=" + trim(result.output));
        return false;
    }

    static boolean helperPresent(Context context) {
        return !findPlayableHelper(context).path.isEmpty();
    }

    private static Helper findPlayableHelper(Context context) {
        ShellResult vendor = runRoot("[ -x " + shellQuote(VMIC_PLAY) + " ] && echo " + shellQuote(VMIC_PLAY) + " || true", 4000);
        if (vendor.output != null && vendor.output.contains(VMIC_PLAY)) {
            return new Helper("vmic_play", VMIC_PLAY);
        }
        ShellResult module = runRoot("[ -x " + shellQuote(VMIC_PLAY_MODULE) + " ] && echo " + shellQuote(VMIC_PLAY_MODULE) + " || true", 4000);
        if (module.output != null && module.output.contains(VMIC_PLAY_MODULE)) {
            return new Helper("vmic_play_module", VMIC_PLAY_MODULE);
        }
        ShellResult push = runRoot("[ -x " + shellQuote(VMIC_PUSH) + " ] && echo " + shellQuote(VMIC_PUSH) + " || true", 4000);
        if (push.output != null && push.output.contains(VMIC_PUSH)) {
            return new Helper("vmic_push", VMIC_PUSH);
        }
        ShellResult instantnoodlep = runRoot("[ -x " + shellQuote(VMIC_PUSH_INSTANTNOODLEP_MODULE) + " ] && echo "
                + shellQuote(VMIC_PUSH_INSTANTNOODLEP_MODULE) + " || true", 4000);
        if (instantnoodlep.output != null && instantnoodlep.output.contains(VMIC_PUSH_INSTANTNOODLEP_MODULE)) {
            return new Helper("vmic_push_instantnoodlep_module", VMIC_PUSH_INSTANTNOODLEP_MODULE);
        }
        if (context != null) {
            BotLog.i(context, "vmic.inject.helper",
                    "vmic_play=false vmic_push=false instantnoodlep_module=false");
        }
        return new Helper("", "");
    }

    private static ShellResult runRoot(String command, int timeoutMs) {
        String[] candidates = {"/debug_ramdisk/su", "/sbin/su", "/system/bin/su", "/system/xbin/su", "su"};
        Exception last = null;
        for (String binary : candidates) {
            if (!"su".equals(binary) && !new File(binary).exists()) {
                continue;
            }
            Process process = null;
            long start = SystemClock.uptimeMillis();
            try {
                process = new ProcessBuilder(binary, "-c", command).redirectErrorStream(true).start();
                boolean finished = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
                long elapsed = SystemClock.uptimeMillis() - start;
                if (!finished) {
                    process.destroy();
                    process.waitFor(500, TimeUnit.MILLISECONDS);
                }
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (out.length() < 2200) {
                            out.append(line).append('\n');
                        }
                    }
                }
                if (!finished) {
                    return new ShellResult(124, out.toString(), elapsed);
                }
                return new ShellResult(process.exitValue(), out.toString(), elapsed);
            } catch (Exception e) {
                last = e;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        String message = last == null ? "su not found" : last.getClass().getSimpleName() + ": " + last.getMessage();
        return new ShellResult(127, message, 0);
    }

    private static String shellQuote(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 600 ? oneLine : oneLine.substring(0, 600);
    }

    private static final class ShellResult {
        final int code;
        final String output;
        final long elapsedMs;

        ShellResult(int code, String output, long elapsedMs) {
            this.code = code;
            this.output = output;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class Helper {
        final String name;
        final String path;

        Helper(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }
}
