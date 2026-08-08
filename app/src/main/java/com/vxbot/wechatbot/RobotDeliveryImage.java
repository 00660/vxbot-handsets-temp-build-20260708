package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 将面板通知渲染为一张本地 PNG，避免机器人向微信高频发送文字。 */
final class RobotDeliveryImage {
    private static final int WIDTH = 1080;
    private static final int OUTER = 64;
    private static final int CARD_RADIUS = 28;
    private static final int CONTENT_WIDTH = WIDTH - OUTER * 2;

    private RobotDeliveryImage() {
    }

    static File create(Context context, String title, String text, String event, String deliveryId) throws Exception {
        Paint titlePaint = paint(50f, Color.rgb(20, 33, 47), true);
        Paint bodyPaint = paint(34f, Color.rgb(67, 84, 101), false);
        Paint labelPaint = paint(25f, Color.rgb(0, 106, 101), true);
        List<String> titleLines = wrap(clean(title, "iBox 实时提醒"), titlePaint, CONTENT_WIDTH - 88, 2);
        List<String> bodyLines = wrap(clean(text, "检测到新的业务事件，请查看面板详情。"), bodyPaint, CONTENT_WIDTH - 88, 12);
        int titleBlock = Math.max(1, titleLines.size()) * 64;
        int bodyBlock = Math.max(1, bodyLines.size()) * 49;
        int bodyBottom = OUTER + 192 + titleBlock + 22 + 62 + bodyBlock;
        int height = Math.max(620, Math.min(1680, bodyBottom + 198));

        Bitmap bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(242, 246, 248));

        Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        shape.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(OUTER, OUTER, WIDTH - OUTER, height - OUTER), CARD_RADIUS, CARD_RADIUS, shape);

        shape.setColor(Color.rgb(0, 128, 120));
        canvas.drawRoundRect(new RectF(OUTER, OUTER, WIDTH - OUTER, OUTER + 18), CARD_RADIUS, CARD_RADIUS, shape);
        canvas.drawRect(OUTER, OUTER + 10, WIDTH - OUTER, OUTER + 18, shape);

        Paint brandPaint = paint(30f, Color.rgb(17, 43, 60), true);
        canvas.drawText("iBox", OUTER + 44, OUTER + 90, brandPaint);
        Paint descriptorPaint = paint(25f, Color.rgb(105, 125, 142), false);
        canvas.drawText("交易与行情提醒", OUTER + 142, OUTER + 90, descriptorPaint);

        String label = eventLabel(event);
        float labelWidth = Math.max(150f, labelPaint.measureText(label) + 54f);
        shape.setColor(Color.rgb(229, 246, 244));
        canvas.drawRoundRect(new RectF(WIDTH - OUTER - labelWidth, OUTER + 47, WIDTH - OUTER - 36, OUTER + 103), 28, 28, shape);
        canvas.drawText(label, WIDTH - OUTER - labelWidth + 27, OUTER + 84, labelPaint);

        int y = OUTER + 192;
        for (String line : titleLines) {
            canvas.drawText(line, OUTER + 44, y, titlePaint);
            y += 64;
        }
        y += 22;
        Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        divider.setColor(Color.rgb(228, 234, 238));
        divider.setStrokeWidth(2f);
        canvas.drawLine(OUTER + 44, y, WIDTH - OUTER - 44, y, divider);
        y += 62;
        for (String line : bodyLines) {
            canvas.drawText(line, OUTER + 44, y, bodyPaint);
            y += 49;
        }

        int footerTop = height - OUTER - 126;
        canvas.drawLine(OUTER + 44, footerTop - 30, WIDTH - OUTER - 44, footerTop - 30, divider);
        Paint footerPaint = paint(25f, Color.rgb(116, 132, 146), false);
        canvas.drawText("北京时间 " + beijingTime(), OUTER + 44, footerTop + 18, footerPaint);
        String id = "投递 " + shortId(deliveryId);
        float idWidth = footerPaint.measureText(id);
        canvas.drawText(id, WIDTH - OUTER - 44 - idWidth, footerTop + 18, footerPaint);
        Paint hintPaint = paint(24f, Color.rgb(0, 117, 108), true);
        canvas.drawText("来自 iBox 面板", OUTER + 44, footerTop + 64, hintPaint);

        File base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) base = context.getFilesDir();
        File directory = new File(base, "robot-deliveries");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("robot_delivery_image_directory_unavailable");
        }
        File file = new File(directory, "ibox_delivery_" + safeFilePart(deliveryId) + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("robot_delivery_image_encode_failed");
            }
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private static Paint paint(float size, int color, boolean bold) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        return paint;
    }

    private static List<String> wrap(String value, Paint paint, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String remaining = value;
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            int newline = remaining.indexOf('\n');
            String segment = newline >= 0 ? remaining.substring(0, newline) : remaining;
            if (segment.isEmpty()) {
                lines.add("");
            } else {
                while (!segment.isEmpty() && lines.size() < maxLines) {
                    int count = paint.breakText(segment, true, maxWidth, null);
                    if (count <= 0) break;
                    lines.add(segment.substring(0, count));
                    segment = segment.substring(count);
                }
            }
            remaining = newline >= 0 ? remaining.substring(newline + 1) : "";
        }
        if (!remaining.isEmpty() && !lines.isEmpty()) {
            int last = lines.size() - 1;
            String clipped = lines.get(last);
            while (!clipped.isEmpty() && paint.measureText(clipped + "...") > maxWidth) {
                clipped = clipped.substring(0, clipped.length() - 1);
            }
            lines.set(last, clipped + "...");
        }
        return lines;
    }

    private static String clean(String value, String fallback) {
        String result = value == null ? "" : value.replaceAll("[\\u0000-\\u001f\\u007f]+", " ").trim();
        return result.isEmpty() ? fallback : result;
    }

    private static String eventLabel(String event) {
        String value = event == null ? "" : event.trim();
        if (value.contains("market_watch")) return "行情提醒";
        if (value.contains("quant")) return "量化交易";
        if (value.contains("first_sale")) return "首发任务";
        if (value.contains("market_trade")) return "交易任务";
        if (value.contains("verification")) return "人工验证";
        if (value.contains("daily")) return "每日汇总";
        return "实时提醒";
    }

    private static String beijingTime() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date());
    }

    private static String shortId(String deliveryId) {
        String value = deliveryId == null ? "" : deliveryId.trim();
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String safeFilePart(String value) {
        String result = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return result.isEmpty() ? String.valueOf(System.currentTimeMillis()) : result;
    }
}
