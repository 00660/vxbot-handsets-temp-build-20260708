package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class StickerFlow {
    private static final String STICKER_IMAGE_SIZE = "1024x1024";
    private static final String[] STICKER_LAYOUT_REFERENCE_NAMES = {
            "sticker_layout_reference_1",
            "sticker_layout_reference_2"
    };
    private static final String EMOJICUT_BASE_PROMPT = "为图中角色设计一个可爱的卡通角色，生成 16种 LINE 贴纸。姿势和文字排版要富有创意，变化丰富，设计独特。对话应为简体中文，可以是角色在不同场景，不同情绪的，角色比例二头身。\n\n"
            + "重要要求：背景必须是纯白色(#FFFFFF)，不要有任何其他颜色或图案。每个贴纸之间要有足够间距。\n"
            + "画面风格：可爱的卡通二头身角色，适合日常聊天";
    private static final String EMOJICUT_REFERENCE_NOTE = "\n\n"
            + "输入图规则：第一张输入图是人物参考，人物身份、脸型、发型、眼睛和气质必须以第一张为准；最后一张输入图是表情底图模板，只参考它的4x4排版、动作表情、装饰符号、纯白背景、贴纸间距和整体比例，不要复制模板人物身份。必须生成一张完整的4x4整图，不要单张贴纸，不要深色背景，不要底部大面积空白。";

    public boolean handle(Context context, BotConfig config, WxMessage message, WechatDriver driver) {
        try {
            ImageFlow.CapturedReference wechatReference = new ImageFlow().captureLatestWechatImage(context, config, "sticker");
            String prompt = buildPrompt(true);
            BotLog.i(context, "sticker.api.start", "开始生成 EmojiCut 表情包 base=" + config.imageEndpoint
                    + " stickerSize=" + STICKER_IMAGE_SIZE
                    + " configImageSize=" + config.imageSize
                    + " sessionName=" + message.sessionName
                    + " wechatReference=" + (wechatReference != null));
            String image = requestStickerImage(context, config, prompt, wechatReference);
            File sheet = saveImage(context, image, "vxbot_sticker_sheet_");
            int cutCount = cutStickerSheet(context, sheet);
            BotLog.i(context, "sticker.cut.done", "表情包切图完成 count=" + cutCount + " sheet=" + sheet.getAbsolutePath());
            boolean shared = new ImageFlow().shareExistingImage(context, config, sheet, message.sessionName);
            if (shared) {
                driver.leaveWechatIfForeground(context, "sticker-share-done");
            }
            if (!shared && config.dropImageTaskOnError) {
                driver.leaveWechatIfForeground(context, "sticker-share-failed-drop");
            }
            return shared;
        } catch (Exception e) {
            BotLog.e(context, "sticker.flow.error", "表情包流程异常: " + e.getMessage());
            if (config.dropImageTaskOnError) {
                try {
                    driver.leaveWechatIfForeground(context, "sticker-flow-error-drop");
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    private String buildPrompt(boolean withReferences) {
        return withReferences ? EMOJICUT_BASE_PROMPT + EMOJICUT_REFERENCE_NOTE : EMOJICUT_BASE_PROMPT;
    }

    private String requestStickerImage(Context context, BotConfig config, String prompt, ImageFlow.CapturedReference wechatReference) throws Exception {
        StickerReference layoutReference = loadRandomLayoutReference(context);
        if (wechatReference != null && wechatReference.bytes != null && wechatReference.bytes.length > 0) {
            BotLog.i(context, "sticker.wechat.reference.attach", "表情包已附带群聊图片参考 filename="
                    + wechatReference.filename
                    + " bytes=" + wechatReference.bytes.length
                    + " sha256=" + sha256Short(wechatReference.bytes));
            List<StickerReference> references = new ArrayList<>();
            references.add(new StickerReference(wechatReference.filename, wechatReference.bytes));
            addLayoutReference(context, references, layoutReference);
            return requestStickerEdit(context, config, prompt, references);
        }
        File reference = localFile(config.selfieReferencePhotoPath());
        if (reference != null) {
            byte[] bytes = readFile(reference);
            BotLog.i(context, "sticker.reference.attach", "表情包已附带人物参考图 bytes=" + reference.length()
                    + " sha256=" + sha256Short(bytes)
                    + " exMode=" + config.enableExMode);
            List<StickerReference> references = new ArrayList<>();
            references.add(new StickerReference("sticker_reference.png", bytes));
            addLayoutReference(context, references, layoutReference);
            return requestStickerEdit(context, config, prompt, references);
        }
        prompt = buildPrompt(false);
        String endpoint = config.imageEndpoint.replaceAll("/+$", "") + "/images/generations";
        JSONObject payload = new JSONObject();
        payload.put("model", BotConfig.DEFAULT_IMAGE_MODEL);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("size", STICKER_IMAGE_SIZE);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(config.imageTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (config.imageApiKey != null && !config.imageApiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.imageApiKey.trim());
        }
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        BotLog.i(context, "sticker.generate.response", "HTTP " + code + " " + summarizeImageResponse(body));
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("图片上游 HTTP " + code + " " + trim(body));
        }
        return parseImage(body);
    }

    private String requestStickerEdit(Context context, BotConfig config, String prompt, List<StickerReference> references) throws Exception {
        String endpoint = config.imageEndpoint.replaceAll("/+$", "") + "/images/edits";
        String boundary = "vxbot-sticker-" + System.currentTimeMillis();
        int totalBytes = 0;
        StringBuilder imageSummary = new StringBuilder();
        for (StickerReference reference : references) {
            totalBytes += reference.bytes.length;
            if (imageSummary.length() > 0) {
                imageSummary.append(',');
            }
            imageSummary.append(reference.filename)
                    .append(':')
                    .append(reference.bytes.length)
                    .append(':')
                    .append(sha256Short(reference.bytes));
        }
        BotLog.i(context, "sticker.edit.request", "请求图片编辑接口 endpoint=" + endpoint
                + " model=" + BotConfig.DEFAULT_IMAGE_MODEL
                + " size=" + STICKER_IMAGE_SIZE
                + " imageField=image"
                + " imageCount=" + references.size()
                + " totalBytes=" + totalBytes
                + " images=" + imageSummary
                + " promptLength=" + prompt.length());
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(config.imageTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (config.imageApiKey != null && !config.imageApiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.imageApiKey.trim());
        }
        try (OutputStream out = conn.getOutputStream()) {
            writeFormField(out, boundary, "model", BotConfig.DEFAULT_IMAGE_MODEL);
            writeFormField(out, boundary, "prompt", prompt);
            writeFormField(out, boundary, "n", "1");
            writeFormField(out, boundary, "size", STICKER_IMAGE_SIZE);
            writeFormField(out, boundary, "response_format", "b64_json");
            for (StickerReference reference : references) {
                writeImagePart(out, boundary, reference);
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        BotLog.i(context, "sticker.edit.response", "HTTP " + code + " " + summarizeImageResponse(body));
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("图片编辑上游 HTTP " + code + " " + trim(body));
        }
        return parseImage(body);
    }

    private void addLayoutReference(Context context, List<StickerReference> references, StickerReference layoutReference) {
        if (layoutReference == null || layoutReference.bytes.length == 0) {
            BotLog.w(context, "sticker.layout.reference.missing", "未找到内置表情底图参考");
            return;
        }
        references.add(layoutReference);
        BotLog.i(context, "sticker.layout.reference.attach", "表情包已随机附带底图参考 filename="
                + layoutReference.filename
                + " bytes=" + layoutReference.bytes.length
                + " sha256=" + sha256Short(layoutReference.bytes));
    }

    private StickerReference loadRandomLayoutReference(Context context) {
        int start = (int) Math.floorMod(System.nanoTime(), STICKER_LAYOUT_REFERENCE_NAMES.length);
        for (int i = 0; i < STICKER_LAYOUT_REFERENCE_NAMES.length; i++) {
            String name = STICKER_LAYOUT_REFERENCE_NAMES[(start + i) % STICKER_LAYOUT_REFERENCE_NAMES.length];
            int id = context.getResources().getIdentifier(name, "raw", context.getPackageName());
            if (id == 0) {
                continue;
            }
            try (InputStream in = context.getResources().openRawResource(id)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
                return new StickerReference(name + ".jpg", out.toByteArray());
            } catch (Exception e) {
                BotLog.w(context, "sticker.layout.reference.read.fail", name + " 读取失败: " + e.getMessage());
            }
        }
        return null;
    }

    private static void writeFormField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeImagePart(OutputStream out, String boundary, StickerReference reference) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"image\"; filename=\"" + reference.filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType(reference.filename) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(reference.bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String safeFilename(String filename) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty()) {
            return "sticker_reference.png";
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String contentType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private File saveImage(Context context, String image, String prefix) throws Exception {
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "stickers");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建表情包目录 " + dir.getAbsolutePath());
        }
        File file = new File(dir, prefix + System.currentTimeMillis() + ".png");
        byte[] bytes;
        if (image.startsWith("data:image/")) {
            int comma = image.indexOf(',');
            if (comma < 0) {
                throw new IllegalStateException("data url 格式错误");
            }
            bytes = Base64.decode(image.substring(comma + 1), Base64.DEFAULT);
        } else if (image.startsWith("http://") || image.startsWith("https://")) {
            bytes = download(image);
        } else {
            bytes = Base64.decode(image, Base64.DEFAULT);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private int cutStickerSheet(Context context, File sheet) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(sheet.getAbsolutePath());
        if (bitmap == null) {
            throw new IllegalStateException("表情包整图无法解码");
        }
        List<RectBox> rects = findStickerRects(bitmap);
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "stickers/cut_" + System.currentTimeMillis());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建切图目录 " + dir.getAbsolutePath());
        }
        int count = 0;
        for (RectBox rect : rects) {
            if (count >= 24) {
                break;
            }
            RectBox padded = rect.pad(4, bitmap.getWidth(), bitmap.getHeight());
            Bitmap crop = Bitmap.createBitmap(bitmap, padded.minX, padded.minY, padded.width(), padded.height());
            File outFile = new File(dir, "sticker_" + (count + 1) + ".png");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                crop.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            crop.recycle();
            count++;
        }
        bitmap.recycle();
        return count;
    }

    private List<RectBox> findStickerRects(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] visited = new byte[width * height];
        int[] stack = new int[width * height];
        List<RectBox> raw = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (visited[index] != 0) {
                    continue;
                }
                if (isBackground(pixels[index])) {
                    visited[index] = 1;
                    continue;
                }
                RectBox rect = floodFill(pixels, visited, stack, width, height, x, y);
                if (rect.count > 80 && rect.width() > 8 && rect.height() > 8) {
                    raw.add(rect);
                }
            }
        }
        return mergeRects(raw, 15);
    }

    private RectBox floodFill(int[] pixels, byte[] visited, int[] stack, int width, int height, int startX, int startY) {
        int top = 0;
        int start = startY * width + startX;
        stack[top++] = start;
        visited[start] = 1;
        RectBox rect = new RectBox(startX, startY, startX, startY, 0);
        while (top > 0) {
            int index = stack[--top];
            int x = index % width;
            int y = index / width;
            rect.include(x, y);
            top = pushNeighbor(pixels, visited, stack, width, height, x + 1, y, top);
            top = pushNeighbor(pixels, visited, stack, width, height, x - 1, y, top);
            top = pushNeighbor(pixels, visited, stack, width, height, x, y + 1, top);
            top = pushNeighbor(pixels, visited, stack, width, height, x, y - 1, top);
        }
        return rect;
    }

    private int pushNeighbor(int[] pixels, byte[] visited, int[] stack, int width, int height, int x, int y, int top) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return top;
        }
        int index = y * width + x;
        if (visited[index] != 0) {
            return top;
        }
        visited[index] = 1;
        if (!isBackground(pixels[index])) {
            stack[top++] = index;
        }
        return top;
    }

    private List<RectBox> mergeRects(List<RectBox> rects, int distance) {
        List<RectBox> merged = new ArrayList<>(rects);
        boolean changed = true;
        while (changed) {
            changed = false;
            boolean[] used = new boolean[merged.size()];
            List<RectBox> next = new ArrayList<>();
            for (int i = 0; i < merged.size(); i++) {
                if (used[i]) {
                    continue;
                }
                RectBox current = merged.get(i).copy();
                used[i] = true;
                for (int j = i + 1; j < merged.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    RectBox other = merged.get(j);
                    if (current.distanceX(other) < distance && current.distanceY(other) < distance) {
                        current.merge(other);
                        used[j] = true;
                        changed = true;
                    }
                }
                next.add(current);
            }
            merged = next;
        }
        merged.sort(Comparator.comparingInt((RectBox r) -> r.minY).thenComparingInt(r -> r.minX));
        return merged;
    }

    private boolean isBackground(int color) {
        int alpha = Color.alpha(color);
        if (alpha < 20) {
            return true;
        }
        return Color.red(color) > 240 && Color.green(color) > 240 && Color.blue(color) > 240;
    }

    private static boolean hasFile(String path) {
        return localFile(path) != null;
    }

    private static File localFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path.trim());
        return file.isFile() && file.length() > 0 ? file : null;
    }

    private static String parseImage(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        if (json.has("data")) {
            JSONArray data = json.getJSONArray("data");
            if (data.length() > 0) {
                JSONObject first = data.getJSONObject(0);
                String b64 = first.optString("b64_json", "");
                if (!b64.isEmpty()) {
                    return b64;
                }
                String url = first.optString("url", "");
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }
        String image = json.optString("image", "");
        if (!image.isEmpty()) {
            return image;
        }
        throw new IllegalStateException("图片响应没有 data/url/b64_json");
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
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
        return body.length() > 500 ? body.substring(0, 500) : body;
    }

    private static String summarizeImageResponse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "empty body";
        }
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("data")) {
                JSONArray data = json.getJSONArray("data");
                if (data.length() > 0) {
                    JSONObject first = data.getJSONObject(0);
                    String b64 = first.optString("b64_json", "");
                    String url = first.optString("url", "");
                    return "dataCount=" + data.length()
                            + " hasB64=" + !b64.isEmpty()
                            + " b64Len=" + b64.length()
                            + " hasUrl=" + !url.isEmpty()
                            + " urlLen=" + url.length();
                }
                return "dataCount=0";
            }
            String image = json.optString("image", "");
            if (!image.isEmpty()) {
                return "imageLen=" + image.length();
            }
            return "keys=" + json.names() + " body=" + trim(body);
        } catch (Exception e) {
            return "raw=" + trim(body);
        }
    }

    private static String sha256Short(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "empty";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(8, digest.length);
            for (int i = 0; i < limit; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "error";
        }
    }

    private static final class StickerReference {
        final String filename;
        final byte[] bytes;

        StickerReference(String filename, byte[] bytes) {
            this.filename = safeFilename(filename);
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    private static final class RectBox {
        int minX;
        int minY;
        int maxX;
        int maxY;
        int count;

        RectBox(int minX, int minY, int maxX, int maxY, int count) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.count = count;
        }

        void include(int x, int y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            count++;
        }

        int width() {
            return Math.max(1, maxX - minX + 1);
        }

        int height() {
            return Math.max(1, maxY - minY + 1);
        }

        RectBox copy() {
            return new RectBox(minX, minY, maxX, maxY, count);
        }

        RectBox pad(int padding, int imageWidth, int imageHeight) {
            return new RectBox(
                    Math.max(0, minX - padding),
                    Math.max(0, minY - padding),
                    Math.min(imageWidth - 1, maxX + padding),
                    Math.min(imageHeight - 1, maxY + padding),
                    count);
        }

        void merge(RectBox other) {
            minX = Math.min(minX, other.minX);
            minY = Math.min(minY, other.minY);
            maxX = Math.max(maxX, other.maxX);
            maxY = Math.max(maxY, other.maxY);
            count += other.count;
        }

        int distanceX(RectBox other) {
            return Math.max(0, Math.max(minX - other.maxX, other.minX - maxX));
        }

        int distanceY(RectBox other) {
            return Math.max(0, Math.max(minY - other.maxY, other.minY - maxY));
        }
    }
}
