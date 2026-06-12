package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class TtsCache {
    private static final String DEFAULT_ENDPOINT = "https://qwen3ttsai.com/api/qwen3tts/generate";

    private TtsCache() {
    }

    static File prepare(Context context, BotConfig config, String text, String reason, int timeoutMs, int attempts) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            BotLog.e(context, "tts.prepare.abort", "TTS 内容为空 reason=" + reason);
            return null;
        }
        if (config != null && BotConfig.TTS_PROVIDER_DOUBAO.equals(config.ttsProvider)) {
            File file = DoubaoWebTtsClient.synthesize(context, config, payload, reason, timeoutMs);
            if (file != null && file.isFile() && file.length() > 128) {
                return file;
            }
            BotLog.w(context, "tts.prepare.fallback", "豆包 TTS 未生成音频，回退千问 TTS reason=" + reason);
        }
        if (config != null && BotConfig.TTS_PROVIDER_MIMO.equals(config.ttsProvider)) {
            File file = MimoTtsClient.synthesize(context, config, payload, reason, timeoutMs);
            if (file != null && file.isFile() && file.length() > 128) {
                return file;
            }
            BotLog.w(context, "tts.prepare.fallback", "MiMo TTS 未生成音频，回退千问 TTS reason=" + reason);
        }
        return prepareQwen(context, config, payload, reason, timeoutMs, attempts);
    }

    private static File prepareQwen(Context context, BotConfig config, String payload, String reason, int timeoutMs, int attempts) {
        String voice = config == null ? BotConfig.DEFAULT_TTS_VOICE : config.ttsVoice;
        float speed = config == null ? BotConfig.DEFAULT_TTS_SPEED : config.ttsSpeed;
        int waitMs = Math.max(8000, timeoutMs);
        int maxAttempts = Math.max(1, Math.min(3, attempts));
        String body = "{\"text\":" + jsonString(payload)
                + ",\"voice\":" + jsonString(voice == null || voice.trim().isEmpty() ? BotConfig.DEFAULT_TTS_VOICE : voice.trim())
                + ",\"speed\":" + String.format(Locale.US, "%.2f", BotConfig.normalizeTtsSpeed(speed))
                + ",\"mode\":\"system\"}";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection connection = null;
            try {
                BotLog.i(context, "tts.prepare.start", "reason=" + reason + " attempt=" + attempt
                        + " voice=" + voice + " speed=" + BotConfig.normalizeTtsSpeed(speed)
                        + " length=" + payload.length());
                connection = (HttpURLConnection) new URL(DEFAULT_ENDPOINT).openConnection();
                connection.setConnectTimeout(waitMs);
                connection.setReadTimeout(waitMs);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "audio/wav,audio/*,*/*");
                connection.setRequestProperty("Origin", "https://qwen3ttsai.com");
                connection.setRequestProperty("Referer", "https://qwen3ttsai.com/zh");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 VXBotWechatBot/0.1");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int code = connection.getResponseCode();
                String contentType = connection.getContentType();
                if (code >= 200 && code < 300) {
                    File dir = new File(context.getFilesDir(), "tts");
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
                    }
                    File file = new File(dir, "vxbot-tts-" + SystemClock.uptimeMillis() + ".wav");
                    try (InputStream in = connection.getInputStream();
                         FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    if (file.length() > 44) {
                        BotLog.i(context, "tts.prepare.done", "reason=" + reason + " code=" + code
                                + " type=" + contentType + " file=" + file.getAbsolutePath()
                                + " size=" + file.length());
                        return file;
                    }
                    BotLog.e(context, "tts.prepare.empty", "reason=" + reason + " code=" + code
                            + " type=" + contentType + " size=" + file.length());
                    file.delete();
                } else {
                    BotLog.e(context, "tts.prepare.http", "reason=" + reason + " code=" + code
                            + " type=" + contentType + " body=" + readError(connection));
                }
            } catch (Exception e) {
                BotLog.e(context, "tts.prepare.fail", "reason=" + reason + " attempt=" + attempt
                        + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            if (attempt < maxAttempts) {
                SystemClock.sleep(700);
            }
        }
        return null;
    }

    static void cleanup(Context context, File file, String reason) {
        if (file == null) {
            return;
        }
        if (file.delete()) {
            BotLog.i(context, "tts.prepare.deleted", "reason=" + reason + " file=" + file.getAbsolutePath());
        }
    }

    private static String readError(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) {
                return "";
            }
            byte[] buffer = new byte[768];
            int read = in.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).replace('\n', ' ').trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String jsonString(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }
}
