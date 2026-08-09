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

/**
 * 面板图片的独立投递流程。
 *
 * 每个可见页面阶段都在限定区域内轮询。精确群名可立即点击，模糊群名需要连续两帧确认。
 */
public final class PanelImageDeliveryFlow {
    private static final int STABLE_FRAME_COUNT = 2;
    private static final float MIN_TARGET_MATCH_RATIO = 0.50f;
    private static final long TARGET_TIMEOUT_MS = 30000L;
    private static final long CONFIRM_TIMEOUT_MS = 30000L;
    private static final long SEND_TIMEOUT_MS = 30000L;
    private static final long SUBMIT_TIMEOUT_MS = 15000L;

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

    private static final class ConfirmState {
        final OcrHelper.OcrItem sendTo;
        final OcrHelper.OcrItem target;
        final OcrHelper.OcrItem send;

        ConfirmState(OcrHelper.OcrItem sendTo, OcrHelper.OcrItem target, OcrHelper.OcrItem send) {
            this.sendTo = sendTo;
            this.target = target;
            this.send = send;
        }
    }

    private static final class TargetCandidate {
        final OcrHelper.OcrItem item;
        final float matchRatio;

        TargetCandidate(OcrHelper.OcrItem item, float matchRatio) {
            this.item = item;
            this.matchRatio = matchRatio;
        }
    }

    public boolean shareExistingImage(Context context, BotConfig config, File file, String target) {
        long startedAt = SystemClock.uptimeMillis();
        HsClient hs = new HsClient(config == null ? 9010 : config.hsPort);
        ShareAsset asset = null;
        boolean sent = false;
        String targetKey = groupNameKey(target);
        try {
            if (targetKey.isEmpty()) {
                BotLog.e(context, "panel.image.abort", "面板图片目标为空");
                return false;
            }

            asset = createShareAsset(context, file);
            if (!startShareIntent(context, asset)) {
                return false;
            }
            TargetCandidate selected = waitForTarget(context, config, hs, targetKey, target);
            if (selected == null) {
                BotLog.e(context, "panel.share.target.timeout", "最近聊天中未找到目标 target=" + target);
                return false;
            }
            hs.tap(selected.item.centerX, selected.item.centerY);
            BotLog.i(context, "panel.share.target.tap", "点击最近聊天目标 target=" + target
                    + " text=" + selected.item.text
                    + " match=" + Math.round(selected.matchRatio * 100f) + "%"
                    + " rect=" + selected.item.rect.flattenToString());

            if (!waitForConfirmPage(context, config, hs, targetKey, target)) {
                BotLog.e(context, "panel.share.confirm.timeout", "确认页未就绪 target=" + target);
                return false;
            }
            if (!tapStableGreenSend(context, config, hs, targetKey, target)) {
                BotLog.e(context, "panel.share.send.timeout", "确认页绿色发送按钮未就绪 target=" + target);
                return false;
            }
            if (!waitForConfirmExit(context, config, hs, targetKey)) {
                BotLog.e(context, "panel.share.submit.timeout", "点击发送后确认页未退出 target=" + target);
                return false;
            }

            sent = true;
            BotLog.write(context, "SUCCESS", "panel.image.share.done", "面板图片已发送 target="
                    + target + " costMs=" + (SystemClock.uptimeMillis() - startedAt));
            return true;
        } catch (Exception error) {
            BotLog.e(context, "panel.image.share.error", "面板图片分享异常 target=" + target
                    + " error=" + error.getMessage());
            return false;
        } finally {
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
            BotLog.i(context, "panel.share.intent.start", "已拉起微信图片分享页");
            return true;
        } catch (Exception error) {
            BotLog.e(context, "panel.share.intent.error", "拉起微信图片分享页失败: " + error.getMessage());
            return false;
        }
    }

    private TargetCandidate waitForTarget(Context context, BotConfig config, HsClient hs,
                                           String targetKey, String target) {
        long deadline = SystemClock.uptimeMillis() + TARGET_TIMEOUT_MS;
        TargetCandidate previous = null;
        int stableFrames = 0;
        int attempts = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++;
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            OcrHelper.OcrItem recentChats = findRecentChatsHeader(screen);
            TargetCandidate candidate = findBestTargetBelowRecentChats(screen, recentChats, targetKey);
            if (candidate != null) {
                stableFrames = sameTargetCandidate(previous, candidate) ? stableFrames + 1 : 1;
                previous = candidate;
                if (candidate.matchRatio >= 1f || stableFrames >= STABLE_FRAME_COUNT) {
                    BotLog.i(context, "panel.share.target.ready", "最近聊天目标已稳定 target=" + target
                            + " text=" + candidate.item.text
                            + " match=" + Math.round(candidate.matchRatio * 100f) + "%"
                            + " rect=" + candidate.item.rect.flattenToString()
                            + " attempts=" + attempts);
                    return candidate;
                }
            } else {
                previous = null;
                stableFrames = 0;
            }
            SystemClock.sleep(selectPoll(config));
        }
        return null;
    }

    private boolean waitForConfirmPage(Context context, BotConfig config, HsClient hs,
                                       String targetKey, String target) {
        long deadline = SystemClock.uptimeMillis() + CONFIRM_TIMEOUT_MS;
        int attempts = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++;
            ConfirmState current = findConfirmState(OcrHelper.inspect(context, hs), targetKey);
            if (current != null) {
                BotLog.i(context, "panel.share.confirm.ready", "确认页分区已就绪 target=" + target
                        + " sendTo=" + current.sendTo.rect.flattenToString()
                        + " targetRect=" + current.target.rect.flattenToString()
                        + " sendRect=" + describeRect(current.send)
                        + " attempts=" + attempts);
                return true;
            }
            SystemClock.sleep(confirmPoll(config));
        }
        return false;
    }

    private boolean tapStableGreenSend(Context context, BotConfig config, HsClient hs,
                                       String targetKey, String target) throws Exception {
        long deadline = SystemClock.uptimeMillis() + SEND_TIMEOUT_MS;
        ConfirmState previous = null;
        int stableFrames = 0;
        int attempts = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++;
            ConfirmState current = findConfirmState(OcrHelper.inspect(context, hs), targetKey);
            Rect greenButton = current == null ? null : OcrHelper.findShareConfirmGreenSendButton(context, hs);
            if (current != null && greenButton != null
                    && (current.send == null || overlapsSendLabel(greenButton, current.send.rect))) {
                hs.tap(greenButton.centerX(), greenButton.centerY());
                BotLog.i(context, "panel.share.send.green.tap", "点击绿色发送按钮 target=" + target
                        + " sendRect=" + describeRect(current.send)
                        + " greenRect=" + greenButton.flattenToString() + " attempts=" + attempts);
                return true;
            }
            if (current != null && current.send != null) {
                stableFrames = sameConfirmState(previous, current) ? stableFrames + 1 : 1;
                previous = current;
                if (stableFrames >= STABLE_FRAME_COUNT) {
                    hs.tap(current.send.centerX, current.send.centerY);
                    BotLog.i(context, "panel.share.send.ocr.tap", "绿色取色未命中，点击稳定 OCR 发送按钮 target="
                            + target + " sendRect=" + current.send.rect.flattenToString()
                            + " attempts=" + attempts);
                    return true;
                }
            } else {
                previous = null;
                stableFrames = 0;
            }
            SystemClock.sleep(sendPoll(config));
        }
        return false;
    }

    private boolean waitForConfirmExit(Context context, BotConfig config, HsClient hs, String targetKey) {
        long deadline = SystemClock.uptimeMillis() + SUBMIT_TIMEOUT_MS;
        int absentFrames = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            ConfirmState current = findConfirmState(OcrHelper.inspect(context, hs), targetKey);
            if (current == null) {
                absentFrames++;
                if (absentFrames >= STABLE_FRAME_COUNT) {
                    return true;
                }
            } else {
                absentFrames = 0;
            }
            SystemClock.sleep(submitPoll(config));
        }
        return false;
    }

    private OcrHelper.OcrItem findRecentChatsHeader(OcrHelper.Screen screen) {
        if (screen == null || screen.height <= 0) {
            return null;
        }
        OcrHelper.OcrItem result = null;
        int minY = Math.round(screen.height * 0.15f);
        int maxY = Math.round(screen.height * 0.60f);
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minY || item.centerY > maxY || !"最近聊天".equals(textKey(item.text))) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = item;
        }
        return result;
    }

    private TargetCandidate findBestTargetBelowRecentChats(OcrHelper.Screen screen,
                                                            OcrHelper.OcrItem recentChats,
                                                            String targetKey) {
        if (screen == null || recentChats == null || targetKey.isEmpty()) {
            return null;
        }
        int minY = recentChats.rect.bottom + Math.max(8, Math.round(screen.height * 0.005f));
        int maxY = screen.height - Math.max(96, Math.round(screen.height * 0.10f));
        TargetCandidate result = null;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minY || item.centerY > maxY) {
                continue;
            }
            float matchRatio = targetMatchRatio(targetKey, groupNameKey(item.text));
            if (matchRatio < MIN_TARGET_MATCH_RATIO) {
                continue;
            }
            if (result == null || matchRatio > result.matchRatio
                    || matchRatio == result.matchRatio && item.centerY < result.item.centerY) {
                result = new TargetCandidate(item, matchRatio);
            }
        }
        return result;
    }

    private ConfirmState findConfirmState(OcrHelper.Screen screen, String targetKey) {
        if (screen == null || screen.height <= 0) {
            return null;
        }
        int minHeaderY = Math.round(screen.height * 0.40f);
        int maxHeaderY = Math.round(screen.height * 0.70f);
        OcrHelper.OcrItem sendTo = null;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minHeaderY || item.centerY > maxHeaderY) {
                continue;
            }
            String key = textKey(item.text);
            if (key.contains("发送给") || key.contains("发送到")) {
                if (sendTo == null || item.centerY < sendTo.centerY) {
                    sendTo = item;
                }
            }
        }
        if (sendTo == null) {
            return null;
        }

        int minTargetY = sendTo.rect.bottom + Math.max(12, Math.round(screen.height * 0.008f));
        int maxTargetY = Math.min(Math.round(screen.height * 0.78f),
                sendTo.rect.bottom + Math.round(screen.height * 0.30f));
        OcrHelper.OcrItem target = findBestMatchingItem(screen, targetKey, minTargetY, maxTargetY,
                0f, 1f);
        if (target == null) {
            return null;
        }

        int minSendY = Math.max(Math.round(screen.height * 0.76f),
                target.rect.bottom + Math.round(screen.height * 0.14f));
        int maxSendY = Math.round(screen.height * 0.95f);
        OcrHelper.OcrItem send = null;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minSendY || item.centerY > maxSendY
                    || item.centerX < Math.round(screen.width * 0.35f)
                    || item.centerX > Math.round(screen.width * 0.90f)
                    || !"发送".equals(textKey(item.text))) {
                continue;
            }
            if (send == null || item.centerY > send.centerY) {
                send = item;
            }
        }
        return sendTo != null && target != null
                ? new ConfirmState(sendTo, target, send)
                : null;
    }

    private OcrHelper.OcrItem findBestMatchingItem(OcrHelper.Screen screen, String targetKey,
                                                    int minY, int maxY, float minX, float maxX) {
        OcrHelper.OcrItem best = null;
        float bestRatio = 0f;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minY || item.centerY > maxY
                    || item.centerX < screen.width * minX || item.centerX > screen.width * maxX) {
                continue;
            }
            float matchRatio = targetMatchRatio(targetKey, groupNameKey(item.text));
            if (matchRatio < MIN_TARGET_MATCH_RATIO) {
                continue;
            }
            if (best == null || matchRatio > bestRatio
                    || matchRatio == bestRatio && item.centerY < best.centerY) {
                best = item;
                bestRatio = matchRatio;
            }
        }
        return best;
    }

    private boolean sameTargetCandidate(TargetCandidate first, TargetCandidate second) {
        return first != null && second != null && sameVisualItem(first.item, second.item);
    }

    private boolean overlapsSendLabel(Rect greenButton, Rect sendLabel) {
        if (greenButton == null || sendLabel == null) {
            return false;
        }
        int padding = 24;
        return greenButton.left - padding <= sendLabel.right
                && greenButton.right + padding >= sendLabel.left
                && greenButton.top - padding <= sendLabel.bottom
                && greenButton.bottom + padding >= sendLabel.top;
    }

    private float targetMatchRatio(String targetKey, String candidateKey) {
        if (targetKey == null || candidateKey == null || targetKey.isEmpty() || candidateKey.isEmpty()) {
            return 0f;
        }
        if (targetKey.equals(candidateKey)) {
            return 1f;
        }
        int[] previous = new int[candidateKey.length() + 1];
        int[] current = new int[candidateKey.length() + 1];
        for (int targetIndex = 1; targetIndex <= targetKey.length(); targetIndex++) {
            char targetChar = targetKey.charAt(targetIndex - 1);
            for (int candidateIndex = 1; candidateIndex <= candidateKey.length(); candidateIndex++) {
                if (targetChar == candidateKey.charAt(candidateIndex - 1)) {
                    current[candidateIndex] = previous[candidateIndex - 1] + 1;
                } else {
                    current[candidateIndex] = Math.max(previous[candidateIndex], current[candidateIndex - 1]);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[candidateKey.length()] / (float) targetKey.length();
    }

    private boolean sameVisualItem(OcrHelper.OcrItem first, OcrHelper.OcrItem second) {
        return first != null && second != null
                && textKey(first.text).equals(textKey(second.text))
                && Math.abs(first.centerX - second.centerX) <= 48
                && Math.abs(first.centerY - second.centerY) <= 48;
    }

    private boolean sameConfirmState(ConfirmState first, ConfirmState second) {
        return first != null && second != null
                && sameVisualItem(first.sendTo, second.sendTo)
                && sameVisualItem(first.target, second.target);
    }

    private String describeRect(OcrHelper.OcrItem item) {
        return item == null ? "none" : item.rect.flattenToString();
    }

    private String groupNameKey(String value) {
        if (value == null) {
            return "";
        }
        return NameNormalizer.nameKey(value.replaceAll("[（(]\\s*\\d+\\s*人\\s*[）)]", ""));
    }

    private String textKey(String value) {
        return NameNormalizer.nameKey(value);
    }

    private long selectPoll(BotConfig config) {
        return config == null ? 250L : Math.max(200L, config.shareSelectPollMs);
    }

    private long confirmPoll(BotConfig config) {
        return config == null ? 250L : Math.max(200L, config.shareConfirmPollMs);
    }

    private long sendPoll(BotConfig config) {
        return config == null ? 250L : Math.max(200L, config.shareSendButtonPollMs);
    }

    private long submitPoll(BotConfig config) {
        return config == null ? 350L : Math.max(250L, config.shareSubmitPollMs);
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
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/VXBotPanel");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IllegalStateException("media insert failed");
                }
                copyFile(context, file, uri);
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
                return new ShareAsset(uri, mime, name, true);
            } catch (Exception error) {
                if (uri != null) {
                    context.getContentResolver().delete(uri, null, null);
                }
                BotLog.w(context, "panel.share.asset.fallback", error.getMessage());
            }
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        return new ShareAsset(uri, mime, file.getName(), false);
    }

    private void copyFile(Context context, File file, Uri uri) throws Exception {
        try (InputStream input = new FileInputStream(file);
             OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("media output unavailable");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void cleanupShareAsset(Context context, ShareAsset asset) {
        if (asset == null || !asset.publicMedia || asset.contentUri == null) {
            return;
        }
        try {
            context.getContentResolver().delete(asset.contentUri, null, null);
        } catch (Exception error) {
            BotLog.w(context, "panel.share.asset.cleanup.fail", error.getMessage());
        }
    }
}
