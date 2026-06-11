package com.vxbot.wechatbot;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public final class BotAccessibilityService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        BotLog.i(this, "accessibility.connected", "辅助功能服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        BotLog.w(this, "accessibility.interrupt", "辅助功能服务被系统中断");
    }
}
