package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewsBriefingFlow {
    private static final String GOOGLE_RSS = "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
    private static final int CARD_COUNT = 8;
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern RSS_ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern HTML_LINK = Pattern.compile("<a[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public boolean handle(Context context, BotConfig config, WxMessage message, WechatDriver driver) {
        try {
            File image = createImage(context, config);
            boolean ok = new ImageFlow().shareExistingImage(context, config, image, message.sessionName);
            BotLog.write(context, ok ? "SUCCESS" : "ERROR", "news.share.done",
                    (ok ? "新闻早报图片已发送" : "新闻早报图片发送失败") + " sessionName=" + message.sessionName);
            return ok;
        } catch (Exception e) {
            BotLog.e(context, "news.flow.error", e.getMessage());
            try {
                driver.sendTextInCurrentChat(context, config, message.sessionName, "早报生成失败了，新闻源这会儿没拉稳。", false);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    public File createImage(Context context, BotConfig config) throws Exception {
        List<Story> candidates = fetchCandidates();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("没有抓到可用新闻");
        }
        List<Card> cards;
        try {
            String evidence = buildEvidence(candidates);
            String reply = new ChatClient().requestNewsDigest(context, config, evidence);
            cards = parseModelCards(reply, candidates);
            BotLog.i(context, "news.editor.done", "新闻编辑完成 cards=" + cards.size());
        } catch (Exception e) {
            BotLog.w(context, "news.editor.fallback", "新闻编辑上游失败，使用本地筛选: " + e.getMessage());
            cards = new ArrayList<>();
        }
        if (cards.size() < CARD_COUNT) {
            cards = localCards(candidates);
        }
        File image = renderImage(context, cards);
        BotLog.i(context, "news.image.ready", "新闻早报图片已生成 path=" + image.getAbsolutePath()
                + " candidates=" + candidates.size() + " cards=" + cards.size());
        return image;
    }

    private static List<Story> fetchCandidates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<List<Story>>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> fetchFeed("焦点", GOOGLE_RSS, 18)));
            futures.add(pool.submit(() -> fetchFeed("国际", searchRss("国际 局势 外交"), 10)));
            futures.add(pool.submit(() -> fetchFeed("财经", searchRss("财经 市场 公司"), 10)));
            futures.add(pool.submit(() -> fetchFeed("科技", searchRss("科技 AI 人工智能"), 10)));
            futures.add(pool.submit(() -> fetchFeed("体育", searchRss("体育 世界杯 比赛"), 8)));

            List<List<Story>> feeds = new ArrayList<>();
            Exception last = null;
            for (Future<List<Story>> future : futures) {
                try {
                    feeds.add(future.get());
                } catch (Exception e) {
                    last = e;
                    feeds.add(new ArrayList<>());
                }
            }
            List<Story> merged = new ArrayList<>();
            for (int row = 0; row < 18; row++) {
                for (List<Story> feed : feeds) {
                    if (row < feed.size()) {
                        merged.add(feed.get(row));
                    }
                }
            }
            List<Story> filtered = deduplicate(merged, 35);
            if (filtered.isEmpty() && last != null) {
                throw last;
            }
            return filtered;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Story> fetchFeed(String category, String url, int limit) throws Exception {
        String rss = get(url, 12000);
        Matcher matcher = RSS_ITEM.matcher(rss);
        List<Story> out = new ArrayList<>();
        while (matcher.find() && out.size() < limit) {
            String block = matcher.group(1);
            String source = xmlText(block, "source");
            String title = cleanTitle(xmlText(block, "title"), source);
            if (isLowValue(title)) {
                continue;
            }
            Story story = new Story();
            story.category = category;
            story.title = title;
            story.source = source.isEmpty() ? "Google News" : source;
            story.time = parseTime(xmlText(block, "pubDate"));
            story.related = extractRelated(xmlRaw(block, "description"), title);
            out.add(story);
        }
        return out;
    }

    private static List<Story> deduplicate(List<Story> source, int limit) {
        List<Story> out = new ArrayList<>();
        Set<String> exact = new HashSet<>();
        for (Story story : source) {
            if (story == null || isLowValue(story.title)) {
                continue;
            }
            String key = normalize(story.title);
            if (key.isEmpty() || !exact.add(key) || containsSimilar(out, story.title)) {
                continue;
            }
            story.id = out.size() + 1;
            out.add(story);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static String buildEvidence(List<Story> stories) {
        StringBuilder out = new StringBuilder(8000);
        for (Story story : stories) {
            out.append('[').append(story.id).append(']')
                    .append('[').append(story.category).append(']')
                    .append('[').append(story.source).append(']')
                    .append('[').append(story.time).append("] ")
                    .append(story.title);
            if (!story.related.isEmpty()) {
                out.append(" | 关联报道：").append(String.join("；", story.related));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static List<Card> parseModelCards(String reply, List<Story> candidates) throws Exception {
        String value = reply == null ? "" : reply.trim();
        int start = value.indexOf('[');
        int end = value.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("新闻编辑没有返回 JSON 数组");
        }
        JSONArray array = new JSONArray(value.substring(start, end + 1));
        Map<Integer, Story> byId = new HashMap<>();
        for (Story story : candidates) {
            byId.put(story.id, story);
        }
        List<Card> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < array.length() && out.size() < CARD_COUNT; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            int id = item.optInt("id", -1);
            Story story = byId.get(id);
            if (story == null || !used.add(id) || containsSimilarCards(out, story.title)) {
                continue;
            }
            String summary = cleanModelText(item.optString("summary"));
            if (summary.length() < 12) {
                summary = fallbackSummary(story);
            }
            Card card = new Card();
            card.category = safeCategory(item.optString("category"), story.category);
            card.title = story.title;
            card.summary = trimTo(summary, 72);
            card.source = story.source;
            card.time = story.time;
            out.add(card);
        }
        return out;
    }

    private static List<Card> localCards(List<Story> candidates) {
        LinkedHashMap<String, Integer> quota = new LinkedHashMap<>();
        quota.put("焦点", 2);
        quota.put("国际", 1);
        quota.put("财经", 2);
        quota.put("科技", 2);
        quota.put("体育", 1);
        List<Card> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Map.Entry<String, Integer> entry : quota.entrySet()) {
            int count = 0;
            for (Story story : candidates) {
                if (!entry.getKey().equals(story.category) || used.contains(story.id)
                        || containsSimilarCards(out, story.title)) {
                    continue;
                }
                out.add(toCard(story));
                used.add(story.id);
                if (++count >= entry.getValue()) {
                    break;
                }
            }
        }
        for (Story story : candidates) {
            if (out.size() >= CARD_COUNT) {
                break;
            }
            if (used.add(story.id) && !containsSimilarCards(out, story.title)) {
                out.add(toCard(story));
            }
        }
        return out;
    }

    private static Card toCard(Story story) {
        Card card = new Card();
        card.category = safeCategory(story.category, "焦点");
        card.title = story.title;
        card.summary = fallbackSummary(story);
        card.source = story.source;
        card.time = story.time;
        return card;
    }

    private static String fallbackSummary(Story story) {
        for (String related : story.related) {
            if (!similarTitle(story.title, related)) {
                return trimTo(related, 68);
            }
        }
        return "事件仍在更新，先看事实进展，不拿热搜词硬凑结论。";
    }

    private static File renderImage(Context context, List<Card> cards) throws Exception {
        int width = 1080;
        int height = 1790;
        int cardWidth = 486;
        int cardHeight = 340;
        int startY = 260;
        int gapY = 22;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        canvas.drawColor(Color.rgb(245, 242, 235));
        paint.setColor(Color.rgb(19, 42, 34));
        canvas.drawRect(0, 0, width, 230, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(70f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        canvas.drawText("今日早报", 48, 104, paint);

        paint.setTextSize(30f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        String date = ZonedDateTime.now(CHINA_ZONE).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA));
        canvas.drawText(date + " · 只讲值得看的事", 52, 160, paint);

        paint.setColor(Color.rgb(218, 181, 97));
        canvas.drawRoundRect(new RectF(810, 55, 1030, 137), 41, 41, paint);
        paint.setColor(Color.rgb(19, 42, 34));
        paint.setTextSize(28f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        String count = Math.min(CARD_COUNT, cards.size()) + " 条精选";
        canvas.drawText(count, 920 - paint.measureText(count) / 2f, 106, paint);

        for (int i = 0; i < Math.min(CARD_COUNT, cards.size()); i++) {
            int col = i % 2;
            int row = i / 2;
            float left = col == 0 ? 42 : 552;
            float top = startY + row * (cardHeight + gapY);
            drawCard(canvas, paint, cards.get(i), left, top, cardWidth, cardHeight, i + 1);
        }

        paint.setColor(Color.rgb(92, 91, 86));
        paint.setTextSize(24f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        String footer = "数据源：Google News RSS · AI 仅做去重与事实压缩";
        canvas.drawText(footer, 54, 1742, paint);
        String stamp = "更新 " + ZonedDateTime.now(CHINA_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"));
        canvas.drawText(stamp, width - 54 - paint.measureText(stamp), 1742, paint);

        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "news");
        if (!dir.exists() && !dir.mkdirs()) {
            bitmap.recycle();
            throw new IllegalStateException("无法创建新闻图片目录 " + dir.getAbsolutePath());
        }
        cleanupOldImages(dir);
        File file = new File(dir, "vxbot_news_briefing_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private static void drawCard(Canvas canvas, Paint paint, Card card, float left, float top,
                                 int width, int height, int rank) {
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(left, top, left + width, top + height), 26, 26, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.rgb(226, 221, 209));
        canvas.drawRoundRect(new RectF(left, top, left + width, top + height), 26, 26, paint);
        paint.setStyle(Paint.Style.FILL);

        int accent = categoryColor(card.category);
        paint.setColor(accent);
        canvas.drawRoundRect(new RectF(left + 24, top + 22, left + 122, top + 62), 20, 20, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(23f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        canvas.drawText(card.category, left + 73 - paint.measureText(card.category) / 2f, top + 50, paint);

        paint.setColor(Color.rgb(152, 148, 137));
        paint.setTextSize(23f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        String rankText = String.format(Locale.CHINA, "%02d", rank);
        canvas.drawText(rankText, left + width - 24 - paint.measureText(rankText), top + 50, paint);

        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.rgb(30, 30, 27));
        titlePaint.setTextSize(38f);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        drawText(canvas, card.title, titlePaint, left + 24, top + 82, width - 48, 3);

        TextPaint summaryPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        summaryPaint.setColor(Color.rgb(73, 71, 66));
        summaryPaint.setTextSize(28f);
        summaryPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        drawText(canvas, "看点：" + card.summary, summaryPaint, left + 24, top + 224, width - 48, 2);

        paint.setColor(accent);
        canvas.drawCircle(left + 29, top + height - 25, 5, paint);
        paint.setColor(Color.rgb(135, 131, 121));
        paint.setTextSize(22f);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        String meta = trimTo(card.source, 18) + " · " + card.time;
        canvas.drawText(meta, left + 43, top + height - 17, paint);
    }

    private static void drawText(Canvas canvas, String value, TextPaint paint, float x, float y, int width, int maxLines) {
        String text = value == null ? "" : value.trim();
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(2f, 1.05f)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(width)
                .setMaxLines(maxLines)
                .build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private static int categoryColor(String category) {
        if ("国际".equals(category)) return Color.rgb(46, 94, 145);
        if ("财经".equals(category)) return Color.rgb(157, 91, 30);
        if ("科技".equals(category)) return Color.rgb(93, 66, 153);
        if ("体育".equals(category)) return Color.rgb(38, 126, 89);
        if ("社会".equals(category)) return Color.rgb(164, 65, 59);
        return Color.rgb(174, 76, 42);
    }

    private static String searchRss(String query) throws Exception {
        return "https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q="
                + URLEncoder.encode(query, "UTF-8");
    }

    private static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VXBot/1.0");
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

    private static String xmlText(String block, String tag) {
        return decodeEntities(xmlRaw(block, tag).replaceAll("<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String xmlRaw(String block, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(block);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replace("<![CDATA[", "").replace("]]>", "").trim();
    }

    private static List<String> extractRelated(String rawDescription, String mainTitle) {
        String html = rawDescription.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
        Matcher matcher = HTML_LINK.matcher(html);
        List<String> out = new ArrayList<>();
        while (matcher.find() && out.size() < 3) {
            String title = decodeEntities(matcher.group(1).replaceAll("<[^>]+>", " "))
                    .replaceAll("\\s+", " ").trim();
            title = cleanTitle(title, "");
            if (!isLowValue(title) && !similarTitle(mainTitle, title) && !containsSimilarText(out, title)) {
                out.add(title);
            }
        }
        return out;
    }

    private static String decodeEntities(String text) {
        return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String cleanTitle(String title, String source) {
        String value = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (source != null && !source.isEmpty() && value.endsWith(" - " + source)) {
            value = value.substring(0, value.length() - source.length() - 3).trim();
        }
        return value.replaceFirst("\\s+-\\s+(?:搜狐网|新浪财经|网易|腾讯网|凤凰网|澎湃新闻|观察者网)$", "").trim();
    }

    private static boolean isLowValue(String title) {
        if (title == null || title.trim().length() < 9) {
            return true;
        }
        return containsAny(title,
                "学习习近平", "总书记这样", "总书记心中", "总书记驻足", "新思想指引", "新征程",
                "重要讲话心得", "贯彻重要讲话", "加快建设具有", "高质量发展新篇章", "绘就新图景",
                "迈上新台阶", "释放新动能", "追光的你", "壹视界", "一见·", "圆满完成使命归航");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String parseTime(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(CHINA_ZONE)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception ignored) {
            return "刚刚";
        }
    }

    private static boolean containsSimilar(List<Story> stories, String title) {
        for (Story story : stories) {
            if (similarTitle(story.title, title)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSimilarCards(List<Card> cards, String title) {
        for (Card card : cards) {
            if (similarTitle(card.title, title)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSimilarText(List<String> texts, String title) {
        for (String text : texts) {
            if (similarTitle(text, title)) {
                return true;
            }
        }
        return false;
    }

    private static boolean similarTitle(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return Math.min(a.length(), b.length()) >= 8;
        }
        Set<String> aa = bigrams(a);
        Set<String> bb = bigrams(b);
        int common = 0;
        for (String gram : aa) {
            if (bb.contains(gram)) {
                common++;
            }
        }
        int union = aa.size() + bb.size() - common;
        return union > 0 && common >= 3 && (double) common / union >= 0.28;
    }

    private static Set<String> bigrams(String value) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            out.add(value.substring(i, i + 2));
        }
        return out;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]", "")
                .replaceAll("(最新|突发|刚刚|重磅|官方|消息|回应)", "");
    }

    private static String cleanModelText(String value) {
        return value == null ? "" : value.replaceAll("[*#`\\r\\n]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String safeCategory(String category, String fallback) {
        String value = category == null ? "" : category.trim();
        if (containsAny(value, "国际", "财经", "科技", "体育", "社会", "焦点")) {
            return value.length() > 2 ? value.substring(0, 2) : value;
        }
        return "焦点".equals(fallback) ? "社会" : fallback;
    }

    private static String trimTo(String value, int maxChars) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
    }

    private static void cleanupOldImages(File dir) {
        File[] files = dir.listFiles((ignored, name) -> name.startsWith("vxbot_news_briefing_") && name.endsWith(".png"));
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static final class Story {
        int id;
        String category;
        String title;
        String source;
        String time;
        List<String> related = new ArrayList<>();
    }

    private static final class Card {
        String category;
        String title;
        String summary;
        String source;
        String time;
    }
}
