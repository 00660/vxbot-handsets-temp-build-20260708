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
import java.util.ArrayList;
import java.util.List;

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

    private static final class TargetHit {
        final String text;
        final Rect rect;

        TargetHit(String text, Rect rect) {
            this.text = text == null ? "" : text;
            this.rect = new Rect(rect);
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
            if (!selectTargetBySearch(context, config, hs, target.trim())) {
                BotLog.e(context, "panel.share.target.failed", "分享页搜索目标失败 target=" + target);
                return false;
            }
            if (!waitConfirmPage(context, config, hs, CONFIRM_TIMEOUT_MS, target.trim())) {
                BotLog.e(context, "panel.share.confirm.failed", "点击搜索结果后未确认目标 target=" + target);
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
            intent.putExtra(ShareProxyActivity.EXTRA_PREFIX, "PanelSearch");
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
                    || value.contains("最近聊天") || value.contains("最近转发");
        }, 0f, 1f, 0f, 0.35f) != null;
    }

    private boolean selectTargetBySearch(Context context, BotConfig config, HsClient hs, String target) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TARGET_TIMEOUT_MS;
        TargetHit previous = null;
        int stable = 0;
        boolean pasted = false;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (screen == null) {
                SystemClock.sleep(selectPoll(config));
                continue;
            }
            if (!pasted) {
                OcrHelper.OcrItem search = findShareSearchBox(screen);
                if (search == null) {
                    BotLog.i(context, "panel.share.search.wait", "等待分享页顶部搜索框 target=" + target);
                    SystemClock.sleep(selectPoll(config));
                    continue;
                }
                BotLog.i(context, "panel.share.search.input.tap", "点击分享页顶部搜索框"
                        + " text=" + search.text + " x=" + search.centerX + " y=" + search.centerY);
                hs.tap(search.centerX, search.centerY);
                SystemClock.sleep(Math.max(1500L, selectPoll(config) * 5));
                String clip = hs.clipSet(target);
                if (isError(clip)) {
                    BotLog.e(context, "panel.share.search.clip.fail", clip);
                    return false;
                }
                String paste = hs.keyCode(279);
                if (isError(paste)) {
                    BotLog.e(context, "panel.share.search.paste.fail", paste);
                    return false;
                }
                pasted = true;
                BotLog.i(context, "panel.share.search.paste", "已粘贴分享页搜索目标 target=" + target
                        + " x=" + search.centerX + " y=" + search.centerY);
                SystemClock.sleep(Math.max(500L, selectPoll(config) * 2));
                continue;
            }

            TargetHit hit = findSearchResult(screen, target);
            if (hit == null) {
                stable = 0;
                previous = null;
                SystemClock.sleep(selectPoll(config));
                continue;
            }
            if (sameHit(previous, hit)) {
                stable++;
            } else {
                stable = 1;
            }
            previous = hit;
            if (stable < 3) {
                SystemClock.sleep(Math.max(350L, selectPoll(config)));
                continue;
            }
            hs.tap(hit.rect.centerX(), hit.rect.centerY());
            BotLog.i(context, "panel.share.search.result.tap", "点击分享页搜索结果 target=" + target
                    + " text=" + hit.text + " x=" + hit.rect.centerX() + " y=" + hit.rect.centerY());
            return true;
        }
        BotLog.e(context, "panel.share.search.timeout", "分享页搜索结果超时 target=" + target);
        return false;
    }

    private TargetHit findSearchResult(OcrHelper.Screen screen, String target) {
        if (screen == null) {
            return null;
        }
        OcrHelper.OcrItem search = findShareSearchBox(screen);
        OcrHelper.OcrItem groupHeader = find(screen, text -> "群聊".equals(clean(text)),
                0f, 1f, 0.12f, 0.55f);
        int minY = Math.round(screen.height * 0.16f);
        if (search != null) {
            minY = Math.max(minY, search.rect.bottom + 12);
        }
        if (groupHeader != null) {
            minY = Math.max(minY, groupHeader.rect.bottom + 8);
        }
        List<OcrHelper.OcrItem> matches = new ArrayList<>();
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minY || item.centerY > screen.height * 0.86f) {
                continue;
            }
            if (!matchesTarget(item.text, target)) {
                continue;
            }
            matches.add(item);
        }
        if (matches.isEmpty()) {
            return null;
        }
        List<TargetHit> rows = new ArrayList<>();
        for (OcrHelper.OcrItem item : matches) {
            TargetHit hit = new TargetHit(item.text, item.rect);
            int row = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (Math.abs(rows.get(i).rect.centerY() - hit.rect.centerY()) <= 58
                        && Math.abs(rows.get(i).rect.centerX() - hit.rect.centerX()) <= screen.width * 0.20f) {
                    row = i;
                    break;
                }
            }
            if (row < 0) {
                rows.add(hit);
            } else if (hit.rect.top < rows.get(row).rect.top || hit.rect.width() > rows.get(row).rect.width()) {
                rows.set(row, hit);
            }
        }
        if (rows.size() != 1) {
            return null;
        }
        return rows.get(0);
    }

    private OcrHelper.OcrItem findShareSearchBox(OcrHelper.Screen screen) {
        if (screen == null) {
            return null;
        }
        OcrHelper.OcrItem best = null;
        for (OcrHelper.OcrItem item : screen.items) {
            String value = clean(item.text);
            if (item.centerY < screen.height * 0.06f || item.centerY > screen.height * 0.24f
                    || !value.contains("搜索")) {
                continue;
            }
            if (best == null || item.rect.width() > best.rect.width()) {
                best = item;
            }
        }
        return best;
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
            if (item.centerY < screen.height * 0.50f || item.centerY > screen.height * 0.82f) {
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

    private boolean sameHit(TargetHit a, TargetHit b) {
        return a != null && b != null && Math.abs(a.rect.centerX() - b.rect.centerX()) <= 18
                && Math.abs(a.rect.centerY() - b.rect.centerY()) <= 24;
    }

    private boolean isError(String value) {
        return value != null && value.startsWith("ERR:");
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
