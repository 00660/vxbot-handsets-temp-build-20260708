package com.ibox.nativepanel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs the native task engine while the application is backgrounded. */
public final class PanelSyncService extends Service {
    private static final String PREFS = "ibox_native_panel";
    private static final String CHANNEL_ID = "ibox_panel_sync";
    private static final int NOTIFICATION_ID = 304;
    private ScheduledExecutorService scheduler;
    private volatile boolean stopped;
    private NativeEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("正在启动本机任务引擎"));
        engine = new NativeEngine(this);
        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::sync, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void sync() {
        if (stopped) return;
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONObject state = engine.backgroundSummary();
            int watchCount = state.optInt("watchCount", 0);
            int quantCount = state.optInt("quantCount", 0);
            int tradeCount = state.optInt("tradeCount", 0);
            int firstCount = state.optInt("firstSaleCount", 0);
            String summary = "行情 " + watchCount + " · 量化 " + quantCount + " · 交易 " + tradeCount + " · 首发 " + firstCount;
            preferences.edit()
                    .putString("last_sync_summary", summary)
                    .putLong("last_sync_at", System.currentTimeMillis())
                    .apply();
            update(summary);
        } catch (Exception error) {
            String message = error.getMessage();
            update("同步失败" + (message == null || message.isEmpty() ? "" : " · " + message));
        }
    }

    private void tick() {
        if (stopped) return;
        try {
            engine.tick();
        } catch (Exception ignored) {
            // Individual task state records the error; the status worker stays alive.
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "iBox 面板同步", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("后台同步行情和任务状态");
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_status_sync)
                .setContentTitle("iBox 原生面板")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private void update(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }
}
