package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;

public final class WechatProfileFlow {
    public String analyze(Context context, BotConfig config, WxMessage message, String requestedTarget) {
        String targetName = requestedTarget == null || requestedTarget.trim().isEmpty()
                ? message.senderName : requestedTarget.trim();
        if (targetName.isEmpty()) {
            return "人物画像没有拿到目标昵称。";
        }
        HsClient hs = new HsClient(config.hsPort);
        OcrHelper.Screen profileScreen = null;
        ProfileCapture capture = null;
        try {
            profileScreen = openMemberProfile(context, hs, targetName);
            if (profileScreen == null) {
                return "我没在当前聊天记录附近找到“" + targetName + "”的头像，让他先在群里说句话再试。";
            }
            capture = captureProfile(context, hs, profileScreen);
        } catch (Exception e) {
            BotLog.e(context, "profile.persona.capture.error", "人物资料页抓取失败 target="
                    + targetName + " error=" + e.getMessage());
            return "“" + targetName + "”的资料页没抓稳，等他在群里说句话后再试。";
        } finally {
            if (profileScreen != null) {
                try {
                    hs.key("BACK");
                    SystemClock.sleep(900L);
                    BotLog.i(context, "profile.persona.back", "人物画像截图后已返回会话 target=" + targetName);
                } catch (Exception e) {
                    BotLog.w(context, "profile.persona.back.fail", e.getMessage());
                }
            }
        }
        if (capture == null || capture.profileBytes.length < 8192 || capture.avatarBytes.length < 4096) {
            return "“" + targetName + "”的资料页截图无效，暂时没法生成画像。";
        }
        try {
            String avatarDataUrl = "data:image/png;base64,"
                    + Base64.encodeToString(capture.avatarBytes, Base64.NO_WRAP);
            String profileDataUrl = "data:image/png;base64,"
                    + Base64.encodeToString(capture.profileBytes, Base64.NO_WRAP);
            String reply = new ChatClient().requestProfilePersona(
                    context, config, message, targetName, capture.profileText, avatarDataUrl, profileDataUrl);
            return "【" + targetName + "·人物画像】\n" + plainReply(reply);
        } catch (Exception e) {
            BotLog.e(context, "profile.persona.request.fail", "人物画像视觉请求失败 target="
                    + targetName + " error=" + e.getMessage());
            return "“" + targetName + "”的头像和签名已经拿到，但人物画像上游分析失败了。";
        }
    }

    private OcrHelper.Screen openMemberProfile(Context context, HsClient hs, String targetName) throws Exception {
        for (int attempt = 1; attempt <= 6; attempt++) {
            OcrHelper.Screen chat = OcrHelper.inspect(context, hs);
            OcrHelper.OcrItem nameItem = findLatestMemberName(chat, targetName);
            if (nameItem != null) {
                boolean left = nameItem.centerX < chat.width / 2;
                int offsetX = Math.max(42, Math.round(chat.width * 0.075f));
                int x = left
                        ? Math.max(Math.round(chat.width * 0.055f), nameItem.rect.left - offsetX)
                        : Math.min(Math.round(chat.width * 0.945f), nameItem.rect.right + offsetX);
                int y = Math.min(Math.round(chat.height * 0.84f),
                        nameItem.rect.bottom + Math.max(24, Math.round(chat.height * 0.025f)));
                hs.tap(x, y);
                BotLog.i(context, "profile.persona.avatar.tap", "点击成员头像 target=" + targetName
                        + " text=" + nameItem.text + " x=" + x + " y=" + y + " attempt=" + attempt);
                OcrHelper.Screen profile = waitProfileScreen(context, hs, targetName, 3500L);
                if (profile != null) {
                    return profile;
                }
                OcrHelper.Screen afterTap = OcrHelper.inspect(context, hs);
                if (afterTap != null && afterTap.bottom.score == 0) {
                    hs.key("BACK");
                    SystemClock.sleep(700L);
                }
            }
            if (attempt < 6 && chat != null) {
                hs.swipe(chat.width / 2, Math.round(chat.height * 0.40f),
                        chat.width / 2, Math.round(chat.height * 0.72f), 350);
                SystemClock.sleep(650L);
                BotLog.i(context, "profile.persona.chat.scroll", "当前屏未找到成员，向上查找历史消息 target="
                        + targetName + " attempt=" + attempt);
            }
        }
        BotLog.w(context, "profile.persona.member.missing", "聊天记录附近未找到成员昵称 target=" + targetName);
        return null;
    }

    private OcrHelper.Screen waitProfileScreen(Context context, HsClient hs, String targetName, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (looksLikeProfileScreen(screen, targetName)) {
                BotLog.i(context, "profile.persona.page.ready", "已确认成员资料页 target=" + targetName
                        + " snippets=" + screen.snippets);
                return screen;
            }
            SystemClock.sleep(400L);
        }
        return null;
    }

    private OcrHelper.OcrItem findLatestMemberName(OcrHelper.Screen screen, String targetName) {
        if (screen == null) {
            return null;
        }
        OcrHelper.OcrItem exact = null;
        OcrHelper.OcrItem loose = null;
        String target = NameNormalizer.nameKey(targetName);
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < screen.height * 0.12f || item.centerY > screen.height * 0.86f) {
                continue;
            }
            String value = NameNormalizer.nameKey(item.text);
            if (value.equals(target)) {
                if (exact == null || item.centerY > exact.centerY) {
                    exact = item;
                }
            } else if (NameNormalizer.looseNameMatch(item.text, targetName)
                    && (loose == null || item.centerY > loose.centerY)) {
                loose = item;
            }
        }
        return exact != null ? exact : loose;
    }

    private boolean looksLikeProfileScreen(OcrHelper.Screen screen, String targetName) {
        if (screen == null) {
            return false;
        }
        boolean nameHit = false;
        int markerCount = 0;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < screen.height * 0.32f && NameNormalizer.looseNameMatch(item.text, targetName)) {
                nameHit = true;
            }
            String value = item.text == null ? "" : item.text.trim();
            if (value.contains("朋友资料") || value.equals("签名") || value.contains("朋友圈")
                    || value.contains("发消息")) {
                markerCount++;
            }
        }
        return nameHit && markerCount >= 1 || markerCount >= 2;
    }

    private ProfileCapture captureProfile(Context context, HsClient hs, OcrHelper.Screen screen) {
        Bitmap bitmap = OcrHelper.captureBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            int top = Math.max(0, Math.round(bitmap.getHeight() * 0.07f));
            int bottom = Math.min(bitmap.getHeight(), Math.round(bitmap.getHeight() * 0.48f));
            Bitmap profileCrop = Bitmap.createBitmap(bitmap, 0, top, bitmap.getWidth(), bottom - top);
            try {
                byte[] profileBytes = pngBytes(profileCrop);
                int avatarLeft = Math.max(0, Math.round(bitmap.getWidth() * 0.025f));
                int avatarTop = Math.max(0, Math.round(bitmap.getHeight() * 0.085f));
                int avatarRight = Math.min(bitmap.getWidth(), Math.round(bitmap.getWidth() * 0.24f));
                int avatarBottom = Math.min(bitmap.getHeight(), Math.round(bitmap.getHeight() * 0.21f));
                Bitmap avatarCrop = Bitmap.createBitmap(bitmap, avatarLeft, avatarTop,
                        avatarRight - avatarLeft, avatarBottom - avatarTop);
                byte[] avatarBytes;
                try {
                    Bitmap enlarged = Bitmap.createScaledBitmap(avatarCrop,
                            avatarCrop.getWidth() * 4, avatarCrop.getHeight() * 4, true);
                    try {
                        avatarBytes = pngBytes(enlarged);
                    } finally {
                        enlarged.recycle();
                    }
                } finally {
                    avatarCrop.recycle();
                }
                String profileText = profileText(screen);
                BotLog.i(context, "profile.persona.capture", "成员资料页截图完成 profileBytes="
                        + profileBytes.length + " avatarBytes=" + avatarBytes.length
                        + " ocr=" + profileText);
                return new ProfileCapture(profileBytes, avatarBytes, profileText);
            } finally {
                profileCrop.recycle();
            }
        } catch (Exception e) {
            BotLog.w(context, "profile.persona.capture.fail", e.getMessage());
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private String profileText(OcrHelper.Screen screen) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < screen.height * 0.07f || item.centerY > screen.height * 0.55f) {
                continue;
            }
            String value = item.text == null ? "" : item.text.trim();
            if (!value.isEmpty() && value.length() <= 80) {
                lines.add(value);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(line);
            if (out.length() >= 500) {
                break;
            }
        }
        return out.toString();
    }

    private String plainReply(String reply) {
        String value = reply == null ? "" : reply
                .replace("**", "")
                .replace("__", "")
                .replace("```", "")
                .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*-\\s*", "• ")
                .trim();
        if (value.startsWith("人物画像：") || value.startsWith("人物画像:")) {
            int line = value.indexOf('\n');
            if (line >= 0 && line + 1 < value.length()) {
                value = value.substring(line + 1).trim();
            }
        }
        return value;
    }

    private byte[] pngBytes(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private static final class ProfileCapture {
        final byte[] profileBytes;
        final byte[] avatarBytes;
        final String profileText;

        ProfileCapture(byte[] profileBytes, byte[] avatarBytes, String profileText) {
            this.profileBytes = profileBytes;
            this.avatarBytes = avatarBytes;
            this.profileText = profileText == null ? "" : profileText;
        }
    }
}
