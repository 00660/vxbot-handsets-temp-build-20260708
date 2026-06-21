package com.vxbot.wechatbot;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BotInputMethodService extends InputMethodService {
    public static final String COMPONENT = "com.vxbot.wechatbot/.BotInputMethodService";
    private static final Object LOCK = new Object();
    private static BotInputMethodService active;

    @Override
    public void onCreate() {
        super.onCreate();
        setActive(this);
    }

    @Override
    public void onStartInput(android.view.inputmethod.EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        setActive(this);
    }

    @Override
    public View onCreateInputView() {
        return hiddenImeView();
    }

    @Override
    public View onCreateCandidatesView() {
        return hiddenImeView();
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return false;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        View decor = getWindow() == null || getWindow().getWindow() == null
                ? null
                : getWindow().getWindow().getDecorView();
        int bottom = decor == null ? 0 : decor.getHeight();
        outInsets.contentTopInsets = bottom;
        outInsets.visibleTopInsets = bottom;
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME;
    }

    @Override
    public void onDestroy() {
        synchronized (LOCK) {
            if (active == this) {
                active = null;
            }
            LOCK.notifyAll();
        }
        super.onDestroy();
    }

    public static boolean commitText(String text, long timeoutMs) {
        BotInputMethodService service = waitForActive(timeoutMs);
        return service != null && service.commitOnMainThread(text == null ? "" : text, timeoutMs);
    }

    private static void setActive(BotInputMethodService service) {
        synchronized (LOCK) {
            active = service;
            LOCK.notifyAll();
        }
    }

    private static BotInputMethodService waitForActive(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(250L, timeoutMs);
        synchronized (LOCK) {
            while (active == null && System.currentTimeMillis() < deadline) {
                try {
                    LOCK.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return active;
        }
    }

    private View hiddenImeView() {
        FrameLayout view = new FrameLayout(this);
        view.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        view.setMinimumHeight(0);
        view.setVisibility(View.GONE);
        return view;
    }

    private boolean commitOnMainThread(String text, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return commitNow(text);
        }
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = new boolean[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = commitNow(text);
            } finally {
                latch.countDown();
            }
        });
        try {
            return latch.await(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS) && result[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean commitNow(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            connection.deleteSurroundingText(4096, 4096);
            return connection.commitText(text, 1);
        } finally {
            connection.endBatchEdit();
        }
    }
}
