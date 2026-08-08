package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 内网图片投递桥。面板只投递图片任务，机器人分别回传接收与微信发送终态。 */
final class BotHttpServer {
    private static final String PREFS = "bot_config";
    private static final String KEY_BOT_ID = "robotPushBotId";
    private static final String KEY_AUTH_TOKEN = "robotPushAuthToken";
    private static final String KEY_HTTP_PORT = "robotPushHttpPort";
    private static final String KEY_PANEL_URL = "robotPushPanelUrl";
    private static final int DEFAULT_HTTP_PORT = 18234;
    private static final String DEFAULT_PANEL_URL = "http://192.168.2.204:5000";
    private static final int APP_VERSION_CODE = 235;
    private static final String APP_VERSION_NAME = "0.1.235-image-delivery-receipts";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int REGISTER_READ_TIMEOUT_MS = 8000;
    private static final int RECEIPT_READ_TIMEOUT_MS = 8000;
    private static final long REGISTER_INTERVAL_MS = 60 * 1000L;
    private static final long RECEIPT_RETRY_DELAY_MS = 4000L;
    private static final int RECEIPT_MAX_ATTEMPTS = 3;
    private static final int DELIVERY_CACHE_LIMIT = 200;

    interface DeliveryHandler {
        boolean enqueue(DeliveryTask task);
    }

    static final class DeliveryTask {
        final String deliveryId;
        final String targetType;
        final String target;
        final String title;
        final String text;
        final String event;

        DeliveryTask(String deliveryId, String targetType, String target, String title, String text, String event) {
            this.deliveryId = deliveryId;
            this.targetType = targetType;
            this.target = target;
            this.title = title;
            this.text = text;
            this.event = event;
        }
    }

    private static final class DeliveryRecord {
        final DeliveryTask task;
        String status;
        String detail;

        DeliveryRecord(DeliveryTask task, String status, String detail) {
            this.task = task;
            this.status = status;
            this.detail = detail;
        }
    }

    private final Context context;
    private final DeliveryHandler deliveryHandler;
    private final SharedPreferences prefs;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService registrationExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService receiptExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, DeliveryRecord> deliveries = new LinkedHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private String botId;
    private String authToken;
    private int httpPort;
    private String panelUrl;

    BotHttpServer(Context context, DeliveryHandler deliveryHandler) {
        this.context = context.getApplicationContext();
        this.deliveryHandler = deliveryHandler;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void start() {
        if (running) return;
        botId = persistedOrCreate(KEY_BOT_ID, false);
        authToken = persistedOrCreate(KEY_AUTH_TOKEN, true);
        httpPort = clampPort(prefs.getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT));
        panelUrl = normalizeUrl(prefs.getString(KEY_PANEL_URL, DEFAULT_PANEL_URL));
        try {
            serverSocket = new ServerSocket(httpPort);
            running = true;
            acceptExecutor.execute(this::acceptLoop);
            registrationExecutor.scheduleWithFixedDelay(this::registerWithPanel, 1000L, REGISTER_INTERVAL_MS, TimeUnit.MILLISECONDS);
            BotLog.i(context, "robot.http.start", "机器人图片投递服务已启动 port=" + httpPort + " panel=" + panelUrl);
        } catch (IOException error) {
            BotLog.e(context, "robot.http.start.fail", "机器人 HTTP 服务启动失败 port=" + httpPort + " error=" + error.getMessage());
            closeServer();
        }
    }

    synchronized void stop() {
        running = false;
        closeServer();
        registrationExecutor.shutdownNow();
        receiptExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        acceptExecutor.shutdownNow();
        BotLog.i(context, "robot.http.stop", "机器人 HTTP 服务已停止");
    }

    void complete(DeliveryTask task, boolean sent, String detail) {
        if (task == null || task.deliveryId.isEmpty()) return;
        reportReceipt(task.deliveryId, sent ? "sent" : "failed", detail, 1);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                requestExecutor.execute(() -> handle(socket));
            } catch (IOException error) {
                if (running) BotLog.w(context, "robot.http.accept.fail", error.getMessage());
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(RECEIPT_READ_TIMEOUT_MS);
            InputStream input = client.getInputStream();
            String headerText = readHeaders(input);
            if (headerText.isEmpty()) return;
            String[] headerLines = headerText.split("\\r\\n");
            String[] requestParts = headerLines[0].split("\\s+", 3);
            if (requestParts.length < 2) {
                writeJson(client, 400, jsonError("invalid_request"));
                return;
            }
            String method = requestParts[0].toUpperCase(Locale.ROOT);
            String path = requestParts[1];
            String token = "";
            int contentLength = 0;
            for (int i = 1; i < headerLines.length; i++) {
                int colon = headerLines[i].indexOf(':');
                if (colon <= 0) continue;
                String key = headerLines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = headerLines[i].substring(colon + 1).trim();
                if ("content-length".equals(key)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = -1;
                    }
                } else if ("x-vxbot-token".equals(key)) {
                    token = value;
                }
            }
            String route = new URI(path).getPath();
            if ("GET".equals(method) && "/vxbot/v1/health".equals(route)) {
                writeJson(client, 200, healthJson());
                return;
            }
            if (!"POST".equals(method) || !"/vxbot/v1/messages".equals(route)) {
                writeJson(client, 404, jsonError("not_found"));
                return;
            }
            if (!authToken.equals(token)) {
                writeJson(client, 401, jsonError("robot_auth_failed"));
                return;
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeJson(client, 413, jsonError("request_body_too_large"));
                return;
            }
            JSONObject payload;
            try {
                payload = new JSONObject(readBody(input, contentLength));
            } catch (Exception error) {
                writeJson(client, 400, jsonError("invalid_json"));
                return;
            }
            String kind = payload.optString("kind", "").trim().toLowerCase(Locale.ROOT);
            String targetType = payload.optString("targetType", "group").trim().toLowerCase(Locale.ROOT);
            String target = payload.optString("target", "").trim();
            String title = payload.optString("title", "iBox 提醒").trim();
            String text = payload.optString("text", "").trim();
            String event = payload.optString("event", "").trim();
            String deliveryId = payload.optString("deliveryId", payload.optString("requestId", "")).trim();
            if (!"image".equals(kind)) {
                writeJson(client, 409, jsonError("robot_image_delivery_required"));
                return;
            }
            if (!("group".equals(targetType) || "person".equals(targetType))) {
                writeJson(client, 400, jsonError("robot_target_type_invalid"));
                return;
            }
            if (target.isEmpty() || target.length() > 200 || text.isEmpty() || text.length() > 5000 || title.length() > 120 || !validDeliveryId(deliveryId)) {
                writeJson(client, 400, jsonError("robot_image_delivery_invalid"));
                return;
            }
            DeliveryTask task = new DeliveryTask(deliveryId, targetType, target, title, text, event);
            DeliveryRecord existing = rememberAccepted(task);
            if (existing != null) {
                reportReceipt(existing.task.deliveryId, existing.status, existing.detail, 1);
                writeJson(client, 202, new JSONObject().put("success", true).put("deliveryId", existing.task.deliveryId).put("status", existing.status));
                return;
            }
            reportReceipt(deliveryId, "accepted", "", 1);
            boolean queued = deliveryHandler != null && deliveryHandler.enqueue(task);
            if (!queued) {
                complete(task, false, "delivery_queue_rejected");
                writeJson(client, 503, jsonError("robot_delivery_queue_rejected"));
                return;
            }
            writeJson(client, 202, new JSONObject().put("success", true).put("deliveryId", deliveryId).put("status", "accepted"));
        } catch (Exception error) {
            BotLog.w(context, "robot.http.request.fail", error.getMessage());
        }
    }

    private synchronized DeliveryRecord rememberAccepted(DeliveryTask task) {
        DeliveryRecord previous = deliveries.get(task.deliveryId);
        if (previous != null) return previous;
        deliveries.put(task.deliveryId, new DeliveryRecord(task, "accepted", ""));
        while (deliveries.size() > DELIVERY_CACHE_LIMIT) {
            String oldest = deliveries.keySet().iterator().next();
            deliveries.remove(oldest);
        }
        return null;
    }

    private synchronized void rememberReceipt(String deliveryId, String status, String detail) {
        DeliveryRecord record = deliveries.get(deliveryId);
        if (record == null) return;
        if ("sent".equals(record.status) || "failed".equals(record.status)) return;
        record.status = status;
        record.detail = detail == null ? "" : detail;
    }

    private void reportReceipt(String deliveryId, String status, String detail, int attempt) {
        rememberReceipt(deliveryId, status, detail);
        try {
            postReceipt(deliveryId, status, detail);
            BotLog.i(context, "robot.http.receipt", "任务回执已上报 deliveryId=" + deliveryId + " status=" + status);
        } catch (Exception error) {
            BotLog.w(context, "robot.http.receipt.fail", "任务回执上报失败 deliveryId=" + deliveryId + " status=" + status + " attempt=" + attempt + " error=" + error.getMessage());
            if (attempt < RECEIPT_MAX_ATTEMPTS && running && !receiptExecutor.isShutdown()) {
                receiptExecutor.schedule(() -> reportReceipt(deliveryId, status, detail, attempt + 1),
                        RECEIPT_RETRY_DELAY_MS * attempt, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void postReceipt(String deliveryId, String status, String detail) throws Exception {
        if (panelUrl.isEmpty()) throw new IOException("panel_url_missing");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(panelUrl + "/api/ibox/v304/robots/deliveries/" + deliveryId + "/receipt");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(RECEIPT_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-VXBot-Token", authToken);
            JSONObject body = new JSONObject()
                    .put("botId", botId)
                    .put("deliveryId", deliveryId)
                    .put("status", status)
                    .put("detail", detail == null ? "" : detail);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("panel_receipt_http_" + responseCode);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) >= 0) {
            output.write(current);
            if (output.size() > 16 * 1024) throw new IOException("request_headers_too_large");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                int length = bytes.length;
                if (length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n'
                        && bytes[length - 2] == '\r' && bytes[length - 1] == '\n') {
                    return new String(bytes, 0, length - 4, StandardCharsets.US_ASCII);
                }
            }
            previous = current;
        }
        return "";
    }

    private String readBody(InputStream input, int contentLength) throws IOException {
        if (contentLength == 0) return "";
        byte[] bytes = new byte[contentLength];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset != bytes.length) throw new IOException("request_body_incomplete");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private JSONObject healthJson() throws Exception {
        JSONArray capabilities = new JSONArray().put("image").put("group").put("person").put("receipt");
        return new JSONObject()
                .put("success", true)
                .put("botId", botId)
                .put("versionCode", APP_VERSION_CODE)
                .put("versionName", APP_VERSION_NAME)
                .put("httpPort", httpPort)
                .put("capabilities", capabilities)
                .put("uptimeMs", SystemClock.elapsedRealtime());
    }

    private JSONObject jsonError(String message) throws Exception {
        return new JSONObject().put("success", false).put("message", message);
    }

    private void writeJson(Socket socket, int status, JSONObject body) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        String reason = status == 200 ? "OK" : status == 202 ? "Accepted" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }

    private void registerWithPanel() {
        if (!running || panelUrl.isEmpty()) return;
        HttpURLConnection connection = null;
        try {
            URL url = new URL(panelUrl + "/api/ibox/v304/robots/register");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(REGISTER_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject()
                    .put("botId", botId)
                    .put("authToken", authToken)
                    .put("httpPort", httpPort)
                    .put("versionCode", APP_VERSION_CODE)
                    .put("versionName", APP_VERSION_NAME)
                    .put("capabilities", new JSONArray().put("image").put("group").put("person").put("receipt"));
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                BotLog.i(context, "robot.http.register", "机器人已向面板注册 botId=" + botId + " http=" + httpPort);
            } else {
                BotLog.w(context, "robot.http.register.fail", "面板注册失败 http=" + status);
            }
        } catch (Exception error) {
            BotLog.w(context, "robot.http.register.fail", "面板注册请求失败: " + error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String persistedOrCreate(String key, boolean secret) {
        String current = prefs.getString(key, "").trim();
        if (!current.isEmpty()) return current;
        String generated = secret ? randomHex(32) : java.util.UUID.randomUUID().toString();
        prefs.edit().putString(key, generated).apply();
        return generated;
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(length);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value));
        return result.substring(0, length);
    }

    private static boolean validDeliveryId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }

    private static int clampPort(int value) {
        return value >= 1024 && value <= 65535 ? value : DEFAULT_HTTP_PORT;
    }

    private static String normalizeUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? DEFAULT_PANEL_URL : result;
    }

    private synchronized void closeServer() {
        if (serverSocket == null) return;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
    }
}
