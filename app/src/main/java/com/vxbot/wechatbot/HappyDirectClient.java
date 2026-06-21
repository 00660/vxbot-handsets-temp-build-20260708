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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

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
                || isBlank(config.happyDirectSecret)) {
            throw new IllegalStateException("Happy 直连缺少 token/secret");
        }
        String server = trimServer(config.happyDirectServerUrl);
        byte[] accountSecret = HappyCrypto.decodeBase64Any(config.happyDirectSecret);
        String sessionId = config.happyDirectSessionId;
        if (isBlank(sessionId)) {
            try {
                sessionId = spawnCodexSession(server, config.happyDirectToken, accountSecret, config);
                BotConfig.prefs(context).edit().putString("happyDirectSessionId", sessionId).apply();
                BotLog.i(context, "codex.happy_direct.spawn", "Happy 已拉起 Codex sessionId=" + sessionId);
            } catch (Exception e) {
                BotLog.w(context, "codex.happy_direct.spawn.fail",
                        "Happy 机器 RPC 拉起失败，尝试最近活跃 session: " + e.getMessage());
            }
        }
        SessionRef session = resolveSessionWithRetry(server, config.happyDirectToken, accountSecret, sessionId);
        int beforeSeq = latestSeq(server, config.happyDirectToken, session.id);
        int sentSeq = sendUserMessage(server, config.happyDirectToken, session, prompt, config);
        Reply reply = waitForReply(server, config.happyDirectToken, session,
                Math.max(beforeSeq, sentSeq), Math.max(10000, config.replyTimeoutMs));
        BotLog.i(context, "codex.happy_direct.reply", "Happy 直连完成 sessionId=" + session.id
                + " status=" + reply.status + " bytes=" + reply.text.getBytes(StandardCharsets.UTF_8).length);
        return reply.text.isEmpty() ? "Codex 已完成，但没有返回文本。" : reply.text;
    }

    private String spawnCodexSession(String server, String token, byte[] accountSecret, BotConfig config) throws Exception {
        MachineRef machine = resolveMachine(server, token, accountSecret, config.happyDirectMachineId);
        JSONObject params = new JSONObject()
                .put("type", "spawn-in-directory")
                .put("directory", config.happyDirectCwd)
                .put("approvedNewDirectoryCreation", false)
                .put("agent", "codex");
        String encryptedParams = HappyCrypto.encodeBase64(
                HappyCrypto.encryptJson(params.toString(), machine.key, machine.variant));
        JSONObject ack = socketRpc(server, token, machine.id + ":spawn-happy-session", encryptedParams);
        if (!ack.optBoolean("ok", false)) {
            throw new IllegalStateException(ack.optString("error", "RPC call failed"));
        }
        String encryptedResult = ack.optString("result", "");
        if (encryptedResult.isEmpty()) {
            throw new IllegalStateException("RPC 返回为空");
        }
        String json = HappyCrypto.decryptToJson(HappyCrypto.decodeBase64Any(encryptedResult), machine.key, machine.variant);
        JSONObject result = new JSONObject(json == null ? "{}" : json);
        String type = result.optString("type", "");
        if ("success".equals(type) && !result.optString("sessionId", "").isEmpty()) {
            return result.optString("sessionId");
        }
        if (!result.optString("error", "").isEmpty()) {
            throw new IllegalStateException(result.optString("error"));
        }
        if ("requestToApproveDirectoryCreation".equals(type)) {
            throw new IllegalStateException("Happy 机器要求确认创建目录 " + result.optString("directory", ""));
        }
        throw new IllegalStateException(result.optString("errorMessage", "Happy 机器拉起 Codex 失败"));
    }

    private MachineRef resolveMachine(String server, String token, byte[] accountSecret, String machineId) throws Exception {
        JSONArray machines = httpJsonArray(server + "/v1/machines", token);
        if (machines.length() == 0) {
            throw new IllegalStateException("Happy machines 响应为空");
        }
        String target = machineId == null ? "" : machineId.trim();
        JSONObject found = null;
        if (target.isEmpty()) {
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < machines.length(); i++) {
                JSONObject item = machines.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                long score = Math.max(item.optLong("activeAt", 0), item.optLong("updatedAt", 0));
                if (item.optBoolean("active", false)) {
                    score += 10_000_000_000_000L;
                }
                if (found == null || score > bestScore) {
                    found = item;
                    bestScore = score;
                }
            }
        } else {
            for (int i = 0; i < machines.length(); i++) {
                JSONObject item = machines.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                if (id.equals(target) || id.startsWith(target)) {
                    found = item;
                    break;
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException(target.isEmpty()
                    ? "未找到可用 Happy machine"
                    : "未找到 Happy machineId=" + target);
        }
        String dataKey = found.optString("dataEncryptionKey", "");
        if (!dataKey.isEmpty() && !"null".equalsIgnoreCase(dataKey)) {
            byte[] encoded = HappyCrypto.decodeBase64Any(dataKey);
            if (encoded.length < 2 || encoded[0] != 0) {
                throw new IllegalStateException("不支持的 Happy machine dataEncryptionKey");
            }
            TweetNaclFast.Box.KeyPair contentKeyPair = HappyCrypto.deriveContentKeyPair(accountSecret);
            byte[] machineKey = HappyCrypto.decryptBoxBundle(slice(encoded, 1), contentKeyPair.getSecretKey());
            if (machineKey == null || machineKey.length != 32) {
                throw new IllegalStateException("Happy machine key 解密失败");
            }
            return new MachineRef(found.optString("id"), machineKey, "dataKey");
        }
        return new MachineRef(found.optString("id"), accountSecret, "legacy");
    }

    private SessionRef resolveSessionWithRetry(String server, String token, byte[] accountSecret, String sessionId) throws Exception {
        String target = sessionId == null ? "" : sessionId.trim();
        Exception last = null;
        int attempts = target.isEmpty() ? 1 : 12;
        for (int i = 0; i < attempts; i++) {
            try {
                return resolveSession(server, token, accountSecret, target);
            } catch (Exception e) {
                last = e;
                if (i + 1 >= attempts) {
                    break;
                }
                Thread.sleep(500L);
            }
        }
        throw last == null ? new IllegalStateException("Happy session 解析失败") : last;
    }

    private SessionRef resolveSession(String server, String token, byte[] accountSecret, String sessionId) throws Exception {
        String target = sessionId == null ? "" : sessionId.trim();
        JSONObject data = httpJson(server + (target.isEmpty()
                ? "/v2/sessions/active?limit=50"
                : "/v1/sessions"), "GET", token, null);
        JSONArray sessions = data.optJSONArray("sessions");
        if ((sessions == null || sessions.length() == 0) && target.isEmpty()) {
            data = httpJson(server + "/v1/sessions", "GET", token, null);
            sessions = data.optJSONArray("sessions");
        }
        if (sessions == null || sessions.length() == 0) {
            throw new IllegalStateException("Happy sessions 响应为空");
        }
        JSONObject found = null;
        if (target.isEmpty()) {
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject item = sessions.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                long score = Math.max(item.optLong("activeAt", 0), item.optLong("updatedAt", 0));
                if (item.optBoolean("active", false)) {
                    score += 10_000_000_000_000L;
                }
                if (found == null || score > bestScore) {
                    found = item;
                    bestScore = score;
                }
            }
        } else {
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
        }
        if (found == null) {
            throw new IllegalStateException(target.isEmpty()
                    ? "未找到可用 Happy session"
                    : "未找到 Happy sessionId=" + target);
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

    private static JSONObject socketRpc(String server, String token, String method, String params) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> resultRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        Request request = new Request.Builder()
                .url(socketUrl(server))
                .build();
        WebSocket socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    handleSocketMessage(webSocket, token, text, connected, done, resultRef, errorRef);
                } catch (Exception e) {
                    errorRef.compareAndSet(null, e);
                    connected.countDown();
                    done.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                errorRef.compareAndSet(null, new IllegalStateException(t.getMessage()));
                connected.countDown();
                done.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (resultRef.get() == null) {
                    errorRef.compareAndSet(null, new IllegalStateException("Socket closed " + code + " " + reason));
                    connected.countDown();
                    done.countDown();
                }
            }
        });
        try {
            if (!connected.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待 Happy Socket 连接超时");
            }
            if (errorRef.get() != null) {
                throw errorRef.get();
            }
            JSONArray event = new JSONArray()
                    .put("rpc-call")
                    .put(new JSONObject()
                            .put("method", method)
                            .put("params", params));
            if (!socket.send("421" + event)) {
                throw new IllegalStateException("Happy Socket 发送失败");
            }
            if (!done.await(35, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待 Happy RPC ack 超时");
            }
            if (errorRef.get() != null) {
                throw errorRef.get();
            }
            JSONObject result = resultRef.get();
            if (result == null) {
                throw new IllegalStateException("Happy RPC 无返回");
            }
            return result;
        } finally {
            socket.cancel();
            client.dispatcher().executorService().shutdown();
        }
    }

    private static void handleSocketMessage(WebSocket socket, String token, String text,
                                           CountDownLatch connected,
                                           CountDownLatch done,
                                           AtomicReference<JSONObject> resultRef,
                                           AtomicReference<Exception> errorRef) throws Exception {
        if (text == null || text.isEmpty()) {
            return;
        }
        if ("2".equals(text)) {
            socket.send("3");
            return;
        }
        if (text.startsWith("0")) {
            JSONObject auth = new JSONObject()
                    .put("token", token)
                    .put("happyClient", CLIENT_HEADER);
            socket.send("40" + auth);
            return;
        }
        if (text.startsWith("40")) {
            connected.countDown();
            return;
        }
        if (text.startsWith("44")) {
            errorRef.compareAndSet(null, new IllegalStateException("Happy Socket auth failed " + text.substring(2)));
            connected.countDown();
            done.countDown();
            return;
        }
        if (text.startsWith("431")) {
            JSONArray ack = new JSONArray(text.substring(3));
            JSONObject result = ack.optJSONObject(0);
            if (result == null) {
                errorRef.compareAndSet(null, new IllegalStateException("Happy RPC ack 格式异常"));
            } else {
                resultRef.set(result);
            }
            done.countDown();
        }
    }

    private static String socketUrl(String server) {
        String text = trimServer(server);
        if (text.startsWith("https://")) {
            text = "wss://" + text.substring("https://".length());
        } else if (text.startsWith("http://")) {
            text = "ws://" + text.substring("http://".length());
        }
        return text + "/v1/updates/?EIO=4&transport=websocket";
    }

    private static JSONObject httpJson(String url, String method, String token, JSONObject body) throws Exception {
        String text = httpText(url, method, token, body);
        return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static JSONArray httpJsonArray(String url, String token) throws Exception {
        String text = httpText(url, "GET", token, null);
        return text.trim().isEmpty() ? new JSONArray() : new JSONArray(text);
    }

    private static String httpText(String url, String method, String token, JSONObject body) throws Exception {
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
        return text;
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

    private static final class MachineRef {
        final String id;
        final byte[] key;
        final String variant;

        MachineRef(String id, byte[] key, String variant) {
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
