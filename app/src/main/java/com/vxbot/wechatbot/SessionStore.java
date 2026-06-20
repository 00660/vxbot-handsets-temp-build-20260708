package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SessionStore {
    private static final int MAX_LINES = 15;
    private static final String PREFS = "session_store";
    private static final String KEY_ACTIVE_PREFIX = "active_sender_";
    private static final String KEY_CODEX_MODE_SENDER_PREFIX = "codex_mode_sender_";

    private final Map<String, ArrayDeque<String>> histories = new HashMap<>();
    private final Map<String, String> activeSenders = new HashMap<>();
    private final Map<String, Long> shutupCooldownUntil = new HashMap<>();
    private final Map<String, ModeTarget> roastTargets = new HashMap<>();
    private final Map<String, ModeTarget> loverTargets = new HashMap<>();

    public synchronized boolean shouldHandle(Context context, WxMessage message, BotConfig config) {
        String session = message.sessionName;
        if (!config.isAllowedSession(session)) {
            return false;
        }
        if (MessageRouter.isCodexModeEnterCommand(message.text, config)) {
            if (canEnableSessionCodexMode(message, config)) {
                return true;
            }
            BotLog.i(context, "codex.session.not_allowed",
                    "Codex 模式入口拒绝，发起人不在续聊控制人白名单 group="
                            + session + " sender=" + message.senderName);
            return false;
        }
        if (isSessionCodexMode(context, message, config)) {
            return true;
        }
        long until = shutupCooldownUntil.getOrDefault(session, 0L);
        if (System.currentTimeMillis() < until) {
            return false;
        }
        ModeTarget roast = activeRoastTarget(session);
        if (roast != null) {
            return NameNormalizer.sameName(roast.targetName, message.senderName) || MessageRouter.isRoastExitCommand(message.text);
        }
        ModeTarget lover = activeLoverTarget(session);
        if (lover != null) {
            return NameNormalizer.sameName(lover.targetName, message.senderName) || MessageRouter.isLoverExitCommand(message.text);
        }
        boolean mentioned = config.isBotMentioned(message.text);
        boolean reportCommand = MessageRouter.isReportCommand(message.text);
        boolean personaCommand = MessageRouter.isPersonaCommand(message.text);
        if (mentioned || reportCommand || personaCommand) {
            if (canLockActiveSender(message, config)) {
                activeSenders.put(session, message.senderName);
                if (config.enableFollowUpWithoutMention) {
                    persistActiveSender(context, session, message.senderName);
                }
            } else if (config.lockActiveSender && config.enableFollowUpWithoutMention && !message.senderName.isEmpty()) {
                BotLog.i(context, "followup.sender.not_allowed",
                        "发起人不在续聊白名单，不写入发起人锁 group=" + session + " sender=" + message.senderName);
            }
            return true;
        }
        if (!config.lockActiveSender) {
            return false;
        }
        String active = activeSenders.get(session);
        if (config.enableFollowUpWithoutMention && active != null && !active.isEmpty()
                && !config.isFollowUpSenderAllowed(active)) {
            activeSenders.remove(session);
            clearPersistedActiveSender(context, session);
            return false;
        }
        if ((active == null || active.isEmpty()) && config.enableFollowUpWithoutMention) {
            active = persistedActiveSender(context, session);
            if (active != null && !active.isEmpty() && config.isFollowUpSenderAllowed(active)) {
                activeSenders.put(session, active);
            } else if (active != null && !active.isEmpty()) {
                clearPersistedActiveSender(context, session);
                active = "";
            }
        }
        return active != null && NameNormalizer.sameName(active, message.senderName);
    }

    private static boolean canLockActiveSender(WxMessage message, BotConfig config) {
        if (message == null || config == null || !config.lockActiveSender || message.senderName.isEmpty()) {
            return false;
        }
        if (!config.enableFollowUpWithoutMention) {
            return true;
        }
        return config.isFollowUpSenderAllowed(message.senderName);
    }

    public synchronized void remember(WxMessage message, String role) {
        ArrayDeque<String> lines = histories.computeIfAbsent(message.sessionName, key -> new ArrayDeque<>());
        lines.addLast(role + ":" + message.senderName + ":" + message.text);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public synchronized void rememberBot(String sessionName, String botName, String text) {
        ArrayDeque<String> lines = histories.computeIfAbsent(sessionName, key -> new ArrayDeque<>());
        lines.addLast("bot:" + botName + ":" + text);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public synchronized List<String> contextOf(String sessionName) {
        ArrayDeque<String> lines = histories.get(sessionName);
        return lines == null ? new ArrayList<>() : new ArrayList<>(lines);
    }

    public synchronized boolean isSessionCodexMode(Context context, WxMessage message, BotConfig config) {
        if (context == null || message == null || config == null) {
            return false;
        }
        String sender = prefs(context).getString(codexModeSenderKey(message.sessionName), "");
        if (sender == null || sender.trim().isEmpty()) {
            return false;
        }
        if (!config.isFollowUpSenderAllowed(sender)) {
            clearSessionCodexMode(context, message.sessionName);
            return false;
        }
        return NameNormalizer.sameName(sender, message.senderName);
    }

    public synchronized boolean canEnableSessionCodexMode(WxMessage message, BotConfig config) {
        return message != null
                && config != null
                && config.isAllowedSession(message.sessionName)
                && config.isFollowUpSenderAllowed(message.senderName);
    }

    public synchronized void enableSessionCodexMode(Context context, WxMessage message, BotConfig config) {
        if (context == null || !canEnableSessionCodexMode(message, config)) {
            return;
        }
        prefs(context).edit()
                .putString(codexModeSenderKey(message.sessionName), message.senderName.trim())
                .apply();
    }

    public synchronized void muteFor(String sessionName, long millis) {
        shutupCooldownUntil.put(sessionName, System.currentTimeMillis() + millis);
    }

    public synchronized void setRoastTarget(String sessionName, String targetName, long millis) {
        if (targetName == null || targetName.trim().isEmpty()) {
            return;
        }
        roastTargets.put(sessionName, new ModeTarget(targetName.trim(), System.currentTimeMillis() + millis));
    }

    public synchronized void setLoverTarget(String sessionName, String targetName, long millis) {
        if (targetName == null || targetName.trim().isEmpty()) {
            return;
        }
        loverTargets.put(sessionName, new ModeTarget(targetName.trim(), System.currentTimeMillis() + millis));
    }

    public synchronized void clearRoastTarget(String sessionName) {
        roastTargets.remove(sessionName);
    }

    public synchronized void clearLoverTarget(String sessionName) {
        loverTargets.remove(sessionName);
    }

    public synchronized String roastTargetName(String sessionName) {
        ModeTarget target = activeRoastTarget(sessionName);
        return target == null ? "" : target.targetName;
    }

    public synchronized String loverTargetName(String sessionName) {
        ModeTarget target = activeLoverTarget(sessionName);
        return target == null ? "" : target.targetName;
    }

    private ModeTarget activeRoastTarget(String sessionName) {
        return activeTarget(roastTargets, sessionName);
    }

    private ModeTarget activeLoverTarget(String sessionName) {
        return activeTarget(loverTargets, sessionName);
    }

    private ModeTarget activeTarget(Map<String, ModeTarget> targets, String sessionName) {
        ModeTarget target = targets.get(sessionName);
        if (target == null) {
            return null;
        }
        if (System.currentTimeMillis() > target.expiresAt) {
            targets.remove(sessionName);
            return null;
        }
        return target;
    }

    private static void persistActiveSender(Context context, String sessionName, String senderName) {
        if (context == null || sessionName == null || sessionName.trim().isEmpty()
                || senderName == null || senderName.trim().isEmpty()) {
            return;
        }
        prefs(context).edit().putString(activeSenderKey(sessionName), senderName.trim()).apply();
    }

    private static String persistedActiveSender(Context context, String sessionName) {
        if (context == null || sessionName == null || sessionName.trim().isEmpty()) {
            return "";
        }
        return prefs(context).getString(activeSenderKey(sessionName), "");
    }

    private static void clearPersistedActiveSender(Context context, String sessionName) {
        if (context == null || sessionName == null || sessionName.trim().isEmpty()) {
            return;
        }
        prefs(context).edit().remove(activeSenderKey(sessionName)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String activeSenderKey(String sessionName) {
        String normalized = NameNormalizer.nameKey(sessionName);
        return KEY_ACTIVE_PREFIX + Integer.toHexString(normalized.hashCode());
    }

    private static void clearSessionCodexMode(Context context, String sessionName) {
        if (context == null || sessionName == null || sessionName.trim().isEmpty()) {
            return;
        }
        prefs(context).edit().remove(codexModeSenderKey(sessionName)).apply();
    }

    private static String codexModeSenderKey(String sessionName) {
        String normalized = NameNormalizer.nameKey(sessionName);
        return KEY_CODEX_MODE_SENDER_PREFIX + Integer.toHexString(normalized.hashCode());
    }

    private static final class ModeTarget {
        final String targetName;
        final long expiresAt;

        ModeTarget(String targetName, long expiresAt) {
            this.targetName = targetName;
            this.expiresAt = expiresAt;
        }
    }
}
