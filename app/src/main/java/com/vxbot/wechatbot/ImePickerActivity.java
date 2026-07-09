package com.vxbot.wechatbot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;

public final class ImePickerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm == null) {
                BotLog.e(this, "control.ime.fail", "InputMethodManager 不可用");
            } else {
                imm.showInputMethodPicker();
                BotLog.i(this, "control.ime.picker", "已打开系统输入法切换面板");
            }
        } catch (Exception e) {
            BotLog.e(this, "control.ime.fail", e.getClass().getSimpleName() + " " + e.getMessage());
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 600);
    }
}
