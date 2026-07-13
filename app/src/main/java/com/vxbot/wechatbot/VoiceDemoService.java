package com.vxbot.wechatbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceDemoService extends Service {
    private static final String CHANNEL_ID = "voice_demo";
    private static final int NOTIFICATION_ID = 41;
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_DEVICE_TYPE_REMOTE_SUBMIX = readRemoteSubmixType();
    private static final String DEFAULT_QWEN_TTS_ENDPOINT = "https://qwen3ttsai.com/api/qwen3tts/generate";
    private static final String DEFAULT_QWEN_TTS_VOICE = BotConfig.DEFAULT_TTS_VOICE;
    static final String ACTION_VOICE_DEMO_FINISH = "com.vxbot.wechatbot.action.VOICE_DEMO_FINISH";
    static final String EXTRA_REQUEST_ID = "requestId";
    private static final AtomicBoolean VMIC_RECORD_TEST_RUNNING = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent safeIntent = intent == null ? new Intent() : intent;
        ensureForeground(safeIntent);
        new Thread(() -> {
            try {
                runMode(safeIntent);
            } catch (Exception e) {
                BotLog.e(this, "voice.demo.error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                sendFinishBroadcast(safeIntent);
                stopSelf(startId);
            }
        }, "voice-demo").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runMode(Intent intent) throws Exception {
        String mode = stringExtra(intent, "mode", "tone");
        int delayMs = intExtra(intent, "delayMs", 800);
        BotLog.i(this, "voice.demo.start", "mode=" + mode + " delayMs=" + delayMs);
        if (isPressTtsMode(mode) || "pressFile".equalsIgnoreCase(mode)
                || "pressTone".equalsIgnoreCase(mode)) {
            VmicInjector.resetMtkState(this, "voice-demo-start:" + mode);
        }
        if (mode.startsWith("route")) {
            runRouteMode(mode);
            return;
        }
        if ("probe".equalsIgnoreCase(mode)) {
            probeDevice();
            return;
        }
        if (delayMs > 0) {
            SystemClock.sleep(delayMs);
        }
        if (boolExtra(intent, "toggleVoice", false) && !isPressTtsMode(mode)) {
            if (!ensureVoiceInputMode(intent, "voice-demo-start")) {
                throw new IllegalStateException("WeChat voice input mode not ready");
            }
        }
        if ("pressTone".equalsIgnoreCase(mode)) {
            pressAndPlayTone(intent);
        } else if ("pressFile".equalsIgnoreCase(mode)) {
            pressAndPlayFile(intent);
        } else if ("pressTts".equalsIgnoreCase(mode) || "pressText".equalsIgnoreCase(mode)) {
            pressAndSpeakTts(intent);
        } else if ("vmicTts".equalsIgnoreCase(mode) || "vmicText".equalsIgnoreCase(mode)) {
            injectRemoteTts(intent);
        } else if ("vmicFile".equalsIgnoreCase(mode)) {
            injectFile(intent);
        } else if ("vmicFileRecord".equalsIgnoreCase(mode)) {
            recordInjectedFile(intent);
        } else if ("vmicRecordTest".equalsIgnoreCase(mode)) {
            runVmicRecordTest(intent);
        } else if ("file".equalsIgnoreCase(mode)) {
            playFile(intent, stringExtra(intent, "path", ""));
        } else if ("tts".equalsIgnoreCase(mode) || "text".equalsIgnoreCase(mode)) {
            speakTts(intent, stringExtra(intent, "text", ""));
        } else {
            playTone(intent);
        }
        BotLog.i(this, "voice.demo.done", "mode=" + mode);
    }

    private void injectFile(Intent intent) throws Exception {
        String path = stringExtra(intent, "path", "");
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException("file not found: " + path);
        }
        int durationMs = readMediaDurationMs(file);
        if (!VmicInjector.injectFile(this, file, Math.max(8000, durationMs + 5000), "vmic-file")) {
            throw new IllegalStateException("vmic inject failed");
        }
    }

    private void recordInjectedFile(Intent intent) throws Exception {
        if (!VMIC_RECORD_TEST_RUNNING.compareAndSet(false, true)) {
            BotLog.w(this, "voice.demo.vmic.file.record.skip_busy", "已有虚拟麦录音测试正在运行");
            return;
        }
        SystemRecorderRecordingJob job = null;
        try {
            String path = stringExtra(intent, "path", "");
            File source = new File(path);
            if (!source.isFile()) {
                throw new IllegalArgumentException("file not found: " + path);
            }
            int durationMs = readMediaDurationMs(source);
            int recordRate = 44100;
            File recordFile = newVmicRecordFile();
            job = new SystemRecorderRecordingJob(recordFile, recordRate);
            job.start();
            SystemClock.sleep(500);
            BotLog.i(this, "voice.demo.vmic.file.record.inject",
                    "source=" + source.getAbsolutePath()
                            + " durationMs=" + durationMs
                            + " recordRate=" + recordRate
                            + " channels=2 audioSource=MIC"
                            + " record=" + recordFile.getAbsolutePath());
            boolean injected = VmicInjector.injectFile(this, source,
                    Math.max(8000, durationMs + 5000), "vmic-file-record");
            SystemClock.sleep(1500);
            job.requestStop();
            job.await(5000);
            if (!recordFile.isFile() || recordFile.length() <= 44) {
                throw new IllegalStateException("录音文件为空 injected=" + injected);
            }
            BotLog.i(this, "voice.demo.vmic.file.record.done",
                    "injected=" + injected
                            + " source=" + source.getAbsolutePath()
                            + " record=" + recordFile.getAbsolutePath()
                            + " size=" + recordFile.length());
            playFile(intent, recordFile.getAbsolutePath());
        } finally {
            if (job != null) {
                job.requestStop();
            }
            VMIC_RECORD_TEST_RUNNING.set(false);
        }
    }

    private void pressAndPlayTone(Intent intent) throws Exception {
        int toneMs = intExtra(intent, "toneMs", 3200);
        PressHandle press = null;
        try {
            press = pressDown(intent, "tone");
            if (press == null) {
                throw new IllegalStateException("WeChat voice press point not confirmed");
            }
            playTone(intent);
        } finally {
            delayBeforeRelease(intent, "tone");
            releasePress(press, "tone");
        }
    }

    private void pressAndPlayFile(Intent intent) throws Exception {
        String path = stringExtra(intent, "path", "");
        playFileWithPress(intent, path, "press-file");
    }

    private void injectRemoteTts(Intent intent) throws Exception {
        String text = stringExtra(intent, "text", "");
        File remoteFile = generateRemoteTtsFile(intent, text);
        try {
            if (remoteFile == null || !remoteFile.isFile() || remoteFile.length() <= 44) {
                throw new IllegalStateException("remote TTS file missing");
            }
            int durationMs = readMediaDurationMs(remoteFile);
            BotLog.i(this, "voice.demo.tts.vmic.start", remoteFile.getAbsolutePath()
                    + " size=" + remoteFile.length() + " durationMs=" + durationMs);
            int preInjectMs = Math.max(0, intExtra(intent, "preVmicInjectMs", 0));
            if (preInjectMs > 0) {
                BotLog.i(this, "voice.demo.tts.vmic.delay", "delayMs=" + preInjectMs);
                SystemClock.sleep(preInjectMs);
            }
            if (!VmicInjector.injectFile(this, remoteFile, Math.max(8000, durationMs + 5000), "vmic-tts")) {
                throw new IllegalStateException("vmic inject failed");
            }
            BotLog.i(this, "voice.demo.tts.vmic.done", remoteFile.getAbsolutePath()
                    + " durationMs=" + durationMs);
        } finally {
            cleanupTtsFile(intent, remoteFile);
        }
    }

    private void runVmicRecordTest(Intent intent) throws Exception {
        if (!VMIC_RECORD_TEST_RUNNING.compareAndSet(false, true)) {
            BotLog.w(this, "voice.demo.vmic.record.skip_busy", "已有虚拟麦录音测试正在运行，跳过本次请求");
            return;
        }
        File sourceFile = null;
        SystemRecorderRecordingJob job = null;
        try {
            BotConfig config = BotConfig.load(this);
            sourceFile = TtsCache.prepare(this, config,
                    "这是虚拟麦真实录音测试，当前通道音频应当清晰完整。",
                    "vmic-record-test", 60000, 1);
            if (sourceFile == null || !sourceFile.isFile() || sourceFile.length() <= 44) {
                throw new IllegalStateException("虚拟麦测试 TTS 生成失败");
            }
            int durationMs = readMediaDurationMs(sourceFile);
            int recordRate = 44100;
            File recordFile = newVmicRecordFile();
            job = new SystemRecorderRecordingJob(recordFile, recordRate);
            job.start();
            int warmupMs = Math.max(200, intExtra(intent, "recordWarmupMs", 500));
            SystemClock.sleep(warmupMs);
            BotLog.i(this, "voice.demo.vmic.record.inject",
                    "provider=" + config.ttsProvider
                            + " source=" + sourceFile.getAbsolutePath()
                            + " durationMs=" + durationMs
                            + " recordRate=" + recordRate
                            + " channels=2 audioSource=MIC"
                            + " record=" + recordFile.getAbsolutePath());
            boolean injected = VmicInjector.injectFile(this, sourceFile,
                    Math.max(8000, durationMs + 5000), "vmic-record-test");
            SystemClock.sleep(1500);
            job.requestStop();
            job.await(5000);
            if (!recordFile.isFile() || recordFile.length() <= 44) {
                throw new IllegalStateException("录音文件为空 injected=" + injected);
            }
            BotLog.write(this, injected ? "INFO" : "WARN", "voice.demo.vmic.record.done",
                    "虚拟麦录音测试完成 injected=" + injected
                            + " provider=" + config.ttsProvider
                            + " record=" + recordFile.getAbsolutePath()
                            + " recordRate=" + recordRate
                            + " channels=2 audioSource=MIC"
                            + " size=" + recordFile.length());
            playFile(intent, recordFile.getAbsolutePath());
        } finally {
            if (job != null) {
                job.requestStop();
            }
            if (sourceFile != null && boolExtra(intent, "deleteSourceFile", true)) {
                TtsCache.cleanup(this, sourceFile, "vmic-record-test");
            }
            VMIC_RECORD_TEST_RUNNING.set(false);
        }
    }

    private void pressAndSpeakTts(Intent intent) throws Exception {
        String text = stringExtra(intent, "text", "");
        File remoteFile = generateRemoteTtsFile(intent, text);
        PressHandle press = null;
        try {
            if (boolExtra(intent, "toggleVoice", false)) {
                if (!ensureVoiceInputMode(intent, "press-tts-start")) {
                    throw new IllegalStateException("WeChat voice input mode not ready");
                }
            }
            if (remoteFile != null) {
                playFileWithPress(intent, remoteFile.getAbsolutePath(), "press-tts-file");
                return;
            }
            press = pressDown(intent, "system-tts");
            if (press == null) {
                throw new IllegalStateException("WeChat voice press point not confirmed");
            }
            delayAfterPressBeforePlayback(intent, "system-tts");
            speakSystemTts(intent, text);
        } finally {
            if (press != null) {
                delayBeforeRelease(intent, "system-tts");
                releasePress(press, "system-tts");
            }
            if (remoteFile != null) {
                cleanupTtsFile(intent, remoteFile);
            }
        }
    }

    private boolean isPressTtsMode(String mode) {
        return "pressTts".equalsIgnoreCase(mode) || "pressText".equalsIgnoreCase(mode);
    }

    private void tapVoiceButton(Intent intent) {
        int voiceX = intExtra(intent, "voiceX", 42);
        int voiceY = intExtra(intent, "voiceY", 1395);
        int hsPort = intExtra(intent, "hsPort", 9010);
        try {
            String result = new HsClient(hsPort).tap(voiceX, voiceY);
            BotLog.i(this, "voice.demo.tap.hs", "x=" + voiceX + " y=" + voiceY + " result=" + result);
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.tap.hs.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
            runRootCommand(String.format(Locale.US, "input tap %d %d", voiceX, voiceY));
        }
        SystemClock.sleep(intExtra(intent, "afterToggleMs", 800));
    }

    private boolean ensureVoiceInputMode(Intent intent, String reason) {
        int hsPort = intExtra(intent, "hsPort", 9010);
        String sessionName = stringExtra(intent, "sessionName", "");
        try {
            return new WechatDriver(hsPort).ensureVoiceInputMode(this, BotConfig.load(this), sessionName, reason);
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.mode.voice.fail", reason + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private PressHandle pressDown(Intent intent, String reason) {
        int[] point = resolvePressPoint(intent);
        if (point == null) {
            BotLog.w(this, "voice.demo.press.abort", "reason=" + reason + " 未确认按住/说话区域，取消按下动作");
            return null;
        }
        int pressX = point[0];
        int pressY = point[1];
        int hsPort = intExtra(intent, "hsPort", 9010);
        try {
            String result = new HsClient(hsPort).down(pressX, pressY);
            BotLog.i(this, "voice.demo.press.down", "reason=" + reason
                    + " x=" + pressX + " y=" + pressY + " result=" + result);
            if (result != null && result.startsWith("ERR:")) {
                return null;
            }
            return new PressHandle(pressX, pressY, hsPort);
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.press.down.fail", "reason=" + reason + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void releasePress(PressHandle press, String reason) {
        if (press == null) {
            return;
        }
        try {
            String result = new HsClient(press.hsPort).up(press.x, press.y);
            BotLog.i(this, "voice.demo.press.up", "reason=" + reason
                    + " x=" + press.x + " y=" + press.y + " result=" + result);
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.press.up.fail", "reason=" + reason + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private int[] resolvePressPoint(Intent intent) {
        int hsPort = intExtra(intent, "hsPort", 9010);
        String sessionName = stringExtra(intent, "sessionName", "");
        HsClient client = new HsClient(hsPort);
        WechatDriver driver = new WechatDriver(hsPort);
        try {
            if (!driver.ensureVoiceInputMode(this, BotConfig.load(this), sessionName, "voice-press-realtime")) {
                BotLog.w(this, "voice.demo.press.point.prepare_fail", "准备语音态失败 sessionName=" + sessionName);
                return null;
            }
            OcrHelper.InputModeFeature state = scanRealtimePressPoint(intent, client, sessionName, "voice-press-realtime");
            if (state != null
                    && state.pressTalkTextHit
                    && state.isVoiceModeLikely()
                    && state.inputRect != null) {
                return pressPointFromState(state);
            }
            if (state != null && state.isTextModeLikely()) {
                BotLog.w(this, "voice.demo.mode.cache.stale", "实时识别发现页面是文字态，清输入态缓存后重新切换 sessionName="
                        + sessionName + " " + state.summary());
                driver.clearStoredInputMode(this, sessionName);
                if (driver.ensureVoiceInputMode(this, BotConfig.load(this), sessionName, "voice-press-correct")) {
                    SystemClock.sleep(Math.max(500L, intExtra(intent, "afterToggleMs", 800)));
                    state = scanRealtimePressPoint(intent, client, sessionName, "voice-press-correct");
                    if (state != null
                            && state.pressTalkTextHit
                            && state.isVoiceModeLikely()
                            && state.inputRect != null) {
                        return pressPointFromState(state);
                    }
                }
            }
            BotLog.w(this, "voice.demo.press.point.unconfirmed", "未确认按住/说话区域，不使用兜底坐标 "
                    + (state == null ? "state=null" : state.summary()));
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.press.point.fail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    private OcrHelper.InputModeFeature scanRealtimePressPoint(Intent intent, HsClient client,
                                                              String sessionName, String reason) {
        long waitMs = Math.max(350L, intExtra(intent, "afterToggleMs", 800));
        OcrHelper.InputModeFeature lastState = null;
        for (int i = 1; i <= 4; i++) {
            try {
                OcrHelper.InputModeFeature state = OcrHelper.inspectInputMode(this, client);
                if (state != null) {
                    lastState = state;
                }
                if (state != null
                        && state.pressTalkTextHit
                        && state.isVoiceModeLikely()
                        && state.inputRect != null) {
                    BotLog.i(this, "voice.demo.press.point.realtime.hit", "实时 OCR 命中按住/说话区域 sessionName="
                            + sessionName + " round=" + i + " reason=" + reason + " " + state.summary());
                    return state;
                }
                BotLog.i(this, "voice.demo.press.point.realtime.scan", "实时 OCR 未命中按住/说话区域 sessionName="
                        + sessionName + " round=" + i + " reason=" + reason + " "
                        + (state == null ? "state=null" : state.summary()));
            } catch (Exception e) {
                BotLog.w(this, "voice.demo.press.point.realtime.fail", "round=" + i
                        + " reason=" + reason + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            SystemClock.sleep(waitMs);
        }
        return lastState;
    }

    private int[] pressPointFromState(OcrHelper.InputModeFeature state) {
        Rect rect = state.inputRect;
        int x = rect.centerX();
        int y = rect.centerY();
        BotLog.i(this, "voice.demo.press.point", "使用实时按住/说话区域中心 x=" + x
                + " y=" + y + " rect=" + rect.flattenToString() + " " + state.summary());
        return new int[]{x, y};
    }

    private void playTone(Intent intent) {
        int toneMs = intExtra(intent, "toneMs", 3200);
        int freqHz = intExtra(intent, "freqHz", 620);
        int volume = intExtra(intent, "musicVolume", -1);
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int oldVolume = -1;
        if (audioManager != null && volume >= 0) {
            oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(volume, max), 0);
        }
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuffer, SAMPLE_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        AudioDeviceInfo preferredDevice = findOutputDevice(intent);
        if (preferredDevice != null) {
            BotLog.i(this, "voice.demo.output.route", describeDevice(preferredDevice) + " ok=" + track.setPreferredDevice(preferredDevice));
        }
        try {
            BotLog.i(this, "voice.demo.tone.start", "freqHz=" + freqHz + " toneMs=" + toneMs);
            track.play();
            int totalSamples = SAMPLE_RATE * toneMs / 1000;
            short[] buffer = new short[1024];
            double phase = 0.0;
            double step = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
            int written = 0;
            while (written < totalSamples) {
                int count = Math.min(buffer.length, totalSamples - written);
                for (int i = 0; i < count; i++) {
                    double envelope = envelope(written + i, totalSamples);
                    buffer[i] = (short) (Math.sin(phase) * 16000 * envelope);
                    phase += step;
                }
                track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING);
                written += count;
            }
            track.stop();
            BotLog.i(this, "voice.demo.tone.done", "samples=" + totalSamples);
        } finally {
            track.release();
            if (audioManager != null && oldVolume >= 0 && boolExtra(intent, "restoreVolume", true)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0);
            }
        }
    }

    private void playFileWithPress(Intent intent, String path, String reason) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path is empty");
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException("file not found: " + path);
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int oldVolume = -1;
        int volume = intExtra(intent, "musicVolume", -1);
        if (audioManager != null && volume >= 0) {
            oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(volume, max), 0);
        }
        MediaPlayer player = new MediaPlayer();
        PressHandle press = null;
        int[] nativePressPoint = null;
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build());
            AudioDeviceInfo preferredDevice = findOutputDevice(intent);
            if (preferredDevice != null) {
                BotLog.i(this, "voice.demo.output.route", describeDevice(preferredDevice) + " ok=" + player.setPreferredDevice(preferredDevice));
            }
            player.setDataSource(path);
            player.prepare();
            applyPlaybackSpeed(player, intent, reason);
            BotLog.i(this, "voice.demo.file.ready", path + " size=" + file.length()
                    + " durationMs=" + Math.max(0, player.getDuration()));
            int durationMs = Math.max(0, player.getDuration());
            if (RootAccessChecker.hasRootPermission()) {
                int[] point = resolvePressPoint(intent);
                if (point == null) {
                    throw new IllegalStateException("WeChat voice press point not confirmed");
                }
                if (!pressNativeDown(point[0], point[1], reason)) {
                    throw new IllegalStateException("native WeChat voice press failed");
                }
                nativePressPoint = point;
            } else {
                press = pressDown(intent, reason);
                if (press == null) {
                    throw new IllegalStateException("WeChat voice press point not confirmed");
                }
            }
            delayAfterPressBeforePlayback(intent, reason);
            BotLog.i(this, "voice.demo.file.start", path + " size=" + file.length() + " pressSynced=true");
            if (VmicInjector.injectFile(this, file, vmicPlaybackTimeoutMs(durationMs), reason)) {
                BotLog.i(this, "voice.demo.file.vmic.done", path + " durationMs=" + durationMs);
            } else {
                BotLog.w(this, "voice.demo.file.vmic.fallback", path + " durationMs=" + durationMs);
                player.start();
                while (player.isPlaying()) {
                    SystemClock.sleep(80);
                }
            }
            BotLog.i(this, "voice.demo.file.done", path);
        } finally {
            if (nativePressPoint != null) {
                delayBeforeRelease(intent, reason);
                releaseNativePress(nativePressPoint[0], nativePressPoint[1], reason);
            } else {
                delayBeforeRelease(intent, reason);
                releasePress(press, reason);
            }
            player.release();
            if (audioManager != null && oldVolume >= 0 && boolExtra(intent, "restoreVolume", true)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0);
            }
        }
    }

    private boolean pressNativeDown(int x, int y, String reason) {
        String command = String.format(Locale.US, "input touchscreen motionevent DOWN %d %d", x, y);
        RootShellSession.Result result = RootShellSession.execute(command, 4000);
        BotLog.write(this, result.code == 0 ? "INFO" : "ERROR", "voice.demo.press.native.down",
                "reason=" + reason + " x=" + x + " y=" + y + " code=" + result.code
                        + " out=" + rootOutput(result.output));
        return result.code == 0;
    }

    private void releaseNativePress(int x, int y, String reason) {
        String command = String.format(Locale.US, "input touchscreen motionevent UP %d %d", x, y);
        RootShellSession.Result result = RootShellSession.execute(command, 4000);
        BotLog.write(this, result.code == 0 ? "INFO" : "WARN", "voice.demo.press.native.up",
                "reason=" + reason + " x=" + x + " y=" + y + " code=" + result.code
                        + " out=" + rootOutput(result.output));
    }

    private static int vmicPlaybackTimeoutMs(int durationMs) {
        return Math.max(8000, Math.min(58000, Math.round(durationMs * 1.45f) + 5000));
    }

    private static String rootOutput(String output) {
        if (output == null) {
            return "";
        }
        String text = output.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= 300 ? text : text.substring(0, 300);
    }

    private void delayAfterPressBeforePlayback(Intent intent, String reason) {
        long delayMs = Math.max(0, intExtra(intent, "prePlaybackPressMs", 500));
        if (delayMs <= 0) {
            return;
        }
        BotLog.i(this, "voice.demo.preplay.delay", "reason=" + reason + " delayMs=" + delayMs);
        SystemClock.sleep(delayMs);
    }

    private void delayBeforeRelease(Intent intent, String reason) {
        long delayMs = Math.max(0, intExtra(intent, "releaseAfterPlaybackMs", 2000));
        if (delayMs <= 0) {
            return;
        }
        BotLog.i(this, "voice.demo.release.delay", "reason=" + reason + " delayMs=" + delayMs);
        SystemClock.sleep(delayMs);
    }

    private static final class PressHandle {
        final int x;
        final int y;
        final int hsPort;

        PressHandle(int x, int y, int hsPort) {
            this.x = x;
            this.y = y;
            this.hsPort = hsPort;
        }
    }

    private void applyPlaybackSpeed(MediaPlayer player, Intent intent, String reason) {
        float speed = speechRate(intent);
        if (Math.abs(speed - BotConfig.DEFAULT_TTS_SPEED) < 0.001f || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(speed);
            player.setPlaybackParams(params);
            BotLog.i(this, "voice.demo.file.speed", "reason=" + reason + " speed=" + speed);
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.file.speed.fail", "reason=" + reason + " speed=" + speed
                    + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static float speechRate(Intent intent) {
        return BotConfig.normalizeTtsSpeed(floatExtra(intent, "speechRate", BotConfig.DEFAULT_TTS_SPEED));
    }

    private void playFile(Intent intent, String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path is empty");
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException("file not found: " + path);
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int oldVolume = -1;
        int volume = intExtra(intent, "musicVolume", -1);
        if (audioManager != null && volume >= 0) {
            oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(volume, max), 0);
        }
        BotLog.i(this, "voice.demo.file.start", path + " size=" + file.length());
        MediaPlayer player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build());
            AudioDeviceInfo preferredDevice = findOutputDevice(intent);
            if (preferredDevice != null) {
                BotLog.i(this, "voice.demo.output.route", describeDevice(preferredDevice) + " ok=" + player.setPreferredDevice(preferredDevice));
            }
            player.setDataSource(path);
            player.prepare();
            player.start();
            while (player.isPlaying()) {
                SystemClock.sleep(100);
            }
        } finally {
            player.release();
            if (audioManager != null && oldVolume >= 0 && boolExtra(intent, "restoreVolume", true)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0);
            }
        }
        BotLog.i(this, "voice.demo.file.done", path);
    }

    private void speakTts(Intent intent, String text) throws Exception {
        File remoteFile = generateRemoteTtsFile(intent, text);
        if (remoteFile != null) {
            try {
                playFile(intent, remoteFile.getAbsolutePath());
            } finally {
                cleanupTtsFile(intent, remoteFile);
            }
            return;
        }
        speakSystemTts(intent, text);
    }

    private void speakSystemTts(Intent intent, String text) throws Exception {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("text is empty");
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int oldVolume = -1;
        int volume = intExtra(intent, "musicVolume", -1);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(true);
            if (volume >= 0) {
                oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(volume, max), 0);
            }
        }
        CountDownLatch initLatch = new CountDownLatch(1);
        int[] initStatus = new int[]{TextToSpeech.ERROR};
        String engine = selectTtsEngine(intent);
        BotLog.i(this, "voice.demo.tts.engine", engine.isEmpty() ? "default" : engine);
        TextToSpeech tts = engine.isEmpty()
                ? new TextToSpeech(this, status -> {
                    initStatus[0] = status;
                    initLatch.countDown();
                })
                : new TextToSpeech(this, status -> {
                    initStatus[0] = status;
                    initLatch.countDown();
                }, engine);
        try {
            if (!initLatch.await(10, TimeUnit.SECONDS) || initStatus[0] != TextToSpeech.SUCCESS) {
                throw new IllegalStateException("TextToSpeech init failed status=" + initStatus[0]);
            }
            int language = tts.setLanguage(Locale.CHINA);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                BotLog.w(this, "voice.demo.tts.lang", "中文语音包不可用，尝试使用系统默认语言");
            }
            tts.setSpeechRate(speechRate(intent));
            tts.setPitch(floatExtra(intent, "speechPitch", 1.0f));
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            CountDownLatch doneLatch = new CountDownLatch(1);
            String utteranceId = "vxbot-tts-" + UUID.randomUUID();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    BotLog.i(VoiceDemoService.this, "voice.demo.tts.start", payload);
                }

                @Override
                public void onDone(String id) {
                    BotLog.i(VoiceDemoService.this, "voice.demo.tts.done", payload);
                    doneLatch.countDown();
                }

                @Override
                public void onError(String id) {
                    BotLog.e(VoiceDemoService.this, "voice.demo.tts.error", payload);
                    doneLatch.countDown();
                }
            });
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            int result = tts.speak(payload, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
                throw new IllegalStateException("TextToSpeech speak failed");
            }
            long waitMs = Math.max(8000L, estimateTtsPressMs(payload) + 5000L);
            if (!doneLatch.await(waitMs, TimeUnit.MILLISECONDS)) {
                BotLog.w(this, "voice.demo.tts.timeout", "waitMs=" + waitMs + " text=" + payload);
            }
        } finally {
            tts.shutdown();
            if (audioManager != null && oldVolume >= 0 && boolExtra(intent, "restoreVolume", true)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0);
            }
        }
    }

    private File generateRemoteTtsFile(Intent intent, String text) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            BotLog.e(this, "voice.demo.tts.remote.abort", "text is empty");
            return null;
        }
        String endpoint = stringExtra(intent, "ttsEndpoint", DEFAULT_QWEN_TTS_ENDPOINT).trim();
        String voice = stringExtra(intent, "ttsVoice", DEFAULT_QWEN_TTS_VOICE).trim();
        float speed = speechRate(intent);
        if (endpoint.isEmpty()) {
            return null;
        }
        int timeoutMs = Math.max(8000, intExtra(intent, "ttsTimeoutMs", 60000));
        int attempts = Math.max(1, Math.min(3, intExtra(intent, "ttsAttempts", 2)));
        String body = "{\"text\":" + jsonString(payload)
                + ",\"voice\":" + jsonString(voice.isEmpty() ? DEFAULT_QWEN_TTS_VOICE : voice)
                + ",\"speed\":" + String.format(Locale.US, "%.2f", speed)
                + ",\"mode\":\"system\"}";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpURLConnection connection = null;
            try {
                BotLog.i(this, "voice.demo.tts.remote.start",
                        "attempt=" + attempt + " endpoint=" + endpoint + " voice=" + voice + " speed=" + speed);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "audio/wav,audio/*,*/*");
                connection.setRequestProperty("Origin", "https://qwen3ttsai.com");
                connection.setRequestProperty("Referer", "https://qwen3ttsai.com/zh");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 VXBotWechatBot/0.1");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int code = connection.getResponseCode();
                String contentType = connection.getContentType();
                if (code >= 200 && code < 300) {
                    File dir = new File(getFilesDir(), "tts");
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
                    }
                    File file = new File(dir, "vxbot-tts-" + SystemClock.uptimeMillis() + ".wav");
                    try (InputStream in = connection.getInputStream();
                         FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    if (file.length() > 44) {
                        BotLog.i(this, "voice.demo.tts.remote.done",
                                "code=" + code + " type=" + contentType + " file=" + file.getAbsolutePath()
                                        + " size=" + file.length());
                        return file;
                    }
                    BotLog.e(this, "voice.demo.tts.remote.empty",
                            "code=" + code + " type=" + contentType + " size=" + file.length());
                    file.delete();
                } else {
                    BotLog.e(this, "voice.demo.tts.remote.http",
                            "code=" + code + " type=" + contentType + " body=" + readError(connection));
                }
            } catch (Exception e) {
                BotLog.e(this, "voice.demo.tts.remote.fail",
                        "attempt=" + attempt + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            if (attempt < attempts) {
                SystemClock.sleep(700);
            }
        }
        return null;
    }

    private int readMediaDurationMs(File file) {
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            return Math.max(0, player.getDuration());
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.file.duration.fail", e.getMessage());
            return 0;
        } finally {
            player.release();
        }
    }

    private int readWavSampleRate(File file) throws Exception {
        long length = file.length();
        if (length <= 0 || length > 32L * 1024L * 1024L) {
            throw new IllegalStateException("bad WAV size: " + length);
        }
        byte[] bytes = new byte[(int) length];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        if (offset != bytes.length) {
            throw new IllegalStateException("short WAV read: " + offset + "/" + bytes.length);
        }
        return readWavSampleRate(bytes, 0, bytes.length, false, file, 0);
    }

    private int readWavSampleRate(byte[] bytes, int base, int limit, boolean allowTruncatedData, File file, int depth)
            throws Exception {
        if (limit - base < 44 || !"RIFF".equals(ascii(bytes, base, 4))
                || !"WAVE".equals(ascii(bytes, base + 8, 4))) {
            throw new IllegalStateException("not a WAV file: " + file.getAbsolutePath());
        }
        int sampleRate = 0;
        int dataOffset = -1;
        int dataSize = 0;
        int offset = base + 12;
        while (offset + 8 <= limit) {
            String id = ascii(bytes, offset, 4);
            int size = le32(bytes, offset + 4);
            int chunkData = offset + 8;
            int remaining = limit - chunkData;
            if (size < 0 || chunkData > limit) {
                throw new IllegalStateException("bad WAV chunk: " + id);
            }
            if (size > remaining) {
                if ("data".equals(id) && allowTruncatedData && remaining > 0) {
                    size = remaining;
                } else {
                    throw new IllegalStateException("bad WAV chunk: " + id);
                }
            }
            if ("fmt ".equals(id)) {
                if (size < 16) {
                    throw new IllegalStateException("short WAV fmt chunk: " + file.getAbsolutePath());
                }
                int audioFormat = le16(bytes, chunkData);
                int channels = le16(bytes, chunkData + 2);
                sampleRate = le32(bytes, chunkData + 4);
                int bitsPerSample = le16(bytes, chunkData + 14);
                if (audioFormat != 1 || channels < 1 || sampleRate < 1 || bitsPerSample != 16) {
                    throw new IllegalStateException("unsupported WAV fmt=" + audioFormat
                            + " channels=" + channels
                            + " rate=" + sampleRate
                            + " bits=" + bitsPerSample);
                }
            } else if ("data".equals(id) && size > dataSize) {
                dataOffset = chunkData;
                dataSize = size;
            }
            offset = chunkData + size + (size & 1);
        }
        if (depth < 2 && dataOffset >= 0 && dataSize >= 44 && "RIFF".equals(ascii(bytes, dataOffset, 4))
                && "WAVE".equals(ascii(bytes, dataOffset + 8, 4))) {
            return readWavSampleRate(bytes, dataOffset, Math.min(limit, dataOffset + dataSize),
                    true, file, depth + 1);
        }
        if (sampleRate > 0) {
            return sampleRate;
        }
        throw new IllegalStateException("WAV fmt chunk missing: " + file.getAbsolutePath());
    }

    private File newVmicRecordFile() throws Exception {
        File dir = new File(getFilesDir(), "vmic-recordings");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
        }
        return new File(dir, "vxbot-vmic-record-" + SystemClock.uptimeMillis() + ".wav");
    }

    private File createVmicRecordTestSource() throws Exception {
        final int sampleRate = 24000;
        final int[] toneHz = {440, 660, 880};
        final int leadMs = 200;
        final int toneMs = 700;
        final int gapMs = 150;
        final int tailMs = 200;
        final int totalMs = leadMs + tailMs
                + toneHz.length * toneMs + (toneHz.length - 1) * gapMs;
        byte[] pcm = new byte[sampleRate * totalMs / 1000 * 2];
        int sample = sampleRate * leadMs / 1000;
        for (int frequency : toneHz) {
            int toneSamples = sampleRate * toneMs / 1000;
            int fadeSamples = sampleRate / 100;
            for (int i = 0; i < toneSamples; i++, sample++) {
                double gain = 1.0;
                if (i < fadeSamples) {
                    gain = (double) i / fadeSamples;
                } else if (i >= toneSamples - fadeSamples) {
                    gain = (double) (toneSamples - 1 - i) / fadeSamples;
                }
                short value = (short) (Math.sin(2.0 * Math.PI * frequency * i / sampleRate)
                        * 10000.0 * Math.max(0.0, gain));
                int offset = sample * 2;
                pcm[offset] = (byte) (value & 0xff);
                pcm[offset + 1] = (byte) ((value >>> 8) & 0xff);
            }
            sample += sampleRate * gapMs / 1000;
        }
        File dir = new File(getFilesDir(), "vmic-recordings");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("mkdir failed: " + dir.getAbsolutePath());
        }
        File source = new File(dir, "vxbot-vmic-source-" + SystemClock.uptimeMillis() + ".wav");
        writeWavFile(source, pcm, sampleRate);
        BotLog.i(this, "voice.demo.vmic.source.created",
                "file=" + source.getAbsolutePath() + " rate=" + sampleRate
                        + " durationMs=" + totalMs + " size=" + source.length());
        return source;
    }

    private RecordingHandle startMicRecording(File output, int recordMs, int sampleRate,
                                              int audioSource, String audioSourceName) {
        String tinycap = findTinycapBinary();
        if (!tinycap.isEmpty()) {
            TinycapRecordingJob job = new TinycapRecordingJob(output, Math.max(1000, recordMs),
                    sampleRate, tinycap);
            job.start();
            return job;
        }
        RecordingJob job = new RecordingJob(output, Math.max(1000, recordMs), sampleRate,
                audioSource, audioSourceName);
        job.start();
        return job;
    }

    private interface RecordingHandle {
        void await(long timeoutMs) throws InterruptedException;
    }

    private final class TinycapRecordingJob implements Runnable, RecordingHandle {
        private final File output;
        private final int recordMs;
        private final int sampleRate;
        private final String tinycap;
        private final Thread thread;

        TinycapRecordingJob(File output, int recordMs, int sampleRate, String tinycap) {
            this.output = output;
            this.recordMs = recordMs;
            this.sampleRate = sampleRate;
            this.tinycap = tinycap;
            this.thread = new Thread(this, "vmic-record-tinycap");
        }

        void start() {
            thread.start();
        }

        @Override
        public void await(long timeoutMs) throws InterruptedException {
            thread.join(Math.max(1000L, timeoutMs));
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(1000L);
            }
        }

        @Override
        public void run() {
            try {
                int seconds = Math.max(1, (recordMs + 999) / 1000);
                String path = output.getAbsolutePath();
                String owner = android.os.Process.myUid() + ":" + android.os.Process.myUid();
                String command = "rm -f " + shellQuote(path) + "; "
                        + shellQuote(tinycap) + " " + shellQuote(path)
                        + " -D 1 -d 1 -c 1 -r " + sampleRate + " -b 32 -t " + seconds + "; "
                        + "rc=$?; "
                        + "[ -f " + shellQuote(path) + " ] && chown " + owner + " " + shellQuote(path)
                        + " && chmod 600 " + shellQuote(path)
                        + " && restorecon " + shellQuote(path) + " 2>/dev/null || true; "
                        + "exit $rc";
                BotLog.i(VoiceDemoService.this, "voice.demo.vmic.record.tinycap.start",
                        "file=" + path + " recordMs=" + recordMs + " seconds=" + seconds
                                + " rate=" + sampleRate + " tinycap=" + tinycap);
                String out = runRootCommand(command);
                if (!output.isFile() || output.length() <= 44) {
                    throw new IllegalStateException("tinycap empty: " + out);
                }
                boolean converted = convertTinycapS32WavToS16(output);
                BotLog.i(VoiceDemoService.this, "voice.demo.vmic.record.tinycap.file",
                        "file=" + path + " rate=" + sampleRate
                                + " size=" + output.length()
                                + " convertedS32ToS16=" + converted
                                + " out=" + out);
            } catch (Exception e) {
                BotLog.e(VoiceDemoService.this, "voice.demo.vmic.record.tinycap.fail",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private final class RecordingJob implements Runnable, RecordingHandle {
        private final File output;
        private final int recordMs;
        private final int sampleRate;
        private final int audioSource;
        private final String audioSourceName;
        private final Thread thread;

        RecordingJob(File output, int recordMs, int sampleRate, int audioSource, String audioSourceName) {
            this.output = output;
            this.recordMs = recordMs;
            this.sampleRate = sampleRate;
            this.audioSource = audioSource;
            this.audioSourceName = audioSourceName;
            this.thread = new Thread(this, "vmic-record-test");
        }

        void start() {
            thread.start();
        }

        @Override
        public void await(long timeoutMs) throws InterruptedException {
            thread.join(Math.max(1000L, timeoutMs));
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(1000L);
            }
        }

        @Override
        public void run() {
            AudioRecord recorder = null;
            try {
                int minBuffer = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minBuffer <= 0) {
                    throw new IllegalStateException("bad minBuffer=" + minBuffer + " rate=" + sampleRate);
                }
                int bufferSize = Math.max(minBuffer, sampleRate);
                recorder = new AudioRecord(
                        audioSource,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord init failed state=" + recorder.getState());
                }
                ByteArrayOutputStream pcm = new ByteArrayOutputStream(sampleRate * 2 * Math.max(1, recordMs / 1000));
                byte[] buffer = new byte[bufferSize];
                long endAt = SystemClock.uptimeMillis() + recordMs;
                recorder.startRecording();
                BotLog.i(VoiceDemoService.this, "voice.demo.vmic.record.start",
                        "file=" + output.getAbsolutePath()
                                + " recordMs=" + recordMs
                                + " rate=" + sampleRate
                                + " actualRate=" + recorder.getSampleRate()
                                + " audioSource=" + audioSourceName
                                + " buffer=" + bufferSize);
                while (!Thread.currentThread().isInterrupted() && SystemClock.uptimeMillis() < endAt) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcm.write(buffer, 0, read);
                    } else {
                        SystemClock.sleep(20);
                    }
                }
                recorder.stop();
                writeWavFile(output, pcm.toByteArray(), sampleRate);
                BotLog.i(VoiceDemoService.this, "voice.demo.vmic.record.file",
                        "file=" + output.getAbsolutePath()
                                + " rate=" + sampleRate
                                + " size=" + output.length());
            } catch (Exception e) {
                BotLog.e(VoiceDemoService.this, "voice.demo.vmic.record.fail",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (recorder != null) {
                    recorder.release();
                }
            }
        }
    }

    private final class SystemRecorderRecordingJob implements Runnable, RecordingHandle {
        private final File output;
        private final int sampleRate;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final Thread thread;

        SystemRecorderRecordingJob(File output, int sampleRate) {
            this.output = output;
            this.sampleRate = sampleRate;
            this.thread = new Thread(this, "vmic-record-system-compatible");
        }

        void start() {
            thread.start();
        }

        void requestStop() {
            stopRequested.set(true);
        }

        @Override
        public void await(long timeoutMs) throws InterruptedException {
            thread.join(Math.max(1000L, timeoutMs));
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(1000L);
            }
        }

        @Override
        public void run() {
            AudioRecord recorder = null;
            try {
                int minBuffer = AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minBuffer <= 0) {
                    throw new IllegalStateException("bad stereo minBuffer=" + minBuffer);
                }
                int bufferSize = Math.max(minBuffer, sampleRate * 4);
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("stereo AudioRecord init failed state=" + recorder.getState());
                }
                ByteArrayOutputStream pcm = new ByteArrayOutputStream(sampleRate * 4 * 10);
                byte[] buffer = new byte[bufferSize];
                recorder.startRecording();
                BotLog.i(VoiceDemoService.this, "voice.demo.vmic.record.system.start",
                        "file=" + output.getAbsolutePath()
                                + " rate=" + recorder.getSampleRate()
                                + " channels=2 audioSource=MIC"
                                + " buffer=" + bufferSize);
                while (!Thread.currentThread().isInterrupted() && !stopRequested.get()) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcm.write(buffer, 0, read);
                    } else {
                        SystemClock.sleep(20);
                    }
                }
                recorder.stop();
                writeWavFile(output, pcm.toByteArray(), sampleRate, 2);
                BotLog.i(VoiceDemoService.this, "voice.demo.vmic.record.system.file",
                        "file=" + output.getAbsolutePath()
                                + " rate=" + sampleRate
                                + " channels=2 size=" + output.length());
            } catch (Exception e) {
                BotLog.e(VoiceDemoService.this, "voice.demo.vmic.record.system.fail",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (recorder != null) {
                    recorder.release();
                }
            }
        }
    }

    private String findTinycapBinary() {
        String out = runRootCommand("for p in /data/local/tmp/tinycap /system/bin/tinycap /vendor/bin/tinycap; do "
                + "[ -x \"$p\" ] && echo \"$p\" && exit 0; done");
        if (out == null) {
            return "";
        }
        for (String line : out.split("\\n")) {
            String path = line.trim();
            if (path.endsWith("/tinycap")) {
                return path;
            }
        }
        return "";
    }

    private static boolean convertTinycapS32WavToS16(File file) throws Exception {
        byte[] bytes = readFileBytes(file, 32L * 1024L * 1024L);
        if (bytes.length < 44 || !"RIFF".equals(ascii(bytes, 0, 4))
                || !"WAVE".equals(ascii(bytes, 8, 4))) {
            throw new IllegalStateException("tinycap output is not WAV");
        }
        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataSize = 0;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String id = ascii(bytes, offset, 4);
            int size = le32(bytes, offset + 4);
            int chunkData = offset + 8;
            int remaining = bytes.length - chunkData;
            if (size < 0 || chunkData > bytes.length || size > remaining) {
                throw new IllegalStateException("bad tinycap WAV chunk: " + id);
            }
            if ("fmt ".equals(id)) {
                if (size < 16) {
                    throw new IllegalStateException("short tinycap WAV fmt");
                }
                audioFormat = le16(bytes, chunkData);
                channels = le16(bytes, chunkData + 2);
                sampleRate = le32(bytes, chunkData + 4);
                bitsPerSample = le16(bytes, chunkData + 14);
            } else if ("data".equals(id)) {
                dataOffset = chunkData;
                dataSize = size;
            }
            offset = chunkData + size + (size & 1);
        }
        if (audioFormat != 1 || channels != 1 || sampleRate < 1 || dataOffset < 0 || dataSize <= 0) {
            throw new IllegalStateException("unsupported tinycap WAV fmt=" + audioFormat
                    + " channels=" + channels + " rate=" + sampleRate + " data=" + dataSize);
        }
        if (bitsPerSample == 16) {
            return false;
        }
        if (bitsPerSample != 32 || (dataSize % 4) != 0) {
            throw new IllegalStateException("unsupported tinycap bits=" + bitsPerSample);
        }
        byte[] pcm16 = new byte[(dataSize / 4) * 2];
        int out = 0;
        for (int in = dataOffset; in + 3 < dataOffset + dataSize; in += 4) {
            int sample32 = le32(bytes, in);
            int sample16 = sample32 >> 8;
            if (sample16 > Short.MAX_VALUE) {
                sample16 = Short.MAX_VALUE;
            } else if (sample16 < Short.MIN_VALUE) {
                sample16 = Short.MIN_VALUE;
            }
            pcm16[out++] = (byte) (sample16 & 0xff);
            pcm16[out++] = (byte) ((sample16 >>> 8) & 0xff);
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".s16.tmp");
        writeWavFile(tmp, pcm16, sampleRate);
        if (!file.delete()) {
            throw new IllegalStateException("delete tinycap source failed: " + file.getAbsolutePath());
        }
        if (!tmp.renameTo(file)) {
            throw new IllegalStateException("replace tinycap WAV failed: " + file.getAbsolutePath());
        }
        return true;
    }

    private static void writeWavFile(File file, byte[] pcm, int sampleRate) throws Exception {
        writeWavFile(file, pcm, sampleRate, 1);
    }

    private static void writeWavFile(File file, byte[] pcm, int sampleRate, int channels) throws Exception {
        int dataSize = pcm == null ? 0 : pcm.length;
        int blockAlign = channels * 2;
        int byteRate = sampleRate * blockAlign;
        try (FileOutputStream out = new FileOutputStream(file)) {
            writeAscii(out, "RIFF");
            writeLe32(out, 36 + dataSize);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLe32(out, 16);
            writeLe16(out, 1);
            writeLe16(out, channels);
            writeLe32(out, sampleRate);
            writeLe32(out, byteRate);
            writeLe16(out, blockAlign);
            writeLe16(out, 16);
            writeAscii(out, "data");
            writeLe32(out, dataSize);
            if (pcm != null && pcm.length > 0) {
                out.write(pcm);
            }
        }
    }

    private static int audioSourceExtra(Intent intent) {
        String value = stringExtra(intent, "audioSource", "UNPROCESSED").trim().toUpperCase(Locale.US);
        if ("VOICE_RECOGNITION".equals(value) || "VR".equals(value)) {
            return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
        if ("VOICE_COMMUNICATION".equals(value) || "VC".equals(value)) {
            return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
        if ("CAMCORDER".equals(value)) {
            return MediaRecorder.AudioSource.CAMCORDER;
        }
        if ("UNPROCESSED".equals(value) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return MediaRecorder.AudioSource.UNPROCESSED;
        }
        return MediaRecorder.AudioSource.MIC;
    }

    private static String audioSourceName(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            return "VOICE_COMMUNICATION";
        }
        if (audioSource == MediaRecorder.AudioSource.CAMCORDER) {
            return "CAMCORDER";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && audioSource == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED";
        }
        return "MIC";
    }

    private static void writeAscii(FileOutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLe16(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            return "";
        }
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static byte[] readFileBytes(File file, long maxBytes) throws Exception {
        long length = file.length();
        if (length <= 0 || length > maxBytes) {
            throw new IllegalStateException("bad file size: " + length);
        }
        byte[] bytes = new byte[(int) length];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        if (offset != bytes.length) {
            throw new IllegalStateException("short file read: " + offset + "/" + bytes.length);
        }
        return bytes;
    }

    private static String shellQuote(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private void cleanupTtsFile(Intent intent, File file) {
        if (file == null || !boolExtra(intent, "deleteTtsFile", true)) {
            return;
        }
        if (file.delete()) {
            BotLog.i(this, "voice.demo.tts.file.deleted", file.getAbsolutePath());
        }
    }

    private static String readError(HttpURLConnection connection) {
        try (InputStream in = connection.getErrorStream()) {
            if (in == null) {
                return "";
            }
            byte[] buffer = new byte[768];
            int read = in.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).replace('\n', ' ').trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String jsonString(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }

    private void probeDevice() {
        BotLog.i(this, "voice.demo.probe.kernel", runRootCommand("uname -a"));
        BotLog.i(this, "voice.demo.probe.cards", runRootCommand("cat /proc/asound/cards 2>/dev/null || true"));
        BotLog.i(this, "voice.demo.probe.pcm", runRootCommand("cat /proc/asound/pcm 2>/dev/null | head -80"));
        BotLog.i(this, "voice.demo.probe.modules", runRootCommand("find /vendor/lib/modules /system/lib/modules -type f 2>/dev/null | grep -Ei 'snd|loop|dummy|bt' | head -50"));
        BotLog.i(this, "voice.demo.probe.policy", runRootCommand("dumpsys media.audio_policy 2>/dev/null | grep -Ei 'Remote Submix|BT SCO Headset Mic|primary input|voip_tx|fast input' | head -120"));
    }

    private void runRouteMode(String mode) throws Exception {
        String routeMode;
        if ("routeSetSubmix".equalsIgnoreCase(mode) || "routePreferSubmix".equalsIgnoreCase(mode)) {
            routeMode = "set-submix";
        } else if ("routeClear".equalsIgnoreCase(mode)) {
            routeMode = "clear";
        } else if ("routeDump".equalsIgnoreCase(mode) || "routeStatus".equalsIgnoreCase(mode)) {
            routeMode = "dump";
        } else {
            routeMode = "list";
        }
        BotLog.i(this, "voice.route.start", routeMode);
        BotLog.i(this, "voice.route.result", AudioRouteTool.runForContext(this, routeMode));
    }

    private AudioDeviceInfo findOutputDevice(Intent intent) {
        return findOutputDeviceByRoute(stringExtra(intent, "outputRoute", ""));
    }

    private AudioDeviceInfo findOutputDeviceByRoute(String route) {
        if (route == null || route.trim().isEmpty()) {
            return null;
        }
        int wantedType;
        if ("remote_submix".equalsIgnoreCase(route) || "submix".equalsIgnoreCase(route)) {
            wantedType = AUDIO_DEVICE_TYPE_REMOTE_SUBMIX;
        } else {
            BotLog.w(this, "voice.demo.output.route.unknown", route);
            return null;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.getType() == wantedType && device.isSink()) {
                return device;
            }
        }
        BotLog.w(this, "voice.demo.output.route.missing", route);
        return null;
    }

    private static String describeDevice(AudioDeviceInfo device) {
        return "id=" + device.getId()
                + " type=" + device.getType()
                + " sink=" + device.isSink()
                + " source=" + device.isSource()
                + " name=" + device.getProductName()
                + " address=" + device.getAddress();
    }

    private static int readRemoteSubmixType() {
        try {
            return AudioDeviceInfo.class.getField("TYPE_REMOTE_SUBMIX").getInt(null);
        } catch (Exception ignored) {
            return 25;
        }
    }

    private String runRootCommand(String command) {
        String[] candidates = {"/debug_ramdisk/su", "/sbin/su", "/system/bin/su", "/system/xbin/su", "su"};
        Exception last = null;
        for (String binary : candidates) {
            if (!"su".equals(binary) && !new File(binary).exists()) {
                continue;
            }
            Process process = null;
            try {
                process = new ProcessBuilder(binary, "-c", command).redirectErrorStream(true).start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (out.length() < 1800) {
                            out.append(line).append('\n');
                        }
                    }
                }
                int code = process.waitFor();
                String text = out.toString().trim();
                BotLog.i(this, "voice.demo.shell", "su=" + binary + " code=" + code + " cmd=" + command + " out=" + text);
                return text;
            } catch (Exception e) {
                last = e;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        if (last != null) {
            BotLog.e(this, "voice.demo.shell.fail", command + " :: " + last.getMessage());
        } else {
            BotLog.e(this, "voice.demo.shell.fail", command + " :: su not found");
        }
        return "";
    }

    private void ensureForeground(Intent intent) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Voice Demo", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Voice demo running")
                .setContentText("Testing WeChat voice bubble")
                .setOngoing(false)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if ("vmicRecordTest".equalsIgnoreCase(stringExtra(intent, "mode", ""))) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void sendFinishBroadcast(Intent source) {
        String requestId = stringExtra(source, EXTRA_REQUEST_ID, "");
        if (requestId.isEmpty()) {
            return;
        }
        Intent done = new Intent(ACTION_VOICE_DEMO_FINISH)
                .setPackage(getPackageName())
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra("mode", stringExtra(source, "mode", ""));
        sendBroadcast(done);
        BotLog.i(this, "voice.demo.finish.broadcast", "requestId=" + requestId);
    }

    private static double envelope(int sample, int totalSamples) {
        int fade = Math.max(1, SAMPLE_RATE / 20);
        if (sample < fade) {
            return sample / (double) fade;
        }
        int remain = totalSamples - sample;
        if (remain < fade) {
            return Math.max(0.0, remain / (double) fade);
        }
        return 1.0;
    }

    private String selectTtsEngine(Intent intent) {
        String requested = stringExtra(intent, "ttsEngine", "").trim();
        if (!requested.isEmpty()) {
            return requested;
        }
        try {
            String current = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
            if (current != null && !current.trim().isEmpty()) {
                return current.trim();
            }
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.tts.default.fail", e.getMessage());
        }
        try {
            Intent query = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> engines = getPackageManager().queryIntentServices(query, 0);
            if (engines != null) {
                for (ResolveInfo info : engines) {
                    if (info != null && info.serviceInfo != null && info.serviceInfo.packageName != null) {
                        return info.serviceInfo.packageName;
                    }
                }
            }
        } catch (Exception e) {
            BotLog.w(this, "voice.demo.tts.query.fail", e.getMessage());
        }
        return "";
    }

    private static int estimateTtsPressMs(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", "");
        int chars = Math.max(1, value.length());
        return Math.max(3500, Math.min(60000, 1800 + chars * 260));
    }

    private static String stringExtra(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null ? fallback : value;
    }

    private static int intExtra(Intent intent, String key, int fallback) {
        return intent.hasExtra(key) ? intent.getIntExtra(key, fallback) : fallback;
    }

    private static boolean boolExtra(Intent intent, String key, boolean fallback) {
        return intent.hasExtra(key) ? intent.getBooleanExtra(key, fallback) : fallback;
    }

    private static float floatExtra(Intent intent, String key, float fallback) {
        return intent.hasExtra(key) ? intent.getFloatExtra(key, fallback) : fallback;
    }
}
