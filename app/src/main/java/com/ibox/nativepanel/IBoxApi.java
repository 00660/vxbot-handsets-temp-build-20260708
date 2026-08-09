package com.ibox.nativepanel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Small synchronous client for the existing iBox v304 service API. */
public final class IBoxApi {
    private final String baseUrl;

    public IBoxApi(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        baseUrl = value;
        this.baseUrl = value;
    }

    public JSONObject request(String method, String path, JSONObject body) throws Exception {
        if (baseUrl.isEmpty()) throw new ApiException("服务地址为空");
        URL url = new URL(baseUrl + (path.startsWith("/") ? path : "/" + path));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method == null ? "GET" : method.toUpperCase());
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "iBox-Native-Android/1.0");
        connection.setRequestProperty("X-Request-ID", UUID.randomUUID().toString());
        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = read(stream);
        connection.disconnect();
        JSONObject result;
        try {
            result = text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception error) {
            throw new ApiException("服务返回不是 JSON（HTTP " + status + "）");
        }
        if (status < 200 || status >= 300 || !result.optBoolean("success", false)) {
            String message = result.optString("businessMessage");
            if (message.isEmpty()) message = result.optString("message");
            if (message.isEmpty()) message = "请求失败（HTTP " + status + "）";
            throw new ApiException(message, status);
        }
        return result;
    }

    private String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line);
        }
        return output.toString();
    }

    public static final class ApiException extends Exception {
        public final int status;

        ApiException(String message) {
            this(message, 0);
        }

        ApiException(String message, int status) {
            super(message);
            this.status = status;
        }
    }
}
