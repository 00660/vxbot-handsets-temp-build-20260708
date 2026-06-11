package com.vxbot.wechatbot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WechatDriver {
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");
    private static final Pattern WECHAT_LIST_TITLE = Pattern.compile("^微信(?:[（(]\\d+[）)])?$");
    private static final Pattern DUMPSYS_RESUMED_ACTIVITY = Pattern.compile(
            "(?:mResumedActivity|topResumedActivity|mFocusedActivity)[^\\n]*?\\s([A-Za-z0-9_.$]+/[A-Za-z0-9_.$/]+)");
    private static final String WECHAT_COMPONENT = "com.tencent.mm/.ui.LauncherUI";
    private static final String INPUT_MODE_PREFS = "wechat_input_modes";
    private static final String MODE_TEXT = "text";
    private static final String MODE_VOICE = "voice";
    private static final long STEP_DELAY_MS = 800L;
    private static final long BROADCAST_STEP_DELAY_MS = 650L;
    private static final long SHADE_SETTLE_MS = 1600L;
    private static final long SHADE_OCR_STABLE_MS = 800L;

    private final HsClient hs;

    public WechatDriver(int port) {
        hs = new HsClient(port);
    }

    private static long stepDelay(BotConfig config) {
        return config == null ? STEP_DELAY_MS : config.wechatStepDelayMs;
    }

    public boolean sendText(Context context, BotConfig config, WxMessage message, String reply) {
        try {
            if (!openTargetChatForReply(context, config, message)) {
                BotLog.e(context, "notice.chat.open.fail", "未能确认进入目标会话，取消发送 " + message.display());
                return false;
            }
            return sendTextInCurrentChat(context, config, message.sessionName, reply, false);
        } catch (Exception e) {
            BotLog.e(context, "send.error", "发送失败: " + e.getMessage());
            return false;
        }
    }

    public boolean openTargetChatForReply(Context context, BotConfig config, WxMessage message) {
        try {
            return openTargetChat(context, config, message);
        } catch (Exception e) {
            BotLog.e(context, "notice.chat.open.error", "打开目标会话失败: " + e.getMessage());
            return false;
        }
    }

    public boolean sendTextInCurrentChat(Context context, BotConfig config, String reply, boolean keepForeground) {
        return sendTextInCurrentChat(context, config, "", reply, keepForeground);
    }

    public boolean sendTextInCurrentChat(Context context, BotConfig config, String sessionName,
                                         String reply, boolean keepForeground) {
        try {
            try {
                hs.command("wait_for_idle idle_ms=300 timeout_ms=3000");
            } catch (Exception ignored) {
            }
            if (!ensureTextInputMode(context, config, sessionName, "reply-text")) {
                BotLog.e(context, "input.mode.text.abort", "发送文字前未确认文字输入模式，取消输入动作");
                return false;
            }
            if (!focusAndSetText(context, config, reply)) {
                BotLog.e(context, "input.failed", "文字未确认进入输入框，取消发送");
                return false;
            }
            SystemClock.sleep(config.sendButtonDelayMs);
            boolean sent = tapSendButton(context);
            if (!sent) {
                BotLog.e(context, "send.button", "未找到发送按钮，取消返回动作，避免误点");
                return false;
            }
            BotLog.i(context, "send.text", "回复已发送 text=" + reply);
            SystemClock.sleep(600);
            if (!keepForeground) {
                backUntilLeaveWechat(context, "reply-sent");
            } else {
                BotLog.i(context, "back.skip.codex", "Codex 会话保持前台，跳过返回后台");
            }
            return true;
        } catch (Exception e) {
            BotLog.e(context, "send.error", "发送失败: " + e.getMessage());
            return false;
        }
    }

    public boolean sendBroadcastTextToSession(Context context, BotConfig config, String sessionName, String text) {
        long start = SystemClock.uptimeMillis();
        boolean sent = false;
        try {
            if (!openSessionBySearchForBroadcast(context, config, sessionName)) {
                BotLog.e(context, "broadcast.search.open.failed", "群发搜索无法进入目标会话 sessionName=" + sessionName);
                return false;
            }
            sent = sendBroadcastTextInCurrentChat(context, config, sessionName, text);
            return sent;
        } catch (Exception e) {
            BotLog.e(context, "broadcast.text.error", "群发发送异常 sessionName=" + sessionName + " error=" + e.getMessage());
            return false;
        } finally {
            if (!sent) {
                try {
                    backUntilLeaveWechat(context, "broadcast-failed");
                } catch (Exception e) {
                    BotLog.w(context, "broadcast.back.fail", e.getMessage());
                }
            }
            BotLog.write(context, sent ? "SUCCESS" : "ERROR", "broadcast.text.sent",
                    (sent ? "主动文字已发送到会话" : "主动文字发送失败")
                            + " sessionName=" + sessionName
                            + " costMs=" + (SystemClock.uptimeMillis() - start));
        }
    }

    public void leaveWechatIfForeground(Context context, String reason) {
        try {
            if (!isWechatForeground()) {
                return;
            }
            BotLog.i(context, "startup.wechat.foreground", "机器人启动时检测到微信仍在前台，准备退后台 reason=" + reason
                    + " foregroundPackage=" + foregroundPackageName());
            backUntilLeaveWechat(context, reason);
        } catch (Exception e) {
            BotLog.w(context, "startup.wechat.background.fail", e.getMessage());
        }
    }

    public boolean ensureTextInputMode(Context context, BotConfig config, String reason) {
        return ensureTextInputMode(context, config, "", reason);
    }

    public boolean ensureTextInputMode(Context context, BotConfig config, String sessionName, String reason) {
        return ensureInputMode(context, config, sessionName, true, reason);
    }

    public boolean ensureVoiceInputMode(Context context, BotConfig config, String reason) {
        return ensureVoiceInputMode(context, config, "", reason);
    }

    public boolean ensureVoiceInputMode(Context context, BotConfig config, String sessionName, String reason) {
        return ensureInputMode(context, config, sessionName, false, reason);
    }

    public void clearStoredInputMode(Context context, String sessionName) {
        context.getSharedPreferences(INPUT_MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(inputModeKey(sessionName))
                .remove(inputModeKey(sessionName) + ".pressX")
                .remove(inputModeKey(sessionName) + ".pressY")
                .apply();
    }

    public static void clearAllStoredInputModes(Context context) {
        context.getSharedPreferences(INPUT_MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    public static void syncAllowedSessionInputModes(Context context, BotConfig config) {
        BotConfig safeConfig = config == null ? BotConfig.load(context) : config;
        boolean voiceMode = safeConfig.normalReplyAsVoice
                || safeConfig.imageWarmupAsVoice
                || safeConfig.imageAfterAsVoice;
        String mode = voiceMode ? MODE_VOICE : MODE_TEXT;
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(INPUT_MODE_PREFS, Context.MODE_PRIVATE)
                .edit();
        int count = 0;
        for (String sessionName : safeConfig.allowedSessionList()) {
            String key = inputModeKey(sessionName);
            editor.putString(key, mode);
            if (!voiceMode) {
                editor.remove(key + ".pressX")
                        .remove(key + ".pressY");
            }
            count++;
        }
        editor.apply();
        BotLog.i(context, "input.mode.whitelist.sync", "已按配置同步白名单会话输入态 mode="
                + mode + " count=" + count);
    }

    public int[] cachedVoicePressPoint(Context context, String sessionName) {
        if (!MODE_VOICE.equals(storedInputMode(context, sessionName))) {
            return null;
        }
        String key = inputModeKey(sessionName);
        android.content.SharedPreferences prefs = context.getSharedPreferences(INPUT_MODE_PREFS, Context.MODE_PRIVATE);
        int x = prefs.getInt(key + ".pressX", -1);
        int y = prefs.getInt(key + ".pressY", -1);
        if (x <= 0 || y <= 0) {
            return null;
        }
        return new int[]{x, y};
    }

    private boolean ensureInputMode(Context context, BotConfig config, String sessionName, boolean needText, String reason) {
        String target = needText ? MODE_TEXT : MODE_VOICE;
        String stored = storedInputMode(context, sessionName);
        if (stored.isEmpty()) {
            OcrHelper.InputModeFeature detected = scanInitialInputMode(context, config, sessionName, reason);
            stored = modeFromFirstScan(detected);
            if (stored.isEmpty()) {
                BotLog.w(context, "input.mode.initial_unknown", "会话输入态为空，首次扫描仍未确认，取消切换 target="
                        + target + " sessionName=" + sessionName + " reason=" + reason + " "
                        + (detected == null ? "state=null" : detected.summary()));
                return false;
            }
            saveInputMode(context, sessionName, stored, detected);
            BotLog.i(context, "input.mode.initial_saved", "首次扫描并记录会话输入态 sessionName=" + sessionName
                    + " mode=" + stored + " reason=" + reason + " " + detected.summary());
        }
        if (target.equals(stored)) {
            if (MODE_VOICE.equals(target) && cachedVoicePressPoint(context, sessionName) == null) {
                if (!rebuildVoiceInputMode(context, config, sessionName, reason)) {
                    return false;
                }
            }
            BotLog.i(context, "input.mode.ready", "按会话状态机确认输入态 sessionName=" + sessionName
                    + " mode=" + stored + " reason=" + reason);
            return true;
        }
        OcrHelper.InputModeFeature state = OcrHelper.inspectInputMode(context, hs);
        if (isTargetInputModeReady(target, state)) {
            saveInputMode(context, sessionName, target, state);
            BotLog.i(context, "input.mode.current_matches", "现场输入态已是目标态，更新缓存并跳过切换 sessionName="
                    + sessionName + " target=" + target + " cached=" + stored
                    + " reason=" + reason + " " + state.summary());
            return true;
        }
        if (state == null || state.toggleRect == null) {
            BotLog.w(context, "input.mode.toggle.missing", "会话状态机需要切换，但未找到左侧切换按钮 target="
                    + target + " current=" + stored + " sessionName=" + sessionName + " reason=" + reason + " "
                    + (state == null ? "state=null" : state.summary()));
            return false;
        }
        if (!tapInputModeToggle(context, target, reason, state, false)) {
            return false;
        }
        long settleMs = Math.max(800L, config == null ? 1200L : config.inputModeToggleSettleMs);
        SystemClock.sleep(settleMs);
        OcrHelper.InputModeFeature confirmed = null;
        if (MODE_VOICE.equals(target)) {
            confirmed = OcrHelper.inspectInputMode(context, hs);
            if (!isVoiceInputReady(confirmed) || confirmed.inputRect == null) {
                BotLog.w(context, "input.mode.voice_confirm_failed", "切换到语音态后未识别到按住说话，不保存语音态 sessionName="
                        + sessionName + " reason=" + reason + " "
                        + (confirmed == null ? "state=null" : confirmed.summary()));
                return false;
            }
        }
        saveInputMode(context, sessionName, target, confirmed);
        BotLog.write(context, "SUCCESS", "input.mode.switched",
                "按会话状态机切换输入态 sessionName=" + sessionName
                        + " from=" + stored + " to=" + target
                        + " settleMs=" + settleMs + " reason=" + reason);
        return true;
    }

    private boolean rebuildVoiceInputMode(Context context, BotConfig config, String sessionName, String reason) {
        BotLog.w(context, "input.mode.voice_point_missing", "会话语音态缺少按压坐标，先扫描按住说话，不切换输入态 sessionName="
                + sessionName + " reason=" + reason);
        OcrHelper.InputModeFeature state = scanVoicePressPointOnly(context, config, sessionName, reason + "-voice-point-rebuild");
        if (isVoiceInputReady(state) && state.inputRect != null) {
            saveInputMode(context, sessionName, MODE_VOICE, state);
            BotLog.i(context, "input.mode.voice_point_rebuilt", "重新扫描命中语音态并保存按住说话坐标 sessionName="
                    + sessionName + " reason=" + reason + " " + state.summary());
            return true;
        }
        if (!isTextInputReady(state)) {
            BotLog.w(context, "input.mode.voice_rebuild_state_unknown", "语音态缺坐标但未确认当前是文字态，停止切换避免乱点 sessionName="
                    + sessionName + " reason=" + reason + " "
                    + (state == null ? "state=null" : state.summary()));
            return false;
        }
        if (state == null || state.toggleRect == null) {
            BotLog.w(context, "input.mode.voice_rebuild_toggle_missing", "重建语音态时未找到切换按钮，取消发送 sessionName="
                    + sessionName + " reason=" + reason + " "
                    + (state == null ? "state=null" : state.summary()));
            return false;
        }
        if (!tapInputModeToggle(context, MODE_VOICE, reason + "-voice-point-rebuild", state, false)) {
            return false;
        }
        long settleMs = Math.max(800L, config == null ? 1200L : config.inputModeToggleSettleMs);
        SystemClock.sleep(settleMs);
        OcrHelper.InputModeFeature confirmed = scanVoicePressPointOnly(context, config, sessionName, reason + "-voice-point-confirm");
        if (!isVoiceInputReady(confirmed) || confirmed.inputRect == null) {
            BotLog.w(context, "input.mode.voice_rebuild_confirm_failed", "重建语音态后未识别到按住说话，不保存坐标 sessionName="
                    + sessionName + " reason=" + reason + " settleMs=" + settleMs + " "
                    + (confirmed == null ? "state=null" : confirmed.summary()));
            return false;
        }
        saveInputMode(context, sessionName, MODE_VOICE, confirmed);
        BotLog.write(context, "SUCCESS", "input.mode.voice_point_rebuilt",
                "重建并保存该群按住说话坐标 sessionName=" + sessionName
                        + " settleMs=" + settleMs + " reason=" + reason + " "
                        + confirmed.summary());
        return true;
    }

    private OcrHelper.InputModeFeature scanVoicePressPointOnly(Context context, BotConfig config,
                                                               String sessionName, String reason) {
        long waitMs = Math.max(500L, config == null ? 800L : config.wechatChatOcrPollMs);
        OcrHelper.InputModeFeature lastState = null;
        for (int i = 1; i <= 5; i++) {
            OcrHelper.InputModeFeature state = OcrHelper.inspectInputMode(context, hs);
            if (state != null) {
                lastState = state;
            }
            if (isVoiceInputReady(state) && state.inputRect != null) {
                BotLog.i(context, "input.mode.voice_point.scan_hit", "扫描命中按住说话 sessionName="
                        + sessionName + " round=" + i + " reason=" + reason + " " + state.summary());
                return state;
            }
            BotLog.i(context, "input.mode.voice_point.scan", "扫描未命中按住说话 sessionName="
                    + sessionName + " round=" + i + " reason=" + reason + " "
                    + (state == null ? "state=null" : state.summary()));
            SystemClock.sleep(waitMs);
        }
        return lastState;
    }

    private OcrHelper.InputModeFeature scanInitialInputMode(Context context, BotConfig config,
                                                            String sessionName, String reason) {
        long waitMs = Math.max(350L, config == null ? 600L : Math.min(1200L, config.wechatChatOcrPollMs));
        OcrHelper.InputModeFeature textCandidate = null;
        OcrHelper.InputModeFeature lastState = null;
        for (int i = 1; i <= 4; i++) {
            OcrHelper.InputModeFeature state = OcrHelper.inspectInputMode(context, hs);
            if (state != null) {
                lastState = state;
            }
            if (isVoiceInputReady(state)) {
                BotLog.i(context, "input.mode.initial.voice_hit", "首次状态机扫描命中按住说话 sessionName="
                        + sessionName + " round=" + i + " reason=" + reason + " " + state.summary());
                return state;
            }
            if (isTextInputReady(state) && textCandidate == null) {
                textCandidate = state;
            }
            BotLog.i(context, "input.mode.initial.scan", "首次状态机扫描未命中按住说话 sessionName="
                    + sessionName + " round=" + i + " reason=" + reason + " "
                    + (state == null ? "state=null" : state.summary()));
            SystemClock.sleep(waitMs);
        }
        if (textCandidate != null) {
            BotLog.i(context, "input.mode.initial.text_after_scans", "连续扫描未命中按住说话，记录文字态 sessionName="
                    + sessionName + " reason=" + reason + " " + textCandidate.summary());
            return textCandidate;
        }
        if (lastState != null) {
            BotLog.i(context, "input.mode.initial.text_without_visual", "连续扫描未命中按住说话，按文字态记录 sessionName="
                    + sessionName + " reason=" + reason + " " + lastState.summary());
        }
        return lastState;
    }

    private boolean tapInputModeToggle(Context context, String target, String reason,
                                       OcrHelper.InputModeFeature state, boolean retry) {
        int x = state.toggleCenterX();
        int y = state.toggleCenterY();
        try {
            hs.tap(x, y);
            BotLog.i(context, retry ? "input.mode.toggle.retry_tap" : "input.mode.toggle.tap",
                    "点击左下角输入模式按钮切换 target=" + target
                            + " x=" + x + " y=" + y
                            + " reason=" + reason + " " + state.summary());
            return true;
        } catch (Exception e) {
            BotLog.w(context, "input.mode.toggle.fail", "target=" + target + " reason=" + reason
                    + " retry=" + retry + " error=" + e.getMessage());
            return false;
        }
    }

    private static String modeFromFirstScan(OcrHelper.InputModeFeature state) {
        if (isVoiceInputReady(state)) {
            return MODE_VOICE;
        }
        if (state != null && !state.pressTalkTextHit) {
            return MODE_TEXT;
        }
        return "";
    }

    private static String storedInputMode(Context context, String sessionName) {
        String key = inputModeKey(sessionName);
        String value = context.getSharedPreferences(INPUT_MODE_PREFS, Context.MODE_PRIVATE)
                .getString(key, "");
        return value == null ? "" : value.trim();
    }

    private static void saveInputMode(Context context, String sessionName, String mode,
                                      OcrHelper.InputModeFeature feature) {
        String key = inputModeKey(sessionName);
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(INPUT_MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key, mode == null ? "" : mode.trim());
        if (MODE_VOICE.equals(mode) && feature != null && feature.inputRect != null) {
            editor.putInt(key + ".pressX", feature.inputRect.centerX())
                    .putInt(key + ".pressY", feature.inputRect.centerY());
        } else {
            editor.remove(key + ".pressX")
                    .remove(key + ".pressY");
        }
        editor.apply();
    }

    private static String inputModeKey(String sessionName) {
        String value = sessionName == null ? "" : sessionName.trim();
        return value.isEmpty() ? "__global__" : value;
    }

    private static boolean isTextInputReady(OcrHelper.InputModeFeature state) {
        if (state == null) {
            return false;
        }
        return !state.pressTalkTextHit
                && state.isTextModeLikely()
                && (state.textInputVisualHit || state.voiceIconLikely || state.inputRect != null);
    }

    private static boolean isVoiceInputReady(OcrHelper.InputModeFeature state) {
        if (state == null) {
            return false;
        }
        return state.pressTalkTextHit && state.isVoiceModeLikely();
    }

    private static boolean isTargetInputModeReady(String target, OcrHelper.InputModeFeature state) {
        if (MODE_VOICE.equals(target)) {
            return isVoiceInputReady(state);
        }
        return isTextInputReady(state);
    }

    private void backUntilLeaveWechat(Context context, String reason) throws Exception {
        for (int i = 1; i <= 5; i++) {
            if (!isWechatForeground()) {
                BotLog.i(context, "back.background.confirmed", "已确认离开微信 reason=" + reason + " steps=" + (i - 1));
                return;
            }
            hs.key("BACK");
            SystemClock.sleep(i == 1 ? 900 : 1200);
            BotLog.i(context, "back.step", "已执行 Back step=" + i + " reason=" + reason
                    + " foregroundPackage=" + foregroundPackageName()
                    + " leftWechat=" + !isWechatForeground());
            if (!isWechatForeground()) {
                BotLog.i(context, "back.background.confirmed", "已确认离开微信 reason=" + reason + " steps=" + i
                        + " foregroundPackage=" + foregroundPackageName());
                return;
            }
        }
        BotLog.w(context, "back.background.unconfirmed", "多次 Back 后仍在微信 reason=" + reason
                + " foregroundPackage=" + foregroundPackageName());
    }

    private boolean openTargetChat(Context context, BotConfig config, WxMessage message) throws Exception {
        boolean openedByShade = openNoticeFromShade(context, config, message);
        if (!openedByShade) {
            BotLog.w(context, "notice.open.list_fallback", "通知栏没有打开目标通知，改走微信会话列表 OCR sessionName="
                    + message.sessionName);
            return openTargetChatFromConversationList(context, config, message, "shade-failed");
        }
        SystemClock.sleep(stepDelay(config));
        if (!waitWechatForeground(Math.max(8000, config.notificationSettleMs + 5000))) {
            BotLog.w(context, "notice.open.foreground.fallback", "点击通知后微信未到前台，改走微信会话列表 OCR sessionName="
                    + message.sessionName);
            return openTargetChatFromConversationList(context, config, message, "foreground-unconfirmed");
        }
        SystemClock.sleep(Math.max(config.notificationSettleMs, config.wechatStepDelayMs));
        if (ensureTargetChatByOcr(context, config, message)) {
            return true;
        }
        BotLog.w(context, "notice.open.list_fallback", "已进入微信但未确认目标会话，改走微信会话列表 OCR sessionName="
                + message.sessionName);
        return openTargetChatFromConversationList(context, config, message, "chat-unconfirmed");
    }

    private boolean openTargetChatFromConversationList(Context context, BotConfig config, WxMessage message, String reason) throws Exception {
        collapseNotificationShade(context);
        if (!ensureConversationListForBroadcast(context, config, "notice-fallback:" + reason + ":" + message.sessionName)) {
            BotLog.e(context, "notice.list_fallback.list_failed", "无法进入微信会话列表 reason=" + reason
                    + " sessionName=" + message.sessionName);
            return false;
        }
        if (!clickConversationFromListByOcr(context, config, message.sessionName, null)) {
            BotLog.e(context, "notice.list_fallback.session_missing", "会话列表 OCR 未找到目标会话 reason=" + reason
                    + " sessionName=" + message.sessionName);
            return false;
        }
        if (waitWechatChatByOcr(context, config, Math.max(6500, config.notificationSettleMs + 3500))) {
            BotLog.i(context, "notice.list_fallback.ready", "已通过会话列表进入目标会话 reason=" + reason
                    + " sessionName=" + message.sessionName);
            waitChatBottomReady(context, config, "notice-list-open", message.sessionName);
            return true;
        }
        BotLog.e(context, "notice.list_fallback.chat_failed", "点击会话后仍未确认会话页 reason=" + reason
                + " sessionName=" + message.sessionName);
        return false;
    }

    private boolean openNoticeFromShade(Context context, BotConfig config, WxMessage message) {
        try {
            String shell = hs.shell("cmd", "statusbar", "expand-notifications");
            BotLog.i(context, "notice.shade.expand", compact(shell));
        } catch (Exception e) {
            BotLog.w(context, "notice.shade.expand.error", e.getMessage());
            return false;
        }
        waitNoticeShadeStable(context, config, "first");
        NoticeTarget target = findNoticeTargetWithRetry(context, config, message, 1);
        if (target == null) {
            BotLog.w(context, "notice.shade.target.missing", "通知栏未找到目标通知 sessionName=" + message.sessionName
                    + " text=" + message.text);
            collapseNotificationShade(context);
            return false;
        }
        if (tapNoticeTarget(context, config, target, 1)) {
            return true;
        }
        if (skipNoticeRetryIfWechatPageReady(context, config, message)) {
            return true;
        }
        BotLog.w(context, "notice.shade.tap.fallback", "点击通知未确认进入微信，交给会话列表 OCR 补偿 sessionName="
                + message.sessionName);
        collapseNotificationShade(context);
        return false;
    }

    private boolean skipNoticeRetryIfWechatPageReady(Context context, BotConfig config, WxMessage message) {
        SystemClock.sleep(stepDelay(config));
        PageInfo page = inspectWechatScreenByOcr(context, "after_notice_first_tap");
        BotLog.i(context, "notice.shade.retry.guard", "sessionName=" + message.sessionName
                + " page=" + page.page
                + " bottomScore=" + page.bottomScore()
                + " texts=" + (page.screen == null ? "" : page.screen.snippets));
        if ("chat".equals(page.page) || "list".equals(page.page)) {
            BotLog.i(context, "notice.shade.retry.skip", "首次点击通知后已进入微信页面，跳过二次展开通知 sessionName="
                    + message.sessionName + " page=" + page.page);
            return true;
        }
        return false;
    }

    private void collapseNotificationShade(Context context) {
        try {
            String shell = hs.shell("cmd", "statusbar", "collapse");
            BotLog.i(context, "notice.shade.collapse", compact(shell));
            SystemClock.sleep(500);
        } catch (Exception e) {
            BotLog.w(context, "notice.shade.collapse.fail", e.getMessage());
            try {
                hs.key("BACK");
                SystemClock.sleep(500);
            } catch (Exception ignored) {
            }
        }
    }

    private void waitNoticeShadeStable(Context context, BotConfig config, String phase) {
        long shadeWaitMs = config == null ? SHADE_SETTLE_MS : config.notificationShadeSettleMs;
        long ocrStableMs = config == null ? SHADE_OCR_STABLE_MS : config.notificationShadeOcrStableMs;
        SystemClock.sleep(shadeWaitMs);
        SystemClock.sleep(ocrStableMs);
        BotLog.i(context, "notice.shade.stable_wait", "phase=" + phase
                + " shadeWaitMs=" + shadeWaitMs
                + " ocrStableMs=" + ocrStableMs);
    }

    private NoticeTarget findNoticeTargetWithRetry(Context context, BotConfig config, WxMessage message, int attempt) {
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        NoticeTarget target = findNoticeTargetInShade(screen, message);
        if (target != null) {
            return target;
        }
        if (looksLikeQuickSettingsOnly(screen)) {
            target = recoverNoticeTargetFromQuickSettings(context, config, message, attempt, screen);
            if (target != null) {
                return target;
            }
        }
        long waitMs = config == null ? 800L : config.notificationOcrRetryDelayMs;
        SystemClock.sleep(waitMs);
        BotLog.i(context, "notice.shade.ocr.retry_wait", "attempt=" + attempt + " waitMs=" + waitMs);
        screen = OcrHelper.inspect(context, hs);
        target = findNoticeTargetInShade(screen, message);
        if (target == null) {
            BotLog.w(context, "notice.shade.target.retry.missing", "attempt=" + attempt
                    + " sessionName=" + message.sessionName
                    + " snippets=" + (screen == null ? "" : screen.snippets));
        }
        return target;
    }

    private NoticeTarget recoverNoticeTargetFromQuickSettings(Context context, BotConfig config,
                                                              WxMessage message, int attempt,
                                                              OcrHelper.Screen firstScreen) {
        long waitMs = config == null ? 800L : config.notificationOcrRetryDelayMs;
        String firstSnippets = firstScreen == null ? "" : firstScreen.snippets;
        for (int i = 1; i <= 3; i++) {
            try {
                SystemClock.sleep(Math.max(600L, waitMs));
                OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
                NoticeTarget target = findNoticeTargetInShade(screen, message);
                if (target != null) {
                    BotLog.i(context, "notice.shade.quick_only.late_hit", "attempt=" + attempt
                            + " round=" + i + " firstSnippets=" + firstSnippets);
                    return target;
                }
                if (i == 1) {
                    int width = screen == null ? 720 : screen.width;
                    int height = screen == null ? 1540 : screen.height;
                    int x = Math.round(width * 0.50f);
                    int y1 = Math.round(height * 0.23f);
                    int y2 = Math.round(height * 0.77f);
                    BotLog.i(context, "notice.shade.quick_only.swipe", "attempt=" + attempt
                            + " x=" + x + " y1=" + y1 + " y2=" + y2
                            + " snippets=" + (screen == null ? "" : screen.snippets));
                    hs.shell("input", "swipe",
                            String.valueOf(x), String.valueOf(y1),
                            String.valueOf(x), String.valueOf(y2),
                            "260");
                    waitNoticeShadeStable(context, config, "quick_only_swipe");
                } else {
                    BotLog.i(context, "notice.shade.quick_only.wait", "attempt=" + attempt
                            + " round=" + i
                            + " snippets=" + (screen == null ? "" : screen.snippets));
                }
            } catch (Exception e) {
                BotLog.w(context, "notice.shade.quick_only.recover.fail", "attempt=" + attempt
                        + " round=" + i + " error=" + e.getMessage());
            }
        }
        try {
            String shell = hs.shell("cmd", "statusbar", "expand-notifications");
            BotLog.i(context, "notice.shade.quick_only.reexpand", "attempt=" + attempt + " " + compact(shell)
                    + " snippets=" + firstSnippets);
            waitNoticeShadeStable(context, config, "quick_only_retry");
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            return findNoticeTargetInShade(screen, message);
        } catch (Exception e) {
            BotLog.w(context, "notice.shade.quick_only.reexpand.fail", e.getMessage());
            return null;
        }
    }

    private NoticeTarget findNoticeTargetInShade(OcrHelper.Screen screen, WxMessage message) {
        if (screen == null) {
            return null;
        }
        String targetName = normalizeOcrName(message.sessionName);
        String targetText = normalizeOcrName(message.text);
        String textPrefix = targetText.length() >= 2 ? targetText.substring(0, Math.min(6, targetText.length())) : "";
        OcrHelper.OcrItem fallback = null;
        for (OcrHelper.OcrItem item : screen.items) {
            if (!isNoticeCardArea(screen, item)) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (value.isEmpty()) {
                continue;
            }
            if (!targetName.isEmpty() && (value.contains(targetName) || targetName.contains(value))) {
                return buildNoticeTarget(screen, item);
            }
            if (!textPrefix.isEmpty() && value.contains(textPrefix)) {
                fallback = item;
            }
        }
        if (fallback != null) {
            return buildNoticeTarget(screen, fallback);
        }
        return null;
    }

    private NoticeTarget buildNoticeTarget(OcrHelper.Screen screen, OcrHelper.OcrItem item) {
        int minX = Math.max(80, Math.round(screen.width * 0.18f));
        int maxX = Math.max(minX, screen.width - Math.max(80, Math.round(screen.width * 0.08f)));
        int minY = Math.round(screen.height * 0.22f);
        int maxY = Math.round(screen.height * 0.84f);
        int safeMaxX = Math.min(maxX, Math.round(screen.width * 0.58f));
        int tapX = clamp(Math.max(item.centerX, Math.round(screen.width * 0.30f)), minX, safeMaxX);
        NoticeBounds bounds = estimateNoticeBounds(screen, item);
        int cardTop = bounds.top;
        int cardBottom = bounds.bottom;
        int tapY = clamp((cardTop + cardBottom) / 2, minY, maxY);
        return new NoticeTarget(item.text, item.centerX, item.centerY, tapX, tapY, cardTop, cardBottom);
    }

    private NoticeBounds estimateNoticeBounds(OcrHelper.Screen screen, OcrHelper.OcrItem target) {
        int minY = Math.round(screen.height * 0.22f);
        int maxY = Math.round(screen.height * 0.84f);
        int innerGap = Math.max(48, Math.round(screen.height * 0.055f));
        List<Integer> lineCenters = new ArrayList<>();
        for (OcrHelper.OcrItem item : screen.items) {
            if (!isNoticeCardArea(screen, item)) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (value.isEmpty() || isNoticeShadeControlText(value)) {
                continue;
            }
            addUniqueLineCenter(lineCenters, item.centerY);
        }
        if (lineCenters.isEmpty()) {
            int top = Math.max(minY, target.rect.top - Math.round(screen.height * 0.045f));
            int bottom = Math.min(maxY, target.rect.bottom + Math.round(screen.height * 0.060f));
            return new NoticeBounds(top, bottom);
        }
        Collections.sort(lineCenters);
        int index = nearestLineIndex(lineCenters, target.centerY);
        int first = index;
        int last = index;
        while (first > 0 && lineCenters.get(first) - lineCenters.get(first - 1) <= innerGap) {
            first--;
        }
        while (last + 1 < lineCenters.size() && lineCenters.get(last + 1) - lineCenters.get(last) <= innerGap) {
            last++;
        }
        int firstY = lineCenters.get(first);
        int lastY = lineCenters.get(last);
        int top = first > 0
                ? (lineCenters.get(first - 1) + firstY) / 2
                : target.rect.top - Math.round(screen.height * 0.050f);
        int bottom = last + 1 < lineCenters.size()
                ? (lastY + lineCenters.get(last + 1)) / 2
                : target.rect.bottom + Math.round(screen.height * 0.070f);
        top = clamp(top, minY, maxY);
        bottom = clamp(bottom, minY, maxY);
        if (bottom <= top + Math.max(32, Math.round(screen.height * 0.020f))) {
            top = Math.max(minY, target.rect.top - Math.round(screen.height * 0.045f));
            bottom = Math.min(maxY, target.rect.bottom + Math.round(screen.height * 0.060f));
        }
        int maxHeight = Math.max(140, Math.round(screen.height * 0.140f));
        if (bottom - top > maxHeight) {
            int center = (firstY + lastY) / 2;
            top = clamp(center - maxHeight / 2, minY, maxY);
            bottom = clamp(center + maxHeight / 2, minY, maxY);
        }
        return new NoticeBounds(top, bottom);
    }

    private static void addUniqueLineCenter(List<Integer> lineCenters, int centerY) {
        for (Integer existing : lineCenters) {
            if (Math.abs(existing - centerY) <= 6) {
                return;
            }
        }
        lineCenters.add(centerY);
    }

    private static int nearestLineIndex(List<Integer> lineCenters, int centerY) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < lineCenters.size(); i++) {
            int distance = Math.abs(lineCenters.get(i) - centerY);
            if (distance < bestDistance) {
                bestIndex = i;
                bestDistance = distance;
            }
        }
        return bestIndex;
    }

    private static boolean isNoticeShadeControlText(String value) {
        return value.contains("互联网")
                || value.contains("蓝牙")
                || value.contains("手电筒")
                || value.contains("勿扰")
                || value.contains("静音")
                || value.contains("全部清除")
                || value.equals("管理")
                || value.equals("三")
                || value.equals("凸");
    }

    private static boolean isNoticeCardArea(OcrHelper.Screen screen, OcrHelper.OcrItem item) {
        if (screen == null || item == null) {
            return false;
        }
        return item.centerY >= screen.height * 0.22f && item.centerY <= screen.height * 0.84f;
    }

    private static boolean looksLikeQuickSettingsOnly(OcrHelper.Screen screen) {
        if (screen == null) {
            return false;
        }
        String snippets = normalizeOcrName(screen.snippets);
        boolean quick = snippets.contains("互联网")
                || snippets.contains("蓝牙")
                || snippets.contains("手电筒")
                || snippets.contains("勿扰")
                || snippets.contains("静音");
        boolean hasNotice = snippets.contains("微信") || snippets.contains("wechat");
        return quick && !hasNotice;
    }

    private boolean tapNoticeTarget(Context context, BotConfig config, NoticeTarget target, int attempt) {
        try {
            hs.tap(target.tapX, target.tapY);
            BotLog.i(context, "notice.shade.target.safe_tap", "已点击目标通知安全点 attempt=" + attempt
                    + " text=" + target.text
                    + " textX=" + target.textX + " textY=" + target.textY
                    + " tapX=" + target.tapX + " tapY=" + target.tapY
                    + " cardTop=" + target.cardTop + " cardBottom=" + target.cardBottom);
            SystemClock.sleep(Math.max(1000L, stepDelay(config)));
            if (waitWechatForeground(2500)) {
                BotLog.i(context, "notice.shade.tap.confirmed", "点击通知后已确认微信前台 attempt=" + attempt
                        + " foregroundPackage=" + foregroundPackageName());
                return true;
            }
            BotLog.w(context, "notice.shade.tap.unconfirmed", "点击通知后未确认微信前台 attempt=" + attempt
                    + " foregroundPackage=" + foregroundPackageName());
            return false;
        } catch (Exception e) {
            BotLog.w(context, "notice.shade.tap.fail", "attempt=" + attempt + " error=" + e.getMessage());
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean ensureTargetChatByOcr(Context context, BotConfig config, WxMessage message) throws Exception {
        SystemClock.sleep(stepDelay(config));
        long timeoutMs = Math.max(6500, config.notificationSettleMs + 3200);
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        PageInfo last = null;
        while (SystemClock.uptimeMillis() < deadline) {
            last = inspectWechatScreenByOcr(context, "after_notice");
            BotLog.i(context, "notice.open.ocr.page", "sessionName=" + message.sessionName
                    + " page=" + last.page
                    + " ocrCount=" + (last.screen == null ? 0 : last.screen.items.size())
                    + " bottomScore=" + last.bottomScore()
                    + " toolbarScore=" + last.toolbarScore()
                    + " texts=" + (last.screen == null ? "" : last.screen.snippets));
            if ("chat".equals(last.page)) {
                BotLog.i(context, "notice.chat.ready", "已确认在会话页 sessionName=" + message.sessionName);
                waitChatBottomReady(context, config, "notice", message.sessionName);
                return true;
            }
            if ("list".equals(last.page)) {
                BotLog.w(context, "notice.open.list", "点击通知后落到会话列表，尝试进入目标会话 sessionName=" + message.sessionName);
                if (clickConversationFromListByOcr(context, config, message.sessionName, last.screen)
                        && waitWechatChatByOcr(context, config, Math.max(5000, config.notificationSettleMs + 2500))) {
                    BotLog.i(context, "notice.chat.ready", "已从会话列表进入目标会话页 sessionName=" + message.sessionName);
                    waitChatBottomReady(context, config, "notice-list-fallback", message.sessionName);
                    return true;
                }
                return false;
            }
            SystemClock.sleep(stepDelay(config));
        }
        BotLog.w(context, "notice.open.unknown", "未确认进入会话页 sessionName=" + message.sessionName
                + " page=" + (last == null ? "" : last.page));
        return false;
    }

    private PageInfo inspectWechatScreenByOcr(Context context, String label) {
        String foreground = foregroundPackageName();
        if (foreground == null || !foreground.contains("com.tencent.mm")) {
            return new PageInfo("unknown", null, "not_wechat_foreground");
        }
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        if (screen == null) {
            return new PageInfo("unknown", null, "ocr_failed");
        }
        boolean list = isWechatConversationListByOcr(screen);
        String lowerForeground = foreground.toLowerCase();
        boolean chatActivity = lowerForeground.contains("chatting");
        String page = list ? "list" : (screen.bottom.score >= 3 || (chatActivity && screen.bottom.score >= 1) ? "chat" : "unknown");
        return new PageInfo(page, screen, label);
    }

    private boolean isWechatForeground() {
        try {
            String top = foregroundPackageName();
            return top != null && top.contains("com.tencent.mm");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String foregroundPackageName() {
        String top = "";
        try {
            top = hs.stateTop();
            top = top == null ? "" : top.trim();
            if (!top.isEmpty() && !"null".equalsIgnoreCase(top) && !top.startsWith("ERR:")) {
                return top;
            }
        } catch (Exception ignored) {
        }
        String fallback = foregroundPackageNameFromDumpsys();
        return fallback.isEmpty() ? top : fallback;
    }

    private String foregroundPackageNameFromDumpsys() {
        try {
            String dump = hs.shell("dumpsys", "activity", "activities");
            Matcher matcher = DUMPSYS_RESUMED_ACTIVITY.matcher(dump);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean waitWechatForeground(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (isWechatForeground()) {
                return true;
            }
            SystemClock.sleep(250);
        }
        return false;
    }

    private boolean isWechatConversationListByOcr(OcrHelper.Screen screen) {
        boolean tabWechat = false;
        boolean tabContacts = false;
        boolean tabDiscover = false;
        boolean tabMe = false;
        boolean topWechatTitle = false;
        for (OcrHelper.OcrItem item : screen.items) {
            String value = item.clean;
            if (value.isEmpty()) {
                continue;
            }
            if (item.centerY < screen.height * 0.13f && WECHAT_LIST_TITLE.matcher(value).matches()) {
                topWechatTitle = true;
            }
            if (item.centerY > screen.height * 0.84f) {
                if (value.contains("微信")) {
                    tabWechat = true;
                }
                if (value.contains("通讯录")) {
                    tabContacts = true;
                }
                if (value.contains("发现")) {
                    tabDiscover = true;
                }
                if ("我".equals(value) || value.endsWith("我")) {
                    tabMe = true;
                }
            }
        }
        int tabCount = 0;
        if (tabWechat) tabCount++;
        if (tabContacts) tabCount++;
        if (tabDiscover) tabCount++;
        if (tabMe) tabCount++;
        return tabCount >= 3 || (topWechatTitle && tabCount >= 2);
    }

    private boolean clickConversationFromListByOcr(Context context, BotConfig config, String sessionName, OcrHelper.Screen currentScreen) throws Exception {
        OcrHelper.Screen screen = currentScreen != null ? currentScreen : OcrHelper.inspect(context, hs);
        OcrHelper.OcrItem item = findConversationByOcrItems(screen, sessionName);
        if (item == null) {
            BotLog.w(context, "wechat.list.session.missing", "OCR 未找到目标会话 sessionName=" + sessionName
                    + " ocrCount=" + (screen == null ? 0 : screen.items.size())
                    + " snippets=" + (screen == null ? "" : screen.snippets));
            return false;
        }
        int x = Math.round(screen.width * 0.45f);
        int y = item.centerY;
        hs.tap(x, y);
        BotLog.i(context, "wechat.list.session.found", "OCR 找到目标会话 sessionName=" + sessionName
                + " text=" + item.text + " x=" + x + " y=" + y);
        SystemClock.sleep(Math.max(1200L, stepDelay(config)));
        return true;
    }

    private OcrHelper.OcrItem findConversationByOcrItems(OcrHelper.Screen screen, String sessionName) {
        if (screen == null) {
            return null;
        }
        String target = normalizeOcrName(sessionName);
        if (target.isEmpty()) {
            return null;
        }
        OcrHelper.OcrItem best = null;
        int bestScore = Integer.MAX_VALUE;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY < screen.height * 0.10f || item.centerY > screen.height * 0.84f) {
                continue;
            }
            String value = item.clean;
            if (value.isEmpty()) {
                continue;
            }
            boolean matched = target.length() <= 2
                    ? value.equals(target)
                    : value.equals(target) || value.contains(target) || target.contains(value);
            if (!matched) {
                continue;
            }
            int score = item.rect.top + Math.abs(item.centerX - Math.round(screen.width * 0.28f));
            if (score < bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean waitWechatChatByOcr(Context context, BotConfig config, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            PageInfo info = inspectWechatScreenByOcr(context, "wait_chat");
            if ("chat".equals(info.page)) {
                BotLog.i(context, "wechat.chat.ocr.ready", "bottomScore=" + info.bottomScore()
                        + " toolbarScore=" + info.toolbarScore()
                        + " inputBrightRatio=" + info.inputBrightRatio()
                        + " leftDarkHit=" + info.leftDarkHit()
                        + " rightDarkHit=" + info.rightDarkHit()
                        + " voiceIconHit=" + info.voiceIconHit()
                        + " rightIconHit=" + info.rightIconHit());
                return true;
            }
            SystemClock.sleep(config == null ? 600L : config.wechatChatOcrPollMs);
        }
        return false;
    }

    private void waitChatBottomReady(Context context, BotConfig config, String reason, String sessionName) {
        long waitMs = config == null ? 2000L : config.chatReadyExtraDelayMs;
        if (waitMs <= 0) {
            return;
        }
        BotLog.i(context, "wechat.chat.bottom_wait", "进入会话后等待底部栏渲染 waitMs=" + waitMs
                + " reason=" + reason
                + " sessionName=" + sessionName);
        SystemClock.sleep(waitMs);
    }

    private boolean openSessionBySearchForBroadcast(Context context, BotConfig config, String sessionName) throws Exception {
        if (!ensureConversationListForBroadcast(context, config, "broadcast:" + sessionName)) {
            BotLog.e(context, "broadcast.search.list.failed", "主动群发无法进入微信会话列表 sessionName=" + sessionName);
            return false;
        }
        if (!tapSearchEntryForBroadcast(context, config)) {
            return false;
        }
        if (!setSearchKeywordForBroadcast(context, config, sessionName)) {
            return false;
        }
        if (!waitSearchResultReadyForBroadcast(context, config, sessionName)) {
            return false;
        }
        if (!tapSearchResultForBroadcast(context, config, sessionName)) {
            return false;
        }
        if (!waitForTargetChatByTitle(context, config, sessionName, Math.max(8000, config.notificationSettleMs + 5000), "broadcast_search_confirm")) {
            BotLog.e(context, "broadcast.search.chat.failed", "微信搜索结果进入目标会话失败 sessionName=" + sessionName);
            return false;
        }
        waitChatBottomReady(context, config, "broadcast-search", sessionName);
        BotLog.write(context, "SUCCESS", "broadcast.search.chat.confirmed", "群发搜索已进入目标会话 sessionName=" + sessionName);
        return true;
    }

    private boolean ensureConversationListForBroadcast(Context context, BotConfig config, String reason) throws Exception {
        for (int attempt = 1; attempt <= 6; attempt++) {
            if (!isWechatForeground()) {
                hs.startActivity(WECHAT_COMPONENT);
                BotLog.i(context, "broadcast.wechat.open", "已打开微信 reason=" + reason);
            }
            SystemClock.sleep(Math.max(700L, stepDelay(config)));
            if (!waitWechatForeground(Math.max(5000, config.notificationSettleMs + 2500))) {
                BotLog.w(context, "broadcast.wechat.foreground.missing", "等待微信前台超时 attempt=" + attempt);
                continue;
            }
            PageInfo state = inspectWechatScreenByOcr(context, "broadcast_list_" + attempt);
            BotLog.i(context, "broadcast.page.inspect", "主动发送前检查微信页面 reason=" + reason
                    + " attempt=" + attempt
                    + " page=" + state.page
                    + " ocrCount=" + (state.screen == null ? 0 : state.screen.items.size())
                    + " title=" + titleFromOcr(state.screen));
            if ("list".equals(state.page)) {
                return true;
            }
            hs.key("BACK");
            SystemClock.sleep(Math.max(1200L, stepDelay(config)));
        }
        return false;
    }

    private boolean tapSearchEntryForBroadcast(Context context, BotConfig config) throws Exception {
        PageInfo state = inspectWechatScreenByOcr(context, "broadcast_search_entry");
        OcrHelper.Screen screen = state.screen;
        Rect searchIcon = OcrHelper.findTopRightSearchIcon(context, hs);
        if (searchIcon == null) {
            BotLog.e(context, "broadcast.search.entry.missing", "会话列表未找到顶部放大镜图标 ocrCount="
                    + (screen == null ? 0 : screen.items.size()));
            return false;
        }
        hs.tap(searchIcon.centerX(), searchIcon.centerY());
        BotLog.i(context, "broadcast.search.entry.icon", "截图找到微信搜索入口并点击 x="
                + searchIcon.centerX() + " y=" + searchIcon.centerY()
                + " rect=" + searchIcon.flattenToString());
        broadcastStepDelay(config, "search_entry_tap");
        return true;
    }

    private boolean setSearchKeywordForBroadcast(Context context, BotConfig config, String sessionName) throws Exception {
        for (int attempt = 1; attempt <= 2; attempt++) {
            OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
            Rect input = findSearchInputRectForBroadcast(screen);
            if (input == null) {
                int width = screen == null ? 720 : screen.width;
                int height = screen == null ? 1540 : screen.height;
                input = broadcastSearchBoxFallback(width, height);
                BotLog.w(context, "broadcast.search.input.fallback", "顶部搜索框 OCR 未命中，按固定顶部搜索条区域点击 sessionName="
                        + sessionName + " attempt=" + attempt
                        + " rect=" + input.flattenToString()
                        + " snippets=" + (screen == null ? "" : screen.snippets));
            }
            hs.tap(input.centerX(), input.centerY());
            broadcastStepDelay(config, "search_input_tap");
            setAppClipboard(context, sessionName);
            String pasteResult = hs.keyCode(279);
            BotLog.i(context, "broadcast.search.input.keypaste", "已按 OCR 搜索框粘贴群名 sessionName="
                    + sessionName + " x=" + input.centerX() + " y=" + input.centerY()
                    + " rect=" + input.flattenToString()
                    + " attempt=" + attempt
                    + " result=" + pasteResult);
            broadcastStepDelay(config, "search_input_paste");
            if (waitSearchResultReadyForBroadcast(context, config, sessionName)) {
                return true;
            }
            BotLog.w(context, "broadcast.search.input.retry", "粘贴后未看到稳定搜索结果区，重试 sessionName="
                    + sessionName + " attempt=" + attempt);
        }
        return false;
    }

    private boolean waitSearchResultReadyForBroadcast(Context context, BotConfig config, String sessionName) {
        long configured = config == null ? 6000L : config.broadcastSearchResultWaitMs;
        long waitMs = Math.max(2500, Math.min(12000, configured));
        long areaStableMs = config == null ? 800L : config.broadcastSearchAreaStableMs;
        long pollMs = config == null ? 500L : config.broadcastSearchPollMs;
        long deadline = SystemClock.uptimeMillis() + waitMs;
        int lastCount = 0;
        long resultAreaSeenAt = 0L;
        while (SystemClock.uptimeMillis() < deadline) {
            PageInfo state = inspectWechatScreenByOcr(context, "broadcast_search_result_ready");
            OcrHelper.Screen screen = state.screen;
            lastCount = screen == null ? 0 : screen.items.size();
            OcrHelper.OcrItem item = findBroadcastFrequentResultItem(screen, sessionName);
            if (item != null) {
                BotLog.i(context, "broadcast.search.result.ready", "微信搜索最常使用区已出现目标群 sessionName=" + sessionName
                        + " text=" + item.text + " x=" + item.centerX + " y=" + item.centerY
                        + " ocrCount=" + lastCount);
                return true;
            }
            if (hasBroadcastFirstResultArea(screen)) {
                if (resultAreaSeenAt == 0L) {
                    resultAreaSeenAt = SystemClock.uptimeMillis();
                    BotLog.i(context, "broadcast.search.result.area.seen", "OCR 看到搜索结果固定区域，等待稳定 sessionName="
                            + sessionName + " ocrCount=" + lastCount);
                } else if (SystemClock.uptimeMillis() - resultAreaSeenAt >= areaStableMs) {
                    BotLog.i(context, "broadcast.search.result.area.ready", "搜索结果固定区域已稳定，按手动跑通流程继续 sessionName="
                            + sessionName + " ocrCount=" + lastCount);
                    return true;
                }
            } else {
                resultAreaSeenAt = 0L;
            }
            SystemClock.sleep(pollMs);
        }
        BotLog.e(context, "broadcast.search.result.not_ready", "OCR 未看到稳定搜索结果区域，停止群发搜索 sessionName="
                + sessionName + " ocrCount=" + lastCount);
        return false;
    }

    private boolean tapSearchResultForBroadcast(Context context, BotConfig config, String sessionName) throws Exception {
        OcrHelper.Screen screen = OcrHelper.inspect(context, hs);
        OcrHelper.OcrItem item = findBroadcastFrequentResultItem(screen, sessionName);
        int width = screen == null ? 720 : screen.width;
        int height = screen == null ? 1540 : screen.height;
        if (item != null) {
            int tapX = clamp(Math.round(width * 0.25f), Math.round(width * 0.12f), Math.round(width * 0.60f));
            int tapY = clamp(item.centerY, Math.round(height * 0.14f), Math.round(height * 0.36f));
            hs.tap(tapX, tapY);
            BotLog.i(context, "broadcast.search.result.frequent_name_tap", "OCR 命中最常使用目标并点击 sessionName="
                    + sessionName + " text=" + item.text + " x=" + item.centerX + " y=" + item.centerY
                    + " tapX=" + tapX + " tapY=" + tapY
                    + " rect=" + item.rect.flattenToString());
            broadcastStepDelay(config, "search_result_frequent_name_tap");
            return true;
        }
        OcrHelper.OcrItem frequent = findBroadcastFrequentHeader(screen);
        if (frequent != null) {
            int tapX = clamp(Math.round(width * 0.24f), 1, width - 2);
            int nextSectionTop = findBroadcastNextSectionTop(screen, frequent);
            int lowerBound = frequent.rect.bottom + 20;
            int upperBound = nextSectionTop > 0 ? nextSectionTop - 12 : Math.round(height * 0.34f);
            int tapY = clamp(frequent.rect.bottom + Math.max(62, Math.round(height * 0.048f)),
                    lowerBound, Math.max(lowerBound, upperBound));
            hs.tap(tapX, tapY);
            BotLog.i(context, "broadcast.search.result.frequent_fixed_tap", "OCR 未命中目标名，按最常使用标题下方第一条点击 sessionName="
                    + sessionName + " header=" + frequent.text
                    + " headerRect=" + frequent.rect.flattenToString()
                    + " x=" + tapX + " y=" + tapY
                    + " screen=" + width + "x" + height);
            broadcastStepDelay(config, "search_result_frequent_fixed_tap");
            return true;
        }
        BotLog.e(context, "broadcast.search.result.tap_missing", "OCR 未找到最常使用区域，取消点击 sessionName="
                + sessionName + " snippets=" + (screen == null ? "" : screen.snippets));
        return false;
    }

    private boolean hasBroadcastFirstResultArea(OcrHelper.Screen screen) {
        if (screen == null) {
            return false;
        }
        if (findBroadcastFrequentHeader(screen) != null) {
            return true;
        }
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY <= screen.height * 0.14f || item.centerY >= screen.height * 0.32f) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (value.isEmpty() || value.contains("搜索") || value.contains("取消")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private OcrHelper.OcrItem findBroadcastFrequentHeader(OcrHelper.Screen screen) {
        if (screen == null) {
            return null;
        }
        OcrHelper.OcrItem best = null;
        int bestScore = Integer.MAX_VALUE;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY <= screen.height * 0.10f || item.centerY >= screen.height * 0.40f) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (!(value.contains("最常使用") || value.contains("常使用") || value.contains("最常"))) {
                continue;
            }
            int score = item.rect.top + Math.abs(item.centerX - Math.round(screen.width * 0.12f));
            if (score < bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best;
    }

    private Rect findSearchInputRectForBroadcast(OcrHelper.Screen screen) {
        if (screen == null) {
            return null;
        }
        OcrHelper.OcrItem best = null;
        int bestScore = Integer.MAX_VALUE;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerX < screen.width * 0.06f || item.centerX > screen.width * 0.88f) {
                continue;
            }
            if (item.centerY < screen.height * 0.045f || item.centerY > screen.height * 0.12f) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (!(value.contains("搜索") || value.contains("搜素") || value.contains("度索"))) {
                continue;
            }
            int score = Math.abs(item.centerY - Math.round(screen.height * 0.073f))
                    + Math.abs(item.centerX - Math.round(screen.width * 0.18f));
            if (score < bestScore) {
                best = item;
                bestScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        return new Rect(
                Math.round(screen.width * 0.09f),
                Math.round(screen.height * 0.050f),
                Math.round(screen.width * 0.86f),
                Math.round(screen.height * 0.118f));
    }

    private Rect broadcastSearchBoxFallback(int width, int height) {
        return new Rect(
                Math.round(width * 0.09f),
                Math.round(height * 0.050f),
                Math.round(width * 0.86f),
                Math.round(height * 0.118f));
    }

    private void broadcastStepDelay(BotConfig config, String step) {
        SystemClock.sleep(config == null ? BROADCAST_STEP_DELAY_MS : config.broadcastStepDelayMs);
    }

    private OcrHelper.OcrItem findBroadcastSearchResultItem(OcrHelper.Screen screen, String sessionName) {
        return findBroadcastFrequentResultItem(screen, sessionName);
    }

    private OcrHelper.OcrItem findBroadcastFrequentResultItem(OcrHelper.Screen screen, String sessionName) {
        if (screen == null) {
            return null;
        }
        OcrHelper.OcrItem header = findBroadcastFrequentHeader(screen);
        if (header == null) {
            return null;
        }
        int nextSectionTop = findBroadcastNextSectionTop(screen, header);
        int minY = header.rect.bottom;
        int maxY = nextSectionTop > 0
                ? nextSectionTop
                : Math.min(Math.round(screen.height * 0.36f), header.rect.bottom + Math.round(screen.height * 0.15f));
        OcrHelper.OcrItem best = null;
        int bestScore = Integer.MAX_VALUE;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.centerY <= minY || item.centerY >= maxY) {
                continue;
            }
            if (!isLikelyOcrName(item.text, sessionName)) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (isBroadcastResultNoise(value)) {
                continue;
            }
            int score = item.rect.top;
            if (value.equals(normalizeOcrName(sessionName))) {
                score -= 200;
            }
            if (score < bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best;
    }

    private int findBroadcastNextSectionTop(OcrHelper.Screen screen, OcrHelper.OcrItem frequentHeader) {
        if (screen == null || frequentHeader == null) {
            return -1;
        }
        int best = Integer.MAX_VALUE;
        for (OcrHelper.OcrItem item : screen.items) {
            if (item.rect.top <= frequentHeader.rect.bottom) {
                continue;
            }
            String value = normalizeOcrName(item.text);
            if (isBroadcastSectionHeader(value)) {
                best = Math.min(best, item.rect.top);
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static boolean isBroadcastSectionHeader(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return value.contains("聊天记录")
                || value.contains("群聊")
                || value.contains("联系人")
                || value.contains("搜索网络结果")
                || value.contains("网络结果");
    }

    private static boolean isBroadcastResultNoise(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.contains("包含")
                || value.contains("我是群聊")
                || value.contains("相关的聊天记录")
                || value.matches(".*\\d{1,2}:\\d{2}.*")
                || value.matches(".*\\d+月\\d+日.*");
    }

    private boolean waitForTargetChatByTitle(Context context, BotConfig config, String sessionName, long timeoutMs, String label) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            PageInfo state = inspectWechatScreenByOcr(context, label);
            String title = titleFromOcr(state.screen);
            BotLog.i(context, "broadcast.chat.confirm.check", "sessionName=" + sessionName
                    + " page=" + state.page
                    + " title=" + title);
            if ("chat".equals(state.page) && isLikelyOcrName(title, sessionName)) {
                return true;
            }
            SystemClock.sleep(config == null ? 600L : config.wechatChatOcrPollMs);
        }
        return false;
    }

    private boolean sendBroadcastTextInCurrentChat(Context context, BotConfig config, String sessionName, String text) throws Exception {
        if (!ensureTextInputMode(context, config, sessionName, "broadcast-text")) {
            BotLog.e(context, "broadcast.input.mode.abort", "群发发送前未确认文字输入模式，取消输入动作");
            return false;
        }
        Rect input = OcrHelper.findChatInputBlock(context, hs);
        if (input == null) {
            BotLog.e(context, "broadcast.input.missing", "群发会话中未识别到底部输入框");
            return false;
        }
        hs.tap(input.centerX(), input.centerY());
        SystemClock.sleep(stepDelay(config));
        BotLog.i(context, "broadcast.input.tap", "群发按截图输入框点击 x=" + input.centerX()
                + " y=" + input.centerY() + " rect=" + input.flattenToString());
        setAppClipboard(context, text);
        hs.keyCode(279);
        BotLog.i(context, "broadcast.input.keypaste", "群发输入框聚焦后按 KEYCODE_PASTE");
        SystemClock.sleep(Math.max(500L, config.sendButtonDelayMs));
        Rect button = waitBroadcastGreenSendButton(context, 3500);
        if (button == null) {
            BotLog.e(context, "broadcast.send.button.missing", "群发未找到绿色发送按钮，取消发送");
            return false;
        }
        hs.tap(button.centerX(), button.centerY());
        BotLog.i(context, "broadcast.send.button.tap", "群发取色点击绿色发送按钮 x="
                + button.centerX() + " y=" + button.centerY() + " rect=" + button.flattenToString());
        SystemClock.sleep(600);
        backUntilLeaveWechat(context, "broadcast-sent");
        return true;
    }

    private Rect waitBroadcastGreenSendButton(Context context, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            Rect button = OcrHelper.findGreenSendButton(context, hs);
            if (button != null) {
                return button;
            }
            Rect ocrButton = OcrHelper.findBottomRightText(context, hs, "发送");
            if (ocrButton != null) {
                BotLog.i(context, "broadcast.send.button.ocr.hit", "群发 OCR 找到发送按钮 rect=" + ocrButton.flattenToString());
                return ocrButton;
            }
            Rect dumpButton = findSendButtonByDump();
            if (dumpButton != null) {
                BotLog.i(context, "broadcast.send.button.dump.hit", "群发 dump 找到发送按钮 rect=" + dumpButton.flattenToString());
                return dumpButton;
            }
            SystemClock.sleep(400);
        }
        return null;
    }

    private boolean focusAndSetText(Context context, BotConfig config, String text) throws Exception {
        if (pasteAfterImageInputFocus(context, config, text)) {
            return true;
        }
        String selector = resolveEditTextSelector(context);
        String focused = hs.focusNode(selector);
        if (focused.startsWith("ERR:")) {
            BotLog.w(context, "input.focus.fail", focused);
            String clicked = hs.clickNode(selector);
            if (clicked.startsWith("ERR:")) {
                BotLog.w(context, "input.click.fail", clicked);
            }
        }
        String result = hs.setText(selector, text);
        if (!result.startsWith("ERR:")) {
            BotLog.i(context, "input.set_text", "已通过 node_set_text 输入 selector=" + selector);
            if (waitSendButtonVisible(context, 1200)) {
                return true;
            }
            BotLog.w(context, "input.set_text.no_send", "node_set_text 后未出现发送按钮，继续尝试");
        } else {
            BotLog.w(context, "input.set_text.fail", result);
        }
        setAppClipboard(context, text);
        String pasted = hs.paste(selector);
        if (!pasted.startsWith("ERR:")) {
            BotLog.i(context, "input.clipboard", "已通过 hs paste 输入");
            if (waitSendButtonVisible(context, 1200)) {
                return true;
            }
            BotLog.w(context, "input.clipboard.no_send", "selector paste 后未出现发送按钮，继续尝试");
        } else {
            BotLog.w(context, "input.paste.fail", pasted);
        }
        String textInput = hs.textInput(text);
        if (!textInput.startsWith("ERR:")) {
            BotLog.i(context, "input.textcmd", "已通过 hs text 输入");
            if (waitSendButtonVisible(context, 1200)) {
                return true;
            }
            BotLog.w(context, "input.textcmd.no_send", "hs text 后未出现发送按钮，继续尝试");
        } else {
            BotLog.w(context, "input.textcmd.fail", textInput);
        }
        return false;
    }

    private boolean setAppClipboard(Context context, String text) {
        String value = text == null ? "" : text;
        try {
            if (setClipboardViaApp(context, value)) {
                BotLog.i(context, "input.clipboard.set", "已通过 APK 主线程设置剪贴板 length=" + value.length());
                SystemClock.sleep(250L);
                return true;
            }
            BotLog.w(context, "input.clipboard.set.fail", "APK 写入后读回校验失败 length=" + value.length());
        } catch (Exception e) {
            BotLog.w(context, "input.clipboard.set.fail", e.getMessage());
        }
        try {
            String result = hs.clipSet(value);
            SystemClock.sleep(250L);
            if (result.startsWith("ERR:")) {
                BotLog.w(context, "input.clipboard.hs_set.fail", result);
                return false;
            }
            if (!verifyClipboardViaApp(context, value)) {
                BotLog.w(context, "input.clipboard.hs_set.verify_fail", "hs 返回成功但剪贴板读回不一致 length=" + value.length());
                return false;
            }
            BotLog.i(context, "input.clipboard.hs_set", "已通过 hs 设置剪贴板并校验 length=" + value.length());
            return true;
        } catch (Exception inner) {
            BotLog.w(context, "input.clipboard.hs_set.fail", inner.getMessage());
            return false;
        }
    }

    private boolean setClipboardViaApp(Context context, String value) throws Exception {
        return runClipboardOnMainThread(() -> {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return false;
            }
            manager.setPrimaryClip(ClipData.newPlainText("vxbot", value));
            return clipboardMatches(context, manager, value);
        });
    }

    private boolean verifyClipboardViaApp(Context context, String value) throws Exception {
        return runClipboardOnMainThread(() -> {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            return manager != null && clipboardMatches(context, manager, value);
        });
    }

    private boolean clipboardMatches(Context context, ClipboardManager manager, String value) {
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() <= 0) {
            return value.isEmpty();
        }
        CharSequence current = clip.getItemAt(0).getText();
        if (current == null) {
            current = clip.getItemAt(0).coerceToText(context);
        }
        return value.contentEquals(current == null ? "" : current);
    }

    private boolean runClipboardOnMainThread(ClipboardTask task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.run();
        }
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = new boolean[1];
        Exception[] error = new Exception[1];
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = task.run();
            } catch (Exception e) {
                error[0] = e;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(2000L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("main thread clipboard timeout");
        }
        if (error[0] != null) {
            throw error[0];
        }
        return result[0];
    }

    private interface ClipboardTask {
        boolean run() throws Exception;
    }

    private boolean pasteAfterImageInputFocus(Context context, BotConfig config, String text) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Rect input = OcrHelper.findChatInputBlock(context, hs);
                if (input == null) {
                    return false;
                }
                hs.tap(input.centerX(), input.centerY());
                SystemClock.sleep(stepDelay(config));
                BotLog.i(context, "input.image.tap", "已按截图识别输入框点击 attempt=" + attempt
                        + " x=" + input.centerX() + " y=" + input.centerY()
                        + " rect=" + input.flattenToString());
                setAppClipboard(context, text);
                hs.keyCode(279);
                BotLog.i(context, "input.image.keypaste.try", "输入框聚焦后按 KEYCODE_PASTE attempt=" + attempt);
                if (waitSendButtonVisible(context, 2500)) {
                    BotLog.i(context, "input.image.keypaste", "截图输入框粘贴后已出现发送按钮 attempt=" + attempt);
                    return true;
                }
                BotLog.w(context, "input.image.keypaste.no_send", "截图输入框粘贴后未出现发送按钮 attempt=" + attempt);
            } catch (Exception e) {
                BotLog.w(context, "input.image.focus.fail", e.getMessage());
            }
        }
        return false;
    }

    private String resolveEditTextSelector(Context context) {
        try {
            String dump = hs.dumpActive();
            Object json = dump.trim().startsWith("[") ? new JSONArray(dump) : new JSONObject(dump);
            JSONObject node = findFirstInputNode(json);
            if (node != null) {
                String selector = nodeToDaemonSelector(node);
                BotLog.i(context, "input.selector", "dump 解析输入框 selector=" + selector);
                return selector;
            }
        } catch (Exception e) {
            BotLog.w(context, "input.selector.fail", e.getMessage());
        }
        return "class=EditText";
    }

    private JSONObject findFirstInputNode(Object node) throws Exception {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findFirstInputNode(array.get(i));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!(node instanceof JSONObject)) {
            return null;
        }
        JSONObject object = (JSONObject) node;
        String cls = firstString(object, "cls", "class", "className");
        if (isInputClass(cls)) {
            return object;
        }
        Object root = object.opt("root");
        if (root instanceof JSONObject || root instanceof JSONArray) {
            JSONObject found = findFirstInputNode(root);
            if (found != null) {
                return found;
            }
        }
        Object windows = object.opt("windows");
        if (windows instanceof JSONObject || windows instanceof JSONArray) {
            JSONObject found = findFirstInputNode(windows);
            if (found != null) {
                return found;
            }
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = object.opt(key);
            if (child instanceof JSONObject || child instanceof JSONArray) {
                JSONObject found = findFirstInputNode(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isInputClass(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        return cls.endsWith(".EditText")
                || cls.endsWith(".AutoCompleteTextView")
                || cls.endsWith(".MultiAutoCompleteTextView")
                || "EditText".equals(cls)
                || "AutoCompleteTextView".equals(cls)
                || "MultiAutoCompleteTextView".equals(cls);
    }

    private static String nodeToDaemonSelector(JSONObject node) {
        String rid = firstString(node, "rid", "id", "resourceId", "resource-id");
        if (!rid.isEmpty()) {
            return "id=" + rid;
        }
        String cls = firstString(node, "cls", "class", "className");
        String simple = cls;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < simple.length()) {
            simple = simple.substring(dot + 1);
        }
        if (simple.isEmpty()) {
            simple = "EditText";
        }
        String text = firstString(node, "text");
        if (!text.isEmpty()) {
            return "class=" + simple + " text=" + HsClient.quote(text);
        }
        return "class=" + simple;
    }

    private boolean tapSendButton(Context context) throws Exception {
        Rect green = OcrHelper.findGreenSendButton(context, hs);
        if (green != null) {
            hs.tap(green.centerX(), green.centerY());
            BotLog.i(context, "send.button.green.tap", "取色找到绿色发送按钮 x=" + green.centerX() + " y=" + green.centerY());
            return true;
        }
        try {
            String wait = hs.waitForText("发送", 2500);
            if (!wait.startsWith("ERR:")) {
                BotLog.i(context, "send.button.wait", wait);
            }
        } catch (Exception e) {
            BotLog.w(context, "send.button.wait.fail", e.getMessage());
        }
        String clicked = hs.clickNode("text=\"发送\"");
        if (!clicked.startsWith("ERR:")) {
            BotLog.i(context, "send.button.node", "node_click text=发送 成功");
            return true;
        }
        BotLog.w(context, "send.button.node.fail", clicked);
        String descClicked = hs.clickNode("desc=\"发送\"");
        if (!descClicked.startsWith("ERR:")) {
            BotLog.i(context, "send.button.desc", "node_click desc=发送 成功");
            return true;
        }
        BotLog.w(context, "send.button.desc.fail", descClicked);
        Rect rect = findSendButtonByDump();
        if (rect != null) {
            hs.tap(rect.centerX(), rect.centerY());
            BotLog.i(context, "send.button.tap", "dump 找到发送按钮 x=" + rect.centerX() + " y=" + rect.centerY());
            return true;
        }
        green = OcrHelper.findGreenSendButton(context, hs);
        if (green != null) {
            hs.tap(green.centerX(), green.centerY());
            BotLog.i(context, "send.button.green.tap", "取色找到绿色发送按钮 x=" + green.centerX() + " y=" + green.centerY());
            return true;
        }
        Rect ocrRect = OcrHelper.findBottomRightText(context, hs, "发送");
        if (ocrRect != null) {
            hs.tap(ocrRect.centerX(), ocrRect.centerY());
            BotLog.i(context, "send.button.ocr.tap", "OCR 找到发送按钮 x=" + ocrRect.centerX() + " y=" + ocrRect.centerY());
            return true;
        }
        BotLog.w(context, "send.button.submit.skip", "未找到绿色发送按钮，不使用 submit/回车伪发送");
        return false;
    }

    private boolean waitSendButtonVisible(Context context, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                String wait = hs.waitForText("发送", 300);
                if (!wait.startsWith("ERR:")) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            if (findSendButtonByDump() != null) {
                return true;
            }
            if (OcrHelper.findGreenSendButton(context, hs) != null) {
                return true;
            }
            Rect ocrRect = OcrHelper.findBottomRightText(context, hs, "发送");
            if (ocrRect != null) {
                return true;
            }
            SystemClock.sleep(150);
        }
        return false;
    }

    private Rect findSendButtonByDump() {
        try {
            String dump = hs.dumpActive();
            Object json = dump.trim().startsWith("[") ? new JSONArray(dump) : new JSONObject(dump);
            return findSendRect(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Rect findSendRect(Object node) throws Exception {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Rect rect = findSendRect(array.get(i));
                if (rect != null) {
                    return rect;
                }
            }
            return null;
        }
        if (!(node instanceof JSONObject)) {
            return null;
        }
        JSONObject object = (JSONObject) node;
        String text = firstString(object, "text", "contentDescription", "desc", "label");
        if ("发送".equals(text)) {
            Rect rect = parseBounds(firstString(object, "bounds", "boundsInScreen", "rect"));
            if (rect != null) {
                return rect;
            }
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = object.opt(key);
            if (child instanceof JSONObject || child instanceof JSONArray) {
                Rect rect = findSendRect(child);
                if (rect != null) {
                    return rect;
                }
            }
        }
        return null;
    }

    private static String normalizeOcrName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "")
                .replace("：", ":")
                .replace(" ", "")
                .trim();
    }

    private static String normalizeOcrContent(String value) {
        return normalizeOcrName(value)
                .replace("別", "别")
                .replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "")
                .trim();
    }

    private static boolean isLikelyOcrName(String text, String name) {
        String a = normalizeOcrContent(text);
        String b = normalizeOcrContent(name);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        if (a.length() > b.length() + 8) {
            return false;
        }
        int hit = 0;
        for (int i = 0; i < b.length(); i++) {
            if (a.indexOf(b.charAt(i)) >= 0) {
                hit++;
            }
        }
        return hit >= Math.max(2, (int) Math.ceil(b.length() * 0.6d));
    }

    private static String titleFromOcr(OcrHelper.Screen screen) {
        if (screen == null) {
            return "";
        }
        OcrHelper.OcrItem candidate = null;
        int bestScore = Integer.MAX_VALUE;
        int centerX = Math.round(screen.width * 0.5f);
        for (OcrHelper.OcrItem item : screen.items) {
            String text = item.text == null ? "" : item.text.trim();
            String value = normalizeOcrName(text);
            if (value.isEmpty()
                    || "微信".equals(value)
                    || "返回".equals(value)
                    || "聊天信息".equals(value)
                    || "通讯录".equals(value)
                    || "发现".equals(value)
                    || "我".equals(value)
                    || value.matches("^\\d{1,2}:\\d{2}$")
                    || value.matches("^(P[:0-9/.\\s-]*|d[XY]:|Xv:|Yv:|Prs:|Size:).*")
                    || value.matches("^\\d+$")) {
                continue;
            }
            if (item.centerY > screen.height * 0.25f) {
                continue;
            }
            int score = item.rect.top + Math.abs(item.centerX - centerX) * 3;
            if (score < bestScore) {
                candidate = item;
                bestScore = score;
            }
        }
        return candidate == null ? "" : candidate.text.trim();
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\r", " ").replace("\n", " ").trim();
        if (text.length() > 120) {
            return text.substring(0, 120);
        }
        return text;
    }

    private static final class NoticeBounds {
        final int top;
        final int bottom;

        NoticeBounds(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final class NoticeTarget {
        final String text;
        final int textX;
        final int textY;
        final int tapX;
        final int tapY;
        final int cardTop;
        final int cardBottom;

        NoticeTarget(String text, int textX, int textY, int tapX, int tapY, int cardTop, int cardBottom) {
            this.text = text == null ? "" : text;
            this.textX = textX;
            this.textY = textY;
            this.tapX = tapX;
            this.tapY = tapY;
            this.cardTop = cardTop;
            this.cardBottom = cardBottom;
        }
    }

    private static final class PageInfo {
        final String page;
        final OcrHelper.Screen screen;
        final String reason;

        PageInfo(String page, OcrHelper.Screen screen, String reason) {
            this.page = page;
            this.screen = screen;
            this.reason = reason;
        }

        int bottomScore() {
            return screen == null ? 0 : screen.bottom.score;
        }

        float inputBrightRatio() {
            return screen == null ? 0f : screen.bottom.inputBrightRatio;
        }

        int leftDarkHit() {
            return screen == null ? 0 : screen.bottom.leftDarkHit;
        }

        int rightDarkHit() {
            return screen == null ? 0 : screen.bottom.rightDarkHit;
        }

        int toolbarScore() {
            return screen == null ? 0 : screen.bottom.toolbarScore;
        }

        int voiceIconHit() {
            return screen == null ? 0 : screen.bottom.voiceIconHit;
        }

        int rightIconHit() {
            return screen == null ? 0 : screen.bottom.rightIconHit;
        }
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static Rect parseBounds(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Matcher matcher = BOUNDS.matcher(raw);
        if (matcher.find()) {
            return new Rect(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)));
        }
        return null;
    }
}
