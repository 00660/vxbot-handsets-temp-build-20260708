package com.vxbot.wechatbot;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class CodexForegroundWatcher {
    static final String NOTIFICATION_KEY_PREFIX = "codex-foreground-ocr:";

    private static final int MAX_SEEN_SIGNATURES = 160;
    private static final int MAX_NOT_CHAT_COUNT = 3;
    private static final long RECENT_TEXT_SUPPRESS_MS = 120_000L;

    private final Object lock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private WatchTarget target;

    interface Callback {
        boolean isOperationActive();

        boolean isTargetAllowed(BotConfig config, String sessionName, String senderName);

        void onMessage(WxMessage message);
    }

    void start(Context context, BotConfig config, String sessionName, String senderName, Callback callback) {
        if (context == null || config == null || callback == null) {
            return;
        }
        String session = clean(sessionName);
        String sender = clean(senderName);
        if (session.isEmpty() || sender.isEmpty() || !config.stayInCodexSession) {
            return;
        }
        synchronized (lock) {
            if (target != null && target.same(session, sender) && task != null && !task.isDone()) {
                BotLog.i(context, "codex.foreground.watch.keep", "Codex 前台 OCR 已在运行 sessionName="
                        + session + " sender=" + sender);
                return;
            }
            stopLocked();
            WatchTarget next = new WatchTarget(session, sender);
            target = next;
            Context appContext = context.getApplicationContext();
            task = executor.submit(() -> loop(appContext, next, callback));
        }
    }

    void stop(Context context, String reason) {
        synchronized (lock) {
            if (target != null && context != null) {
                BotLog.i(context, "codex.foreground.watch.stop.request", "停止 Codex 前台 OCR reason="
                        + reason + " sessionName=" + target.sessionName + " sender=" + target.senderName);
            }
            stopLocked();
        }
    }

    void shutdown(Context context) {
        stop(context, "service-destroy");
        executor.shutdownNow();
    }

    private void stopLocked() {
        if (target != null) {
            target.stopRequested = true;
        }
        if (task != null) {
            task.cancel(true);
        }
        task = null;
        target = null;
    }

    private void loop(Context context, WatchTarget watch, Callback callback) {
        BotLog.i(context, "codex.foreground.watch.start", "开始 Codex 前台 OCR sessionName="
                + watch.sessionName + " sender=" + watch.senderName);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Map<String, Long> recentTexts = new HashMap<>();
        boolean baselineCaptured = false;
        int notChatCount = 0;
        try {
            while (!watch.stopRequested) {
                BotConfig config = BotConfig.load(context);
                if (!config.stayInCodexSession) {
                    BotLog.i(context, "codex.foreground.watch.stop", "配置已关闭 Codex 会话保持前台");
                    return;
                }
                if (!callback.isTargetAllowed(config, watch.sessionName, watch.senderName)) {
                    BotLog.i(context, "codex.foreground.watch.stop", "Codex 绑定群或授权人已失效 sessionName="
                            + watch.sessionName + " sender=" + watch.senderName);
                    return;
                }
                if (callback.isOperationActive()) {
                    sleepPoll(config);
                    continue;
                }
                WechatDriver driver = new WechatDriver(config.hsPort);
                OcrHelper.Screen screen = driver.inspectCurrentCodexScreen(context, "codex_foreground_poll", watch.sessionName);
                if (screen == null) {
                    notChatCount++;
                    if (notChatCount >= MAX_NOT_CHAT_COUNT) {
                        BotLog.w(context, "codex.foreground.watch.stop",
                                "连续未确认微信会话页，停止 Codex 前台 OCR sessionName=" + watch.sessionName
                                        + " sender=" + watch.senderName + " missCount=" + notChatCount);
                        return;
                    }
                    sleepPoll(config);
                    continue;
                }
                notChatCount = 0;
                List<Candidate> candidates = extractAuthorizedIncoming(screen, watch.senderName);
                if (!baselineCaptured) {
                    long baselineAt = System.currentTimeMillis();
                    for (Candidate candidate : candidates) {
                        addSeen(seen, candidate.signature);
                        recentTexts.put(candidate.textKey, baselineAt);
                    }
                    baselineCaptured = true;
                    BotLog.i(context, "codex.foreground.ocr.baseline", "已记录当前会话 OCR 基线 sessionName="
                            + watch.sessionName + " sender=" + watch.senderName
                            + " count=" + candidates.size());
                    sleepPoll(config);
                    continue;
                }
                pruneRecentTexts(recentTexts);
                Candidate next = newestUnhandled(candidates, seen, recentTexts);
                if (next != null) {
                    long now = System.currentTimeMillis();
                    addSeen(seen, next.signature);
                    recentTexts.put(next.textKey, now);
                    WxMessage message = new WxMessage(
                            watch.sessionName,
                            watch.senderName,
                            next.text,
                            watch.sessionName,
                            watch.senderName + ": " + next.text,
                            NOTIFICATION_KEY_PREFIX + now + ":" + Integer.toHexString(next.signature.hashCode()),
                            now,
                            null);
                    BotLog.i(context, "codex.foreground.ocr.new_message",
                            "OCR 捕获 Codex 前台新消息 sessionName=" + watch.sessionName
                                    + " sender=" + watch.senderName
                                    + " rect=" + next.rect.flattenToString()
                                    + " text=" + next.text);
                    callback.onMessage(message);
                }
                sleepPoll(config);
            }
        } finally {
            BotLog.i(context, "codex.foreground.watch.exit", "Codex 前台 OCR 退出 sessionName="
                    + watch.sessionName + " sender=" + watch.senderName);
            synchronized (lock) {
                if (target == watch) {
                    task = null;
                    target = null;
                }
            }
        }
    }

    private static List<Candidate> extractAuthorizedIncoming(OcrHelper.Screen screen, String senderName) {
        if (screen == null || screen.items.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrHelper.OcrItem> items = new ArrayList<>(screen.items);
        items.sort((left, right) -> {
            int byTop = Integer.compare(left.rect.top, right.rect.top);
            return byTop != 0 ? byTop : Integer.compare(left.rect.left, right.rect.left);
        });
        List<Candidate> leftBubbles = extractLeftBubbleIncoming(screen, items, senderName);
        if (!leftBubbles.isEmpty()) {
            return leftBubbles;
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            OcrHelper.OcrItem label = items.get(i);
            if (!isAuthorizedSenderLabel(screen, label, senderName)) {
                continue;
            }
            Candidate candidate = buildCandidateBelowLabel(screen, items, i, senderName);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static List<Candidate> extractLeftBubbleIncoming(OcrHelper.Screen screen, List<OcrHelper.OcrItem> items,
                                                              String senderName) {
        List<Candidate> out = new ArrayList<>();
        for (OcrHelper.OcrItem item : items) {
            if (!isLeftBubbleMessageText(screen, item, senderName)) {
                continue;
            }
            String text = item.text.trim();
            String textKey = NameNormalizer.contentKey(text);
            int yBucket = Math.max(0, item.centerY / 12);
            String signature = textKey + ":" + yBucket + ":" + Math.max(0, item.rect.height() / 8);
            out.add(new Candidate(text, textKey, signature, item.rect));
        }
        return out;
    }

    private static Candidate buildCandidateBelowLabel(OcrHelper.Screen screen, List<OcrHelper.OcrItem> items,
                                                       int labelIndex, String senderName) {
        OcrHelper.OcrItem label = items.get(labelIndex);
        int maxFirstGap = Math.max(70, Math.round(screen.height * 0.075f));
        int maxLineGap = Math.max(42, Math.round(screen.height * 0.045f));
        int maxBlockBottom = label.rect.bottom + Math.max(220, Math.round(screen.height * 0.18f));
        int lastBottom = label.rect.bottom;
        Rect union = null;
        List<String> lines = new ArrayList<>();
        for (int j = labelIndex + 1; j < items.size(); j++) {
            OcrHelper.OcrItem item = items.get(j);
            if (item.rect.top > maxBlockBottom) {
                break;
            }
            if (isAuthorizedSenderLabel(screen, item, senderName) && !lines.isEmpty()) {
                break;
            }
            int gap = item.rect.top - lastBottom;
            if (lines.isEmpty() && gap > maxFirstGap) {
                break;
            }
            if (!lines.isEmpty() && gap > maxLineGap) {
                break;
            }
            if (!isChatMessageText(screen, item, senderName)) {
                continue;
            }
            lines.add(item.text.trim());
            if (union == null) {
                union = new Rect(item.rect);
            } else {
                union.union(item.rect);
            }
            lastBottom = item.rect.bottom;
        }
        String text = joinLines(lines);
        if (text.isEmpty() || union == null) {
            return null;
        }
        String textKey = NameNormalizer.contentKey(text);
        if (textKey.isEmpty()) {
            return null;
        }
        int yBucket = Math.max(0, union.centerY() / 12);
        String signature = textKey + ":" + yBucket + ":" + Math.max(0, union.height() / 8);
        return new Candidate(text, textKey, signature, union);
    }

    private static boolean isAuthorizedSenderLabel(OcrHelper.Screen screen, OcrHelper.OcrItem item, String senderName) {
        if (screen == null || item == null || senderName == null || senderName.trim().isEmpty()) {
            return false;
        }
        if (item.centerY < screen.height * 0.12f || item.centerY > screen.height * 0.82f) {
            return false;
        }
        if (item.centerX > screen.width * 0.62f) {
            return false;
        }
        return NameNormalizer.sameName(item.text, senderName);
    }

    private static boolean isChatMessageText(OcrHelper.Screen screen, OcrHelper.OcrItem item, String senderName) {
        if (screen == null || item == null) {
            return false;
        }
        if (item.centerY < screen.height * 0.13f || item.centerY > screen.height * 0.80f) {
            return false;
        }
        if (item.centerX < screen.width * 0.08f || item.centerX > screen.width * 0.84f) {
            return false;
        }
        String text = item.text == null ? "" : item.text.trim();
        if (text.isEmpty() || NameNormalizer.sameName(text, senderName)) {
            return false;
        }
        String key = NameNormalizer.contentKey(text);
        if (key.isEmpty()) {
            return false;
        }
        return !isWechatChromeText(text, key);
    }

    private static boolean isLeftBubbleMessageText(OcrHelper.Screen screen, OcrHelper.OcrItem item, String senderName) {
        if (!isChatMessageText(screen, item, senderName)) {
            return false;
        }
        if (item.centerX < screen.width * 0.12f || item.centerX > screen.width * 0.68f) {
            return false;
        }
        if (item.rect.left > screen.width * 0.19f) {
            return false;
        }
        if (item.rect.left < screen.width * 0.09f && item.rect.width() < screen.width * 0.16f) {
            return false;
        }
        return true;
    }

    private static boolean isWechatChromeText(String raw, String key) {
        String text = raw == null ? "" : raw.trim();
        return "发送".equals(key)
                || "按住说话".equals(key)
                || "微信".equals(key)
                || "通讯录".equals(key)
                || "发现".equals(key)
                || "我".equals(key)
                || "语音输入".equals(key)
                || "切换到键盘".equals(key)
                || "切换到按住说话".equals(key)
                || "更多".equals(key)
                || "表情".equals(key)
                || "照片".equals(key)
                || "拍摄".equals(key)
                || "视频通话".equals(key)
                || text.matches("\\d{1,2}:\\d{2}")
                || key.matches("(昨天|今天|星期.|周.).*");
    }

    private static String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String text = line == null ? "" : line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(text);
        }
        return builder.toString().trim();
    }

    private static Candidate newestUnhandled(List<Candidate> candidates, LinkedHashSet<String> seen,
                                             Map<String, Long> recentTexts) {
        long now = System.currentTimeMillis();
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Candidate candidate = candidates.get(i);
            if (seen.contains(candidate.signature)) {
                continue;
            }
            Long last = recentTexts.get(candidate.textKey);
            if (last != null && now - last < RECENT_TEXT_SUPPRESS_MS) {
                addSeen(seen, candidate.signature);
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static void addSeen(LinkedHashSet<String> seen, String value) {
        if (seen == null || value == null || value.isEmpty()) {
            return;
        }
        if (seen.add(value) && seen.size() > MAX_SEEN_SIGNATURES) {
            Iterator<String> iterator = seen.iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static void pruneRecentTexts(Map<String, Long> recentTexts) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = recentTexts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > RECENT_TEXT_SUPPRESS_MS) {
                iterator.remove();
            }
        }
    }

    private static void sleepPoll(BotConfig config) {
        long pollMs = config == null ? 600L : Math.max(250L, config.wechatChatOcrPollMs);
        SystemClock.sleep(pollMs);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class WatchTarget {
        final String sessionName;
        final String senderName;
        volatile boolean stopRequested;

        WatchTarget(String sessionName, String senderName) {
            this.sessionName = sessionName;
            this.senderName = senderName;
        }

        boolean same(String sessionName, String senderName) {
            return NameNormalizer.sameName(this.sessionName, sessionName)
                    && NameNormalizer.sameName(this.senderName, senderName);
        }
    }

    private static final class Candidate {
        final String text;
        final String textKey;
        final String signature;
        final Rect rect;

        Candidate(String text, String textKey, String signature, Rect rect) {
            this.text = text;
            this.textKey = textKey;
            this.signature = signature;
            this.rect = new Rect(rect);
        }
    }
}
