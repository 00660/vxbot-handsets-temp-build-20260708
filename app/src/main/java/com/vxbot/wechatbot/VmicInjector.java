package com.vxbot.wechatbot;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class VmicInjector {
    private static final String VMIC_PLAY = "/vendor/bin/vmic_play";
    private static final String VMIC_PLAY_MODULE = "/data/adb/modules/mido_vmic_hal/system/vendor/bin/vmic_play";
    private static final String VMIC_PUSH = "/vendor/bin/vmic_push";
    private static final String VMIC_PUSH_INSTANTNOODLEP_MODULE =
            "/data/adb/modules/instantnoodlep_vmic/system/vendor/bin/vmic_push";
    private static final String MTK_VIRTUAL_MIC_CTL = "/proc/mtk_virtual_mic_ctl";
    private static final String MTK_VIRTUAL_MIC_PCM = "/proc/mtk_virtual_mic_pcm";
    private static final String MTK_VIRTUAL_MIC_STATUS = "/proc/mtk_virtual_mic_status";
    private static final int MTK_PROC_TARGET_PEAK = 22938;
    private static final Object INJECT_LOCK = new Object();

    private VmicInjector() {
    }

    static boolean injectFile(Context context, File file, int timeoutMs, String reason) {
        synchronized (INJECT_LOCK) {
            if (file == null || !file.isFile() || file.length() <= 44) {
                BotLog.w(context, "vmic.inject.skip", "reason=" + reason + " invalid file");
                return false;
            }
            Helper procHelper = findMtkProcHelper(context);
            if (!procHelper.path.isEmpty() && injectMtkProc(context, file, timeoutMs, reason)) {
                return true;
            }
            Helper helper = findLegacyHelper(context);
            if (helper.path.isEmpty()) {
                BotLog.w(context, "vmic.inject.unavailable", "reason=" + reason
                        + " helper missing: " + MTK_VIRTUAL_MIC_CTL + " / " + VMIC_PLAY + " / " + VMIC_PUSH);
                return false;
            }
            int waitMs = Math.max(8000, timeoutMs);
            String command = shellQuote(helper.path) + " " + shellQuote(file.getAbsolutePath()) + " 48000 1";
            ShellResult result = runRoot(command, waitMs);
            if (result.code == 0) {
                BotLog.i(context, "vmic.inject.done", "reason=" + reason
                        + " helper=" + helper.name
                        + " file=" + file.getAbsolutePath()
                        + " size=" + file.length()
                        + " elapsedMs=" + result.elapsedMs
                        + " out=" + trim(result.output));
                return true;
            }
            BotLog.w(context, "vmic.inject.fail", "reason=" + reason
                    + " helper=" + helper.name
                    + " code=" + result.code
                    + " elapsedMs=" + result.elapsedMs
                    + " out=" + trim(result.output));
            return false;
        }
    }

    static boolean helperPresent(Context context) {
        return !findMtkProcHelper(context).path.isEmpty() || !findLegacyHelper(context).path.isEmpty();
    }

    private static Helper findMtkProcHelper(Context context) {
        ShellResult proc = runRoot("[ -e " + shellQuote(MTK_VIRTUAL_MIC_CTL) + " ] && [ -e "
                + shellQuote(MTK_VIRTUAL_MIC_PCM) + " ] && [ -r " + shellQuote(MTK_VIRTUAL_MIC_STATUS)
                + " ] && echo mtk_virtual_mic || true", 4000);
        if (proc.output != null && proc.output.contains("mtk_virtual_mic")) {
            return new Helper("mtk_virtual_mic_proc", MTK_VIRTUAL_MIC_CTL);
        }
        if (context != null) {
            BotLog.i(context, "vmic.inject.proc", "mtk_virtual_mic=false");
        }
        return new Helper("", "");
    }

    private static Helper findLegacyHelper(Context context) {
        ShellResult vendor = runRoot("[ -x " + shellQuote(VMIC_PLAY) + " ] && echo "
                + shellQuote(VMIC_PLAY) + " || true", 4000);
        if (vendor.output != null && vendor.output.contains(VMIC_PLAY)) {
            return new Helper("vmic_play", VMIC_PLAY);
        }
        ShellResult module = runRoot("[ -x " + shellQuote(VMIC_PLAY_MODULE) + " ] && echo "
                + shellQuote(VMIC_PLAY_MODULE) + " || true", 4000);
        if (module.output != null && module.output.contains(VMIC_PLAY_MODULE)) {
            return new Helper("vmic_play_module", VMIC_PLAY_MODULE);
        }
        ShellResult push = runRoot("[ -x " + shellQuote(VMIC_PUSH) + " ] && echo "
                + shellQuote(VMIC_PUSH) + " || true", 4000);
        if (push.output != null && push.output.contains(VMIC_PUSH)) {
            return new Helper("vmic_push", VMIC_PUSH);
        }
        ShellResult instantnoodlep = runRoot("[ -x " + shellQuote(VMIC_PUSH_INSTANTNOODLEP_MODULE) + " ] && echo "
                + shellQuote(VMIC_PUSH_INSTANTNOODLEP_MODULE) + " || true", 4000);
        if (instantnoodlep.output != null && instantnoodlep.output.contains(VMIC_PUSH_INSTANTNOODLEP_MODULE)) {
            return new Helper("vmic_push_instantnoodlep_module", VMIC_PUSH_INSTANTNOODLEP_MODULE);
        }
        if (context != null) {
            BotLog.i(context, "vmic.inject.helper",
                    "vmic_play=false vmic_push=false instantnoodlep_module=false");
        }
        return new Helper("", "");
    }

    private static boolean injectMtkProc(Context context, File file, int timeoutMs, String reason) {
        ProcAudio audio = null;
        try {
            audio = prepareMtkProcAudio(context, file);
            String ctl = shellQuote(MTK_VIRTUAL_MIC_CTL);
            String pcm = shellQuote(MTK_VIRTUAL_MIC_PCM);
            String status = shellQuote(MTK_VIRTUAL_MIC_STATUS);
            String raw = shellQuote(audio.file.getAbsolutePath());
            String startCommand = "echo enable 0 > " + ctl + " 2>/dev/null || true; "
                    + "echo rate " + audio.controlRate + " > " + ctl + "; "
                    + "echo loop 0 > " + ctl + " 2>/dev/null || true; "
                    + "cat " + raw + " > " + pcm + "; rc=$?; "
                    + "[ $rc -eq 0 ] || exit $rc; "
                    + "echo enable 1 > " + ctl + "; rc=$?; "
                    + "cat " + status + " 2>/dev/null || true; "
                    + "exit $rc";
            long start = SystemClock.uptimeMillis();
            ShellResult started = runRoot(startCommand, 8000);
            if (started.code != 0) {
                BotLog.w(context, "vmic.inject.proc.fail", "reason=" + reason
                        + " startCode=" + started.code
                        + " elapsedMs=" + started.elapsedMs
                        + " out=" + trim(started.output));
                return false;
            }
            int holdMs = Math.min(Math.max(audio.durationMs + 350, 800), Math.max(1000, timeoutMs));
            BotLog.i(context, "vmic.inject.proc.start", "reason=" + reason
                    + " source=" + file.getAbsolutePath()
                    + " pcm=" + audio.file.getAbsolutePath()
                    + " bytes=" + audio.file.length()
                    + " rate=" + audio.sampleRate
                    + " controlRate=" + audio.controlRate
                    + " durationMs=" + audio.durationMs
                    + " holdMs=" + holdMs
                    + " out=" + trim(started.output));
            SystemClock.sleep(holdMs);
            ShellResult stopped = runRoot("echo enable 0 > " + ctl + " 2>/dev/null || true; cat "
                    + status + " 2>/dev/null || true", 4000);
            long elapsed = SystemClock.uptimeMillis() - start;
            BotLog.i(context, "vmic.inject.done", "reason=" + reason
                    + " helper=mtk_virtual_mic_proc"
                    + " file=" + file.getAbsolutePath()
                    + " size=" + file.length()
                    + " pcmRate=" + audio.sampleRate
                    + " controlRate=" + audio.controlRate
                    + " pcmBytes=" + audio.file.length()
                    + " elapsedMs=" + elapsed
                    + " stopCode=" + stopped.code
                    + " out=" + trim(stopped.output));
            return true;
        } catch (Exception e) {
            BotLog.w(context, "vmic.inject.proc.error", "reason=" + reason + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (audio != null && audio.file != null && audio.file.delete()) {
                BotLog.i(context, "vmic.inject.proc.deleted", audio.file.getAbsolutePath());
            }
        }
    }

    private static ProcAudio prepareMtkProcAudio(Context context, File wavFile) throws IOException {
        WavData wav = readWav(wavFile);
        byte[] pcm = toSourceRateMonoPcm(wav);
        if (pcm.length < 2) {
            throw new IOException("empty pcm");
        }
        File dir = new File(context.getCacheDir(), "vmic");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdir failed: " + dir.getAbsolutePath());
        }
        File out = new File(dir, "vxbot-vmic-" + SystemClock.uptimeMillis() + ".pcm");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(pcm);
        }
        int frames = pcm.length / 2;
        int durationMs = Math.max(1, Math.round(frames * 1000f / wav.sampleRate));
        int controlRate = mtkProcControlRate(wav.sampleRate);
        int pcmPeak = pcmPeak(pcm);
        BotLog.i(context, "vmic.inject.proc.audio", "sourceRate=" + wav.sampleRate
                + " controlRate=" + controlRate
                + " channels=" + wav.channels
                + " dataBytes=" + wav.dataSize
                + " pcmBytes=" + pcm.length
                + " pcmPeak=" + pcmPeak
                + " durationMs=" + durationMs);
        return new ProcAudio(out, durationMs, wav.sampleRate, controlRate);
    }

    private static int mtkProcControlRate(int sampleRate) {
        int compensated = Math.round(sampleRate * 4f / 3f);
        return Math.max(8000, Math.min(192000, compensated));
    }

    private static WavData readWav(File file) throws IOException {
        byte[] bytes = readAll(file);
        return readWav(bytes, 0, bytes.length, false, 0);
    }

    private static WavData readWav(byte[] bytes, int base, int limit, boolean allowTruncatedData, int depth)
            throws IOException {
        if (limit - base < 44 || !"RIFF".equals(ascii(bytes, base, 4))
                || !"WAVE".equals(ascii(bytes, base + 8, 4))) {
            throw new IOException("not a RIFF/WAVE file");
        }
        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataSize = 0;
        int offset = base + 12;
        while (offset + 8 <= limit) {
            String id = ascii(bytes, offset, 4);
            int size = le32(bytes, offset + 4);
            int chunkData = offset + 8;
            int remaining = limit - chunkData;
            if (size < 0 || chunkData > limit) {
                throw new IOException("bad wav chunk: " + id);
            }
            if (size > remaining) {
                if ("data".equals(id) && allowTruncatedData && remaining > 0) {
                    size = remaining;
                } else {
                    throw new IOException("bad wav chunk: " + id);
                }
            }
            if ("fmt ".equals(id)) {
                if (size < 16) {
                    throw new IOException("short fmt chunk");
                }
                audioFormat = le16(bytes, chunkData);
                channels = le16(bytes, chunkData + 2);
                sampleRate = le32(bytes, chunkData + 4);
                bitsPerSample = le16(bytes, chunkData + 14);
            } else if ("data".equals(id) && size > dataSize) {
                dataOffset = chunkData;
                dataSize = size;
            }
            offset = chunkData + size + (size & 1);
        }
        if (audioFormat != 1 || channels < 1 || sampleRate < 1 || bitsPerSample != 16
                || dataOffset < 0 || dataSize < channels * 2) {
            throw new IOException("unsupported wav format fmt=" + audioFormat
                    + " channels=" + channels
                    + " rate=" + sampleRate
                    + " bits=" + bitsPerSample
                    + " data=" + dataSize);
        }
        if (depth < 2 && dataSize >= 44 && "RIFF".equals(ascii(bytes, dataOffset, 4))
                && "WAVE".equals(ascii(bytes, dataOffset + 8, 4))) {
            return readWav(bytes, dataOffset, dataOffset + dataSize, true, depth + 1);
        }
        return new WavData(bytes, dataOffset, dataSize, channels, sampleRate);
    }

    private static byte[] toSourceRateMonoPcm(WavData wav) {
        int sourceFrameBytes = wav.channels * 2;
        int sourceFrames = wav.dataSize / sourceFrameBytes;
        if (wav.channels == 1) {
            byte[] out = new byte[sourceFrames * 2];
            System.arraycopy(wav.bytes, wav.dataOffset, out, 0, out.length);
            return limitPcmPeak(out, MTK_PROC_TARGET_PEAK);
        }
        byte[] out = new byte[sourceFrames * 2];
        for (int i = 0; i < sourceFrames; i++) {
            int sample = monoSampleAt(wav, i);
            out[i * 2] = (byte) (sample & 0xff);
            out[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return limitPcmPeak(out, MTK_PROC_TARGET_PEAK);
    }

    private static byte[] limitPcmPeak(byte[] pcm, int targetPeak) {
        int peak = pcmPeak(pcm);
        if (peak <= targetPeak || peak <= 0) {
            return pcm;
        }
        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) le16(pcm, i);
            int scaled = Math.round(sample * targetPeak / (float) peak);
            if (scaled > Short.MAX_VALUE) {
                scaled = Short.MAX_VALUE;
            } else if (scaled < Short.MIN_VALUE) {
                scaled = Short.MIN_VALUE;
            }
            out[i] = (byte) (scaled & 0xff);
            out[i + 1] = (byte) ((scaled >>> 8) & 0xff);
        }
        return out;
    }

    private static int pcmPeak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) le16(pcm, i);
            int value = Math.abs(sample);
            if (value > peak) {
                peak = value;
            }
        }
        return peak;
    }

    private static int monoSampleAt(WavData wav, int frame) {
        int offset = wav.dataOffset + frame * wav.channels * 2;
        int sum = 0;
        for (int channel = 0; channel < wav.channels; channel++) {
            sum += (short) le16(wav.bytes, offset + channel * 2);
        }
        return sum / wav.channels;
    }

    private static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > 32L * 1024L * 1024L) {
            throw new IOException("bad wav size: " + length);
        }
        byte[] bytes = new byte[(int) length];
        int offset = 0;
        try (FileInputStream stream = new FileInputStream(file)) {
            while (offset < bytes.length) {
                int read = stream.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        if (offset != bytes.length) {
            throw new IOException("short read: " + offset + "/" + bytes.length);
        }
        return bytes;
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

    private static ShellResult runRoot(String command, int timeoutMs) {
        String[] candidates = {"/debug_ramdisk/su", "/sbin/su", "/system/bin/su", "/system/xbin/su", "su"};
        Exception last = null;
        for (String binary : candidates) {
            if (!"su".equals(binary) && !new File(binary).exists()) {
                continue;
            }
            Process process = null;
            long start = SystemClock.uptimeMillis();
            try {
                process = new ProcessBuilder(binary, "-c", command).redirectErrorStream(true).start();
                boolean finished = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
                long elapsed = SystemClock.uptimeMillis() - start;
                if (!finished) {
                    process.destroy();
                    process.waitFor(500, TimeUnit.MILLISECONDS);
                }
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (out.length() < 2200) {
                            out.append(line).append('\n');
                        }
                    }
                }
                if (!finished) {
                    return new ShellResult(124, out.toString(), elapsed);
                }
                return new ShellResult(process.exitValue(), out.toString(), elapsed);
            } catch (Exception e) {
                last = e;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        String message = last == null ? "su not found" : last.getClass().getSimpleName() + ": " + last.getMessage();
        return new ShellResult(127, message, 0);
    }

    private static String shellQuote(String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= 600 ? oneLine : oneLine.substring(0, 600);
    }

    private static final class ProcAudio {
        final File file;
        final int durationMs;
        final int sampleRate;
        final int controlRate;

        ProcAudio(File file, int durationMs, int sampleRate, int controlRate) {
            this.file = file;
            this.durationMs = durationMs;
            this.sampleRate = sampleRate;
            this.controlRate = controlRate;
        }
    }

    private static final class WavData {
        final byte[] bytes;
        final int dataOffset;
        final int dataSize;
        final int channels;
        final int sampleRate;

        WavData(byte[] bytes, int dataOffset, int dataSize, int channels, int sampleRate) {
            this.bytes = bytes;
            this.dataOffset = dataOffset;
            this.dataSize = dataSize;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }
    }

    private static final class ShellResult {
        final int code;
        final String output;
        final long elapsedMs;

        ShellResult(int code, String output, long elapsedMs) {
            this.code = code;
            this.output = output;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class Helper {
        final String name;
        final String path;

        Helper(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }
}
