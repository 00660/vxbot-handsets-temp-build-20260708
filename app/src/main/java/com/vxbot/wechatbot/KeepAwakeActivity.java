package com.vxbot.wechatbot;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

public final class KeepAwakeActivity extends Activity {
    public static final String ACTION_KEEP_AWAKE = "com.vxbot.wechatbot.KEEP_AWAKE";
    public static final String ACTION_STOP_KEEP_AWAKE = "com.vxbot.wechatbot.STOP_KEEP_AWAKE";

    private static WeakReference<KeepAwakeActivity> current = new WeakReference<>(null);

    public static void finishRunning() {
        KeepAwakeActivity activity = current.get();
        if (activity != null) {
            activity.finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<>(this);
        applyWindow();
        if (ACTION_STOP_KEEP_AWAKE.equals(getIntent() == null ? "" : getIntent().getAction())) {
            finish();
            return;
        }
        setContentView(new View(this));
        BotLog.i(this, "screen.keepawake.activity", "低亮防熄屏窗口已开启");
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_STOP_KEEP_AWAKE.equals(intent == null ? "" : intent.getAction())) {
            finish();
        } else {
            applyWindow();
        }
    }

    @Override
    protected void onDestroy() {
        KeepAwakeActivity activity = current.get();
        if (activity == this) {
            current = new WeakReference<>(null);
        }
        BotLog.i(this, "screen.keepawake.activity", "低亮防熄屏窗口已关闭");
        super.onDestroy();
    }

    private void applyWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        WindowManager.LayoutParams params = window.getAttributes();
        params.screenBrightness = 0.01f;
        params.gravity = Gravity.START | Gravity.TOP;
        window.setAttributes(params);
        window.setGravity(Gravity.START | Gravity.TOP);
        window.setLayout(1, 1);
    }
}
