package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PersonaStore {
    private static final String PREFS = "persona_store";
    private static final String KEY_PREFIX = "day_";
    private static final int KEEP_DAYS = 8;
    private static final int MAX_MEMBERS_PER_DAY = 80;
    private static final int MAX_MESSAGES_PER_MEMBER = 40;
    private static final int MAX_TEXT_CHARS = 160;
    private static final int MAX_REPORT_MEMBERS = 5;
    private static final int MAX_MODEL_MEMBERS = 8;
    private static final int MAX_MODEL_MESSAGES_PER_MEMBER = 8;
    private static final int MAX_MODEL_MESSAGE_CHARS = 90;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String[] STOP_WORDS = {
            "这个", "那个", "就是", "不是", "可以", "一下", "今天", "昨天", "明天", "现在",
            "已经", "没有", "还是", "然后", "感觉", "时候", "自己", "大家", "群里", "机器人",
            "什么", "怎么", "为什么", "哈哈", "哈哈哈", "图片", "视频", "链接", "语音",
            "收到", "看看", "有人", "一起", "应该", "可能", "因为", "所以", "但是", "不过"
    };

    public synchronized void remember(Context context, WxMessage message) {
        if (context == null || message == null || message.sessionName.isEmpty()
                || message.senderName.isEmpty() || message.text.isEmpty()) {
            return;
        }
        long when = message.postTime > 0 ? message.postTime : System.currentTimeMillis();
        String dateKey = dateKey(when);
        String prefKey = prefKey(message.sessionName, dateKey);
        SharedPreferences prefs = prefs(context);
        JSONObject day = readDay(prefs, prefKey, message.sessionName, dateLabel(when));
        try {
            JSONArray members = day.optJSONArray("members");
            if (members == null) {
                members = new JSONArray();
                day.put("members", members);
            }
            JSONObject member = findMember(members, message.senderName);
            if (member == null) {
                if (members.length() >= MAX_MEMBERS_PER_DAY) {
                    BotLog.w(context, "persona.store.full", "群成员画像当日成员过多，跳过新增 group="
                            + message.sessionName + " sender=" + message.senderName);
                    return;
                }
                member = new JSONObject()
                        .put("name", message.senderName)
                        .put("key", NameNormalizer.nameKey(message.senderName))
                        .put("count", 0)
                        .put("chars", 0)
                        .put("firstAt", when)
                        .put("lastAt", when)
                        .put("messages", new JSONArray());
                members.put(member);
            }
            String text = compactMessage(message.text);
            if (text.isEmpty()) {
                return;
            }
            member.put("name", message.senderName);
            member.put("count", member.optInt("count", 0) + 1);
            member.put("chars", member.optInt("chars", 0) + text.length());
            if (member.optLong("firstAt", 0L) <= 0 || when < member.optLong("firstAt", when)) {
                member.put("firstAt", when);
            }
            if (when > member.optLong("lastAt", 0L)) {
                member.put("lastAt", when);
            }
            JSONArray messages = member.optJSONArray("messages");
            if (messages == null) {
                messages = new JSONArray();
                member.put("messages", messages);
            }
            messages.put(new JSONObject().put("t", when).put("text", text));
            trimMessages(messages);
            day.put("updatedAt", System.currentTimeMillis());
            prefs.edit().putString(prefKey, day.toString()).apply();
            cleanupOldDays(prefs, System.currentTimeMillis());
        } catch (JSONException e) {
            BotLog.w(context, "persona.store.error", "画像写入失败: " + e.getMessage());
        }
    }

    public synchronized String buildReport(Context context, String sessionName, String command, long now) {
        if (context == null || sessionName == null || sessionName.trim().isEmpty()) {
            return "人物画像没有拿到当前群名。";
        }
        int offset = targetDayOffset(command);
        long targetTime = now + offset * DAY_MS;
        String prefKey = prefKey(sessionName, dateKey(targetTime));
        JSONObject day = readDayOrNull(prefs(context), prefKey);
        if (day == null) {
            return "当前群 " + dateLabel(targetTime) + " 还没有可分析的群成员消息记录。先让机器人运行一阵再看。";
        }
        List<MemberStats> members = readMembers(day);
        if (members.isEmpty()) {
            return "当前群 " + dateLabel(targetTime) + " 没有可分析的群成员发言。";
        }
        Collections.sort(members, (a, b) -> {
            int count = Integer.compare(b.count, a.count);
            return count != 0 ? count : Integer.compare(b.chars, a.chars);
        });
        int total = 0;
        List<String> allMessages = new ArrayList<>();
        for (MemberStats member : members) {
            total += member.count;
            allMessages.addAll(member.messages);
        }
        StringBuilder out = new StringBuilder();
        out.append(offset == 0 ? "今日" : (offset == -2 ? "前日" : "昨日"))
                .append("人物画像：").append(sessionName.trim()).append('\n');
        MemberStats talker = members.get(0);
        int percent = total <= 0 ? 0 : Math.round(talker.count * 100f / total);
        out.append("话痨：").append(talker.name)
                .append("，").append(talker.count).append("条，占比").append(percent).append("%。\n");
        out.append("活跃排行：").append(rankLine(members, total)).append('\n');
        String keywords = joinKeywords(extractKeywords(allMessages, 8));
        if (!keywords.isEmpty()) {
            out.append("重点：").append(keywords).append('\n');
        }
        out.append("成员画像：");
        int limit = Math.min(MAX_REPORT_MEMBERS, members.size());
        for (int i = 0; i < limit; i++) {
            MemberStats member = members.get(i);
            out.append('\n').append(i + 1).append(". ").append(member.name)
                    .append("：").append(member.count).append("条");
            if (member.firstAt > 0 && member.lastAt > 0) {
                out.append("，").append(timeLabel(member.firstAt)).append("-").append(timeLabel(member.lastAt));
            }
            List<String> memberKeywords = extractKeywords(member.messages, 4);
            if (!memberKeywords.isEmpty()) {
                out.append("，常聊 ").append(joinKeywords(memberKeywords));
            }
            String sample = representativeMessage(member.messages);
            if (!sample.isEmpty()) {
                out.append("；代表：").append(quote(sample));
            }
        }
        return out.toString();
    }

    public synchronized String buildModelContext(Context context, String sessionName, String command, long now) {
        if (context == null || sessionName == null || sessionName.trim().isEmpty()) {
            return "";
        }
        int offset = targetDayOffset(command);
        long targetTime = now + offset * DAY_MS;
        JSONObject day = readDayOrNull(prefs(context), prefKey(sessionName, dateKey(targetTime)));
        if (day == null) {
            return "";
        }
        List<MemberStats> members = readMembers(day);
        if (members.isEmpty()) {
            return "";
        }
        Collections.sort(members, (a, b) -> {
            int count = Integer.compare(b.count, a.count);
            return count != 0 ? count : Integer.compare(b.chars, a.chars);
        });
        int total = 0;
        for (MemberStats member : members) {
            total += member.count;
        }
        StringBuilder out = new StringBuilder();
        out.append("群聊: ").append(sessionName.trim()).append('\n');
        out.append("日期: ").append(dateLabel(targetTime)).append(" / ").append(dayTitle(offset)).append('\n');
        out.append("用户指令: ").append(command == null ? "" : command.trim()).append('\n');
        out.append("总消息数: ").append(total).append('\n');
        out.append("活跃成员数: ").append(members.size()).append('\n');
        out.append("请基于以下按成员分组的发言样本分析人物性格、昨天/今天干了啥说了啥、群聊重点。不要编造样本外事实。\n");
        int memberLimit = Math.min(MAX_MODEL_MEMBERS, members.size());
        for (int i = 0; i < memberLimit; i++) {
            MemberStats member = members.get(i);
            int percent = total <= 0 ? 0 : Math.round(member.count * 100f / total);
            out.append("\n成员 ").append(i + 1).append(": ").append(member.name).append('\n');
            out.append("发言数: ").append(member.count)
                    .append(" / 占比: ").append(percent).append("%")
                    .append(" / 字数: ").append(member.chars).append('\n');
            if (member.firstAt > 0 && member.lastAt > 0) {
                out.append("活跃时段: ").append(timeLabel(member.firstAt)).append("-").append(timeLabel(member.lastAt)).append('\n');
            }
            List<String> keywords = extractKeywords(member.messages, 6);
            if (!keywords.isEmpty()) {
                out.append("本地关键词: ").append(joinKeywords(keywords)).append('\n');
            }
            out.append("发言样本:\n");
            for (String sample : modelSamples(member.messages)) {
                out.append("- ").append(limitForModel(sample)).append('\n');
            }
        }
        return out.toString();
    }

    private static int targetDayOffset(String command) {
        String value = NameNormalizer.nameKey(command);
        if (value.contains("前天") || value.contains("前日")) {
            return -2;
        }
        if (value.contains("今天") || value.contains("今日")) {
            return 0;
        }
        return -1;
    }

    private static JSONObject readDay(SharedPreferences prefs, String key, String sessionName, String dateLabel) {
        JSONObject day = readDayOrNull(prefs, key);
        if (day != null) {
            return day;
        }
        try {
            return new JSONObject()
                    .put("sessionName", sessionName)
                    .put("date", dateLabel)
                    .put("updatedAt", System.currentTimeMillis())
                    .put("members", new JSONArray());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject readDayOrNull(SharedPreferences prefs, String key) {
        String raw = prefs.getString(key, "");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject findMember(JSONArray members, String senderName) {
        String key = NameNormalizer.nameKey(senderName);
        for (int i = 0; i < members.length(); i++) {
            JSONObject item = members.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String existing = item.optString("key", "");
            if (existing.isEmpty()) {
                existing = NameNormalizer.nameKey(item.optString("name", ""));
            }
            if (!key.isEmpty() && key.equals(existing)) {
                return item;
            }
        }
        return null;
    }

    private static void trimMessages(JSONArray messages) {
        while (messages.length() > MAX_MESSAGES_PER_MEMBER) {
            messages.remove(0);
        }
    }

    private static List<MemberStats> readMembers(JSONObject day) {
        JSONArray array = day.optJSONArray("members");
        List<MemberStats> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            MemberStats member = new MemberStats();
            member.name = item.optString("name", "").trim();
            member.count = item.optInt("count", 0);
            member.chars = item.optInt("chars", 0);
            member.firstAt = item.optLong("firstAt", 0L);
            member.lastAt = item.optLong("lastAt", 0L);
            JSONArray messages = item.optJSONArray("messages");
            if (messages != null) {
                for (int j = 0; j < messages.length(); j++) {
                    JSONObject msg = messages.optJSONObject(j);
                    if (msg != null) {
                        String text = msg.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            member.messages.add(text);
                        }
                    }
                }
            }
            if (!member.name.isEmpty() && member.count > 0) {
                out.add(member);
            }
        }
        return out;
    }

    private static String rankLine(List<MemberStats> members, int total) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, members.size());
        for (int i = 0; i < limit; i++) {
            MemberStats member = members.get(i);
            if (i > 0) {
                sb.append("；");
            }
            int percent = total <= 0 ? 0 : Math.round(member.count * 100f / total);
            sb.append(i + 1).append(".").append(member.name).append(" ")
                    .append(member.count).append("条/").append(percent).append("%");
        }
        return sb.toString();
    }

    private static String dayTitle(int offset) {
        if (offset == 0) {
            return "今日";
        }
        if (offset == -2) {
            return "前日";
        }
        return "昨日";
    }

    private static List<String> extractKeywords(List<String> messages, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (String message : messages) {
            for (String token : tokensOf(message)) {
                counts.put(token, counts.getOrDefault(token, 0) + 1);
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int count = Integer.compare(b.getValue(), a.getValue());
            return count != 0 ? count : Integer.compare(b.getKey().length(), a.getKey().length());
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (entry.getValue() <= 1 && out.size() >= 3) {
                continue;
            }
            out.add(entry.getKey());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static List<String> tokensOf(String text) {
        String value = text == null ? "" : text
                .replaceAll("https?://\\S+", " ")
                .replaceAll("@[^\\s，,。！？!?；;：:]+", " ")
                .replaceAll("[\\p{Punct}，。！？；：、（）【】《》“”‘’]", " ");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int type = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int nextType = charType(ch);
            if (nextType == 0) {
                flushToken(out, current, type);
                type = 0;
                continue;
            }
            if (type != 0 && nextType != type) {
                flushToken(out, current, type);
                current.setLength(0);
            }
            current.append(ch);
            type = nextType;
        }
        flushToken(out, current, type);
        return out;
    }

    private static void flushToken(List<String> out, StringBuilder current, int type) {
        if (current.length() == 0) {
            return;
        }
        String token = current.toString().trim();
        current.setLength(0);
        if (token.length() < 2) {
            return;
        }
        if (type == 1 && token.length() > 6) {
            addChineseChunks(out, token);
            return;
        }
        if (!isStopWord(token)) {
            out.add(token.toLowerCase(Locale.US));
        }
    }

    private static void addChineseChunks(List<String> out, String token) {
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        int max = Math.min(4, token.length());
        for (int len = max; len >= 2; len--) {
            for (int i = 0; i + len <= token.length(); i++) {
                String part = token.substring(i, i + len);
                if (!isStopWord(part)) {
                    seen.put(part, true);
                }
            }
            if (seen.size() >= 4) {
                break;
            }
        }
        out.addAll(seen.keySet());
    }

    private static int charType(char ch) {
        if (isChinese(ch)) {
            return 1;
        }
        if (Character.isLetterOrDigit(ch)) {
            return 2;
        }
        return 0;
    }

    private static boolean isChinese(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }

    private static boolean isStopWord(String token) {
        for (String stop : STOP_WORDS) {
            if (stop.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String joinKeywords(List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        for (String keyword : keywords) {
            if (keyword == null || keyword.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(keyword.trim());
        }
        return sb.toString();
    }

    private static String representativeMessage(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<String> candidates = new ArrayList<>();
        for (String message : messages) {
            String text = compactMessage(message);
            if (text.length() >= 4 && !MessageRouter.isMediaOnlyMessage(text)) {
                candidates.add(text);
            }
        }
        if (candidates.isEmpty()) {
            return compactMessage(messages.get(messages.size() - 1));
        }
        return Collections.max(candidates, Comparator.comparingInt(String::length));
    }

    private static List<String> modelSamples(List<String> messages) {
        List<String> out = new ArrayList<>();
        String representative = representativeMessage(messages);
        if (!representative.isEmpty()) {
            out.add(representative);
        }
        if (messages == null || messages.isEmpty()) {
            return out;
        }
        for (int i = messages.size() - 1; i >= 0 && out.size() < MAX_MODEL_MESSAGES_PER_MEMBER; i--) {
            String text = compactMessage(messages.get(i));
            if (text.isEmpty() || out.contains(text)) {
                continue;
            }
            out.add(text);
        }
        return out;
    }

    private static String limitForModel(String text) {
        String value = compactMessage(text);
        if (value.length() > MAX_MODEL_MESSAGE_CHARS) {
            value = value.substring(0, MAX_MODEL_MESSAGE_CHARS) + "...";
        }
        return value;
    }

    private static String quote(String text) {
        String value = compactMessage(text);
        if (value.length() > 34) {
            value = value.substring(0, 34) + "...";
        }
        return "“" + value + "”";
    }

    private static String compactMessage(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (value.length() > MAX_TEXT_CHARS) {
            value = value.substring(0, MAX_TEXT_CHARS);
        }
        return value;
    }

    private static void cleanupOldDays(SharedPreferences prefs, long now) {
        String minDate = dateKey(now - KEEP_DAYS * DAY_MS);
        SharedPreferences.Editor editor = null;
        Map<String, ?> all = prefs.getAll();
        for (String key : all.keySet()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            String date = key.substring(key.lastIndexOf('_') + 1);
            if (date.compareTo(minDate) < 0) {
                if (editor == null) {
                    editor = prefs.edit();
                }
                editor.remove(key);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String prefKey(String sessionName, String dateKey) {
        String normalized = NameNormalizer.nameKey(sessionName);
        return KEY_PREFIX + Integer.toHexString(normalized.hashCode()) + "_" + dateKey;
    }

    private static String dateKey(long time) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(time));
    }

    private static String dateLabel(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(time));
    }

    private static String timeLabel(long time) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(time));
    }

    private static final class MemberStats {
        String name = "";
        int count;
        int chars;
        long firstAt;
        long lastAt;
        final List<String> messages = new ArrayList<>();
    }
}
