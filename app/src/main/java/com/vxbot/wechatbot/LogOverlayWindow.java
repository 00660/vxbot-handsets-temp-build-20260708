package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class LogOverlayWindow {
    private static final int PANEL_WIDTH_DP = 300;
    private static final int PANEL_HEIGHT_DP = 300;
    private static final int DOT_SIZE_DP = 38;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            update();
        }
    };

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout root;
    private FrameLayout panelView;
    private FrameLayout panelHeader;
    private TextView dotView;
    private TextView logText;
    private boolean receiverRegistered;
    private boolean collapsed;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean moved;

    public LogOverlayWindow(Context context) {
        this.context = context.getApplicationContext();
    }

    public void show() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            BotLog.w(context, "overlay.permission", "悬浮窗权限未开启，无法显示实时日志");
            return;
        }
        handler.post(() -> {
            if (root != null) {
                update();
                return;
            }
            try {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                root = new FrameLayout(context);
                params = buildParams();
                buildViews();
                applyCollapsed(false);
                windowManager.addView(root, params);
                registerReceiver();
                update();
                BotLog.i(context, "overlay.show", "悬浮日志已显示");
            } catch (Exception e) {
                BotLog.e(context, "overlay.show.fail", "悬浮日志显示失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                cleanupViews();
            }
        });
    }

    public void hide() {
        handler.post(() -> {
            unregisterReceiver();
            if (root != null && windowManager != null) {
                try {
                    windowManager.removeView(root);
                } catch (Exception ignored) {
                }
            }
            root = null;
            panelView = null;
            panelHeader = null;
            dotView = null;
            logText = null;
            params = null;
            windowManager = null;
        });
    }

    public void expand() {
        handler.post(() -> {
            if (root == null) {
                show();
                handler.post(() -> applyCollapsed(false));
                return;
            }
            applyCollapsed(false);
        });
    }

    public void update() {
        handler.post(() -> {
            if (logText != null) {
                String analysis = GoutouJunshiStore.latestSummary(context);
                String rawLogs = BotLog.readTailNewestFirst(context, 5000);
                String routes = compactRouteLog(rawLogs);
                String flow = compactGoutouLog(rawLogs);
                String logs = compactLog(rawLogs);
                logText.setText(analysis + "\n\n最近消息路由\n" + routes
                        + "\n\n本次军师流程记录\n" + flow + "\n\n运行日志\n" + logs);
            }
        });
    }

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams next = new WindowManager.LayoutParams(
                dp(PANEL_WIDTH_DP),
                dp(PANEL_HEIGHT_DP),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        next.gravity = Gravity.TOP | Gravity.START;
        next.x = BotConfig.prefs(context).getInt("logOverlayX", dp(12));
        next.y = BotConfig.prefs(context).getInt("logOverlayY", dp(76));
        return next;
    }

    private void buildViews() {
        panelView = new FrameLayout(context);
        panelView.setBackground(panelBackground());

        LinearLayout panelContent = new LinearLayout(context);
        panelContent.setOrientation(LinearLayout.VERTICAL);
        panelView.addView(panelContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        panelHeader = new FrameLayout(context);
        TextView title = new TextView(context);
        title.setText("狗头军师 · 自动操作流");
        title.setTextColor(0xFF8FDCCB);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(10), 0, dp(36), 0);
        panelHeader.addView(title, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        logText = new TextView(context);
        logText.setTextColor(Color.rgb(226, 232, 240));
        logText.setTextSize(12);
        logText.setTypeface(Typeface.DEFAULT);
        logText.setLineSpacing(dp(2), 1.0f);
        logText.setPadding(dp(10), dp(6), dp(10), dp(12));
        logText.setSingleLine(false);
        ScrollView details = new ScrollView(context);
        details.setFillViewport(true);
        details.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panelContent.addView(panelHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        panelContent.addView(details, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = new TextView(context);
        close.setText("x");
        close.setTextColor(Color.WHITE);
        close.setTextSize(14);
        close.setGravity(Gravity.CENTER);
        close.setBackground(circleBackground(0xDD334155));
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(4), dp(4), 0);
        panelHeader.addView(close, closeParams);
        close.setOnClickListener(v -> applyCollapsed(true));
        root.addView(panelView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        attachDrag(panelHeader, false);

        dotView = new TextView(context);
        dotView.setText("军");
        dotView.setTextColor(Color.WHITE);
        dotView.setTextSize(14);
        dotView.setTypeface(Typeface.DEFAULT_BOLD);
        dotView.setGravity(Gravity.CENTER);
        dotView.setBackground(circleBackground(0xFF1677FF));
        root.addView(dotView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        dotView.setOnClickListener(v -> applyCollapsed(false));
        attachDrag(dotView, true);
    }

    private void applyCollapsed(boolean nextCollapsed) {
        if (root == null || panelView == null || dotView == null) {
            return;
        }
        collapsed = nextCollapsed;
        panelView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        dotView.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        resize(collapsed ? dp(DOT_SIZE_DP) : dp(PANEL_WIDTH_DP),
                collapsed ? dp(DOT_SIZE_DP) : dp(PANEL_HEIGHT_DP));
        if (!collapsed) {
            update();
        }
    }

    private void attachDrag(View view, boolean tapToExpand) {
        view.setOnTouchListener((v, event) -> {
            if (params == null || windowManager == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = params.x;
                    downY = params.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX);
                    int dy = Math.round(event.getRawY() - downRawY);
                    if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
                        moved = true;
                    }
                    params.x = Math.max(0, downX + dx);
                    params.y = Math.max(0, downY + dy);
                    try {
                        windowManager.updateViewLayout(root, params);
                    } catch (Exception e) {
                        BotLog.e(context, "overlay.drag.fail", "悬浮窗拖动失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePosition();
                    if (tapToExpand && !moved) {
                        applyCollapsed(false);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void resize(int width, int height) {
        if (params == null || windowManager == null || root == null) {
            return;
        }
        params.width = width;
        params.height = height;
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Exception ignored) {
        }
    }

    private void savePosition() {
        if (params == null) {
            return;
        }
        BotConfig.prefs(context).edit()
                .putInt("logOverlayX", params.x)
                .putInt("logOverlayY", params.y)
                .apply();
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BotLog.ACTION_LOG_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void cleanupViews() {
        unregisterReceiver();
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
        panelView = null;
        panelHeader = null;
        dotView = null;
        logText = null;
        params = null;
        windowManager = null;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private String compactLog(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "暂无运行日志";
        }
        String[] lines = raw.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            String one = compactLine(line);
            if (one.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(one);
            count++;
            if (count >= 7) {
                break;
            }
        }
        return out.length() == 0 ? "暂无中文日志" : out.toString();
    }

    private String compactGoutouLog(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "等待普通聊天进入策略分析";
        StringBuilder selected = new StringBuilder();
        int count = 0;
        for (String line : raw.split("\\r?\\n")) {
            if (!line.contains("goutou.")) continue;
            String compact = compactLine(line);
            if (compact.isEmpty()) continue;
            if (selected.length() > 0) selected.append('\n');
            selected.append(compact);
            if (++count == 9) break;
        }
        return selected.length() == 0 ? "等待普通聊天进入策略分析" : selected.toString();
    }

    private String compactRouteLog(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "等待消息路由";
        StringBuilder selected = new StringBuilder();
        int count = 0;
        for (String line : raw.split("\\r?\\n")) {
            if (!line.contains("route.selected")) continue;
            String compact = compactLine(line);
            if (compact.isEmpty()) continue;
            if (selected.length() > 0) selected.append('\n');
            selected.append(compact);
            if (++count == 5) break;
        }
        return selected.length() == 0 ? "等待消息路由" : selected.toString();
    }

    private String compactLine(String line) {
        if (line == null) {
            return "";
        }
        String text = line.trim();
        if (text.length() < 15) {
            return "";
        }
        String time = text.substring(6, 14);
        String rest = text.substring(15).trim();
        rest = rest.replaceFirst("^(INFO|WARN|ERROR|SUCCESS)\\s+", "");
        int firstSpace = rest.indexOf(' ');
        if (firstSpace >= 0) {
            rest = rest.substring(firstSpace + 1).trim();
        }
        String chinese = keepChineseLog(rest);
        if (chinese.isEmpty()) {
            return "";
        }
        return time + " " + chinese;
    }

    private String keepChineseLog(String text) {
        StringBuilder out = new StringBuilder();
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c) || isChinesePunctuation(c)) {
                out.append(c);
                lastSpace = false;
            } else if (Character.isWhitespace(c) && !lastSpace && out.length() > 0) {
                out.append(' ');
                lastSpace = true;
            }
        }
        return out.toString().trim();
    }

    private boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private boolean isChinesePunctuation(char c) {
        return "，。！？、：；（）《》【】“”‘’".indexOf(c) >= 0;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xE60F172A);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), 0x66334155);
        return drawable;
    }

    private GradientDrawable circleBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
