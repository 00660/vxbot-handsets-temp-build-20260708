package com.vxbot.wechatbot;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Point;

import com.baidu.paddle.lite.demo.ocr.OCRPredictorNative;
import com.baidu.paddle.lite.demo.ocr.OcrResultModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Offline PP-OCRv2 detector and recognizer backed by Paddle Lite. */
final class PaddleOcrEngine {
    private static final String ASSET_MODELS = "paddleocr/models/ch_PP-OCRv2";
    private static final String ASSET_LABELS = "paddleocr/labels/ppocr_keys_v1.txt";
    private static final Object LOCK = new Object();

    private static PaddleOcrEngine instance;

    static PaddleOcrEngine get(Context context) throws IOException {
        synchronized (LOCK) {
            if (instance == null) {
                instance = new PaddleOcrEngine(context.getApplicationContext());
            }
            return instance;
        }
    }

    static final class Result {
        final String text;
        final android.graphics.Rect rect;
        final float confidence;

        Result(String text, android.graphics.Rect rect, float confidence) {
            this.text = text;
            this.rect = rect;
            this.confidence = confidence;
        }
    }

    private final OCRPredictorNative predictor;
    private final List<String> labels;

    private PaddleOcrEngine(Context context) throws IOException {
        File root = new File(context.getNoBackupFilesDir(), "paddleocr-v2");
        File modelDir = new File(root, "models");
        copyAssets(context.getAssets(), ASSET_MODELS, modelDir);
        labels = readLabels(context.getAssets());

        OCRPredictorNative.Config config = new OCRPredictorNative.Config();
        config.useOpencl = 0;
        config.cpuThreadNum = 4;
        config.cpuPower = "LITE_POWER_HIGH";
        config.detModelFilename = new File(modelDir, "det_db.nb").getAbsolutePath();
        config.recModelFilename = new File(modelDir, "rec_crnn.nb").getAbsolutePath();
        config.clsModelFilename = new File(modelDir, "cls.nb").getAbsolutePath();
        predictor = new OCRPredictorNative(config);
    }

    synchronized List<Result> recognize(Bitmap bitmap) {
        if (bitmap == null) {
            return Collections.emptyList();
        }
        ArrayList<OcrResultModel> raw = predictor.runImage(bitmap, 960, 1, 0, 1);
        List<Result> results = new ArrayList<>(raw.size());
        for (OcrResultModel item : raw) {
            String text = decode(item.getWordIndex()).trim();
            android.graphics.Rect rect = bounds(item.getPoints());
            if (!text.isEmpty() && rect != null && !rect.isEmpty()) {
                results.add(new Result(text, rect, item.getConfidence()));
            }
        }
        return results;
    }

    private String decode(List<Integer> indexes) {
        StringBuilder text = new StringBuilder();
        for (Integer index : indexes) {
            if (index != null && index >= 0 && index < labels.size()) {
                text.append(labels.get(index));
            }
        }
        return text.toString();
    }

    private static android.graphics.Rect bounds(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Point point : points) {
            left = Math.min(left, point.x);
            top = Math.min(top, point.y);
            right = Math.max(right, point.x);
            bottom = Math.max(bottom, point.y);
        }
        return new android.graphics.Rect(left, top, right, bottom);
    }

    private static List<String> readLabels(AssetManager assets) throws IOException {
        List<String> result = new ArrayList<>();
        result.add("");
        try (InputStream in = assets.open(ASSET_LABELS)) {
            String[] lines = new String(readAll(in), StandardCharsets.UTF_8).split("\\r?\\n");
            Collections.addAll(result, lines);
        }
        result.add(" ");
        return Collections.unmodifiableList(result);
    }

    private static void copyAssets(AssetManager assets, String source, File destination) throws IOException {
        String[] children = assets.list(source);
        if (children == null || children.length == 0) {
            copyAssetFile(assets, source, destination);
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("cannot create " + destination);
        }
        for (String child : children) {
            copyAssets(assets, source + "/" + child, new File(destination, child));
        }
    }

    private static void copyAssetFile(AssetManager assets, String source, File destination) throws IOException {
        if (destination.isFile()) {
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (InputStream in = assets.open(source); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
