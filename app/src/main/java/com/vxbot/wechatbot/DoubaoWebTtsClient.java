package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class DoubaoWebTtsClient {
    private static final String WS_URL = "wss://ws-samantha.doubao.com/samantha/audio/tts";
    private static final long RANDOM_ID_BASE = 7400000000000000000L;
    private static final long RANDOM_ID_RANGE = 100000000000000000L;
    private static final Random RANDOM = new Random();

    private DoubaoWebTtsClient() {
    }

    static File synthesize(Context context, BotConfig config, String text, String reason, int timeoutMs) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            BotLog.e(context, "tts.doubao.abort", "内容为空 reason=" + reason);
            return null;
        }
        String cookie = config == null ? "" : config.doubaoCookie();
        if (cookie.isEmpty()) {
            BotLog.w(context, "tts.doubao.cookie.missing", "三段 Cookie 未填完整，回退千问 TTS reason=" + reason);
            return null;
        }
        int waitMs = Math.max(8000, timeoutMs);
        ByteArrayOutputStream audio = new ByteArrayOutputStream(64 * 1024);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>("");
        AtomicLong lastAudioAt = new AtomicLong(0);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(waitMs, TimeUnit.MILLISECONDS)
                .writeTimeout(waitMs, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(waitMs + 5000L, TimeUnit.MILLISECONDS)
                .build();
        Request request = new Request.Builder()
                .url(buildUrl(config))
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Pragma", "no-cache")
                .addHeader("Origin", "https://www.doubao.com")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36 VXBotWechatBot/0.1")
                .addHeader("Cookie", cookie)
                .build();
        BotLog.i(context, "tts.doubao.start", "reason=" + reason
                + " speaker=" + (config == null ? BotConfig.DEFAULT_DOUBAO_TTS_VOICE : config.doubaoTtsVoice)
                + " speed=" + (config == null ? BotConfig.DEFAULT_TTS_SPEED : BotConfig.normalizeTtsSpeed(config.ttsSpeed))
                + " length=" + payload.length());
        WebSocket socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(jsonEvent("text", payload));
                webSocket.send(jsonEvent("finish", ""));
            }

            @Override
            public void onMessage(WebSocket webSocket, String message) {
                handleTextMessage(webSocket, message, error, done);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                lastAudioAt.set(SystemClock.uptimeMillis());
                synchronized (audio) {
                    try {
                        audio.write(bytes.toByteArray());
                    } catch (Exception e) {
                        error.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                        done.countDown();
                    }
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reasonText) {
                done.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String status = response == null ? "" : " http=" + response.code();
                error.set(t.getClass().getSimpleName() + ": " + t.getMessage() + status);
                done.countDown();
            }
        });
        try {
            startIdleMonitor(audio, lastAudioAt, done, waitMs);
            if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
                error.set("timeout after " + waitMs + "ms");
                socket.cancel();
            } else {
                socket.close(1000, "done");
            }
            byte[] bytes;
            synchronized (audio) {
                bytes = audio.toByteArray();
            }
            if (bytes.length > 128) {
                File dir = new File(context.getFilesDir(), "tts");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
                }
                File file = new File(dir, "vxbot-doubao-tts-" + SystemClock.uptimeMillis() + ".mp3");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                BotLog.i(context, "tts.doubao.done", "reason=" + reason + " file=" + file.getAbsolutePath()
                        + " size=" + file.length());
                return file;
            }
            BotLog.e(context, "tts.doubao.empty", "reason=" + reason + " size=" + bytes.length
                    + " error=" + error.get());
        } catch (Exception e) {
            BotLog.e(context, "tts.doubao.fail", "reason=" + reason + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        return null;
    }

    private static void startIdleMonitor(ByteArrayOutputStream audio, AtomicLong lastAudioAt,
                                         CountDownLatch done, int waitMs) {
        Thread monitor = new Thread(() -> {
            long start = SystemClock.uptimeMillis();
            while (done.getCount() > 0 && SystemClock.uptimeMillis() - start < waitMs) {
                SystemClock.sleep(250);
                long last = lastAudioAt.get();
                if (last <= 0) {
                    continue;
                }
                int size;
                synchronized (audio) {
                    size = audio.size();
                }
                if (size > 128 && SystemClock.uptimeMillis() - last >= 1200) {
                    done.countDown();
                    return;
                }
            }
        }, "doubao-tts-idle-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static void handleTextMessage(WebSocket webSocket, String message,
                                          AtomicReference<String> error, CountDownLatch done) {
        try {
            JSONObject data = new JSONObject(message);
            int code = data.optInt("code", 0);
            String event = data.optString("event", "");
            if ("error".equals(event) || code != 0) {
                String msg = data.optString("message", "unknown");
                error.set("code=" + code + " message=" + msg);
                webSocket.close(1000, "error");
                done.countDown();
            }
        } catch (Exception ignored) {
        }
    }

    private static String buildUrl(BotConfig config) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("speaker", config == null ? BotConfig.DEFAULT_DOUBAO_TTS_VOICE : config.doubaoTtsVoice);
        params.put("format", "mp3");
        params.put("speech_rate", String.valueOf(toDoubaoRate(config == null ? BotConfig.DEFAULT_TTS_SPEED : config.ttsSpeed)));
        params.put("pitch", "0");
        params.put("version_code", "20800");
        params.put("language", "zh");
        params.put("device_platform", "web");
        params.put("aid", "497858");
        params.put("real_aid", "497858");
        params.put("pkg_type", "release_version");
        String webId = randomWebId();
        params.put("device_id", randomWebId());
        params.put("pc_version", "2.46.3");
        params.put("web_id", webId);
        params.put("tea_uuid", webId);
        params.put("region", "");
        params.put("sys_region", "");
        params.put("samantha_web", "1");
        params.put("use-olympus-account", "1");
        params.put("web_tab_id", UUID.randomUUID().toString());
        StringBuilder url = new StringBuilder(WS_URL).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return url.toString();
    }

    private static int toDoubaoRate(float speed) {
        float normalized = BotConfig.normalizeTtsSpeed(speed);
        return Math.max(-100, Math.min(100, Math.round((normalized - 1.0f) * 100.0f)));
    }

    private static String randomWebId() {
        long offset;
        synchronized (RANDOM) {
            offset = Math.floorMod(RANDOM.nextLong(), RANDOM_ID_RANGE);
        }
        return Long.toString(RANDOM_ID_BASE + offset);
    }

    private static String jsonEvent(String event, String text) {
        try {
            JSONObject object = new JSONObject();
            object.put("event", event);
            if (!text.isEmpty()) {
                object.put("text", text);
            }
            return object.toString();
        } catch (Exception e) {
            return String.format(Locale.US, "{\"event\":\"%s\"}", event);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }
}
