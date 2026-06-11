package com.vxbot.wechatbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class VoiceReply {
    static final class PreparedVoice {
        final File file;

        PreparedVoice(File file) {
            this.file = file;
        }

        boolean available() {
            return file != null && file.isFile() && file.length() > 44;
        }
    }

    private VoiceReply() {
    }

    static PreparedVoice prepare(Context context, BotConfig config, String text, String reason) {
        File file = TtsCache.prepare(context, config, text, reason, 45000, 1);
        return file == null ? null : new PreparedVoice(file);
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
            TtsCache.cleanup(context, prepared.file, reason);
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
        int leadMs = Math.max(650, config == null ? 800 : config.wechatStepDelayMs);
        int afterToggleMs = Math.max(700, config == null ? 800 : config.wechatStepDelayMs);
        int ttsTimeoutMs = 45000;
        float speechRate = config == null ? BotConfig.DEFAULT_TTS_SPEED : config.ttsSpeed;
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
        boolean usePrepared = prepared != null && prepared.available();
        Intent intent = new Intent(context, VoiceDemoService.class)
                .putExtra("mode", usePrepared ? "pressFile" : "pressTts")
                .putExtra("text", payload)
                .putExtra("path", usePrepared ? prepared.file.getAbsolutePath() : "")
                .putExtra("toggleVoice", true)
                .putExtra("hsPort", config == null ? 9010 : config.hsPort)
                .putExtra("ttsVoice", config == null ? BotConfig.DEFAULT_TTS_VOICE : config.ttsVoice)
                .putExtra("speechRate", speechRate)
                .putExtra("sessionName", sessionName == null ? "" : sessionName)
                .putExtra("leadMs", leadMs)
                .putExtra("afterToggleMs", afterToggleMs)
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
                    + " prepared=" + usePrepared + " text=" + payload);
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
        } finally {
            if (registered) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
            if (usePrepared) {
                cleanupPrepared(context, prepared, reason);
            }
        }
        if (leaveAfter && driver != null) {
            driver.leaveWechatIfForeground(context, reason);
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
}
