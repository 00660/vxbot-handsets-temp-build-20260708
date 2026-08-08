package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.text.Normalizer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShareTargetOcr {
    private static final int SCALE = 3;
    private static final float TEXT_LEFT_RATIO = 0.145f;
    private static final float TEXT_RIGHT_RATIO = 0.92f;
    private static final float LIST_TOP_RATIO = 0.35f;
    private static final float LIST_BOTTOM_RATIO = 0.94f;
    private static final float BAND_HEIGHT_RATIO = 0.09f;
    private static final float BAND_STEP_RATIO = 0.055f;
    private static final int TEXT_LUMINANCE_THRESHOLD = 210;
    private static final Pattern MEMBER_COUNT_SUFFIX = Pattern.compile(
            "(?:\\(\\d+(?:人|A)?\\)?|\\d+人)$");
    private static final TextRecognizer RECOGNIZER =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    private ShareTargetOcr() {
    }

    static Hit findVisibleTarget(Context context, HsClient hs, String sessionName) {
        String target = strictNameKey(sessionName);
        if (target.isEmpty()) {
            return null;
        }
        Bitmap screenshot = OcrHelper.captureBitmap(context, hs);
        if (screenshot == null) {
            return null;
        }
        try {
            int cropLeft = Math.round(screenshot.getWidth() * TEXT_LEFT_RATIO);
            int cropRight = Math.round(screenshot.getWidth() * TEXT_RIGHT_RATIO);
            int searchTop = Math.round(screenshot.getHeight() * LIST_TOP_RATIO);
            int searchBottom = Math.round(screenshot.getHeight() * LIST_BOTTOM_RATIO);
            int bandHeight = Math.max(96, Math.round(screenshot.getHeight() * BAND_HEIGHT_RATIO));
            int bandStep = Math.max(64, Math.round(screenshot.getHeight() * BAND_STEP_RATIO));
            StringBuilder snippets = new StringBuilder();
            for (int bandTop = searchTop; bandTop < searchBottom - 48; bandTop += bandStep) {
                int currentHeight = Math.min(bandHeight, searchBottom - bandTop);
                Bitmap band = null;
                Bitmap scaled = null;
                Bitmap enhanced = null;
                try {
                    band = Bitmap.createBitmap(screenshot, cropLeft, bandTop,
                            cropRight - cropLeft, currentHeight);
                    scaled = Bitmap.createScaledBitmap(band,
                            band.getWidth() * SCALE, band.getHeight() * SCALE, true);
                    enhanced = enhanceTextContrast(scaled);
                    Text recognized = recognize(enhanced);
                    if (recognized == null) {
                        BotLog.w(context, "image.share.target.ocr.timeout", "分享目标专用 OCR 超时 target=" + sessionName
                                + " bandTop=" + bandTop);
                        continue;
                    }
                    for (Text.TextBlock block : recognized.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            appendSnippet(snippets, line.getText());
                            Hit hit = exactHit(line.getText(), line.getBoundingBox(), target, cropLeft, bandTop);
                            if (hit != null) {
                                logHit(context, sessionName, hit);
                                return hit;
                            }
                        }
                    }
                } finally {
                    if (enhanced != null) {
                        enhanced.recycle();
                    }
                    if (scaled != null) {
                        scaled.recycle();
                    }
                    if (band != null) {
                        band.recycle();
                    }
                }
            }
            BotLog.w(context, "image.share.target.ocr.miss", "分享目标专用 OCR 未命中 target=" + sessionName
                    + " snippets=" + snippets);
            return null;
        } catch (Exception e) {
            BotLog.w(context, "image.share.target.ocr.error", "分享目标专用 OCR 异常 target=" + sessionName
                    + " error=" + e.getMessage());
            return null;
        } finally {
            if (screenshot != null) {
                screenshot.recycle();
            }
        }
    }

    private static Bitmap enhanceTextContrast(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
            pixels[i] = luminance < TEXT_LUMINANCE_THRESHOLD ? Color.BLACK : Color.WHITE;
        }
        Bitmap enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        enhanced.setPixels(pixels, 0, width, 0, 0, width, height);
        return enhanced;
    }

    private static void appendSnippet(StringBuilder snippets, String text) {
        if (text == null || text.trim().isEmpty() || snippets.length() >= 320) {
            return;
        }
        if (snippets.length() > 0) {
            snippets.append('|');
        }
        String value = text.trim().replace('|', '/');
        snippets.append(value, 0, Math.min(value.length(), 60));
    }

    private static void logHit(Context context, String sessionName, Hit hit) {
        BotLog.i(context, "image.share.target.ocr.hit", "分享目标专用 OCR 命中 target=" + sessionName
                + " text=" + hit.text + " rect=" + hit.rect.flattenToString());
    }

    private static Hit exactHit(String text, Rect scaledRect, String target, int cropLeft, int cropTop) {
        if (scaledRect == null || !sameTargetText(text, target)) {
            return null;
        }
        Rect rect = new Rect(
                cropLeft + Math.round(scaledRect.left / (float) SCALE),
                cropTop + Math.round(scaledRect.top / (float) SCALE),
                cropLeft + Math.round(scaledRect.right / (float) SCALE),
                cropTop + Math.round(scaledRect.bottom / (float) SCALE));
        return new Hit(text, rect);
    }

    private static boolean sameTargetText(String text, String target) {
        String value = strictNameKey(text);
        Matcher matcher = MEMBER_COUNT_SUFFIX.matcher(value);
        if (matcher.find()) {
            value = value.substring(0, matcher.start());
        }
        return value.equals(target);
    }

    private static String strictNameKey(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        }
        return result.toString();
    }

    private static Text recognize(Bitmap bitmap) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Text[] result = new Text[1];
        Exception[] error = new Exception[1];
        RECOGNIZER.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    result[0] = text;
                    latch.countDown();
                })
                .addOnFailureListener(exception -> {
                    error[0] = exception;
                    latch.countDown();
                });
        if (!latch.await(8, TimeUnit.SECONDS)) {
            return null;
        }
        if (error[0] != null) {
            throw error[0];
        }
        return result[0];
    }

    static final class Hit {
        final String text;
        final Rect rect;

        private Hit(String text, Rect rect) {
            this.text = text == null ? "" : text;
            this.rect = new Rect(rect);
        }

        static Hit from(OcrHelper.OcrItem item) {
            return item == null ? null : new Hit(item.text, item.rect);
        }
    }
}
