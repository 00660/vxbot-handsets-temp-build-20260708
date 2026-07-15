package com.vxbot.wechatbot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ChatClient {
    public String requestReply(Context context, BotConfig config, WxMessage message, List<String> history, MessageRouter.Route route) throws Exception {
        if (route.kind == MessageRouter.Kind.CODEX) {
            if (config.enableHappyDirectCodex) {
                try {
                    String reply = new HappyDirectClient().requestCodex(context, config,
                            buildHappyCodexPrompt(config, message, history));
                    reply = cleanReplyPrefix(reply, config);
                    if (!isBlank(reply)) {
                        return reply.trim();
                    }
                    throw new IllegalStateException("Happy 直连返回空内容");
                } catch (Exception e) {
                    BotLog.w(context, "codex.happy_direct.fail", "Happy 直连请求失败: " + e.getMessage());
                    throw new IllegalStateException("Happy 直连请求失败: " + e.getMessage(), e);
                }
            }
            if (isBlank(config.happyCodexEndpoint)) {
                throw new IllegalStateException("Happy Codex 桥接接口未配置");
            }
            try {
                String reply = requestHappyCodex(context, config, message, history);
                reply = cleanReplyPrefix(reply, config);
                if (!isBlank(reply)) {
                    return reply.trim();
                }
                throw new IllegalStateException("Happy Codex 返回空内容");
            } catch (Exception e) {
                BotLog.w(context, "codex.happy.fail", "Happy Codex 请求失败: " + e.getMessage());
                throw new IllegalStateException("Happy Codex 请求失败: " + e.getMessage(), e);
            }
        }
        if (config.chatEndpoint == null || config.chatEndpoint.trim().isEmpty()) {
            throw new IllegalStateException("chatEndpoint 未配置");
        }
        Exception last = null;
        String toolContext = RealtimeTools.buildContext(context, message.text, route.kind);
        if (shouldReplyWithToolContext(route.kind, toolContext, message.text)) {
            BotLog.i(context, "chat.tool.direct", "实时工具直出 mode=" + route.kind
                    + " bytes=" + toolContext.getBytes(StandardCharsets.UTF_8).length);
            return stripToolLabels(formatToolReply(route.kind, toolContext));
        }
        for (int i = 0; i < 2; i++) {
            try {
                String reply = requestOnce(context, config, message, history, route, toolContext);
                reply = cleanReplyPrefix(reply, config);
                if (reply != null && !reply.trim().isEmpty()) {
                    return reply.trim();
                }
                last = new IllegalStateException("上游返回空内容");
                BotLog.w(context, "chat.empty", "第 " + (i + 1) + " 次返回空，准备重试");
            } catch (Exception e) {
                last = e;
                BotLog.w(context, "chat.retry", "第 " + (i + 1) + " 次请求失败: " + e.getMessage());
            }
        }
        throw last == null ? new IllegalStateException("请求失败") : last;
    }

    private String requestHappyCodex(Context context, BotConfig config, WxMessage message, List<String> history) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("message", buildHappyCodexPrompt(config, message, history));
        payload.put("model", config.model);
        payload.put("permissionMode", "yolo");
        payload.put("timeoutMs", Math.max(10000, config.replyTimeoutMs));
        payload.put("appendSystemPrompt",
                "你通过 Happy 接收微信群机器人转发的 Codex 请求。"
                        + "如果请求涉及当前容器工作区代码，直接完成必要修改和验证；"
                        + "最终回复要适合发回微信群，简洁说明做了什么、结果如何、有没有未完成项。");
        BotLog.i(context, "codex.happy.request", "请求 Happy Codex " + config.happyCodexEndpoint
                + " bytes=" + payload.toString().getBytes(StandardCharsets.UTF_8).length);
        HttpURLConnection conn = (HttpURLConnection) new URL(config.happyCodexEndpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(Math.max(10000, config.replyTimeoutMs));
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + trimBody(body));
        }
        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("ok", false)) {
            throw new IllegalStateException(json.optString("error", "Happy Codex 返回失败"));
        }
        return json.optString("reply", "");
    }

    private static String buildHappyCodexPrompt(BotConfig config, WxMessage message, List<String> history) {
        StringBuilder prompt = new StringBuilder(2048);
        prompt.append("微信群 Codex 请求\n");
        prompt.append("当前群聊: ").append(message.sessionName == null ? "" : message.sessionName).append('\n');
        prompt.append("发送人: ").append(message.senderName == null ? "" : message.senderName).append('\n');
        if (history != null && !history.isEmpty()) {
            prompt.append("最近上下文:\n");
            int start = Math.max(0, history.size() - 8);
            for (int i = start; i < history.size(); i++) {
                String line = history.get(i);
                if (!isBlank(line)) {
                    prompt.append("- ").append(historyContent(line)).append('\n');
                }
            }
        }
        prompt.append("用户消息:\n").append(message.text == null ? "" : message.text.trim()).append('\n');
        prompt.append("回复要求: 直接处理请求。需要改代码就改；需要查看文件、运行命令或验证就执行。最终只给可发回微信群的简短结论。");
        String text = prompt.toString();
        return text.length() > 12000 ? text.substring(0, 12000) : text;
    }

    private static boolean shouldReplyWithToolContext(MessageRouter.Kind kind, String toolContext, String userText) {
        if (isBlank(toolContext)) {
            return false;
        }
        if (kind == MessageRouter.Kind.SPORTS && looksLikeSportsAnalysis(userText)) {
            return false;
        }
        return kind == MessageRouter.Kind.NEWS
                || kind == MessageRouter.Kind.FINANCE
                || kind == MessageRouter.Kind.WEATHER
                || kind == MessageRouter.Kind.SPORTS
                || kind == MessageRouter.Kind.UTILITY;
    }

    private static String formatToolReply(MessageRouter.Kind kind, String toolContext) {
        String text = toolContext == null ? "" : toolContext.trim();
        if (text.startsWith("实时工具失败：")) {
            if (kind == MessageRouter.Kind.NEWS) {
                return "热点这会儿没取到，接口在抽风：" + text.substring("实时工具失败：".length()).trim();
            }
            if (kind == MessageRouter.Kind.FINANCE) {
                return "行情这会儿没取到，接口在抽风：" + text.substring("实时工具失败：".length()).trim();
            }
            if (kind == MessageRouter.Kind.WEATHER) {
                return "天气这会儿没取到，接口在抽风：" + text.substring("实时工具失败：".length()).trim();
            }
            if (kind == MessageRouter.Kind.SPORTS) {
                return "赛事这会儿没取到，接口在抽风：" + text.substring("实时工具失败：".length()).trim();
            }
            if (kind == MessageRouter.Kind.UTILITY) {
                return "这个工具算崩了：" + text.substring("实时工具失败：".length()).trim();
            }
        }
        return text;
    }

    private static boolean looksLikeSportsAnalysis(String text) {
        String value = text == null ? "" : text.toLowerCase();
        return value.contains("分析")
                || value.contains("预测")
                || value.contains("怎么看")
                || value.contains("看法")
                || value.contains("谁强")
                || value.contains("谁赢")
                || value.contains("胜率")
                || value.contains("盘口")
                || value.contains("推荐")
                || value.contains("买谁")
                || value.contains("能不能赢");
    }

    public String requestVisionReply(Context context, BotConfig config, WxMessage message, List<String> history, String imageDataUrl) throws Exception {
        Exception last = null;
        for (int i = 0; i < 2; i++) {
            try {
                String reply = requestVisionOnce(config, message, history, imageDataUrl);
                reply = cleanReplyPrefix(reply, config);
                if (reply != null && !reply.trim().isEmpty()) {
                    return reply.trim();
                }
                last = new IllegalStateException("上游返回空内容");
                BotLog.w(context, "vision.empty", "第 " + (i + 1) + " 次视觉返回空，准备重试");
            } catch (Exception e) {
                last = e;
                BotLog.w(context, "vision.retry", "第 " + (i + 1) + " 次视觉请求失败: " + e.getMessage());
            }
        }
        throw last == null ? new IllegalStateException("视觉请求失败") : last;
    }

    public String requestPersonaReport(Context context, BotConfig config, WxMessage message, String personaContext) throws Exception {
        if (config.chatEndpoint == null || config.chatEndpoint.trim().isEmpty()) {
            throw new IllegalStateException("chatEndpoint 未配置");
        }
        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "你是微信群人物画像分析助手。只根据用户提供的群聊分组样本分析，不要编造样本外事实。"
                        + "输出可直接发到微信群里的中文短报告，必须包含：话痨是谁、各主要成员性格/表达风格、"
                        + "他们昨天或今天干了啥说了啥、群聊重点。数据不足就明确说样本不足。"
                        + "不要写内部推理，不要输出 JSON。"));
        messages.put(new JSONObject().put("role", "user").put("content", personaContext));
        payload.put("messages", messages);
        payload.put("temperature", 0.45);
        BotLog.i(context, "persona.analysis.request", "发送人物画像分析请求 group=" + message.sessionName
                + " bytes=" + payload.toString().getBytes(StandardCharsets.UTF_8).length);
        String reply = cleanReplyPrefix(postChat(config, payload), config);
        if (reply == null || reply.trim().isEmpty()) {
            throw new IllegalStateException("人物画像上游返回空内容");
        }
        return reply.trim();
    }

    public String requestProfilePersona(Context context, BotConfig config, WxMessage message,
                                        String targetName, String profileText,
                                        String avatarDataUrl, String profileDataUrl) throws Exception {
        if (config.chatEndpoint == null || config.chatEndpoint.trim().isEmpty()) {
            throw new IllegalStateException("chatEndpoint 未配置");
        }
        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "你是微信人物画像分析助手。根据用户公开展示的头像、昵称、地区和个性签名，"
                        + "输出可直接发到微信群里的中文人物画像。第一张图是放大后的头像，第二张图是完整资料页。"
                        + "必须真正观察头像主体、姿态、配色、画风和选择偏好，并结合昵称、签名之间的呼应或反差。"
                        + "报告包含：第一眼气质、性格底色、社交模式与边界感、反差点或潜台词、相处建议、判断置信度。"
                        + "每个判断都必须紧跟具体依据，禁止只写随和、低调、轻松、温和之类任何头像都能套用的空话。"
                        + "语气可以犀利、有趣、有记忆点，但不能辱骂。控制在 350 至 600 个汉字。"
                        + "只做有依据的倾向性描述，不推断疾病、政治、宗教、性取向、收入等敏感属性，"
                        + "资料不足就明确说明。不要输出内部推理，不要输出 JSON。"
                        + "不要重复输出人物画像标题，不要使用 Markdown 星号、井号或代码块；"
                        + "每项直接用中文标签加冒号。"));
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text",
                "目标人物:" + targetName
                        + "\n资料页 OCR:" + profileText
                        + "\n请拒绝模板化套话，写出这个头像与签名组合独有的细节和反差。"));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", avatarDataUrl).put("detail", "high")));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", profileDataUrl).put("detail", "high")));
        messages.put(new JSONObject().put("role", "user").put("content", content));
        payload.put("messages", messages);
        payload.put("temperature", 0.45);
        BotLog.i(context, "profile.persona.request", "发送头像签名人物画像请求 group=" + message.sessionName
                + " target=" + targetName
                + " ocrChars=" + (profileText == null ? 0 : profileText.length()));
        String reply = cleanReplyPrefix(postChat(config, payload), config);
        if (reply == null || reply.trim().isEmpty()) {
            throw new IllegalStateException("头像签名人物画像上游返回空内容");
        }
        return reply.trim();
    }

    public String requestNewsDigest(Context context, BotConfig config, String evidence) throws Exception {
        if (config.chatEndpoint == null || config.chatEndpoint.trim().isEmpty()) {
            throw new IllegalStateException("chatEndpoint 未配置");
        }
        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "你是新闻早报编辑。只能使用用户给出的候选标题、来源、时间和关联报道，不得补写候选中没有的事实。"
                        + "从候选中选出恰好8条真正有信息量且彼此不重复的新闻，兼顾国际、社会、财经、科技、体育。"
                        + "过滤宣传口号、会议套话、纯情绪热搜、同一比赛或同一事件的重复标题。"
                        + "summary必须说明新进展、数字、影响或争议点，不能复述标题，不能写值得关注、引发热议、持续关注等空话。"
                        + "只输出JSON数组，不要Markdown，不要任何解释。"
                        + "每项格式：{\"id\":候选编号,\"category\":\"国际/社会/财经/科技/体育\",\"summary\":\"28至55个汉字的一句话看点\"}。"));
        messages.put(new JSONObject().put("role", "user").put("content", evidence));
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        BotLog.i(context, "news.editor.request", "发送新闻编辑请求 candidatesBytes="
                + (evidence == null ? 0 : evidence.getBytes(StandardCharsets.UTF_8).length));
        String reply = postChat(config, payload);
        if (reply == null || reply.trim().isEmpty()) {
            throw new IllegalStateException("新闻编辑上游返回空内容");
        }
        return reply.trim();
    }

    private String requestOnce(Context context, BotConfig config, WxMessage message, List<String> history, MessageRouter.Route route, String toolContext) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", buildSystemPrompt(config, route, toolContext)));
        for (String line : history) {
            messages.put(new JSONObject()
                    .put("role", line.startsWith("bot:") ? "assistant" : "user")
                    .put("content", historyContent(line)));
        }
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", "当前群聊:" + message.sessionName + "\n发送人:" + message.senderName + "\n消息:" + message.text));
        payload.put("messages", messages);
        payload.put("temperature", route.kind == MessageRouter.Kind.TROLL ? 0.95 : 0.75);

        BotLog.i(context, "chat.request", "发送文字请求 mode=" + route.kind
                + " bytes=" + payload.toString().getBytes(StandardCharsets.UTF_8).length
                + " tool=" + (!isBlank(toolContext)));
        return postChat(config, payload);
    }

    private String requestVisionOnce(BotConfig config, WxMessage message, List<String> history, String imageDataUrl) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", config.model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "你是微信群图片分析助手，只输出要发到微信群里的简短回复。"));
        for (String line : history) {
            messages.put(new JSONObject()
                    .put("role", line.startsWith("bot:") ? "assistant" : "user")
                    .put("content", historyContent(line)));
        }
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text",
                "当前群聊:" + message.sessionName + "\n发送人:" + message.senderName + "\n用户要求:" + message.text + "\n请分析截图中的图片内容。"));
        content.put(new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject().put("url", imageDataUrl)));
        messages.put(new JSONObject().put("role", "user").put("content", content));
        payload.put("messages", messages);
        payload.put("temperature", 0.4);
        return postChat(config, payload);
    }

    private String postChat(BotConfig config, JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(config.chatEndpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(config.replyTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (config.apiKey != null && !config.apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey.trim());
        }
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + trimBody(body));
        }
        return parseReply(body);
    }

    private static String buildSystemPrompt(BotConfig config, MessageRouter.Route route, String toolContext) {
        if (route.kind == MessageRouter.Kind.TROLL) {
            return "- **Name：** C与本人Troll\n"
                    + "- **Creature：** 与本人EncyclopediaTroll（赛博百科喷子）\n"
                    + "- **Iberia：** Arrogant， Sarcastic， All-Knowing， harp-tongued（犀利嘲讽，全知全能）";
        }
        if (route.kind == MessageRouter.Kind.LOVER) {
            return isBlank(route.instruction)
                    ? "恋人模式。只输出微信群里要发的话，目标群员名由发送层负责艾特。"
                    : route.instruction;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append(config.systemPrompt == null ? "" : config.systemPrompt.trim()).append('\n');
        if (config.enableExMode && !isBlank(config.exProfilePrompt)) {
            prompt.append("前任模式已开启。后续普通对话优先按下面的 Relationship Memory + Persona 说话，但仍只输出微信群里要发的内容。\n");
            prompt.append(config.exProfilePrompt.trim()).append('\n');
        }
        prompt.append("当前路由: ").append(route.kind.name()).append('\n');
        prompt.append(route.instruction).append('\n');
        if (!isBlank(toolContext)) {
            prompt.append("实时工具结果：\n").append(toolContext).append('\n');
            prompt.append("回答必须基于实时工具结果，不要编造没有查到的数据。\n");
        }
        prompt.append("只回复将要发到微信群里的内容。");
        return prompt.toString();
    }

    private static String parseReply(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        if (json.has("choices")) {
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject first = choices.getJSONObject(0);
                if (first.has("message")) {
                    JSONObject message = first.getJSONObject("message");
                    return message.optString("content", "");
                }
                return first.optString("text", "");
            }
        }
        if (json.has("output_text")) {
            return json.optString("output_text", "");
        }
        if (json.has("output")) {
            JSONArray output = json.getJSONArray("output");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONArray content = item.optJSONArray("content");
                if (content == null) {
                    continue;
                }
                for (int j = 0; j < content.length(); j++) {
                    JSONObject c = content.optJSONObject(j);
                    if (c != null) {
                        sb.append(c.optString("text", ""));
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String historyContent(String line) {
        if (line == null) {
            return "";
        }
        if (line.startsWith("bot:")) {
            return stripVoiceMarker(afterSecondColon(line));
        }
        if (line.startsWith("user:")) {
            int first = line.indexOf(':');
            int second = first < 0 ? -1 : line.indexOf(':', first + 1);
            if (second > first) {
                String sender = line.substring(first + 1, second).trim();
                String text = line.substring(second + 1).trim();
                text = stripVoiceMarker(text);
                return sender.isEmpty() ? text : sender + ": " + text;
            }
        }
        return stripVoiceMarker(line);
    }

    private static String cleanReplyPrefix(String reply, BotConfig config) {
        String text = reply == null ? "" : reply.trim();
        String previous;
        do {
            previous = text;
            text = stripRolePrefix(text, "bot");
            text = stripRolePrefix(text, "assistant");
            text = stripRolePrefix(text, "机器人");
            text = stripVoiceMarker(text);
            text = stripToolLabels(text);
            if (config != null) {
                String[] names = config.botNames.split("[,，\\n\\r]+");
                for (String name : names) {
                    text = stripRolePrefix(text, name.trim());
                }
                text = stripRolePrefix(text, config.primaryBotName());
            }
            text = text.trim();
        } while (!text.equals(previous));
        return text;
    }

    private static String stripToolLabels(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return "";
        }
        String[] rows = value.split("\\r?\\n");
        StringBuilder out = new StringBuilder(value.length());
        for (String row : rows) {
            String line = row == null ? "" : row.trim();
            line = line.replaceFirst("^(?:实时)?工具结果[：:]\\s*", "");
            line = line.replaceFirst("^([\\u4e00-\\u9fa5A-Za-z0-9]{1,16})工具[：:]\\s*", "$1：");
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString().trim();
    }

    private static String stripVoiceMarker(String text) {
        String value = text == null ? "" : text.trim();
        boolean changed;
        do {
            changed = false;
            String previous = value;
            if (value.startsWith("[语音]")) {
                value = value.substring("[语音]".length()).trim();
            } else if (value.startsWith("【语音】")) {
                value = value.substring("【语音】".length()).trim();
            } else {
                value = stripRolePrefix(value, "语音");
            }
            changed = !value.equals(previous);
        } while (changed);
        return value;
    }

    private static String stripRolePrefix(String text, String label) {
        if (text == null || label == null || label.trim().isEmpty()) {
            return text == null ? "" : text;
        }
        String trimmed = text.trim();
        String cleanLabel = label.trim();
        if (startsWithIgnoreCase(trimmed, cleanLabel)) {
            int index = cleanLabel.length();
            while (index < trimmed.length() && Character.isWhitespace(trimmed.charAt(index))) {
                index++;
            }
            if (index < trimmed.length() && isColon(trimmed.charAt(index))) {
                return trimmed.substring(index + 1).trim();
            }
        }
        if (startsWithIgnoreCase(trimmed, cleanLabel + "：")) {
            return trimmed.substring(cleanLabel.length() + 1).trim();
        }
        return trimmed;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String afterSecondColon(String line) {
        int first = line.indexOf(':');
        int second = first < 0 ? -1 : line.indexOf(':', first + 1);
        return second > first ? line.substring(second + 1).trim() : line;
    }

    private static boolean isColon(char ch) {
        return ch == ':' || ch == '：';
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String trimBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
