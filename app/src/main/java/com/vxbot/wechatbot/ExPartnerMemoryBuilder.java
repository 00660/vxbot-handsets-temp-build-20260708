package com.vxbot.wechatbot;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public final class ExPartnerMemoryBuilder {
    private static final int MAX_IMAGE_SOURCES = 8;
    private static final int MAX_FILE_TEXT_BYTES = 80 * 1024;

    private ExPartnerMemoryBuilder() {
    }

    public static File sourceDir(Context context) {
        return new File(new File(context.getFilesDir(), "ex"), "sources");
    }

    public static int sourceCount(Context context) {
        File[] files = listSources(context);
        return files == null ? 0 : files.length;
    }

    public static String distill(Context context, BotConfig config) throws Exception {
        if (config.chatEndpoint == null || config.chatEndpoint.trim().isEmpty()) {
            throw new IllegalStateException("chatEndpoint 未配置");
        }
        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        payload.put("temperature", 0.45);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt()));

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", buildTextContext(config, context)));
        appendImage(content, config.exPhotoPath, "前任照片");

        int imageCount = 0;
        File[] sources = listSources(context);
        if (sources != null) {
            for (File file : sources) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                if (isImage(file) && imageCount < MAX_IMAGE_SOURCES) {
                    appendImage(content, file.getAbsolutePath(), "聊天/社交截图 " + file.getName());
                    imageCount++;
                } else if (!isImage(file)) {
                    content.put(new JSONObject().put("type", "text").put("text",
                            "材料文件 " + file.getName() + "：\n" + readTextFile(file)));
                }
            }
        }

        messages.put(new JSONObject().put("role", "user").put("content", content));
        payload.put("messages", messages);
        BotLog.i(context, "ex.distill.request", "开始蒸馏前任资料 name=" + config.exName
                + " sources=" + sourceCount(context)
                + " bytes=" + payload.toString().getBytes(StandardCharsets.UTF_8).length);
        String reply = postChat(config, payload);
        if (reply == null || reply.trim().isEmpty()) {
            throw new IllegalStateException("上游返回空前任画像");
        }
        return reply.trim();
    }

    private static String systemPrompt() {
        return "你是前任关系资料蒸馏器。根据用户提供的微信聊天记录、QQ 聊天记录、朋友圈/微博截图、照片和口述材料，生成一份可注入聊天机器人的 Relationship Memory + Persona。\n"
                + "输出必须是中文 Markdown，结构固定：\n"
                + "## Relationship Memory\n"
                + "- 关系时间线\n- 日常模式\n- 共同经历\n- Inside Jokes\n- 饮食偏好\n- 争吵模式\n- 甜蜜时刻\n- 分手记忆\n"
                + "## Persona\n"
                + "- Layer 0：硬规则\n- Layer 1：身份\n- Layer 2：说话风格\n- Layer 3：情感模式\n- Layer 4：关系行为\n"
                + "要求：只基于材料总结；信息不足要标注“信息不足，使用保守推断”；保留棱角，不要美化成完美恋人；最终内容用于机器人模拟说话风格。";
    }

    private static String buildTextContext(BotConfig config, Context context) {
        StringBuilder text = new StringBuilder();
        text.append("前任花名/代号：").append(config.exName == null || config.exName.isEmpty() ? "前任" : config.exName).append('\n');
        if (config.exManualText != null && !config.exManualText.trim().isEmpty()) {
            text.append("用户粘贴/口述材料：\n").append(config.exManualText.trim()).append('\n');
        }
        text.append("本地材料数量：").append(sourceCount(context)).append('\n');
        text.append("请把截图里的聊天文字、朋友圈文案、昵称、语气词、表情习惯和照片时间线一起纳入分析。");
        return text.toString();
    }

    private static void appendImage(JSONArray content, String path, String label) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        File file = new File(path.trim());
        if (!file.isFile() || file.length() <= 0 || !isImage(file)) {
            return;
        }
        content.put(new JSONObject().put("type", "text").put("text", label + "："));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", dataUrl(file))));
    }

    private static File[] listSources(Context context) {
        File dir = sourceDir(context);
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        return files;
    }

    private static boolean isImage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
    }

    private static String dataUrl(File file) throws Exception {
        String mime = mimeType(file);
        byte[] bytes = readBytes(file, 4 * 1024 * 1024);
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static String mimeType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static String readTextFile(File file) throws Exception {
        byte[] bytes = readBytes(file, MAX_FILE_TEXT_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file, int maxBytes) throws Exception {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                int allowed = Math.min(read, maxBytes - total);
                if (allowed > 0) {
                    out.write(buffer, 0, allowed);
                    total += allowed;
                }
                if (total >= maxBytes) {
                    break;
                }
            }
            return out.toByteArray();
        }
    }

    private static String postChat(BotConfig config, JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(config.chatEndpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(config.replyTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (config.apiKey != null && !config.apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey.trim());
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + trimBody(body));
        }
        return parseReply(body);
    }

    private static String parseReply(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        if (json.has("choices")) {
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject first = choices.getJSONObject(0);
                if (first.has("message")) {
                    return first.getJSONObject("message").optString("content", "");
                }
                return first.optString("text", "");
            }
        }
        if (json.has("output_text")) {
            return json.optString("output_text", "");
        }
        return "";
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

    private static String trimBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
