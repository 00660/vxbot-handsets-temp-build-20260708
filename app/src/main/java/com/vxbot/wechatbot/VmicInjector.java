package com.vxbot.wechatbot;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class VmicInjector {
    private static final String VMIC_PLAY = "/vendor/bin/vmic_play";
    private static final String VMIC_PLAY_MODULE = "/data/adb/modules/mido_vmic_hal/system/vendor/bin/vmic_play";
    private static final String VMIC_PUSH = "/vendor/bin/vmic_push";
    private static final String VMIC_PUSH_INSTANTNOODLEP_MODULE =
            "/data/adb/modules/instantnoodlep_vmic/system/vendor/bin/vmic_push";
    private static final String MTK_VIRTUAL_MIC_CTL = "/proc/mtk_virtual_mic_ctl";
    private static final String MTK_VIRTUAL_MIC_PCM = "/proc/mtk_virtual_mic_pcm";
    private static final String MTK_VIRTUAL_MIC_STATUS = "/proc/mtk_virtual_mic_status";
    private static final int MTK_PROC_TARGET_PEAK = 10000;
    private static final int MTK_PROC_STATUS_POLL_MS = 100;
    private static final int MTK_PROC_TAIL_MS = 800;
    private static final int MTK_PROC_CONSUMPTION_START_TIMEOUT_MS = 4000;
    private static final int MAX_DECODED_PCM_BYTES = 64 * 1024 * 1024;
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
            if (!procHelper.path.isEmpty()) {
                return injectMtkProc(context, file, timeoutMs, reason);
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

    static void resetMtkState(Context context, String reason) {
        synchronized (INJECT_LOCK) {
            Helper helper = findMtkProcHelper(context);
            if (helper.path.isEmpty()) {
                return;
            }
            String ctl = shellQuote(MTK_VIRTUAL_MIC_CTL);
            ShellResult result = runRoot("echo enable 0 > " + ctl + " 2>/dev/null || true; "
                    + "echo clear > " + ctl + " 2>/dev/null || true", 4000);
            BotLog.write(context, result.code == 0 ? "INFO" : "WARN", "vmic.reset",
                    "reason=" + reason + " code=" + result.code + " out=" + trim(result.output));
        }
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
                    + "echo interp 1 > " + ctl + "; "
                    + "echo copy 1 > " + ctl + "; "
                    + "echo dma 0 > " + ctl + "; "
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
            int waitMs = Math.max(1000, timeoutMs);
            BotLog.i(context, "vmic.inject.proc.start", "reason=" + reason
                    + " source=" + file.getAbsolutePath()
                    + " pcm=" + audio.file.getAbsolutePath()
                    + " bytes=" + audio.file.length()
                    + " rate=" + audio.sampleRate
                    + " controlRate=" + audio.controlRate
                    + " durationMs=" + audio.durationMs
                    + " waitMs=" + waitMs
                    + " out=" + trim(started.output));
            ProcConsumption consumption = waitForMtkProcConsumption(status, waitMs);
            if (consumption.completed) {
                SystemClock.sleep(MTK_PROC_TAIL_MS);
            } else {
                String event = consumption.sourceReadFrame > 0
                        ? "vmic.inject.proc.consume.timeout"
                        : "vmic.inject.proc.consume.not_started";
                BotLog.w(context, event, "reason=" + reason
                        + " sourceFrames=" + consumption.sourceFrames
                        + " sourceReadFrame=" + consumption.sourceReadFrame
                        + " waitMs=" + waitMs
                        + " out=" + trim(consumption.status));
            }
            ShellResult stopped = runRoot("echo enable 0 > " + ctl + " 2>/dev/null || true; "
                    + "echo clear > " + ctl + " 2>/dev/null || true; cat "
                    + status + " 2>/dev/null || true", 4000);
            long elapsed = SystemClock.uptimeMillis() - start;
            BotLog.i(context, "vmic.inject.done", "reason=" + reason
                    + " helper=mtk_virtual_mic_proc"
                    + " file=" + file.getAbsolutePath()
                    + " size=" + file.length()
                    + " pcmRate=" + audio.sampleRate
                    + " controlRate=" + audio.controlRate
                    + " pcmBytes=" + audio.file.length()
                    + " completed=" + consumption.completed
                    + " sourceFrames=" + consumption.sourceFrames
                    + " sourceReadFrame=" + consumption.sourceReadFrame
                    + " elapsedMs=" + elapsed
                    + " stopCode=" + stopped.code
                    + " out=" + trim(stopped.output));
            return consumption.completed;
        } catch (Exception e) {
            runRoot("echo enable 0 > " + shellQuote(MTK_VIRTUAL_MIC_CTL) + " 2>/dev/null || true; "
                    + "echo clear > " + shellQuote(MTK_VIRTUAL_MIC_CTL) + " 2>/dev/null || true", 4000);
            BotLog.w(context, "vmic.inject.proc.error", "reason=" + reason + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (audio != null && audio.file != null && audio.file.delete()) {
                BotLog.i(context, "vmic.inject.proc.deleted", audio.file.getAbsolutePath());
            }
        }
    }

    private static ProcConsumption waitForMtkProcConsumption(String statusPath, int timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(1, timeoutMs);
        long startDeadline = Math.min(deadline, SystemClock.uptimeMillis() + MTK_PROC_CONSUMPTION_START_TIMEOUT_MS);
        ProcConsumption last = new ProcConsumption(false, -1, -1, "");
        boolean consumptionStarted = false;
        while (SystemClock.uptimeMillis() < deadline) {
            ShellResult status = runRoot("cat " + statusPath + " 2>/dev/null || true", 2000);
            long sourceFrames = statusValue(status.output, "source_frames");
            long sourceReadFrame = statusValue(status.output, "source_read_frame");
            if (sourceFrames > 0 && sourceReadFrame >= 0) {
                last = new ProcConsumption(sourceReadFrame >= sourceFrames,
                        sourceFrames, sourceReadFrame, status.output);
                if (last.completed) {
                    return last;
                }
                consumptionStarted |= sourceReadFrame > 0;
            }
            if (!consumptionStarted && SystemClock.uptimeMillis() >= startDeadline) {
                return last;
            }
            long remainingMs = deadline - SystemClock.uptimeMillis();
            if (remainingMs > 0) {
                SystemClock.sleep(Math.min(MTK_PROC_STATUS_POLL_MS, remainingMs));
            }
        }
        return last;
    }

    private static long statusValue(String status, String key) {
        if (status == null || status.isEmpty()) {
            return -1;
        }
        String prefix = key + "=";
        for (String field : status.split("\\s+")) {
            if (!field.startsWith(prefix)) {
                continue;
            }
            try {
                return Long.parseLong(field.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static ProcAudio prepareMtkProcAudio(Context context, File sourceFile) throws IOException {
        PcmData source;
        if (isWav(sourceFile)) {
            WavData wav = readWav(sourceFile);
            source = new PcmData(toMonoPcm(wav), wav.sampleRate, 1, "wav");
        } else {
            source = decodeCompressedAudio(sourceFile);
        }
        int controlRate = mtkProcControlRate(source.sampleRate);
        byte[] pcm = source.channels == 1 ? source.bytes : toMonoPcm(source.bytes, source.channels);
        limitPcmPeakInPlace(pcm, MTK_PROC_TARGET_PEAK);
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
        int durationMs = Math.max(1, Math.round(frames * 1000f / controlRate));
        int pcmPeak = pcmPeak(pcm);
        BotLog.i(context, "vmic.inject.proc.audio", "sourceType=" + source.type
                + " sourceRate=" + source.sampleRate
                + " controlRate=" + controlRate
                + " channels=" + source.channels
                + " dataBytes=" + source.bytes.length
                + " pcmBytes=" + pcm.length
                + " pcmPeak=" + pcmPeak
                + " resampled=false"
                + " durationMs=" + durationMs);
        return new ProcAudio(out, durationMs, source.sampleRate, controlRate);
    }

    private static int mtkProcControlRate(int sampleRate) {
        return Math.max(8000, Math.min(192000, sampleRate));
    }

    private static WavData readWav(File file) throws IOException {
        byte[] bytes = readAll(file);
        return readWav(bytes, 0, bytes.length, false, 0);
    }

    private static boolean isWav(File file) throws IOException {
        byte[] header = new byte[12];
        int offset = 0;
        try (FileInputStream stream = new FileInputStream(file)) {
            while (offset < header.length) {
                int read = stream.read(header, offset, header.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return offset == header.length
                && "RIFF".equals(ascii(header, 0, 4))
                && "WAVE".equals(ascii(header, 8, 4));
    }

    private static PcmData decodeCompressedAudio(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat inputFormat = null;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String candidateMime = candidate.getString(MediaFormat.KEY_MIME);
                if (candidateMime != null && candidateMime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    inputFormat = candidate;
                    mime = candidateMime;
                    break;
                }
            }
            if (inputFormat == null || mime == null) {
                throw new IOException("compressed audio track not found");
            }

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            long deadline = SystemClock.uptimeMillis() + 60000;
            while (!outputEnded && SystemClock.uptimeMillis() < deadline) {
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) {
                            throw new IOException("decoder input buffer unavailable");
                        }
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    Math.max(0, extractor.getSampleTime()), extractor.getSampleFlags());
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int encoding = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            : AudioFormat.ENCODING_PCM_16BIT;
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                        throw new IOException("unsupported decoded pcm encoding: " + encoding);
                    }
                } else if (outputIndex >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (pcm.size() + info.size > MAX_DECODED_PCM_BYTES) {
                            throw new IOException("decoded pcm too large");
                        }
                        ByteBuffer output = codec.getOutputBuffer(outputIndex);
                        if (output == null) {
                            throw new IOException("decoder output buffer unavailable");
                        }
                        byte[] chunk = new byte[info.size];
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        output.get(chunk);
                        pcm.write(chunk, 0, chunk.length);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (!outputEnded) {
                throw new IOException("audio decoder timeout");
            }
            if (sampleRate <= 0 || channels <= 0 || pcm.size() < channels * 2) {
                throw new IOException("empty decoded pcm rate=" + sampleRate + " channels=" + channels);
            }
            return new PcmData(pcm.toByteArray(), sampleRate, channels, mime);
        } catch (RuntimeException e) {
            throw new IOException("audio decode failed: " + e.getMessage(), e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
            }
        }
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

    private static byte[] toMonoPcm(WavData wav) {
        int sourceFrameBytes = wav.channels * 2;
        int sourceFrames = wav.dataSize / sourceFrameBytes;
        if (wav.channels == 1) {
            byte[] out = new byte[sourceFrames * 2];
            System.arraycopy(wav.bytes, wav.dataOffset, out, 0, out.length);
            return out;
        }
        byte[] out = new byte[sourceFrames * 2];
        for (int i = 0; i < sourceFrames; i++) {
            int sample = monoSampleAt(wav, i);
            out[i * 2] = (byte) (sample & 0xff);
            out[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return out;
    }

    private static byte[] toMonoPcm(byte[] pcm, int channels) throws IOException {
        if (channels < 1 || pcm.length < channels * 2) {
            throw new IOException("invalid decoded pcm channels=" + channels + " bytes=" + pcm.length);
        }
        int sourceFrames = pcm.length / (channels * 2);
        byte[] out = new byte[sourceFrames * 2];
        for (int frame = 0; frame < sourceFrames; frame++) {
            int offset = frame * channels * 2;
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += (short) le16(pcm, offset + channel * 2);
            }
            int sample = sum / channels;
            out[frame * 2] = (byte) (sample & 0xff);
            out[frame * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return out;
    }

    private static void limitPcmPeakInPlace(byte[] pcm, int targetPeak) {
        int peak = pcmPeak(pcm);
        if (peak <= targetPeak || peak <= 0) {
            return;
        }
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) le16(pcm, i);
            int scaled = Math.round(sample * targetPeak / (float) peak);
            if (scaled > Short.MAX_VALUE) {
                scaled = Short.MAX_VALUE;
            } else if (scaled < Short.MIN_VALUE) {
                scaled = Short.MIN_VALUE;
            }
            pcm[i] = (byte) (scaled & 0xff);
            pcm[i + 1] = (byte) ((scaled >>> 8) & 0xff);
        }
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
        RootShellSession.Result result = RootShellSession.execute(command, timeoutMs);
        return new ShellResult(result.code, result.output, result.elapsedMs);
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

    private static final class ProcConsumption {
        final boolean completed;
        final long sourceFrames;
        final long sourceReadFrame;
        final String status;

        ProcConsumption(boolean completed, long sourceFrames, long sourceReadFrame, String status) {
            this.completed = completed;
            this.sourceFrames = sourceFrames;
            this.sourceReadFrame = sourceReadFrame;
            this.status = status;
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

    private static final class PcmData {
        final byte[] bytes;
        final int sampleRate;
        final int channels;
        final String type;

        PcmData(byte[] bytes, int sampleRate, int channels, String type) {
            this.bytes = bytes;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.type = type;
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
