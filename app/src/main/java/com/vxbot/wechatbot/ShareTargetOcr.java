package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ShareTargetOcr {
    private static final int SCALE = 2;
    private static final TextRecognizer RECOGNIZER =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    private ShareTargetOcr() {
    }

    static Hit findVisibleTarget(Context context, HsClient hs, String sessionName) {
        String target = NameNormalizer.nameKey(sessionName);
        if (target.isEmpty()) {
            return null;
        }
        Bitmap screenshot = OcrHelper.captureBitmap(context, hs);
        if (screenshot == null) {
            return null;
        }
        Bitmap crop = null;
        Bitmap scaled = null;
        try {
            int cropLeft = Math.round(screenshot.getWidth() * 0.13f);
            int cropTop = Math.round(screenshot.getHeight() * 0.34f);
            int cropRight = Math.round(screenshot.getWidth() * 0.97f);
            int cropBottom = Math.round(screenshot.getHeight() * 0.97f);
            crop = Bitmap.createBitmap(screenshot, cropLeft, cropTop,
                    cropRight - cropLeft, cropBottom - cropTop);
            scaled = Bitmap.createScaledBitmap(crop,
                    crop.getWidth() * SCALE, crop.getHeight() * SCALE, true);
            crop.recycle();
            crop = null;
            screenshot.recycle();
            screenshot = null;
            Text recognized = recognize(scaled);
            if (recognized == null) {
                BotLog.w(context, "image.share.target.ocr.timeout", "分享目标专用 OCR 超时 target=" + sessionName);
                return null;
            }
            for (Text.TextBlock block : recognized.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Hit hit = exactHit(line.getText(), line.getBoundingBox(), target, cropLeft, cropTop);
                    if (hit != null) {
                        BotLog.i(context, "image.share.target.ocr.hit", "分享目标专用 OCR 命中 target=" + sessionName
                                + " text=" + hit.text + " rect=" + hit.rect.flattenToString());
                        return hit;
                    }
                    for (Text.Element element : line.getElements()) {
                        hit = exactHit(element.getText(), element.getBoundingBox(), target, cropLeft, cropTop);
                        if (hit != null) {
                            BotLog.i(context, "image.share.target.ocr.hit", "分享目标专用 OCR 命中 target=" + sessionName
                                    + " text=" + hit.text + " rect=" + hit.rect.flattenToString());
                            return hit;
                        }
                    }
                }
            }
            BotLog.w(context, "image.share.target.ocr.miss", "分享目标专用 OCR 未命中 target=" + sessionName);
            return null;
        } catch (Exception e) {
            BotLog.w(context, "image.share.target.ocr.error", "分享目标专用 OCR 异常 target=" + sessionName
                    + " error=" + e.getMessage());
            return null;
        } finally {
            if (scaled != null) {
                scaled.recycle();
            }
            if (crop != null) {
                crop.recycle();
            }
            if (screenshot != null) {
                screenshot.recycle();
            }
        }
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
        String normalized = NameNormalizer.nameKey(text);
        if (normalized.equals(target)) {
            return true;
        }
        String withoutMemberCount = normalized.replaceFirst("\\d+(?:人|A)?$", "");
        return withoutMemberCount.equals(target);
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
