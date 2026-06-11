package com.vxbot.wechatbot;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_PERSONA_PHOTO = 6603;
    private static final int REQ_STYLE_PHOTO = 6604;
    private static final int REQ_EX_PHOTO = 6605;
    private static final int REQ_EX_SOURCE_IMAGE = 6606;
    private static final int REQ_EX_SOURCE_FILE = 6607;
    private static final int BG = 0xFFF6F8FB;
    private static final int INK = 0xFF182033;
    private static final int MUTED = 0xFF5E6B82;
    private static final int NAV = 0xFF0F172A;
    private static final int TEAL = 0xFF0EA5A3;

    private final List<LinearLayout> pages = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private final ExecutorService uiWorker = Executors.newSingleThreadExecutor();

    private EditText botNames;
    private EditText allowedSessions;
    private EditText followUpSenderWhitelist;
    private EditText chatEndpoint;
    private EditText apiKey;
    private EditText model;
    private EditText systemPrompt;
    private ImageView personaImage;
    private TextView personaInfo;
    private ImageView styleImage;
    private TextView styleInfo;
    private ImageView exImage;
    private TextView exInfo;
    private TextView exSourceInfo;
    private EditText exName;
    private EditText exManualText;
    private EditText exProfilePrompt;
    private EditText imageEndpoint;
    private EditText imageApiKey;
    private EditText imageModel;
    private EditText imageSize;
    private EditText licensePanelBaseUrl;
    private EditText licensePanelTimeoutMs;
    private EditText defaultYuanPackage;
    private EditText paymentCallbackUrl;
    private EditText paymentCallbackSecret;
    private EditText paymentCallbackTimeoutMs;
    private Spinner ttsVoice;
    private EditText ttsSpeed;
    private EditText hsPort;
    private EditText replyTimeoutMs;
    private EditText imageTimeoutMs;
    private EditText notificationSettleMs;
    private EditText sendButtonDelayMs;
    private EditText wechatStepDelayMs;
    private EditText notificationShadeSettleMs;
    private EditText notificationShadeOcrStableMs;
    private EditText notificationOcrRetryDelayMs;
    private EditText wechatChatOcrPollMs;
    private EditText chatReadyExtraDelayMs;
    private EditText broadcastStepDelayMs;
    private EditText broadcastSearchAreaStableMs;
    private EditText broadcastSearchPollMs;
    private EditText shareSelectPollMs;
    private EditText shareConfirmPollMs;
    private EditText shareSendButtonPollMs;
    private EditText shareSubmitPollMs;
    private EditText inputModeToggleSettleMs;
    private EditText quotedImageOpenDelayMs;
    private EditText broadcastMessage;
    private EditText broadcastGapMs;
    private EditText broadcastSearchResultWaitMs;
    private Spinner activeMode;
    private Switch enableImageAnalysis;
    private Switch enableImage;
    private Switch enableTroll;
    private Switch enableLover;
    private Switch enableMorningGreeting;
    private Switch enableShutupCooldown;
    private Switch enableFinance;
    private Switch enableNews;
    private Switch enableWeather;
    private Switch enableLogOverlay;
    private Switch keepLogOverlayDuringOperation;
    private Switch enableNoRootKeepAwake;
    private Switch enableExMode;
    private Switch enablePaymentListener;
    private Switch enableImageWarmupText;
    private Switch imageWarmupAsVoice;
    private Switch enableImageAfterText;
    private Switch imageAfterAsVoice;
    private Switch normalReplyAsVoice;
    private Switch dropImageTaskOnError;
    private Switch lockActiveSender;
    private Switch enableFollowUpWithoutMention;
    private Switch stayInCodexSession;
    private TextView logView;
    private TextView statusView;
    private boolean loadingConfig;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLogs();
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadConfigIntoUi();
        selectTab(0);
        requestPostNotificationIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(BotLog.ACTION_LOG_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        refreshLogs();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(logReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiWorker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setBackgroundColor(BG);

        ScrollView railScroll = new ScrollView(this);
        railScroll.setFillViewport(true);
        railScroll.setBackgroundColor(NAV);
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(10), dp(18), dp(10), dp(18));
        railScroll.addView(rail, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        shell.addView(railScroll, new LinearLayout.LayoutParams(dp(124), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView brand = text("HS Bot", 20, Color.WHITE, Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, 0, 0, dp(14));
        rail.addView(brand, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addTab(rail, "状态", 0);
        addTab(rail, "基础", 1);
        addTab(rail, "上游", 2);
        addTab(rail, "人物", 3);
        addTab(rail, "图片", 4);
        addTab(rail, "蒸馏", 5);
        addTab(rail, "功能", 6);
        addTab(rail, "群发", 7);
        addTab(rail, "支付", 8);
        addTab(rail, "延时", 9);
        addTab(rail, "日志", 10);

        ScrollView contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(16), dp(14), dp(24));
        contentScroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        shell.addView(contentScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        TextView title = text("微信机器人", 26, INK, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(12));
        content.addView(title);

        LinearLayout statusPage = page(content, "运行状态");
        statusView = text("", 15, INK, Typeface.BOLD);
        statusView.setPadding(0, 0, 0, dp(12));
        statusPage.addView(statusView);
        statusPage.addView(buttonRow(
                button("保存配置", v -> saveConfig()),
                button("启动机器人", v -> startBotFromUi())));
        statusPage.addView(buttonRow(
                button("停止", v -> {
                    Intent intent = new Intent(this, BotService.class);
                    intent.setAction(BotService.ACTION_STOP);
                    startService(intent);
                }),
                button("测试 hs", v -> testHs())));
        statusPage.addView(buttonRow(
                button("通知监听设置", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))),
                button("通知权限", v -> requestPostNotificationIfNeeded())));
        statusPage.addView(buttonRow(
                button("悬浮窗权限", v -> openOverlaySettings()),
                button("Shizuku 授权", v -> toast(ShizukuBridge.requestPermission()))));
        statusPage.addView(buttonRow(
                button("辅助功能", v -> openAccessibilitySettings()),
                button("刷新状态", v -> refreshStatus())));
        statusPage.addView(buttonRow(
                button("电池白名单", v -> openBatteryWhitelistSettings())));
        statusPage.addView(buttonRow(
                button("亮度权限", v -> openWriteSettingsPermission()),
                button("屏幕最亮", v -> restoreScreenBrightnessFromUi())));

        LinearLayout basicPage = page(content, "基础配置");
        botNames = edit(basicPage, "机器人名称/别名", "机器人", false);
        allowedSessions = edit(basicPage, "白名单群，一行一个", "", true);
        followUpSenderWhitelist = edit(basicPage, "续聊控制人白名单，一行一个微信名", "", true);
        activeMode = spinner(basicPage, "hs 启动方式", new String[]{"root", "shizuku"});
        hsPort = edit(basicPage, "hs 端口", "9010", false);

        LinearLayout upstreamPage = page(content, "上游模型");
        chatEndpoint = edit(upstreamPage, "OpenAI 兼容接口", BotConfig.DEFAULT_CHAT_ENDPOINT, false);
        apiKey = edit(upstreamPage, "API Key", BotConfig.DEFAULT_API_KEY, false);
        model = edit(upstreamPage, "模型", "gpt-5.5", false);
        systemPrompt = edit(upstreamPage, "基础 system prompt", "", true);
        replyTimeoutMs = edit(upstreamPage, "回复超时毫秒", "60000", false);
        licensePanelBaseUrl = edit(upstreamPage, "注册机面板", BotConfig.DEFAULT_LICENSE_PANEL_BASE_URL, false);
        licensePanelTimeoutMs = edit(upstreamPage, "注册机超时毫秒", "30000", false);
        defaultYuanPackage = edit(upstreamPage, "元统计默认包名", BotConfig.DEFAULT_YUAN_PACKAGE, false);

        LinearLayout personaPage = page(content, "人物形象");
        personaInfo = text("", 14, MUTED, Typeface.BOLD);
        personaInfo.setPadding(0, 0, 0, dp(10));
        personaPage.addView(personaInfo);
        personaImage = new ImageView(this);
        personaImage.setAdjustViewBounds(true);
        personaImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        personaImage.setBackground(round(Color.rgb(248, 250, 252), dp(12), 1, Color.rgb(226, 232, 240)));
        personaPage.addView(personaImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));
        personaPage.addView(buttonRow(
                button("上传", v -> choosePersonaPhoto()),
                button("清除", v -> clearPersonaPhoto())));

        LinearLayout imagePage = page(content, "图片与发送");
        styleInfo = text("", 14, MUTED, Typeface.BOLD);
        styleInfo.setPadding(0, 0, 0, dp(10));
        imagePage.addView(styleInfo);
        styleImage = new ImageView(this);
        styleImage.setAdjustViewBounds(true);
        styleImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        styleImage.setBackground(round(Color.rgb(248, 250, 252), dp(12), 1, Color.rgb(226, 232, 240)));
        imagePage.addView(styleImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        imagePage.addView(buttonRow(
                button("上传", v -> chooseStyleReferencePhoto()),
                button("清除", v -> clearStyleReferencePhoto())));
        imageEndpoint = edit(imagePage, "图片上游基址", BotConfig.DEFAULT_IMAGE_ENDPOINT, false);
        imageApiKey = edit(imagePage, "图片 API Key", BotConfig.DEFAULT_API_KEY, false);
        imageModel = edit(imagePage, "图片模型", BotConfig.DEFAULT_IMAGE_MODEL, false);
        imageSize = edit(imagePage, "图片分辨率", "941x1672", false);
        imageTimeoutMs = edit(imagePage, "图片超时毫秒", "180000", false);
        enableImageWarmupText = switchRow(imagePage, "拍照前置话术");
        imageWarmupAsVoice = switchRow(imagePage, "前置话术用语音");
        enableImageAfterText = switchRow(imagePage, "拍照后置话术");
        imageAfterAsVoice = switchRow(imagePage, "后置话术用语音");
        dropImageTaskOnError = switchRow(imagePage, "图片失败直接丢弃任务并退后台");
        notificationSettleMs = edit(imagePage, "通知点开后等待毫秒", "2600", false);
        sendButtonDelayMs = edit(imagePage, "输入后找发送按钮延时毫秒", "500", false);

        LinearLayout exPage = page(content, "蒸馏模式");
        enableExMode = switchRow(exPage, "打开蒸馏模式");
        exName = edit(exPage, "人物标签", "人物", false);
        exInfo = text("", 14, MUTED, Typeface.BOLD);
        exInfo.setPadding(0, dp(8), 0, dp(10));
        exPage.addView(exInfo);
        exImage = new ImageView(this);
        exImage.setAdjustViewBounds(true);
        exImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        exImage.setBackground(round(Color.rgb(248, 250, 252), dp(12), 1, Color.rgb(226, 232, 240)));
        exPage.addView(exImage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        exPage.addView(buttonRow(
                button("照片", v -> chooseExPhoto()),
                button("清除", v -> clearExPhoto())));
        exSourceInfo = text("", 14, MUTED, Typeface.BOLD);
        exSourceInfo.setPadding(0, dp(6), 0, dp(8));
        exPage.addView(exSourceInfo);
        exPage.addView(buttonRow(
                button("截图", v -> chooseExSourceImage()),
                button("文件", v -> chooseExSourceFile())));
        exManualText = edit(exPage, "粘贴/口述材料", "聊天片段、口头禅、争吵模式、共同经历", true);
        exProfilePrompt = edit(exPage, "蒸馏结果", "蒸馏完成后会写入这里", true);
        exPage.addView(buttonRow(
                button("保存", v -> saveConfig()),
                button("蒸馏", v -> distillExPartner())));

        LinearLayout featuresPage = page(content, "功能开关");
        enableImageAnalysis = switchRow(featuresPage, "图片分析/识图");
        enableImage = switchRow(featuresPage, "图片/自拍/比基尼触发");
        enableTroll = switchRow(featuresPage, "赛博喷子/对喷");
        enableLover = switchRow(featuresPage, "恋人模式");
        enableMorningGreeting = switchRow(featuresPage, "每天早安问好");
        enableShutupCooldown = switchRow(featuresPage, "闭嘴后本群 30 分钟忽略");
        enableFinance = switchRow(featuresPage, "金融/股票/虚拟币查询");
        enableNews = switchRow(featuresPage, "新闻/微博热点");
        enableWeather = switchRow(featuresPage, "天气查询");
        ttsVoice = spinner(featuresPage, "TTS 语音角色", BotConfig.TTS_VOICE_LABELS);
        ttsSpeed = edit(featuresPage, "TTS 语速倍率 0.5-2.0", "1.0", false);
        normalReplyAsVoice = switchRow(featuresPage, "普通聊天用语音回复");
        enableNoRootKeepAwake = switchRow(featuresPage, "无 root 低亮防熄屏保活");
        enableLogOverlay = switchRow(featuresPage, "悬浮实时日志");
        enableLogOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingConfig) {
                return;
            }
            BotConfig.prefs(this).edit().putBoolean("enableLogOverlay", isChecked).apply();
            String text = isChecked ? "悬浮实时日志已开启" : "悬浮实时日志已关闭";
            BotLog.i(this, "config.overlay", text);
            toast(text);
            refreshStatus();
        });
        keepLogOverlayDuringOperation = switchRow(featuresPage, "微信操作时仍显示悬浮日志");
        lockActiveSender = switchRow(featuresPage, "续聊锁定最初 @ 的群员");
        enableFollowUpWithoutMention = switchRow(featuresPage, "续聊免 @ 记住发起人");
        stayInCodexSession = switchRow(featuresPage, "Codex 会话保持前台");
        featuresPage.addView(buttonRow(button("保存功能开关", v -> saveConfig())));

        LinearLayout broadcastPage = page(content, "白名单群发");
        broadcastMessage = edit(broadcastPage, "群发消息内容", "", true);
        broadcastGapMs = edit(broadcastPage, "群间隔毫秒", "8000", false);
        broadcastSearchResultWaitMs = edit(broadcastPage, "搜索结果等待毫秒", "6000", false);
        broadcastPage.addView(buttonRow(
                button("保存", v -> saveConfig()),
                button("发送", v -> startBroadcastText())));

        LinearLayout paymentPage = page(content, "支付监听");
        enablePaymentListener = switchRow(paymentPage, "监听微信收款通知");
        paymentCallbackUrl = edit(paymentPage, "回调地址", BotConfig.DEFAULT_PAYMENT_CALLBACK_URL, false);
        paymentCallbackSecret = edit(paymentPage, "回调密钥", "", false);
        paymentCallbackTimeoutMs = edit(paymentPage, "回调超时毫秒", "10000", false);
        paymentPage.addView(buttonRow(button("保存", v -> saveConfig())));

        LinearLayout delayPage = page(content, "OCR 与步骤延时");
        wechatStepDelayMs = edit(delayPage, "微信通用步骤等待毫秒", "800", false);
        notificationShadeSettleMs = edit(delayPage, "通知栏展开等待毫秒", "1600", false);
        notificationShadeOcrStableMs = edit(delayPage, "通知栏 OCR 稳定等待毫秒", "800", false);
        notificationOcrRetryDelayMs = edit(delayPage, "通知 OCR 重试等待毫秒", "800", false);
        wechatChatOcrPollMs = edit(delayPage, "会话页 OCR 轮询间隔毫秒", "600", false);
        chatReadyExtraDelayMs = edit(delayPage, "进入会话后底部栏等待毫秒", "2000", false);
        broadcastStepDelayMs = edit(delayPage, "群发步骤等待毫秒", "650", false);
        broadcastSearchAreaStableMs = edit(delayPage, "群发搜索结果稳定毫秒", "800", false);
        broadcastSearchPollMs = edit(delayPage, "群发搜索 OCR 轮询毫秒", "500", false);
        shareSelectPollMs = edit(delayPage, "图片分享选择页 OCR 轮询毫秒", "220", false);
        shareConfirmPollMs = edit(delayPage, "图片分享确认页 OCR 轮询毫秒", "250", false);
        shareSendButtonPollMs = edit(delayPage, "图片分享发送按钮轮询毫秒", "220", false);
        shareSubmitPollMs = edit(delayPage, "图片分享提交后轮询毫秒", "350", false);
        inputModeToggleSettleMs = edit(delayPage, "语音/文字切换稳定等待毫秒", "1200", false);
        quotedImageOpenDelayMs = edit(delayPage, "引用图点开等待毫秒", "1000", false);
        delayPage.addView(buttonRow(button("保存延时", v -> saveConfig())));

        LinearLayout logsPage = page(content, "实时日志");
        logView = text("", 12, Color.rgb(226, 232, 240), Typeface.NORMAL);
        logView.setTextIsSelectable(true);
        logView.setBackground(round(Color.rgb(15, 23, 42), dp(12), 0, 0));
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logsPage.addView(logView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(520)));

        setContentView(shell);
    }

    private void addTab(LinearLayout rail, String label, int index) {
        TextView tab = text(label, 15, Color.WHITE, Typeface.BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(6), dp(12), dp(6), dp(12));
        tab.setOnClickListener(v -> selectTab(index));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        rail.addView(tab, lp);
        tabs.add(tab);
    }

    private void selectTab(int index) {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabs.get(i).setTextColor(i == index ? NAV : Color.WHITE);
            tabs.get(i).setBackground(i == index ? round(Color.WHITE, dp(18), 0, 0) : round(0x223B475C, dp(18), 0, 0));
        }
        refreshLogs();
        refreshStatus();
    }

    private LinearLayout page(LinearLayout content, String title) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(16), dp(14), dp(16));
        page.setBackground(round(Color.WHITE, dp(18), 1, Color.rgb(226, 232, 240)));
        TextView pageTitle = text(title, 21, INK, Typeface.BOLD);
        pageTitle.setPadding(0, 0, 0, dp(12));
        page.addView(pageTitle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(16));
        content.addView(page, lp);
        pages.add(page);
        return page;
    }

    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(rowLp);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(i == 0 ? 0 : dp(6), 0, i == buttons.length - 1 ? 0 : dp(6), 0);
            row.addView(buttons[i], lp);
        }
        return row;
    }

    private void loadConfigIntoUi() {
        loadingConfig = true;
        try {
            BotConfig config = BotConfig.load(this);
            botNames.setText(config.botNames);
            allowedSessions.setText(config.allowedSessions);
            followUpSenderWhitelist.setText(config.followUpSenderWhitelist);
            activeMode.setSelection("shizuku".equals(config.activeMode) ? 1 : 0);
            chatEndpoint.setText(config.chatEndpoint);
            apiKey.setText(config.apiKey);
            model.setText(config.model);
            systemPrompt.setText(config.systemPrompt);
            refreshPersonaPreview(config.personaPhotoPath);
            refreshStylePreview(config.styleReferencePath);
            refreshExPreview(config.exPhotoPath);
            refreshExSourceInfo();
            exName.setText(config.exName);
            exManualText.setText(config.exManualText);
            exProfilePrompt.setText(config.exProfilePrompt);
            imageEndpoint.setText(config.imageEndpoint);
            imageApiKey.setText(config.imageApiKey);
            imageModel.setText(config.imageModel);
            imageSize.setText(config.imageSize);
            licensePanelBaseUrl.setText(config.licensePanelBaseUrl);
            licensePanelTimeoutMs.setText(String.valueOf(config.licensePanelTimeoutMs));
            defaultYuanPackage.setText(config.defaultYuanPackage);
            paymentCallbackUrl.setText(config.paymentCallbackUrl);
            paymentCallbackSecret.setText(config.paymentCallbackSecret);
            paymentCallbackTimeoutMs.setText(String.valueOf(config.paymentCallbackTimeoutMs));
            ttsVoice.setSelection(ttsVoiceIndex(config.ttsVoice));
            ttsSpeed.setText(String.valueOf(config.ttsSpeed));
            hsPort.setText(String.valueOf(config.hsPort));
            replyTimeoutMs.setText(String.valueOf(config.replyTimeoutMs));
            imageTimeoutMs.setText(String.valueOf(config.imageTimeoutMs));
            notificationSettleMs.setText(String.valueOf(config.notificationSettleMs));
            sendButtonDelayMs.setText(String.valueOf(config.sendButtonDelayMs));
            wechatStepDelayMs.setText(String.valueOf(config.wechatStepDelayMs));
            notificationShadeSettleMs.setText(String.valueOf(config.notificationShadeSettleMs));
            notificationShadeOcrStableMs.setText(String.valueOf(config.notificationShadeOcrStableMs));
            notificationOcrRetryDelayMs.setText(String.valueOf(config.notificationOcrRetryDelayMs));
            wechatChatOcrPollMs.setText(String.valueOf(config.wechatChatOcrPollMs));
            chatReadyExtraDelayMs.setText(String.valueOf(config.chatReadyExtraDelayMs));
            broadcastStepDelayMs.setText(String.valueOf(config.broadcastStepDelayMs));
            broadcastSearchAreaStableMs.setText(String.valueOf(config.broadcastSearchAreaStableMs));
            broadcastSearchPollMs.setText(String.valueOf(config.broadcastSearchPollMs));
            shareSelectPollMs.setText(String.valueOf(config.shareSelectPollMs));
            shareConfirmPollMs.setText(String.valueOf(config.shareConfirmPollMs));
            shareSendButtonPollMs.setText(String.valueOf(config.shareSendButtonPollMs));
            shareSubmitPollMs.setText(String.valueOf(config.shareSubmitPollMs));
            inputModeToggleSettleMs.setText(String.valueOf(config.inputModeToggleSettleMs));
            quotedImageOpenDelayMs.setText(String.valueOf(config.quotedImageOpenDelayMs));
            broadcastGapMs.setText(String.valueOf(config.broadcastGapMs));
            broadcastSearchResultWaitMs.setText(String.valueOf(config.broadcastSearchResultWaitMs));
            enableImageAnalysis.setChecked(config.enableImageAnalysis);
            enableImage.setChecked(config.enableImage);
            enableTroll.setChecked(config.enableTroll);
            enableLover.setChecked(config.enableLover);
            enableMorningGreeting.setChecked(config.enableMorningGreeting);
            enableShutupCooldown.setChecked(config.enableShutupCooldown);
            enableFinance.setChecked(config.enableFinance);
            enableNews.setChecked(config.enableNews);
            enableWeather.setChecked(config.enableWeather);
            normalReplyAsVoice.setChecked(config.normalReplyAsVoice);
            enableNoRootKeepAwake.setChecked(config.enableNoRootKeepAwake);
            enableLogOverlay.setChecked(config.enableLogOverlay);
            keepLogOverlayDuringOperation.setChecked(config.keepLogOverlayDuringOperation);
            enableExMode.setChecked(config.enableExMode);
            enablePaymentListener.setChecked(config.enablePaymentListener);
            enableImageWarmupText.setChecked(config.enableImageWarmupText);
            imageWarmupAsVoice.setChecked(config.imageWarmupAsVoice);
            enableImageAfterText.setChecked(config.enableImageAfterText);
            imageAfterAsVoice.setChecked(config.imageAfterAsVoice);
            dropImageTaskOnError.setChecked(config.dropImageTaskOnError);
            lockActiveSender.setChecked(config.lockActiveSender);
            enableFollowUpWithoutMention.setChecked(config.enableFollowUpWithoutMention);
            stayInCodexSession.setChecked(config.stayInCodexSession);
        } finally {
            loadingConfig = false;
        }
    }

    private void saveConfig() {
        SharedPreferences prefs = BotConfig.prefs(this);
        boolean oldNormalVoice = prefs.getBoolean("normalReplyAsVoice", false);
        boolean oldWarmupVoice = prefs.getBoolean("imageWarmupAsVoice", false);
        boolean oldAfterVoice = prefs.getBoolean("imageAfterAsVoice", false);
        boolean newNormalVoice = normalReplyAsVoice.isChecked();
        boolean newWarmupVoice = imageWarmupAsVoice.isChecked();
        boolean newAfterVoice = imageAfterAsVoice.isChecked();
        SharedPreferences.Editor e = prefs.edit();
        e.putString("botNames", botNames.getText().toString());
        e.putString("allowedSessions", allowedSessions.getText().toString());
        e.putString("followUpSenderWhitelist", followUpSenderWhitelist.getText().toString());
        e.putString("activeMode", activeMode.getSelectedItem().toString());
        e.putString("chatEndpoint", chatEndpoint.getText().toString());
        e.putString("apiKey", apiKey.getText().toString());
        e.putString("model", model.getText().toString());
        e.putString("systemPrompt", systemPrompt.getText().toString());
        e.putString("imageEndpoint", imageEndpoint.getText().toString());
        e.putString("imageApiKey", imageApiKey.getText().toString());
        e.putString("imageModel", imageModel.getText().toString());
        e.putString("imageSize", imageSize.getText().toString());
        e.putString("licensePanelBaseUrl", licensePanelBaseUrl.getText().toString());
        e.putString("defaultYuanPackage", defaultYuanPackage.getText().toString());
        e.putString("paymentCallbackUrl", paymentCallbackUrl.getText().toString());
        e.putString("paymentCallbackSecret", paymentCallbackSecret.getText().toString());
        e.putString("ttsVoice", selectedTtsVoiceId());
        e.putFloat("ttsSpeed", BotConfig.normalizeTtsSpeed(floatValue(ttsSpeed, BotConfig.DEFAULT_TTS_SPEED)));
        e.putString("exName", exName.getText().toString());
        e.putString("exManualText", exManualText.getText().toString());
        e.putString("exProfilePrompt", exProfilePrompt.getText().toString());
        e.putInt("hsPort", intValue(hsPort, 9010));
        e.putInt("replyTimeoutMs", intValue(replyTimeoutMs, 60000));
        e.putInt("imageTimeoutMs", intValue(imageTimeoutMs, 180000));
        e.putInt("licensePanelTimeoutMs", intValue(licensePanelTimeoutMs, 30000));
        e.putInt("paymentCallbackTimeoutMs", intValue(paymentCallbackTimeoutMs, 10000));
        e.putInt("notificationSettleMs", intValue(notificationSettleMs, 2600));
        e.putInt("sendButtonDelayMs", intValue(sendButtonDelayMs, 500));
        e.putInt("wechatStepDelayMs", intValue(wechatStepDelayMs, 800));
        e.putInt("notificationShadeSettleMs", intValue(notificationShadeSettleMs, 1600));
        e.putInt("notificationShadeOcrStableMs", intValue(notificationShadeOcrStableMs, 800));
        e.putInt("notificationOcrRetryDelayMs", intValue(notificationOcrRetryDelayMs, 800));
        e.putInt("wechatChatOcrPollMs", intValue(wechatChatOcrPollMs, 600));
        e.putInt("chatReadyExtraDelayMs", intValue(chatReadyExtraDelayMs, 2000));
        e.putInt("broadcastStepDelayMs", intValue(broadcastStepDelayMs, 650));
        e.putInt("broadcastSearchAreaStableMs", intValue(broadcastSearchAreaStableMs, 800));
        e.putInt("broadcastSearchPollMs", intValue(broadcastSearchPollMs, 500));
        e.putInt("shareSelectPollMs", intValue(shareSelectPollMs, 220));
        e.putInt("shareConfirmPollMs", intValue(shareConfirmPollMs, 250));
        e.putInt("shareSendButtonPollMs", intValue(shareSendButtonPollMs, 220));
        e.putInt("shareSubmitPollMs", intValue(shareSubmitPollMs, 350));
        e.putInt("inputModeToggleSettleMs", intValue(inputModeToggleSettleMs, 1200));
        e.putInt("quotedImageOpenDelayMs", intValue(quotedImageOpenDelayMs, 1000));
        e.putInt("broadcastGapMs", intValue(broadcastGapMs, 8000));
        e.putInt("broadcastSearchResultWaitMs", intValue(broadcastSearchResultWaitMs, 6000));
        e.putBoolean("enableImageAnalysis", enableImageAnalysis.isChecked());
        e.putBoolean("enableImage", enableImage.isChecked());
        e.putBoolean("enableTroll", enableTroll.isChecked());
        e.putBoolean("enableLover", enableLover.isChecked());
        e.putBoolean("enableMorningGreeting", enableMorningGreeting.isChecked());
        e.putBoolean("enableShutupCooldown", enableShutupCooldown.isChecked());
        e.putBoolean("enableFinance", enableFinance.isChecked());
        e.putBoolean("enableNews", enableNews.isChecked());
        e.putBoolean("enableWeather", enableWeather.isChecked());
        e.putBoolean("normalReplyAsVoice", newNormalVoice);
        e.putBoolean("enableNoRootKeepAwake", enableNoRootKeepAwake.isChecked());
        e.putBoolean("enableLogOverlay", enableLogOverlay.isChecked());
        e.putBoolean("keepLogOverlayDuringOperation", keepLogOverlayDuringOperation.isChecked());
        e.putBoolean("enableExMode", enableExMode.isChecked());
        e.putBoolean("enablePaymentListener", enablePaymentListener.isChecked());
        e.putBoolean("enableImageWarmupText", enableImageWarmupText.isChecked());
        e.putBoolean("imageWarmupAsVoice", newWarmupVoice);
        e.putBoolean("enableImageAfterText", enableImageAfterText.isChecked());
        e.putBoolean("imageAfterAsVoice", newAfterVoice);
        e.putBoolean("dropImageTaskOnError", dropImageTaskOnError.isChecked());
        e.putBoolean("lockActiveSender", lockActiveSender.isChecked());
        e.putBoolean("enableFollowUpWithoutMention", enableFollowUpWithoutMention.isChecked());
        e.putBoolean("stayInCodexSession", stayInCodexSession.isChecked());
        e.apply();
        WechatDriver.syncAllowedSessionInputModes(this, BotConfig.load(this));
        if (oldNormalVoice != newNormalVoice || oldWarmupVoice != newWarmupVoice || oldAfterVoice != newAfterVoice) {
            BotLog.i(this, "input.mode.cache.sync", "语音/文字发送开关变化，已同步白名单输入态");
        }
        if (enableMorningGreeting.isChecked()) {
            MorningGreetingScheduler.schedule(this);
        } else {
            MorningGreetingScheduler.cancel(this);
        }
        ScreenControl.syncForConfig(this, BotConfig.load(this), "ui-save");
        BotLog.i(this, "config.save", "配置已保存");
        toast("配置已保存");
        refreshStatus();
    }

    private void startBroadcastText() {
        saveConfig();
        String text = broadcastMessage == null ? "" : broadcastMessage.getText().toString().trim();
        if (text.isEmpty()) {
            toast("群发消息为空");
            BotLog.e(this, "broadcast.ui.abort", "群发消息为空");
            return;
        }
        Intent intent = new Intent(this, BotService.class);
        intent.setAction(BotService.ACTION_BROADCAST_TEXT);
        intent.putExtra("text", text);
        startForegroundService(intent);
        BotLog.i(this, "broadcast.ui.start", "已提交白名单群发任务 text=" + text);
        toast("群发任务已提交");
        refreshLogs();
    }

    private void choosePersonaPhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PERSONA_PHOTO);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQ_PERSONA_PHOTO);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PERSONA_PHOTO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            savePersonaPhoto(data.getData());
        } else if (requestCode == REQ_STYLE_PHOTO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            saveStyleReferencePhoto(data.getData());
        } else if (requestCode == REQ_EX_PHOTO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            saveExPhoto(data.getData());
        } else if (requestCode == REQ_EX_SOURCE_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            saveExSource(data.getData(), "screenshot");
        } else if (requestCode == REQ_EX_SOURCE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            saveExSource(data.getData(), "file");
        }
    }

    private void savePersonaPhoto(Uri uri) {
        try {
            File dir = new File(getFilesDir(), "persona");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建人物目录");
            }
            File target = new File(dir, "persona_reference.png");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    throw new IllegalStateException("无法读取图片");
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            BotConfig.prefs(this).edit().putString("personaPhotoPath", target.getAbsolutePath()).apply();
            BotLog.i(this, "persona.photo.save", "人物照片已保存 path=" + target.getAbsolutePath());
            refreshPersonaPreview(target.getAbsolutePath());
            toast("人物照片已保存");
        } catch (Exception e) {
            BotLog.e(this, "persona.photo.save.fail", e.getMessage());
            toast("人物照片保存失败，看日志");
        }
    }

    private void clearPersonaPhoto() {
        String path = BotConfig.load(this).personaPhotoPath;
        if (path != null && !path.trim().isEmpty()) {
            try {
                new File(path).delete();
            } catch (Exception ignored) {
            }
        }
        BotConfig.prefs(this).edit().putString("personaPhotoPath", "").apply();
        BotLog.i(this, "persona.photo.clear", "人物照片已清除");
        refreshPersonaPreview("");
        toast("人物照片已清除");
    }

    private void refreshPersonaPreview(String path) {
        if (personaImage == null || personaInfo == null) {
            return;
        }
        File file = path == null || path.trim().isEmpty() ? null : new File(path);
        if (file != null && file.exists() && file.length() > 0) {
            personaImage.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            personaInfo.setText("已上传人物照片：" + file.getName() + " / " + file.length() + " bytes");
        } else {
            personaImage.setImageDrawable(null);
            personaInfo.setText("未上传人物照片。自拍会仅按文字提示生成；上传后会把此照片作为面貌参考。");
        }
    }

    private void chooseStyleReferencePhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_STYLE_PHOTO);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQ_STYLE_PHOTO);
        }
    }

    private void saveStyleReferencePhoto(Uri uri) {
        try {
            File dir = new File(getFilesDir(), "style");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建风格图目录");
            }
            File target = new File(dir, "cool_style_reference.png");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    throw new IllegalStateException("无法读取图片");
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            }
            BotConfig.prefs(this).edit().putString("styleReferencePath", target.getAbsolutePath()).apply();
            BotLog.i(this, "style.photo.save", "风格底图已保存 path=" + target.getAbsolutePath());
            refreshStylePreview(target.getAbsolutePath());
            toast("风格底图已保存");
        } catch (Exception e) {
            BotLog.e(this, "style.photo.save.fail", e.getMessage());
            toast("风格底图保存失败，看日志");
        }
    }

    private void clearStyleReferencePhoto() {
        String path = BotConfig.load(this).styleReferencePath;
        if (path != null && !path.trim().isEmpty()) {
            try {
                new File(path).delete();
            } catch (Exception ignored) {
            }
        }
        BotConfig.prefs(this).edit().putString("styleReferencePath", "").apply();
        BotLog.i(this, "style.photo.clear", "风格底图已清除");
        refreshStylePreview("");
        toast("风格底图已清除");
    }

    private void refreshStylePreview(String path) {
        if (styleImage == null || styleInfo == null) {
            return;
        }
        File file = path == null || path.trim().isEmpty() ? null : new File(path);
        if (file != null && file.exists() && file.length() > 0) {
            styleImage.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            styleInfo.setText("已上传风格底图：" + file.getName() + " / " + file.length() + " bytes");
        } else {
            styleImage.setImageDrawable(null);
            styleInfo.setText("未上传风格底图。比基尼/清凉请求会先用内置参考图；上传后优先用这张。");
        }
    }

    private void chooseExPhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_EX_PHOTO);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQ_EX_PHOTO);
        }
    }

    private void saveExPhoto(Uri uri) {
        try {
            File dir = new File(getFilesDir(), "ex");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建蒸馏目录");
            }
            File target = new File(dir, "ex_reference.png");
            copyUriToFile(uri, target);
            BotConfig.prefs(this).edit().putString("exPhotoPath", target.getAbsolutePath()).apply();
            BotLog.i(this, "ex.photo.save", "蒸馏照片已保存 path=" + target.getAbsolutePath());
            refreshExPreview(target.getAbsolutePath());
            toast("蒸馏照片已保存");
        } catch (Exception e) {
            BotLog.e(this, "ex.photo.save.fail", e.getMessage());
            toast("蒸馏照片保存失败，看日志");
        }
    }

    private void clearExPhoto() {
        String path = BotConfig.load(this).exPhotoPath;
        if (path != null && !path.trim().isEmpty()) {
            try {
                new File(path).delete();
            } catch (Exception ignored) {
            }
        }
        BotConfig.prefs(this).edit().putString("exPhotoPath", "").apply();
        BotLog.i(this, "ex.photo.clear", "蒸馏照片已清除");
        refreshExPreview("");
        toast("蒸馏照片已清除");
    }

    private void refreshExPreview(String path) {
        if (exImage == null || exInfo == null) {
            return;
        }
        File file = path == null || path.trim().isEmpty() ? null : new File(path);
        if (file != null && file.exists() && file.length() > 0) {
            exImage.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            exInfo.setText("已上传蒸馏照片：" + file.getName() + " / " + file.length() + " bytes");
        } else {
            exImage.setImageDrawable(null);
            exInfo.setText("未上传蒸馏照片。打开蒸馏模式后，自拍会优先使用这里的人物参考。");
        }
    }

    private void chooseExSourceImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_EX_SOURCE_IMAGE);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQ_EX_SOURCE_IMAGE);
        }
    }

    private void chooseExSourceFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_EX_SOURCE_FILE);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("*/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQ_EX_SOURCE_FILE);
        }
    }

    private void saveExSource(Uri uri, String prefix) {
        try {
            File dir = ExPartnerMemoryBuilder.sourceDir(this);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建蒸馏材料目录");
            }
            File target = new File(dir, prefix + "_" + System.currentTimeMillis() + guessExtension(uri));
            copyUriToFile(uri, target);
            BotLog.i(this, "ex.source.save", "蒸馏材料已保存 path=" + target.getAbsolutePath());
            refreshExSourceInfo();
            toast("蒸馏材料已保存");
        } catch (Exception e) {
            BotLog.e(this, "ex.source.save.fail", e.getMessage());
            toast("蒸馏材料保存失败，看日志");
        }
    }

    private void refreshExSourceInfo() {
        if (exSourceInfo == null) {
            return;
        }
        int count = ExPartnerMemoryBuilder.sourceCount(this);
        exSourceInfo.setText("已导入材料：" + count + " 份");
    }

    private void distillExPartner() {
        saveConfig();
        toast("开始蒸馏资料");
        uiWorker.execute(() -> {
            try {
                BotConfig config = BotConfig.load(this);
                String profile = ExPartnerMemoryBuilder.distill(this, config);
                BotConfig.prefs(this).edit().putString("exProfilePrompt", profile).apply();
                BotLog.i(this, "ex.distill.done", "蒸馏画像已生成 bytes=" + profile.getBytes().length);
                runOnUiThread(() -> {
                    if (exProfilePrompt != null) {
                        exProfilePrompt.setText(profile);
                    }
                    refreshExSourceInfo();
                    refreshLogs();
                    toast("蒸馏画像已生成");
                });
            } catch (Exception e) {
                BotLog.e(this, "ex.distill.fail", e.getMessage());
                runOnUiThread(() -> {
                    refreshLogs();
                    toast("蒸馏失败，看日志");
                });
            }
        });
    }

    private void copyUriToFile(Uri uri, File target) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IllegalStateException("无法读取文件");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private String guessExtension(Uri uri) {
        String type = getContentResolver().getType(uri);
        if ("image/jpeg".equals(type)) {
            return ".jpg";
        }
        if ("image/png".equals(type)) {
            return ".png";
        }
        if ("image/webp".equals(type)) {
            return ".webp";
        }
        if ("text/plain".equals(type)) {
            return ".txt";
        }
        String path = uri == null ? "" : uri.getPath();
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < path.length() && path.length() - dot <= 8) {
            return path.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        }
        return ".dat";
    }

    private void testHs() {
        saveConfig();
        BotConfig config = BotConfig.load(this);
        if (!ensureNoRootAccessibilityReady(config)) {
            refreshStatus();
            return;
        }
        toast("正在测试 hs");
        uiWorker.execute(() -> {
            boolean ok = new HsDaemonManager().ensureRunning(this, config);
            runOnUiThread(() -> {
                toast(ok ? "hs daemon 正常" : "hs daemon 未就绪，看日志");
                refreshLogs();
                refreshStatus();
            });
        });
    }

    private void refreshLogs() {
        if (logView != null) {
            logView.setText(BotLog.readTailNewestFirst(this, 16000));
        }
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        BotConfig config = BotConfig.load(this);
        String base = "模式=" + config.activeMode
                + "\nAPK记录=" + HsDaemonManager.runtimeStatus(this)
                + "\nhs端口=检测中"
                + "\nShizuku=检测中"
                + "\n授权=检测中"
                + "\n辅助功能=" + (accessibilityServiceEnabled() ? "已开" : "未开")
                + "\n悬浮窗=" + (overlayEnabled() ? "已开" : "未开")
                + "\n通知监听=" + (notificationListenerEnabled() ? "已开" : "未开")
                + "\n上游=" + config.chatEndpoint
                + "\n支付回调=" + config.paymentCallbackUrl;
        statusView.setText(base);
        uiWorker.execute(() -> {
            boolean shizuku = ShizukuBridge.available();
            boolean shizukuPerm = ShizukuBridge.hasPermission();
            String hs = new HsClient(config.hsPort).healthLabel();
            String hsRuntime = HsDaemonManager.runtimeStatus(this);
            runOnUiThread(() -> {
                if (statusView == null) {
                    return;
                }
                statusView.setText("模式=" + config.activeMode
                        + "\nAPK记录=" + hsRuntime
                        + "\nhs端口=" + hs
                        + "\nShizuku=" + (shizuku ? "在线" : "离线")
                        + "\n授权=" + (shizukuPerm ? "已授权" : "未授权")
                        + "\n辅助功能=" + (accessibilityServiceEnabled() ? "已开" : "未开")
                        + "\n悬浮窗=" + (overlayEnabled() ? "已开" : "未开")
                        + "\n通知监听=" + (notificationListenerEnabled() ? "已开" : "未开")
                        + "\n上游=" + config.chatEndpoint
                        + "\n支付回调=" + config.paymentCallbackUrl);
            });
        });
    }

    private boolean notificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private boolean accessibilityServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null || flat.trim().isEmpty()) {
            return false;
        }
        String full = getPackageName() + "/" + BotAccessibilityService.class.getName();
        String shortName = getPackageName() + "/.BotAccessibilityService";
        return flat.contains(full) || flat.contains(shortName);
    }

    private boolean overlayEnabled() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void startBotFromUi() {
        saveConfig();
        BotConfig config = BotConfig.load(this);
        if (!ensureNoRootAccessibilityReady(config)) {
            refreshStatus();
            return;
        }
        Intent intent = new Intent(this, BotService.class);
        intent.setAction(BotService.ACTION_START);
        startForegroundService(intent);
    }

    private boolean ensureNoRootAccessibilityReady(BotConfig config) {
        if (!"shizuku".equals(config.activeMode)) {
            return true;
        }
        if (accessibilityServiceEnabled()) {
            return true;
        }
        BotLog.w(this, "permission.accessibility.required", "shizuku 无 root 模式需要先开启辅助功能服务");
        toast("先开启 HS 微信机器人辅助服务");
        openAccessibilitySettings();
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            BotLog.e(this, "permission.accessibility.open.fail", e.getMessage());
            toast("无法打开辅助功能设置");
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            toast("当前系统无需单独开启悬浮窗权限");
        }
    }

    private void openBatteryWhitelistSettings() {
        if (Build.VERSION.SDK_INT < 23) {
            toast("当前系统无需电池白名单");
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池白名单");
                return;
            }
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                BotLog.e(this, "permission.battery.open.fail", e.getMessage());
                toast("无法打开电池白名单设置");
            }
        }
    }

    private void openWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            toast("当前系统无需亮度写入权限");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            BotLog.e(this, "permission.write_settings.open.fail", e.getMessage());
            toast("无法打开亮度权限设置");
        }
    }

    private void restoreScreenBrightnessFromUi() {
        ScreenControl.disableKeepAwake(this, "ui");
        boolean ok = ScreenControl.trySetSystemBrightness(this, 255, "ui");
        toast(ok ? "已尝试调到最亮" : "已关闭低亮窗口，系统亮度权限未开");
        refreshLogs();
        refreshStatus();
    }

    private void requestPostNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6602);
        }
    }

    private EditText edit(LinearLayout root, String label, String hint, boolean multiLine) {
        TextView tv = text(label, 14, MUTED, Typeface.BOLD);
        tv.setPadding(0, dp(9), 0, dp(5));
        root.addView(tv);
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(15);
        input.setSingleLine(!multiLine);
        input.setMinLines(multiLine ? 4 : 1);
        input.setGravity(multiLine ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
        input.setInputType(multiLine ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(round(Color.rgb(248, 250, 252), dp(12), 1, Color.rgb(226, 232, 240)));
        root.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private Spinner spinner(LinearLayout root, String label, String[] values) {
        TextView tv = text(label, 14, MUTED, Typeface.BOLD);
        tv.setPadding(0, dp(9), 0, dp(5));
        root.addView(tv);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshStatus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(spinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    private int ttsVoiceIndex(String voice) {
        String normalized = BotConfig.normalizeTtsVoice(voice);
        for (int i = 0; i < BotConfig.TTS_VOICE_IDS.length; i++) {
            if (BotConfig.TTS_VOICE_IDS[i].equals(normalized)) {
                return i;
            }
        }
        return 0;
    }

    private String selectedTtsVoiceId() {
        int index = ttsVoice == null ? 0 : ttsVoice.getSelectedItemPosition();
        if (index < 0 || index >= BotConfig.TTS_VOICE_IDS.length) {
            return BotConfig.DEFAULT_TTS_VOICE;
        }
        return BotConfig.TTS_VOICE_IDS[index];
    }

    private Switch switchRow(LinearLayout root, String label) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(INK);
        sw.setTextSize(15);
        sw.setPadding(0, dp(8), 0, dp(8));
        root.addView(sw, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return sw;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setOnClickListener(listener);
        button.setBackground(round(NAV, dp(18), 0, 0));
        return button;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, style);
        return tv;
    }

    private GradientDrawable round(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        if (strokeWidth > 0) {
            bg.setStroke(strokeWidth, strokeColor);
        }
        return bg;
    }

    private int intValue(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private float floatValue(EditText input, float fallback) {
        try {
            return Float.parseFloat(input.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
