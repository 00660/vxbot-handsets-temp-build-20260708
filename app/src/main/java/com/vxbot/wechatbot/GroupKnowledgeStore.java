package com.vxbot.wechatbot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

public final class GroupKnowledgeStore {
    private static final String PREFS = "group_knowledge";
    private static final int MAX_ITEMS = 50;

    private GroupKnowledgeStore() {
    }

    public static String handle(Context context, String session, String command) {
        String text = command == null ? "" : command.trim();
        if (text.matches(".*(?:清空|删除|忘记)(?:本群)?知识库.*")) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(session)).apply();
            return "本群知识库已清空。";
        }
        String content = text.replaceFirst("^.*?(?:记住|加入群知识库|保存到群知识库)[：:]?\\s*", "").trim();
        if (!content.equals(text) && !content.isEmpty()) {
            try {
                JSONArray items = items(context, session);
                items.put(new JSONObject().put("text", content).put("time", System.currentTimeMillis()));
                while (items.length() > MAX_ITEMS) {
                    JSONArray next = new JSONArray();
                    for (int i = 1; i < items.length(); i++) next.put(items.opt(i));
                    items = next;
                }
                save(context, session, items);
                return "已加入本群知识库：" + content;
            } catch (Exception e) {
                BotLog.e(context, "knowledge.save.fail", e.getMessage());
                return "群知识库保存失败：" + e.getMessage();
            }
        }
        JSONArray items = items(context, session);
        if (items.length() == 0) {
            return "本群知识库还是空的。发送“记住：内容”即可添加。";
        }
        StringBuilder out = new StringBuilder("本群知识库：");
        int start = Math.max(0, items.length() - 10);
        for (int i = start; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row != null) out.append("\n").append(i - start + 1).append(". ").append(row.optString("text"));
        }
        return out.toString();
    }

    public static String context(Context context, String session, String query) {
        JSONArray items = items(context, session);
        if (items.length() == 0) return "";
        String normalized = NameNormalizer.contentKey(query);
        StringBuilder out = new StringBuilder("群知识库：");
        int count = 0;
        for (int i = items.length() - 1; i >= 0 && count < 8; i--) {
            JSONObject row = items.optJSONObject(i);
            String text = row == null ? "" : row.optString("text");
            String key = NameNormalizer.contentKey(text);
            if (normalized.length() >= 2 && !key.contains(normalized) && !normalized.contains(key)
                    && !sharesToken(normalized, key)) {
                continue;
            }
            out.append("\n- ").append(text);
            count++;
        }
        return count == 0 ? "" : out.toString();
    }

    private static boolean sharesToken(String left, String right) {
        if (left.length() < 2 || right.length() < 2) return false;
        for (int i = 0; i + 2 <= left.length(); i++) {
            if (right.contains(left.substring(i, i + 2))) return true;
        }
        return false;
    }

    private static JSONArray items(Context context, String session) {
        try {
            return new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(session), "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void save(Context context, String session, JSONArray items) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(session), items.toString()).apply();
    }

    private static String key(String session) {
        return "group_" + NameNormalizer.nameKey(session);
    }
}
