package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.util.Locale;

public final class CoordinateProfileStore {
    private static final String PREFS = "coordinate_profiles";
    private static final String VERSION = "v1";

    private CoordinateProfileStore() {
    }

    public static Point get(Context context, String operationId) {
        ScreenProfile profile = currentProfile(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefix = profile.keyPrefix(operationId);
        if (!prefs.contains(prefix + ".x_ratio") || !prefs.contains(prefix + ".y_ratio")) {
            return null;
        }
        float xRatio = prefs.getFloat(prefix + ".x_ratio", -1f);
        float yRatio = prefs.getFloat(prefix + ".y_ratio", -1f);
        if (xRatio < 0f || yRatio < 0f) {
            return null;
        }
        int x = clamp(Math.round(xRatio * profile.width), 0, Math.max(0, profile.width - 1));
        int y = clamp(Math.round(yRatio * profile.height), 0, Math.max(0, profile.height - 1));
        return new Point(x, y);
    }

    public static Point resolve(Context context, String operationId, int fallbackX, int fallbackY) {
        Point point = get(context, operationId);
        if (point == null) {
            return new Point(fallbackX, fallbackY);
        }
        BotLog.i(context, "coordinate.profile.hit", "operationId=" + operationId
                + " profile=" + currentProfile(context).displayName()
                + " x=" + point.x + " y=" + point.y);
        return point;
    }

    public static void save(Context context, String operationId, int rawX, int rawY) {
        ScreenProfile profile = currentProfile(context);
        int x = clamp(rawX, 0, Math.max(0, profile.width - 1));
        int y = clamp(rawY, 0, Math.max(0, profile.height - 1));
        String prefix = profile.keyPrefix(operationId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(prefix + ".raw_x", x)
                .putInt(prefix + ".raw_y", y)
                .putInt(prefix + ".source_width", profile.width)
                .putInt(prefix + ".source_height", profile.height)
                .putFloat(prefix + ".x_ratio", profile.width <= 0 ? 0f : (float) x / profile.width)
                .putFloat(prefix + ".y_ratio", profile.height <= 0 ? 0f : (float) y / profile.height)
                .apply();
        BotLog.i(context, "coordinate.profile.save", "operationId=" + operationId
                + " profile=" + profile.displayName() + " x=" + x + " y=" + y);
    }

    public static void clearCurrentProfile(Context context) {
        ScreenProfile profile = currentProfile(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String prefix = profile.basePrefix() + ".";
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
        BotLog.i(context, "coordinate.profile.clear", "profile=" + profile.displayName());
    }

    public static String currentProfileName(Context context) {
        return currentProfile(context).displayName();
    }

    private static ScreenProfile currentProfile(Context context) {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        int rotation = 0;
        if (manager != null) {
            Display display = manager.getDefaultDisplay();
            display.getRealMetrics(metrics);
            rotation = display.getRotation();
        } else {
            metrics = context.getResources().getDisplayMetrics();
        }
        String mode = BotConfig.load(context).activeMode;
        return new ScreenProfile(safe(mode), Math.max(1, metrics.widthPixels),
                Math.max(1, metrics.heightPixels), rotation);
    }

    private static String safe(String value) {
        String next = value == null ? "default" : value.trim().toLowerCase(Locale.US);
        return next.replaceAll("[^a-z0-9_-]", "_");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ScreenProfile {
        final String mode;
        final int width;
        final int height;
        final int rotation;

        ScreenProfile(String mode, int width, int height, int rotation) {
            this.mode = mode;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }

        String basePrefix() {
            return VERSION + "." + mode + ".r" + rotation;
        }

        String keyPrefix(String operationId) {
            return basePrefix() + "." + operationId;
        }

        String displayName() {
            return mode + " / " + width + "x" + height + " / r" + rotation;
        }
    }
}
