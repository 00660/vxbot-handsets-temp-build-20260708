package com.vxbot.wechatbot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class HidBleClient {
    private static final String TAG = "HsHidBle";
    private static final String DEVICE_ADDRESS = "DC:32:62:9B:82:2D";
    private static final UUID CONTROL_SERVICE = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    private static final UUID CONTROL_COMMAND = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb");
    private static final int CONNECT_TIMEOUT_SECONDS = 20;
    private static final int WRITE_TIMEOUT_SECONDS = 8;
    private static final int MOUSE_LEFT = 1;
    private static final int MOUSE_RIGHT = 2;
    private static final int MOUSE_MIDDLE = 4;

    private static final Object STATE_LOCK = new Object();
    private static final Object WRITE_LOCK = new Object();
    private static Context appContext;
    private static BluetoothGatt gatt;
    private static BluetoothGattCharacteristic commandCharacteristic;
    private static CountDownLatch connectionLatch;
    private static CountDownLatch writeLatch;
    private static int writeStatus = BluetoothGatt.GATT_FAILURE;
    private static UUID waitingForWrite;
    private static boolean connecting;
    private static volatile boolean bondRequested;

    /* ---- In-app pairing management (replaces the system pairing dialog) ----
     * FFF5 is ENCRYPT_WRITE so a BLE bond is mandatory at the link layer. But we
     * hide the OS pairing UI entirely: the app triggers the bond itself,
     * auto-confirms the SMP pairing request (Just Works), and manages the paired
     * state / forget / reconnect from inside the app. The user never sees the
     * Android system Bluetooth pairing screen. */
    private static volatile BluetoothDevice managedDevice;
    private static volatile boolean autoConfirmBond = true;
    private static BroadcastReceiver pairReceiver;
    private static Context receiverContext;

    private static final BroadcastReceiver PAIR_RECEIVER = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
            if (device == null || managedDevice == null ||
                    !DEVICE_ADDRESS.equalsIgnoreCase(device.getAddress()))
                return;
            switch (action) {
                case BluetoothDevice.ACTION_PAIRING_REQUEST:
                    if (!autoConfirmBond) return;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            device.setPairingConfirmation(true);
                        }
                        abortBroadcast();
                        Log.i(TAG, "Auto-confirmed in-app pairing request");
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Auto-confirm pairing failed: " + e.getMessage());
                    }
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    int state = device.getBondState();
                    Log.i(TAG, "BOND_STATE_CHANGED -> " + state);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        bondRequested = false;
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        bondRequested = false;
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private HidBleClient() {}

    public static void initialize(Context context) {
        appContext = context.getApplicationContext();
        registerPairReceiver(appContext);
    }

    private static synchronized void registerPairReceiver(Context context) {
        if (pairReceiver != null) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.setPriority(1000);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(PAIR_RECEIVER, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(PAIR_RECEIVER, filter);
            }
            pairReceiver = PAIR_RECEIVER;
            receiverContext = context;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not register pairing receiver: " + e.getMessage());
        }
    }

    /* ================= In-app pairing management API ================= */

    /** True once the robot device is bonded (paired) — driven entirely by the app. */
    public static boolean isPaired() {
        try {
            BluetoothDevice d = remoteDevice();
            return d != null && d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isBonding() {
        try {
            BluetoothDevice d = remoteDevice();
            return d != null && d.getBondState() == BluetoothDevice.BOND_BONDING;
        } catch (Exception e) {
            return false;
        }
    }

    public static String pairedStateLabel() {
        try {
            BluetoothDevice d = remoteDevice();
            if (d == null) return "蓝牙不可用";
            switch (d.getBondState()) {
                case BluetoothDevice.BOND_BONDED: return "已配对";
                case BluetoothDevice.BOND_BONDING: return "正在配对…";
                default: return "未配对";
            }
        } catch (Exception e) {
            return "配对状态未知";
        }
    }

    private static BluetoothDevice remoteDevice() {
        Context context = appContext;
        if (context == null) return null;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return null;
        try {
            return adapter.getRemoteDevice(DEVICE_ADDRESS);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Do the whole pairing from inside the app: bond (auto-confirmed, no system
     * dialog) then connect the GATT link. Returns a user-facing status string.
     */
    public static String pairAndConnect() throws Exception {
        pair() ;                       /* ensure bonded first (in-app, auto-confirm) */
        return connect();              /* connect + verify encrypted link */
    }

    /** Trigger the bond from inside the app and block until bonded (or timeout). */
    public static boolean pair() throws Exception {
        Context context = appContext;
        if (context == null) throw new IOException("BLE HID client is not initialized");
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Bluetooth permission is required");
        BluetoothDevice device = remoteDevice();
        if (device == null) throw new IOException("Bluetooth is unavailable");
        managedDevice = device;
        if (device.getBondState() == BluetoothDevice.BOND_BONDED)
            return true;
        autoConfirmBond = true;
        if (device.getBondState() != BluetoothDevice.BOND_BONDING) {
            try {
                bondRequested = device.createBond();
            } catch (SecurityException e) {
                throw new IOException("无法发起配对：" + e.getMessage());
            }
        }
        for (int i = 0; i < 80; i++) {           /* up to 20s */
            int state = device.getBondState();
            if (state == BluetoothDevice.BOND_BONDED) return true;
            if (state == BluetoothDevice.BOND_NONE && !bondRequested && i > 4) return false;
            Thread.sleep(250L);
        }
        return device.getBondState() == BluetoothDevice.BOND_BONDED;
    }

    /** Forget the pairing from inside the app (removes the OS bond too, on purpose). */
    public static boolean forget() {
        BluetoothDevice device = managedDevice != null ? managedDevice : remoteDevice();
        if (device == null) return false;
        try {
            disconnect();
            Method removeBond = device.getClass().getMethod("removeBond");
            removeBond.setAccessible(true);
            Object ok = removeBond.invoke(device);
            return ok instanceof Boolean ? (Boolean) ok : true;
        } catch (Exception e) {
            Log.w(TAG, "forget() failed: " + e.getMessage());
            return false;
        }
    }

    public static void connectAsync() {
        Thread thread = new Thread(() -> {
            try {
                ensureConnected();
                Log.i(TAG, "Connected to " + DEVICE_ADDRESS);
            } catch (Exception e) {
                Log.w(TAG, "BLE HID unavailable: " + e.getMessage());
            }
        }, "hs-hid-ble-connect");
        thread.setDaemon(true);
        thread.start();
    }

    public static String connect() throws Exception {
        BluetoothGatt activeGatt = ensureConnected();
        BluetoothDevice device = activeGatt.getDevice();
        if (device.getBondState() != BluetoothDevice.BOND_BONDED && !awaitBond(activeGatt))
            throw new IOException("等待 CH591D 蓝牙配对超时");
        return "BLE GATT 已连接并完成配对";
    }

    public static void disconnect() {
        BluetoothGatt activeGatt;
        CountDownLatch ready;
        CountDownLatch pendingWrite;
        synchronized (STATE_LOCK) {
            activeGatt = gatt;
            gatt = null;
            commandCharacteristic = null;
            connecting = false;
            bondRequested = false;
            ready = connectionLatch;
            connectionLatch = null;
            pendingWrite = writeLatch;
            writeLatch = null;
            waitingForWrite = null;
        }
        if (ready != null) ready.countDown();
        if (pendingWrite != null) pendingWrite.countDown();
        if (activeGatt != null) {
            try { activeGatt.disconnect(); } catch (SecurityException ignored) {}
            try { activeGatt.close(); } catch (SecurityException ignored) {}
        }
    }

    public static String tap(int x, int y) throws Exception {
        Point point = normalize(x, y);
        return write(new byte[]{1, low(point.x), high(point.x), low(point.y), high(point.y)});
    }

    public static String down(int x, int y) throws Exception {
        Point point = normalize(x, y);
        return write(new byte[]{2, low(point.x), high(point.x), low(point.y), high(point.y)});
    }

    public static String downInSpace(int x, int y, int width, int height) throws Exception {
        Point point = normalize(x, y, width, height);
        return write(new byte[]{2, low(point.x), high(point.x), low(point.y), high(point.y)});
    }

    public static String move(int x, int y) throws Exception {
        Point point = normalize(x, y);
        return write(new byte[]{3, low(point.x), high(point.x), low(point.y), high(point.y)});
    }

    public static String moveInSpace(int x, int y, int width, int height) throws Exception {
        Point point = normalize(x, y, width, height);
        return write(new byte[]{3, low(point.x), high(point.x), low(point.y), high(point.y)});
    }

    public static String up() throws Exception {
        return write(new byte[]{4});
    }

    public static String mouse(int buttons, int dx, int dy, int wheel) throws Exception {
        if ((buttons & ~0x07) != 0)
            throw new IOException("Mouse buttons must use left=1, right=2, middle=4");
        return write(new byte[]{7, (byte) buttons, signedMouseDelta(dx),
                signedMouseDelta(dy), signedMouseDelta(wheel)});
    }

    public static String mouseMove(int dx, int dy) throws Exception {
        return mouse(0, dx, dy, 0);
    }

    public static String mouseButton(int buttons) throws Exception {
        return mouse(buttons, 0, 0, 0);
    }

    public static String mouseClick(int button) throws Exception {
        if (button != MOUSE_LEFT && button != MOUSE_RIGHT && button != MOUSE_MIDDLE)
            throw new IOException("Mouse click button must be left=1, right=2, or middle=4");
        try {
            mouseButton(button);
            Thread.sleep(35L);
        } finally {
            mouseButton(0);
        }
        return "BLE HID sent";
    }

    public static String mouseScroll(int amount) throws Exception {
        return mouse(0, 0, 0, amount);
    }

    private static byte signedMouseDelta(int value) {
        return (byte) Math.max(-127, Math.min(127, value));
    }

    public static String swipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        Point start = normalize(x1, y1);
        Point end = normalize(x2, y2);
        int duration = Math.max(20, Math.min(2000, durationMs));
        return write(new byte[]{5, low(start.x), high(start.x), low(start.y), high(start.y),
                low(end.x), high(end.x), low(end.y), high(end.y), low(duration), high(duration)});
    }

    public static String key(String name) throws Exception {
        String key = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        int code;
        switch (key) {
            case "BACK":
            case "ESC":
            case "ESCAPE": code = KeyEvent.KEYCODE_ESCAPE; break;
            case "HOME": code = KeyEvent.KEYCODE_HOME; break;
            case "MENU": code = KeyEvent.KEYCODE_MENU; break;
            case "ENTER":
            case "RETURN": code = KeyEvent.KEYCODE_ENTER; break;
            case "BACKSPACE":
            case "DEL": code = KeyEvent.KEYCODE_DEL; break;
            case "DELETE": code = KeyEvent.KEYCODE_FORWARD_DEL; break;
            case "TAB": code = KeyEvent.KEYCODE_TAB; break;
            case "SPACE": code = KeyEvent.KEYCODE_SPACE; break;
            case "UP": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "DOWN": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "LEFT": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "RIGHT": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            default: throw new IOException("Unsupported BLE HID key: " + name);
        }
        return keyCode(code);
    }

    public static String keyCode(int androidKeyCode) throws Exception {
        if (androidKeyCode == 279) // Android KEYCODE_PASTE => Ctrl+V on a HID keyboard.
            return keyChord(0x01, KeyEvent.KEYCODE_V);
        return keyChord(0, androidKeyCode);
    }

    public static String keyChord(int modifiers, int androidKeyCode) throws Exception {
        if ((modifiers & ~0xff) != 0)
            throw new IOException("HID keyboard modifiers must fit in one byte");
        int usage = hidUsage(androidKeyCode);
        if (usage == 0)
            throw new IOException("Unsupported Android key code for BLE HID: " + androidKeyCode);
        String result = write(new byte[]{6, (byte) modifiers, (byte) usage});
        Thread.sleep(35L);
        write(new byte[]{6, 0, 0});
        return result;
    }

    private static int hidUsage(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
            return 0x04 + keyCode - KeyEvent.KEYCODE_A;
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9)
            return 0x1e + keyCode - KeyEvent.KEYCODE_1;
        if (keyCode == KeyEvent.KEYCODE_0)
            return 0x27;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK: return 0x29;
            case KeyEvent.KEYCODE_ENTER: return 0x28;
            case KeyEvent.KEYCODE_ESCAPE: return 0x29;
            case KeyEvent.KEYCODE_DEL: return 0x2a;
            case KeyEvent.KEYCODE_TAB: return 0x2b;
            case KeyEvent.KEYCODE_SPACE: return 0x2c;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0x4c;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x4f;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x50;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0x52;
            case KeyEvent.KEYCODE_HOME: return 0x4a;
            case KeyEvent.KEYCODE_MENU: return 0x65;
            default: return 0;
        }
    }

    private static Point normalize(int x, int y) throws IOException {
        Context context = appContext;
        if (context == null)
            throw new IOException("BLE HID client is not initialized");
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null)
            throw new IOException("Display is unavailable");
        Display display = manager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return normalize(x, y, size.x, size.y);
    }

    private static Point normalize(int x, int y, int width, int height) throws IOException {
        if (width <= 1 || height <= 1)
            throw new IOException("Display size is unavailable");
        int normalizedX = Math.max(0, Math.min(32767,
                Math.round(Math.max(0, Math.min(width - 1, x)) * 32767f / (width - 1))));
        int normalizedY = Math.max(0, Math.min(32767,
                Math.round(Math.max(0, Math.min(height - 1, y)) * 32767f / (height - 1))));
        return new Point(normalizedX, normalizedY);
    }

    private static byte low(int value) {
        return (byte) (value & 0xff);
    }

    private static byte high(int value) {
        return (byte) ((value >>> 8) & 0xff);
    }

    private static String write(byte[] value) throws Exception {
        synchronized (WRITE_LOCK) {
            BluetoothGatt activeGatt = ensureConnected();
            BluetoothGattCharacteristic characteristic;
            CountDownLatch completion = new CountDownLatch(1);
            synchronized (STATE_LOCK) {
                characteristic = commandCharacteristic;
                writeLatch = completion;
                waitingForWrite = CONTROL_COMMAND;
                writeStatus = BluetoothGatt.GATT_FAILURE;
            }

            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = activeGatt.writeCharacteristic(characteristic, value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(value);
                started = activeGatt.writeCharacteristic(characteristic);
            }
            if (!started) {
                clearWriteWaiter(completion);
                throw new IOException("BLE HID GATT write could not start");
            }
            if (!completion.await(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                clearWriteWaiter(completion);
                throw new IOException("BLE HID GATT write timed out");
            }
            int completedStatus;
            synchronized (STATE_LOCK) {
                completedStatus = writeStatus;
            }
            if (completedStatus != BluetoothGatt.GATT_SUCCESS) {
                if ((completedStatus == 5 || completedStatus == 15) && awaitBond(activeGatt))
                    return write(value);
                throw new IOException("BLE HID GATT write failed, status=" + completedStatus);
            }
            return "BLE HID sent";
        }
    }

    private static void clearWriteWaiter(CountDownLatch expected) {
        synchronized (STATE_LOCK) {
            if (writeLatch == expected) {
                writeLatch = null;
                waitingForWrite = null;
            }
        }
    }

    private static BluetoothGatt ensureConnected() throws Exception {
        Context context = appContext;
        if (context == null)
            throw new IOException("BLE HID client is not initialized");
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Bluetooth permission is required");

        CountDownLatch latch;
        synchronized (STATE_LOCK) {
            if (gatt != null && commandCharacteristic != null && !connecting)
                return gatt;
            if (connecting && connectionLatch != null) {
                latch = connectionLatch;
            } else {
                BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null || !adapter.isEnabled())
                    throw new IOException("Bluetooth is disabled");
                BluetoothDevice device = adapter.getRemoteDevice(DEVICE_ADDRESS);
                latch = new CountDownLatch(1);
                connectionLatch = latch;
                connecting = true;
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) {
                    connecting = false;
                    connectionLatch = null;
                    throw new IOException("Could not open BLE connection");
                }
            }
        }
        if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            BluetoothGatt timedOut = null;
            synchronized (STATE_LOCK) {
                if (connectionLatch == latch) {
                    connecting = false;
                    timedOut = gatt;
                }
            }
            if (timedOut != null)
                failConnection(timedOut, "Timed out connecting to " + DEVICE_ADDRESS);
            throw new IOException("Timed out connecting to " + DEVICE_ADDRESS);
        }
        synchronized (STATE_LOCK) {
            if (gatt == null || commandCharacteristic == null || connecting)
                throw new IOException("BLE HID GATT service is unavailable");
            return gatt;
        }
    }

    private static final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!source.discoverServices())
                    failConnection(source, "GATT service discovery could not start");
                return;
            }
            failConnection(source, "BLE disconnected, status=" + status);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt source, int status) {
            BluetoothGattCharacteristic characteristic = null;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = source.getService(CONTROL_SERVICE);
                if (service != null)
                    characteristic = service.getCharacteristic(CONTROL_COMMAND);
            }
            if (characteristic == null) {
                failConnection(source, "BLE device did not expose the HID control characteristic");
                return;
            }
            CountDownLatch latch;
            synchronized (STATE_LOCK) {
                if (gatt != source)
                    return;
                commandCharacteristic = characteristic;
                connecting = false;
                latch = connectionLatch;
                connectionLatch = null;
            }
            if (latch != null)
                latch.countDown();
            try {
                managedDevice = source.getDevice();
                if (source.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                    autoConfirmBond = true;
                    bondRequested = source.getDevice().createBond();
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Could not begin HID pairing: " + e.getMessage());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt source,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            completeWrite(source, characteristic, status);
        }

        private void completeWrite(BluetoothGatt source,
                                   BluetoothGattCharacteristic characteristic,
                                   int status) {
            CountDownLatch latch = null;
            synchronized (STATE_LOCK) {
                if (source == gatt && CONTROL_COMMAND.equals(characteristic.getUuid()) &&
                        CONTROL_COMMAND.equals(waitingForWrite)) {
                    writeStatus = status;
                    latch = writeLatch;
                    writeLatch = null;
                    waitingForWrite = null;
                }
            }
            if (latch != null)
                latch.countDown();
        }
    };

    private static boolean awaitBond(BluetoothGatt activeGatt) throws InterruptedException {
        try {
            BluetoothDevice device = activeGatt.getDevice();
            managedDevice = device;
            if (device.getBondState() == BluetoothDevice.BOND_NONE && !bondRequested) {
                autoConfirmBond = true;
                bondRequested = device.createBond();
            }
            for (int i = 0; i < 60; i++) {
                int state = device.getBondState();
                if (state == BluetoothDevice.BOND_BONDED)
                    return true;
                Thread.sleep(250L);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth pairing permission is missing");
        }
        return false;
    }

    private static void failConnection(BluetoothGatt source, String reason) {
        CountDownLatch ready;
        CountDownLatch pendingWrite;
        synchronized (STATE_LOCK) {
            if (gatt != source)
                return;
            gatt = null;
            commandCharacteristic = null;
            connecting = false;
            bondRequested = false;
            ready = connectionLatch;
            connectionLatch = null;
            pendingWrite = writeLatch;
            writeLatch = null;
            waitingForWrite = null;
        }
        try {
            source.close();
        } catch (SecurityException ignored) {
        }
        if (ready != null)
            ready.countDown();
        if (pendingWrite != null)
            pendingWrite.countDown();
        Log.w(TAG, reason);
    }
}
