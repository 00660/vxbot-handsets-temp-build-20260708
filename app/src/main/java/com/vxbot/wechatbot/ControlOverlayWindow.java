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
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ControlOverlayWindow {
    private static final int DOT_SIZE_DP = 28;
    private static final int PANEL_WIDTH_DP = 234;
    private static final int PANEL_HEIGHT_DP = 44;
    private static final int RESTORE_NOT_FOCUSABLE_DELAY_MS = 1200;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            updatePauseButton();
        }
    };

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout root;
    private TextView dotView;
    private TextView pauseButton;
    private boolean receiverRegistered;
    private boolean expanded;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean moved;

    public ControlOverlayWindow(Context context) {
        this.context = context.getApplicationContext();
    }

    public void show() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            BotLog.w(context, "control.overlay.permission", "悬浮窗权限未开启，无法显示控制小白点");
            return;
        }
        handler.post(() -> {
            if (root != null) {
                updatePauseButton();
                return;
            }
            try {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                root = new FrameLayout(context);
                root.setFocusable(true);
                root.setFocusableInTouchMode(true);
                params = buildParams();
                buildViews();
                setExpanded(false);
                windowManager.addView(root, params);
                registerReceiver();
                updatePauseButton();
                BotLog.i(context, "control.overlay.show", "控制小白点已显示");
            } catch (Exception e) {
                BotLog.e(context, "control.overlay.show.fail",
                        "控制小白点显示失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                cleanupViews();
            }
        });
    }

    public void hide() {
        handler.post(this::cleanupViews);
    }

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams next = new WindowManager.LayoutParams(
                dp(DOT_SIZE_DP),
                dp(DOT_SIZE_DP),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        next.gravity = Gravity.TOP | Gravity.START;
        next.x = BotConfig.prefs(context).getInt("controlOverlayX", dp(8));
        next.y = BotConfig.prefs(context).getInt("controlOverlayY", dp(180));
        return next;
    }

    private void buildViews() {
        dotView = new TextView(context);
        dotView.setText("");
        dotView.setGravity(Gravity.CENTER);
        dotView.setBackground(circleBackground(0xDDFFFFFF));
        dotView.setElevation(dp(8));
        root.addView(dotView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        attachDrag(dotView, true);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(6), dp(5), dp(6), dp(5));
        panel.setBackground(roundBackground(0xEE111827));
        panel.setElevation(dp(8));

        TextView imeButton = actionButton("输入法");
        imeButton.setOnClickListener(v -> showInputMethodPicker());
        panel.addView(imeButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        pauseButton = actionButton("");
        pauseButton.setOnClickListener(v -> {
            BotRuntimeControls.togglePaused(context);
            updatePauseButton();
        });
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        pauseParams.setMargins(dp(6), 0, 0, 0);
        panel.addView(pauseButton, pauseParams);

        TextView collapseButton = actionButton("收起");
        collapseButton.setBackground(roundBackground(0xFF475569));
        collapseButton.setOnClickListener(v -> setExpanded(false));
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        collapseParams.setMargins(dp(6), 0, 0, 0);
        panel.addView(collapseButton, collapseParams);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        attachDrag(panel, false);
    }

    private TextView actionButton(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setBackground(roundBackground(0xFF2563EB));
        return view;
    }

    private void showInputMethodPicker() {
        try {
            makeOverlayFocusableForPicker();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                BotLog.e(context, "control.ime.fail", "InputMethodManager 不可用");
                restoreOverlayNotFocusableDelayed();
                return;
            }
            imm.showInputMethodPicker();
            BotLog.i(context, "control.ime.picker", "已请求系统输入法切换面板");
            restoreOverlayNotFocusableDelayed();
        } catch (Exception e) {
            BotLog.e(context, "control.ime.fail", e.getClass().getSimpleName() + " " + e.getMessage());
            restoreOverlayNotFocusableDelayed();
        }
    }

    private void makeOverlayFocusableForPicker() {
        if (params == null || windowManager == null || root == null) {
            return;
        }
        if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
            root.requestFocus();
            return;
        }
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            windowManager.updateViewLayout(root, params);
            root.requestFocus();
            BotLog.i(context, "control.ime.focus", "控制面板临时获取焦点以打开系统输入法面板");
        } catch (Exception e) {
            BotLog.e(context, "control.ime.focus.fail", e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void restoreOverlayNotFocusableDelayed() {
        handler.postDelayed(() -> {
            if (params == null || windowManager == null || root == null
                    || (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                return;
            }
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try {
                windowManager.updateViewLayout(root, params);
            } catch (Exception e) {
                BotLog.e(context, "control.ime.restore.fail", e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }, RESTORE_NOT_FOCUSABLE_DELAY_MS);
    }

    private void updatePauseButton() {
        handler.post(() -> {
            if (pauseButton != null) {
                pauseButton.setText(BotRuntimeControls.isPaused(context) ? "继续" : "暂停");
                pauseButton.setBackground(roundBackground(BotRuntimeControls.isPaused(context) ? 0xFF16A34A : 0xFFDC2626));
            }
        });
    }

    private void setExpanded(boolean nextExpanded) {
        if (root == null || dotView == null || root.getChildCount() < 2) {
            return;
        }
        expanded = nextExpanded;
        dotView.setVisibility(expanded ? View.GONE : View.VISIBLE);
        root.getChildAt(1).setVisibility(expanded ? View.VISIBLE : View.GONE);
        resize(expanded ? dp(PANEL_WIDTH_DP) : dp(DOT_SIZE_DP),
                expanded ? dp(PANEL_HEIGHT_DP) : dp(DOT_SIZE_DP));
        updatePauseButton();
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
                        BotLog.e(context, "control.overlay.drag.fail",
                                "控制小白点拖动失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePosition();
                    if (!moved) {
                        if (tapToExpand) {
                            setExpanded(true);
                        } else if (expanded) {
                            return false;
                        }
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
                .putInt("controlOverlayX", params.x)
                .putInt("controlOverlayY", params.y)
                .apply();
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BotRuntimeControls.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
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

    private void cleanupViews() {
        unregisterReceiver();
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (Exception ignored) {
            }
        }
        windowManager = null;
        params = null;
        root = null;
        dotView = null;
        pauseButton = null;
    }

    private GradientDrawable circleBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setStroke(dp(1), 0x55334155);
        return drawable;
    }

    private GradientDrawable roundBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
