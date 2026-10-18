package com.vxbot.wechatbot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;

/** Sends pairing-free touch reports as BLE service-data advertisements. */
final class TouchBroadcastSender {
    static final int TARGET_ID = 0x829B;
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff6-0000-1000-8000-00805f9b34fb");
    private static final int ACTION_DOWN = 1;
    private static final int ACTION_MOVE = 2;
    private static final int ACTION_UP = 3;

    private BluetoothLeAdvertiser advertiser;
    private AdvertisingSet set;
    private boolean starting;
    private boolean updateInFlight;
    private int duplicateBudget;
    private Packet pending;
    private Packet lastSent;
    private int sequence;
    private int session;
    private Callback callback;
    private AdvertisingSetCallback setCallback;

    interface Callback {
        void onStatus(String status);
    }

    synchronized void start(Context context, Callback statusCallback) throws IOException {
        if (set != null || starting) return;
        callback = statusCallback;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("请先打开蓝牙");
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported())
            throw new IOException("此设备不支持 BLE 广播");
        byte[] random = new byte[2];
        new SecureRandom().nextBytes(random);
        session = ((random[0] & 0xff) << 8) | (random[1] & 0xff);
        if (session == 0) session = 1;
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build();
        starting = true;
        try {
            setCallback = new AdvertisingSetCallback() {
                        @Override public void onAdvertisingSetStarted(AdvertisingSet advertisingSet,
                                                                     int txPower, int status) {
                            synchronized (TouchBroadcastSender.this) {
                                starting = false;
                                if (status == ADVERTISE_SUCCESS) {
                                    set = advertisingSet;
                                    report("已开始 BLE 广播（无需配对）");
                                    flush();
                                } else {
                                    report("BLE 广播启动失败，状态 " + status);
                                }
                            }
                        }

                        @Override public void onAdvertisingDataSet(AdvertisingSet advertisingSet,
                                                                    int status) {
                            synchronized (TouchBroadcastSender.this) {
                                updateInFlight = false;
                                if (status != ADVERTISE_SUCCESS) report("广播更新失败，状态 " + status);
                                if (pending == null && duplicateBudget > 1 && set != null) {
                                    duplicateBudget--;
                                    try { set.setAdvertisingData(data(lastSent)); updateInFlight = true; }
                                    catch (SecurityException | IllegalStateException error) { updateInFlight = false; report("BLE 广播重发失败：" + error.getMessage()); }
                                } else {
                                    duplicateBudget = 0;
                                    flush();
                                }
                            }
                        }
                    };
            advertiser.startAdvertisingSet(parameters, data(new Packet(ACTION_UP, 0, 0)), null,
                    null, null, setCallback);
        } catch (SecurityException | IllegalStateException error) {
            starting = false;
            throw new IOException("无法启动 BLE 广播：" + error.getMessage(), error);
        }
    }

    synchronized void down(int x, int y, int width, int height) {
        enqueue(ACTION_DOWN, normalize(x, width), normalize(y, height));
    }

    synchronized void move(int x, int y, int width, int height) {
        enqueue(ACTION_MOVE, normalize(x, width), normalize(y, height));
    }

    synchronized void up() {
        enqueue(ACTION_UP, 0, 0);
    }

    synchronized void stop() {
        pending = null;
        if (advertiser != null && set != null) {
            try { advertiser.stopAdvertisingSet(setCallback); }
            catch (SecurityException ignored) { }
        }
        set = null;
        starting = false;
        updateInFlight = false;
        advertiser = null;
        setCallback = null;
        report("BLE 广播已停止");
    }

    private void enqueue(int action, int x, int y) {
        pending = new Packet(action, x, y);
        duplicateBudget = action == ACTION_UP ? 4 : action == ACTION_DOWN ? 2 : 1;
        flush();
    }

    private void flush() {
        if (set == null || updateInFlight || pending == null) return;
        Packet packet = pending;
        pending = null;
        packet.sequence = sequence++ & 0xff;
        packet.session = session;
        lastSent = packet;
        updateInFlight = true;
        try {
            set.setAdvertisingData(data(lastSent));
        } catch (SecurityException | IllegalStateException error) {
            updateInFlight = false;
            report("BLE 广播发送失败：" + error.getMessage());
        }
    }

    private AdvertiseData data(Packet packet) {
        byte[] payload = new byte[13];
        payload[0] = 0x48;
        payload[1] = 0x53;
        payload[2] = (byte) TARGET_ID;
        payload[3] = (byte) (TARGET_ID >>> 8);
        payload[4] = (byte) packet.session;
        payload[5] = (byte) (packet.session >>> 8);
        payload[6] = (byte) packet.sequence;
        payload[7] = (byte) packet.action;
        payload[8] = (byte) packet.x;
        payload[9] = (byte) (packet.x >>> 8);
        payload[10] = (byte) packet.y;
        payload[11] = (byte) (packet.y >>> 8);
        payload[12] = crc8(payload, 12);
        return new AdvertiseData.Builder()
                .addServiceData(new ParcelUuid(SERVICE_UUID), payload)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();
    }

    private static int normalize(int coordinate, int dimension) {
        if (dimension <= 1) return 0;
        int clamped = Math.max(0, Math.min(dimension - 1, coordinate));
        return Math.round(clamped * 32767f / (dimension - 1));
    }

    private static byte crc8(byte[] data, int length) {
        int crc = 0;
        for (int i = 0; i < length; ++i) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; ++bit)
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff;
        }
        return (byte) crc;
    }

    private void report(String status) {
        Callback current = callback;
        if (current != null) current.onStatus(status);
    }

    private static final class Packet {
        final int action, x, y;
        int sequence, session;
        Packet(int action, int x, int y) { this.action = action; this.x = x; this.y = y; }
    }
}
