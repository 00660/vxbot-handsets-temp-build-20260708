package com.vxbot.wechatbot;

import android.content.Context;

import com.iwebpp.crypto.TweetNaclFast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class HappyDirectClient {
    private static final String CLIENT_HEADER = "vxbot-apk-happy-direct/1";

    Pairing startAccountPairing(String serverUrl) throws Exception {
        TweetNaclFast.Box.KeyPair keyPair = HappyCrypto.newBoxKeyPair();
        String publicKey = HappyCrypto.encodeBase64(keyPair.getPublicKey());
        JSONObject body = new JSONObject().put("publicKey", publicKey);
        httpJson(trimServer(serverUrl) + "/v1/auth/account/request", "POST", "", body);
        return new Pairing(
                publicKey,
                HappyCrypto.encodeBase64(keyPair.getSecretKey()),
                "happy:///account?" + HappyCrypto.encodeBase64Url(keyPair.getPublicKey()));
    }

    Credentials finishAccountPairing(String serverUrl, String publicKeyBase64, String secretKeyBase64) throws Exception {
        JSONObject body = new JSONObject().put("publicKey", publicKeyBase64);
        JSONObject json = httpJson(trimServer(serverUrl) + "/v1/auth/account/request", "POST", "", body);
        if (!"authorized".equals(json.optString("state"))) {
            throw new IllegalStateException("Happy 配对尚未授权");
        }
        String token = json.optString("token", "").trim();
        String response = json.optString("response", "").trim();
        if (token.isEmpty() || response.isEmpty()) {
            throw new IllegalStateException("Happy 配对响应缺少 token/response");
        }
        byte[] secretKey = HappyCrypto.decodeBase64Any(secretKeyBase64);
        byte[] accountSecret = HappyCrypto.decryptBoxBundle(HappyCrypto.decodeBase64Any(response), secretKey);
        if (accountSecret == null || accountSecret.length != 32) {
            throw new IllegalStateException("Happy 配对响应解密失败");
        }
        return new Credentials(token, HappyCrypto.encodeBase64(accountSecret));
    }

    String requestCodex(Context context, BotConfig config, String prompt) throws Exception {
        if (isBlank(config.happyDirectToken)
                || isBlank(config.happyDirectSecret)
                || isBlank(config.happyDirectSessionId)) {
            throw new IllegalStateException("Happy 直连缺少 token/secret/sessionId");
        }
        String server = trimServer(config.happyDirectServerUrl);
        byte[] accountSecret = HappyCrypto.decodeBase64Any(config.happyDirectSecret);
        SessionRef session = resolveSession(server, config.happyDirectToken, accountSecret, config.happyDirectSessionId);
        int beforeSeq = latestSeq(server, config.happyDirectToken, session.id);
        int sentSeq = sendUserMessage(server, config.happyDirectToken, session, prompt, config);
        Reply reply = waitForReply(server, config.happyDirectToken, session,
                Math.max(beforeSeq, sentSeq), Math.max(10000, config.replyTimeoutMs));
        BotLog.i(context, "codex.happy_direct.reply", "Happy 直连完成 sessionId=" + session.id
                + " status=" + reply.status + " bytes=" + reply.text.getBytes(StandardCharsets.UTF_8).length);
        return reply.text.isEmpty() ? "Codex 已完成，但没有返回文本。" : reply.text;
    }

    private SessionRef resolveSession(String server, String token, byte[] accountSecret, String sessionId) throws Exception {
        JSONObject data = httpJson(server + "/v1/sessions", "GET", token, null);
        JSONArray sessions = data.optJSONArray("sessions");
        if (sessions == null) {
            throw new IllegalStateException("Happy sessions 响应为空");
        }
        JSONObject found = null;
        String target = sessionId.trim();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject item = sessions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (id.equals(target) || id.startsWith(target)) {
                found = item;
                break;
            }
        }
        if (found == null) {
            throw new IllegalStateException("未找到 Happy sessionId=" + target);
        }
        String dataKey = found.optString("dataEncryptionKey", "");
        if (!dataKey.isEmpty() && !"null".equalsIgnoreCase(dataKey)) {
            byte[] encoded = HappyCrypto.decodeBase64Any(dataKey);
            if (encoded.length < 2 || encoded[0] != 0) {
                throw new IllegalStateException("不支持的 Happy dataEncryptionKey");
            }
            TweetNaclFast.Box.KeyPair contentKeyPair = HappyCrypto.deriveContentKeyPair(accountSecret);
            byte[] sessionKey = HappyCrypto.decryptBoxBundle(slice(encoded, 1), contentKeyPair.getSecretKey());
            if (sessionKey == null || sessionKey.length != 32) {
                throw new IllegalStateException("Happy session key 解密失败");
            }
            return new SessionRef(found.optString("id"), sessionKey, "dataKey");
        }
        return new SessionRef(found.optString("id"), accountSecret, "legacy");
    }

    private int latestSeq(String server, String token, String sessionId) throws Exception {
        int cursor = 0;
        while (true) {
            JSONObject data = httpJson(server + "/v3/sessions/" + enc(sessionId)
                    + "/messages?after_seq=" + cursor + "&limit=500", "GET", token, null);
            JSONArray messages = data.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject msg = messages.optJSONObject(i);
                    if (msg != null) {
                        cursor = Math.max(cursor, msg.optInt("seq", cursor));
                    }
                }
            }
            if (!data.optBoolean("hasMore", false) || messages == null || messages.length() == 0) {
                return cursor;
            }
        }
    }

    private int sendUserMessage(String server, String token, SessionRef session, String prompt, BotConfig config) throws Exception {
        JSONObject content = new JSONObject()
                .put("role", "user")
                .put("content", new JSONObject()
                        .put("type", "text")
                        .put("text", prompt))
                .put("meta", new JSONObject()
                        .put("sentFrom", "vxbot-apk")
                        .put("permissionMode", "yolo")
                        .put("model", config.model)
                        .put("appendSystemPrompt",
                                "你通过 Happy 接收微信群机器人转发的 Codex 请求。"
                                        + "如果请求涉及当前容器工作区代码，直接完成必要修改和验证；"
                                        + "最终回复要适合发回微信群，简洁说明做了什么、结果如何、有没有未完成项。"));
        String encrypted = HappyCrypto.encodeBase64(HappyCrypto.encryptJson(content.toString(), session.key, session.variant));
        JSONObject body = new JSONObject().put("messages", new JSONArray().put(new JSONObject()
                .put("content", encrypted)
                .put("localId", UUID.randomUUID().toString())));
        JSONObject data = httpJson(server + "/v3/sessions/" + enc(session.id) + "/messages", "POST", token, body);
        JSONArray messages = data.optJSONArray("messages");
        int seq = 0;
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.optJSONObject(i);
                if (msg != null) {
                    seq = Math.max(seq, msg.optInt("seq", 0));
                }
            }
        }
        return seq;
    }

    private Reply waitForReply(String server, String token, SessionRef session, int afterSeq, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int cursor = afterSeq;
        StringBuilder text = new StringBuilder();
        String status = "completed";
        boolean sawText = false;
        while (System.currentTimeMillis() < deadline) {
            JSONObject data = httpJson(server + "/v3/sessions/" + enc(session.id)
                    + "/messages?after_seq=" + cursor + "&limit=100", "GET", token, null);
            JSONArray messages = data.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject msg = messages.optJSONObject(i);
                    if (msg == null) {
                        continue;
                    }
                    cursor = Math.max(cursor, msg.optInt("seq", cursor));
                    String part = parseReplyText(session, msg);
                    if (!part.isEmpty() && text.indexOf(part) < 0) {
                        if (text.length() > 0) {
                            text.append('\n');
                        }
                        text.append(part);
                        sawText = true;
                    }
                    JSONObject body = decryptedBody(session, msg);
                    JSONObject envelope = body != null && "session".equals(body.optString("role"))
                            ? body.optJSONObject("content") : null;
                    JSONObject ev = envelope == null ? null : envelope.optJSONObject("ev");
                    if (ev != null && "turn-end".equals(ev.optString("t"))) {
                        status = ev.optString("status", status);
                        return new Reply(text.toString().trim(), status);
                    }
                }
            }
            if (!data.optBoolean("hasMore", false)) {
                Thread.sleep(sawText ? 700L : 1000L);
            }
        }
        if (sawText) {
            return new Reply(text.toString().trim(), status);
        }
        throw new IllegalStateException("等待 Happy Codex 回复超时");
    }

    private String parseReplyText(SessionRef session, JSONObject msg) throws Exception {
        JSONObject body = decryptedBody(session, msg);
        if (body == null) {
            return "";
        }
        String role = body.optString("role", "");
        JSONObject content = body.optJSONObject("content");
        if ("session".equals(role) && content != null && "agent".equals(content.optString("role"))) {
            JSONObject ev = content.optJSONObject("ev");
            if (ev != null && "text".equals(ev.optString("t"))) {
                return ev.optString("text", "").trim();
            }
        }
        if ("agent".equals(role) && content != null) {
            if ("text".equals(content.optString("type"))) {
                return content.optString("text", "").trim();
            }
            JSONObject data = content.optJSONObject("data");
            if (data != null) {
                return firstText(data).trim();
            }
        }
        return "";
    }

    private JSONObject decryptedBody(SessionRef session, JSONObject msg) throws Exception {
        JSONObject content = msg.optJSONObject("content");
        String encrypted = content == null ? "" : content.optString("c", "");
        if (encrypted.isEmpty()) {
            return null;
        }
        String json = HappyCrypto.decryptToJson(HappyCrypto.decodeBase64Any(encrypted), session.key, session.variant);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        return new JSONObject(json);
    }

    private static String firstText(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            String text = obj.optString("text", "");
            if (!text.isEmpty()) {
                return text;
            }
            JSONArray names = obj.names();
            if (names == null) {
                return "";
            }
            for (int i = 0; i < names.length(); i++) {
                String out = firstText(obj.opt(names.optString(i)));
                if (!out.isEmpty()) {
                    return out;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String out = firstText(array.opt(i));
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return "";
    }

    private static JSONObject httpJson(String url, String method, String token, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("X-Happy-Client", CLIENT_HEADER);
        if (!isBlank(token)) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            conn.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + trim(text));
        }
        return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String trimServer(String serverUrl) {
        String text = isBlank(serverUrl) ? BotConfig.DEFAULT_HAPPY_DIRECT_SERVER_URL : serverUrl.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String enc(String value) {
        return value.replace("/", "%2F").replace(":", "%3A");
    }

    private static byte[] slice(byte[] input, int start) {
        byte[] out = new byte[input.length - start];
        System.arraycopy(input, start, out, 0, out.length);
        return out;
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 400 ? text.substring(0, 400) : text;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    static final class Pairing {
        final String publicKeyBase64;
        final String secretKeyBase64;
        final String url;

        Pairing(String publicKeyBase64, String secretKeyBase64, String url) {
            this.publicKeyBase64 = publicKeyBase64;
            this.secretKeyBase64 = secretKeyBase64;
            this.url = url;
        }
    }

    static final class Credentials {
        final String token;
        final String secretBase64;

        Credentials(String token, String secretBase64) {
            this.token = token;
            this.secretBase64 = secretBase64;
        }
    }

    private static final class SessionRef {
        final String id;
        final byte[] key;
        final String variant;

        SessionRef(String id, byte[] key, String variant) {
            this.id = id;
            this.key = key;
            this.variant = variant;
        }
    }

    private static final class Reply {
        final String text;
        final String status;

        Reply(String text, String status) {
            this.text = text;
            this.status = status;
        }
    }
}
