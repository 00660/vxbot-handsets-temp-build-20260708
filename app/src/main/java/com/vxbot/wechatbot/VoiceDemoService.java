package com.vxbot.wechatbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.BufferedReader;
import java.io.File;
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

public final class VoiceDemoService extends Service {
    private static final String CHANNEL_ID = "voice_demo";
    private static final int NOTIFICATION_ID = 41;
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_DEVICE_TYPE_REMOTE_SUBMIX = readRemoteSubmixType();
    private static final String DEFAULT_QWEN_TTS_ENDPOINT = "https://qwen3ttsai.com/api/qwen3tts/generate";
    private static final String DEFAULT_QWEN_TTS_VOICE = BotConfig.DEFAULT_TTS_VOICE;
    static final String ACTION_VOICE_DEMO_FINISH = "com.vxbot.wechatbot.action.VOICE_DEMO_FINISH";
    static final String EXTRA_REQUEST_ID = "requestId";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        Intent safeIntent = intent == null ? new Intent() : intent;
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
        } else if ("file".equalsIgnoreCase(mode)) {
            playFile(intent, stringExtra(intent, "path", ""));
        } else if ("tts".equalsIgnoreCase(mode) || "text".equalsIgnoreCase(mode)) {
            speakTts(intent, stringExtra(intent, "text", ""));
        } else {
            playTone(intent);
        }
        BotLog.i(this, "voice.demo.done", "mode=" + mode);
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
            if (!VmicInjector.injectFile(this, remoteFile, Math.max(8000, durationMs + 5000), "vmic-tts")) {
                throw new IllegalStateException("vmic inject failed");
            }
            BotLog.i(this, "voice.demo.tts.vmic.done", remoteFile.getAbsolutePath()
                    + " durationMs=" + durationMs);
        } finally {
            cleanupTtsFile(intent, remoteFile);
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
            press = pressDown(intent, reason);
            if (press == null) {
                throw new IllegalStateException("WeChat voice press point not confirmed");
            }
            delayAfterPressBeforePlayback(intent, reason);
            BotLog.i(this, "voice.demo.file.start", path + " size=" + file.length() + " pressSynced=true");
            int durationMs = Math.max(0, player.getDuration());
            if (VmicInjector.injectFile(this, file, Math.max(8000, durationMs + 5000), reason)) {
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
            delayBeforeRelease(intent, reason);
            releasePress(press, reason);
            player.release();
            if (audioManager != null && oldVolume >= 0 && boolExtra(intent, "restoreVolume", true)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0);
            }
        }
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

    private void ensureForeground() {
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
        startForeground(NOTIFICATION_ID, notification);
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
