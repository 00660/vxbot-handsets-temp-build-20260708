package com.vxbot.wechatbot;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LicensePanelClient {
    private static final Pattern EXPLICIT_DURATION = Pattern.compile(
            "(\\d{1,5})\\s*(小时|小時|时|時|天|日|周|星期|礼拜|週|个月|個月|月|年|hours?|days?|weeks?|months?|years?)",
            Pattern.CASE_INSENSITIVE);

    public String request(Context context, BotConfig config, String text) throws Exception {
        Request request = parseRequest(config, text);
        if (request == null) {
            throw new IllegalStateException("未识别到注册码/授权码请求");
        }
        if (!isBlank(request.error)) {
            return request.error;
        }
        String baseUrl = config.licensePanelBaseUrl == null ? "" : config.licensePanelBaseUrl.trim().replaceAll("/+$", "");
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("license-panel baseUrl 未配置");
        }
        BotLog.i(context, "license.panel.post", "path=" + request.path
                + " kind=" + request.kind
                + " payload=" + request.payload.toString());
        JSONObject body = postJson(baseUrl + request.path, request.payload, config.licensePanelTimeoutMs);
        BotLog.i(context, "license.panel.done", "kind=" + request.kind + " response=" + body.toString());
        return formatReply(request.kind, body);
    }

    private Request parseRequest(BotConfig config, String text) throws Exception {
        String kind = detectKind(text);
        if (kind.isEmpty()) {
            return null;
        }
        String note = extractNote(text);
        Duration duration = parseDuration(text);
        if ("jipin".equals(kind)) {
            String machineCode = extractMachineCode(text);
            if (machineCode.isEmpty()) {
                return Request.error(kind, "生成注册码要带机器码，例如：极品注册码 机器码 BY2026xxxx 月卡");
            }
            JSONObject payload = new JSONObject()
                    .put("machine_code", machineCode)
                    .put("customer", extractCustomer(text))
                    .put("expires_at", resolveEndDate(text, duration))
                    .put("note", note);
            return new Request(kind, "/api/generate", payload);
        }
        if ("yuan".equals(kind)) {
            String deviceId = extractDeviceId(text);
            if (deviceId.isEmpty()) {
                return Request.error(kind, "元统计授权要带设备 ID。例：元统计授权 设备ID 25912129 月卡");
            }
            JSONObject payload = new JSONObject()
                    .put("device_id", deviceId)
                    .put("android_id", "")
                    .put("package_name", extractPackageName(config, text))
                    .put("duration_amount", String.valueOf(duration.amount))
                    .put("duration_unit", duration.unit)
                    .put("note", note);
            String secretKey = extractSecretKey(text);
            if (!secretKey.isEmpty()) {
                payload.put("secret_key", secretKey);
            }
            return new Request(kind, "/api/yuan/generate", payload);
        }
        JSONObject payload = new JSONObject()
                .put("duration_amount", duration.amount)
                .put("duration_unit", duration.unit)
                .put("end_time", extractEndDate(text))
                .put("note", note);
        return new Request(kind, "/api/gxaz/registration-codes/generate", payload);
    }

    private static String detectKind(String text) {
        String compact = normalize(text);
        if (compact.isEmpty()) {
            return "";
        }
        if (containsAny(compact, "AI统计", "ai统计", "GXAZ", "gxaz", "登录密码", "登录码")) {
            return "gxaz";
        }
        if (containsAny(compact, "元统计", "元授权", "元统计授权", "授权码", "设备ID", "设备id")) {
            return "yuan";
        }
        if (containsAny(compact, "极品统计", "极品", "注册码", "机器码", "注册")) {
            return "jipin";
        }
        return "";
    }

    private static Duration parseDuration(String text) {
        Duration card = parseCardDuration(text);
        if (card != null) {
            return card;
        }
        Matcher match = EXPLICIT_DURATION.matcher(text == null ? "" : text);
        if (!match.find()) {
            return new Duration(1, "months");
        }
        int amount = Integer.parseInt(match.group(1));
        String unitText = match.group(2).toLowerCase(Locale.ROOT);
        String unit = "days";
        if (unitText.matches(".*(小时|小時|时|時|hour).*")) {
            unit = "hours";
        } else if (unitText.matches(".*(周|週|星期|礼拜|week).*")) {
            amount *= 7;
        } else if (unitText.matches(".*(月|month).*")) {
            unit = "months";
        } else if (unitText.matches(".*(年|year).*")) {
            unit = "years";
        }
        return new Duration(amount, unit);
    }

    private static Duration parseCardDuration(String text) {
        String compact = normalize(text);
        if (Pattern.compile("天卡|日卡|一天卡|1天卡").matcher(compact).find()) {
            return new Duration(1, "days");
        }
        if (Pattern.compile("周卡|星期卡|礼拜卡|一周卡|1周卡").matcher(compact).find()) {
            return new Duration(7, "days");
        }
        if (Pattern.compile("月卡|包月|一月卡|1月卡").matcher(compact).find()) {
            return new Duration(1, "months");
        }
        if (Pattern.compile("季卡|季度卡|包季|一季卡|1季卡").matcher(compact).find()) {
            return new Duration(3, "months");
        }
        if (Pattern.compile("年卡|包年|一年卡|1年卡").matcher(compact).find()) {
            return new Duration(1, "years");
        }
        return null;
    }

    private static String resolveEndDate(String text, Duration duration) {
        String explicit = extractEndDate(text);
        return explicit.isEmpty() ? addDurationToDate(duration) : explicit;
    }

    private static String addDurationToDate(Duration duration) {
        Calendar calendar = Calendar.getInstance();
        if ("hours".equals(duration.unit)) {
            calendar.add(Calendar.HOUR_OF_DAY, duration.amount);
        } else if ("months".equals(duration.unit)) {
            calendar.add(Calendar.MONTH, duration.amount);
        } else if ("years".equals(duration.unit)) {
            calendar.add(Calendar.YEAR, duration.amount);
        } else {
            calendar.add(Calendar.DAY_OF_MONTH, duration.amount);
        }
        return String.format(Locale.CHINA, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String extractEndDate(String text) {
        Matcher match = Pattern.compile("(?:到期(?:日|时间)?|截至|截止|有效期到)[:：\\s]*(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)")
                .matcher(text == null ? "" : text);
        return match.find() ? formatDate(match.group(1)) : "";
    }

    private static String extractNote(String text) {
        Matcher match = Pattern.compile("(?:备注|注释|说明)[:：\\s]*([^\\n]+)").matcher(text == null ? "" : text);
        return match.find() ? clean(match.group(1)) : "";
    }

    private static String extractCustomer(String text) {
        Matcher match = Pattern.compile("(?:客户|客户名|姓名)[:：\\s]*([^\\s，,。；;]+)").matcher(text == null ? "" : text);
        return match.find() ? clean(match.group(1)) : "";
    }

    private static String extractMachineCode(String text) {
        String raw = text == null ? "" : text;
        Matcher match = Pattern.compile("机器码[:：\\s]*([A-Za-z0-9]{8,})", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (match.find()) {
            return match.group(1).toUpperCase(Locale.ROOT);
        }
        match = Pattern.compile("\\b(BY2026[A-Za-z0-9]{2,})\\b", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (match.find()) {
            return match.group(1).toUpperCase(Locale.ROOT);
        }
        match = Pattern.compile("[A-Za-z0-9]{8,}").matcher(raw);
        while (match.find()) {
            String value = match.group();
            if (!value.matches("(?i)^(com|http|https)$")) {
                return value.toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static String extractDeviceId(String text) {
        String raw = text == null ? "" : text;
        Matcher match = Pattern.compile("设备\\s*(?:ID|Id|id)?[:：\\s]*(\\d{3,})", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (match.find()) {
            return match.group(1);
        }
        match = Pattern.compile("device\\s*id[:：\\s]*(\\d{3,})", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (match.find()) {
            return match.group(1);
        }
        if (containsAny(normalize(raw), "元统计", "元授权", "授权码")) {
            match = Pattern.compile("\\b(\\d{5,})\\b").matcher(raw);
            if (match.find()) {
                return match.group(1);
            }
        }
        return "";
    }

    private static String extractPackageName(BotConfig config, String text) {
        Matcher match = Pattern.compile("(?:包名|package(?:_name)?)[:：\\s]*([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE)
                .matcher(text == null ? "" : text);
        if (match.find()) {
            return match.group(1);
        }
        return config.defaultYuanPackage;
    }

    private static String extractSecretKey(String text) {
        Matcher match = Pattern.compile("(?:密钥|secret(?:_key)?|key)[:：\\s]*([^\\s，,。；;]+)", Pattern.CASE_INSENSITIVE)
                .matcher(text == null ? "" : text);
        return match.find() ? match.group(1) : "";
    }

    private static JSONObject postJson(String url, JSONObject payload, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(Math.min(timeoutMs, 15000));
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        JSONObject json = isBlank(body) ? null : new JSONObject(body);
        if (code < 200 || code >= 300 || json == null) {
            throw new IllegalStateException(errorFromBody(json, "生成接口失败: " + code));
        }
        return json;
    }

    private static String formatReply(String kind, JSONObject body) {
        if ("jipin".equals(kind)) {
            return joinLines(
                    "注册码生成好了：",
                    body.optString("registration_code", ""),
                    body.optString("checked_serial", "").isEmpty() ? "" : "校验串：" + body.optString("checked_serial"),
                    body.optString("expires_at", "").isEmpty() ? "" : "到期：" + body.optString("expires_at"));
        }
        if ("yuan".equals(kind)) {
            return joinLines(
                    "元统计授权码生成好了：",
                    body.optString("activation_code", ""),
                    body.optString("device_id", "").isEmpty() ? "" : "设备ID：" + body.optString("device_id"),
                    body.optString("expire_at", "").isEmpty() ? "" : "到期：" + body.optString("expire_at"),
                    body.optString("license_value", "").isEmpty() ? "" : "授权值：" + body.optString("license_value"));
        }
        JSONObject item = body.optJSONObject("item");
        String endTime = item == null ? "" : item.optString("endTime", "");
        if (endTime.isEmpty()) {
            endTime = body.optString("endTime", "");
        }
        return joinLines(
                "AI统计登录密码生成好了：",
                !body.optString("code", "").isEmpty() ? body.optString("code") : body.optString("registration_code", ""),
                endTime.isEmpty() ? "" : "到期：" + endTime);
    }

    private static String errorFromBody(JSONObject body, String fallback) {
        if (body != null && !body.optString("error", "").isEmpty()) {
            return body.optString("error");
        }
        if (body != null && !body.optString("message", "").isEmpty()) {
            return body.optString("message");
        }
        return fallback;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String joinLines(String... lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line.trim());
        }
        return builder.toString();
    }

    private static String formatDate(String value) {
        String raw = value == null ? "" : value.trim()
                .replace('年', '-')
                .replace('月', '-')
                .replace("日", "")
                .replace('/', '-')
                .replace('.', '-');
        String[] parts = raw.split("-");
        if (parts.length != 3) {
            return raw;
        }
        return parts[0] + "-" + pad2(parts[1]) + "-" + pad2(parts[2]);
    }

    private static String pad2(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("^[：:，,\\s]+|[。.!！\\s]+$", "").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (!term.isEmpty() && value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Duration {
        final int amount;
        final String unit;

        Duration(int amount, String unit) {
            this.amount = amount;
            this.unit = unit;
        }
    }

    private static final class Request {
        final String kind;
        final String path;
        final JSONObject payload;
        final String error;

        Request(String kind, String path, JSONObject payload) {
            this.kind = kind;
            this.path = path;
            this.payload = payload;
            this.error = "";
        }

        static Request error(String kind, String error) {
            return new Request(kind, "", new JSONObject(), error);
        }

        private Request(String kind, String path, JSONObject payload, String error) {
            this.kind = kind;
            this.path = path;
            this.payload = payload;
            this.error = error;
        }
    }
}
