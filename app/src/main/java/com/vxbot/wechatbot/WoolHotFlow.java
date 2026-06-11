package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WoolHotFlow {
    private static final String SOURCE_URL = "https://www.zuankeba.cn/";
    private static final int MAX_ITEMS = 20;

    public boolean handle(Context context, BotConfig config, WxMessage message, WechatDriver driver) {
        try {
            List<String> items = fetchItems();
            if (items.isEmpty()) {
                driver.sendTextInCurrentChat(context, config, message.sessionName, "羊毛榜没抓到内容，站点这会儿有点空。", false);
                BotLog.w(context, "wool.empty", "赚客吧没有解析到条目");
                return true;
            }
            File image = renderImage(context, items);
            BotLog.i(context, "wool.image.ready", "羊毛榜图片已生成 path=" + image.getAbsolutePath() + " count=" + items.size());
            boolean ok = new ImageFlow().shareExistingImage(context, config, image, message.sessionName);
            BotLog.write(context, ok ? "SUCCESS" : "ERROR", "wool.share.done",
                    (ok ? "羊毛榜已发送" : "羊毛榜发送失败") + " sessionName=" + message.sessionName);
            return ok;
        } catch (Exception e) {
            BotLog.e(context, "wool.flow.error", e.getMessage());
            try {
                driver.sendTextInCurrentChat(context, config, message.sessionName, "羊毛榜抓取卡住了，等会儿再薅。", false);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private static List<String> fetchItems() throws Exception {
        String html = get(SOURCE_URL, 15000);
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("<a\\s+[^>]*class=\"[^\"]*deal-link-item[^\"]*\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (matcher.find() && out.size() < MAX_ITEMS) {
            String title = htmlToText(matcher.group(1));
            if (shouldSkip(title, out)) {
                continue;
            }
            out.add(title);
        }
        return out;
    }

    private static boolean shouldSkip(String title, List<String> existing) {
        if (title == null || title.trim().isEmpty()) {
            return true;
        }
        String clean = title.trim();
        if (clean.contains("微信推送上线") || clean.contains("建议意见收集贴")) {
            return true;
        }
        for (String item : existing) {
            if (item.equals(clean)) {
                return true;
            }
        }
        return false;
    }

    private static File renderImage(Context context, List<String> items) throws Exception {
        int width = 720;
        int rowHeight = 38;
        int headerHeight = 50;
        int footerHeight = 20;
        int height = headerHeight + 12 + items.size() * rowHeight + footerHeight;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, width, height, paint);

        paint.setColor(Color.rgb(216, 0, 0));
        canvas.drawRect(0, 0, width, headerHeight, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(20f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        canvas.drawText("摸鱼热榜", 18, 31, paint);

        paint.setTextSize(15f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        String stamp = "更新 " + new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(new Date())
                + " · " + items.size() + " 条";
        canvas.drawText(stamp, width - 18 - paint.measureText(stamp), 31, paint);

        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setTextSize(19f);
        int y = headerHeight + 28;
        for (int i = 0; i < items.size(); i++) {
            int rank = i + 1;
            int rowTop = y - 24;
            if (rank <= 3) {
                paint.setColor(Color.rgb(218, 0, 0));
                canvas.drawRect(18, rowTop + 2, 42, rowTop + 25, paint);
                paint.setColor(Color.WHITE);
                paint.setTextSize(17f);
                canvas.drawText(String.valueOf(rank), rank < 10 ? 26 : 21, y - 4, paint);
            } else {
                paint.setColor(Color.rgb(16, 16, 16));
                paint.setTextSize(17f);
                canvas.drawText(String.valueOf(rank), rank < 10 ? 26 : 21, y - 4, paint);
            }

            paint.setColor(Color.rgb(18, 18, 18));
            paint.setTextSize(19f);
            String title = ellipsize(items.get(i), paint, width - 74);
            canvas.drawText(title, 58, y - 4, paint);
            y += rowHeight;
        }

        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "wool");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建羊毛榜目录 " + dir.getAbsolutePath());
        }
        File file = new File(dir, "vxbot_wool_hot_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private static String ellipsize(String value, Paint paint, float maxWidth) {
        String text = value == null ? "" : value.trim();
        if (paint.measureText(text) <= maxWidth) {
            return text;
        }
        int count = paint.breakText(text, true, Math.max(1f, maxWidth - paint.measureText("...")), null);
        if (count <= 0 || count >= text.length()) {
            return text;
        }
        return text.substring(0, Math.max(0, count)) + "...";
    }

    private static String htmlToText(String value) {
        String raw = value == null ? "" : value.replaceAll("<[^>]+>", " ");
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) VXBot/1.0");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        int code = conn.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
