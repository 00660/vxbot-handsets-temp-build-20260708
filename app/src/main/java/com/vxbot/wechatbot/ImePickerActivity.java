package com.vxbot.wechatbot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;

public final class ImePickerActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int requestCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(true);
        BotLog.i(this, "control.ime.activity", "输入法切换桥接页已启动");
        requestPickerDelayed(120);
        requestPickerDelayed(500);
        handler.postDelayed(this::finish, 4500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestPickerDelayed(180);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            requestPickerDelayed(80);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestPickerDelayed(long delayMs) {
        handler.postDelayed(this::showPicker, Math.max(0L, delayMs));
    }

    private void showPicker() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) {
                BotLog.e(this, "control.ime.fail", "InputMethodManager 不可用");
            } else {
                imm.showInputMethodPicker();
                requestCount++;
                BotLog.i(this, "control.ime.picker", "已请求系统输入法切换面板 count="
                        + requestCount + " focus=" + hasWindowFocus());
            }
        } catch (Exception e) {
            BotLog.e(this, "control.ime.fail", e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
