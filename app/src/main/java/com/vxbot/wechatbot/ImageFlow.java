package com.vxbot.wechatbot;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ImageFlow {
    private static final Random RANDOM = new Random();
    private static final long IMAGE_CONTEXT_TTL_MS = 15 * 60 * 1000L;
    private static final Map<String, ImageMemory> IMAGE_CONTEXTS = new HashMap<>();

    public boolean handle(Context context, BotConfig config, WxMessage message, SessionStore store, WechatDriver driver) {
        ExecutorService ttsPreparer = null;
        Future<VoiceReply.PreparedVoice> afterVoiceFuture = null;
        VoiceReply.PreparedVoice usedAfterVoice = null;
        try {
            String request = MessageRouter.stripBotMention(message.text, config);
            ImageMemory previous = imageMemory(message.sessionName);
            boolean explicitIncomingReference = looksIncomingReference(request);
            boolean revision = previous != null && !explicitIncomingReference && looksRevision(request);
            boolean incomingReference = explicitIncomingReference;
            boolean coolStyle = looksCoolStyle(request);
            boolean selfie = revision && previous != null ? "persona_selfie".equals(previous.kind) : looksSelfie(request);
            boolean hasPersona = hasFile(config.selfieReferencePhotoPath());
            boolean hasStyle = coolStyle && (hasFile(config.styleReferencePath) || hasRawCoolStyleReference(context));
            List<ReferenceImage> sourceImages = new ArrayList<>();
            if (incomingReference) {
                ReferenceImage ref = captureLatestWechatImageReference(context, config, "image");
                if (ref == null) {
                    String missing = "我没拿到刚才那张图，你把图发出来再@我说怎么改。";
                    driver.sendTextInCurrentChat(context, config, message.sessionName, missing, false);
                    store.rememberBot(message.sessionName, config.primaryBotName(), missing);
                    BotLog.w(context, "image.reference.missing", "图生图没有找到可用群聊参考图 sessionName=" + message.sessionName);
                    return true;
                }
                sourceImages.add(ref);
                BotLog.i(context, "image.reference.attached", "已附带群聊引用图给 3002 图片接口 bytes=" + ref.bytes.length);
            }
            if (revision && previous != null) {
                ReferenceImage ref = previous.asReference();
                if (ref != null) {
                    sourceImages.add(ref);
                    BotLog.i(context, "image.revision.reference", "连续改图已附带上一张生成图 bytes=" + ref.bytes.length);
                }
            }
            String warmup = revision
                    ? random(REVISION_WARMUP_LINES)
                    : (incomingReference ? random(REFERENCE_WARMUP_LINES) : random(WARMUP_LINES));
            String after = revision ? random(REVISION_AFTER_LINES) : random(AFTER_LINES);
            if (!after.isEmpty() && config.enableImageAfterText && config.imageAfterAsVoice) {
                String afterText = after;
                ttsPreparer = Executors.newSingleThreadExecutor();
                afterVoiceFuture = ttsPreparer.submit(() -> VoiceReply.prepare(context, config, afterText, "image-after-voice"));
                BotLog.i(context, "image.after.tts.prepare.queue", "后置话术提前生成 TTS length=" + afterText.length());
            }
            if (!warmup.isEmpty() && config.enableImageWarmupText) {
                if (config.imageWarmupAsVoice) {
                    VoiceReply.sendInCurrentChat(context, config, driver, message.sessionName, warmup, "image-warmup-voice", false);
                    store.rememberBot(message.sessionName, config.primaryBotName(), warmup);
                } else {
                    driver.sendTextInCurrentChat(context, config, message.sessionName, warmup, true);
                    store.rememberBot(message.sessionName, config.primaryBotName(), warmup);
                }
            }
            String prompt = buildImagePrompt(config, message, request, coolStyle, selfie, hasPersona, hasStyle, revision, incomingReference, previous);
            BotLog.i(context, "image.api.start", "开始请求 3002 图片接口 base=" + config.imageEndpoint
                    + " model=" + config.imageModel + " size=" + config.imageSize
                    + " revision=" + revision + " incomingReference=" + incomingReference
                    + " sourceImages=" + sourceImages.size());
            String image = requestImage(context, config, prompt, coolStyle, selfie, sourceImages);
            File file = saveImage(context, image);
            rememberImageMemory(message.sessionName, request, selfie ? "persona_selfie" : "general", file, image);
            BotLog.i(context, "image.save", "图片已保存 path=" + file.getAbsolutePath());
            boolean shared = shareImageTo(context, config, file, message.sessionName);
            if (!shared) {
                if (config != null && config.dropImageTaskOnError) {
                    BotLog.w(context, "image.share.discard", "图片分享失败，按配置丢弃任务并退后台 sessionName=" + message.sessionName);
                    driver.leaveWechatIfForeground(context, "image-share-failed-drop");
                }
                return false;
            }
            if (!after.isEmpty() && config.enableImageAfterText) {
                if (config.imageAfterAsVoice) {
                    usedAfterVoice = VoiceReply.awaitPrepared(context, afterVoiceFuture, "image-after-voice");
                    VoiceReply.sendPreparedInCurrentChat(context, config, driver, message.sessionName, usedAfterVoice, after, "image-after-voice", true);
                    store.rememberBot(message.sessionName, config.primaryBotName(), after);
                } else {
                    driver.sendTextInCurrentChat(context, config, message.sessionName, after, false);
                    store.rememberBot(message.sessionName, config.primaryBotName(), after);
                }
            }
            return true;
        } catch (Exception e) {
            BotLog.e(context, "image.flow.error", "图片流程异常: " + e.getMessage());
            if (config != null && config.dropImageTaskOnError) {
                BotLog.e(context, "image.flow.discard", "图片流程失败，按配置丢弃任务并退后台: " + e.getMessage());
                try {
                    driver.leaveWechatIfForeground(context, "image-flow-error-drop");
                } catch (Exception ignored) {
                }
                return false;
            }
            try {
                driver.sendTextInCurrentChat(context, config, message.sessionName, "图片这边卡住了，等我缓一下再来。", false);
            } catch (Exception ignored) {
            }
            return false;
        } finally {
            if (afterVoiceFuture != null && usedAfterVoice == null) {
                if (afterVoiceFuture.isDone()) {
                    VoiceReply.cleanupPrepared(context, VoiceReply.awaitPrepared(context, afterVoiceFuture, "image-after-unused"),
                            "image-after-unused");
                } else {
                    afterVoiceFuture.cancel(true);
                }
            }
            if (ttsPreparer != null) {
                ttsPreparer.shutdownNow();
            }
        }
    }

    public String analyzeCurrentScreen(Context context, BotConfig config, WxMessage message, List<String> history) throws Exception {
        ReferenceImage ref = captureLatestWechatImageReference(context, config, "vision");
        if (ref == null) {
            BotLog.w(context, "vision.reference.missing", "图片分析没有拿到群聊引用图 sessionName=" + message.sessionName);
            return "我没拿到刚才那张图，你把图发出来再让我分析。";
        }
        String dataUrl = "data:image/png;base64," + Base64.encodeToString(ref.bytes, Base64.NO_WRAP);
        BotLog.i(context, "vision.reference.attached", "已点开并裁剪群聊引用图上传给文字上游 bytes=" + ref.bytes.length);
        return new ChatClient().requestVisionReply(context, config, message, history, dataUrl);
    }

    public CapturedReference captureLatestWechatImage(Context context, BotConfig config, String label) {
        ReferenceImage ref = captureLatestWechatImageReference(context, config, label);
        return ref == null ? null : new CapturedReference(ref.filename, ref.bytes);
    }

    private String buildImagePrompt(BotConfig config, WxMessage message, String request, boolean cool, boolean selfie, boolean hasPersona, boolean hasStyle,
                                    boolean revision, boolean incomingReference, ImageMemory previous) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("图片应像群聊里自然发出的照片，真实、清晰、生活化，不要文字、水印、Logo、边框。\n");
        prompt.append("避免夸张网红滤镜和明显 AI 感。\n");
        prompt.append("图片分辨率目标：").append(config.imageSize).append("。\n");
        if (revision) {
            prompt.append("这是连续改图/重拍请求：必须以上一张机器人刚生成的图片为参考，保留核心人物或主题，按用户反馈明显换场景、姿势、构图、光线或背景；不要从零另起炉灶。\n");
            if (selfie) {
                prompt.append("连续自拍换场景时，不要只把原室内图换个角度；必须真实换到另一个生活地点，例如客厅、厨房、阳台、马路边、野外小路、商场、电梯口、咖啡店、河边、公园、便利店门口等。\n");
            }
            if (previous != null && previous.originalText != null && !previous.originalText.isEmpty()) {
                prompt.append("上一张图片原始请求：").append(previous.originalText).append("\n");
            }
        }
        if (incomingReference) {
            prompt.append("这是基于群聊引用图的图生图请求：参考图来自当前微信群里用户引用/刚发出的图片，必须按用户要求修改，不要错用机器人上一张图。\n");
        }
        if (hasPersona) {
            prompt.append("本次附带人物形象参考图：只参考人物面貌、五官、发型气质和身份一致性；不要复制参考图背景、姿势、构图或原衣服，必须按用户请求重新生成一张新的自然自拍。\n");
        }
        if (cool) {
            prompt.append("清凉/泳装/比基尼请求：只表现清爽明亮的夏日质感、泳装或比基尼穿搭氛围、自拍视角、自然光线和色彩；不要照搬固定海滩背景，必须按当前请求换一个新的地点、场景、姿势或构图。\n");
            if (hasStyle) {
                prompt.append("本次附带风格底图：只参考清爽明亮的夏日质感、穿搭氛围、自拍视角、光线和色彩；不要复制底图人物身份，不要照搬背景、建筑、椰树、海滩或双人构图。\n");
            }
        }
        if (selfie) {
            prompt.append("自拍人物方向：眼神像蜜妮跟对象见面时的样子，青涩、略带羞涩、脸红红、自然不好意思，日常自拍，不要强制全身照。\n");
            prompt.append(currentChinaSeasonOutfitRule()).append("\n");
            prompt.append(currentChinaTimeSceneRule()).append("\n");
            prompt.append("人物参考图只锁定同一张脸、五官、发型气质和身份一致性；参考图里的衣服、毛衣纹理、针织材质、背景、姿势、手势、镜头角度都不要继承，除非当前请求明确要求。\n");
            prompt.append("自拍表情和动作：表情要自然随机，可以是开心、害羞、微恼、困倦、酷一点、emo、偷笑、发呆、认真看镜头等；动作像临时自拍，不要照搬人物参考图、风格底图或上一张图。\n");
            prompt.append(random(SELFIE_PERSONA_LINES)).append("\n");
            prompt.append(random(SELFIE_STYLE_LINES)).append("\n");
            prompt.append(selfieSceneRule()).append("\n");
        }
        String userRequest = request.isEmpty() ? message.text : request;
        if (shouldAppendUserRequest(userRequest, cool, selfie, revision, incomingReference)) {
            prompt.append("用户需求：").append(userRequest.trim()).append("\n");
        }
        prompt.append("最终只生成图片，不要在图片里出现任何说明文字。");
        return prompt.toString();
    }

    private String requestImage(Context context, BotConfig config, String prompt, boolean coolStyle, boolean selfie, List<ReferenceImage> sourceImages) throws Exception {
        List<ReferenceImage> refs = new ArrayList<>();
        if (sourceImages != null) {
            refs.addAll(sourceImages);
        }
        if (selfie) {
            ReferenceImage persona = loadFileReference(config.selfieReferencePhotoPath(), "persona_reference.png");
            if (persona != null) {
                refs.add(persona);
                BotLog.i(context, "image.persona.reference", "自拍请求已附带人物面貌参考图 bytes=" + persona.bytes.length
                        + " exMode=" + config.enableExMode);
            }
        }
        if (coolStyle) {
            ReferenceImage style = loadFileReference(config.styleReferencePath, "cool_style_reference.png");
            if (style != null) {
                refs.add(style);
                BotLog.i(context, "image.cool.reference", "清凉/比基尼请求已附带用户上传风格底图 bytes=" + style.bytes.length);
            } else {
                byte[] coolRef = loadCoolStyleReference(context);
                if (coolRef != null && coolRef.length > 0) {
                    refs.add(new ReferenceImage("cool_style_reference.png", coolRef));
                    BotLog.i(context, "image.cool.reference", "清凉/比基尼请求已附带 APK 内置参考图 bytes=" + coolRef.length);
                }
            }
        }
        if (!refs.isEmpty()) {
            return requestEditedImage(config, prompt, refs);
        }
        if (coolStyle) {
            BotLog.w(context, "image.cool.ref.missing", "清凉/比基尼请求未找到上传风格底图或 APK raw/cool_style_reference，降级文生图");
        }
        return requestGeneratedImage(config, prompt);
    }

    private String requestGeneratedImage(BotConfig config, String prompt) throws Exception {
        String endpoint = config.imageEndpoint.replaceAll("/+$", "") + "/images/generations";
        JSONObject payload = new JSONObject();
        payload.put("model", config.imageModel);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("size", normalizeSize(config.imageSize));
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(config.imageTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (config.imageApiKey != null && !config.imageApiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.imageApiKey.trim());
        }
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("图片上游 HTTP " + code + " " + trim(body));
        }
        return parseImage(body, config.imageEndpoint);
    }

    private String requestEditedImage(BotConfig config, String prompt, List<ReferenceImage> images) throws Exception {
        String endpoint = config.imageEndpoint.replaceAll("/+$", "") + "/images/edits";
        String boundary = "vxbot-apk-" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(config.imageTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (config.imageApiKey != null && !config.imageApiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.imageApiKey.trim());
        }
        try (OutputStream out = conn.getOutputStream()) {
            writeFormField(out, boundary, "model", config.imageModel);
            writeFormField(out, boundary, "prompt", prompt);
            writeFormField(out, boundary, "n", "1");
            writeFormField(out, boundary, "size", normalizeSize(config.imageSize));
            writeFormField(out, boundary, "response_format", "b64_json");
            for (ReferenceImage image : images) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"image\"; filename=\"" + image.filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                out.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.write(image.bytes);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("图片编辑上游 HTTP " + code + " " + trim(body));
        }
        return parseImage(body, config.imageEndpoint);
    }

    private static void writeFormField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] loadCoolStyleReference(Context context) {
        int id = context.getResources().getIdentifier("cool_style_reference", "raw", context.getPackageName());
        if (id == 0) {
            return null;
        }
        try (InputStream in = context.getResources().openRawResource(id)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasRawCoolStyleReference(Context context) {
        return context.getResources().getIdentifier("cool_style_reference", "raw", context.getPackageName()) != 0;
    }

    private static ReferenceImage loadFileReference(String path, String filename) throws Exception {
        if (!hasFile(path)) {
            return null;
        }
        File file = new File(path);
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return new ReferenceImage(filename, out.toByteArray());
        }
    }

    private static boolean hasFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        File file = new File(path);
        return file.exists() && file.length() > 0;
    }

    private File saveImage(Context context, String image) throws Exception {
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "generated");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建图片目录 " + dir.getAbsolutePath());
        }
        File file = new File(dir, "vxbot_ai_image_" + System.currentTimeMillis() + ".png");
        byte[] bytes;
        if (image.startsWith("data:image/")) {
            int comma = image.indexOf(',');
            if (comma < 0) {
                throw new IllegalStateException("data url 格式错误");
            }
            bytes = Base64.decode(image.substring(comma + 1), Base64.DEFAULT);
        } else if (image.startsWith("http://") || image.startsWith("https://")) {
            bytes = download(image);
        } else {
            bytes = Base64.decode(image, Base64.DEFAULT);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private boolean shareImageTo(Context context, BotConfig config, File file, String sessionName) {
        long start = SystemClock.uptimeMillis();
        HsClient hs = new HsClient(config.hsPort);
        ShareAsset asset = null;
        try {
            prepareShareStart(context, hs, "ImageShare");
            asset = createShareAsset(context, file);
            boolean selectReady = openWechatShareSelectCompatible(context, config, hs, asset, sessionName, "ImageShare");
            boolean ok = selectReady
                    && clickShareTargetByOcr(context, config, hs, sessionName)
                    && clickShareSendByOcr(context, config, hs, sessionName, "ImageShare");
            if (ok) {
                ok = finishShareAfterSend(context, config, hs, "ImageShare");
            }
            BotLog.write(context, ok ? "SUCCESS" : "ERROR", "image.share.done",
                    (ok ? "图片已分享进群" : "图片分享失败")
                            + " sessionName=" + sessionName
                            + " costMs=" + (SystemClock.uptimeMillis() - start));
            if (!ok) {
                closeShareRemainder(context, hs, "ImageShare");
            }
            return ok;
        } catch (Exception e) {
            BotLog.e(context, "image.share.error", "图片分享流程异常 sessionName=" + sessionName + " error=" + e.getMessage());
            closeShareRemainder(context, hs, "ImageShare");
            return false;
        } finally {
            cleanupShareAsset(context, asset);
        }
    }

    public boolean debugOpenImageShare(Context context, BotConfig config, File file) {
        HsClient hs = new HsClient(config.hsPort);
        ShareAsset asset = null;
        try {
            prepareShareStart(context, hs, "ImageShareDebug");
            asset = createShareAsset(context, file);
            boolean ok = openWechatShareSelectCompatible(context, config, hs, asset,
                    file == null ? "" : file.getName(), "ImageShareDebug");
            BotLog.write(context, ok ? "SUCCESS" : "ERROR", "image.share.debug.open",
                    (ok ? "微信图片分享选择页已打开" : "微信图片分享选择页未打开")
                            + " path=" + (file == null ? "" : file.getAbsolutePath()));
            return ok;
        } catch (Exception e) {
            BotLog.e(context, "image.share.debug.error", "打开图片分享选择页异常 error=" + e.getMessage());
            return false;
        } finally {
            cleanupShareAsset(context, asset);
        }
    }

    public boolean shareExistingImage(Context context, BotConfig config, File file, String sessionName) {
        return shareImageTo(context, config, file, sessionName);
    }

    private boolean openWechatShareSelectCompatible(Context context, BotConfig config, HsClient hs, ShareAsset asset,
                                                    String sessionName, String prefix) throws Exception {
        if (asset == null) {
            return false;
        }
        if (tryOpenWechatShareSelect(context, config, hs, asset, asset.contentUri, true, prefix,
                sessionName, "content-direct", 2)) {
            return true;
        }
        if (asset.fileUri != null && tryOpenWechatShareSelect(context, config, hs, asset, asset.fileUri, true, prefix,
                sessionName, "file-direct", 2)) {
            return true;
        }
        if (tryOpenWechatShareSelect(context, config, hs, asset, asset.contentUri, false, prefix,
                sessionName, "content-package", 3)) {
            return true;
        }
        return asset.fileUri != null && tryOpenWechatShareSelect(context, config, hs, asset, asset.fileUri, false, prefix,
                sessionName, "file-package", 3);
    }

    private boolean tryOpenWechatShareSelect(Context context, BotConfig config, HsClient hs, ShareAsset asset, Uri uri,
                                             boolean direct, String prefix, String sessionName,
                                             String strategy, int attempts) throws Exception {
        if (uri == null) {
            return false;
        }
        closeShareRemainder(context, hs, prefix + "-" + strategy);
        if (!startShareIntent(context, asset, uri, prefix, direct, strategy)) {
            return false;
        }
        boolean ready = openWechatSendFriendEntry(context, config, hs, attempts);
        BotLog.write(context, ready ? "SUCCESS" : "WARN", "image.share.strategy",
                "strategy=" + strategy + " direct=" + direct + " ready=" + ready
                        + " sessionName=" + sessionName + " uriScheme=" + uri.getScheme());
        if (!ready) {
            closeShareRemainder(context, hs, prefix + "-" + strategy);
        }
        return ready;
    }

    private boolean startShareIntent(Context context, ShareAsset asset, Uri uri,
                                     String prefix, boolean direct, String strategy) {
        try {
            if (asset == null || uri == null) {
                throw new IllegalStateException("share asset missing");
            }
            Intent intent = new Intent(context, ShareProxyActivity.class);
            if ("file".equals(uri.getScheme())) {
                intent.putExtra(ShareProxyActivity.EXTRA_FILE_PATH, uri.getPath());
            } else {
                intent.putExtra(ShareProxyActivity.EXTRA_URI, uri);
            }
            intent.putExtra(ShareProxyActivity.EXTRA_MIME, asset.mime);
            intent.putExtra(ShareProxyActivity.EXTRA_FILE_NAME, asset.fileName);
            intent.putExtra(ShareProxyActivity.EXTRA_DIRECT, direct);
            intent.putExtra(ShareProxyActivity.EXTRA_PREFIX, prefix + "-" + strategy);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if ("content".equals(uri.getScheme())) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.grantUriPermission("com.tencent.mm", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            context.startActivity(intent);
            BotLog.i(context, "image.share.intent.start", "启动分享代理 strategy=" + strategy
                    + " direct=" + direct + " uriScheme=" + uri.getScheme() + " mime=" + asset.mime);
            return true;
        } catch (Exception e) {
            BotLog.e(context, "image.share.intent.error", prefix + " strategy=" + strategy
                    + " direct=" + direct + " error=" + e.getMessage());
            return false;
        }
    }

    private boolean openWechatSendFriendEntry(Context context, BotConfig config, HsClient hs, int maxAttempts) throws Exception {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (waitShareSelect(context, hs, 900, shareSelectPoll(config))) {
                SystemClock.sleep(Math.max(250L, shareSelectPoll(config)));
                return true;
            }
            boolean clickedFriend = clickShareOcrItem(context, config, hs, this::matchShareFriendEntryText,
                    "发送给朋友", 0f, 1f, 0.45f, 1f);
            if (!clickedFriend) {
                BotLog.i(context, "image.share.friend.entry.missing", "未找到发送给朋友入口，可能已在微信选择页 attempt=" + attempt);
            }
            SystemClock.sleep(Math.max(900L, shareSelectPoll(config)));
            clickShareOcrItem(context, config, hs, text -> normalizeShareTargetName(text).contains("仅此一次"),
                    "仅此一次", 0f, 1f, 0f, 1f);
            if (waitShareSelect(context, hs, 5000, shareSelectPoll(config))) {
                SystemClock.sleep(Math.max(250L, shareSelectPoll(config)));
                return true;
            }
            BotLog.w(context, "image.share.select.wait.retry", "微信选择聊天页未就绪，重试 attempt=" + attempt);
        }
        dumpShareOcrItems(context, hs, "select-page-timeout");
        return false;
    }

    private ShareAsset createShareAsset(Context context, File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalStateException("file not found: " + (file == null ? "" : file.getAbsolutePath()));
        }
        String mime = shareMimeFromPath(file);
        String publicName = "vxbot_share_" + System.currentTimeMillis() + extensionForMime(mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri mediaUri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, publicName);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VXBotShare");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                mediaUri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (mediaUri == null) {
                    throw new IllegalStateException("MediaStore insert returned null");
                }
                copyFileToUri(context, file, mediaUri);
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(mediaUri, done, null, null);
                File publicFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "VXBotShare/" + publicName);
                Uri fileUri = Uri.fromFile(publicFile);
                BotLog.i(context, "image.share.public.copy", "已复制图片到公共媒体库 name=" + publicName
                        + " contentUri=" + mediaUri + " fileUri=" + fileUri);
                return new ShareAsset(mediaUri, fileUri, mime, publicName, true);
            } catch (Exception e) {
                if (mediaUri != null) {
                    try {
                        context.getContentResolver().delete(mediaUri, null, null);
                    } catch (Exception ignored) {
                    }
                }
                BotLog.w(context, "image.share.public.copy.fail", e.getMessage());
            }
        }
        Uri privateUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        BotLog.w(context, "image.share.fileprovider.fallback", "公共媒体库副本不可用，回退 FileProvider uri=" + privateUri);
        return new ShareAsset(privateUri, null, mime, file.getName(), false);
    }

    private void copyFileToUri(Context context, File file, Uri uri) throws Exception {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IllegalStateException("openOutputStream returned null");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void cleanupShareAsset(Context context, ShareAsset asset) {
        if (asset == null || !asset.publicMedia || asset.contentUri == null) {
            return;
        }
        try {
            int count = context.getContentResolver().delete(asset.contentUri, null, null);
            BotLog.i(context, "image.share.public.cleanup", "已清理公共分享临时图 count=" + count
                    + " uri=" + asset.contentUri);
        } catch (Exception e) {
            BotLog.w(context, "image.share.public.cleanup.fail", e.getMessage());
        }
    }

    private String extensionForMime(String mime) {
        if ("image/jpeg".equals(mime)) {
            return ".jpg";
        }
        if ("image/webp".equals(mime)) {
            return ".webp";
        }
        return ".png";
    }

    private boolean clickShareTargetByOcr(Context context, BotConfig config, HsClient hs, String sessionName) throws Exception {
        for (int attempt = 1; attempt <= 8; attempt++) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            OcrHelper.OcrItem recent = findShareOcrText(screen, text -> normalizeShareTargetName(text).contains("最近聊天"),
                    0f, 1f, 0f, 1f);
            int minY = recent == null ? Math.round((screen == null ? 1600 : screen.height) * 0.32f) : recent.rect.bottom + 20;
            OcrHelper.OcrItem fallback = null;
            OcrHelper.OcrItem candidate = null;
            if (screen != null) {
                for (OcrHelper.OcrItem item : screen.items) {
                    if (!matchShareTargetName(item.text, sessionName)) {
                        continue;
                    }
                    if (fallback == null) {
                        fallback = item;
                    }
                    if (item.centerY >= minY && item.centerY < screen.height - 80) {
                        candidate = item;
                        break;
                    }
                }
            }
            candidate = candidate == null ? fallback : candidate;
            if (candidate == null) {
                BotLog.w(context, "image.share.target.missing", "OCR 未找到分享目标会话 target=" + sessionName
                        + " attempt=" + attempt
                        + " snippets=" + (screen == null ? "" : screen.snippets));
                SystemClock.sleep(shareConfirmPoll(config));
                continue;
            }
            int targetX = candidate.centerX;
            hs.tap(targetX, candidate.centerY);
            BotLog.i(context, "image.share.target.found", "OCR 找到分享目标会话 target=" + sessionName
                    + " text=" + candidate.text + " x=" + targetX + " y=" + candidate.centerY
                    + " attempt=" + attempt);
            if (waitShareConfirmPage(context, config, hs, 2500)) {
                BotLog.i(context, "image.share.confirm.ready", "分享确认页已出现 target=" + sessionName);
                return true;
            }
            BotLog.w(context, "image.share.target.unconfirmed", "点击目标后未进入确认页，重试 target=" + sessionName
                    + " attempt=" + attempt);
            SystemClock.sleep(Math.max(350L, shareConfirmPoll(config)));
        }
        dumpShareOcrItems(context, hs, "target-not-found");
        return false;
    }

    private boolean waitShareConfirmPage(Context context, BotConfig config, HsClient hs, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isShareConfirmPage(context, hs)) {
                return true;
            }
            SystemClock.sleep(shareConfirmPoll(config));
        }
        return false;
    }

    private boolean clickShareSendByOcr(Context context, BotConfig config, HsClient hs, String sessionName, String prefix) throws Exception {
        for (int attempt = 1; attempt <= 8; attempt++) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (!isShareConfirmPage(screen)) {
                BotLog.w(context, "image.share.confirm.missing", "当前未识别到分享确认页，等待发送按钮 target=" + sessionName
                        + " attempt=" + attempt
                        + " snippets=" + (screen == null ? "" : screen.snippets));
                SystemClock.sleep(Math.max(350L, shareConfirmPoll(config)));
                continue;
            }
            boolean targetConfirmed = confirmPageContainsTarget(screen, sessionName);
            if (!targetConfirmed) {
                BotLog.w(context, "image.share.confirm.target_miss", "确认页目标群 OCR 未确认，继续优先尝试绿色发送按钮 target=" + sessionName
                        + " attempt=" + attempt
                        + " snippets=" + screen.snippets);
            }
            Rect greenSend = waitStableShareGreenSendButton(context, config, hs, attempt == 1 ? 1400 : 900);
            if (greenSend != null) {
                String tapResult = hs.tap(greenSend.centerX(), greenSend.centerY());
                BotLog.i(context, "image.share.send.green.tap", "取色点击分享确认页绿色发送按钮 target=" + sessionName
                        + " targetConfirmed=" + targetConfirmed
                        + " x=" + greenSend.centerX() + " y=" + greenSend.centerY()
                        + " rect=" + greenSend.flattenToString()
                        + " attempt=" + attempt
                        + " result=" + tapResult);
                if (tapResult != null && tapResult.startsWith("ERR:")) {
                    SystemClock.sleep(Math.max(450L, shareSendButtonPoll(config)));
                    continue;
                }
                SystemClock.sleep(Math.max(650L, shareSubmitPoll(config)));
                if (waitShareSubmitted(context, config, hs, prefix, 4500)) {
                    return true;
                }
                BotLog.w(context, "image.share.send.unsubmitted", "绿色发送按钮已点但确认页未消失，重试 attempt=" + attempt);
                continue;
            }
            OcrHelper.OcrItem send = findShareOcrText(screen, text -> "发送".equals(normalizeShareTargetName(text)),
                    0f, 1f, 0.35f, 1f);
            if (send == null) {
                BotLog.w(context, "image.share.send.missing", "OCR 未找到分享发送按钮 attempt=" + attempt);
                SystemClock.sleep(shareSendButtonPoll(config));
                continue;
            }
            hs.tap(send.centerX, send.centerY);
            BotLog.i(context, "image.share.send.click", "点击分享发送按钮 target=" + sessionName
                    + " x=" + send.centerX + " y=" + send.centerY
                    + " attempt=" + attempt);
            SystemClock.sleep(Math.max(350L, shareSubmitPoll(config)));
            if (waitShareSubmitted(context, config, hs, prefix, 4500)) {
                return true;
            }
            BotLog.w(context, "image.share.send.unsubmitted", "发送按钮已点但确认页未消失，重试 attempt=" + attempt);
        }
        dumpShareOcrItems(context, hs, "send-not-found");
        return false;
    }

    private Rect waitStableShareGreenSendButton(Context context, BotConfig config, HsClient hs, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        Rect previous = null;
        int stableTicks = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            Rect current = OcrHelper.findShareConfirmGreenSendButton(context, hs);
            if (current != null) {
                if (isSameShareButton(previous, current)) {
                    stableTicks++;
                } else {
                    stableTicks = 1;
                }
                previous = current;
                if (stableTicks >= 2) {
                    return current;
                }
            } else {
                stableTicks = 0;
            }
            SystemClock.sleep(shareSendButtonPoll(config));
        }
        return previous;
    }

    private boolean isSameShareButton(Rect previous, Rect current) {
        if (previous == null || current == null) {
            return false;
        }
        return Math.abs(previous.centerX() - current.centerX()) <= 10
                && Math.abs(previous.centerY() - current.centerY()) <= 10
                && Math.abs(previous.width() - current.width()) <= 18
                && Math.abs(previous.height() - current.height()) <= 18;
    }

    private boolean finishShareAfterSend(Context context, BotConfig config, HsClient hs, String prefix) {
        SystemClock.sleep(Math.max(900L, shareSubmitPoll(config)));
        if (!isWechatForeground(hs)) {
            return true;
        }
        if (isShareConfirmPage(context, hs) || isShareSelectPage(OcrHelper.inspect(context, hs))) {
            dumpShareOcrItems(context, hs, "share-page-still-visible-after-submit");
            return false;
        }
        SystemClock.sleep(Math.max(700L, shareSubmitPoll(config)));
        return !hasShareFailureHint(context, hs, prefix);
    }

    private boolean waitShareSubmitted(Context context, BotConfig config, HsClient hs, String prefix, long timeoutMs) {
        if (!waitShareConfirmGone(context, config, hs, 4500)) {
            dumpShareOcrItems(context, hs, "send-confirm-still-visible");
            return false;
        }
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        int stableTicks = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isWechatForeground(hs)) {
                SystemClock.sleep(shareSubmitPoll(config));
                continue;
            }
            if (hasShareFailureHint(context, hs, prefix)) {
                return false;
            }
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (isShareConfirmPage(screen) || isShareSelectPage(screen)) {
                stableTicks = 0;
                SystemClock.sleep(shareSubmitPoll(config));
                continue;
            }
            stableTicks++;
            if (stableTicks >= 3) {
                return true;
            }
            SystemClock.sleep(shareSubmitPoll(config));
        }
        return !hasShareFailureHint(context, hs, prefix);
    }

    private boolean waitShareConfirmGone(Context context, BotConfig config, HsClient hs, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isShareConfirmPage(context, hs)) {
                return true;
            }
            SystemClock.sleep(shareSubmitPoll(config));
        }
        return false;
    }

    private boolean hasShareFailureHint(Context context, HsClient hs, String prefix) {
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        if (screen == null) {
            return false;
        }
        for (OcrHelper.OcrItem item : screen.items) {
            String value = normalizeShareTargetName(item.text);
            if (value.matches(".*(未发送|发送失败|无法发送|重试|重新发送).*")) {
                BotLog.e(context, "image.share.failure.hint", prefix + " OCR 发现分享失败提示 text=" + item.text);
                return true;
            }
        }
        return false;
    }

    private void prepareShareStart(Context context, HsClient hs, String prefix) {
        if (!isWechatForeground(hs)) {
            return;
        }
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        if (!isShareConfirmPage(screen) && !isShareSelectPage(screen)) {
            return;
        }
        closeShareRemainder(context, hs, prefix);
    }

    private boolean closeShareRemainder(Context context, HsClient hs, String prefix) {
        for (int i = 1; i <= 3; i++) {
            if (!isWechatForeground(hs)) {
                return true;
            }
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (!isShareConfirmPage(screen) && !isShareSelectPage(screen)) {
                return true;
            }
            BotLog.i(context, "image.share.close.remainder", "关闭残留分享页 prefix=" + prefix + " attempt=" + i);
            try {
                hs.key("BACK");
            } catch (Exception e) {
                BotLog.w(context, "image.share.close.fail", e.getMessage());
                return false;
            }
            SystemClock.sleep(450);
        }
        return true;
    }

    private boolean waitShareSelect(Context context, HsClient hs, long timeoutMs, long intervalMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            if (isShareSelectPage(screen)) {
                BotLog.i(context, "image.share.select.ready", "微信选择聊天页已就绪 snippets=" + screen.snippets);
                return true;
            }
            SystemClock.sleep(intervalMs);
        }
        return false;
    }

    private boolean isShareSelectPage(OcrHelper.Screen screen) {
        return findShareOcrText(screen, this::isShareSelectText, 0f, 1f, 0f, 1f) != null;
    }

    private boolean isShareConfirmPage(Context context, HsClient hs) {
        return isShareConfirmPage(OcrHelper.inspect(context, hs));
    }

    private boolean isShareConfirmPage(OcrHelper.Screen screen) {
        if (findShareOcrText(screen, text -> normalizeShareTargetName(text).contains("发送给")
                || normalizeShareTargetName(text).contains("发送到"), 0f, 1f, 0f, 1f) != null) {
            return true;
        }
        OcrHelper.OcrItem send = findShareOcrText(screen, text -> "发送".equals(normalizeShareTargetName(text)),
                0f, 1f, 0.45f, 1f);
        OcrHelper.OcrItem cancel = findShareOcrText(screen, text -> "取消".equals(normalizeShareTargetName(text)),
                0f, 1f, 0.45f, 1f);
        OcrHelper.OcrItem message = findShareOcrText(screen, text -> normalizeShareTargetName(text).contains("发消息"),
                0f, 1f, 0.45f, 1f);
        return cancel != null && (send != null || message != null);
    }

    private boolean confirmPageContainsTarget(OcrHelper.Screen screen, String sessionName) {
        if (screen == null || !isShareConfirmPage(screen)) {
            return false;
        }
        String target = normalizeShareTargetName(sessionName);
        String snippets = normalizeShareTargetName(screen.snippets);
        if (snippets.contains(target)) {
            return true;
        }
        for (OcrHelper.OcrItem item : screen.items) {
            String value = normalizeShareTargetName(item.text);
            if (value.equals(target) || value.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private OcrHelper.OcrItem findShareOcrText(OcrHelper.Screen screen, ShareTextMatcher matcher,
                                               float minX, float maxX, float minY, float maxY) {
        if (screen == null || matcher == null) {
            return null;
        }
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerX < screen.width * minX || item.centerX > screen.width * maxX) {
                continue;
            }
            if (item.centerY < screen.height * minY || item.centerY > screen.height * maxY) {
                continue;
            }
            if (matcher.matches(item.text)) {
                return item;
            }
        }
        return null;
    }

    private boolean clickShareOcrItem(Context context, BotConfig config, HsClient hs, ShareTextMatcher matcher, String label,
                                      float minX, float maxX, float minY, float maxY) throws Exception {
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        OcrHelper.OcrItem item = findShareOcrText(screen, matcher, minX, maxX, minY, maxY);
        if (item == null) {
            return false;
        }
        BotLog.i(context, "image.share.ocr.click", "点击分享 OCR 项 label=" + label
                + " text=" + item.text + " x=" + item.centerX + " y=" + item.centerY);
        hs.tap(item.centerX, item.centerY);
        SystemClock.sleep(Math.max(600L, shareSelectPoll(config)));
        return true;
    }

    private static long shareSelectPoll(BotConfig config) {
        return config == null ? 220L : config.shareSelectPollMs;
    }

    private static long shareConfirmPoll(BotConfig config) {
        return config == null ? 250L : config.shareConfirmPollMs;
    }

    private static long shareSendButtonPoll(BotConfig config) {
        return config == null ? 220L : config.shareSendButtonPollMs;
    }

    private static long shareSubmitPoll(BotConfig config) {
        return config == null ? 350L : config.shareSubmitPollMs;
    }

    private void dumpShareOcrItems(Context context, HsClient hs, String label) {
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        BotLog.i(context, "image.share.ocr.dump", "label=" + label
                + " count=" + (screen == null ? 0 : screen.items.size())
                + " packageName=" + foregroundPackageName(hs)
                + " texts=" + (screen == null ? "" : screen.snippets));
    }

    private boolean isShareSelectText(String text) {
        String value = normalizeShareTargetName(text);
        return value.contains("选择聊天")
                || value.contains("选择一个聊天")
                || value.contains("最近转发")
                || value.contains("最近聊天");
    }

    private boolean matchShareFriendEntryText(String text) {
        String value = normalizeShareTargetName(text);
        if (value.isEmpty() || !value.contains("朋友")) {
            return false;
        }
        return value.contains("发") || value.contains("送") || value.contains("递") || value.contains("给");
    }

    private boolean matchShareTargetName(String text, String name) {
        String a = normalizeShareTargetName(text);
        String b = normalizeShareTargetName(name);
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private static String normalizeShareTargetName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[（(]\\d+\\s*人?[）)]", "")
                .replaceAll("[（(]\\d+[）)]$", "")
                .replaceAll("\\s+", "")
                .replace(" ", "")
                .trim();
    }

    private OcrHelper.OcrItem findText(OcrHelper.Screen screen, String text, float minX, float maxX, float minY, float maxY) {
        return OcrHelper.chooseBestTextItem(screen, text, minX, maxX, minY, maxY);
    }

    private String shareMimeFromPath(File file) {
        String lower = file == null ? "" : file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private boolean isWechatForeground(HsClient hs) {
        String top = foregroundPackageName(hs);
        return top.contains("com.tencent.mm");
    }

    private String foregroundPackageName(HsClient hs) {
        try {
            return hs.stateTop().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final class ShareAsset {
        final Uri contentUri;
        final Uri fileUri;
        final String mime;
        final String fileName;
        final boolean publicMedia;

        ShareAsset(Uri contentUri, Uri fileUri, String mime, String fileName, boolean publicMedia) {
            this.contentUri = contentUri;
            this.fileUri = fileUri;
            this.mime = mime;
            this.fileName = fileName;
            this.publicMedia = publicMedia;
        }
    }

    private interface ShareTextMatcher {
        boolean matches(String text);
    }

    private static String parseImage(String body, String baseUrl) throws Exception {
        JSONObject json = new JSONObject(body);
        if (json.has("data")) {
            JSONArray data = json.getJSONArray("data");
            if (data.length() > 0) {
                JSONObject first = data.getJSONObject(0);
                String b64 = first.optString("b64_json", "");
                if (!b64.isEmpty()) {
                    return "data:image/png;base64," + b64;
                }
                String url = first.optString("url", "");
                if (!url.isEmpty()) {
                    return normalizeImageUrl(url, baseUrl);
                }
            }
        }
        if (json.has("image")) {
            return normalizeImageUrl(json.optString("image", ""), baseUrl);
        }
        throw new IllegalStateException("图片上游没有返回图片字段");
    }

    private static String normalizeImageUrl(String value, String baseUrl) {
        if (value == null || value.isEmpty() || value.startsWith("data:") || value.startsWith("http://") || value.startsWith("https://")) {
            return value == null ? "" : value;
        }
        if (value.startsWith("/")) {
            try {
                URL url = new URL(baseUrl);
                return url.getProtocol() + "://" + url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "") + value;
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean looksCoolStyle(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return value.matches(".*(清凉|清爽穿搭|比基尼|泳装|泳衣|泳池自拍|海边自拍|海滩自拍|沙滩自拍|度假自拍|夏日自拍|夏日泳装|清凉图|bikini|swimsuit|swimwear|beachselfie|poolselfie|summerselfie|vacationselfie).*");
    }

    private static boolean looksSelfie(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return value.contains("自拍") || value.contains("照片") || value.contains("露脸") || value.contains("selfie") || value.contains("photo");
    }

    private static String normalizeSize(String value) {
        String size = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (size.matches("^(941x1672|1024x1024|1024x1536|1536x1024|auto)$")) {
            return size;
        }
        return "941x1672";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[（(]\\d+\\s*人?[）)]", "").replaceAll("\\s+", "").trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }

    private static boolean startsWithErr(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'E' && bytes[1] == 'R' && bytes[2] == 'R' && bytes[3] == ':';
    }

    private static String random(String[] values) {
        if (values.length == 0) {
            return "";
        }
        return values[RANDOM.nextInt(values.length)];
    }

    private ReferenceImage captureLatestWechatImageReference(Context context, BotConfig config, String label) {
        HsClient hs = new HsClient(config.hsPort);
        SystemClock.sleep(650);
        Rect input = OcrHelper.findChatInputBlock(context, hs);
        if (input == null) {
            String top = foregroundPackageName(hs);
            if (!isWechatImagePreview(top)) {
                BotLog.w(context, "image.reference.preview.not_open", "未识别到输入框，但当前不是图片预览页，不截图 label="
                        + label + " top=" + top);
                return null;
            }
            try {
                return saveCurrentPreviewReference(context, hs, label + "_current_preview");
            } finally {
                closeWechatImagePreview(context, hs, label + "_current_preview");
            }
        }
        ImageBlock card = findLatestQuoteCard(context, hs, config);
        if (card == null) {
            BotLog.w(context, "image.reference.quote.missing", "未找到群聊引用灰卡 label=" + label);
            return null;
        }
        long waitMs = Math.max(1200L, config.quotedImageOpenDelayMs) + 2200L;
        for (int attempt = 1; attempt <= 2; attempt++) {
            boolean opened = false;
            String topAfterTap = "";
            try {
                BotLog.i(context, "image.reference.quote.tap", "点击最新引用灰卡 label=" + label
                        + " attempt=" + attempt
                        + " rect=" + card.rect.flattenToString() + " center=" + card.centerX + "," + card.centerY
                        + " waitMs=" + waitMs);
                hs.tap(card.centerX, card.centerY);
                topAfterTap = waitForWechatImagePreview(hs, waitMs, 250L);
                opened = isWechatImagePreview(topAfterTap);
                if (!opened) {
                    BotLog.w(context, "image.reference.quote.open.timeout", "引用灰卡点击后未进入图片预览页 label=" + label
                            + " attempt=" + attempt
                            + " rect=" + card.rect.flattenToString()
                            + " top=" + topAfterTap);
                    SystemClock.sleep(450);
                    continue;
                }
                SystemClock.sleep(300);
                ReferenceImage saved = saveCurrentPreviewReference(context, hs, label + "_quote_card");
                if (saved != null) {
                    BotLog.i(context, "image.reference.saved", "已点开并裁剪群聊引用图 label=" + label
                            + " attempt=" + attempt
                            + " rect=" + card.rect.flattenToString()
                            + " top=" + topAfterTap + " bytes=" + saved.bytes.length);
                    return saved;
                }
                BotLog.w(context, "image.reference.quote.capture.miss", "已进入图片预览页但截图裁剪失败 label=" + label
                        + " attempt=" + attempt
                        + " rect=" + card.rect.flattenToString() + " top=" + topAfterTap);
                return null;
            } catch (Exception e) {
                BotLog.w(context, "image.reference.quote.error", "引用灰卡取图异常 label=" + label
                        + " attempt=" + attempt + " error=" + e.getMessage());
                return null;
            } finally {
                if (opened) {
                    closeWechatImagePreview(context, hs, label + "_quote_card");
                }
            }
        }
        return null;
    }

    private String waitForWechatImagePreview(HsClient hs, long timeoutMs, long intervalMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(800L, timeoutMs);
        String top = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(Math.max(150L, intervalMs));
            top = foregroundPackageName(hs);
            if (isWechatImagePreview(top)) {
                return top;
            }
        }
        return top;
    }

    private void closeWechatImagePreview(Context context, HsClient hs, String label) {
        String top = foregroundPackageName(hs);
        if (!isWechatImagePreview(top)) {
            BotLog.i(context, "image.reference.preview.back.skip", "当前已不在图片预览页 label=" + label + " top=" + top);
            return;
        }
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                if (attempt % 2 == 0) {
                    hs.keyCode(4);
                } else {
                    hs.key("BACK");
                }
                SystemClock.sleep(950);
                top = foregroundPackageName(hs);
                if (!isWechatImagePreview(top)) {
                    BotLog.i(context, "image.reference.preview.back", "已退出图片预览 label=" + label
                            + " attempt=" + attempt + " top=" + top);
                    return;
                }
                BotLog.w(context, "image.reference.preview.back.retry", "Back 后仍在图片预览页 label=" + label
                        + " attempt=" + attempt + " top=" + top);
            } catch (Exception e) {
                BotLog.w(context, "image.reference.back.fail", "退出图片预览失败 label=" + label
                        + " attempt=" + attempt + " error=" + e.getMessage());
            }
        }
        BotLog.e(context, "image.reference.preview.back.stuck", "多次 Back 后仍停留图片预览页 label=" + label
                + " top=" + foregroundPackageName(hs));
    }

    private boolean isWechatImagePreview(String top) {
        String value = top == null ? "" : top;
        return value.contains("com.tencent.mm")
                && (value.toLowerCase(Locale.ROOT).contains("gallery")
                || value.contains("ImageGalleryUI")
                || value.contains("ImagePreviewUI"));
    }

    private ImageBlock findLatestQuoteCard(Context context, HsClient hs, BotConfig config) {
        Bitmap bitmap = OcrHelper.captureBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            ImageBlock card = findLatestQuoteCardByGray(bitmap);
            if (card != null) {
                BotLog.i(context, "image.reference.quote.gray.hit", "灰卡命中 rect="
                        + card.rect.flattenToString() + " imageLike=" + card.area);
                return card;
            }
        } finally {
            bitmap.recycle();
        }
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        ImageBlock ocrCard = findLatestQuoteCardByOcr(screen, config);
        if (ocrCard != null) {
            BotLog.i(context, "image.reference.quote.ocr.hit", "OCR 引用灰卡命中 rect="
                    + ocrCard.rect.flattenToString());
        }
        return ocrCard;
    }

    private ImageBlock findLatestQuoteCardByOcr(OcrHelper.Screen screen, BotConfig config) {
        if (screen == null || screen.items.isEmpty()) {
            return null;
        }
        OcrHelper.OcrItem best = null;
        for (OcrHelper.OcrItem item : screen.items) {
            String clean = normalize(item.text);
            if (clean.isEmpty()) {
                continue;
            }
            boolean looksQuoteSource = clean.contains(":") || clean.contains("：");
            if (!looksQuoteSource) {
                continue;
            }
            if (item.rect.top < screen.height * 0.12f || item.rect.bottom > screen.height * 0.9f) {
                continue;
            }
            if (item.rect.left > screen.width * 0.55f) {
                continue;
            }
            if (best == null || item.centerY > best.centerY) {
                best = item;
            }
        }
        if (best == null) {
            return null;
        }
        int left = clamp(Math.min(best.rect.left, Math.round(screen.width * 0.12f)), 0, screen.width - 1);
        int top = clamp(best.rect.top - Math.round(screen.height * 0.012f), 0, screen.height - 1);
        int right = clamp(Math.max(best.rect.right + Math.round(screen.width * 0.12f), Math.round(screen.width * 0.36f)), left + 1, screen.width);
        int bottom = clamp(best.rect.bottom + Math.round(screen.height * 0.028f), top + 1, screen.height);
        return new ImageBlock(new Rect(left, top, right, bottom), 0);
    }

    private ImageBlock findLatestQuoteCardByGray(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = clamp(Math.round(width * 0.10f), 0, width - 1);
        int right = clamp(Math.round(width * 0.48f), left + 1, width);
        int top = clamp(Math.round(height * 0.10f), 0, height - 1);
        int bottom = clamp(Math.round(height * 0.89f), top + 1, height);
        List<ImageBlock> blocks = new ArrayList<>();
        int runTop = -1;
        int runLeft = 0;
        int runRight = 0;
        int previousY = -10;
        for (int y = top; y < bottom; y++) {
            int rowLeft = -1;
            int rowRight = -1;
            int x = left;
            while (x < right) {
                while (x < right && !isQuoteGrayPixel(bitmap.getPixel(x, y))) {
                    x++;
                }
                int start = x;
                while (x < right && isQuoteGrayPixel(bitmap.getPixel(x, y))) {
                    x++;
                }
                int end = x - 1;
                if (end - start + 1 >= Math.max(55, width / 12)) {
                    rowLeft = rowLeft < 0 ? start : Math.min(rowLeft, start);
                    rowRight = Math.max(rowRight, end);
                }
            }
            if (rowLeft >= 0) {
                if (runTop < 0 || y - previousY > 2) {
                    if (runTop >= 0) {
                        addQuoteCardBlock(blocks, bitmap, runLeft, runTop, runRight, previousY);
                    }
                    runTop = y;
                    runLeft = rowLeft;
                    runRight = rowRight;
                } else {
                    runLeft = Math.min(runLeft, rowLeft);
                    runRight = Math.max(runRight, rowRight);
                }
                previousY = y;
            }
        }
        if (runTop >= 0) {
            addQuoteCardBlock(blocks, bitmap, runLeft, runTop, runRight, previousY);
        }
        ImageBlock best = null;
        for (ImageBlock block : blocks) {
            int blockWidth = block.rect.width();
            int blockHeight = block.rect.height();
            if (blockWidth < Math.max(110, width / 7) || blockHeight < 35 || blockHeight > Math.max(120, height / 9)) {
                continue;
            }
            if (best == null || block.centerY > best.centerY) {
                best = block;
            }
        }
        return best;
    }

    private void addQuoteCardBlock(List<ImageBlock> blocks, Bitmap bitmap, int left, int top, int right, int bottom) {
        Rect rect = boundRect(new Rect(left, top, right + 1, bottom + 1), bitmap.getWidth(), bitmap.getHeight());
        int imageLike = countQuoteCardImageLikePixels(bitmap, rect);
        blocks.add(new ImageBlock(rect, imageLike));
    }

    private int countQuoteCardImageLikePixels(Bitmap bitmap, Rect rect) {
        int count = 0;
        int step = 4;
        int imageAreaLeft = rect.left + Math.round(rect.width() * 0.45f);
        for (int y = rect.top; y < rect.bottom; y += step) {
            for (int x = imageAreaLeft; x < rect.right; x += step) {
                int color = bitmap.getPixel(x, y);
                if (!isQuoteGrayPixel(color) && !isWhiteBubblePixel(color) && isImageLikePixel(color)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isQuoteGrayPixel(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min <= 6 && r >= 218 && r <= 242;
    }

    private static boolean isWhiteBubblePixel(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        return r >= 246 && g >= 246 && b >= 246;
    }

    private ReferenceImage saveCurrentPreviewReference(Context context, HsClient hs, String label) {
        Bitmap bitmap = OcrHelper.captureBitmap(context, hs);
        if (bitmap == null) {
            return null;
        }
        try {
            ImageBlock crop = choosePreviewImageBlock(bitmap);
            if (crop == null) {
                int top = clamp(Math.round(bitmap.getHeight() * 0.08f), 0, bitmap.getHeight() - 2);
                int bottom = clamp(Math.round(bitmap.getHeight() * 0.92f), top + 1, bitmap.getHeight());
                crop = new ImageBlock(new Rect(0, top, bitmap.getWidth(), bottom), (bottom - top) * bitmap.getWidth());
                BotLog.w(context, "image.reference.preview.fallback", "预览图块识别失败，裁剪中间区域 label=" + label);
            }
            Rect rect = boundRect(crop.rect, bitmap.getWidth(), bitmap.getHeight());
            Bitmap clipped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height());
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                clipped.compress(Bitmap.CompressFormat.PNG, 100, out);
                byte[] bytes = out.toByteArray();
                if (bytes.length < 8192) {
                    BotLog.w(context, "image.reference.too_small", "裁剪引用图过小 label=" + label + " bytes=" + bytes.length);
                    return null;
                }
                return new ReferenceImage("wechat_reference_" + System.currentTimeMillis() + ".png", bytes);
            } finally {
                clipped.recycle();
            }
        } catch (Exception e) {
            BotLog.w(context, "image.reference.preview.error", e.getMessage());
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private List<ImageBlock> findVisibleWechatImageBlocks(Context context, HsClient hs, int maxCandidates) {
        Bitmap bitmap = OcrHelper.captureBitmap(context, hs);
        if (bitmap == null) {
            return new ArrayList<>();
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            List<ImageBlock> raw = detectImageLikeBlocks(bitmap, 8);
            List<ImageBlock> filtered = new ArrayList<>();
            for (ImageBlock block : raw) {
                int blockWidth = block.rect.width();
                int blockHeight = block.rect.height();
                float aspect = blockHeight <= 0 ? 99f : (float) blockWidth / (float) blockHeight;
                if (blockWidth < 40 || blockHeight < 40 || block.area < 1200) {
                    continue;
                }
                if (block.rect.top < 120 || block.rect.bottom > height - 120 || aspect < 0.35f || aspect > 2.4f) {
                    continue;
                }
                boolean smallEdge = blockWidth <= 100 && blockHeight <= 100
                        && (block.rect.left < 110 || block.rect.right > width - 110);
                if (smallEdge) {
                    continue;
                }
                filtered.add(block);
            }
            filtered.sort((a, b) -> {
                if (b.centerY != a.centerY) {
                    return b.centerY - a.centerY;
                }
                return b.area - a.area;
            });
            if (filtered.size() <= maxCandidates) {
                return filtered;
            }
            return new ArrayList<>(filtered.subList(0, Math.max(1, maxCandidates)));
        } finally {
            bitmap.recycle();
        }
    }

    private ImageBlock choosePreviewImageBlock(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        List<ImageBlock> raw = detectImageLikeBlocks(bitmap, 6);
        ImageBlock best = null;
        for (ImageBlock block : raw) {
            int blockWidth = block.rect.width();
            int blockHeight = block.rect.height();
            float aspect = blockHeight <= 0 ? 99f : (float) blockWidth / (float) blockHeight;
            if (blockWidth < 140 || blockHeight < 140 || block.area < 18000) {
                continue;
            }
            if (block.rect.top < 80 || block.rect.bottom > height - 80 || aspect < 0.25f || aspect > 3.0f) {
                continue;
            }
            if (best == null || block.area > best.area
                    || (block.area == best.area && Math.abs(block.centerX - width / 2) < Math.abs(best.centerX - width / 2))) {
                best = block;
            }
        }
        return best;
    }

    private List<ImageBlock> detectImageLikeBlocks(Bitmap bitmap, int step) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int safeStep = Math.max(4, step);
        int gridW = Math.max(1, (width + safeStep - 1) / safeStep);
        int gridH = Math.max(1, (height + safeStep - 1) / safeStep);
        boolean[] mask = new boolean[gridW * gridH];
        boolean[] seen = new boolean[gridW * gridH];
        for (int gy = 0; gy < gridH; gy++) {
            int y = Math.min(height - 1, gy * safeStep);
            for (int gx = 0; gx < gridW; gx++) {
                int x = Math.min(width - 1, gx * safeStep);
                mask[gy * gridW + gx] = isImageLikePixel(bitmap.getPixel(x, y));
            }
        }
        List<ImageBlock> blocks = new ArrayList<>();
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
                    tail = enqueueImageNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx + 1, cy);
                    tail = enqueueImageNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx - 1, cy);
                    tail = enqueueImageNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy + 1);
                    tail = enqueueImageNeighbor(mask, seen, queueX, queueY, tail, gridW, gridH, cx, cy - 1);
                }
                Rect rect = new Rect(
                        clamp(minGx * safeStep, 0, width - 1),
                        clamp(minGy * safeStep, 0, height - 1),
                        clamp(maxGx * safeStep + safeStep, 1, width),
                        clamp(maxGy * safeStep + safeStep, 1, height));
                blocks.add(new ImageBlock(rect, count * safeStep * safeStep));
            }
        }
        return blocks;
    }

    private static int enqueueImageNeighbor(boolean[] mask, boolean[] seen, int[] queueX, int[] queueY,
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

    private static boolean isImageLikePixel(int color) {
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (r >= 238 && g >= 238 && b >= 238 && max - min <= 18) {
            return false;
        }
        if (r <= 45 && g <= 45 && b <= 45 && max - min <= 18) {
            return false;
        }
        return max - min >= 22 || max <= 215;
    }

    private static Rect boundRect(Rect rect, int width, int height) {
        int left = clamp(rect.left, 0, width - 1);
        int top = clamp(rect.top, 0, height - 1);
        int right = clamp(rect.right, left + 1, width);
        int bottom = clamp(rect.bottom, top + 1, height);
        return new Rect(left, top, right, bottom);
    }

    private static ImageMemory imageMemory(String sessionName) {
        String key = normalize(sessionName);
        if (key.isEmpty()) {
            return null;
        }
        ImageMemory memory = IMAGE_CONTEXTS.get(key);
        if (memory == null) {
            return null;
        }
        if (SystemClock.uptimeMillis() - memory.timeMs > IMAGE_CONTEXT_TTL_MS) {
            IMAGE_CONTEXTS.remove(key);
            return null;
        }
        return memory;
    }

    private static void rememberImageMemory(String sessionName, String request, String kind, File file, String image) {
        String key = normalize(sessionName);
        if (key.isEmpty()) {
            return;
        }
        String path = file == null ? "" : file.getAbsolutePath();
        String dataUrl = image != null && image.startsWith("data:image/") ? image : "";
        String url = image != null && (image.startsWith("http://") || image.startsWith("https://")) ? image : "";
        IMAGE_CONTEXTS.put(key, new ImageMemory(SystemClock.uptimeMillis(), kind, request, path, dataUrl, url));
    }

    private static boolean looksIncomingReference(String text) {
        String value = normalize(text);
        String lower = (text == null ? "" : text.toLowerCase(Locale.ROOT)).replaceAll("\\s+", "");
        return value.matches(".*(图生图|以图生图|参考图|参考这张|参考这个图|参考这个图片|照着这张|按这张|按这个图|用这张|用这图|用这个图|根据这张|根据这个图|拿这张|拿这图|改这张|换这张|修这张|这张改|这图改|这个图改).*")
                || lower.matches(".*(img2img|image2image|referenceimage|editimage|editphoto|editthisimage|editthisphoto|usethisimage|usethisphoto|referencethis|basedonthisimage|modifythisimage|changethisimage).*");
    }

    private static boolean looksRevision(String text) {
        String value = normalize(text);
        String lower = (text == null ? "" : text.toLowerCase(Locale.ROOT)).replaceAll("\\s+", "");
        return value.matches(".*(换个场景|换场景|换个地方|换地方|换个背景|换背景|换成户外|换到户外|换个动作|换个姿势|换一版|改一版|重拍|重新拍|再来一张|再发一张|再拍一张|继续来|继续|再来|侧一点|坐着拍|站着拍|近一点|远一点|自然一点|清晰一点|更清晰|不行|不好看|不满意).*")
                || lower.matches(".*(differentbackground|changebackground|newbackground|anotherone|again|redo|retry|remake|regenerate|newpose|differentpose|changeangle|makeitclearer|moreclear).*");
    }

    private static final class ImageBlock {
        final Rect rect;
        final int area;
        final int centerX;
        final int centerY;

        ImageBlock(Rect rect, int area) {
            this.rect = rect == null ? new Rect() : new Rect(rect);
            this.area = area;
            this.centerX = this.rect.centerX();
            this.centerY = this.rect.centerY();
        }
    }

    private static final class ReferenceImage {
        final String filename;
        final byte[] bytes;

        ReferenceImage(String filename, byte[] bytes) {
            this.filename = filename == null || filename.isEmpty() ? "reference.png" : filename;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    public static final class CapturedReference {
        public final String filename;
        public final byte[] bytes;

        CapturedReference(String filename, byte[] bytes) {
            this.filename = filename;
            this.bytes = bytes;
        }
    }

    private static final class ImageMemory {
        final long timeMs;
        final String kind;
        final String originalText;
        final String path;
        final String dataUrl;
        final String url;

        ImageMemory(long timeMs, String kind, String originalText, String path, String dataUrl, String url) {
            this.timeMs = timeMs;
            this.kind = kind == null ? "general" : kind;
            this.originalText = originalText == null ? "" : originalText;
            this.path = path == null ? "" : path;
            this.dataUrl = dataUrl == null ? "" : dataUrl;
            this.url = url == null ? "" : url;
        }

        ReferenceImage asReference() {
            try {
                if (!dataUrl.isEmpty()) {
                    int comma = dataUrl.indexOf(',');
                    if (comma >= 0) {
                        return new ReferenceImage("previous_image.png", Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT));
                    }
                }
                if (!path.isEmpty() && hasFile(path)) {
                    return loadFileReference(path, "previous_image.png");
                }
                if (!url.isEmpty()) {
                    return new ReferenceImage("previous_image.png", download(url));
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static final String[] WARMUP_LINES = {
            "想看我？那先夸我两句再说，先欠着。",
            "急什么，好看的照片总要压轴登场。",
            "就知道你惦记我的颜值哈哈。",
            "发可以，看完不许吐槽啊。",
            "行吧，勉为其难满足你一下。",
            "来咯来咯，新鲜出炉的本人。",
            "就这么想看我呀，怪害羞的。",
            "收到指令，马上安排自拍。",
            "哈哈就等你这句话呢。",
            "看个毛线，红包都没发，不过我先拍一张。",
            "还没看够呀，那我再营业一下。",
            "想看可以，先夸夸我的盛世美颜。",
            "你是不是闲得无聊啦哈哈。",
            "休想套路我，我差点不上当。",
            "哎呀突然有点害羞啦。",
            "等等嘛，我先整理一下样子。",
            "才不要，除非你先给我看你的。",
            "你先夸我一句，我就考虑一下。",
            "发不了，我今天没化妆，颜值不在线。",
            "想看我？那得视频通话，照片多没意思。",
            "我怕发完你天天盯着看，耽误你工作。",
            "等我修个图先，不能让你看到我的真实面目。",
            "想看我素颜？门都没有，窗户也焊死了。",
            "照片哪有真人好看，不如约出来见一面呀。",
            "你猜我今天穿什么颜色的衣服，猜对了就发。",
            "不给不给，我的照片是稀有资源，要收费的。",
            "哎呀，人家害羞嘛，突然让我发照片。",
            "等我找个光线好的地方拍一张，别急。",
            "发完你可不能截图存着哦，我会害羞的。",
            "你是不是想我啦？直说嘛，发你一张就是了。",
            "只能看一秒钟，看完立刻忘掉。",
            "我的照片有魔力，看了会开心一整天哦。",
            "不行不行，我刚睡醒，头发乱糟糟的。",
            "那你要夸我好看，不然我就撤回。",
            "给你看个背影杀，正面就别想了。",
            "发你一张小时候的，可爱死你。",
            "怕发完你爱上我，我可不负责任。",
            "发不了，我的颜值被外星人偷走了。",
            "别要了，我长得太丑，怕吓到你。",
            "你确定要看？看完晚上做噩梦我可不赔。",
            "我的照片？拿去辟邪刚好合适。",
            "正在和奥特曼拯救地球，没空拍照。",
            "手机里全是表情包，没有真人照片。",
            "发你一张丑照，你可别笑到喷饭。",
            "我怕发完你就把我拉黑了。",
            "想看我？先交10块钱门票费。",
            "照片就免了，我怕你自卑。",
            "我长得太普通，不值得你浪费流量。",
            "别催别催，我正在用美颜相机抢救一下。",
            "发完你可得请我喝奶茶，不然亏大了。",
            "我的照片可是违禁品，轻易不能发。",
            "你是不是闲得慌？非要找我要照片。",
            "发你一张糊到看不清的，自己脑补吧。",
            "我长什么样你心里没点数吗？还要照片。",
            "看照片多没意思，我给你画一个吧。",
            "刚吃完火锅，脸上油光满面，没法看。",
            "我不太爱拍照，手机里没什么好看的。",
            "不好意思，我平时很少自拍。",
            "我不上镜，拍出来的照片都不好看。",
            "暂时没有合适的照片，下次吧。",
            "我不喜欢发自己的照片给别人。",
            "抱歉，我不太习惯给陌生人发照片。",
            "最近没拍什么照片，等以后有了再说。",
            "我觉得文字聊天就挺好的，不用看照片。",
            "我长得不好看，就不发出来辣眼睛了。",
            "不好意思，我不太想发自己的照片。",
            "我手机里都是风景照，没有自拍。",
            "算了吧，我拍照技术太差了。",
            "我对自己的长相没什么自信，就不发了。",
            "发照片多尴尬呀，还是算了。",
            "我们还不太熟，发照片不太合适吧。",
            "没照片。",
            "不发。",
            "没必要。",
            "没兴趣。",
            "凭什么？",
            "你谁啊？",
            "关你屁事。",
            "不喜欢发照片。",
            "想看自己脑补。",
            "没空理你。",
            "没什么好看的。",
            "别来烦我。",
            "无可奉告。",
            "我不发照片，谢谢。",
            "想都别想。",
            "那你可得准备好被我美到/帅到哦。",
            "发你一张，看完可不许说不喜欢。",
            "只能看我的照片，不许看别人的。",
            "给你看个侧脸，剩下的留给你想象。",
            "发完你可要夸我，不然我会伤心的。",
            "是不是特别想我？满足你这个小愿望。",
            "看完我的照片，有没有更想我了？",
            "这张可是我的珍藏，只发给你一个人。",
            "怎么样？有没有心动的感觉？",
            "发你一张，下次见面可得认出来我哦。",
            "偷偷告诉你，这张照片我只给喜欢的人看。",
            "看完记得点赞，不然下次不给你看了。",
            "是不是觉得我比你想象中好看？",
            "给你看我的照片，你要怎么报答我呀？",
            "发完这张，下次就该你发了哦。",
            "稍等，我找张好看的发你。",
            "好呀，这张是我上周拍的。",
            "没问题，发你几张最近的。",
            "刚好手机里有一张，发你看看。",
            "行，等我拍一张现在的给你。",
            "好的，不过我拍照技术不太好，别嫌弃。",
            "发你啦，这张我还挺喜欢的。",
            "可以呀，你想看什么样的？",
            "没问题，马上发给你。",
            "好，我整理一下，发你几张。",
            "发你一张我家猫的，它比我好看。",
            "这张是我抠脚的照片，要不要看？",
            "发你一张表情包，凑活看吧。",
            "给你看个我的丑照，笑到肚子痛概不负责。",
            "发你一张我戴着口罩的，全靠眼神交流。",
            "想看我？那先夸我两句再说～",
            "准备好了吗，颜值暴击来袭",
            "眼光不错，偏偏想看我",
            "想看自拍？拿你的来换",
            "猜猜我现在是什么表情？猜对就发",
            "别眨眼，接下来高能预警",
            "终于发现我的美貌了？",
            "想看可以，先答应我不许存图",
            "安排！不过丑了可别笑我",
            "想看正脸还是侧脸？二选一",
            "没问题，看完记得点赞哦",
            "来啦，今日份营业上线",
            "今天没收拾，就不献丑啦",
            "最近状态一般，就不拍啦",
            "算了吧，今天颜值不在线",
            "刚睡醒/刚忙完，形象有点潦草",
            "手机里没存新照片，下次再发哈",
            "我不太喜欢拍自拍，见谅啦",
            "现在乱糟糟的，实在不好意思发",
            "改天拍了好看的再发给你～",
            "素颜出镜怕吓到你，哈哈",
            "暂时不想拍哦，咱们聊点别的吧",
            "今天懒得拍照，放过我吧",
            "没怎么自拍，相册里全是风景照",
            "出门没整理仪容，就不发啦",
            "我拍照巨不上镜，还是算了哈",
            "现在不方便拍，回头再说呀",
            "别为难我啦，我真不爱自拍",
            "状态拉胯，还是不展示了",
            "手头忙着呢，没空拍照啦",
            "好久没拍新自拍了，旧的就不发咯",
            "哈哈还是别看了，会颠覆你想象",
            "前方高能，非战斗人员请撤离",
            "警告！本人自拍杀伤力极强",
            "看完请做好心理准备，哈哈",
            "别问，问就是颜值不稳定",
            "自拍？那得收费了啊",
            "我的自拍，主打一个随缘颜值",
            "慎看！看完不许取关/绝交",
            "来了来了，野生原相机直出",
            "将就看看吧，全靠滤镜撑着",
            "建议调低亮度观看，保护眼睛",
            "丑话先说在前头，不好看别骂",
            "本颜值选手今日暂时下线",
            "自拍库存告急，真没有啦",
            "别看外表，有趣的灵魂在这里",
            "原相机生人勿近，算了吧",
            "发可以，笑出声我可要生气的",
            "准备好，大型颜值翻车现场",
            "我拍照全靠角度，角度没找好",
            "看完记得洗眼睛，哈哈哈哈",
            "平平无奇普通人，没啥好拍的",
            "不要啦，人家不好意思嘛",
            "突然被点名，还有点小紧张",
            "好吧好吧，拗不过你啦",
            "就发一张哦，不许多要啦",
            "人家今天软软糯糯的，快看～",
            "别一直要看啦，怪不好意思的",
            "来啦，偷偷给你看哦",
            "那我发啦，你可不许笑话我",
            "没啥好拍的。",
            "不拍，懒得弄。",
            "没有自拍。",
            "算了，不发。",
            "不想露脸。",
            "随缘吧，不拍了。",
            "就不发啦。",
            "不爱自拍。",
            "不必了哈。",
            "怎么，突然想我了？",
            "天天想看我，是不是暗恋我",
            "先把你的自拍发过来，礼尚往来",
            "你是不是就等着看我笑话呢",
            "好家伙，又来套路我发自拍",
            "想看？先说说理由",
            "你咋总惦记我的照片啊",
            "先交你的，我再考虑考虑",
            "别搞事啊，小心我也催你",
            "就知道你又要调侃我",
            "休想套路我，我才不上当",
            "怎么，今天格外好奇我的样子？",
            "彼此彼此，你先发我就发",
            "又来催自拍，能不能换个话题",
            "看完可别到处传播啊",
            "行啊，看完轮到你咯",
            "我猜你就是想截图恶搞我",
            "少来这套，我可不上当",
            "急什么，好看的照片总要压轴登场",
            "就知道你惦记我的颜值哈哈",
            "发可以，看完不许吐槽啊",
            "行吧，勉为其难满足你一下",
            "来咯来咯，新鲜出炉的本人",
            "就这么想看我呀，怪害羞的",
            "收到指令，马上安排自拍",
            "哈哈就等你这句话呢",
            "哎呀突然有点害羞啦",
            "等等嘛，我先整理一下样子",
            "想看可以，先夸夸我的盛世美颜",
            "你是不是闲得无聊啦哈哈",
            "等下，我找个不翻车的角度。",
            "你这催得像查岗，我先准备一下。",
            "先别急，我看看今天脸给不给面子。",
            "要看也行，别拿去当表情包。",
            "我先把刘海救一下，马上。",
            "你这突然要照片，我手都不会摆了。",
            "行吧，给你开个小窗。",
            "别催，我在跟镜头和解。",
            "你先把笑声收一收，我怕你笑我。",
            "我拍一张能看的，别急。",
            "发可以，评价温柔点。",
            "我先看看光线站哪边。",
            "哎呀你这人，怎么突然查颜值。",
            "你别一副等开奖的样子。",
            "拍了不好看你当没看见。",
            "我先找个不背刺我的角度。",
            "你要看就看，别到处显摆。",
            "这要求来得突然，我缓两秒。",
            "我先整理下头发，别偷笑。",
            "行，给你看看今天状态。",
            "我尽量不翻车。",
            "你等着，我去找个能活人的光。",
            "先说好，原相机不背锅。",
            "这张要是糊了你自己脑补。",
            "你这也太会点菜了。",
            "我先营业一下，别太认真。",
            "发一张可以，多了没有。",
            "别催，我在给颜值排队。",
            "等我换个不尴尬的表情。",
            "你是不是蹲我自拍很久了。",
            "我找一张不社死的。",
            "行吧，今天给你个面子。",
            "先让我摆脱一下死亡角度。",
            "你等我调整下状态。",
            "我拍一张日常的，别期待太高。",
            "你这问题来得很突然，我先找镜子。",
            "可以是可以，但你别像审图一样看。",
            "我先找个背景不乱的地方。",
            "等我把表情管理捡回来。",
            "你要看就认真看，别敷衍。",
            "行，给你一点新鲜感。",
            "我先确认下自己还能不能见人。",
            "别急，我这边正在加载颜值。",
            "今天状态普通，给你看个真实版。",
            "你这催自拍的架势很专业啊。",
            "发可以，先把夸夸准备好。",
            "我先挑个不那么离谱的角度。",
            "你等一下，我不想拍成证件照。",
            "先让我跟镜头培养一下感情。",
            "行吧，给你一个偷偷看的机会。",
            "你要是敢保存，我下次就装没看见。",
            "我拍完先自检一下。",
            "别催啦，照片又不是外卖。",
            "我先找个不显脸圆的位置。",
            "这就安排，不过你得嘴甜一点。",
            "等我把眼神调成在线。",
            "你突然这么一问，我还怪不好意思的。",
            "行，我给你拍个生活版。",
            "先声明，随手拍不接受退货。",
            "我去找个顺眼的地方拍。",
            "等我十秒，给你整点真实的。",
            "你这人，嘴上聊天心里惦记照片。",
            "好吧，看在你这么执着的份上。",
            "我先把周围乱七八糟的东西避开。",
            "发之前先问一句，你心理准备做好没。",
            "可以，先让我把表情从上班脸切回来。",
            "那我随手拍一张，你别挑刺。",
            "你这算点名营业了是吧。",
            "行，今天破例给你看一下。",
            "我先找个不会把我拍丑的光。",
            "别着急，我正在和镜头谈判。",
            "你想看就直说嘛，绕这么半天。",
            "好，我拍一张不那么敷衍的。",
            "我先收拾一下表情，马上来。",
            "行吧，给你看一眼日常状态。"
    };

    private static final String[] AFTER_LINES = {
            "速看，新鲜出炉的本人。",
            "交作业啦，不许吐槽哦。",
            "怎么样，有没有惊艳到你。",
            "看完啦？这下满足了吧。",
            "快说说，观感如何。",
            "浅浅出镜一下，哈哈。",
            "就这一张，多的没有啦。",
            "专属限定自拍，只给你看。",
            "别盯着看啦，再看我害羞了。",
            "看够没，红包呢。",
            "这张先给你看，不许乱存。",
            "满足你的小愿望啦。",
            "看完了？该轮到你发了啊。",
            "少偷偷保存啊，我都知道。",
            "礼尚往来，该你上了哈。",
            "看完记得保密，禁止外传。",
            "看完记得点评一下哈",
            "今日份颜值营业结束✨",
            "限时观看，看完赶紧忘掉",
            "隆重登场，欢迎品鉴",
            "看看今日状态还还行不？",
            "终于安排上了，开心不",
            "近距离观赏，别被帅/美到",
            "看完记得留个言呀",
            "任务完成，接下来聊点别的",
            "浅浅露个脸，溜了溜了",
            "怎么样，和想象中一样吗",
            "展示完毕，接受你的评价",
            "原相机直出，将就看吧😂",
            "全靠角度撑场面，别笑我",
            "颜值飘忽不定，今日勉强及格",
            "预警完毕，现在可以洗眼睛了",
            "随手一拍，主打一个真实",
            "别深究，细看全是破绽",
            "勉强凑活，也就这样了",
            "拍完才发现表情怪怪的，哈哈",
            "滤镜救我狗命，不然没法见人",
            "野生状态，毫无形象可言",
            "看完别隔夜，容易影响食欲",
            "属实有点潦草，多多包涵",
            "今日颜值掉线，见谅见谅",
            "随便拍的，别当真哈",
            "大型翻车现场，你赢了",
            "也就拍照那一秒还算正常",
            "尽力了，实在拍不出好看的",
            "丑图奉上，不许存图恶搞",
            "看完有没有瞬间清醒？",
            "普通人一枚，没啥看点啦",
            "好啦，给你看完咯😜",
            "有点害羞，不许一直盯着看呀",
            "仓促拍的，希望你喜欢～",
            "浅浅露个面，嘿嘿",
            "拍完啦，这下如愿啦吧",
            "有点不好意思呢，别打趣我",
            "就偷偷给你看一下哦",
            "临时拍的，样子乱糟糟的",
            "嘻嘻，看完啦，开心嘛",
            "勉为其难出镜一下下",
            "随手拍的，姿态都没摆好",
            "好啦图片发完咯，聊别的吧",
            "是不是看着呆呆的呀",
            "鼓起勇气发出来啦✨",
            "就一张哦，不许再要啦",
            "光线不太好，凑合看看啦",
            "嘿嘿，专属小自拍送达",
            "拍完啦，感觉有点小尴尬",
            "简简单单，日常样子而已",
            "怎么样，是不是被我帅/美到了",
            "老实交代，是不是想截图搞我",
            "看完不许到处乱发，听到没",
            "别光看，快交出你的照片",
            "这下看够了吧，还催不催了",
            "是不是和你脑补的不一样？",
            "欣赏完了，接下来换你表演",
            "就这颜值，有没有颠覆认知",
            "看完啥感受，如实招来",
            "天天惦记我照片，这下满意了？",
            "敢吐槽我，你小心点",
            "看完可别做梦都想起我哈哈",
            "怎么样，是不是没让你失望",
            "就问你，服不服😏",
            "催了半天，这下看尽兴了吧",
            "好了，看完啦。",
            "随手拍的，看看就行。",
            "就这张啦。",
            "拍完咯。",
            "简单拍了下。",
            "凑合看看吧。",
            "发完啦。",
            "日常状态而已。",
            "看吧。",
            "到此为止啦。",
            "记录一下当下的状态而已。",
            "随手定格瞬间，分享给你。",
            "平凡日常，简单留个影。",
            "偶尔拍照，也算小乐趣啦。",
            "当下的模样，就此定格。",
            "闲来无事，随手拍了一张。",
            "不刻意修饰，就是本来的样子。",
            "分享今日模样，愿你好心情。",
            "简单出镜，生活常态罢了。",
            "捕捉片刻，留作小纪念。",
            "速看，新鲜出炉的本人～",
            "交作业啦，不许吐槽哦",
            "怎么样，有没有惊艳到你",
            "看完啦？这下满足了吧",
            "快说说，观感如何",
            "浅浅出镜一下，哈哈",
            "就这一张，多的没有啦",
            "专属限定自拍，只给你看",
            "满足你的小愿望啦～",
            "看完了？该轮到你发了啊",
            "少偷偷保存啊，我都知道",
            "礼尚往来，该你上了哈",
            "别盯着看啦，再看我害羞了",
            "看完记得保密，禁止外传",
            "看完别装没看见。",
            "交差了，别再催。",
            "好了，今日份营业结束。",
            "你先评价，差评我就撤回。",
            "看到了吧，别说我小气。",
            "这张算给你面子。",
            "先凑合看，别放大。",
            "发完了，夸不夸看你自觉。",
            "这回满意了没。",
            "别盯太久，我会尴尬。",
            "好了，轮到你了。",
            "图来了，嘴下留情。",
            "临时拍的，别太严格。",
            "别截图搞我，我记仇。",
            "你要的来了，别说我没安排。",
            "看完记得忘掉。",
            "不许笑，听见没。",
            "我已经尽力了。",
            "今天状态就这样了。",
            "这张光线还算给面子。",
            "先发这张，再要就收费。",
            "完成任务，收工。",
            "看完给句好听的。",
            "这张不许外传。",
            "别拿去做头像，求你。",
            "你可算等到了。",
            "我都发了，你也别藏着。",
            "这一张已经很有诚意了。",
            "看完别突然沉默。",
            "你要是敢笑我就撤回。",
            "等你点评，别太损。",
            "今天就开放到这里。",
            "这张是限量版。",
            "终于给你安排上了。",
            "看完别飘。",
            "你要的本人已送达。",
            "这角度我尽力救了。",
            "发了发了，别催啦。",
            "我宣布今日营业完成。",
            "这张够不够交作业。",
            "勉强能见人吧。",
            "图到位了，话题可以继续。",
            "看完记得夸自然一点。",
            "先别放大，给我留点面子。",
            "抓拍感，别挑剔。",
            "你看，这不就来了。",
            "这张给你先验货。",
            "不满意也先憋着。",
            "我已出镜，轮到你说话。",
            "别光看呀，夸两句。",
            "这可是我努力找角度的成果。",
            "你这下总不能说我敷衍了吧。",
            "看完给个反应，不然我很尴尬。",
            "照片发完，嘴甜模式可以启动了。",
            "你要的照片已送达，请签收。",
            "这张算我今天比较能看的。",
            "看完别偷着乐。",
            "好了好了，别一直盯着。",
            "给你看了，别得寸进尺。",
            "今天库存先到这里。",
            "你这回欠我一句好看。",
            "拍得很仓促，但诚意有了。",
            "我已经勇敢发出来了。",
            "先看这个版本，不满意也别急着说。",
            "这张主打一个真实生活感。",
            "发完我都不好意思了。",
            "你别沉默，沉默很吓人。",
            "看完快点评，我等着呢。",
            "好了，满足你的好奇心了。",
            "图给你了，别再念叨。",
            "别问还有没有，先看这张。",
            "这张我自己觉得还行。",
            "你要是夸得好听，下次还有。",
            "这张先顶着，别挑。",
            "我已经把面子交出来了。",
            "看完别笑太大声。"
    };

    private static final String[] REFERENCE_WARMUP_LINES = {
            "我按这张图改一版，等我一下。",
            "收到，我先看这张图的感觉。",
            "行，我照着这个参考弄一张。",
            "这张我拿到了，我换个版本试试。",
            "我先按你这张图处理一下。"
    };

    private static final String[] REVISION_WARMUP_LINES = {
            "我重新弄一张，这次换个感觉。",
            "这版我再调一下，等我一下。",
            "刚才那张不满意是吧，我换一版。",
            "行，我按上一张继续改。",
            "收到，我再给你换个场景。"
    };

    private static final String[] REVISION_AFTER_LINES = {
            "这版你再看看，顺眼点没。",
            "先按这个感觉来，不行我再改。",
            "这张我觉得比刚才自然点。",
            "换好了，你看看这次对不对味。",
            "这版先交作业。"
    };

    private static final String[] SELFIE_STYLE_LINES = {
            "本次自拍变化约束：换一套与当前季节匹配的衣服和材质，避免连续使用同一种布料、同一种领口和同一种颜色。",
            "本次自拍变化约束：穿搭、发丝状态、表情和手部动作都要重新设计，不能像参考图换脸或复制姿势。",
            "本次自拍变化约束：镜头距离、手臂位置、头部角度和视线方向要随机变化，像真实手机临时拍出来。",
            "本次自拍变化约束：服装材质从季节适配材质里随机选，不要默认毛衣、针织开衫或厚实绒感。",
            "本次自拍变化约束：可以自然微笑、抿嘴害羞、轻轻皱眉、歪头、托腮、比小手势、侧身回头，但动作必须自然。"
    };

    private static final String[] SELFIE_PERSONA_LINES = {
            "本次人物气质随机约束：学院风，干净清爽，像刚下课随手拍的自然自拍。",
            "本次人物气质随机约束：邻家小妹感，亲近、松弛、明亮，表情不要端着。",
            "本次人物气质随机约束：气质女神感，五官精致但生活化，不要棚拍写真感。",
            "本次人物气质随机约束：知性温柔，眼神安静，穿搭简洁有质感。",
            "本次人物气质随机约束：温柔儒雅，姿态舒展，像熟人聊天里自然发出的自拍。",
            "本次人物气质随机约束：风情要有层次，眼神和姿态更有故事感，但仍是日常自然自拍，不要夸张摆拍。"
    };

    private static String currentChinaSeasonOutfitRule() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.CHINA);
        int month = calendar.get(Calendar.MONTH) + 1;
        if (month >= 3 && month <= 5) {
            return "当前中国季节按春季处理：优先轻薄衬衫、薄开衫、卫衣、牛仔外套、棉质上衣、长裙或休闲裤等春季材质；不要套用人物参考图原衣服。";
        }
        if (month >= 6 && month <= 8) {
            return "当前中国季节按夏季处理：优先短袖、吊带、衬衫裙、薄棉、棉麻、雪纺、真丝感、轻薄运动面料等清爽材质；普通自拍不要毛衣、针织、厚外套、绒感或冬季穿搭。";
        }
        if (month >= 9 && month <= 11) {
            return "当前中国季节按秋季处理：优先薄风衣、衬衫、针织马甲、薄毛衫、牛仔外套、休闲夹克等秋季层次；可以有轻薄针织，但不要照搬参考图毛衣纹理和颜色。";
        }
        return "当前中国季节按冬季处理：可以选择毛衣、针织、围巾、羽绒服、呢大衣或厚外套等冬季材质，但仍必须换款式、颜色、姿势和场景，不要照搬人物参考图。";
    }

    private static String currentChinaTimeSceneRule() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.CHINA);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 10) {
            return "当前北京时间约为早晨：场景必须是清晨或上午自然光，适合卧室窗边、早餐店、上班路上、校园/小区晨光；不要生成夜景、霓虹或深夜室内灯光。";
        }
        if (hour >= 10 && hour < 17) {
            return "当前北京时间约为白天：场景必须是白天实景和自然日光，适合商场、街边、公园、河边、教室、咖啡店窗边；不要生成夜晚、酒吧、霓虹灯或月光场景。";
        }
        if (hour >= 17 && hour < 20) {
            return "当前北京时间约为傍晚：场景必须是夕阳、晚霞或傍晚室内暖光，适合河边、阳台、街角、商场门口；不要生成正午烈日或深夜场景。";
        }
        return "当前北京时间约为夜晚：场景必须是夜间实景、室内暖灯、夜街或商场灯光，避免白天蓝天烈日；画面仍要真实清晰，不要黑糊。";
    }

    private static String selfieSceneRule() {
        return "自拍场景随机策略：每次从明显不同的生活地点里自然选择，例如客厅、厨房、阳台、街角、野外小路、校园、公园、商场、河边或湖边、咖啡店、便利店门口、电梯口、地铁口等；不要固定室内，不要连续使用同一房间、同一背景、同一镜头角度或同一穿搭。";
    }

    private static boolean shouldAppendUserRequest(String userRequest, boolean cool, boolean selfie,
                                                   boolean revision, boolean incomingReference) {
        if (userRequest == null || userRequest.trim().isEmpty()) {
            return false;
        }
        String normalized = userRequest.replaceAll("\\s+", "");
        if (selfie && !cool && !revision && !incomingReference) {
            return !(normalized.equals("自拍")
                    || normalized.equals("发自拍")
                    || normalized.equals("来张自拍")
                    || normalized.equals("拍张自拍")
                    || normalized.equals("给我发自拍"));
        }
        return true;
    }
}
