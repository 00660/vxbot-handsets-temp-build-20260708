package com.vxbot.wechatbot;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 面板图片投递专用流程。其它图片、视频和群发链路不复用这里的目标选择逻辑。 */
public final class PanelImageDeliveryFlow {
    private static final long SELECT_TIMEOUT_MS = 15000L;
    private static final long TARGET_TIMEOUT_MS = 30000L;
    private static final long CONFIRM_TIMEOUT_MS = 15000L;
    private static final long SUBMIT_TIMEOUT_MS = 7000L;

    private static final class ShareAsset {
        final Uri contentUri;
        final String mime;
        final String fileName;
        final boolean publicMedia;

        ShareAsset(Uri contentUri, String mime, String fileName, boolean publicMedia) {
            this.contentUri = contentUri;
            this.mime = mime;
            this.fileName = fileName;
            this.publicMedia = publicMedia;
        }
    }

    public boolean shareExistingImage(Context context, BotConfig config, File file, String target) {
        long started = SystemClock.uptimeMillis();
        HsClient hs = new HsClient(config == null ? 9010 : config.hsPort);
        ShareAsset asset = null;
        boolean sent = false;
        try {
            if (target == null || target.trim().isEmpty()) {
                BotLog.e(context, "panel.image.abort", "面板图片目标为空");
                return false;
            }
            asset = createShareAsset(context, file);
            closeRemainder(context, hs, "panel-before-start");
            if (!startShareIntent(context, asset)) {
                return false;
            }
            if (!waitSelectPage(context, config, hs, SELECT_TIMEOUT_MS)) {
                BotLog.e(context, "panel.share.select.timeout", "面板分享页未就绪 target=" + target);
                return false;
            }
            if (!selectTargetByDirectOcr(context, config, hs, target.trim())) {
                BotLog.e(context, "panel.share.target.failed", "分享页目标 OCR 失败 target=" + target);
                return false;
            }
            if (!waitConfirmPage(context, config, hs, CONFIRM_TIMEOUT_MS, target.trim())) {
                BotLog.e(context, "panel.share.confirm.failed", "点击目标后未确认 target=" + target);
                return false;
            }
            sent = clickSendAndWait(context, config, hs, target.trim());
            BotLog.write(context, sent ? "SUCCESS" : "ERROR", "panel.image.share.done",
                    (sent ? "面板图片已发送" : "面板图片发送失败")
                            + " target=" + target
                            + " costMs=" + (SystemClock.uptimeMillis() - started));
            return sent;
        } catch (Exception error) {
            BotLog.e(context, "panel.image.share.error", "面板图片分享异常 target=" + target
                    + " error=" + error.getMessage());
            return false;
        } finally {
            if (!sent) {
                closeRemainder(context, hs, "panel-share-failed");
            }
            cleanupShareAsset(context, asset);
        }
    }

    private boolean startShareIntent(Context context, ShareAsset asset) {
        try {
            Intent intent = new Intent(context, ShareProxyActivity.class);
            intent.putExtra(ShareProxyActivity.EXTRA_URI, asset.contentUri);
            intent.putExtra(ShareProxyActivity.EXTRA_MIME, asset.mime);
            intent.putExtra(ShareProxyActivity.EXTRA_FILE_NAME, asset.fileName);
            intent.putExtra(ShareProxyActivity.EXTRA_DIRECT, true);
            intent.putExtra(ShareProxyActivity.EXTRA_PREFIX, "PanelRecentChats");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("content".equals(asset.contentUri.getScheme())) {
                context.grantUriPermission("com.tencent.mm", asset.contentUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            context.startActivity(intent);
            BotLog.i(context, "panel.share.intent.start", "已打开微信图片分享页");
            return true;
        } catch (Exception error) {
            BotLog.e(context, "panel.share.intent.error", error.getMessage());
            return false;
        }
    }

    private boolean waitSelectPage(Context context, BotConfig config, HsClient hs, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (isSelectPage(screen)) {
                SystemClock.sleep(Math.max(1200L, selectPoll(config) * 5));
                return true;
            }
            SystemClock.sleep(selectPoll(config));
        }
        return false;
    }

    private boolean isSelectPage(OcrHelper.Screen screen) {
        return find(screen, text -> {
            String value = clean(text);
            return value.contains("选择聊天") || value.contains("选择一个聊天")
                    || value.contains("最近聊天");
        }, 0f, 1f, 0f, 0.35f) != null;
    }

    private boolean selectTargetByDirectOcr(Context context, BotConfig config, HsClient hs, String target) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TARGET_TIMEOUT_MS;
        int attempt = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempt++;
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (screen == null) {
                SystemClock.sleep(selectPoll(config));
                continue;
            }
            OcrHelper.OcrItem recentChats = find(screen,
                    text -> clean(text).contains("最近聊天"),
                    0f, 1f, 0.20f, 0.70f);
            if (recentChats == null) {
                BotLog.i(context, "panel.share.recent_chats.wait",
                        "等待最近聊天列表 target=" + target + " attempt=" + attempt);
                SystemClock.sleep(Math.max(450L, confirmPoll(config)));
                continue;
            }
            int minY = recentChats.rect.bottom + 12;
            String wanted = normalize(target);
            OcrHelper.OcrItem exact = null;
            OcrHelper.OcrItem fuzzy = null;
            boolean exactAmbiguous = false;
            boolean fuzzyAmbiguous = false;
            for (OcrHelper.OcrItem item : screen.items) {
                if (item.centerY < minY || item.centerY > screen.height - 80
                        || !matchesTarget(item.text, target)) {
                    continue;
                }
                String value = normalize(item.text);
                if (wanted.equals(value)) {
                    if (exact == null) {
                        exact = item;
                    } else if (!sameRow(exact, item)) {
                        exactAmbiguous = true;
                    } else if (item.rect.width() > exact.rect.width()) {
                        exact = item;
                    }
                } else if (fuzzy == null) {
                    fuzzy = item;
                } else if (!sameRow(fuzzy, item)) {
                    fuzzyAmbiguous = true;
                } else if (item.rect.width() > fuzzy.rect.width()) {
                    fuzzy = item;
                }
            }
            OcrHelper.OcrItem candidate = !exactAmbiguous && exact != null
                    ? exact
                    : (!exactAmbiguous && !fuzzyAmbiguous ? fuzzy : null);
            if (candidate == null) {
                BotLog.i(context, "panel.share.target.wait", "等待唯一目标 OCR target=" + target
                        + " attempt=" + attempt + " exactAmbiguous=" + exactAmbiguous
                        + " fuzzyAmbiguous=" + fuzzyAmbiguous
                        + " snippets=" + screen.snippets);
                SystemClock.sleep(Math.max(450L, confirmPoll(config)));
                continue;
            }
            hs.tap(candidate.centerX, candidate.centerY);
            BotLog.i(context, "panel.share.target.tap", "点击分享页目标 target=" + target
                    + " text=" + candidate.text + " x=" + candidate.centerX + " y=" + candidate.centerY
                    + " attempt=" + attempt);
            return true;
        }
        BotLog.e(context, "panel.share.target.timeout", "分享页目标 OCR 超时 target=" + target);
        return false;
    }

    private boolean sameRow(OcrHelper.OcrItem first, OcrHelper.OcrItem second) {
        return first != null && second != null
                && Math.abs(first.centerY - second.centerY) <= 36
                && Math.abs(first.centerX - second.centerX) <= 120;
    }

    private boolean waitConfirmPage(Context context, BotConfig config, HsClient hs,
                                    long timeoutMs, String target) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (isConfirmPage(screen) && confirmContainsTarget(screen, target)) {
                BotLog.i(context, "panel.share.confirm.ready", "确认页目标已稳定 target=" + target);
                return true;
            }
            SystemClock.sleep(confirmPoll(config));
        }
        return false;
    }

    private boolean clickSendAndWait(Context context, BotConfig config, HsClient hs, String target) throws Exception {
        long deadline = SystemClock.uptimeMillis() + CONFIRM_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (!isConfirmPage(screen) || !confirmContainsTarget(screen, target)) {
                SystemClock.sleep(confirmPoll(config));
                continue;
            }
            Rect green = OcrHelper.findShareConfirmGreenSendButton(context, hs);
            if (green != null) {
                hs.tap(green.centerX(), green.centerY());
            } else {
                OcrHelper.OcrItem send = find(screen, text -> "发送".equals(clean(text)),
                        0f, 1f, 0.45f, 1f);
                if (send == null) {
                    SystemClock.sleep(sendPoll(config));
                    continue;
                }
                hs.tap(send.centerX, send.centerY);
            }
            BotLog.i(context, "panel.share.send.tap", "点击确认页发送 target=" + target);
            return waitSubmit(context, config, hs);
        }
        return false;
    }

    private boolean waitSubmit(Context context, BotConfig config, HsClient hs) {
        long deadline = SystemClock.uptimeMillis() + SUBMIT_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (!isConfirmPage(screen)) {
                return true;
            }
            SystemClock.sleep(submitPoll(config));
        }
        BotLog.e(context, "panel.share.submit.timeout", "点击发送后确认页未退出");
        return false;
    }

    private boolean isConfirmPage(OcrHelper.Screen screen) {
        if (screen == null) {
            return false;
        }
        boolean header = find(screen, text -> {
            String value = clean(text);
            return value.contains("发送给") || value.contains("发送到");
        }, 0f, 1f, 0.45f, 0.82f) != null;
        OcrHelper.OcrItem send = find(screen, text -> "发送".equals(clean(text)),
                0f, 1f, 0.45f, 1f);
        OcrHelper.OcrItem cancel = find(screen, text -> "取消".equals(clean(text)),
                0f, 1f, 0.45f, 1f);
        return header || (send != null && cancel != null);
    }

    private boolean confirmContainsTarget(OcrHelper.Screen screen, String target) {
        if (!isConfirmPage(screen)) {
            return false;
        }
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < screen.height * 0.15f || item.centerY > screen.height * 0.82f) {
                continue;
            }
            if (matchesTarget(item.text, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTarget(String text, String target) {
        String value = normalize(text);
        String wanted = normalize(target);
        if (value.isEmpty() || wanted.isEmpty()) {
            return false;
        }
        if (value.equals(wanted) || value.contains(wanted) || wanted.contains(value)) {
            return true;
        }
        return NameNormalizer.looseNameMatch(value, wanted);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return NameNormalizer.nameKey(text.replaceAll("[（(]\\s*\\d+\\s*(?:人|[Aa])?\\s*[）)]", ""));
    }

    private String clean(String text) {
        return normalize(text).replace("Q", "").replace("q", "");
    }

    private long selectPoll(BotConfig config) {
        return config == null ? 350L : Math.max(250L, config.shareSelectPollMs);
    }

    private long confirmPoll(BotConfig config) {
        return config == null ? 350L : Math.max(250L, config.shareConfirmPollMs);
    }

    private long sendPoll(BotConfig config) {
        return config == null ? 300L : Math.max(250L, config.shareSendButtonPollMs);
    }

    private long submitPoll(BotConfig config) {
        return config == null ? 450L : Math.max(350L, config.shareSubmitPollMs);
    }

    private OcrHelper.OcrItem find(OcrHelper.Screen screen, TextMatcher matcher,
                                   float minX, float maxX, float minY, float maxY) {
        if (screen == null || matcher == null) {
            return null;
        }
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerX < screen.width * minX || item.centerX > screen.width * maxX
                    || item.centerY < screen.height * minY || item.centerY > screen.height * maxY) {
                continue;
            }
            if (matcher.matches(item.text)) {
                return item;
            }
        }
        return null;
    }

    private interface TextMatcher {
        boolean matches(String text);
    }

    private ShareAsset createShareAsset(Context context, File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalStateException("file not found");
        }
        String mime = "image/png";
        String name = "vxbot_panel_" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VXBotPanel");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("media insert failed");
                copyFile(context, file, uri);
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
                return new ShareAsset(uri, mime, name, true);
            } catch (Exception error) {
                if (uri != null) context.getContentResolver().delete(uri, null, null);
                BotLog.w(context, "panel.share.asset.fallback", error.getMessage());
            }
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        return new ShareAsset(uri, mime, file.getName(), false);
    }

    private void copyFile(Context context, File file, Uri uri) throws Exception {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("media output unavailable");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }

    private void cleanupShareAsset(Context context, ShareAsset asset) {
        if (asset == null || !asset.publicMedia || asset.contentUri == null) return;
        try { context.getContentResolver().delete(asset.contentUri, null, null); }
        catch (Exception error) { BotLog.w(context, "panel.share.asset.cleanup.fail", error.getMessage()); }
    }

    private void closeRemainder(Context context, HsClient hs, String reason) {
        for (int i = 0; i < 3; i++) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (!isSelectPage(screen) && !isConfirmPage(screen)) return;
            try { hs.key("BACK"); } catch (Exception ignored) { return; }
            SystemClock.sleep(450L);
        }
    }
}
