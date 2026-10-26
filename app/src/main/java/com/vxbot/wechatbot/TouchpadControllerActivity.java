package com.vxbot.wechatbot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Second-device touch controller. HTTP supplies the screen image; BLE GATT carries touch input. */
public final class TouchpadControllerActivity extends Activity {
    private static final String PREFS = "touchpad_controller";
    private static final String URL_KEY = "frame_base_url";
    private static final int REQUEST_BLE_CONNECT = 7411;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService bleWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<int[]> pendingMove = new AtomicReference<>();
    private final AtomicBoolean moveDrainScheduled = new AtomicBoolean();
    private EditText frameBase;
    private TextView status;
    private TextView controlStatus;
    private TextView pairStatus;
    private Button controlButton;
    private FrameView frame;
    private volatile int targetWidth;
    private volatile int targetHeight;
    private volatile boolean controlling;
    private volatile boolean controlConnectionOwned;
    private volatile boolean touchPressed;
    private volatile boolean framePolling;
    private volatile String activeFrameBase = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPairStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(16, 24, 39));

        TextView title = new TextView(this);
        title.setText("蓝牙触控板");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));

        TextView help = new TextView(this);
        help.setText("本机作为第二台控制设备，通过 BLE GATT 连接 CH591D 发送触控指令。配对在本页内完成（自动确认，不弹系统蓝牙配对界面，无需去系统设置里配对）。下方 HTTP 地址只用于读取第一台手机画面。把机器人状态页中的触控板地址粘贴到这里。");
        help.setTextColor(0xffaab7cc);
        help.setTextSize(12);
        root.addView(help, new LinearLayout.LayoutParams(-1, -2));

        frameBase = new EditText(this);
        frameBase.setSingleLine(true);
        frameBase.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(URL_KEY, ""));
        frameBase.setHint("http://机器人IP:18234/touchpad/令牌");
        frameBase.setTextColor(Color.WHITE);
        frameBase.setHintTextColor(0xff8995a8);
        frameBase.setSelectAllOnFocus(false);
        root.addView(frameBase, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button load = new Button(this);
        load.setText("读取画面");
        load.setOnClickListener(v -> startFrames());
        buttons.addView(load, new LinearLayout.LayoutParams(0, dp(48), 1));
        controlButton = new Button(this);
        controlButton.setText("连接蓝牙触控");
        controlButton.setOnClickListener(v -> toggleControl());
        buttons.addView(controlButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, dp(52)));

        status = new TextView(this);
        status.setText("先读取第一台手机画面");
        status.setTextColor(0xffb9c6da);
        status.setTextSize(12);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));

        controlStatus = new TextView(this);
        controlStatus.setText("蓝牙未连接");
        controlStatus.setTextColor(0xffb9c6da);
        controlStatus.setTextSize(12);
        root.addView(controlStatus, new LinearLayout.LayoutParams(-1, dp(26)));

        LinearLayout pairRow = new LinearLayout(this);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);
        pairStatus = new TextView(this);
        pairStatus.setText("配对状态：" + HidBleClient.pairedStateLabel());
        pairStatus.setTextColor(0xffb9c6da);
        pairStatus.setTextSize(12);
        pairStatus.setGravity(Gravity.CENTER_VERTICAL);
        pairRow.addView(pairStatus, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button forget = new Button(this);
        forget.setText("忘记配对");
        forget.setOnClickListener(v -> forgetPairing());
        pairRow.addView(forget, new LinearLayout.LayoutParams(0, dp(40), 1));
        root.addView(pairRow, new LinearLayout.LayoutParams(-1, dp(44)));

        frame = new FrameView(this);
        frame.setOnTouchListener((view, event) -> onFrameTouch(event));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void startFrames() {
        String base = baseUrl();
        if (base.isEmpty()) { toast("请先输入机器人画面地址"); return; }
        if (framePolling && base.equals(activeFrameBase)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(URL_KEY, base).apply();
        activeFrameBase = base;
        framePolling = true;
        status.setText("正在读取机器人画面…");
        worker.execute(() -> {
            try {
                HttpURLConnection connection = open(base + "/info");
                JSONObject info;
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    info = new JSONObject(bytes.toString("UTF-8"));
                } finally { connection.disconnect(); }
                targetWidth = info.optInt("width", 0);
                targetHeight = info.optInt("height", 0);
                if (targetWidth < 2 || targetHeight < 2) throw new IllegalStateException("画面尺寸无效");
                runOnUiThread(() -> status.setText("画面 " + targetWidth + " × " + targetHeight + " · HTTP 仅传画面"));
                pollFrame(base);
                worker.scheduleWithFixedDelay(() -> pollFrame(base), 350, 350, TimeUnit.MILLISECONDS);
            } catch (Exception error) {
                framePolling = false;
                activeFrameBase = "";
                runOnUiThread(() -> status.setText("画面连接失败：" + error.getMessage()));
            }
        });
    }

    private void pollFrame(String base) {
        if (!framePolling) return;
        HttpURLConnection connection = null;
        try {
            connection = open(base + "/screen.jpg?t=" + System.nanoTime());
            Bitmap bitmap;
            try (InputStream input = connection.getInputStream()) { bitmap = BitmapFactory.decodeStream(input); }
            if (bitmap != null) runOnUiThread(() -> frame.setBitmap(bitmap));
        } catch (Exception error) {
            runOnUiThread(() -> status.setText("画面更新失败：" + error.getMessage()));
        } finally { if (connection != null) connection.disconnect(); }
    }

    private HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2500);
        connection.setUseCaches(false);
        return connection;
    }

    private void toggleControl() {
        if (controlling) {
            controlling = false;
            touchPressed = false;
            pendingMove.set(null);
            controlButton.setEnabled(false);
            controlStatus.setText("正在松开触控并断开蓝牙…");
            bleWorker.execute(() -> {
                try { HidBleClient.up(); } catch (Exception ignored) {}
                HidBleClient.disconnect();
                controlConnectionOwned = false;
                mainHandler.post(() -> {
                    controlButton.setEnabled(true);
                    controlButton.setText("连接蓝牙触控");
                    controlStatus.setText("蓝牙已断开");
                });
            });
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLE_CONNECT);
            return;
        }
        startControl();
    }

    private void startControl() {
        controlButton.setEnabled(false);
        controlStatus.setText("正在本页内配对并连接 CH591D（无需系统配对）…");
        bleWorker.execute(() -> {
            try {
                String result = HidBleClient.pairAndConnect();
                controlConnectionOwned = true;
                controlling = true;
                mainHandler.post(() -> {
                    controlButton.setEnabled(true);
                    controlButton.setText("断开蓝牙触控");
                    controlStatus.setText(result + "；触控通过 BLE 直达设备");
                    refreshPairStatus();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    controlButton.setEnabled(true);
                    controlStatus.setText("蓝牙连接失败：" + error.getMessage());
                    refreshPairStatus();
                });
            }
        });
    }

    private void forgetPairing() {
        pairStatus.setText("配对状态：正在忘记…");
        bleWorker.execute(() -> {
            boolean ok = HidBleClient.forget();
            mainHandler.post(() -> {
                pairStatus.setText("配对状态：" + HidBleClient.pairedStateLabel());
                controlStatus.setText(ok ? "已在 App 内解除配对" : "解除配对失败");
                if (controlling) {
                    controlling = false;
                    controlConnectionOwned = false;
                    controlButton.setText("连接蓝牙触控");
                }
            });
        });
    }

    private void refreshPairStatus() {
        if (pairStatus != null) pairStatus.setText("配对状态：" + HidBleClient.pairedStateLabel());
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_BLE_CONNECT && results.length > 0) {
            boolean granted = true;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) startControl();
            else controlStatus.setText("需要蓝牙连接权限才能发送触控命令");
        }
    }

    private boolean onFrameTouch(MotionEvent event) {
        if (!controlling || targetWidth < 2 || targetHeight < 2) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int[] point = frame.map(event.getX(), event.getY(), targetWidth, targetHeight);
                if (point == null) return false;
                frame.getParent().requestDisallowInterceptTouchEvent(true);
                touchPressed = true;
                int width = targetWidth, height = targetHeight;
                bleWorker.execute(() -> sendTouch(() ->
                        HidBleClient.downInSpace(point[0], point[1], width, height)));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!touchPressed) return true;
                int[] point = frame.map(event.getX(), event.getY(), targetWidth, targetHeight);
                if (point == null) return true;
                pendingMove.set(new int[]{point[0], point[1], targetWidth, targetHeight});
                scheduleMoveDrain();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchPressed = false;
                pendingMove.set(null);
                bleWorker.execute(() -> sendTouch(HidBleClient::up));
                frame.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }

    private void scheduleMoveDrain() {
        if (moveDrainScheduled.compareAndSet(false, true))
            bleWorker.execute(this::drainMoves);
    }

    private void drainMoves() {
        try {
            int[] move;
            while ((move = pendingMove.getAndSet(null)) != null) {
                int[] current = move;
                sendTouch(() -> HidBleClient.moveInSpace(current[0], current[1], current[2], current[3]));
            }
        } finally {
            moveDrainScheduled.set(false);
            if (pendingMove.get() != null) scheduleMoveDrain();
        }
    }

    private void sendTouch(TouchCommand command) {
        try {
            command.send();
        } catch (Exception error) {
            controlling = false;
            mainHandler.post(() -> {
                controlButton.setText("连接蓝牙触控");
                controlStatus.setText("触控发送失败：" + error.getMessage());
            });
        }
    }

    private String baseUrl() {
        String value = frameBase.getText().toString().trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    @Override protected void onDestroy() {
        framePolling = false;
        if (touchPressed || controlConnectionOwned) {
            pendingMove.set(null);
            bleWorker.execute(() -> {
                if (touchPressed) {
                    try { HidBleClient.up(); } catch (Exception ignored) {}
                }
                if (controlConnectionOwned) HidBleClient.disconnect();
            });
        }
        worker.shutdownNow();
        bleWorker.shutdown();
        super.onDestroy();
    }

    private interface TouchCommand { String send() throws Exception; }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

    private static final class FrameView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;
        FrameView(Context context) { super(context); setBackgroundColor(Color.rgb(25, 36, 55)); }
        void setBitmap(Bitmap value) {
            Bitmap old = bitmap;
            bitmap = value;
            invalidate();
            if (old != null && old != value && !old.isRecycled()) old.recycle();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            float left = (getWidth() - width) / 2f, top = (getHeight() - height) / 2f;
            canvas.drawBitmap(bitmap, null, new android.graphics.RectF(left, top, left + width, top + height), paint);
        }
        int[] map(float x, float y, int targetWidth, int targetHeight) {
            if (bitmap == null) return null;
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            float left = (getWidth() - width) / 2f, top = (getHeight() - height) / 2f;
            if (x < left || y < top || x > left + width || y > top + height) return null;
            int px = Math.round((x - left) * targetWidth / width);
            int py = Math.round((y - top) * targetHeight / height);
            return new int[]{Math.max(0, Math.min(targetWidth - 1, px)), Math.max(0, Math.min(targetHeight - 1, py))};
        }
    }
}
