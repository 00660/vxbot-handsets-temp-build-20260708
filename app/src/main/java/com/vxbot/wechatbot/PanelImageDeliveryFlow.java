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
 * 每个可见页面阶段都要求连续两帧 OCR 证据后才进入下一步，避免页面尚未绘制完成时误点。
 */
public final class PanelImageDeliveryFlow {
    private static final int STABLE_FRAME_COUNT = 2;
    private static final long SHARE_LIST_TIMEOUT_MS = 30000L;
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
            if (!waitForRecentChats(context, config, hs)) {
                BotLog.e(context, "panel.share.list.timeout", "分享页最近聊天未就绪 target=" + target);
                return false;
            }

            OcrHelper.OcrItem selected = waitForUniqueTarget(context, config, hs, targetKey, target);
            if (selected == null) {
                BotLog.e(context, "panel.share.target.timeout", "最近聊天中未找到唯一目标 target=" + target);
                return false;
            }
            hs.tap(selected.centerX, selected.centerY);
            BotLog.i(context, "panel.share.target.tap", "点击最近聊天目标 target=" + target
                    + " rect=" + selected.rect.flattenToString());

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

    private boolean waitForRecentChats(Context context, BotConfig config, HsClient hs) {
        long deadline = SystemClock.uptimeMillis() + SHARE_LIST_TIMEOUT_MS;
        OcrHelper.OcrItem previous = null;
        int stableFrames = 0;
        int attempts = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++;
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            OcrHelper.OcrItem recentChats = findRecentChatsHeader(screen);
            if (recentChats != null) {
                stableFrames = sameVisualItem(previous, recentChats) ? stableFrames + 1 : 1;
                previous = recentChats;
                if (stableFrames >= STABLE_FRAME_COUNT) {
                    BotLog.i(context, "panel.share.list.ready", "最近聊天已稳定 rect="
                            + recentChats.rect.flattenToString() + " attempts=" + attempts);
                    return true;
                }
            } else {
                previous = null;
                stableFrames = 0;
            }
            SystemClock.sleep(selectPoll(config));
        }
        return false;
    }

    private OcrHelper.OcrItem waitForUniqueTarget(Context context, BotConfig config, HsClient hs,
                                                   String targetKey, String target) {
        long deadline = SystemClock.uptimeMillis() + TARGET_TIMEOUT_MS;
        OcrHelper.OcrItem previous = null;
        int stableFrames = 0;
        int attempts = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++;
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            OcrHelper.OcrItem recentChats = findRecentChatsHeader(screen);
            OcrHelper.OcrItem candidate = findUniqueTargetBelowRecentChats(screen, recentChats, targetKey);
            if (candidate != null) {
                stableFrames = sameVisualItem(previous, candidate) ? stableFrames + 1 : 1;
                previous = candidate;
                if (stableFrames >= STABLE_FRAME_COUNT) {
                    BotLog.i(context, "panel.share.target.ready", "最近聊天目标已稳定 target=" + target
                            + " text=" + candidate.text + " rect=" + candidate.rect.flattenToString()
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
        ConfirmState previous = null;
        int stableFrames = 0;
        int attempts = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            attempts++;
            ConfirmState current = findConfirmState(OcrHelper.inspect(context, hs), targetKey);
            if (current != null) {
                stableFrames = sameConfirmState(previous, current) ? stableFrames + 1 : 1;
                previous = current;
                if (stableFrames >= STABLE_FRAME_COUNT) {
                    BotLog.i(context, "panel.share.confirm.ready", "确认页已稳定 target=" + target
                            + " attempts=" + attempts);
                    return true;
                }
            } else {
                previous = null;
                stableFrames = 0;
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
            boolean sendMatchesGreenButton = current != null && greenButton != null
                    && greenButton.contains(current.send.centerX, current.send.centerY);
            if (sendMatchesGreenButton) {
                stableFrames = sameConfirmState(previous, current) ? stableFrames + 1 : 1;
                previous = current;
                if (stableFrames >= STABLE_FRAME_COUNT) {
                    hs.tap(current.send.centerX, current.send.centerY);
                    BotLog.i(context, "panel.share.send.tap", "点击 OCR 发送按钮 target=" + target
                            + " sendRect=" + current.send.rect.flattenToString()
                            + " greenRect=" + greenButton.flattenToString() + " attempts=" + attempts);
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

    private OcrHelper.OcrItem findUniqueTargetBelowRecentChats(OcrHelper.Screen screen,
                                                                 OcrHelper.OcrItem recentChats,
                                                                 String targetKey) {
        if (screen == null || recentChats == null || targetKey.isEmpty()) {
            return null;
        }
        int minY = recentChats.rect.bottom + Math.max(8, Math.round(screen.height * 0.005f));
        int maxY = screen.height - Math.max(96, Math.round(screen.height * 0.10f));
        OcrHelper.OcrItem result = null;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minY || item.centerY > maxY || !targetKey.equals(groupNameKey(item.text))) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = item;
        }
        return result;
    }

    private ConfirmState findConfirmState(OcrHelper.Screen screen, String targetKey) {
        if (screen == null || screen.height <= 0) {
            return null;
        }
        OcrHelper.OcrItem sendTo = null;
        OcrHelper.OcrItem target = null;
        OcrHelper.OcrItem send = null;
        int minContentY = Math.round(screen.height * 0.15f);
        int maxContentY = Math.round(screen.height * 0.88f);
        int minSendY = Math.round(screen.height * 0.45f);
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < minContentY || item.centerY > maxContentY) {
                continue;
            }
            String key = textKey(item.text);
            if (key.contains("发送给")) {
                if (sendTo != null) {
                    return null;
                }
                sendTo = item;
            }
            if (targetKey.equals(groupNameKey(item.text))) {
                if (target != null) {
                    return null;
                }
                target = item;
            }
            if (item.centerY >= minSendY && "发送".equals(key)) {
                if (send != null) {
                    return null;
                }
                send = item;
            }
        }
        return sendTo != null && target != null && send != null
                ? new ConfirmState(sendTo, target, send)
                : null;
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
                && sameVisualItem(first.target, second.target)
                && sameVisualItem(first.send, second.send);
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
