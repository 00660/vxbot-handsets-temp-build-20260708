package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class OcrHelper {
    private static final TextRecognizer RECOGNIZER =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    private OcrHelper() {
    }

    public static final class OcrItem {
        public final String text;
        public final String clean;
        public final Rect rect;
        public final int centerX;
        public final int centerY;

        private OcrItem(String text, Rect rect) {
            this.text = text == null ? "" : text;
            this.clean = normalize(this.text);
            this.rect = rect == null ? new Rect() : new Rect(rect);
            this.centerX = this.rect.centerX();
            this.centerY = this.rect.centerY();
        }
    }

    public static final class BottomFeature {
        public final int score;
        public final float inputBrightRatio;
        public final int leftDarkHit;
        public final int rightDarkHit;
        public final int toolbarScore;
        public final int voiceIconHit;
        public final int rightIconHit;

        private BottomFeature(int score, float inputBrightRatio, int leftDarkHit, int rightDarkHit,
                              int toolbarScore, int voiceIconHit, int rightIconHit) {
            this.score = score;
            this.inputBrightRatio = inputBrightRatio;
            this.leftDarkHit = leftDarkHit;
            this.rightDarkHit = rightDarkHit;
            this.toolbarScore = toolbarScore;
            this.voiceIconHit = voiceIconHit;
            this.rightIconHit = rightIconHit;
        }
    }

    public static final class Screen {
        public final int width;
        public final int height;
        public final List<OcrItem> items;
        public final BottomFeature bottom;
        public final String snippets;

        private Screen(int width, int height, List<OcrItem> items, BottomFeature bottom) {
            this.width = width;
            this.height = height;
            this.items = Collections.unmodifiableList(items);
            this.bottom = bottom;
            this.snippets = snippets(items);
        }
    }

    public static final class InputModeFeature {
        public final String mode;
        public final int width;
        public final int height;
        public final Rect inputRect;
        public final Rect toggleRect;
        public final boolean pressTalkTextHit;
        public final boolean textInputVisualHit;
        public final boolean keyboardIconLikely;
        public final boolean voiceIconLikely;
        public final int keyboardIconScore;
        public final int voiceIconScore;
        public final boolean voiceBarVisualHit;
        public final boolean keyboardVisualHit;
        public final boolean imeWindowVisible;
        public final float inputCenterDarkRatio;
        public final float inputCenterBrightRatio;
        public final float keyboardAreaDarkRatio;
        public final float keyboardAreaBrightRatio;
        public final String snippets;

        private InputModeFeature(String mode, int width, int height, Rect inputRect, Rect toggleRect,
                                 boolean pressTalkTextHit, boolean textInputVisualHit,
                                 IconShapeFeature icon, InputModeVisualFeature visual,
                                 boolean imeWindowVisible, String snippets) {
            this.mode = mode;
            this.width = width;
            this.height = height;
            this.inputRect = inputRect == null ? null : new Rect(inputRect);
            this.toggleRect = toggleRect == null ? null : new Rect(toggleRect);
            this.pressTalkTextHit = pressTalkTextHit;
            this.textInputVisualHit = textInputVisualHit;
            this.keyboardIconLikely = icon != null && icon.keyboardLikely;
            this.voiceIconLikely = icon != null && icon.voiceLikely;
            this.keyboardIconScore = icon == null ? 0 : icon.keyboardScore;
            this.voiceIconScore = icon == null ? 0 : icon.voiceScore;
            this.voiceBarVisualHit = visual != null && visual.voiceBarLikely;
            this.keyboardVisualHit = visual != null && visual.keyboardLikely;
            this.imeWindowVisible = imeWindowVisible;
            this.inputCenterDarkRatio = visual == null ? 0f : visual.inputCenterDarkRatio;
            this.inputCenterBrightRatio = visual == null ? 0f : visual.inputCenterBrightRatio;
            this.keyboardAreaDarkRatio = visual == null ? 0f : visual.keyboardAreaDarkRatio;
            this.keyboardAreaBrightRatio = visual == null ? 0f : visual.keyboardAreaBrightRatio;
            this.snippets = snippets == null ? "" : snippets;
        }

        public boolean isVoiceModeLikely() {
            return "voice".equals(mode);
        }

        public boolean isTextModeLikely() {
            return "text".equals(mode);
        }

        public int toggleCenterX() {
            return toggleRect == null ? Math.round(width * 0.055f) : toggleRect.centerX();
        }

        public int toggleCenterY() {
            if (toggleRect != null) {
                return toggleRect.centerY();
            }
            if (inputRect != null) {
                return inputRect.centerY();
            }
            return Math.round(height * 0.91f);
        }

        public String summary() {
            return "mode=" + mode
                    + " input=" + (inputRect == null ? "null" : inputRect.flattenToString())
                    + " leftBottomToggle=" + (toggleRect == null ? "null" : toggleRect.flattenToString())
                    + " pressTalk=" + pressTalkTextHit
                    + " textInputVisual=" + textInputVisualHit
                    + " keyboardIcon=" + keyboardIconLikely + "/" + keyboardIconScore
                    + " voiceIcon=" + voiceIconLikely + "/" + voiceIconScore
                    + " voiceBarVisual=" + voiceBarVisualHit
                    + " keyboardVisual=" + keyboardVisualHit
                    + " imeVisible=" + imeWindowVisible
                    + " centerDark=" + ratio(inputCenterDarkRatio)
                    + " centerBright=" + ratio(inputCenterBrightRatio)
                    + " keyboardDark=" + ratio(keyboardAreaDarkRatio)
                    + " keyboardBright=" + ratio(keyboardAreaBrightRatio)
                    + " snippets=" + snippets;
        }
    }

    public static Screen inspect(Context context, HsClient hs) {
        Bitmap bitmap = null;
        try {
            byte[] bytes = hs.screenshotJpeg();
            if (startsWithErr(bytes)) {
                BotLog.w(context, "ocr.screenshot.fail", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                return null;
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                BotLog.w(context, "ocr.screenshot.decode.fail", "截图解码失败 bytes=" + bytes.length);
                return null;
            }
            Text text = recognize(bitmap);
            if (text == null) {
                BotLog.w(context, "ocr.timeout", "OCR 超时");
                return null;
            }
            return new Screen(bitmap.getWidth(), bitmap.getHeight(), collectItems(text), chatBottomFeature(bitmap));
        } catch (Exception e) {
            BotLog.w(context, "ocr.error", e.getMessage());
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    public static Rect findBottomRightText(Context context, HsClient hs, String target) {
        Screen screen = inspect(context, hs);
        if (screen == null) {
            return null;
        }
        OcrItem item = chooseBestTextItem(screen, target, 0.45f, 1.0f, 0.45f, 1.0f);
        if (item == null) {
            BotLog.w(context, "ocr.text.miss", "未识别到 " + target + " / snippets=" + screen.snippets);
            return null;
        }
        BotLog.i(context, "ocr.text.hit", "OCR 命中 " + target + " rect=" + item.rect.flattenToString());
        return item.rect;
    }

    public static String dumpScreen(Screen screen, int maxItems) {
        if (screen == null) {
            return "screen=null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("size=").append(screen.width).append('x').append(screen.height)
                .append(" items=").append(screen.items.size())
                .append(" bottomScore=").append(screen.bottom == null ? -1 : screen.bottom.score);
        int limit = Math.min(Math.max(1, maxItems), screen.items.size());
        for (int i = 0; i < limit; i++) {
            OcrItem item = screen.items.get(i);
            builder.append(" | #").append(i)
                    .append(" text=").append(item.text.replace('\n', ' '))
                    .append(" clean=").append(item.clean)
                    .append(" rect=").append(item.rect.flattenToString())
                    .append(" center=").append(item.centerX).append(',').append(item.centerY);
        }
        return builder.toString();
    }

    public static Rect findChatInputBlock(Context context, HsClient hs) {
        Bitmap bitmap = screenshotBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            Rect rect = findTextInputBlock(bitmap);
            if (rect == null) {
                BotLog.w(context, "input.image.missing", "截图未识别到聊天输入框");
                return null;
            }
            BotLog.i(context, "input.image.hit", "截图识别到聊天输入框 rect=" + rect.flattenToString());
            return rect;
        } finally {
            bitmap.recycle();
        }
    }

    public static InputModeFeature inspectInputMode(Context context, HsClient hs) {
        Bitmap bitmap = screenshotBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Text text = recognize(bitmap);
            List<OcrItem> items = text == null ? Collections.emptyList() : collectItems(text);
            String snippets = snippets(items);
            Rect pressTalkRect = findPressTalkTextRect(items, width, height);
            Rect visualInput = findTextInputBlock(bitmap);
            Rect input = pressTalkRect != null ? pressTalkRect : visualInput;
            IconShapeFeature icon = analyzeInputModeIcon(bitmap, input);
            InputModeVisualFeature visual = analyzeInputModeVisual(bitmap);
            boolean imeVisible = isInputMethodVisible(hs);
            boolean pressTalk = pressTalkRect != null;
            boolean textInputVisual = !pressTalk && visualInput != null && visualInput.width() >= Math.round(width * 0.22f);
            boolean raisedToolbar = visualInput != null && visualInput.centerY() < Math.round(height * 0.82f);
            int voiceScore = 0;
            int textScore = 0;
            if (pressTalk) {
                voiceScore += 10;
            }
            if (visual.voiceBarLikely) {
                voiceScore += 4;
            }
            if (icon.keyboardLikely) {
                voiceScore += 2;
            }
            if (visual.keyboardLikely || imeVisible) {
                textScore += 5;
            }
            if (textInputVisual && !pressTalk && (raisedToolbar || icon.voiceLikely)) {
                textScore += 3;
            }
            if (icon.voiceLikely) {
                textScore += 3;
            }
            String mode = "unknown";
            if (pressTalk || voiceScore >= textScore + 3 && visual.voiceBarLikely) {
                mode = "voice";
            } else if (visual.keyboardLikely || imeVisible || icon.voiceLikely || textInputVisual && (raisedToolbar || textScore >= 3)) {
                mode = "text";
            }
            InputModeFeature feature = new InputModeFeature(mode, width, height, input, icon.region,
                    pressTalk, textInputVisual, icon, visual, imeVisible, snippets);
            BotLog.i(context, "input.mode.inspect", feature.summary());
            return feature;
        } catch (Exception e) {
            BotLog.w(context, "input.mode.inspect.fail", e.getMessage());
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    public static Rect findGreenSendButton(Context context, HsClient hs) {
        Bitmap bitmap = screenshotBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            Rect rect = findGreenSendBlock(bitmap);
            if (rect == null) {
                BotLog.w(context, "send.button.green.miss", "截图取色未找到绿色发送按钮");
                return null;
            }
            BotLog.i(context, "send.button.green.hit", "截图取色命中绿色发送按钮 rect=" + rect.flattenToString());
            return rect;
        } finally {
            bitmap.recycle();
        }
    }

    public static Rect findShareConfirmGreenSendButton(Context context, HsClient hs) {
        Bitmap bitmap = screenshotBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            Rect rect = findShareConfirmGreenSendBlock(bitmap);
            if (rect == null) {
                BotLog.w(context, "image.share.send.green.miss", "分享确认页取色未找到绿色发送按钮");
                return null;
            }
            BotLog.i(context, "image.share.send.green.hit", "分享确认页取色命中绿色发送按钮 rect=" + rect.flattenToString());
            return rect;
        } finally {
            bitmap.recycle();
        }
    }

    public static Rect findTopRightSearchIcon(Context context, HsClient hs) {
        Bitmap bitmap = screenshotBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            Rect rect = findTopRightSearchIconBlock(bitmap);
            if (rect == null) {
                BotLog.w(context, "search.icon.miss", "截图未找到顶部放大镜图标");
                return null;
            }
            BotLog.i(context, "search.icon.hit", "截图命中顶部放大镜图标 rect=" + rect.flattenToString());
            return rect;
        } finally {
            bitmap.recycle();
        }
    }

    public static Bitmap captureBitmap(Context context, HsClient hs) {
        return screenshotBitmap(context, hs);
    }

    public static OcrItem chooseBestTextItem(Screen screen, String target, float minX, float maxX, float minY, float maxY) {
        if (screen == null) {
            return null;
        }
        String normalizedTarget = normalize(target);
        if (normalizedTarget.isEmpty()) {
            return null;
        }
        OcrItem best = null;
        int bestScore = Integer.MIN_VALUE;
        for (OcrItem item : screen.items) {
            if (!item.clean.contains(normalizedTarget)) {
                continue;
            }
            if (item.centerX < screen.width * minX || item.centerX > screen.width * maxX) {
                continue;
            }
            if (item.centerY < screen.height * minY || item.centerY > screen.height * maxY) {
                continue;
            }
            int score = item.centerX + item.centerY * 2;
            if (score > bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best;
    }

    private static Rect findTopRightSearchIconBlock(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int minX = clamp(Math.round(width * 0.72f), 0, width - 1);
        int maxX = clamp(width - Math.round(width * 0.08f), minX, width - 1);
        int minY = clamp(Math.round(height * 0.045f), 0, height - 1);
        int maxY = clamp(Math.round(height * 0.095f), minY, height - 1);
        int gridW = Math.max(1, maxX - minX + 1);
        int gridH = Math.max(1, maxY - minY + 1);
        boolean[] mask = new boolean[gridW * gridH];
        boolean[] seen = new boolean[gridW * gridH];
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                mask[gy * gridW + gx] = isDarkColor(bitmap.getPixel(minX + gx, minY + gy));
            }
        }
        Rect best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int start = gy * gridW + gx;
                if (!mask[start] || seen[start]) {
                    continue;
                }
                int[] queueX = new int[gridW * gridH];
                int[] queueY = new int[gridW * gridH];
                int head = 0;
                int tail = 0;
                queueX[tail] = gx;
                queueY[tail] = gy;
                tail++;
                seen[start] = true;
                int minGx = gx;
                int maxGx = gx;
                int minGy = gy;
                int maxGy = gy;
                int count = 0;
                while (head < tail) {
                    int cx = queueX[head];
                    int cy = queueY[head];
                    head++;
                    count++;
                    minGx = Math.min(minGx, cx);
                    maxGx = Math.max(maxGx, cx);
                    minGy = Math.min(minGy, cy);
                    maxGy = Math.max(maxGy, cy);
                    tail = enqueueMaskNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx + 1, cy);
                    tail = enqueueMaskNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx - 1, cy);
                    tail = enqueueMaskNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy + 1);
                    tail = enqueueMaskNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy - 1);
                }
                Rect rect = new Rect(minX + minGx, minY + minGy, minX + maxGx + 1, minY + maxGy + 1);
                int blockWidth = rect.width();
                int blockHeight = rect.height();
                if (count >= 35
                        && blockWidth >= 16 && blockWidth <= 48
                        && blockHeight >= 16 && blockHeight <= 48
                        && rect.centerX() >= Math.round(width * 0.78f)
                        && rect.centerX() <= Math.round(width * 0.91f)) {
                    int score = count * 10 - Math.abs(rect.width() - rect.height()) * 3;
                    if (score > bestScore) {
                        best = rect;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private static int enqueueMaskNeighbor(boolean[] mask, boolean[] seen, int[] queueX, int[] queueY,
                                           int tail, int gridW, int gridH, int x, int y) {
        if (x < 0 || y < 0 || x >= gridW || y >= gridH) {
            return tail;
        }
        int index = y * gridW + x;
        if (!mask[index] || seen[index]) {
            return tail;
        }
        seen[index] = true;
        queueX[tail] = x;
        queueY[tail] = y;
        return tail + 1;
    }

    private static Text recognize(Bitmap bitmap) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final Text[] result = new Text[1];
        final Exception[] error = new Exception[1];
        RECOGNIZER.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    result[0] = text;
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    error[0] = e;
                    latch.countDown();
                });
        if (!latch.await(8000L, TimeUnit.MILLISECONDS)) {
            return null;
        }
        if (error[0] != null) {
            throw new IllegalStateException(error[0].getMessage(), error[0]);
        }
        return result[0];
    }

    private static Bitmap screenshotBitmap(Context context, HsClient hs) {
        try {
            byte[] bytes = hs.screenshotJpeg();
            if (startsWithErr(bytes)) {
                BotLog.w(context, "image.screenshot.fail", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                return null;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                BotLog.w(context, "image.screenshot.decode.fail", "截图解码失败 bytes=" + bytes.length);
                return null;
            }
            return bitmap;
        } catch (Exception e) {
            BotLog.w(context, "image.screenshot.error", e.getMessage());
            return null;
        }
    }

    private static List<OcrItem> collectItems(Text text) {
        List<OcrItem> items = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            addItem(items, block.getText(), block.getBoundingBox());
            for (Text.Line line : block.getLines()) {
                addItem(items, line.getText(), line.getBoundingBox());
                for (Text.Element element : line.getElements()) {
                    addItem(items, element.getText(), element.getBoundingBox());
                }
            }
        }
        return items;
    }

    private static void addItem(List<OcrItem> items, String text, Rect rect) {
        if (rect == null || normalize(text).isEmpty()) {
            return;
        }
        items.add(new OcrItem(text, rect));
    }

    private static BottomFeature chatBottomFeature(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        ChatToolbarFeature toolbar = detectChatToolbar(bitmap);
        Sample inputStrip = countColorSamples(
                bitmap,
                Math.round(w * 0.14f),
                h - 185,
                Math.round(w * 0.74f),
                h - 95,
                18,
                true);
        Sample leftVoice = countColorSamples(
                bitmap,
                Math.round(w * 0.02f),
                h - 190,
                Math.round(w * 0.10f),
                h - 95,
                8,
                false);
        Sample rightTools = countColorSamples(
                bitmap,
                Math.round(w * 0.78f),
                h - 190,
                Math.round(w * 0.98f),
                h - 95,
                8,
                false);
        int score = 0;
        if (inputStrip.ratio >= 0.65f) {
            score++;
        }
        if (leftVoice.hit >= 4) {
            score++;
        }
        if (rightTools.hit >= 8) {
            score++;
        }
        if (toolbar.score >= 2) {
            score = Math.max(score, 3);
        }
        return new BottomFeature(score, inputStrip.ratio, leftVoice.hit, rightTools.hit,
                toolbar.score, toolbar.voiceIconHit, toolbar.rightIconHit);
    }

    private static Sample countColorSamples(Bitmap bitmap, int left, int top, int right, int bottom, int step, boolean bright) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int l = clamp(left, 0, w - 1);
        int t = clamp(top, 0, h - 1);
        int r = clamp(right, 0, w - 1);
        int b = clamp(bottom, 0, h - 1);
        int total = 0;
        int hit = 0;
        for (int y = t; y <= b; y += step) {
            for (int x = l; x <= r; x += step) {
                total++;
                int color = bitmap.getPixel(x, y);
                if (bright ? isBrightColor(color) : isDarkColor(color)) {
                    hit++;
                }
            }
        }
        return new Sample(total, hit);
    }

    private static boolean isBrightColor(int color) {
        return ((color >> 16) & 0xff) >= 235
                && ((color >> 8) & 0xff) >= 235
                && (color & 0xff) >= 235;
    }

    private static Rect findTextInputBlock(Bitmap bitmap) {
        Rect bottom = scanInputRange(bitmap, 0.72f, 0.96f);
        if (bottom != null) {
            return bottom;
        }
        Rect wide = scanInputRange(bitmap, 0.55f, 0.96f);
        if (wide != null) {
            return wide;
        }
        return detectChatToolbar(bitmap).inputRect;
    }

    private static ChatToolbarFeature detectChatToolbar(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        List<DarkBlock> blocks = findBottomDarkBlocks(bitmap);
        DarkBlock voice = null;
        DarkBlock right = null;
        for (DarkBlock block : blocks) {
            if (!isLikelyBottomToolbarIcon(block, width, height)) {
                continue;
            }
            if (block.rect.centerX() <= Math.round(width * 0.16f)) {
                if (voice == null || block.samples > voice.samples) {
                    voice = block;
                }
            } else if (block.rect.centerX() >= Math.round(width * 0.76f)) {
                if (right == null || block.rect.centerX() < right.rect.centerX()) {
                    right = block;
                }
            }
        }
        Rect input = null;
        if (voice != null || right != null) {
            int left = voice == null ? Math.round(width * 0.12f) : voice.rect.right + Math.round(width * 0.025f);
            int rightEdge = right == null ? Math.round(width * 0.78f) : right.rect.left - Math.round(width * 0.018f);
            int centerY = voice != null ? voice.rect.centerY() : right.rect.centerY();
            int halfHeight = Math.max(32, Math.round(height * 0.028f));
            int top = centerY - halfHeight;
            int bottom = centerY + halfHeight;
            if (rightEdge - left >= Math.round(width * 0.25f)) {
                input = new Rect(
                        clamp(left, 0, width - 2),
                        clamp(top, 0, height - 2),
                        clamp(rightEdge, 1, width - 1),
                        clamp(bottom, 1, height - 1));
            }
        }
        int score = 0;
        if (voice != null) score++;
        if (right != null) score++;
        if (input != null) score++;
        return new ChatToolbarFeature(input, score,
                voice == null ? 0 : voice.samples,
                right == null ? 0 : right.samples);
    }

    private static List<DarkBlock> findBottomDarkBlocks(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(2, width / 430);
        return findDarkBlocks(bitmap, 0, width - 1, Math.round(height * 0.86f), height - 1, step);
    }

    private static List<DarkBlock> findDarkBlocks(Bitmap bitmap, int minX, int maxX, int minY, int maxY, int step) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        minX = clamp(minX, 0, width - 1);
        maxX = clamp(maxX, minX, width - 1);
        minY = clamp(minY, 0, height - 1);
        maxY = clamp(maxY, minY, height - 1);
        int gridW = Math.max(1, ((maxX - minX) / step) + 1);
        int gridH = Math.max(1, ((maxY - minY) / step) + 1);
        boolean[] mask = new boolean[gridW * gridH];
        boolean[] seen = new boolean[gridW * gridH];
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                mask[gy * gridW + gx] = isDarkColor(bitmap.getPixel(
                        clamp(minX + gx * step, 0, width - 1),
                        clamp(minY + gy * step, 0, height - 1)));
            }
        }
        List<DarkBlock> blocks = new ArrayList<>();
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int start = gy * gridW + gx;
                if (!mask[start] || seen[start]) {
                    continue;
                }
                int[] queueX = new int[gridW * gridH];
                int[] queueY = new int[gridW * gridH];
                int head = 0;
                int tail = 0;
                queueX[tail] = gx;
                queueY[tail] = gy;
                tail++;
                seen[start] = true;
                int minGx = gx;
                int maxGx = gx;
                int minGy = gy;
                int maxGy = gy;
                int count = 0;
                while (head < tail) {
                    int cx = queueX[head];
                    int cy = queueY[head];
                    head++;
                    count++;
                    minGx = Math.min(minGx, cx);
                    maxGx = Math.max(maxGx, cx);
                    minGy = Math.min(minGy, cy);
                    maxGy = Math.max(maxGy, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx + 1, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx - 1, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy + 1);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy - 1);
                }
                Rect rect = new Rect(
                        minX + minGx * step,
                        minY + minGy * step,
                        minX + maxGx * step + step,
                        minY + maxGy * step + step);
                blocks.add(new DarkBlock(rect, count));
            }
        }
        return blocks;
    }

    private static boolean isLikelyBottomToolbarIcon(DarkBlock block, int width, int height) {
        int blockWidth = block.rect.width();
        int blockHeight = block.rect.height();
        int centerY = block.rect.centerY();
        return block.samples >= 45
                && blockWidth >= Math.round(width * 0.025f)
                && blockWidth <= Math.round(width * 0.12f)
                && blockHeight >= Math.round(width * 0.025f)
                && blockHeight <= Math.round(width * 0.12f)
                && centerY >= Math.round(height * 0.90f)
                && centerY <= Math.round(height * 0.99f);
    }

    private static Rect scanInputRange(Bitmap bitmap, float startRatio, float endRatio) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(2, width / 180);
        int stepY = Math.max(2, height / 360);
        int minRun = Math.max(40, Math.round(width * 0.18f));
        int maxRun = Math.round(width * 0.92f);
        int edgeGap = stepX * 3;
        Rect best = null;
        int bestScore = Integer.MIN_VALUE;
        int startY = clamp(Math.round(height * startRatio), 0, height - 1);
        int endY = clamp(Math.round(height * endRatio), 0, height - 1);
        for (int y = startY; y < endY; y += stepY) {
            int runStart = -1;
            int runEnd = -1;
            for (int x = 0; x < width; x += stepX) {
                boolean white = isInputWhiteColor(bitmap.getPixel(x, y));
                if (white) {
                    if (runStart < 0) {
                        runStart = x;
                    }
                    runEnd = x;
                    continue;
                }
                if (runStart >= 0) {
                    int score = inputRunScore(width, edgeGap, minRun, maxRun, runStart, runEnd, y);
                    if (score > bestScore) {
                        bestScore = score;
                        best = inputRunRect(runStart, runEnd, y, stepY);
                    }
                    runStart = -1;
                    runEnd = -1;
                }
            }
            if (runStart >= 0) {
                int score = inputRunScore(width, edgeGap, minRun, maxRun, runStart, runEnd, y);
                if (score > bestScore) {
                    bestScore = score;
                    best = inputRunRect(runStart, runEnd, y, stepY);
                }
            }
        }
        return best;
    }

    private static int inputRunScore(int width, int edgeGap, int minRun, int maxRun, int runStart, int runEnd, int y) {
        int runWidth = runEnd - runStart;
        if (runStart <= edgeGap || runEnd >= width - edgeGap || runWidth < minRun || runWidth > maxRun) {
            return Integer.MIN_VALUE;
        }
        return runWidth * 4 + y;
    }

    private static Rect inputRunRect(int runStart, int runEnd, int y, int stepY) {
        int halfHeight = Math.max(18, stepY * 4);
        return new Rect(runStart, y - halfHeight, runEnd, y + halfHeight);
    }

    private static boolean isInputWhiteColor(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return r >= 238 && g >= 238 && b >= 238 && max - min <= 18;
    }

    private static InputModeVisualFeature analyzeInputModeVisual(Bitmap bitmap) {
        Ratio inputCenter = sampleRatios(bitmap, 0.24f, 0.895f, 0.62f, 0.935f, 2);
        Ratio inputFull = sampleRatios(bitmap, 0.13f, 0.885f, 0.78f, 0.945f, 2);
        Ratio keyboardArea = sampleRatios(bitmap, 0.00f, 0.69f, 1.00f, 0.965f, 4);
        boolean keyboardLikely = keyboardArea.darkRatio >= 0.09f && keyboardArea.brightRatio <= 0.65f
                || inputFull.darkRatio >= 0.04f && inputFull.brightRatio <= 0.60f;
        boolean voiceBarLikely = !keyboardLikely
                && inputCenter.darkRatio >= 0.018f
                && inputCenter.brightRatio >= 0.86f
                && inputFull.brightRatio >= 0.92f;
        return new InputModeVisualFeature(
                voiceBarLikely,
                keyboardLikely,
                inputCenter.darkRatio,
                inputCenter.brightRatio,
                keyboardArea.darkRatio,
                keyboardArea.brightRatio);
    }

    private static Ratio sampleRatios(Bitmap bitmap, float leftRatio, float topRatio,
                                      float rightRatio, float bottomRatio, int step) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = clamp(Math.round(width * leftRatio), 0, width - 1);
        int top = clamp(Math.round(height * topRatio), 0, height - 1);
        int right = clamp(Math.round(width * rightRatio), left + 1, width);
        int bottom = clamp(Math.round(height * bottomRatio), top + 1, height);
        int total = 0;
        int dark = 0;
        int bright = 0;
        int stride = Math.max(1, step);
        for (int y = top; y < bottom; y += stride) {
            for (int x = left; x < right; x += stride) {
                int color = bitmap.getPixel(x, y);
                total++;
                if (isDarkColor(color)) {
                    dark++;
                }
                if (isBrightColor(color)) {
                    bright++;
                }
            }
        }
        return new Ratio(total, dark, bright);
    }

    private static boolean isInputMethodVisible(HsClient hs) {
        try {
            String inputMethod = hs.shell("dumpsys", "input_method");
            if (containsVisibleImeFlag(inputMethod)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            String window = hs.shell("dumpsys", "window");
            return containsVisibleImeFlag(window);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean containsVisibleImeFlag(String dump) {
        if (dump == null || dump.isEmpty()) {
            return false;
        }
        return dump.contains("mInputShown=true")
                || dump.contains("mImeWindowVis=0x1")
                || dump.contains("mImeWindowVis=0x3")
                || dump.contains("type=ime") && dump.contains("visible=true")
                || dump.contains("mViewVisibility=0") && dump.contains("InputMethod");
    }

    private static Rect findGreenSendBlock(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = 4;
        int minX = Math.round(width * 0.72f);
        int maxX = width - 1;
        int minY = Math.round(height * 0.45f);
        int maxY = Math.max(minY, height - 70);
        int gridW = Math.max(1, ((maxX - minX) / step) + 1);
        int gridH = Math.max(1, ((maxY - minY) / step) + 1);
        boolean[] mask = new boolean[gridW * gridH];
        boolean[] seen = new boolean[gridW * gridH];
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                mask[gy * gridW + gx] = isWechatSendGreen(bitmap.getPixel(minX + gx * step, minY + gy * step));
            }
        }
        Rect best = null;
        int bestSamples = -1;
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int start = gy * gridW + gx;
                if (!mask[start] || seen[start]) {
                    continue;
                }
                int[] queueX = new int[gridW * gridH];
                int[] queueY = new int[gridW * gridH];
                int head = 0;
                int tail = 0;
                queueX[tail] = gx;
                queueY[tail] = gy;
                tail++;
                seen[start] = true;
                int minGx = gx;
                int maxGx = gx;
                int minGy = gy;
                int maxGy = gy;
                int count = 0;
                while (head < tail) {
                    int cx = queueX[head];
                    int cy = queueY[head];
                    head++;
                    count++;
                    minGx = Math.min(minGx, cx);
                    maxGx = Math.max(maxGx, cx);
                    minGy = Math.min(minGy, cy);
                    maxGy = Math.max(maxGy, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx + 1, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx - 1, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy + 1);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy - 1);
                }
                Rect rect = new Rect(
                        minX + minGx * step,
                        minY + minGy * step,
                        minX + maxGx * step + step,
                        minY + maxGy * step + step);
                int blockWidth = rect.width();
                int blockHeight = rect.height();
                if (count >= 18
                        && blockWidth >= 36 && blockWidth <= 140
                        && blockHeight >= 24 && blockHeight <= 90
                        && rect.centerX() >= width - 120
                        && (best == null || rect.centerX() > best.centerX()
                        || (rect.centerX() == best.centerX() && rect.centerY() > best.centerY())
                        || (rect.centerX() == best.centerX() && rect.centerY() == best.centerY() && count > bestSamples))) {
                    best = rect;
                    bestSamples = count;
                }
            }
        }
        return best;
    }

    private static Rect findShareConfirmGreenSendBlock(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = 4;
        int minX = Math.round(width * 0.30f);
        int maxX = Math.round(width * 0.86f);
        int minY = Math.round(height * 0.72f);
        int maxY = Math.round(height * 0.92f);
        int gridW = Math.max(1, ((maxX - minX) / step) + 1);
        int gridH = Math.max(1, ((maxY - minY) / step) + 1);
        boolean[] mask = new boolean[gridW * gridH];
        boolean[] seen = new boolean[gridW * gridH];
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                mask[gy * gridW + gx] = isWechatSendGreen(bitmap.getPixel(minX + gx * step, minY + gy * step));
            }
        }
        Rect best = null;
        int bestSamples = -1;
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int start = gy * gridW + gx;
                if (!mask[start] || seen[start]) {
                    continue;
                }
                int[] queueX = new int[gridW * gridH];
                int[] queueY = new int[gridW * gridH];
                int head = 0;
                int tail = 0;
                queueX[tail] = gx;
                queueY[tail] = gy;
                tail++;
                seen[start] = true;
                int minGx = gx;
                int maxGx = gx;
                int minGy = gy;
                int maxGy = gy;
                int count = 0;
                while (head < tail) {
                    int cx = queueX[head];
                    int cy = queueY[head];
                    head++;
                    count++;
                    minGx = Math.min(minGx, cx);
                    maxGx = Math.max(maxGx, cx);
                    minGy = Math.min(minGy, cy);
                    maxGy = Math.max(maxGy, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx + 1, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx - 1, cy);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy + 1);
                    tail = enqueueGreenNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy - 1);
                }
                Rect rect = new Rect(
                        minX + minGx * step,
                        minY + minGy * step,
                        minX + maxGx * step + step,
                        minY + maxGy * step + step);
                int blockWidth = rect.width();
                int blockHeight = rect.height();
                if (count >= 55
                        && blockWidth >= 80 && blockWidth <= 260
                        && blockHeight >= 36 && blockHeight <= 120
                        && rect.centerY() >= Math.round(height * 0.78f)
                        && (best == null || count > bestSamples
                        || (count == bestSamples && rect.centerX() > best.centerX()))) {
                    best = rect;
                    bestSamples = count;
                }
            }
        }
        return best;
    }

    private static int enqueueGreenNeighbor(boolean[] mask, boolean[] seen, int[] queueX, int[] queueY,
                                            int tail, int gridW, int gridH, int x, int y) {
        if (x < 0 || y < 0 || x >= gridW || y >= gridH) {
            return tail;
        }
        int index = y * gridW + x;
        if (!mask[index] || seen[index]) {
            return tail;
        }
        seen[index] = true;
        queueX[tail] = x;
        queueY[tail] = y;
        return tail + 1;
    }

    private static boolean isWechatSendGreen(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return g >= 120 && g <= 245
                && r >= 0 && r <= 110
                && b >= 0 && b <= 180
                && g - r >= 45
                && g - b >= 25;
    }

    private static boolean isDarkColor(int color) {
        return ((color >> 16) & 0xff) <= 90
                && ((color >> 8) & 0xff) <= 90
                && (color & 0xff) <= 90;
    }

    private static Rect findPressTalkTextRect(List<OcrItem> items, int width, int height) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        int minY = Math.round(height * 0.48f);
        int maxY = Math.round(height * 0.98f);
        boolean hasPress = false;
        boolean hasTalk = false;
        Rect union = null;
        for (OcrItem item : items) {
            if (item.centerY < minY || item.centerY > maxY) {
                continue;
            }
            if (item.centerX < width * 0.10f || item.centerX > width * 0.90f) {
                continue;
            }
            String value = item.clean;
            if (value.contains("按住说话") || value.contains("按住說話")) {
                return expandRect(item.rect, Math.round(width * 0.05f), Math.round(height * 0.012f), width, height);
            }
            if (value.contains("按住")) {
                hasPress = true;
                union = unionRect(union, item.rect);
            }
            if (value.contains("说话") || value.contains("說話")) {
                hasTalk = true;
                union = unionRect(union, item.rect);
            }
            if (hasPress && hasTalk && union != null) {
                return expandRect(union, Math.round(width * 0.05f), Math.round(height * 0.012f), width, height);
            }
        }
        return null;
    }

    private static Rect unionRect(Rect current, Rect next) {
        if (next == null) {
            return current == null ? null : new Rect(current);
        }
        if (current == null) {
            return new Rect(next);
        }
        Rect rect = new Rect(current);
        rect.union(next);
        return rect;
    }

    private static IconShapeFeature analyzeInputModeIcon(Bitmap bitmap, Rect input) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean reliableInput = input != null && input.width() >= Math.round(width * 0.22f);
        Rect detected = findInputModeToggleBlock(bitmap, reliableInput ? input : null, !reliableInput);
        int centerY = reliableInput ? input.centerY() : Math.round(height * 0.62f);
        int right = reliableInput
                ? Math.max(Math.round(width * 0.07f), input.left - Math.round(width * 0.012f))
                : Math.round(width * 0.18f);
        int left = Math.max(0, Math.round(width * 0.004f));
        right = clamp(right, left + 24, Math.round(width * 0.22f));
        int halfHeight = Math.max(34, Math.round(height * 0.035f));
        int top = clamp(centerY - halfHeight, 0, height - 2);
        int bottom = clamp(centerY + halfHeight, top + 16, height - 1);
        Rect region = detected == null
                ? new Rect(left, top, right, bottom)
                : expandRect(detected, Math.round(width * 0.018f), Math.round(height * 0.018f), width, height);
        int regionWidth = Math.max(1, region.width());
        int regionHeight = Math.max(1, region.height());
        int[] rowHits = new int[regionHeight];
        int[] colHits = new int[regionWidth];
        int dark = 0;
        for (int y = region.top; y < region.bottom; y++) {
            for (int x = region.left; x < region.right; x++) {
                if (isDarkColor(bitmap.getPixel(x, y))) {
                    dark++;
                    rowHits[y - region.top]++;
                    colHits[x - region.left]++;
                }
            }
        }
        int denseRowThreshold = Math.max(4, Math.round(regionWidth * 0.11f));
        int denseColThreshold = Math.max(4, Math.round(regionHeight * 0.11f));
        int denseRows = 0;
        int denseCols = 0;
        for (int hit : rowHits) {
            if (hit >= denseRowThreshold) {
                denseRows++;
            }
        }
        for (int hit : colHits) {
            if (hit >= denseColThreshold) {
                denseCols++;
            }
        }
        int keyboardScore = denseCols + Math.max(0, denseCols - denseRows) * 2;
        int voiceScore = denseRows + Math.max(0, denseRows - denseCols) * 2;
        boolean keyboardLikely = dark >= 80 && keyboardScore >= voiceScore + 6;
        boolean voiceLikely = dark >= 80 && voiceScore >= keyboardScore + 6;
        return new IconShapeFeature(region, keyboardScore, voiceScore, keyboardLikely, voiceLikely);
    }

    private static Rect findInputModeToggleBlock(Bitmap bitmap, Rect input, boolean preferRaised) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(2, width / 430);
        int minY;
        int maxY;
        int preferredY;
        if (input != null) {
            preferredY = input.centerY();
            minY = input.top - Math.round(height * 0.16f);
            maxY = input.bottom + Math.round(height * 0.16f);
        } else {
            preferredY = Math.round(height * (preferRaised ? 0.64f : 0.88f));
            minY = Math.round(height * 0.48f);
            maxY = Math.round(height * (preferRaised ? 0.86f : 0.97f));
        }
        List<DarkBlock> blocks = findDarkBlocks(bitmap,
                0,
                Math.round(width * 0.24f),
                minY,
                maxY,
                step);
        DarkBlock best = null;
        int bestScore = Integer.MIN_VALUE;
        for (DarkBlock block : blocks) {
            if (!isLikelyInputModeToggle(block, width, height)) {
                continue;
            }
            int score = block.samples * 8
                    - Math.abs(block.rect.centerY() - preferredY) * 2
                    - Math.abs(block.rect.centerX() - Math.round(width * 0.055f))
                    - Math.abs(block.rect.width() - block.rect.height()) * 3;
            if (score > bestScore) {
                best = block;
                bestScore = score;
            }
        }
        return best == null ? null : best.rect;
    }

    private static boolean isLikelyInputModeToggle(DarkBlock block, int width, int height) {
        int blockWidth = block.rect.width();
        int blockHeight = block.rect.height();
        return block.samples >= 28
                && block.rect.centerX() >= 0
                && block.rect.centerX() <= Math.round(width * 0.18f)
                && block.rect.centerY() >= Math.round(height * 0.48f)
                && blockWidth >= Math.round(width * 0.018f)
                && blockWidth <= Math.round(width * 0.18f)
                && blockHeight >= Math.round(width * 0.018f)
                && blockHeight <= Math.round(width * 0.18f);
    }

    private static Rect expandRect(Rect rect, int dx, int dy, int width, int height) {
        return new Rect(
                clamp(rect.left - dx, 0, width - 2),
                clamp(rect.top - dy, 0, height - 2),
                clamp(rect.right + dx, 1, width - 1),
                clamp(rect.bottom + dy, 1, height - 1));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").replace("：", ":");
    }

    private static String snippets(List<OcrItem> items) {
        StringBuilder builder = new StringBuilder();
        for (OcrItem item : items) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(item.clean);
            if (builder.length() > 160) {
                break;
            }
        }
        String value = builder.toString();
        if (value.length() > 120) {
            return value.substring(0, 120);
        }
        return value;
    }

    private static String ratio(float value) {
        return String.valueOf(Math.round(value * 1000f) / 1000f);
    }

    private static boolean startsWithErr(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 'E'
                && bytes[1] == 'R'
                && bytes[2] == 'R'
                && bytes[3] == ':';
    }

    private static final class ChatToolbarFeature {
        final Rect inputRect;
        final int score;
        final int voiceIconHit;
        final int rightIconHit;

        ChatToolbarFeature(Rect inputRect, int score, int voiceIconHit, int rightIconHit) {
            this.inputRect = inputRect;
            this.score = score;
            this.voiceIconHit = voiceIconHit;
            this.rightIconHit = rightIconHit;
        }
    }

    private static final class DarkBlock {
        final Rect rect;
        final int samples;

        DarkBlock(Rect rect, int samples) {
            this.rect = rect;
            this.samples = samples;
        }
    }

    private static final class Sample {
        final int hit;
        final float ratio;

        Sample(int total, int hit) {
            this.hit = hit;
            this.ratio = total == 0 ? 0f : (float) hit / (float) total;
        }
    }

    private static final class Ratio {
        final float darkRatio;
        final float brightRatio;

        Ratio(int total, int dark, int bright) {
            this.darkRatio = total == 0 ? 0f : (float) dark / (float) total;
            this.brightRatio = total == 0 ? 0f : (float) bright / (float) total;
        }
    }

    private static final class InputModeVisualFeature {
        final boolean voiceBarLikely;
        final boolean keyboardLikely;
        final float inputCenterDarkRatio;
        final float inputCenterBrightRatio;
        final float keyboardAreaDarkRatio;
        final float keyboardAreaBrightRatio;

        InputModeVisualFeature(boolean voiceBarLikely, boolean keyboardLikely,
                               float inputCenterDarkRatio, float inputCenterBrightRatio,
                               float keyboardAreaDarkRatio, float keyboardAreaBrightRatio) {
            this.voiceBarLikely = voiceBarLikely;
            this.keyboardLikely = keyboardLikely;
            this.inputCenterDarkRatio = inputCenterDarkRatio;
            this.inputCenterBrightRatio = inputCenterBrightRatio;
            this.keyboardAreaDarkRatio = keyboardAreaDarkRatio;
            this.keyboardAreaBrightRatio = keyboardAreaBrightRatio;
        }
    }

    private static final class IconShapeFeature {
        final Rect region;
        final int keyboardScore;
        final int voiceScore;
        final boolean keyboardLikely;
        final boolean voiceLikely;

        IconShapeFeature(Rect region, int keyboardScore, int voiceScore,
                         boolean keyboardLikely, boolean voiceLikely) {
            this.region = region == null ? null : new Rect(region);
            this.keyboardScore = keyboardScore;
            this.voiceScore = voiceScore;
            this.keyboardLikely = keyboardLikely;
            this.voiceLikely = voiceLikely;
        }
    }
}
