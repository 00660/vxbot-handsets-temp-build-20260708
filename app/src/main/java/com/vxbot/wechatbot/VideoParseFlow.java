package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoParseFlow {
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s，。！？；;\"'<>]+|www\\.[^\\s，。！？；;\"'<>]+)", Pattern.CASE_INSENSITIVE);
    private static final String USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1";

    public boolean handle(Context context, BotConfig config, WxMessage message, WechatDriver driver) {
        long start = SystemClock.uptimeMillis();
        try {
            String input = MessageRouter.stripBotMention(message.text, config);
            String shareUrl = extractUrl(input);
            if (shareUrl.isEmpty()) {
                driver.sendTextInCurrentChat(context, config, message.sessionName, "没看到可解析的视频链接。", false);
                return true;
            }
            BotLog.i(context, "video.parse.start", "开始内置解析分享链接 sessionName=" + message.sessionName
                    + " url=" + compactUrl(shareUrl));
            InlineVideoParser.ParseInfo info = new InlineVideoParser(context, config.videoParseTimeoutMs).parse(input);
            if (info.isEmpty()) {
                driver.sendTextInCurrentChat(context, config, message.sessionName, "解析到了，但没拿到视频或图集资源。", false);
                return true;
            }
            String summary = buildSummary(info);
            if (!summary.isEmpty()) {
                driver.sendTextInCurrentChat(context, config, message.sessionName, summary, true);
            }
            ImageFlow imageFlow = new ImageFlow();
            int shared = 0;
            if (!info.videoUrl.isEmpty()) {
                File video = downloadMedia(context, config, info.videoUrl, "vxbot_video_", ".mp4");
                if (imageFlow.shareExistingMedia(context, config, video, message.sessionName, "VideoShare")) {
                    shared++;
                }
            }
            for (InlineVideoParser.ImageItem item : info.images) {
                if (!item.url.isEmpty()) {
                    File image = downloadMedia(context, config, item.url, "vxbot_gallery_", guessImageExtension(item.url));
                    if (imageFlow.shareExistingMedia(context, config, image, message.sessionName, "GalleryShare")) {
                        shared++;
                    }
                }
                if (!item.livePhotoUrl.isEmpty()) {
                    File live = downloadMedia(context, config, item.livePhotoUrl, "vxbot_livephoto_", ".mp4");
                    if (imageFlow.shareExistingMedia(context, config, live, message.sessionName, "LivePhotoShare")) {
                        shared++;
                    }
                }
            }
            if (shared <= 0 && !info.coverUrl.isEmpty()) {
                File cover = downloadMedia(context, config, info.coverUrl, "vxbot_cover_", guessImageExtension(info.coverUrl));
                if (imageFlow.shareExistingMedia(context, config, cover, message.sessionName, "CoverShare")) {
                    shared++;
                }
            }
            if (shared <= 0) {
                String fallback = buildUrlFallback(info);
                driver.sendTextInCurrentChat(context, config, message.sessionName,
                        fallback.isEmpty() ? "解析成功，但分享文件时卡住了。" : fallback, false);
                return false;
            }
            BotLog.i(context, "video.parse.done", "内置视频解析分享完成 sessionName=" + message.sessionName
                    + " shared=" + shared
                    + " costMs=" + (SystemClock.uptimeMillis() - start));
            driver.leaveWechatIfForeground(context, "video-parse-done");
            return true;
        } catch (Exception e) {
            BotLog.e(context, "video.parse.error", "内置视频解析异常: " + e.getMessage());
            try {
                driver.sendTextInCurrentChat(context, config, message.sessionName,
                        "视频解析卡住了：" + safeMessage(e.getMessage()), false);
            } catch (Exception ignored) {
            }
            try {
                driver.leaveWechatIfForeground(context, "video-parse-error");
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private File downloadMedia(Context context, BotConfig config, String url, String prefix, String fallbackExt) throws Exception {
        File dir = new File(context.getCacheDir(), "video_parse");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建视频解析缓存目录 " + dir.getAbsolutePath());
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(Math.max(config.videoParseTimeoutMs, 30000));
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("媒体下载 HTTP " + code);
        }
        long maxBytes = Math.max(1, config.videoDownloadMaxBytesMb) * 1024L * 1024L;
        long length = conn.getContentLengthLong();
        if (length > maxBytes) {
            throw new IllegalStateException("媒体过大 " + (length / 1024 / 1024) + "MB > " + config.videoDownloadMaxBytesMb + "MB");
        }
        String ext = extensionFromContentType(conn.getContentType(), fallbackExt);
        File file = new File(dir, prefix + System.currentTimeMillis() + ext);
        long total = 0;
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("媒体下载超过大小限制 " + config.videoDownloadMaxBytesMb + "MB");
                }
                out.write(buffer, 0, read);
            }
        }
        if (file.length() <= 0) {
            throw new IllegalStateException("媒体下载为空");
        }
        BotLog.i(context, "video.media.download", "已下载媒体 file=" + file.getAbsolutePath()
                + " bytes=" + file.length() + " url=" + compactUrl(url));
        return file;
    }

    private String buildSummary(InlineVideoParser.ParseInfo info) {
        StringBuilder out = new StringBuilder();
        if (!info.title.isEmpty()) {
            out.append("解析好了：").append(trim(info.title, 80));
        } else {
            out.append("解析好了");
        }
        if (!info.authorName.isEmpty()) {
            out.append("\n作者：").append(trim(info.authorName, 40));
        }
        if (!info.videoUrl.isEmpty()) {
            out.append("\n准备发无水印视频");
        } else if (!info.images.isEmpty()) {
            out.append("\n准备发图集 ").append(info.images.size()).append(" 张");
        }
        return out.toString();
    }

    private String buildUrlFallback(InlineVideoParser.ParseInfo info) {
        StringBuilder out = new StringBuilder();
        if (!info.videoUrl.isEmpty()) {
            out.append("视频链接：").append(info.videoUrl);
        }
        if (!info.images.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("图集：");
            for (int i = 0; i < Math.min(5, info.images.size()); i++) {
                if (!info.images.get(i).url.isEmpty()) {
                    out.append('\n').append(i + 1).append(". ").append(info.images.get(i).url);
                }
            }
        }
        return out.toString();
    }

    private static String extractUrl(String text) {
        Matcher matcher = URL_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return "";
        }
        String url = matcher.group(1);
        return url.startsWith("www.") ? "https://" + url : url;
    }

    private static String guessImageExtension(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains(".webp")) {
            return ".webp";
        }
        if (lower.contains(".jpg") || lower.contains(".jpeg")) {
            return ".jpg";
        }
        return ".png";
    }

    private static String extensionFromContentType(String contentType, String fallback) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("video/quicktime")) {
            return ".mov";
        }
        if (type.contains("video/")) {
            return ".mp4";
        }
        if (type.contains("image/webp")) {
            return ".webp";
        }
        if (type.contains("image/jpeg") || type.contains("image/jpg")) {
            return ".jpg";
        }
        if (type.contains("image/png")) {
            return ".png";
        }
        return fallback == null || fallback.trim().isEmpty() ? ".bin" : fallback;
    }

    private static String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String compactUrl(String value) {
        String url = value == null ? "" : value.trim();
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    private static String safeMessage(String value) {
        String text = value == null ? "" : value;
        return trim(text.replace('\n', ' '), 80);
    }
}
