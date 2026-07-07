package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

final class NewPhoneController {
    private static final String CTL = "/proc/mtk_newphone_ctl";
    private static final String PROFILE = "/proc/mtk_newphone_profile";

    private NewPhoneController() {
    }

    static ShellResult generate(Context context) {
        ShellResult result = runRoot("printf generate > " + shellQuote(CTL)
                + " && cat " + shellQuote(PROFILE), 6000);
        log(context, "newphone.generate", result);
        return result;
    }

    static ShellResult disable(Context context) {
        ShellResult result = runRoot("printf disable > " + shellQuote(CTL)
                + " && cat " + shellQuote(PROFILE), 6000);
        log(context, "newphone.disable", result);
        return result;
    }

    static ShellResult readProfile(Context context) {
        ShellResult result = runRoot("[ -e " + shellQuote(CTL) + " ] && cat "
                + shellQuote(PROFILE) + " || echo newphone_proc_missing", 6000);
        log(context, "newphone.profile", result);
        return result;
    }

    private static void log(Context context, String tag, ShellResult result) {
        String text = "code=" + result.code + " elapsedMs=" + result.elapsedMs
                + " out=" + trim(result.output);
        if (result.code == 0) {
            BotLog.i(context, tag, text);
        } else {
            BotLog.w(context, tag, text);
        }
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
                        if (out.length() < 12000) {
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
        return oneLine.length() <= 900 ? oneLine : oneLine.substring(0, 900);
    }

    static final class ShellResult {
        final int code;
        final String output;
        final long elapsedMs;

        ShellResult(int code, String output, long elapsedMs) {
            this.code = code;
            this.output = output == null ? "" : output;
            this.elapsedMs = elapsedMs;
        }
    }
}
