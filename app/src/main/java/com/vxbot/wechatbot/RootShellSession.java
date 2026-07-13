package com.vxbot.wechatbot;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicLong;

final class RootShellSession {
    private static final String[] CANDIDATES = {
            "/proc/1/root/debug_ramdisk/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "su"
    };
    private static final AtomicLong COMMAND_IDS = new AtomicLong();
    private static Process process;
    private static BufferedWriter writer;
    private static BufferedReader reader;

    private RootShellSession() {
    }

    static synchronized Result execute(String command, int timeoutMs) {
        long start = SystemClock.uptimeMillis();
        try {
            ensureStarted();
            long id = COMMAND_IDS.incrementAndGet();
            String marker = "__VXROOT_DONE_" + id + "__";
            writer.write(command);
            writer.newLine();
            writer.write("echo " + marker + "$?");
            writer.newLine();
            writer.flush();

            StringBuilder output = new StringBuilder();
            long deadline = start + Math.max(1, timeoutMs);
            while (SystemClock.uptimeMillis() < deadline) {
                if (!reader.ready()) {
                    if (!process.isAlive()) {
                        throw new IllegalStateException("root shell exited");
                    }
                    SystemClock.sleep(10);
                    continue;
                }
                String line = reader.readLine();
                if (line == null) {
                    throw new IllegalStateException("root shell output closed");
                }
                int markerAt = line.indexOf(marker);
                if (markerAt >= 0) {
                    String codeText = line.substring(markerAt + marker.length()).trim();
                    int code;
                    try {
                        code = Integer.parseInt(codeText);
                    } catch (NumberFormatException e) {
                        code = 1;
                    }
                    return new Result(code, output.toString(), SystemClock.uptimeMillis() - start);
                }
                if (output.length() < 2200) {
                    output.append(line).append('\n');
                }
            }
            closeLocked();
            return new Result(124, output.toString(), SystemClock.uptimeMillis() - start);
        } catch (Exception e) {
            closeLocked();
            return new Result(127, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    SystemClock.uptimeMillis() - start);
        }
    }

    private static void ensureStarted() throws Exception {
        if (process != null && process.isAlive() && writer != null && reader != null) {
            return;
        }
        closeLocked();
        Exception last = null;
        for (String binary : CANDIDATES) {
            if (!"su".equals(binary) && !new File(binary).exists()) {
                continue;
            }
            try {
                Process candidate = new ProcessBuilder(binary).redirectErrorStream(true).start();
                process = candidate;
                writer = new BufferedWriter(new OutputStreamWriter(candidate.getOutputStream()));
                reader = new BufferedReader(new InputStreamReader(candidate.getInputStream()));
                return;
            } catch (Exception e) {
                last = e;
                closeLocked();
            }
        }
        throw last == null ? new IllegalStateException("su not found") : last;
    }

    private static void closeLocked() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }
        if (process != null) {
            process.destroy();
        }
        writer = null;
        reader = null;
        process = null;
    }

    static final class Result {
        final int code;
        final String output;
        final long elapsedMs;

        Result(int code, String output, long elapsedMs) {
            this.code = code;
            this.output = output;
            this.elapsedMs = elapsedMs;
        }
    }
}
