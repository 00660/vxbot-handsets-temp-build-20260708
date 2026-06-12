package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class MimoTtsClient {
    private MimoTtsClient() {
    }

    static File synthesize(Context context, BotConfig config, String text, String reason, int timeoutMs) {
        String payload = text == null ? "" : text.trim();
        String apiKey = config == null ? "" : config.mimoTtsApiKey.trim();
        if (payload.isEmpty()) {
            BotLog.e(context, "tts.mimo.abort", "内容为空 reason=" + reason);
            return null;
        }
        if (apiKey.isEmpty()) {
            BotLog.w(context, "tts.mimo.key.missing", "MiMo API Key 未填，回退千问 TTS reason=" + reason);
            return null;
        }
        int waitMs = Math.max(8000, timeoutMs);
        HttpURLConnection connection = null;
        try {
            String endpoint = config.mimoTtsEndpoint == null || config.mimoTtsEndpoint.trim().isEmpty()
                    ? BotConfig.DEFAULT_MIMO_TTS_ENDPOINT : config.mimoTtsEndpoint.trim();
            String voice = config.mimoTtsVoice == null || config.mimoTtsVoice.trim().isEmpty()
                    ? BotConfig.DEFAULT_MIMO_TTS_VOICE : config.mimoTtsVoice.trim();
            JSONObject body = buildPayload(config, payload, voice);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

            BotLog.i(context, "tts.mimo.start", "reason=" + reason
                    + " voice=" + voice
                    + " bytes=" + bytes.length
                    + " endpoint=" + endpoint);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(waitMs);
            connection.setReadTimeout(waitMs);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("api-key", apiKey);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 VXBotWechatBot/0.1");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            String response = readAll(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                BotLog.e(context, "tts.mimo.http", "reason=" + reason + " code=" + code + " body=" + trim(response));
                return null;
            }
            String audio = extractAudioData(new JSONObject(response));
            if (audio.isEmpty()) {
                BotLog.e(context, "tts.mimo.no_audio", "reason=" + reason + " body=" + trim(response));
                return null;
            }
            byte[] wav = Base64.decode(audio, Base64.DEFAULT);
            if (wav.length <= 44) {
                BotLog.e(context, "tts.mimo.empty", "reason=" + reason + " size=" + wav.length);
                return null;
            }
            File dir = new File(context.getFilesDir(), "tts");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
            }
            File file = new File(dir, "vxbot-mimo-tts-" + SystemClock.uptimeMillis() + ".wav");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(wav);
            }
            BotLog.i(context, "tts.mimo.done", "reason=" + reason
                    + " file=" + file.getAbsolutePath()
                    + " size=" + file.length());
            return file;
        } catch (Exception e) {
            BotLog.e(context, "tts.mimo.fail", "reason=" + reason + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject buildPayload(BotConfig config, String text, String voice) throws Exception {
        JSONArray messages = new JSONArray();
        String instruction = config == null ? BotConfig.DEFAULT_MIMO_NATURAL_LANGUAGE_CONTROL : config.mimoNaturalLanguageControl;
        if (instruction != null && !instruction.trim().isEmpty()) {
            messages.put(new JSONObject().put("role", "user").put("content", instruction.trim()));
        }
        messages.put(new JSONObject().put("role", "assistant").put("content", applyAudioTag(config, text)));
        return new JSONObject()
                .put("model", BotConfig.DEFAULT_MIMO_TTS_MODEL)
                .put("messages", messages)
                .put("audio", new JSONObject()
                        .put("format", "wav")
                        .put("voice", voice));
    }

    private static String applyAudioTag(BotConfig config, String text) {
        String payload = text == null ? "" : text.trim();
        String tag = config == null ? BotConfig.DEFAULT_MIMO_AUDIO_TAG_CONTROL : config.mimoAudioTagControl;
        String style = tag == null ? "" : tag.trim();
        if (payload.isEmpty() || style.isEmpty() || hasLeadingAudioTag(payload)) {
            return payload;
        }
        if ((style.startsWith("(") && style.endsWith(")"))
                || (style.startsWith("（") && style.endsWith("）"))
                || (style.startsWith("[") && style.endsWith("]"))) {
            return style + payload;
        }
        return "(" + style + ")" + payload;
    }

    private static boolean hasLeadingAudioTag(String text) {
        String value = text == null ? "" : text.trim();
        return (value.startsWith("(") && value.indexOf(')') > 1)
                || (value.startsWith("（") && value.indexOf('）') > 1)
                || (value.startsWith("[") && value.indexOf(']') > 1);
    }

    private static String extractAudioData(JSONObject json) {
        JSONArray choices = json.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject first = choices.optJSONObject(0);
            JSONObject message = first == null ? null : first.optJSONObject("message");
            if (message == null && first != null) {
                message = first.optJSONObject("delta");
            }
            Object audio = message == null ? null : message.opt("audio");
            if (audio instanceof JSONObject) {
                String data = ((JSONObject) audio).optString("data", "");
                if (!data.isEmpty()) {
                    return data;
                }
            } else if (audio instanceof String && !((String) audio).isEmpty()) {
                return (String) audio;
            }
        }
        return json.optString("data", "");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').trim();
        return value.length() > 700 ? value.substring(0, 700) : value;
    }
}
