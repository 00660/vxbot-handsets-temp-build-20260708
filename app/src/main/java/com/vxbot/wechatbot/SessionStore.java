package com.vxbot.wechatbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SessionStore {
    private static final int MAX_LINES = 15;

    private final Map<String, ArrayDeque<String>> histories = new HashMap<>();
    private final Map<String, String> activeSenders = new HashMap<>();
    private final Map<String, Long> shutupCooldownUntil = new HashMap<>();
    private final Map<String, ModeTarget> roastTargets = new HashMap<>();
    private final Map<String, ModeTarget> loverTargets = new HashMap<>();

    public synchronized boolean shouldHandle(WxMessage message, BotConfig config) {
        String session = message.sessionName;
        if (!config.isAllowedSession(session)) {
            return false;
        }
        long until = shutupCooldownUntil.getOrDefault(session, 0L);
        if (System.currentTimeMillis() < until) {
            return false;
        }
        ModeTarget roast = activeRoastTarget(session);
        if (roast != null) {
            return roast.targetName.equals(message.senderName) || MessageRouter.isRoastExitCommand(message.text);
        }
        ModeTarget lover = activeLoverTarget(session);
        if (lover != null) {
            return lover.targetName.equals(message.senderName) || MessageRouter.isLoverExitCommand(message.text);
        }
        boolean mentioned = config.isBotMentioned(message.text);
        boolean reportCommand = MessageRouter.isReportCommand(message.text);
        if (mentioned || reportCommand) {
            if (config.lockActiveSender && !message.senderName.isEmpty()) {
                activeSenders.put(session, message.senderName);
            }
            return true;
        }
        if (!config.lockActiveSender) {
            return false;
        }
        String active = activeSenders.get(session);
        return active != null && active.equals(message.senderName);
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

    private static final class ModeTarget {
        final String targetName;
        final long expiresAt;

        ModeTarget(String targetName, long expiresAt) {
            this.targetName = targetName;
            this.expiresAt = expiresAt;
        }
    }
}
