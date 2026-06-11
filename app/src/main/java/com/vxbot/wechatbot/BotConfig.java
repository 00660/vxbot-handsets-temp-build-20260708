package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class BotConfig {
    private static final String PREFS = "bot_config";
    public static final String DEFAULT_CHAT_ENDPOINT = "http://192.168.2.157:8317/v1/chat/completions";
    public static final String DEFAULT_API_KEY = "client-key-1";
    public static final String DEFAULT_IMAGE_ENDPOINT = "http://192.168.3.1:3002/v1";
    public static final String DEFAULT_IMAGE_MODEL = "gpt-image-2";
    public static final String DEFAULT_LICENSE_PANEL_BASE_URL = "http://192.168.2.204:18088";
    public static final String DEFAULT_YUAN_PACKAGE = "com.wxjc.newworld.debug";
    public static final String DEFAULT_PAYMENT_CALLBACK_URL = "http://192.168.2.204:18090/api/payment/wechat";
    public static final String DEFAULT_TTS_VOICE = "Cherry";
    public static final float DEFAULT_TTS_SPEED = 1.0f;
    public static final String[] TTS_VOICE_IDS = {
            "Cherry", "Serena", "Ethan", "Chelsie", "Momo", "Vivian",
            "Moon", "Maia", "Kai", "Nofish", "Bella", "Jennifer",
            "Ryan", "Katerina", "Aiden", "Bodega", "Alek", "Dolce",
            "Sohee", "Ono Anna", "Lenn", "Sonrisa", "Emilien", "Andre",
            "Radio Gol", "Eldric Sage", "Mia", "Mochi", "Bellona", "Vincent",
            "Bunny", "Neil", "Elias", "Arthur", "Nini", "Ebona",
            "Seren", "Pip", "Stella", "Li", "Marcus", "Roy",
            "Peter", "Eric", "Rocky", "Kiki", "Sunny", "Jada", "Dylan"
    };
    public static final String[] TTS_VOICE_LABELS = {
            "Cherry / 芊悦",
            "Serena / 苏瑶",
            "Ethan / 晨煦",
            "Chelsie / 千雪",
            "Momo / 茉兔",
            "Vivian / 十三",
            "Moon / 月白",
            "Maia / 四月",
            "Kai / 凯",
            "Nofish / 不吃鱼",
            "Bella / 萌宝",
            "Jennifer / 詹妮弗",
            "Ryan / 甜茶",
            "Katerina / 卡捷琳娜",
            "Aiden / 艾登",
            "Bodega / 西班牙语-博德加",
            "Alek / 俄语-阿列克",
            "Dolce / 意大利语-多尔切",
            "Sohee / 韩语-素熙",
            "Ono Anna / 日语-小野杏",
            "Lenn / 德语-莱恩",
            "Sonrisa / 西班牙语拉美-索尼莎",
            "Emilien / 法语-埃米尔安",
            "Andre / 葡萄牙语欧-安德雷",
            "Radio Gol / 葡萄牙语巴-拉迪奥·戈尔",
            "Eldric Sage / 精品百人-沧明子",
            "Mia / 精品百人-乖小妹",
            "Mochi / 精品百人-沙小弥",
            "Bellona / 精品百人-燕铮莺",
            "Vincent / 精品百人-田叔",
            "Bunny / 精品百人-萌小姬",
            "Neil / 精品百人-阿闻",
            "Elias / 墨讲师",
            "Arthur / 精品百人-徐大爷",
            "Nini / 精品百人-邻家妹妹",
            "Ebona / 精品百人-诡婆婆",
            "Seren / 精品百人-小婉",
            "Pip / 精品百人-调皮小新",
            "Stella / 精品百人-美少女阿月",
            "Li / 南京-老李",
            "Marcus / 陕西-秦川",
            "Roy / 闽南-阿杰",
            "Peter / 天津-李彼得",
            "Eric / 四川-程川",
            "Rocky / 粤语-阿强",
            "Kiki / 粤语-阿清",
            "Sunny / 四川-晴儿",
            "Jada / 上海-阿珍",
            "Dylan / 北京-晓东"
    };

    public final String botNames;
    public final String allowedSessions;
    public final String followUpSenderWhitelist;
    public final String activeMode;
    public final String chatEndpoint;
    public final String apiKey;
    public final String model;
    public final String systemPrompt;
    public final String personaPhotoPath;
    public final String styleReferencePath;
    public final String imageEndpoint;
    public final String imageApiKey;
    public final String imageModel;
    public final String imageSize;
    public final String licensePanelBaseUrl;
    public final String defaultYuanPackage;
    public final String paymentCallbackUrl;
    public final String paymentCallbackSecret;
    public final String ttsVoice;
    public final float ttsSpeed;
    public final String exName;
    public final String exPhotoPath;
    public final String exManualText;
    public final String exProfilePrompt;
    public final int hsPort;
    public final int replyTimeoutMs;
    public final int imageTimeoutMs;
    public final int licensePanelTimeoutMs;
    public final int paymentCallbackTimeoutMs;
    public final int notificationSettleMs;
    public final int sendButtonDelayMs;
    public final int broadcastGapMs;
    public final int broadcastSearchResultWaitMs;
    public final int wechatStepDelayMs;
    public final int notificationShadeSettleMs;
    public final int notificationShadeOcrStableMs;
    public final int notificationOcrRetryDelayMs;
    public final int wechatChatOcrPollMs;
    public final int chatReadyExtraDelayMs;
    public final int broadcastStepDelayMs;
    public final int broadcastSearchAreaStableMs;
    public final int broadcastSearchPollMs;
    public final int shareSelectPollMs;
    public final int shareConfirmPollMs;
    public final int shareSendButtonPollMs;
    public final int shareSubmitPollMs;
    public final int inputModeToggleSettleMs;
    public final int quotedImageOpenDelayMs;
    public final boolean enableImageAnalysis;
    public final boolean enableImage;
    public final boolean enableTroll;
    public final boolean enableLover;
    public final boolean enableMorningGreeting;
    public final boolean enableShutupCooldown;
    public final boolean enableFinance;
    public final boolean enableNews;
    public final boolean enableWeather;
    public final boolean enableLogOverlay;
    public final boolean keepLogOverlayDuringOperation;
    public final boolean enableNoRootKeepAwake;
    public final boolean enablePaymentListener;
    public final boolean enableImageWarmupText;
    public final boolean imageWarmupAsVoice;
    public final boolean enableImageAfterText;
    public final boolean imageAfterAsVoice;
    public final boolean normalReplyAsVoice;
    public final boolean syncInputModeFromVoiceSwitch;
    public final boolean enableExMode;
    public final boolean dropImageTaskOnError;
    public final boolean lockActiveSender;
    public final boolean enableFollowUpWithoutMention;
    public final boolean stayInCodexSession;

    private BotConfig(SharedPreferences prefs) {
        botNames = prefs.getString("botNames", "机器人");
        allowedSessions = prefs.getString("allowedSessions", "顶呱呱\n东海龙宫\n慢友羊毛群\n社会大学交流群");
        followUpSenderWhitelist = prefs.getString("followUpSenderWhitelist", "");
        activeMode = prefs.getString("activeMode", "root");
        chatEndpoint = normalizeChatEndpoint(prefs.getString("chatEndpoint", DEFAULT_CHAT_ENDPOINT));
        apiKey = normalizeApiKey(prefs.getString("apiKey", DEFAULT_API_KEY));
        model = prefs.getString("model", "gpt-5.5");
        systemPrompt = prefs.getString("systemPrompt", "你是微信群里的机器人，回复短、自然、接上下文。");
        personaPhotoPath = prefs.getString("personaPhotoPath", "");
        styleReferencePath = prefs.getString("styleReferencePath", "");
        imageEndpoint = normalizeImageEndpoint(prefs.getString("imageEndpoint", DEFAULT_IMAGE_ENDPOINT));
        imageApiKey = normalizeApiKey(prefs.getString("imageApiKey", DEFAULT_API_KEY));
        imageModel = prefs.getString("imageModel", DEFAULT_IMAGE_MODEL);
        imageSize = prefs.getString("imageSize", "941x1672");
        licensePanelBaseUrl = normalizeBaseUrl(prefs.getString("licensePanelBaseUrl", DEFAULT_LICENSE_PANEL_BASE_URL));
        defaultYuanPackage = normalizePackageName(prefs.getString("defaultYuanPackage", DEFAULT_YUAN_PACKAGE));
        paymentCallbackUrl = prefs.getString("paymentCallbackUrl", DEFAULT_PAYMENT_CALLBACK_URL).trim();
        paymentCallbackSecret = prefs.getString("paymentCallbackSecret", "").trim();
        ttsVoice = normalizeTtsVoice(prefs.getString("ttsVoice", DEFAULT_TTS_VOICE));
        ttsSpeed = normalizeTtsSpeed(prefs.getFloat("ttsSpeed", DEFAULT_TTS_SPEED));
        exName = prefs.getString("exName", "前任").trim();
        exPhotoPath = prefs.getString("exPhotoPath", "").trim();
        exManualText = prefs.getString("exManualText", "").trim();
        exProfilePrompt = prefs.getString("exProfilePrompt", "").trim();
        hsPort = clampInt(prefs.getInt("hsPort", 9010), 1024, 65535);
        replyTimeoutMs = clampInt(prefs.getInt("replyTimeoutMs", 60000), 5000, 180000);
        imageTimeoutMs = clampInt(prefs.getInt("imageTimeoutMs", 180000), 10000, 300000);
        licensePanelTimeoutMs = clampInt(prefs.getInt("licensePanelTimeoutMs", 30000), 5000, 120000);
        paymentCallbackTimeoutMs = clampInt(prefs.getInt("paymentCallbackTimeoutMs", 10000), 3000, 60000);
        notificationSettleMs = clampInt(prefs.getInt("notificationSettleMs", 2600), 300, 15000);
        sendButtonDelayMs = clampInt(prefs.getInt("sendButtonDelayMs", 500), 0, 5000);
        broadcastGapMs = clampInt(prefs.getInt("broadcastGapMs", 8000), 1500, 60000);
        broadcastSearchResultWaitMs = clampInt(prefs.getInt("broadcastSearchResultWaitMs", 6000), 2500, 12000);
        wechatStepDelayMs = clampInt(prefs.getInt("wechatStepDelayMs", 800), 100, 10000);
        notificationShadeSettleMs = clampInt(prefs.getInt("notificationShadeSettleMs", 1600), 300, 10000);
        notificationShadeOcrStableMs = clampInt(prefs.getInt("notificationShadeOcrStableMs", 800), 100, 10000);
        notificationOcrRetryDelayMs = clampInt(prefs.getInt("notificationOcrRetryDelayMs", 800), 100, 10000);
        wechatChatOcrPollMs = clampInt(prefs.getInt("wechatChatOcrPollMs", 600), 100, 5000);
        chatReadyExtraDelayMs = clampInt(prefs.getInt("chatReadyExtraDelayMs", 2000), 0, 10000);
        broadcastStepDelayMs = clampInt(prefs.getInt("broadcastStepDelayMs", 650), 100, 10000);
        broadcastSearchAreaStableMs = clampInt(prefs.getInt("broadcastSearchAreaStableMs", 800), 100, 10000);
        broadcastSearchPollMs = clampInt(prefs.getInt("broadcastSearchPollMs", 500), 100, 5000);
        shareSelectPollMs = clampInt(prefs.getInt("shareSelectPollMs", 220), 100, 5000);
        shareConfirmPollMs = clampInt(prefs.getInt("shareConfirmPollMs", 250), 100, 5000);
        shareSendButtonPollMs = clampInt(prefs.getInt("shareSendButtonPollMs", 220), 100, 5000);
        shareSubmitPollMs = clampInt(prefs.getInt("shareSubmitPollMs", 350), 100, 5000);
        inputModeToggleSettleMs = clampInt(prefs.getInt("inputModeToggleSettleMs", 1200), 300, 10000);
        quotedImageOpenDelayMs = clampInt(prefs.getInt("quotedImageOpenDelayMs", 1000), 300, 5000);
        enableImageAnalysis = prefs.getBoolean("enableImageAnalysis", true);
        enableImage = prefs.getBoolean("enableImage", true);
        enableTroll = prefs.getBoolean("enableTroll", true);
        enableLover = prefs.getBoolean("enableLover", true);
        enableMorningGreeting = prefs.getBoolean("enableMorningGreeting", true);
        enableShutupCooldown = prefs.getBoolean("enableShutupCooldown", true);
        enableFinance = prefs.getBoolean("enableFinance", true);
        enableNews = prefs.getBoolean("enableNews", true);
        enableWeather = prefs.getBoolean("enableWeather", true);
        enableLogOverlay = prefs.getBoolean("enableLogOverlay", false);
        keepLogOverlayDuringOperation = prefs.getBoolean("keepLogOverlayDuringOperation", true);
        enableNoRootKeepAwake = prefs.getBoolean("enableNoRootKeepAwake", true);
        enablePaymentListener = prefs.getBoolean("enablePaymentListener", true);
        enableImageWarmupText = prefs.getBoolean("enableImageWarmupText", true);
        imageWarmupAsVoice = prefs.getBoolean("imageWarmupAsVoice", false);
        enableImageAfterText = prefs.getBoolean("enableImageAfterText", true);
        imageAfterAsVoice = prefs.getBoolean("imageAfterAsVoice", false);
        normalReplyAsVoice = prefs.getBoolean("normalReplyAsVoice", false);
        syncInputModeFromVoiceSwitch = prefs.getBoolean("syncInputModeFromVoiceSwitch", false);
        enableExMode = prefs.getBoolean("enableExMode", false);
        dropImageTaskOnError = prefs.getBoolean("dropImageTaskOnError", true);
        lockActiveSender = prefs.getBoolean("lockActiveSender", true);
        enableFollowUpWithoutMention = prefs.getBoolean("enableFollowUpWithoutMention", true);
        stayInCodexSession = prefs.getBoolean("stayInCodexSession", false);
    }

    public static BotConfig load(Context context) {
        return new BotConfig(prefs(context));
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAllowedSession(String session) {
        if (session == null || session.trim().isEmpty()) {
            return false;
        }
        String normalized = session.trim();
        String[] rows = allowedSessions.split("[,，\\n\\r]+");
        for (String row : rows) {
            if (normalized.equals(row.trim())) {
                return true;
            }
        }
        return false;
    }

    public List<String> allowedSessionList() {
        List<String> list = new ArrayList<>();
        String[] rows = allowedSessions.split("[,，\\n\\r]+");
        for (String row : rows) {
            String value = row == null ? "" : row.trim();
            if (!value.isEmpty() && !list.contains(value)) {
                list.add(value);
            }
        }
        return list;
    }

    public boolean isBotMentioned(String text) {
        if (text == null) {
            return false;
        }
        String[] names = botNames.split("[,，\\n\\r]+");
        for (String name : names) {
            String n = name.trim();
            if (!n.isEmpty() && (text.contains("@" + n) || text.contains(n))) {
                return true;
            }
        }
        return false;
    }

    public boolean isFollowUpSenderAllowed(String senderName) {
        String sender = senderName == null ? "" : senderName.trim();
        if (sender.isEmpty()) {
            return false;
        }
        String[] rows = followUpSenderWhitelist.split("[,，\\n\\r]+");
        for (String row : rows) {
            String value = row == null ? "" : row.trim();
            if (!value.isEmpty() && sender.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public String primaryBotName() {
        String[] names = botNames.split("[,，\\n\\r]+");
        for (String name : names) {
            String n = name.trim();
            if (!n.isEmpty()) {
                return n;
            }
        }
        return "机器人";
    }

    public String selfieReferencePhotoPath() {
        if (enableExMode && hasLocalFile(exPhotoPath)) {
            return exPhotoPath;
        }
        return personaPhotoPath;
    }

    private static boolean hasLocalFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        File file = new File(path.trim());
        return file.exists() && file.length() > 0;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeChatEndpoint(String value) {
        String endpoint = value == null ? "" : value.trim();
        if (endpoint.isEmpty() || "http://127.0.0.1:8317/v1/chat/completions".equals(endpoint)) {
            return DEFAULT_CHAT_ENDPOINT;
        }
        return endpoint;
    }

    private static String normalizeApiKey(String value) {
        String key = value == null ? "" : value.trim();
        return key.isEmpty() ? DEFAULT_API_KEY : key;
    }

    private static String normalizeImageEndpoint(String value) {
        String endpoint = value == null ? "" : value.trim();
        if (endpoint.isEmpty() || endpoint.contains(":3001/api/generate-image")) {
            return DEFAULT_IMAGE_ENDPOINT;
        }
        return endpoint.replaceAll("/+$", "");
    }

    private static String normalizeBaseUrl(String value) {
        String endpoint = value == null ? "" : value.trim();
        return endpoint.isEmpty() ? DEFAULT_LICENSE_PANEL_BASE_URL : endpoint.replaceAll("/+$", "");
    }

    private static String normalizePackageName(String value) {
        String packageName = value == null ? "" : value.trim();
        return packageName.isEmpty() ? DEFAULT_YUAN_PACKAGE : packageName;
    }

    public static String normalizeTtsVoice(String value) {
        String voice = value == null ? "" : value.trim();
        if (voice.isEmpty()) {
            return DEFAULT_TTS_VOICE;
        }
        for (int i = 0; i < TTS_VOICE_IDS.length; i++) {
            if (voice.equalsIgnoreCase(TTS_VOICE_IDS[i])) {
                return TTS_VOICE_IDS[i];
            }
            String label = TTS_VOICE_LABELS[i];
            int slash = label.indexOf(" / ");
            int dash = label.indexOf(" - ");
            String id = slash > 0 ? label.substring(0, slash).trim() : label;
            String name = slash > 0 ? label.substring(slash + 3).trim() : (dash > 0 ? label.substring(0, dash).trim() : label);
            if (voice.equalsIgnoreCase(label) || voice.equalsIgnoreCase(id) || voice.equalsIgnoreCase(name)) {
                return TTS_VOICE_IDS[i];
            }
        }
        return DEFAULT_TTS_VOICE;
    }

    public static float normalizeTtsSpeed(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return DEFAULT_TTS_SPEED;
        }
        return Math.max(0.5f, Math.min(2.0f, value));
    }
}
