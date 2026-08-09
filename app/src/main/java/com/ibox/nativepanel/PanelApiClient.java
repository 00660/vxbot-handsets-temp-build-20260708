package com.ibox.nativepanel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class PanelApiClient {
    private static final String BASE_URL = "http://192.168.2.204:5000/api/ibox/v304";

    JSONObject request(String method, String rawPath, JSONObject body) throws Exception {
        String verb = method == null ? "GET" : method.trim().toUpperCase();
        if (verb.isEmpty()) verb = "GET";
        String endpoint = endpoint(rawPath);
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + endpoint).openConnection();
        try {
            connection.setRequestMethod(verb);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String payload = readBody(stream);
            if (payload.trim().isEmpty()) {
                throw new NativeEngine.NativeException("服务端返回为空（HTTP " + status + "）");
            }
            JSONObject response = new JSONObject(payload);
            if (status < 200 || status >= 300 || !response.optBoolean("success", false)) {
                String message = response.optString("message", "服务端请求失败").trim();
                throw new NativeEngine.NativeException(message.isEmpty() ? "服务端请求失败（HTTP " + status + "）" : message);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private String endpoint(String rawPath) throws NativeEngine.NativeException {
        String path = rawPath == null ? "" : rawPath.trim();
        if (!path.startsWith("/native/")) {
            throw new NativeEngine.NativeException("原生功能路径不存在：" + path);
        }
        return path.substring("/native".length());
    }

    private String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }
}
