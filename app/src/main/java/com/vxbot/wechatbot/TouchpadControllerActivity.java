package com.vxbot.wechatbot;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Pair-free Android BLE advertiser controller. The robot HTTP endpoint is used only for frames. */
public final class TouchpadControllerActivity extends Activity {
    private static final String PREFS = "touchpad_controller";
    private static final String URL_KEY = "frame_base_url";
    private static final int REQUEST_ADVERTISE = 7411;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TouchBroadcastSender sender = new TouchBroadcastSender();
    private EditText frameBase;
    private TextView status;
    private Button broadcastButton;
    private FrameView frame;
    private volatile int targetWidth;
    private volatile int targetHeight;
    private volatile boolean broadcasting;
    private volatile boolean framePolling;
    private volatile String activeFrameBase = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
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
        help.setText("触控坐标由本机 BLE 广播直达 CH591D，无需配对。下方 HTTP 地址只用于读取机器人手机画面。把机器人状态页中的触控板地址粘贴到这里。目标编号："
                + String.format(java.util.Locale.US, "%04X", TouchBroadcastSender.TARGET_ID));
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
        broadcastButton = new Button(this);
        broadcastButton.setText("开始 BLE 广播");
        broadcastButton.setOnClickListener(v -> toggleBroadcast());
        buttons.addView(broadcastButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, dp(52)));

        status = new TextView(this);
        status.setText("先读取画面，再开始广播控制");
        status.setTextColor(0xffb9c6da);
        status.setTextSize(12);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));

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

    private void toggleBroadcast() {
        if (broadcasting) {
            sender.up();
            worker.schedule(sender::stop, 180, TimeUnit.MILLISECONDS);
            broadcasting = false;
            broadcastButton.setText("开始 BLE 广播");
            status.setText("正在发送抬起并停止广播");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_ADVERTISE);
            return;
        }
        startBroadcast();
    }

    private void startBroadcast() {
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { toast("请先打开蓝牙"); return; }
            sender.start(this, message -> runOnUiThread(() -> status.setText(message)));
            broadcasting = true;
            broadcastButton.setText("停止广播");
        } catch (Exception error) { status.setText(error.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_ADVERTISE && results.length > 0) {
            boolean granted = true;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) startBroadcast();
            else status.setText("需要蓝牙连接与广播权限才能发送触控命令");
        }
    }

    private boolean onFrameTouch(MotionEvent event) {
        if (!broadcasting || targetWidth < 2 || targetHeight < 2) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int[] point = frame.map(event.getX(), event.getY(), targetWidth, targetHeight);
                if (point == null) return false;
                frame.getParent().requestDisallowInterceptTouchEvent(true);
                sender.down(point[0], point[1], targetWidth, targetHeight);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int[] point = frame.map(event.getX(), event.getY(), targetWidth, targetHeight);
                if (point == null) return true;
                sender.move(point[0], point[1], targetWidth, targetHeight);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                sender.up();
                frame.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }

    private String baseUrl() {
        String value = frameBase.getText().toString().trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    @Override protected void onDestroy() {
        framePolling = false;
        if (broadcasting) {
            sender.up();
            mainHandler.postDelayed(sender::stop, 180);
        }
        worker.shutdownNow();
        super.onDestroy();
    }

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
