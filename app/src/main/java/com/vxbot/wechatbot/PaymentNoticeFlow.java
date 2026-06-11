package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaymentNoticeFlow {
    private static final String PREFS = "payment_notice_state";
    private static final String PENDING_EVENTS = "pending_events";
    private static final String[] FIELD_BOUNDARIES = {
            "付款方", "付款人", "来自", "对方", "备注", "留言", "说明", "商品", "订单", "交易单号", "收款金额", "金额"
    };
    private static final Pattern AMOUNT_AFTER_KEYWORD = Pattern.compile(
            "(?:收款|收钱|到账|已收款|二维码收款|收款金额|金额)[^0-9¥￥]{0,24}[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*元?");
    private static final Pattern AMOUNT_YUAN = Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元");
    private static final Pattern AMOUNT_SYMBOL = Pattern.compile("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)");

    public static boolean looksLikePaymentNotice(WxMessage message) {
        if (message == null) {
            return false;
        }
        String text = allText(message);
        String sourceText = firstNonEmpty(message.rawTitle, "") + "\n" + firstNonEmpty(message.rawContent, "");
        if (text.isEmpty()) {
            return false;
        }
        boolean paymentSource = containsAny(message.rawTitle, "微信支付", "微信收款", "收款助手")
                || containsAny(sourceText, "收款到账通知", "二维码收款到账", "微信支付收款", "微信收款助手");
        boolean paymentAction = containsAny(text, "收款", "到账", "已收款", "收钱");
        return paymentSource && paymentAction && extractAmount(text) != null;
    }

    public void handlePayment(Context context, BotConfig config, WxMessage message) {
        if (!config.enablePaymentListener) {
            BotLog.i(context, "payment.skip.disabled", "支付监听开关已关闭");
            return;
        }
        PaymentEvent event = parse(message);
        if (event == null) {
            BotLog.w(context, "payment.parse.miss", compact(allText(message)));
            return;
        }
        if (alreadySeen(context, event.notifyKey)) {
            BotLog.i(context, "payment.skip.duplicate", "notifyKey=" + event.notifyKey + " amount=" + event.amount);
            return;
        }
        savePending(context, event);
        BotLog.write(context, "SUCCESS", "payment.notice.hit",
                "amount=" + event.amount + " payer=" + event.payer + " remark=" + event.remark
                        + " notifyKey=" + event.notifyKey);
        if (config.paymentCallbackUrl == null || config.paymentCallbackUrl.trim().isEmpty()) {
            BotLog.w(context, "payment.callback.skip", "未配置支付回调地址 notifyKey=" + event.notifyKey);
            return;
        }
        Exception last = null;
        for (int i = 0; i < 2; i++) {
            try {
                postCallback(config, event);
                markSeen(context, event.notifyKey);
                removePending(context, event.notifyKey);
                BotLog.write(context, "SUCCESS", "payment.callback.done",
                        "attempt=" + (i + 1) + " amount=" + event.amount + " url=" + config.paymentCallbackUrl);
                return;
            } catch (Exception e) {
                last = e;
                BotLog.w(context, "payment.callback.retry", "attempt=" + (i + 1) + " error=" + e.getMessage());
                sleep(800L);
            }
        }
        BotLog.e(context, "payment.callback.error",
                (last == null ? "unknown" : last.getMessage()) + " / 已保留本地待重试 notifyKey=" + event.notifyKey);
    }

    public void flushPending(Context context, BotConfig config) {
        if (!config.enablePaymentListener) {
            return;
        }
        JSONArray pending = pendingArray(context);
        if (pending.length() == 0) {
            return;
        }
        BotLog.i(context, "payment.pending.flush.start", "count=" + pending.length());
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            PaymentEvent event = item == null ? null : PaymentEvent.fromJson(item);
            if (event == null || alreadySeen(context, event.notifyKey)) {
                continue;
            }
            try {
                postCallback(config, event);
                markSeen(context, event.notifyKey);
                removePending(context, event.notifyKey);
                BotLog.write(context, "SUCCESS", "payment.pending.flush.done",
                        "amount=" + event.amount + " notifyKey=" + event.notifyKey);
            } catch (Exception e) {
                BotLog.w(context, "payment.pending.flush.fail",
                        "notifyKey=" + event.notifyKey + " error=" + e.getMessage());
            }
        }
    }

    private static PaymentEvent parse(WxMessage message) {
        String raw = allText(message);
        String amount = extractAmount(raw);
        if (amount == null) {
            return null;
        }
        String title = firstNonEmpty(message.rawTitle, message.sessionName);
        String notifyKey = paymentNotifyKey(message, title, raw, amount);
        return new PaymentEvent(
                amount,
                "CNY",
                extractAfter(raw, "付款方", "付款人", "来自", "对方"),
                extractAfter(raw, "备注", "留言", "说明", "商品"),
                title,
                firstNonEmpty(message.text, message.rawContent),
                raw,
                message.postTime,
                notifyKey);
    }

    private static String paymentNotifyKey(WxMessage message, String title, String raw, String amount) {
        String sourceKey = firstNonEmpty(message.notificationKey, "");
        return sha256(sourceKey + "|" + title + "|" + amount + "|" + message.postTime + "|" + raw);
    }

    private static void postCallback(BotConfig config, PaymentEvent event) throws Exception {
        JSONObject payload = event.toJson();

        HttpURLConnection conn = (HttpURLConnection) new URL(config.paymentCallbackUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(config.paymentCallbackTimeoutMs);
        conn.setReadTimeout(config.paymentCallbackTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("X-VXBot-Event", "wechat_receipt");
        conn.setRequestProperty("X-VXBot-Notify-Key", event.notifyKey);
        if (config.paymentCallbackSecret != null && !config.paymentCallbackSecret.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.paymentCallbackSecret.trim());
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + trim(body));
        }
    }

    private static JSONArray pendingArray(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PENDING_EVENTS, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void savePending(Context context, PaymentEvent event) {
        JSONArray old = pendingArray(context);
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = Math.max(0, old.length() - 80); i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (event.notifyKey.equals(item.optString("notifyKey", ""))) {
                next.put(event.toJson());
                replaced = true;
            } else {
                next.put(item);
            }
        }
        if (!replaced) {
            next.put(event.toJson());
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_EVENTS, next.toString())
                .apply();
    }

    private static void removePending(Context context, String notifyKey) {
        JSONArray old = pendingArray(context);
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null || notifyKey.equals(item.optString("notifyKey", ""))) {
                continue;
            }
            next.put(item);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_EVENTS, next.toString())
                .apply();
    }

    private static boolean alreadySeen(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = prefs.getLong(key, 0L);
        long now = System.currentTimeMillis();
        return at > 0L && now - at < 24L * 60L * 60L * 1000L;
    }

    private static void markSeen(Context context, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(key, System.currentTimeMillis())
                .apply();
    }

    private static String allText(WxMessage message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendUnique(sb, message.rawTitle);
        appendUnique(sb, message.rawContent);
        appendUnique(sb, message.sessionName);
        appendUnique(sb, message.senderName);
        appendUnique(sb, message.text);
        return sb.toString().trim();
    }

    private static void appendUnique(StringBuilder sb, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return;
        }
        String current = sb.toString();
        if (current.equals(text) || current.contains(text)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(text);
    }

    private static String extractAmount(String text) {
        String value = text == null ? "" : text.replace(',', '.');
        String amount = firstMatch(AMOUNT_AFTER_KEYWORD, value);
        if (amount == null) {
            amount = firstMatch(AMOUNT_SYMBOL, value);
        }
        if (amount == null) {
            amount = firstMatch(AMOUNT_YUAN, value);
        }
        if (amount == null) {
            return null;
        }
        try {
            return String.format(Locale.US, "%.2f", Double.parseDouble(amount));
        } catch (Exception ignored) {
            return amount;
        }
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractAfter(String raw, String... labels) {
        String text = raw == null ? "" : raw;
        for (String label : labels) {
            int index = text.indexOf(label);
            if (index < 0) {
                continue;
            }
            int start = index + label.length();
            while (start < text.length() && isSeparator(text.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '，'
                    && text.charAt(end) != ',' && text.charAt(end) != ';' && text.charAt(end) != '；') {
                if (end > start && startsWithAny(text, end, FIELD_BOUNDARIES)) {
                    break;
                }
                end++;
            }
            String value = text.substring(start, end).trim();
            if (!value.isEmpty() && value.length() <= 48) {
                return value;
            }
        }
        return "";
    }

    private static boolean startsWithAny(String text, int offset, String... values) {
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (offset + value.length() <= text.length() && text.startsWith(value, offset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSeparator(char ch) {
        return ch == ':' || ch == '：' || Character.isWhitespace(ch);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private static String compact(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PaymentEvent {
        final String amount;
        final String currency;
        final String payer;
        final String remark;
        final String title;
        final String text;
        final String raw;
        final long postTime;
        final String notifyKey;

        PaymentEvent(String amount, String currency, String payer, String remark, String title, String text,
                     String raw, long postTime, String notifyKey) {
            this.amount = amount;
            this.currency = currency;
            this.payer = payer;
            this.remark = remark;
            this.title = title;
            this.text = text;
            this.raw = raw;
            this.postTime = postTime;
            this.notifyKey = notifyKey;
        }

        JSONObject toJson() {
            JSONObject payload = new JSONObject();
            try {
                payload.put("source", "wechat");
                payload.put("eventType", "wechat_receipt");
                payload.put("amount", amount);
                payload.put("currency", currency);
                payload.put("payer", payer);
                payload.put("remark", remark);
                payload.put("title", title);
                payload.put("text", text);
                payload.put("raw", raw);
                payload.put("postTime", postTime);
                payload.put("notifyKey", notifyKey);
            } catch (Exception ignored) {
            }
            return payload;
        }

        static PaymentEvent fromJson(JSONObject json) {
            if (json == null) {
                return null;
            }
            String notifyKey = json.optString("notifyKey", "");
            String amount = json.optString("amount", "");
            if (notifyKey.isEmpty() || amount.isEmpty()) {
                return null;
            }
            return new PaymentEvent(
                    amount,
                    json.optString("currency", "CNY"),
                    json.optString("payer", ""),
                    json.optString("remark", ""),
                    json.optString("title", ""),
                    json.optString("text", ""),
                    json.optString("raw", ""),
                    json.optLong("postTime", 0L),
                    notifyKey);
        }
    }
}
