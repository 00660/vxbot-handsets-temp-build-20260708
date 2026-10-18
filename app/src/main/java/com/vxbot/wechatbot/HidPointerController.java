package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Point;
import android.view.WindowManager;

import java.io.IOException;

/** Clicks an OCR coordinate through the paired device's BLE HID absolute-touch interface. */
public final class HidPointerController {
    private HidPointerController() {}

    public static Point clickAt(Context context, HsClient hs, int x, int y) throws Exception {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            throw new IOException("Display is unavailable");
        }
        Point display = new Point();
        manager.getDefaultDisplay().getRealSize(display);
        if (x < 0 || y < 0 || x >= display.x || y >= display.y) {
            throw new IOException("HID target is outside the display: " + x + "," + y);
        }
        hs.tap(x, y);
        return new Point(x, y);
    }
}
