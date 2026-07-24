package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class VoiceReply {
    private static final int VOICE_SEGMENT_TARGET_MS = 52000;
    private static final int VOICE_SEGMENT_MAX_MS = 55000;
    private static final int VOICE_SEGMENT_RETRY_TARGET_MS = 50000;
    private static final int VOICE_SEGMENT_GAP_MS = 800;
    private static final int PRE_PLAYBACK_PRESS_MS = 500;

    static final class PreparedVoice {
        final List<File> files;
        final List<String> segments;

        PreparedVoice(File file) {
            this(file == null ? Collections.emptyList() : Collections.singletonList(file), Collections.emptyList());
        }

        PreparedVoice(List<File> files, List<String> segments) {
            this.files = files == null ? Collections.emptyList() : files;
            this.segments = segments == null ? Collections.emptyList() : segments;
        }

        boolean available() {
            if (files.isEmpty()) {
                return false;
            }
            for (File file : files) {
                if (file == null || !file.isFile() || file.length() <= 44) {
                    return false;
                }
            }
            return true;
        }

        File fileAt(int index) {
            if (index < 0 || index >= files.size()) {
                return null;
            }
            return files.get(index);
        }
    }

    private VoiceReply() {
    }

    static PreparedVoice prepare(Context context, BotConfig config, String text, String reason) {
        float speechRate = config == null ? BotConfig.DEFAULT_TTS_SPEED : config.ttsSpeed;
        List<String> segments = new ArrayList<>(splitVoiceText(text, speechRate));
        if (segments.isEmpty()) {
            BotLog.e(context, "tts.prepare.abort", "TTS 内容为空 reason=" + reason);
            return null;
        }
        List<File> files = new ArrayList<>();
        for (int i = 0; i < segments.size();) {
            File file = TtsCache.prepare(context, config, segments.get(i), partReason(reason, i, segments.size()), 45000, 1);
            if (file == null || !file.isFile() || file.length() <= 44) {
                for (File used : files) {
                    TtsCache.cleanup(context, used, reason + "-partial-fail");
                }
                return null;
            }
            int durationMs = mediaDurationMs(file);
            String segment = segments.get(i);
            if (durationMs > VOICE_SEGMENT_MAX_MS && segment.length() >= 40) {
                TtsCache.cleanup(context, file, reason + "-oversize");
                int targetChars = Math.max(20, Math.min(segment.length() - 1,
                        Math.round(segment.length() * VOICE_SEGMENT_RETRY_TARGET_MS / (float) durationMs)));
                int cut = findSplitPoint(segment, 0, targetChars);
                if (cut <= 0 || cut >= segment.length()) {
                    cut = targetChars;
                }
                String first = segment.substring(0, cut).trim();
                String second = segment.substring(cut).trim();
                if (first.isEmpty() || second.isEmpty()) {
                    for (File used : files) {
                        TtsCache.cleanup(context, used, reason + "-oversize-split-fail");
                    }
                    return null;
                }
                segments.set(i, first);
                segments.add(i + 1, second);
                BotLog.i(context, "tts.prepare.resplit", "reason=" + reason
                        + " durationMs=" + durationMs + " chars=" + segment.length()
                        + " split=" + first.length() + "+" + second.length());
                continue;
            }
            files.add(file);
            i++;
        }
        return new PreparedVoice(files, segments);
    }

    static PreparedVoice awaitPrepared(Context context, Future<PreparedVoice> future, String reason) {
        if (future == null) {
            return null;
        }
        try {
            PreparedVoice prepared = future.get();
            if (prepared != null && prepared.available()) {
                return prepared;
            }
        } catch (Exception e) {
            BotLog.w(context, "tts.prepare.await.fail", "reason=" + reason + " " + e.getMessage());
        }
        return null;
    }

    static void cleanupPrepared(Context context, PreparedVoice prepared, String reason) {
        if (prepared != null) {
            for (File file : prepared.files) {
                TtsCache.cleanup(context, file, reason);
            }
        }
    }

    static boolean sendInCurrentChat(Context context, BotConfig config, WechatDriver driver,
                                     String text, String reason, boolean leaveAfter) {
        return sendInCurrentChat(context, config, driver, "", text, reason, leaveAfter);
    }

    static boolean sendInCurrentChat(Context context, BotConfig config, WechatDriver driver, String sessionName,
                                     String text, String reason, boolean leaveAfter) {
        return sendPreparedInCurrentChat(context, config, driver, sessionName, null, text, reason, leaveAfter);
    }

    static boolean sendPreparedInCurrentChat(Context context, BotConfig config, WechatDriver driver,
                                             PreparedVoice prepared, String text, String reason, boolean leaveAfter) {
        return sendPreparedInCurrentChat(context, config, driver, "", prepared, text, reason, leaveAfter);
    }

    static boolean sendPreparedInCurrentChat(Context context, BotConfig config, WechatDriver driver,
                                             String sessionName, PreparedVoice prepared,
                                             String text, String reason, boolean leaveAfter) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            BotLog.e(context, "voice.reply.abort", "语音内容为空 reason=" + reason);
            return false;
        }
        float speechRate = config == null ? BotConfig.DEFAULT_TTS_SPEED : config.ttsSpeed;
        List<String> segments = preparedSegments(prepared, payload, speechRate);
        if (segments.isEmpty()) {
            BotLog.e(context, "voice.reply.abort", "分段后语音内容为空 reason=" + reason);
            return false;
        }
        boolean ok = true;
        try {
            for (int i = 0; i < segments.size(); i++) {
                String part = segments.get(i);
                File file = prepared != null && prepared.available() && prepared.files.size() == segments.size()
                        ? prepared.fileAt(i) : null;
                if (!sendSingleSegment(context, config, sessionName, file, part, partReason(reason, i, segments.size()), speechRate)) {
                    ok = false;
                    break;
                }
                if (i + 1 < segments.size()) {
                    SystemClock.sleep(VOICE_SEGMENT_GAP_MS);
                }
            }
        } finally {
            if (prepared != null && prepared.available()) {
                cleanupPrepared(context, prepared, reason);
            }
            if (leaveAfter && driver != null) {
                driver.leaveWechatIfForeground(context, reason);
            }
        }
        return ok;
    }

    private static boolean sendSingleSegment(Context context, BotConfig config, String sessionName,
                                             File preparedFile, String payload, String reason, float speechRate) {
        int leadMs = Math.max(650, config == null ? 800 : config.wechatStepDelayMs);
        int afterToggleMs = Math.max(700, config == null ? 800 : config.wechatStepDelayMs);
        int ttsTimeoutMs = 45000;
        File file = preparedFile;
        if (file == null || !file.isFile() || file.length() <= 44) {
            file = TtsCache.prepare(context, config, payload, reason + "-sync", ttsTimeoutMs, 1);
        }
        boolean usePrepared = file != null && file.isFile() && file.length() > 44;
        String requestId = "voice-" + SystemClock.uptimeMillis();
        CountDownLatch doneLatch = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent doneIntent) {
                String finishedId = doneIntent == null ? "" : doneIntent.getStringExtra(VoiceDemoService.EXTRA_REQUEST_ID);
                if (requestId.equals(finishedId)) {
                    doneLatch.countDown();
                }
            }
        };
        boolean registered = false;
        Intent intent = new Intent(context, VoiceDemoService.class)
                .putExtra("mode", usePrepared ? "pressFile" : "pressTts")
                .putExtra("text", payload)
                .putExtra("path", usePrepared ? file.getAbsolutePath() : "")
                .putExtra("toggleVoice", true)
                .putExtra("hsPort", config == null ? 9010 : config.hsPort)
                .putExtra("ttsVoice", config == null ? BotConfig.DEFAULT_TTS_VOICE : config.ttsVoice)
                .putExtra("speechRate", speechRate)
                .putExtra("sessionName", sessionName == null ? "" : sessionName)
                .putExtra("leadMs", leadMs)
                .putExtra("afterToggleMs", afterToggleMs)
                .putExtra("prePlaybackPressMs", PRE_PLAYBACK_PRESS_MS)
                .putExtra("ttsTimeoutMs", ttsTimeoutMs)
                .putExtra("ttsAttempts", 1)
                .putExtra(VoiceDemoService.EXTRA_REQUEST_ID, requestId);
        try {
            IntentFilter filter = new IntentFilter(VoiceDemoService.ACTION_VOICE_DEMO_FINISH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registered = true;
            BotLog.i(context, "voice.reply.dispatch", "reason=" + reason + " requestId=" + requestId
                    + " prepared=" + usePrepared + " length=" + payload.length());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            long waitMs = leadMs + afterToggleMs + ttsTimeoutMs
                    + estimateTtsPressMs(payload, speechRate) + 7000L;
            if (!doneLatch.await(waitMs, TimeUnit.MILLISECONDS)) {
                BotLog.w(context, "voice.reply.timeout", "requestId=" + requestId + " waitMs=" + waitMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            BotLog.e(context, "voice.reply.interrupted", "requestId=" + requestId);
            return false;
        } finally {
            if (registered) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
            if (usePrepared && file != preparedFile) {
                TtsCache.cleanup(context, file, reason);
            }
        }
        return true;
    }

    private static int estimateTtsPressMs(String text, float speechRate) {
        String value = text == null ? "" : text.replaceAll("\\s+", "");
        int chars = Math.max(1, value.length());
        int baseMs = Math.max(3500, Math.min(60000, 1800 + chars * 260));
        float speed = BotConfig.normalizeTtsSpeed(speechRate);
        return Math.max(2500, Math.min(90000, Math.round(baseMs / speed)));
    }

    private static List<String> preparedSegments(PreparedVoice prepared, String payload, float speechRate) {
        if (prepared != null && prepared.available()
                && prepared.segments != null
                && prepared.segments.size() == prepared.files.size()
                && !prepared.segments.isEmpty()) {
            return prepared.segments;
        }
        return splitVoiceText(payload, speechRate);
    }

    private static List<String> splitVoiceText(String text, float speechRate) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            return Collections.emptyList();
        }
        int maxChars = maxSegmentChars(speechRate);
        if (payload.length() <= maxChars) {
            return Collections.singletonList(payload);
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < payload.length()) {
            int end = Math.min(payload.length(), start + maxChars);
            if (end < payload.length()) {
                int cut = findSplitPoint(payload, start, end);
                if (cut > start + Math.max(20, maxChars / 3)) {
                    end = cut;
                }
            }
            String part = payload.substring(start, end).trim();
            if (!part.isEmpty()) {
                out.add(part);
            }
            start = end;
            while (start < payload.length() && Character.isWhitespace(payload.charAt(start))) {
                start++;
            }
        }
        return out.isEmpty() ? Collections.singletonList(payload) : out;
    }

    private static int maxSegmentChars(float speechRate) {
        float speed = BotConfig.normalizeTtsSpeed(speechRate);
        int max = Math.round((VOICE_SEGMENT_TARGET_MS * speed - 1800) / 260.0f);
        return Math.max(80, Math.min(200, max));
    }

    private static int findSplitPoint(String payload, int start, int end) {
        String separators = "\n。！？!?；;，,、 ";
        for (int i = end - 1; i > start; i--) {
            if (separators.indexOf(payload.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return end;
    }

    private static int mediaDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static String partReason(String reason, int index, int total) {
        if (total <= 1) {
            return reason;
        }
        return reason + "-part" + (index + 1) + "of" + total;
    }
}
