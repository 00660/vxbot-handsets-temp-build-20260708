package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Moves the BLE mouse to an OCR coordinate using pointer position feedback, then clicks it. */
public final class HidMouseController {
    private static final int LEFT_BUTTON = 1;
    private static final int MOVE_LIMIT = 127;
    private static final int POSITION_TOLERANCE_PX = 6;
    private static final int MAX_MOVE_STEPS = 36;
    private static final long POINTER_EVENT_TIMEOUT_MS = 700L;

    private HidMouseController() {}

    public static Point clickAt(Context context, HsClient hs, int targetX, int targetY) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("BLE mouse positioning must run off the main thread");
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            throw new IOException("Overlay permission is required to track the BLE mouse pointer");
        }
        Point display = displaySize(context);
        if (targetX < 0 || targetY < 0 || targetX >= display.x || targetY >= display.y) {
            throw new IOException("Mouse target is outside the display: " + targetX + "," + targetY);
        }

        PointerCapture capture = new PointerCapture(context, display);
        Point target = new Point(targetX, targetY);
        Point positioned;
        try {
            capture.show();
            positioned = moveTo(hs, capture, target);
        } finally {
            capture.hide();
        }

        Thread.sleep(80L);
        hs.mouseClick(LEFT_BUTTON);
        return positioned;
    }

    private static Point moveTo(HsClient hs, PointerCapture capture, Point target) throws Exception {
        Point current = null;
        int[][] probes = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] probe : probes) {
            int version = capture.version();
            hs.mouseMove(probe[0], probe[1]);
            current = capture.awaitPosition(version, POINTER_EVENT_TIMEOUT_MS);
            if (current != null) {
                break;
            }
        }
        if (current == null) {
            throw new IOException("Android did not report a BLE mouse hover position");
        }

        double scaleX = 1d;
        double scaleY = 1d;
        int stalledMoves = 0;
        for (int step = 0; step < MAX_MOVE_STEPS; step++) {
            int errorX = target.x - current.x;
            int errorY = target.y - current.y;
            if (Math.abs(errorX) <= POSITION_TOLERANCE_PX
                    && Math.abs(errorY) <= POSITION_TOLERANCE_PX) {
                return current;
            }

            int dx = movementForError(errorX, scaleX);
            int dy = movementForError(errorY, scaleY);
            Point previous = current;
            int version = capture.version();
            hs.mouseMove(dx, dy);
            current = capture.awaitPosition(version, POINTER_EVENT_TIMEOUT_MS);
            if (current == null) {
                throw new IOException("BLE mouse pointer feedback timed out while moving");
            }

            int actualX = current.x - previous.x;
            int actualY = current.y - previous.y;
            boolean moved = actualX != 0 || actualY != 0;
            stalledMoves = moved ? 0 : stalledMoves + 1;
            if (stalledMoves >= 3) {
                throw new IOException("BLE mouse pointer stopped before reaching the target");
            }
            if (dx != 0 && actualX != 0 && Integer.signum(dx) == Integer.signum(actualX)) {
                scaleX = boundedScale(actualX / (double) dx);
            }
            if (dy != 0 && actualY != 0 && Integer.signum(dy) == Integer.signum(actualY)) {
                scaleY = boundedScale(actualY / (double) dy);
            }
        }
        throw new IOException("BLE mouse could not reach target; pointer=" + current.x + "," + current.y
                + " target=" + target.x + "," + target.y);
    }

    private static int movementForError(int error, double scale) {
        if (Math.abs(error) <= POSITION_TOLERANCE_PX) {
            return 0;
        }
        int delta = (int) Math.round(error / scale);
        if (delta == 0) {
            delta = Integer.signum(error);
        }
        return Math.max(-MOVE_LIMIT, Math.min(MOVE_LIMIT, delta));
    }

    private static double boundedScale(double value) {
        return Math.max(0.2d, Math.min(8d, value));
    }

    private static Point displaySize(Context context) throws IOException {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            throw new IOException("Display is unavailable");
        }
        Point size = new Point();
        manager.getDefaultDisplay().getRealSize(size);
        if (size.x <= 1 || size.y <= 1) {
            throw new IOException("Display size is unavailable");
        }
        return size;
    }

    private static final class PointerCapture {
        private final Context context;
        private final Point display;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final Object positionLock = new Object();
        private WindowManager windowManager;
        private View view;
        private WindowManager.LayoutParams params;
        private boolean added;
        private int x;
        private int y;
        private int eventVersion;

        PointerCapture(Context context, Point display) {
            this.context = context.getApplicationContext();
            this.display = new Point(display);
        }

        int version() {
            synchronized (positionLock) {
                return eventVersion;
            }
        }

        Point awaitPosition(int afterVersion, long timeoutMs) throws InterruptedException {
            long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
            synchronized (positionLock) {
                while (eventVersion <= afterVersion) {
                    long remaining = deadline - android.os.SystemClock.uptimeMillis();
                    if (remaining <= 0L) {
                        return null;
                    }
                    positionLock.wait(remaining);
                }
                return new Point(x, y);
            }
        }

        void show() throws Exception {
            runOnMain(() -> {
                windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager == null) {
                    throw new IllegalStateException("Window manager is unavailable");
                }
                int type = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                params = new WindowManager.LayoutParams(
                        display.x,
                        display.y,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                view = new View(context) {
                    @Override
                    public boolean onHoverEvent(MotionEvent event) {
                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_HOVER_ENTER
                                || action == MotionEvent.ACTION_HOVER_MOVE) {
                            updatePosition(event.getRawX(), event.getRawY());
                            return true;
                        }
                        return super.onHoverEvent(event);
                    }

                    @Override
                    public boolean onGenericMotionEvent(MotionEvent event) {
                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_HOVER_ENTER
                                || action == MotionEvent.ACTION_HOVER_MOVE
                                || action == MotionEvent.ACTION_MOVE) {
                            updatePosition(event.getRawX(), event.getRawY());
                            return true;
                        }
                        return super.onGenericMotionEvent(event);
                    }

                    @Override
                    public boolean onTouchEvent(MotionEvent event) {
                        return true;
                    }
                };
                view.setClickable(true);
                windowManager.addView(view, params);
                added = true;
            });
        }

        void hide() throws Exception {
            runOnMain(() -> {
                if (added && view != null && windowManager != null) {
                    try {
                        windowManager.removeView(view);
                    } finally {
                        added = false;
                        view = null;
                        params = null;
                        windowManager = null;
                    }
                } else {
                    added = false;
                    view = null;
                    params = null;
                    windowManager = null;
                }
            });
        }

        private void updatePosition(float rawX, float rawY) {
            synchronized (positionLock) {
                x = Math.max(0, Math.min(display.x - 1, Math.round(rawX)));
                y = Math.max(0, Math.min(display.y - 1, Math.round(rawY)));
                eventVersion++;
                positionLock.notifyAll();
            }
        }

        private void runOnMain(ThrowingRunnable action) throws Exception {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run();
                return;
            }
            CountDownLatch completed = new CountDownLatch(1);
            Throwable[] failure = new Throwable[1];
            mainHandler.post(() -> {
                try {
                    action.run();
                } catch (Throwable error) {
                    failure[0] = error;
                } finally {
                    completed.countDown();
                }
            });
            if (!completed.await(5L, TimeUnit.SECONDS)) {
                throw new IOException("Timed out updating the mouse pointer capture overlay");
            }
            if (failure[0] != null) {
                if (failure[0] instanceof Exception) {
                    throw (Exception) failure[0];
                }
                throw new IOException("Mouse pointer overlay failed", failure[0]);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
