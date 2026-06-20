package com.vxbot.wechatbot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BotService extends Service {
    public static final String ACTION_START = "com.vxbot.wechatbot.START";
    public static final String ACTION_STOP = "com.vxbot.wechatbot.STOP";
    public static final String ACTION_HANDLE_NOTIFICATION = "com.vxbot.wechatbot.HANDLE_NOTIFICATION";
    public static final String ACTION_TEST_OCR = "com.vxbot.wechatbot.TEST_OCR";
    public static final String ACTION_DUMP_OCR = "com.vxbot.wechatbot.DUMP_OCR";
    public static final String ACTION_BROADCAST_TEXT = "com.vxbot.wechatbot.BROADCAST_TEXT";
    public static final String ACTION_DEBUG_OPEN_IMAGE_SHARE = "com.vxbot.wechatbot.DEBUG_OPEN_IMAGE_SHARE";
    public static final String ACTION_MORNING_GREETING = "com.vxbot.wechatbot.MORNING_GREETING";
    private static final String CHANNEL_ID = "bot_status";
    private static final long STARTUP_NOTIFICATION_GRACE_MS = 5000L;
    private static final Random RANDOM = new Random();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService paymentWorker = Executors.newSingleThreadExecutor();
    private final SessionStore sessionStore = new SessionStore();
    private final PersonaStore personaStore = new PersonaStore();
    private final Set<String> seenNotifications = new HashSet<>();
    private final HsDaemonManager daemonManager = new HsDaemonManager();
    private long serviceStartedAtMs;
    private boolean startupForegroundCleaned;
    private volatile boolean botStartedOnce;
    private volatile boolean operationActive;
    private LogOverlayWindow logOverlay;
    private final SharedPreferences.OnSharedPreferenceChangeListener configListener = (prefs, key) -> {
        if (key == null || "enableLogOverlay".equals(key) || "keepLogOverlayDuringOperation".equals(key)) {
            syncLogOverlay(BotConfig.load(this));
        }
        if (isInputModeSettingKey(key)) {
            BotConfig config = BotConfig.load(this);
            if (config.syncInputModeFromVoiceSwitch) {
                WechatDriver.syncAllowedSessionInputModes(this, config);
                BotLog.i(this, "input.mode.cache.sync", "语音/文字发送或白名单设置变化，已按开关同步白名单输入态 key=" + key);
            } else {
                BotLog.i(this, "input.mode.cache.sync.skip", "输入态批量同步关闭，保留每群自动识别缓存 key=" + key);
            }
        }
        if (key == null || "activeMode".equals(key) || "enableNoRootKeepAwake".equals(key)) {
            ScreenControl.syncForConfig(this, BotConfig.load(this), "config-change");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStartedAtMs = System.currentTimeMillis();
        startForeground(1, buildStatusNotification("机器人运行中"));
        KeepAliveScheduler.schedule(this);
        MorningGreetingScheduler.schedule(this);
        GarbageCleaner.runIfDue(this, "service-create");
        logOverlay = new LogOverlayWindow(this);
        BotConfig.prefs(this).registerOnSharedPreferenceChangeListener(configListener);
        BotConfig initialConfig = BotConfig.load(this);
        syncLogOverlay(initialConfig);
        ScreenControl.syncForConfig(this, initialConfig, "service-create");
        if (initialConfig.syncInputModeFromVoiceSwitch) {
            WechatDriver.syncAllowedSessionInputModes(this, initialConfig);
        } else {
            BotLog.i(this, "input.mode.cache.sync.skip", "服务启动时输入态批量同步关闭，保留每群自动识别缓存");
        }
        BotLog.i(this, "bot.service.create", "BotService onCreate pid=" + Process.myPid());
        BotLog.i(this, "payment.guard.start", "支付监听独立线程已启动");
        paymentWorker.execute(() -> new PaymentNoticeFlow().flushPending(this, BotConfig.load(this)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final boolean stickyRestart = intent == null;
        String action = stickyRestart ? ACTION_START : intent.getAction();
        String reason = stickyRestart ? "sticky-restart" : intent.getStringExtra("reason");
        if (ACTION_STOP.equals(action)) {
            BotConfig config = BotConfig.load(this);
            KeepAliveScheduler.cancel(this);
            operationActive = false;
            botStartedOnce = false;
            if (logOverlay != null) {
                logOverlay.hide();
            }
            ScreenControl.disableKeepAwake(this, "bot-stop");
            daemonManager.stop(this, config);
            BotLog.i(this, "bot.stop", "服务停止");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_HANDLE_NOTIFICATION.equals(action)) {
            syncLogOverlay(BotConfig.load(this));
            WxMessage message = fromIntent(intent);
            worker.execute(() -> handleMessage(message));
            return START_REDELIVER_INTENT;
        }
        if (ACTION_TEST_OCR.equals(action)) {
            String target = intent == null ? "发送" : intent.getStringExtra("target");
            worker.execute(() -> runOcrTest(target == null || target.trim().isEmpty() ? "发送" : target.trim()));
            return START_STICKY;
        }
        if (ACTION_DUMP_OCR.equals(action)) {
            String label = intent == null ? "" : intent.getStringExtra("label");
            worker.execute(() -> runOcrDump(label == null || label.trim().isEmpty() ? "manual" : label.trim()));
            return START_STICKY;
        }
        if (ACTION_BROADCAST_TEXT.equals(action)) {
            String text = intent == null ? "" : intent.getStringExtra("text");
            worker.execute(() -> runBroadcastText(text));
            return START_STICKY;
        }
        if (ACTION_DEBUG_OPEN_IMAGE_SHARE.equals(action)) {
            String path = intent == null ? "" : intent.getStringExtra("path");
            worker.execute(() -> runDebugOpenImageShare(path));
            return START_STICKY;
        }
        if (ACTION_MORNING_GREETING.equals(action)) {
            worker.execute(this::runMorningGreeting);
            return START_STICKY;
        }
        worker.execute(() -> {
            BotConfig config = BotConfig.load(this);
            boolean watchdog = "watchdog".equals(reason);
            boolean restartRecover = stickyRestart || "sticky-restart".equals(reason);
            KeepAliveScheduler.schedule(this);
            MorningGreetingScheduler.schedule(this);
            GarbageCleaner.runIfDue(this, restartRecover ? "sticky-restart" : "service-start");
            if (watchdog || restartRecover) {
                boolean ok = new HsClient(config.hsPort).ping();
                if (!ok) {
                    BotLog.w(this, restartRecover ? "service.restart.recover" : "keepalive.service.recover",
                            (restartRecover ? "系统重启服务后" : "守护 tick") + "发现 hs 未响应，尝试恢复");
                    ok = daemonManager.ensureRunning(this, config);
                }
                if (ok) {
                    botStartedOnce = true;
                }
                BotLog.write(this, ok ? "INFO" : "ERROR",
                        restartRecover ? "service.restart.check" : "keepalive.service.check",
                        ok
                                ? (restartRecover ? "系统重启服务，已恢复守护检查，不执行启动清场" : "服务已运行，守护检查通过")
                                : (restartRecover ? "系统重启服务后恢复失败" : "服务守护检查失败"));
                return;
            }
            syncLogOverlay(config);
            boolean ok = daemonManager.ensureRunning(this, config);
            if (ok) {
                botStartedOnce = true;
            }
            if (ok && !startupForegroundCleaned) {
                startupForegroundCleaned = true;
                new WechatDriver(config.hsPort).leaveWechatIfForeground(this, "bot-start");
            }
            ScreenControl.syncForConfig(this, config, "bot-start");
            BotLog.write(this, ok ? "INFO" : "ERROR", "bot.start", ok ? "机器人已启动" : "机器人启动失败");
        });
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (logOverlay != null) {
            logOverlay.hide();
        }
        BotConfig.prefs(this).unregisterOnSharedPreferenceChangeListener(configListener);
        worker.shutdownNow();
        paymentWorker.shutdownNow();
        BotLog.w(this, "bot.service.destroy", "BotService onDestroy pid=" + Process.myPid());
        super.onDestroy();
    }

    private void syncLogOverlay(BotConfig config) {
        if (logOverlay == null) {
            return;
        }
        if (config.enableLogOverlay) {
            if (operationActive && !config.keepLogOverlayDuringOperation) {
                return;
            }
            logOverlay.show();
        } else {
            logOverlay.hide();
        }
    }

    private static boolean isInputModeSettingKey(String key) {
        return "normalReplyAsVoice".equals(key)
                || "imageWarmupAsVoice".equals(key)
                || "imageAfterAsVoice".equals(key)
                || "syncInputModeFromVoiceSwitch".equals(key)
                || "allowedSessions".equals(key);
    }

    private void handleMessage(WxMessage message) {
        BotConfig config = BotConfig.load(this);
        if (message == null || (message.text.isEmpty() && message.rawContent.isEmpty())) {
            return;
        }
        synchronized (seenNotifications) {
            if (!seenNotifications.add(message.key())) {
                return;
            }
            if (seenNotifications.size() > 200) {
                seenNotifications.clear();
            }
        }
        BotLog.i(this, "notice.raw", message.display());
        if (isStaleStartupNotification(message)) {
            BotLog.i(this, "notice.skip.stale", "忽略服务启动前残留旧通知 " + message.display()
                    + " postTime=" + message.postTime
                    + " serviceStartedAt=" + serviceStartedAtMs);
            return;
        }
        if (PaymentNoticeFlow.looksLikePaymentNotice(message)) {
            paymentWorker.execute(() -> new PaymentNoticeFlow().handlePayment(this, BotConfig.load(this), message));
            return;
        }
        if (message.text.isEmpty()) {
            return;
        }
        if (!config.isAllowedSession(message.sessionName)) {
            BotLog.w(this, "notice.skip.whitelist", "群不在白名单 " + message.sessionName);
            return;
        }
        if (!MessageRouter.isPersonaCommand(message.text)) {
            personaStore.remember(this, message);
        }
        if (!sessionStore.shouldHandle(this, message, config)) {
            BotLog.i(this, "notice.skip.context", "未命中@或当前发起人锁 " + message.display());
            return;
        }
        sessionStore.remember(message, "user");
        boolean enteringGlobalCodex = MessageRouter.isCodexModeEnterCommand(message.text, config);
        if (enteringGlobalCodex) {
            sessionStore.enableGlobalCodexMode(this);
            BotLog.i(this, "codex.global.enable", "已进入全局 Codex 模式 " + message.display());
        }
        MessageRouter.Route route = sessionStore.isGlobalCodexMode(this)
                ? new MessageRouter.Route(MessageRouter.Kind.CODEX, "全局 Codex 模式：所有白名单群消息直接交给 Codex 处理，不再走其它工具分流。")
                : MessageRouter.classify(message.text, config);
        route = applySessionMode(message, route);
        if (route.kind == MessageRouter.Kind.SHUTUP) {
            sessionStore.muteFor(message.sessionName, 30 * 60 * 1000L);
        }
        workerReply(config, message, route);
    }

    private boolean isStaleStartupNotification(WxMessage message) {
        if (message == null || message.postTime <= 0 || serviceStartedAtMs <= 0) {
            return false;
        }
        return message.postTime + STARTUP_NOTIFICATION_GRACE_MS < serviceStartedAtMs;
    }

    private MessageRouter.Route applySessionMode(WxMessage message, MessageRouter.Route route) {
        if (route.kind == MessageRouter.Kind.TROLL) {
            if (route.exitCommand) {
                sessionStore.clearRoastTarget(message.sessionName);
                return route;
            }
            if (!route.targetName.isEmpty()) {
                sessionStore.setRoastTarget(message.sessionName, route.targetName, 10 * 60 * 1000L);
                BotLog.i(this, "mode.roast.target", "已锁定对喷目标 group=" + message.sessionName + " target=" + route.targetName);
            }
            return route;
        }
        if (route.kind == MessageRouter.Kind.LOVER) {
            if (route.exitCommand) {
                sessionStore.clearLoverTarget(message.sessionName);
                return route;
            }
            if (!route.targetName.isEmpty()) {
                sessionStore.setLoverTarget(message.sessionName, route.targetName, 60 * 60 * 1000L);
                BotLog.i(this, "mode.lover.target", "已锁定恋人目标 group=" + message.sessionName + " target=" + route.targetName);
            }
            return route;
        }
        String roastTarget = sessionStore.roastTargetName(message.sessionName);
        if (!roastTarget.isEmpty() && NameNormalizer.sameName(roastTarget, message.senderName)) {
            return new MessageRouter.Route(MessageRouter.Kind.TROLL, "", roastTarget, false, false);
        }
        String loverTarget = sessionStore.loverTargetName(message.sessionName);
        if (!loverTarget.isEmpty() && NameNormalizer.sameName(loverTarget, message.senderName)) {
            return new MessageRouter.Route(MessageRouter.Kind.LOVER, MessageRouter.loverInstruction(loverTarget), loverTarget, false, false);
        }
        return route;
    }

    private void workerReply(BotConfig config, WxMessage message, MessageRouter.Route route) {
        if (!daemonManager.ensureRunning(this, config)) {
            BotLog.e(this, "reply.abort", "hs daemon 未就绪");
            return;
        }
        pauseLogOverlayForOperation(config);
        ExecutorService parallel = Executors.newFixedThreadPool(2);
        try {
            List<String> history = sessionStore.contextOf(message.sessionName);
            WechatDriver driver = new WechatDriver(config.hsPort);
            Future<Boolean> openFuture = parallel.submit(() -> {
                BotLog.i(this, "notice.open.async.start", "先打开目标会话 " + message.display());
                boolean opened = driver.openTargetChatForReply(this, config, message);
                BotLog.write(this, opened ? "INFO" : "ERROR", "notice.open.async.done",
                        opened ? "目标会话已就绪 " + message.sessionName : "目标会话打开失败 " + message.sessionName);
                return opened;
            });
            Future<String> replyFuture = parallel.submit(() -> {
                if (route.kind == MessageRouter.Kind.IMAGE) {
                    return "__IMAGE_FLOW__";
                }
                if (route.kind == MessageRouter.Kind.ANALYSIS) {
                    return "__VISION_FLOW__";
                }
                if (route.kind == MessageRouter.Kind.WOOL) {
                    return "__WOOL_FLOW__";
                }
                if (route.kind == MessageRouter.Kind.VIDEO) {
                    return "__VIDEO_FLOW__";
                }
                if (route.kind == MessageRouter.Kind.STICKER) {
                    return "__STICKER_FLOW__";
                }
                if (route.kind == MessageRouter.Kind.TTS) {
                    return "__TTS_FLOW__";
                }
                if (route.kind == MessageRouter.Kind.MANUAL) {
                    return buildManualReply(config);
                }
                if (route.kind == MessageRouter.Kind.PERSONA) {
                    String local = personaStore.buildReport(this, message.sessionName, message.text, System.currentTimeMillis());
                    String personaContext = personaStore.buildModelContext(this, message.sessionName, message.text, System.currentTimeMillis());
                    if (personaContext.isEmpty()) {
                        return local;
                    }
                    try {
                        return new ChatClient().requestPersonaReport(this, config, message, personaContext);
                    } catch (Exception e) {
                        BotLog.w(this, "persona.analysis.fallback", "人物画像上游分析失败，回落本地统计: " + e.getMessage());
                        return local;
                    }
                }
                if (route.kind == MessageRouter.Kind.SCREEN_DIM) {
                    ScreenControl.enableDimKeepAwake(this, "command");
                    ScreenControl.trySetSystemBrightness(this, 1, "command-dim");
                    return "低亮防熄屏开了。无 root 机型会用透明小窗口保持屏幕不灭，需要恢复就发“屏幕最亮”。";
                }
                if (route.kind == MessageRouter.Kind.SCREEN_BRIGHT) {
                    ScreenControl.disableKeepAwake(this, "command");
                    ScreenControl.trySetSystemBrightness(this, 255, "command-bright");
                    return "屏幕亮度恢复指令已执行，低亮防熄屏也关了。";
                }
                if (route.exitCommand && route.kind == MessageRouter.Kind.TROLL) {
                    return "行，先收炮，喷子模式退了。";
                }
                if (route.exitCommand && route.kind == MessageRouter.Kind.LOVER) {
                    return "行，先不撒糖了。";
                }
                if (route.kind == MessageRouter.Kind.REPORT) {
                    return randomReportReply(config);
                }
                if (route.kind == MessageRouter.Kind.LICENSE) {
                    BotLog.i(this, "license.request", "请求 18088 注册机面板 " + message.display());
                    return new LicensePanelClient().request(this, config, message.text);
                }
                BotLog.i(this, "relay.start", "请求上游 " + config.chatEndpoint + " / mode=" + route.kind + " / " + message.display());
                return new ChatClient().requestReply(this, config, message, history, route);
            });
            String reply = replyFuture.get();
            Future<VoiceReply.PreparedVoice> preparedVoiceFuture = null;
            if ("__TTS_FLOW__".equals(reply)) {
                preparedVoiceFuture = prepareVoiceAsync(parallel, config, route.instruction, "tts-command");
            } else if (!isSpecialFlow(reply)) {
                String normalizedReply = prefixTarget(route, reply);
                if (shouldSendReplyAsVoice(config, route)) {
                    preparedVoiceFuture = prepareVoiceAsync(parallel, config, normalizedReply,
                            "reply-voice-" + route.kind.name().toLowerCase());
                }
                reply = normalizedReply;
            }
            boolean opened = openFuture.get();
            if (!opened) {
                cleanupPreparedFutureIfDone(preparedVoiceFuture, "open-failed");
                BotLog.e(this, "reply.abort", "目标会话未打开，取消发送 " + message.display());
                return;
            }
            if ("__IMAGE_FLOW__".equals(reply)) {
                boolean ok = new ImageFlow().handle(this, config, message, sessionStore, driver);
                if (!ok) {
                    BotLog.e(this, "image.flow.fail", "图片流程失败 " + message.display());
                }
                return;
            }
            if ("__VISION_FLOW__".equals(reply)) {
                reply = new ImageFlow().analyzeCurrentScreen(this, config, message, history);
            }
            if ("__WOOL_FLOW__".equals(reply)) {
                boolean ok = new WoolHotFlow().handle(this, config, message, driver);
                if (!ok) {
                    BotLog.e(this, "wool.flow.fail", "羊毛榜流程失败 " + message.display());
                }
                return;
            }
            if ("__VIDEO_FLOW__".equals(reply)) {
                boolean ok = new VideoParseFlow().handle(this, config, message, driver);
                if (!ok) {
                    BotLog.e(this, "video.flow.fail", "视频解析流程失败 " + message.display());
                }
                return;
            }
            if ("__STICKER_FLOW__".equals(reply)) {
                boolean ok = new StickerFlow().handle(this, config, message, driver);
                if (!ok) {
                    BotLog.e(this, "sticker.flow.fail", "表情包流程失败 " + message.display());
                }
                return;
            }
            if ("__TTS_FLOW__".equals(reply)) {
                boolean ok = startTtsVoiceInCurrentChat(config, driver, message, route.instruction,
                        "tts-command", preparedVoiceFuture);
                if (ok) {
                    sessionStore.rememberBot(message.sessionName, config.primaryBotName(), route.instruction);
                }
                return;
            }
            BotLog.i(this, "relay.done", "上游返回 reply=" + reply);
            boolean keepForeground = route.kind == MessageRouter.Kind.CODEX && config.stayInCodexSession;
            if (shouldSendReplyAsVoice(config, route)) {
                boolean sent = startTtsVoiceInCurrentChat(config, driver, message, reply,
                        "reply-voice-" + route.kind.name().toLowerCase(), preparedVoiceFuture);
                if (sent) {
                    sessionStore.rememberBot(message.sessionName, config.primaryBotName(), reply);
                }
                return;
            }
            boolean sent = driver.sendTextInCurrentChat(this, config, message.sessionName, reply, keepForeground);
            if (sent) {
                sessionStore.rememberBot(message.sessionName, config.primaryBotName(), reply);
            }
        } catch (Exception e) {
            BotLog.e(this, "reply.error", "回复失败: " + e.getMessage());
        } finally {
            parallel.shutdownNow();
            resumeLogOverlayAfterOperation();
        }
    }

    private String prefixTarget(MessageRouter.Route route, String reply) {
        String text = reply == null ? "" : reply.trim();
        String target = route.targetName == null ? "" : route.targetName.trim();
        if (target.isEmpty()) {
            return text;
        }
        String prefix = "@" + target;
        if (text.startsWith(prefix)) {
            return text;
        }
        return prefix + " " + text;
    }

    private boolean shouldSendReplyAsVoice(BotConfig config, MessageRouter.Route route) {
        if (config == null || route == null || !config.normalReplyAsVoice) {
            return false;
        }
        return route.kind == MessageRouter.Kind.TEXT
                || route.kind == MessageRouter.Kind.TROLL
                || route.kind == MessageRouter.Kind.LOVER
                || route.kind == MessageRouter.Kind.FINANCE
                || route.kind == MessageRouter.Kind.SPORTS
                || route.kind == MessageRouter.Kind.UTILITY
                || route.kind == MessageRouter.Kind.NEWS
                || route.kind == MessageRouter.Kind.WEATHER
                || route.kind == MessageRouter.Kind.ANALYSIS
                || route.kind == MessageRouter.Kind.REPORT
                || route.kind == MessageRouter.Kind.SHUTUP;
    }

    private String buildManualReply(BotConfig config) {
        String name = config == null ? "机器人" : config.primaryBotName();
        return name + " 操作手册：\n"
                + "1. 聊天：@" + name + " 后面直接说内容。\n"
                + "2. 图片：自拍、比基尼、换个场景、分析图片、生成表情包。\n"
                + "3. 工具：天气、股票/基金/BTC/黄金克价、新闻热点、赛事比分/赛事分析、来点羊毛、短视频/图集链接解析。\n"
                + "4. 画像：人物画像、昨日总结、谁是话痨、昨天说了啥。\n"
                + "5. 模式：撩一下名字、表白名字、跟名字表白、对喷一下名字、退出恋人模式、退出对喷。\n"
                + "6. 语音：发语音 文字；机器人请报道可测在线。\n"
                + "7. 屏幕：屏幕最暗开启低亮防熄屏，屏幕最亮恢复。";
    }

    private boolean startTtsVoiceInCurrentChat(BotConfig config, WechatDriver driver, WxMessage message, String text, String reason) {
        return startTtsVoiceInCurrentChat(config, driver, message, text, reason, null);
    }

    private boolean startTtsVoiceInCurrentChat(BotConfig config, WechatDriver driver, WxMessage message,
                                               String text, String reason,
                                               Future<VoiceReply.PreparedVoice> preparedVoiceFuture) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            BotLog.e(this, "tts.abort", "TTS 内容为空 " + message.display());
            return false;
        }
        BotLog.i(this, "tts.dispatch", "开始发送 TTS 语音 group=" + message.sessionName
                + " sender=" + message.senderName
                + " text=" + payload);
        VoiceReply.PreparedVoice prepared = VoiceReply.awaitPrepared(this, preparedVoiceFuture, reason);
        return VoiceReply.sendPreparedInCurrentChat(this, config, driver, message.sessionName, prepared, payload, reason, true);
    }

    private Future<VoiceReply.PreparedVoice> prepareVoiceAsync(ExecutorService executor, BotConfig config,
                                                               String text, String reason) {
        String payload = text == null ? "" : text.trim();
        if (payload.isEmpty()) {
            return null;
        }
        BotLog.i(this, "tts.prepare.queue", "提前生成 TTS reason=" + reason + " length=" + payload.length());
        return executor.submit(() -> VoiceReply.prepare(this, config, payload, reason));
    }

    private void cleanupPreparedFutureIfDone(Future<VoiceReply.PreparedVoice> future, String reason) {
        if (future == null) {
            return;
        }
        if (!future.isDone()) {
            future.cancel(true);
            return;
        }
        VoiceReply.cleanupPrepared(this, VoiceReply.awaitPrepared(this, future, reason), reason);
    }

    private boolean isSpecialFlow(String reply) {
        return "__IMAGE_FLOW__".equals(reply)
                || "__VISION_FLOW__".equals(reply)
                || "__WOOL_FLOW__".equals(reply)
                || "__VIDEO_FLOW__".equals(reply)
                || "__STICKER_FLOW__".equals(reply)
                || "__TTS_FLOW__".equals(reply);
    }

    private String randomReportReply(BotConfig config) {
        String name = config == null ? "机器人" : config.primaryBotName();
        String[] replies = {
                "{name}到，正在待命。",
                "报道，信号在线。",
                "到位了，谁喊我。",
                "在线，别催，我听着呢。",
                "收到，机器人已冒泡。",
                "来了来了，报道完毕。",
                "在，刚从后台探头。",
                "报告，当前状态：能听见。",
                "到，群里风吹草动我盯着。",
                "我在，别把我当空气。"
        };
        return replies[RANDOM.nextInt(replies.length)].replace("{name}", name);
    }

    private void pauseLogOverlayForOperation(BotConfig config) {
        operationActive = true;
        if (config.enableLogOverlay && !config.keepLogOverlayDuringOperation && logOverlay != null) {
            logOverlay.hide();
            BotLog.i(this, "overlay.pause", "自动操作期间临时隐藏悬浮日志，避免污染 OCR");
            SystemClock.sleep(200);
        } else if (config.enableLogOverlay && config.keepLogOverlayDuringOperation) {
            BotLog.i(this, "overlay.keep", "配置为微信操作时仍显示悬浮日志");
        }
    }

    private void resumeLogOverlayAfterOperation() {
        operationActive = false;
        syncLogOverlay(BotConfig.load(this));
    }

    private void runOcrTest(String target) {
        BotConfig config = BotConfig.load(this);
        if (!daemonManager.ensureRunning(this, config)) {
            BotLog.e(this, "ocr.test.abort", "hs daemon 未就绪");
            return;
        }
        Rect rect = OcrHelper.findBottomRightText(this, new HsClient(config.hsPort), target);
        if (rect == null) {
            BotLog.w(this, "ocr.test.miss", "OCR 测试未命中 target=" + target);
            return;
        }
        BotLog.i(this, "ocr.test.hit", "OCR 测试命中 target=" + target
                + " x=" + rect.centerX()
                + " y=" + rect.centerY()
                + " rect=" + rect.flattenToString());
    }

    private void runOcrDump(String label) {
        BotConfig config = BotConfig.load(this);
        if (!daemonManager.ensureRunning(this, config)) {
            BotLog.e(this, "ocr.dump.abort", "hs daemon 未就绪 label=" + label);
            return;
        }
        OcrHelper.Screen screen = OcrHelper.inspect(this, new HsClient(config.hsPort));
        BotLog.i(this, "ocr.dump", "label=" + label + " " + OcrHelper.dumpScreen(screen, 80));
    }

    private void runBroadcastText(String rawText) {
        runBroadcastText(rawText, "manual");
    }

    private void runBroadcastText(String rawText, String source) {
        BotConfig config = BotConfig.load(this);
        String text = rawText == null ? "" : rawText.trim();
        List<String> sessions = config.allowedSessionList();
        if (text.isEmpty()) {
            BotLog.e(this, "broadcast.abort", "群发文本为空");
            return;
        }
        if (sessions.isEmpty()) {
            BotLog.e(this, "broadcast.abort", "白名单群为空");
            return;
        }
        if (!daemonManager.ensureRunning(this, config)) {
            BotLog.e(this, "broadcast.abort", "hs daemon 未就绪");
            return;
        }
        pauseLogOverlayForOperation(config);
        BotLog.i(this, "broadcast.start", "开始向白名单群依次发送消息 source=" + source + " count=" + sessions.size()
                + " text=" + text);
        int okCount = 0;
        try {
            WechatDriver driver = new WechatDriver(config.hsPort);
            for (int i = 0; i < sessions.size(); i++) {
                String sessionName = sessions.get(i);
                boolean ok = driver.sendBroadcastTextToSession(this, config, sessionName, text);
                if (ok) {
                    okCount++;
                    sessionStore.rememberBot(sessionName, config.primaryBotName(), text);
                }
                if (i + 1 < sessions.size()) {
                    SystemClock.sleep(config.broadcastGapMs);
                }
            }
            BotLog.write(this, okCount == sessions.size() ? "SUCCESS" : (okCount > 0 ? "WARN" : "ERROR"),
                    "broadcast.done", "白名单群发结束 source=" + source + " okCount=" + okCount + " count=" + sessions.size());
        } finally {
            resumeLogOverlayAfterOperation();
        }
    }

    private void runMorningGreeting() {
        BotConfig config = BotConfig.load(this);
        MorningGreetingScheduler.schedule(this);
        if (!config.enableMorningGreeting) {
            BotLog.i(this, "morning.skip.disabled", "早安问好开关已关闭");
            return;
        }
        String text = MORNING_GREETINGS[RANDOM.nextInt(MORNING_GREETINGS.length)];
        try {
            String briefing = RealtimeTools.morningBriefing();
            if (briefing != null && !briefing.trim().isEmpty()) {
                text = text + "\n\n" + briefing.trim();
            }
        } catch (Exception e) {
            BotLog.w(this, "morning.briefing.fail", "新闻早报生成失败，继续发送问好: " + e.getMessage());
        }
        BotLog.i(this, "morning.broadcast.start", "早安问好接入白名单群发 text=" + text);
        runBroadcastText(text, "morning");
    }

    private void runDebugOpenImageShare(String rawPath) {
        BotConfig config = BotConfig.load(this);
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.isEmpty()) {
            BotLog.e(this, "image.share.debug.abort", "调试图片路径为空");
            return;
        }
        if (!daemonManager.ensureRunning(this, config)) {
            BotLog.e(this, "image.share.debug.abort", "hs daemon 未就绪 path=" + path);
            return;
        }
        pauseLogOverlayForOperation(config);
        try {
            boolean ok = new ImageFlow().debugOpenImageShare(this, config, new File(path));
            BotLog.write(this, ok ? "SUCCESS" : "ERROR", "image.share.debug.done",
                    (ok ? "已打开微信图片分享选择页" : "打开微信图片分享选择页失败") + " path=" + path);
        } finally {
            resumeLogOverlayAfterOperation();
        }
    }

    private WxMessage fromIntent(Intent intent) {
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= 33) {
            pendingIntent = intent.getParcelableExtra("contentIntent", PendingIntent.class);
        } else {
            pendingIntent = intent.getParcelableExtra("contentIntent");
        }
        return new WxMessage(
                intent.getStringExtra("sessionName"),
                intent.getStringExtra("senderName"),
                intent.getStringExtra("text"),
                intent.getStringExtra("rawTitle"),
                intent.getStringExtra("rawContent"),
                intent.getStringExtra("notificationKey"),
                intent.getLongExtra("postTime", System.currentTimeMillis()),
                pendingIntent);
    }

    private android.app.Notification buildStatusNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(com.vxbot.wechatbot.R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HS 微信机器人")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private static final String[] MORNING_GREETINGS = {
            "早，今天也别空手而归，羊毛和好心情都安排上。",
            "早上好，醒醒，今天继续薅点正经毛。",
            "早，打工先放一边，先看看今天有没有漏网羊毛。",
            "早安各位，今天也要稳稳当当捡便宜。",
            "早，咖啡还没喝，羊毛雷达先开机了。",
            "早上好，祝大家今天下单不反撸。",
            "早，今天谁先发现好价谁就是群里大功臣。",
            "早安，今天继续低调发财，高调捡漏。",
            "早，别睡太死，神价不会等人。",
            "早上好，今天也要把钱包守住，把优惠拿下。",
            "早，醒了就看看群，别让好价跑了。",
            "早安，今天主打一个不花冤枉钱。",
            "早，今日份好运已开机。",
            "早上好，祝大家今天券到手软。",
            "早，今天继续做精明消费者。",
            "早安，群里先热个场，毛来了记得吱声。",
            "早，今天别上头，便宜也要看需求。",
            "早上好，祝今天红包不断，快递不断。",
            "早，薅毛归薅毛，别忘了吃早餐。",
            "早安，今天先从一条好消息开始。",
            "早，愿今天每个链接都靠谱一点。",
            "早上好，今天也要快准狠。",
            "早，手慢无先说，先把精神头拉满。",
            "早安，今天谁发神车我第一个鼓掌。",
            "早，羊毛可以小，快乐不能少。",
            "早上好，今日份摸鱼和捡漏同步开始。",
            "早，祝大家今天少踩坑，多捡漏。",
            "早安，今天也请优惠券主动点。",
            "早，醒醒，该给生活省点钱了。",
            "早上好，愿今天全是正经福利。"
    };
}
