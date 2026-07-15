package com.vxbot.wechatbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class CoordinateCalibrationActivity extends Activity {
    private FrameLayout root;
    private TextView status;
    private View marker;
    private int capturedX;
    private int capturedY;
    private boolean choosingOperations;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        buildContent();
        BotLog.i(this, "coordinate.capture.open", "profile=" + CoordinateProfileStore.currentProfileName(this));
    }

    @Override
    protected void onDestroy() {
        sendBroadcast(new android.content.Intent(ControlOverlayWindow.ACTION_CALIBRATION_FINISHED)
                .setPackage(getPackageName()));
        super.onDestroy();
    }

    private void buildContent() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setOnTouchListener(this::captureTouch);

        LinearLayout topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.VERTICAL);
        topPanel.setPadding(dp(14), dp(10), dp(14), dp(10));
        topPanel.setBackground(roundBackground(0xE6111827));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setText("点击屏幕目标位置，再勾选对应操作块\n内部配置："
                + CoordinateProfileStore.currentProfileName(this));
        topPanel.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);
        Button clear = button("清除本机坐标");
        clear.setOnClickListener(v -> confirmClear());
        actions.addView(clear);
        Button finish = button("完成");
        finish.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams finishParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        finishParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(finish, finishParams);
        topPanel.addView(actions);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        topParams.setMargins(dp(10), dp(28), dp(10), 0);
        root.addView(topPanel, topParams);

        marker = new View(this);
        marker.setBackground(roundBackground(0xFFFF3B30));
        marker.setVisibility(View.GONE);
        root.addView(marker, new FrameLayout.LayoutParams(dp(14), dp(14)));
        setContentView(root);
    }

    private boolean captureTouch(View view, MotionEvent event) {
        if (choosingOperations || event.getActionMasked() != MotionEvent.ACTION_UP) {
            return true;
        }
        capturedX = Math.round(event.getRawX());
        capturedY = Math.round(event.getRawY());
        showMarker(capturedX, capturedY);
        chooseOperations();
        return true;
    }

    private void chooseOperations() {
        choosingOperations = true;
        List<ClickOperationRegistry.Operation> operations = ClickOperationRegistry.all();
        CharSequence[] labels = new CharSequence[operations.size()];
        boolean[] checked = new boolean[operations.size()];
        for (int i = 0; i < operations.size(); i++) {
            ClickOperationRegistry.Operation operation = operations.get(i);
            Point saved = CoordinateProfileStore.get(this, operation.id);
            labels[i] = saved == null ? operation.label : operation.label + "（已保存 " + saved.x + "," + saved.y + "）";
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("坐标 " + capturedX + "," + capturedY + " 指派给")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int count = 0;
            for (int i = 0; i < operations.size(); i++) {
                if (!checked[i]) {
                    continue;
                }
                CoordinateProfileStore.save(this, operations.get(i).id, capturedX, capturedY);
                count++;
            }
            if (count == 0) {
                Toast.makeText(this, "至少勾选一个操作块", Toast.LENGTH_SHORT).show();
                return;
            }
            status.setText("已把 " + capturedX + "," + capturedY + " 保存到 " + count
                    + " 个操作块\n可继续点击下一位置，或点完成退出");
            dialog.dismiss();
        }));
        dialog.setOnDismissListener(ignored -> choosingOperations = false);
        dialog.show();
    }

    private void confirmClear() {
        choosingOperations = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("清除当前内部配置？")
                .setMessage(CoordinateProfileStore.currentProfileName(this))
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, which) -> {
                    CoordinateProfileStore.clearCurrentProfile(this);
                    marker.setVisibility(View.GONE);
                    status.setText("当前内部配置已清除\n点击屏幕目标位置重新采集");
                })
                .create();
        dialog.setOnDismissListener(ignored -> choosingOperations = false);
        dialog.show();
    }

    private void showMarker(int x, int y) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) marker.getLayoutParams();
        params.leftMargin = x - dp(7);
        params.topMargin = y - dp(7);
        marker.setLayoutParams(params);
        marker.setVisibility(View.VISIBLE);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackground(roundBackground(0xFF2563EB));
        return button;
    }

    private GradientDrawable roundBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
