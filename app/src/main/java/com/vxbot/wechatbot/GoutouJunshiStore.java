package com.vxbot.wechatbot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Private, on-device data for the integrated Goutou Junshi features. */
public final class GoutouJunshiStore {
    private static final Object LOCK = new Object();
    private static final int MAX_HISTORY = 300;
    private static final int MAX_NOTES = 500;

    private GoutouJunshiStore() { }

    public static final class Profile {
        public String id = "";
        public String session = "";
        public String name = "";
        public String aliases = "";
        public String relationship = "";
        public String stage = "未填写";
        public String goal = "自然接话";
        public String background = "";
    }

    public static final class Note {
        public String id = "";
        public String title = "";
        public String content = "";
        public String tags = "";
        public boolean alwaysOn;
        public boolean enabled = true;
    }

    public static final class TrendCandle {
        public String date = "";
        public int open;
        public int high;
        public int low;
        public int close;
    }

    public static void recordInbound(Context context, WxMessage message) {
        if (context == null || message == null || message.text.isEmpty()) return;
        appendHistory(context, message.sessionName, message.senderName, "other", message.text, message.postTime);
    }

    public static void recordOutbound(Context context, WxMessage message, String text) {
        if (context == null || message == null || text == null || text.trim().isEmpty()) return;
        appendHistory(context, message.sessionName, message.senderName, "me", text.trim(), System.currentTimeMillis());
    }

    private static void appendHistory(Context context, String session, String sender, String side, String text, long time) {
        String id = conversationId(session, sender);
        synchronized (LOCK) {
            JSONArray history = readArray(file(context, "history", id + ".json"));
            if ("other".equals(side) && history.length() > 0) {
                JSONObject last = history.optJSONObject(history.length() - 1);
                if (last != null && last.optLong("time") == time
                        && text.equals(last.optString("text"))
                        && sender.equals(last.optString("sender"))) return;
            }
            history.put(object("side", side, "sender", safe(sender),
                    "text", text.length() > 1200 ? text.substring(0, 1200) : text, "time", time));
            while (history.length() > MAX_HISTORY) history.remove(0);
            writeJson(file(context, "history", id + ".json"), history);
        }
    }

    public static JSONObject context(Context context, String session, String sender, String currentText) {
        return context(context, session, sender, currentText, true);
    }

    public static JSONObject context(Context context, String session, String sender, String currentText, boolean includeHistory) {
        synchronized (LOCK) {
            JSONArray history = includeHistory
                    ? readArray(file(context, "history", conversationId(session, sender) + ".json")) : new JSONArray();
            JSONArray recent = new JSONArray();
            int start = Math.max(0, history.length() - 100);
            for (int i = start; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row != null) recent.put(object("speaker", row.optString("side"),
                        "sender", row.optString("sender"), "text", row.optString("text"), "time", row.optLong("time")));
            }
            Profile profile = findProfileLocked(context, session, sender);
            JSONArray noteRows = matchingNotesLocked(context, session, sender, currentText, history);
            JSONObject result = object("session", safe(session), "sender", safe(sender), "history", recent, "knowledge", noteRows);
            if (profile != null) {
                safePut(result, "contact", object("name", profile.name, "relationship", profile.relationship,
                        "stage", profile.stage, "goal", profile.goal, "background", profile.background, "aliases", profile.aliases));
            }
            return result;
        }
    }

    public static String saveAnalysis(Context context, String session, String sender, JSONObject analysis) {
        synchronized (LOCK) {
            String id = conversationId(session, sender);
            writeJson(file(context, "analysis", id + ".json"), analysis);
            return id;
        }
    }

    public static JSONObject latestAnalysis(Context context, String session, String sender) {
        synchronized (LOCK) {
            return readObject(file(context, "analysis", conversationId(session, sender) + ".json"));
        }
    }

    public static List<JSONObject> recentAnalyses(Context context) {
        synchronized (LOCK) {
            ArrayList<JSONObject> result = new ArrayList<>();
            File dir = new File(new File(context.getFilesDir(), "goutoujunshi"), "analysis");
            File[] files = dir == null ? null : dir.listFiles((parent, name) -> name.endsWith(".json"));
            if (files != null) for (File item : files) {
                JSONObject row = readObject(item);
                if (row.length() > 0) result.add(row);
            }
            result.sort((left, right) -> Long.compare(right.optLong("createdAt"), left.optLong("createdAt")));
            if (result.size() > 30) return new ArrayList<>(result.subList(0, 30));
            return result;
        }
    }

    public static List<Profile> profiles(Context context) {
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "", "profiles.json"));
            ArrayList<Profile> result = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) result.add(profileFrom(row));
            }
            return result;
        }
    }

    public static void saveProfile(Context context, Profile profile) {
        if (context == null || profile == null || profile.name.trim().isEmpty()) return;
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "", "profiles.json"));
            if (profile.id.isEmpty()) profile.id = sha256(profile.session + "\u0000" + profile.name);
            JSONObject saved = object("id", profile.id, "session", profile.session.trim(), "name", profile.name.trim(),
                    "aliases", profile.aliases.trim(), "relationship", profile.relationship.trim(), "stage", profile.stage.trim(),
                    "goal", profile.goal.trim(), "background", profile.background.trim());
            upsert(rows, "id", profile.id, saved);
            writeJson(file(context, "", "profiles.json"), rows);
        }
    }

    public static void deleteProfile(Context context, String id) {
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "", "profiles.json"));
            remove(rows, "id", id);
            writeJson(file(context, "", "profiles.json"), rows);
        }
    }

    public static List<Note> notes(Context context) {
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "", "notes.json"));
            ArrayList<Note> result = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) result.add(noteFrom(row));
            }
            return result;
        }
    }

    public static void saveNote(Context context, Note note) {
        if (context == null || note == null || note.title.trim().isEmpty() || note.content.trim().isEmpty()) return;
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "", "notes.json"));
            if (note.id.isEmpty()) note.id = sha256(note.title + "\u0000" + System.currentTimeMillis());
            JSONObject saved = object("id", note.id, "title", note.title.trim(), "content", note.content.trim(),
                    "tags", note.tags.trim(), "alwaysOn", note.alwaysOn, "enabled", note.enabled,
                    "updatedAt", System.currentTimeMillis());
            upsert(rows, "id", note.id, saved);
            while (rows.length() > MAX_NOTES) rows.remove(0);
            writeJson(file(context, "", "notes.json"), rows);
        }
    }

    public static void deleteNote(Context context, String id) {
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "", "notes.json"));
            remove(rows, "id", id);
            writeJson(file(context, "", "notes.json"), rows);
        }
    }

    public static void saveTrend(Context context, String name, List<TrendCandle> candles) {
        if (context == null || candles == null || candles.isEmpty()) return;
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "trends", "saved.json"));
            JSONArray values = new JSONArray();
            for (TrendCandle candle : candles) values.put(object("date", candle.date, "open", candle.open,
                    "high", candle.high, "low", candle.low, "close", candle.close));
            JSONObject item = object("name", safe(name), "candles", values, "updatedAt", System.currentTimeMillis());
            upsert(rows, "name", name, item);
            writeJson(file(context, "trends", "saved.json"), rows);
        }
    }

    public static List<TrendCandle> trend(Context context, String name) {
        synchronized (LOCK) {
            JSONArray rows = readArray(file(context, "trends", "saved.json"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item == null || !name.equals(item.optString("name"))) continue;
                JSONArray values = item.optJSONArray("candles");
                ArrayList<TrendCandle> result = new ArrayList<>();
                if (values != null) for (int j = 0; j < values.length(); j++) {
                    JSONObject row = values.optJSONObject(j);
                    if (row == null) continue;
                    TrendCandle candle = new TrendCandle();
                    candle.date = row.optString("date"); candle.open = row.optInt("open");
                    candle.high = row.optInt("high"); candle.low = row.optInt("low"); candle.close = row.optInt("close");
                    result.add(candle);
                }
                return result;
            }
            return new ArrayList<>();
        }
    }

    public static boolean isExplicitBoundary(String text) {
        String value = safe(text);
        return containsAny(value, "不要再联系我", "别再联系我", "不要再给我发消息", "别再给我发消息",
                "不要再找我", "别再找我", "请不要联系我");
    }

    public static JSONObject boundaryAnalysis() {
        return boundaryAnalysis("", "");
    }

    public static JSONObject boundaryAnalysis(String session, String sender) {
        return object("strategy", "收线", "intent", "对方明确要求停止联系", "confidence", 1.0,
                "facts", new JSONArray().put("对方明确表达了停止联系的边界"), "unknowns", new JSONArray(),
                "danger", "明确边界", "danger_level", 6, "danger_confidence", 1.0,
                "literal_question", JSONObject.NULL, "she_needs", "无需回复，尊重停止联系要求",
                "tension_resolved", false, "reply_now", false, "best_action", "收线",
                "support", "先尊重对方明确表达的边界。", "hypotheses", new JSONArray(),
                "next_step", "停止推进，不再发送回复",
                "stop_condition", "对方主动重新开启对话后再评估",
                "detail", "依据对方可见的原话识别到明确停止联系要求。本轮未发送回复。",
                "candidates", new JSONArray(), "selected", JSONObject.NULL,
                "session", safe(session), "sender", safe(sender), "createdAt", System.currentTimeMillis());
    }

    public static String report(JSONObject analysis) {
        if (analysis == null || analysis.length() == 0) return "当前还没有狗头军师分析记录。";
        StringBuilder out = new StringBuilder("狗头军师自动分析");
        append(out, "策略", analysis.optString("strategy"));
        append(out, "可能意图", analysis.optString("intent"));
        append(out, "判断把握", analysis.isNull("confidence") ? "未提供" : String.format(Locale.ROOT, "%.0f%%", analysis.optDouble("confidence") * 100));
        append(out, "风险", analysis.optString("danger"));
        append(out, "风险等级", analysis.has("danger_level") ? String.valueOf(analysis.optInt("danger_level")) + "/6" : "未提供");
        append(out, "字面提问", analysis.isNull("literal_question") ? "未知" : String.valueOf(analysis.optBoolean("literal_question")));
        append(out, "对方当前需要", analysis.optString("she_needs"));
        append(out, "紧张是否缓解", analysis.isNull("tension_resolved") ? "未知" : String.valueOf(analysis.optBoolean("tension_resolved")));
        append(out, "现在是否回复", analysis.optString("reply_now"));
        append(out, "建议动作", analysis.optString("best_action"));
        append(out, "已知事实", join(analysis.optJSONArray("facts")));
        append(out, "合理推测", join(analysis.optJSONArray("hypotheses")));
        append(out, "仍未知", join(analysis.optJSONArray("unknowns")));
        append(out, "照顾自己的感受", analysis.optString("support"));
        append(out, "下一步", analysis.optString("next_step"));
        append(out, "停止条件", analysis.optString("stop_condition"));
        append(out, "详细分析", analysis.optString("detail"));
        JSONArray candidates = analysis.optJSONArray("candidates");
        if (candidates != null && candidates.length() > 0) {
            out.append("\n候选回复（已自动排序）：");
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject row = candidates.optJSONObject(i);
                if (row != null) out.append("\n").append(i + 1).append(". ").append(row.optString("text"))
                        .append("\n   相对匹配度：").append(row.optInt("score")).append("（不是成功率）")
                        .append("\n   排序理由：").append(row.optString("ranking_reason"))
                        .append("\n   候选理由：").append(row.optString("reason"))
                        .append("\n   可能代价：").append(row.optString("tradeoff"));
            }
        }
        append(out, "自动采用", analysis.optString("selected"));
        return out.toString();
    }

    public static String latestSummary(Context context) {
        List<JSONObject> rows = recentAnalyses(context);
        if (rows.isEmpty()) return "狗头军师操作流\n等待普通聊天消息\n联系人资料可选；未配置联系人也会进入自动策略。";
        JSONObject row = rows.get(0);
        StringBuilder out = new StringBuilder("狗头军师 · 自动操作流\n");
        String session = row.optString("session");
        String sender = row.optString("sender");
        if (!session.isEmpty() || !sender.isEmpty()) out.append(session).append(" · ").append(sender).append('\n');
        out.append("策略选择：").append(row.optString("strategy", "等待分析"));
        if (!row.isNull("confidence") && row.has("confidence")) {
            out.append("（把握 ").append(Math.round(row.optDouble("confidence") * 100)).append("%）");
        }
        appendSummary(out, "建议动作", row.optString("best_action"));
        appendSummary(out, "对方意图", row.optString("intent"));
        boolean replyNow = row.optBoolean("reply_now", true);
        JSONArray candidates = row.optJSONArray("candidates");
        if (!replyNow) {
            out.append("\n流程结果：判断无需回复，已停止发送");
        } else if (candidates == null || candidates.length() == 0) {
            out.append("\n流程结果：策略分析已保存，候选或排序尚未完成");
        } else {
            out.append("\n流程：策略判断 → 生成候选 → 排序 → 自动采用");
            String selected = row.optString("selected");
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.optJSONObject(i);
                if (candidate == null) continue;
                String text = candidate.optString("text");
                out.append("\n").append(i + 1).append(i == 0 ? " ★ " : "   ").append(text);
                if (text.equals(selected)) out.append("  ← 自动采用");
                String reason = candidate.optString("reason");
                if (!reason.isEmpty()) out.append("\n   理由：").append(reason);
            }
        }
        return out.toString();
    }

    private static void appendSummary(StringBuilder out, String label, String value) {
        if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
            out.append('\n').append(label).append("：").append(value.trim());
        }
    }

    public static JSONArray historyForTrend(Context context, String session, String sender) {
        synchronized (LOCK) {
            return readArray(file(context, "history", conversationId(session, sender) + ".json"));
        }
    }

    private static JSONArray matchingNotesLocked(Context context, String session, String sender, String current, JSONArray history) {
        JSONArray source = readArray(file(context, "", "notes.json"));
        JSONArray result = new JSONArray();
        StringBuilder haystack = new StringBuilder(safe(session)).append(' ').append(safe(sender)).append(' ').append(safe(current));
        for (int i = Math.max(0, history.length() - 6); i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row != null) haystack.append(' ').append(row.optString("text"));
        }
        String target = normalize(haystack.toString());
        int hits = 0;
        for (int i = source.length() - 1; i >= 0; i--) {
            JSONObject row = source.optJSONObject(i);
            if (row == null || !row.optBoolean("enabled", true)) continue;
            boolean always = row.optBoolean("alwaysOn");
            boolean match = always;
            if (!match) {
                String terms = row.optString("title") + " " + row.optString("tags");
                for (String token : terms.split("[,，;；\\s]+")) {
                    String key = normalize(token);
                    if (!key.isEmpty() && target.contains(key)) { match = true; break; }
                }
            }
            if (!match || (!always && hits >= 5)) continue;
            String content = row.optString("content");
            if (content.length() > 1400) content = content.substring(0, 1400);
            result.put(object("title", row.optString("title"), "content", content, "alwaysOn", always));
            if (!always) hits++;
        }
        return result;
    }

    private static Profile findProfileLocked(Context context, String session, String sender) {
        String target = normalize(sender);
        JSONArray rows = readArray(file(context, "", "profiles.json"));
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String profileSession = normalize(row.optString("session"));
            if (!profileSession.isEmpty() && !profileSession.equals(normalize(session))) continue;
            if (normalize(row.optString("name")).equals(target)) return profileFrom(row);
            for (String alias : row.optString("aliases").split("[,，;；\\n]+")) {
                if (!target.isEmpty() && normalize(alias).equals(target)) return profileFrom(row);
            }
        }
        return null;
    }

    private static Profile profileFrom(JSONObject row) {
        Profile p = new Profile();
        p.id = row.optString("id"); p.session = row.optString("session"); p.name = row.optString("name");
        p.aliases = row.optString("aliases"); p.relationship = row.optString("relationship");
        p.stage = row.optString("stage", "未填写"); p.goal = row.optString("goal", "自然接话");
        p.background = row.optString("background"); return p;
    }

    private static Note noteFrom(JSONObject row) {
        Note n = new Note(); n.id = row.optString("id"); n.title = row.optString("title");
        n.content = row.optString("content"); n.tags = row.optString("tags");
        n.alwaysOn = row.optBoolean("alwaysOn"); n.enabled = row.optBoolean("enabled", true); return n;
    }

    private static void upsert(JSONArray rows, String key, String value, JSONObject replacement) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject old = rows.optJSONObject(i);
            if (old != null && value.equals(old.optString(key))) { safeArrayPut(rows, i, replacement); return; }
        }
        rows.put(replacement);
    }

    private static void remove(JSONArray rows, String key, String value) {
        JSONArray next = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && !value.equals(row.optString(key))) next.put(row);
        }
        for (int i = rows.length() - 1; i >= 0; i--) rows.remove(i);
        for (int i = 0; i < next.length(); i++) rows.put(next.opt(i));
    }

    private static File file(Context context, String folder, String name) {
        File root = new File(context.getFilesDir(), "goutoujunshi");
        File dir = folder == null || folder.isEmpty() ? root : new File(root, folder);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private static JSONArray readArray(File file) {
        try { return new JSONArray(readText(file)); } catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject readObject(File file) {
        try { return new JSONObject(readText(file)); } catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) safePut(result, String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }

    private static void safePut(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception e) { throw new IllegalStateException("JSON 写入失败", e); }
    }

    private static void safeArrayPut(JSONArray target, int index, Object value) {
        try { target.put(index, value); } catch (Exception e) { throw new IllegalStateException("JSON 数组写入失败", e); }
    }

    private static String readText(File file) throws Exception {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 4L * 1024L * 1024L)];
            int read = in.read(bytes); return read <= 0 ? "" : new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(File file, Object json) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            if (!temp.renameTo(file)) throw new IllegalStateException("临时文件无法提交");
        } catch (Exception e) {
            // Keep the previous on-disk value when a write fails.
            // The calling flow will still have the model result in memory.
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static String conversationId(String session, String sender) { return sha256(safe(session) + "\u0000" + safe(sender)); }
    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) { return safe(value).replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”（）【】《》]+", "").toLowerCase(Locale.ROOT); }
    private static boolean containsAny(String value, String... needles) { for (String needle : needles) if (value.contains(needle)) return true; return false; }
    private static String join(JSONArray array) {
        if (array == null || array.length() == 0) return "无";
        StringBuilder out = new StringBuilder(); for (int i = 0; i < array.length(); i++) { if (out.length() > 0) out.append("；"); out.append(array.optString(i)); }
        return out.toString();
    }
    private static void append(StringBuilder out, String label, String text) { if (text != null && !text.trim().isEmpty() && !"null".equals(text)) out.append("\n").append(label).append("：").append(text.trim()); }
}
