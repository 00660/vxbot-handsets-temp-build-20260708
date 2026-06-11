package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GarbageCleaner {
    private static final String PREF_LAST_CLEANUP = "lastGarbageCleanupAt";
    private static final long MIN_INTERVAL_MS = 3 * 60 * 60 * 1000L;
    private static final long GENERATED_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
    private static final int GENERATED_KEEP_COUNT = 80;
    private static final long GENERATED_KEEP_BYTES = 220L * 1024L * 1024L;

    private GarbageCleaner() {
    }

    public static void runIfDue(Context context, String reason) {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences prefs = BotConfig.prefs(context);
        long last = prefs.getLong(PREF_LAST_CLEANUP, 0L);
        if (now - last < MIN_INTERVAL_MS) {
            return;
        }
        prefs.edit().putLong(PREF_LAST_CLEANUP, now).apply();
        runNow(context, reason);
    }

    public static void runNow(Context context, String reason) {
        if (context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Stats stats = new Stats();
        cleanGeneratedDir(context, now, stats);
        deleteOldChildren(context.getCacheDir(), now, CACHE_TTL_MS, stats);
        deleteOldChildren(context.getExternalCacheDir(), now, CACHE_TTL_MS, stats);
        deleteOldChildren(new File(context.getFilesDir(), "tmp"), now, CACHE_TTL_MS, stats);
        BotLog.i(context, "garbage.cleanup",
                "垃圾回收完成 reason=" + reason
                        + " deletedFiles=" + stats.files
                        + " deletedBytes=" + stats.bytes
                        + " keptGenerated=" + stats.keptGenerated);
    }

    private static void cleanGeneratedDir(Context context, long now, Stats stats) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) {
            return;
        }
        deleteGeneratedFiles(new File(base, "generated"), now, stats);
        deleteGeneratedFiles(new File(base, "wool"), now, stats);
    }

    private static void deleteGeneratedFiles(File dir, long now, Stats stats) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        List<File> kept = new ArrayList<>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                deleteOldChildren(file, now, GENERATED_TTL_MS, stats);
                continue;
            }
            if (now - file.lastModified() > GENERATED_TTL_MS) {
                deleteFile(file, stats);
            } else {
                kept.add(file);
            }
        }
        kept.sort(Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0L;
        for (int i = 0; i < kept.size(); i++) {
            File file = kept.get(i);
            bytes += file.length();
            if (i >= GENERATED_KEEP_COUNT || bytes > GENERATED_KEEP_BYTES) {
                deleteFile(file, stats);
            } else {
                stats.keptGenerated++;
            }
        }
    }

    private static void deleteOldChildren(File dir, long now, long ttlMs, Stats stats) {
        if (dir == null || !dir.exists()) {
            return;
        }
        if (dir.isFile()) {
            if (now - dir.lastModified() > ttlMs) {
                deleteFile(dir, stats);
            }
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (file.isDirectory()) {
                deleteOldChildren(file, now, ttlMs, stats);
                File[] rest = file.listFiles();
                if (rest != null && rest.length == 0 && now - file.lastModified() > ttlMs) {
                    file.delete();
                }
            } else if (now - file.lastModified() > ttlMs) {
                deleteFile(file, stats);
            }
        }
    }

    private static void deleteFile(File file, Stats stats) {
        if (file == null || !file.exists() || !file.isFile()) {
            return;
        }
        long length = file.length();
        if (file.delete()) {
            stats.files++;
            stats.bytes += Math.max(0L, length);
        }
    }

    private static final class Stats {
        int files;
        int keptGenerated;
        long bytes;
    }
}
