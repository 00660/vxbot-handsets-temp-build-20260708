package com.vxbot.wechatbot;

import java.util.Locale;

public final class MessageRouter {
    private static final String TARGET_SEPARATORS = "，,。！？!?；;：:、~～\"“”'‘’()[]{}<>《》";

    public enum Kind {
        TEXT,
        IMAGE,
        ANALYSIS,
        TROLL,
        LOVER,
        CODEX,
        LICENSE,
        FINANCE,
        SPORTS,
        UTILITY,
        NEWS,
        WEATHER,
        VIDEO,
        WOOL,
        TTS,
        STICKER,
        REPORT,
        PERSONA,
        SHUTUP,
        MANUAL,
        SCREEN_DIM,
        SCREEN_BRIGHT
    }

    public static final class Route {
        public final Kind kind;
        public final String instruction;
        public final String targetName;
        public final boolean exitCommand;
        public final boolean explicitModeCommand;

        public Route(Kind kind, String instruction) {
            this(kind, instruction, "", false, false);
        }

        public Route(Kind kind, String instruction, String targetName, boolean exitCommand, boolean explicitModeCommand) {
            this.kind = kind;
            this.instruction = instruction;
            this.targetName = targetName == null ? "" : targetName.trim();
            this.exitCommand = exitCommand;
            this.explicitModeCommand = explicitModeCommand;
        }
    }

    private MessageRouter() {
    }

    public static Route classify(String text, BotConfig config) {
        String source = text == null ? "" : text;
        String command = stripBotMention(source, config);
        if (command.isEmpty()) {
            command = source;
        }
        if (config.enableTroll && isRoastExitCommand(command)) {
            return new Route(Kind.TROLL, "退出赛博喷子/对喷模式。", "", true, true);
        }
        if (config.enableLover && isLoverExitCommand(command)) {
            return new Route(Kind.LOVER, "退出恋人模式。", "", true, true);
        }
        if (isManualCommand(command)) {
            return new Route(Kind.MANUAL, "机器人操作手册。");
        }
        if (isScreenBrightCommand(command)) {
            return new Route(Kind.SCREEN_BRIGHT, "关闭低亮防熄屏并把亮度调高。");
        }
        if (isScreenDimCommand(command)) {
            return new Route(Kind.SCREEN_DIM, "开启低亮防熄屏。");
        }
        if (looksLikeLicenseRequest(command)) {
            return new Route(Kind.LICENSE, "注册机/授权码请求：按旧版 LicensePanelBot 逻辑本地分流处理。");
        }
        String ttsText = extractTtsText(command);
        if (!ttsText.isEmpty()) {
            return new Route(Kind.TTS, ttsText);
        }
        if (isReportCommand(command)) {
            return new Route(Kind.REPORT, "本地随机报道回复。");
        }
        if (isPersonaCommand(command)) {
            return new Route(Kind.PERSONA, "群成员人物画像：按群、成员、日期统计发言，上游分析话痨排行、性格画像、代表发言和关键词重点。");
        }
        if (isCodexModeExitCommand(command)) {
            return new Route(Kind.CODEX, "退出本群授权 Codex 模式。", "", true, true);
        }
        if (extractCodexSessionSwitch(command, config) != null) {
            return new Route(Kind.CODEX, "切换 Happy Codex 会话。", "", false, true);
        }
        if (isCodexModeEnterCommand(command)) {
            return new Route(Kind.CODEX, "进入本群授权 Codex 模式：后续由该授权人发送的消息全部交给 Codex。");
        }
        if (isCodexCommand(command)) {
            return new Route(Kind.CODEX, "Codex 模式：技术回复直接、简洁，优先给可执行步骤。");
        }
        if (config.enableImage && looksLikeStickerRequest(command)) {
            return new Route(Kind.STICKER, "EmojiCut 表情包：生成 16 格白底贴纸图，切图保存后发群。");
        }
        if (config.enableVideoParse && looksLikeVideoParseRequest(command)) {
            return new Route(Kind.VIDEO, "短视频/图集解析：解析分享链接，下载无水印视频或图集，并通过微信分享链路发回当前群。");
        }
        if (config.enableImageAnalysis && looksLikeImageAnalysis(command)) {
            return new Route(Kind.ANALYSIS, "图片分析：分析当前会话截图里用户要求看的图片，只输出微信群里要回复的内容。");
        }
        if (config.enableImage && looksLikeImageRequest(command)) {
            return new Route(Kind.IMAGE, "图片请求：APK 内部组装提示词并直接请求图片上游，不走 3001。");
        }
        if (config.enableFinance && looksLikeFinanceRequest(command)) {
            return new Route(Kind.FINANCE, "金融查询：给出简洁结果和必要风险提示，不编造实时行情。");
        }
        if (config.enableSports && looksLikeSportsRequest(command)) {
            return new Route(Kind.SPORTS, "体育赛事查询/分析：查询比赛赛程、比分和状态；分析类问题基于实时赛事结果给上游判断。");
        }
        if (looksLikeUtilityRequest(command)) {
            return new Route(Kind.UTILITY, "本地工具：时间日期、简单计算和单位换算。");
        }
        if (looksLikeWoolRequest(command)) {
            return new Route(Kind.WOOL, "羊毛线报：抓取赚客吧最新线报并生成榜单图片发群。");
        }
        if (config.enableNews && matchesAny(command, "新闻", "微博热点", "热搜", "热点", "今日头条", "早报", "晨报", "简报")) {
            return new Route(Kind.NEWS, "新闻/早报查询：整合热搜、热榜和 RSS 新闻，只输出适合群里的简短摘要。");
        }
        if (config.enableWeather && matchesAny(command, "天气", "下雨", "温度", "气温", "预报")) {
            return new Route(Kind.WEATHER, "天气查询：按用户地点请求回复，不确定地点就追问。");
        }
        if (config.enableShutupCooldown && matchesAny(command, "闭嘴", "住嘴", "关闭瞎聊", "别说了")) {
            return new Route(Kind.SHUTUP, "回复一句赛博朋克暴脾气风格的闭嘴回击，然后本群安静一段时间。");
        }
        if (config.enableLover && looksLikeLoverRequest(command)) {
            String target = extractLoverTarget(command);
            return new Route(Kind.LOVER, loverInstruction(target), target, false, true);
        }
        if (config.enableTroll && !looksLikeProtectedToolTopic(command)
                && (matchesAny(command, "对喷", "开喷", "喷一下", "喷子模式", "troll mode", "troll模式", "怼一下")
                || looksUncivil(command))) {
            return new Route(Kind.TROLL, "", extractRoastTarget(command), false, true);
        }
        return new Route(Kind.TEXT, "普通群聊接话，短句，自然，不写长段说明。");
    }

    public static boolean isHighPriorityCommand(String text, BotConfig config) {
        if (text == null || config == null) {
            return false;
        }
        String command = stripBotMention(text, config);
        if (command.isEmpty()) {
            command = text;
        }
        if (isRoastExitCommand(command) || isLoverExitCommand(command) || isCodexModeExitCommand(command)) {
            return true;
        }
        if (isManualCommand(command) || isScreenDimCommand(command) || isScreenBrightCommand(command)) {
            return true;
        }
        if (looksLikeLicenseRequest(command)) {
            return true;
        }
        if (!extractTtsText(command).isEmpty()) {
            return true;
        }
        if (isReportCommand(command)) {
            return true;
        }
        if (isPersonaCommand(command)) {
            return true;
        }
        if (isCodexModeEnterCommand(command)) {
            return true;
        }
        if (extractCodexSessionSwitch(command, config) != null) {
            return true;
        }
        if (isCodexCommand(command)) {
            return true;
        }
        if (config.enableImage && looksLikeStickerRequest(command)) {
            return true;
        }
        if (config.enableVideoParse && looksLikeVideoParseRequest(command)) {
            return true;
        }
        if ((config.enableImageAnalysis && looksLikeImageAnalysis(command)) || (config.enableImage && looksLikeImageRequest(command))) {
            return true;
        }
        if (config.enableFinance && looksLikeFinanceRequest(command)) {
            return true;
        }
        if (config.enableSports && looksLikeSportsRequest(command)) {
            return true;
        }
        if (looksLikeUtilityRequest(command)) {
            return true;
        }
        if (looksLikeWoolRequest(command)) {
            return true;
        }
        if (config.enableNews && matchesAny(command, "新闻", "微博热点", "热搜", "热点", "今日头条", "早报", "晨报", "简报")) {
            return true;
        }
        return config.enableWeather && matchesAny(command, "天气", "下雨", "温度", "气温", "预报");
    }

    public static boolean isMediaOnlyMessage(String text) {
        String value = compact(text);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches("^(\\[?(图片|照片|相片|视频|语音|文件|链接|动画表情|表情|聊天记录)\\]?)+$");
    }

    public static boolean isRoastExitCommand(String text) {
        String compact = compact(text);
        return compact.matches("^(强制)?(退出|关闭|停止|结束|取消|解除)(喷子模式|赛博喷子|对喷模式|对喷|喷人|互喷|开喷)$")
                || compact.matches("^(喷子模式|赛博喷子|对喷模式|对喷)(退出|关闭|停止|结束|取消|解除)$")
                || compact.matches("^(别喷了|不要喷了|不喷了|停喷|停止喷|解除对喷|取消对喷|结束对喷)$");
    }

    public static boolean isLoverExitCommand(String text) {
        String compact = compact(text);
        return compact.matches("^(强制)?(退出|关闭|停止|结束|取消|解除)(恋人模式|情侣模式|谈恋爱|谈情说爱|撒糖|互撩)$")
                || compact.matches("^(恋人模式|情侣模式|撒糖|互撩)(退出|关闭|停止|结束|取消|解除)$")
                || compact.matches("^(别撒糖了|不要撒糖了|不撒糖了|别腻歪了|停止撒糖|结束撒糖|退出情侣模式|退出恋人模式)$");
    }

    public static boolean isManualCommand(String text) {
        String value = compact(text);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches("^(机器人|bot|慢一点|韵味)?(菜单|帮助|操作手册|使用手册|指令|功能列表|命令列表)$")
                || value.matches("^(菜单|帮助|操作手册|使用手册|指令|功能列表|命令列表)(机器人|bot|慢一点|韵味)?$");
    }

    public static boolean isPersonaCommand(String text) {
        String value = compact(text);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches(".*(人物画像|群员画像|成员画像|群友画像|群画像|画像分析|话痨排行|活跃排行|谁是话痨|谁最话痨|谁话最多|群聊总结|昨日总结|今日总结|昨天总结|今天总结|昨天干了啥|昨天说了啥|昨日重点|今日重点|重点是啥).*");
    }

    public static boolean isCodexModeEnterCommand(String text, BotConfig config) {
        String command = stripBotMention(text, config);
        if (command.isEmpty()) {
            command = text;
        }
        return isCodexModeEnterCommand(command);
    }

    public static boolean isCodexModeExitCommand(String text, BotConfig config) {
        String command = stripBotMention(text, config);
        if (command.isEmpty()) {
            command = text;
        }
        return isCodexModeExitCommand(command);
    }

    public static String extractCodexSessionSwitch(String text, BotConfig config) {
        String command = stripBotMention(text, config);
        if (command.isEmpty()) {
            command = text;
        }
        String value = cleanCommand(command).trim();
        if (value.isEmpty()) {
            return null;
        }
        String compact = removeWhitespace(compact(value)).toLowerCase(Locale.ROOT);
        boolean hasCodex = compact.contains("codex") || compact.contains("代码");
        boolean hasSession = compact.contains("会话") || compact.contains("session");
        if (!hasCodex || !hasSession) {
            return null;
        }
        boolean switchAction = compact.startsWith("切换")
                || compact.startsWith("使用")
                || compact.startsWith("设置")
                || compact.startsWith("指定")
                || compact.startsWith("绑定")
                || compact.startsWith("选择")
                || compact.startsWith("改成")
                || compact.startsWith("清除")
                || compact.startsWith("重置")
                || compact.startsWith("取消")
                || compact.startsWith("切回")
                || compact.startsWith("codex")
                || compact.startsWith("happycodex")
                || compact.startsWith("代码");
        if (!switchAction) {
            return null;
        }
        if (compact.matches(".*(自动|最近活跃|清除|重置|取消|不指定|不用指定).*")) {
            return "";
        }
        String target = value;
        String[] markers = {
                "sessionId", "sessionID", "SessionId", "Session ID", "session id",
                "session", "Session", "会话"
        };
        int bestIndex = -1;
        String bestMarker = "";
        for (String marker : markers) {
            int index = target.lastIndexOf(marker);
            if (index >= bestIndex) {
                bestIndex = index;
                bestMarker = marker;
            }
        }
        if (bestIndex >= 0) {
            target = target.substring(bestIndex + bestMarker.length());
        }
        target = sanitizeSessionId(target);
        return target.isEmpty() ? null : target;
    }

    public static boolean isScreenDimCommand(String text) {
        String value = compact(text);
        return value.matches(".*(屏幕最暗|亮度最低|调暗屏幕|屏幕调暗|开启防熄屏|打开防熄屏|低亮保活|无root保活|无root防熄屏).*");
    }

    public static boolean isScreenBrightCommand(String text) {
        String value = compact(text);
        return value.matches(".*(屏幕最亮|亮度最高|调亮屏幕|屏幕调亮|恢复亮度|关闭防熄屏|退出防熄屏|取消低亮保活|恢复屏幕亮度).*");
    }

    private static String extractRoastTarget(String text) {
        String value = dropBotPrefix(cleanCommand(text));
        String[] keys = {
                "对喷一下", "对喷下", "对喷两句", "对喷几句", "对喷",
                "对碰一下", "对碰下", "对碰两句", "对碰几句", "对碰"
        };
        int bestIndex = -1;
        String bestKey = "";
        for (String key : keys) {
            int index = value.lastIndexOf(key);
            if (index >= 0 && index >= bestIndex) {
                bestIndex = index;
                bestKey = key;
            }
        }
        if (bestIndex < 0) {
            return "";
        }
        return sanitizeTarget(value.substring(bestIndex + bestKey.length()));
    }

    public static String loverInstruction(String targetName) {
        String target = targetName == null ? "" : targetName.trim();
        if (target.isEmpty()) {
            return "恋人模式。";
        }
        return "@" + target + " 向他表白。每次回复必须 @" + target;
    }

    private static String extractLoverTarget(String text) {
        String value = dropBotPrefix(cleanCommand(text));
        if (value.startsWith("撩一下") && value.length() > 3) {
            return sanitizeTarget(value.substring(3));
        }
        if (value.startsWith("表白") && value.length() > 2) {
            return sanitizeTarget(value.substring(2));
        }
        if (value.startsWith("跟")) {
            int end = value.indexOf("表白", 1);
            if (end > 1) {
                return sanitizeTarget(value.substring(1, end));
            }
        }
        String target = betweenAny(value, new String[]{"艾特", "@"}, new String[]{"跟他表白", "跟她表白", "跟它表白", "表白", "告白"});
        if (!target.isEmpty()) {
            return sanitizeTarget(target);
        }
        target = betweenAny(value, new String[]{"向", "对", "给", "跟", "和"}, new String[]{"表白", "告白"});
        if (!target.isEmpty()) {
            return sanitizeTarget(target);
        }
        int modeIndex = lastIndexOfAny(value, new String[]{"恋人模式", "情侣模式", "谈恋爱", "谈情说爱", "撒糖", "互撩"});
        if (modeIndex < 0) {
            return "";
        }
        int end = modeIndex;
        String[] modeWords = {"恋人模式", "情侣模式", "谈恋爱", "谈情说爱", "撒糖", "互撩"};
        for (String word : modeWords) {
            if (value.startsWith(word, modeIndex)) {
                end = modeIndex + word.length();
                break;
            }
        }
        return sanitizeTarget(value.substring(end));
    }

    private static boolean looksLikeLoverRequest(String text) {
        String value = dropBotPrefix(cleanCommand(text));
        return !extractLoverTarget(value).isEmpty();
    }

    private static boolean looksLikeLicenseRequest(String text) {
        String value = compact(text);
        String lower = removeWhitespace(text == null ? "" : text.toLowerCase(Locale.ROOT));
        return value.matches(".*(注册机码|注册码|授权码|机器码|设备ID|设备id|元统计|元授权|元统计授权|极品统计|极品注册码|AI统计|ai统计|登录密码|登录码|GXAZ|gxaz).*")
                || lower.matches(".*(license|activation|registrationcode|machinecode|deviceid).*");
    }

    private static boolean looksLikeFinanceRequest(String text) {
        return matchesAny(text,
                "股票", "基金", "虚拟币", "加密货币", "数字货币", "币价", "币圈",
                "代币", "合约地址", "链上", "币安链", "meme币", "token", "coin", "crypto", "bsc", "dex",
                "比特币", "大饼", "以太坊", "狗狗币", "瑞波", "波场", "币安币", "波卡", "莱特币", "柴犬币",
                "btc", "eth", "sol", "doge", "xrp", "bnb", "ada", "trx", "avax", "link", "dot", "ltc", "bch", "ton", "shib", "pepe", "uni", "matic", "pol", "etc", "fil", "icp", "atom", "near", "arb", "apt", "sui", "aave", "okb",
                "行情", "汇率", "外汇", "美元", "人民币", "港币", "港元", "欧元", "日元", "英镑", "澳元", "加元", "新加坡元", "瑞郎", "离岸人民币",
                "黄金", "白银", "贵金属", "金价", "银价", "铂金", "白金", "钯金", "克价", "每克", "一克", "多少钱一克", "回收价", "黄金回收", "金店", "金条", "足金", "千足金", "万足金", "伦敦金", "伦敦银", "现货黄金", "现货白银", "au999", "ag999", "pt950", "pd950",
                "原油", "油价", "布伦特", "天然气", "汽油", "铜价", "铜", "伦铜", "沪铜", "电解铜",
                "美股", "港股", "A股", "a股", "纳指", "纳斯达克", "标普", "道指",
                "上证", "创业板", "沪深300", "恒生", "恒指", "富时中国A50", "a50", "qqq", "spy", "dia",
                "茅台", "宁德时代", "特斯拉", "英伟达", "超微", "苹果", "微软", "谷歌", "亚马逊", "拼多多", "腾讯", "小米", "美团");
    }

    private static boolean looksLikeProtectedToolTopic(String text) {
        return looksLikeLicenseRequest(text)
                || looksLikeImageAnalysis(text)
                || looksLikeImageRequest(text)
                || looksLikeVideoParseRequest(text)
                || looksLikeFinanceRequest(text)
                || looksLikeSportsRequest(text)
                || looksLikeUtilityRequest(text)
                || looksLikeWoolRequest(text)
                || matchesAny(text, "天气", "下雨", "温度", "气温", "预报", "新闻", "微博热点", "热搜", "热点", "今日头条", "早报", "晨报", "简报")
                || isCodexModeExitCommand(text)
                || extractCodexSessionSwitch(text, null) != null
                || isCodexModeEnterCommand(text)
                || isCodexCommand(text);
    }

    private static boolean looksLikeSportsRequest(String text) {
        String value = compact(text).toLowerCase(Locale.ROOT);
        return matchesAny(value,
                "世界杯", "世俱杯", "欧洲杯", "亚洲杯", "美洲杯", "欧冠", "欧联", "英超", "西甲", "意甲", "德甲", "法甲", "中超",
                "nba", "wnba", "cba", "nfl", "nhl", "mlb", "f1", "ufc",
                "足球", "篮球", "网球", "羽毛球", "乒乓球", "排球",
                "赛程", "赛事", "比分", "战绩", "排名", "积分榜", "对阵", "谁赢", "几比几", "比赛结果", "今日比赛", "今晚比赛", "明天比赛")
                || value.matches(".*(\\w+vs\\w+|\\w+v\\w+).*");
    }

    private static boolean isCodexCommand(String text) {
        String value = cleanCommand(text);
        String lower = value.toLowerCase(Locale.ROOT).trim();
        if (startsWithAsciiCommand(lower, "codex")) {
            return true;
        }
        String compact = removeWhitespace(compact(value)).toLowerCase(Locale.ROOT);
        return startsWithAsciiCommand(compact, "bug")
                || compact.startsWith("代码")
                || compact.startsWith("报错")
                || compact.startsWith("修复")
                || compact.contains("代码")
                || compact.contains("报错")
                || compact.contains("修复");
    }

    private static boolean isCodexModeEnterCommand(String text) {
        String value = removeWhitespace(compact(text)).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches("^(全局)?(进入|打开|开启|启动)(codex|代码)(模式|状态)?$")
                || value.matches("^(进入|打开|开启|启动)(全局)?(codex|代码)(模式|状态)?$")
                || value.matches("^(全局)?(codex|代码)(模式|状态)(进入|打开|开启|启动)?$");
    }

    private static boolean isCodexModeExitCommand(String text) {
        String value = removeWhitespace(compact(text)).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches("^(强制)?(退出|关闭|停止|结束|取消|解除)(全局)?(codex|代码)(模式|状态)?$")
                || value.matches("^(强制)?(退出|关闭|停止|结束|取消|解除)(codex|代码)$")
                || value.matches("^(全局)?(codex|代码)(模式|状态)?(退出|关闭|停止|结束|取消|解除)$")
                || value.matches("^(退出|关闭|停止|结束|取消|解除)(全局)?(codex|代码)(模式|状态)?$")
                || value.matches("^(别|不要|不用)?(进|跑)?(codex|代码)(了)$");
    }

    private static boolean startsWithAsciiCommand(String value, String command) {
        if (value == null || command == null || !value.startsWith(command)) {
            return false;
        }
        if (value.length() == command.length()) {
            return true;
        }
        char next = value.charAt(command.length());
        return !((next >= 'a' && next <= 'z') || (next >= '0' && next <= '9') || next == '_');
    }

    private static String sanitizeSessionId(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim();
        while (!out.isEmpty() && TARGET_SEPARATORS.indexOf(out.charAt(0)) >= 0) {
            out = out.substring(1).trim();
        }
        String[] fillers = {"为", "到", "成", "使用", "设为", "设置为", "指定为", "绑定为", "切换到"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String filler : fillers) {
                if (out.startsWith(filler)) {
                    out = out.substring(filler.length()).trim();
                    changed = true;
                }
            }
        }
        int space = out.indexOf(' ');
        if (space > 0) {
            out = out.substring(0, space);
        }
        int line = out.indexOf('\n');
        if (line > 0) {
            out = out.substring(0, line);
        }
        while (!out.isEmpty() && TARGET_SEPARATORS.indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }

    private static boolean looksLikeUtilityRequest(String text) {
        String value = compact(text);
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.matches(".*(北京时间|现在几点|几点了|今天几号|今天星期几|今天周几|现在日期|当前时间|当前日期).*")) {
            return true;
        }
        if (matchesAny(value, "计算", "算一下", "等于多少", "单位换算", "换算一下")) {
            return true;
        }
        if (lower.matches(".*\\d+\\s*[+\\-*/×÷]\\s*\\d+.*")) {
            return true;
        }
        return value.matches(".*\\d+(\\.\\d+)?\\s*(公里|千米|米|厘米|毫米|斤|公斤|千克|克|吨|升|毫升|摄氏度|华氏度|美元|人民币).*?(等于|换算|是多少|多少).*");
    }

    public static boolean isReportCommand(String text) {
        String value = compact(text);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches("^(机器人|bot|慢一点|韵味)?(请)?(报道|报到|出来报道|出来报到|上线报道|上线报到)$")
                || value.matches("^(请)?(机器人|bot|慢一点|韵味)(报道|报到|出来报道|出来报到|上线报道|上线报到)$")
                || value.matches("^(机器人|bot|慢一点|韵味)(在不在|在吗|到没到)$");
    }

    private static boolean looksLikeWoolRequest(String text) {
        return matchesAny(text,
                "来点羊毛", "发点羊毛", "发个羊毛", "上点羊毛", "羊毛榜", "羊毛线报", "最新羊毛",
                "薅羊毛", "薅点毛", "撸毛", "撸点毛", "毛线报",
                "有啥羊毛", "有什么羊毛", "优惠线报", "线报榜", "赚客吧", "摸鱼热榜");
    }

    private static boolean looksLikeStickerRequest(String text) {
        String value = compact(text).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches(".*(表情包|贴纸|微信表情|表情图|做表情|生成表情|生成贴纸|做一套表情|做一套贴纸|emojicut|emoji包|stickers?|stickerpack).*");
    }

    public static boolean looksLikeVideoParseRequest(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.matches("(?s).*(https?://|www\\.).*")) {
            return false;
        }
        String[] domains = {
                "v.douyin.com", "iesdouyin.com", "douyin.com",
                "v.kuaishou.com", "kuaishou.com",
                "xiaohongshu.com", "xhslink.com",
                "h5.pipix.com", "pipix.com",
                "h5.pipigx.com", "pipigx.com",
                "weibo.com", "weibo.cn",
                "isee.weishi.qq.com",
                "v.ixigua.com", "ixigua.com",
                "share.huoshan.com", "huoshan.com",
                "share.xiaochuankeji.cn",
                "xspshare.baidu.com", "haokan.baidu.com", "haokan.hao123.com",
                "www.pearvideo.com", "pearvideo.com",
                "v.huya.com", "huya.com",
                "www.acfun.cn", "acfun.cn",
                "doupai.cc", "meipai.com", "kg.qq.com", "6.cn",
                "xinpianchang.com", "bilibili.com", "b23.tv",
                "x.com", "twitter.com", "t.co",
                "v.qq.com", "tv.sohu.com", "m.tv.sohu.com", "cctv.com"
        };
        for (String domain : domains) {
            if (lower.contains(domain)) {
                return true;
            }
        }
        return matchesAny(value, "解析视频", "视频解析", "去水印", "无水印", "解析图集", "解析小红书");
    }

    public static String extractTtsText(String text) {
        String value = cleanCommand(text);
        String lower = value.toLowerCase(Locale.ROOT).trim();
        if (lower.startsWith("tts")) {
            return normalizeTtsPayload(value.substring(3));
        }
        String[] prefixes = {
                "用语音说一下", "用语音说",
                "语音说一下", "语音说",
                "发条语音说", "发条语音",
                "发个语音说", "发个语音",
                "发语音说", "发语音",
                "录条语音说", "录条语音",
                "语音播报一下", "语音播报",
                "朗读一下", "朗读",
                "读出来"
        };
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return normalizeTtsPayload(value.substring(prefix.length()));
            }
        }
        return "";
    }

    private static String normalizeTtsPayload(String value) {
        String clean = cleanCommand(value);
        while (!clean.isEmpty() && isTtsPayloadSeparator(clean.charAt(0))) {
            clean = clean.substring(1).trim();
        }
        if (clean.length() > 180) {
            return clean.substring(0, 180);
        }
        return clean;
    }

    private static boolean looksLikeImageAnalysis(String text) {
        String value = compact(text).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        boolean bareAnalysis = value.matches("^(分析|分析下|分析一下|给分析下|帮我分析|看看|看看图|看图|识图)$");
        boolean imageContextAnalysis = value.matches(".*(分析|看看|看下|识别|识图|说说).{0,8}(图片|照片|相片|图|这图|这张图|图里|图片里|照片里).*")
                || value.matches(".*(图片|照片|相片|图|这图|这张图|图里|图片里|照片里).{0,8}(分析|看看|看下|识别|识图|说说).*");
        boolean explicitAnalysis = bareAnalysis
                || imageContextAnalysis
                || value.matches(".*(分析图片|图片分析|看图分析|describeimage|analyzethis|analyzeimage|whatsthis).*");
        boolean editImage = value.matches(".*(图生图|以图生图|参考图|参考这张|参考这个图|按这张|用这张|用这图|根据这张|改这张|换这张|修这张|这张改|这图改|换背景|换个场景|换个地方|重拍|再来一张|生成).*")
                || value.matches(".*(img2img|image2image|referenceimage|editimage|editphoto|editthisimage|usethisimage|changethisimage|modifythisimage|changebackground).*");
        if (editImage && !explicitAnalysis) {
            return false;
        }
        return explicitAnalysis
                || value.matches(".*(图片分析|看图分析|识图|看看图|这图|这张图|图里|图片里|照片里|whatsthis|analyzethis|analyzeimage|describeimage).*");
    }

    private static boolean looksLikeImageRequest(String text) {
        String value = compact(text);
        String lower = removeWhitespace(text == null ? "" : text.toLowerCase(Locale.ROOT));
        if (value.matches("^(帮我自拍|帮我拍照|给我拍照|替我拍照|帮我照相|给我照相).*$")
                && !value.matches(".*(你|你的|你自己|机器人).*")) {
            return false;
        }
        if (value.matches("^(图|图片|照片|相片|\\[图片\\]|\\[照片\\]|识图|图生图不行|文生图可以吗)$")) {
            return false;
        }
        if (value.matches(".*(清凉|清爽穿搭|比基尼|泳装|泳衣|泳池自拍|海边自拍|海滩自拍|沙滩自拍|度假自拍|夏日自拍|夏日泳装|清凉图).*")) {
            return true;
        }
        if (lower.matches(".*(bikini|swimsuit|swimwear|beachselfie|poolselfie|summerselfie|vacationselfie).*")) {
            return true;
        }
        if (value.matches(".*(图生图|以图生图|参考图|参考这张|参考这个图|参考这个图片|照着这张|按这张|用这张|用这图|根据这张|拿这张|拿这图|按这个图|用这个图|改这张|换这张|修这张|这张改|这图改|这个图改).*")) {
            return true;
        }
        if (lower.matches(".*(img2img|image2image|referenceimage|editimage|editphoto|referencethis|editthisimage|editthisphoto|usethisimage|usethisphoto|basedonthisimage|modifythisimage|changethisimage).*")) {
            return true;
        }
        if (value.matches(".*(换个场景|换场景|换个地方|换地方|换个背景|换背景|换成户外|换到户外|重拍|重新拍|再来一张|再发一张|再拍一张|继续来|继续|再来|侧一点|坐着拍|站着拍|换个动作|换个姿势|换一版|改一版|近一点|远一点|自然一点|清晰一点|更清晰).*")) {
            return true;
        }
        if (lower.matches(".*(differentbackground|changebackground|newbackground|anotherone|again|redo|retry|remake|regenerate|newpose|differentpose|changeangle|makeitclearer|moreclear).*")) {
            return true;
        }
        if (value.matches("^(自拍|拍照|发照片|露脸|发个自拍|来张自拍|拍张自拍|发你的自拍|给我发自拍|展示一下自拍|看看你的自拍|发张照片|露个脸|让我看看你)$")) {
            return true;
        }
        if (value.matches("^(发张你的自拍呗|来一张自拍看看|有没有自拍呀|快发自拍|来个自拍瞧瞧|想看你的自拍|分享下自拍|发一下你的照片|来张近照|看看你现在的样子|拍一张给我看看|有没有新自拍|发张新自拍|整点自拍看看|晒一下自拍|露个脸呗|出来露个脸|让我瞅瞅你|看看真人样子|亮个相吧|出来冒个泡拍张照)$")) {
            return true;
        }
        if (value.matches("^(好不好嘛发张自拍|求求啦来张自拍|想看你自拍嘛|快嘛快嘛发自拍|敢不敢发自拍|来看看你的颜值|快亮出你的自拍|别躲了发张自拍|让我鉴定一下颜值|快营业发自拍啦|今日份自拍安排一下|该出来营业了|怎么不发自拍呢|什么时候发自拍|就不能发张自拍吗|不给看看自拍吗)$")) {
            return true;
        }
        if (value.matches("^(来自拍|发自拍呗|拍一个|来一张|看看照片|整个自拍|发发自拍嘛|来嘛来嘛自拍|快一点发自拍|发下今日自拍|来张日常自拍|看看你日常的样子|拍一下现在的你|分享今日日常自拍|来点日常照片|再发一张自拍|还有别的自拍吗|再来一张看看|速速发自拍|交出你的自拍|在线求自拍|蹲一个自拍|看看素颜自拍|原相机自拍安排下|浅发一张自拍|整活发自拍|整张自拍瞅瞅|来张相片呗|露个面儿)$")) {
            return true;
        }
        if (value.matches(".*(想看|看看|看下|给我看|让我看|让我瞅瞅|瞅瞅|鉴定一下).{0,8}(你|你的|本人|真人|样子|照片|自拍|近照|穿搭|颜值|脸|脸蛋).*")) {
            return true;
        }
        if (value.matches(".*(你|你的).{0,8}(照片|相片|自拍|拍照|近照|样子|啥样|什么样|长什么样|长啥样|穿搭|穿的什么|今天穿|颜值|露脸|露个脸).*")) {
            return true;
        }
        if (value.matches(".*(发|来|拍|整|晒|分享|安排|交出).{0,8}(自拍|自拍照|照片|相片|近照|日常照片|今日自拍|素颜自拍|原相机自拍).*")) {
            return true;
        }
        return lower.matches("^(selfie|sendselfie|sendaselfie|sendmeaselfie|sendyourselfie|showmeyourselfie|takeaselfie|snapaselfie|newselfie|anotherselfie|yourphoto|yourpicture|yourpic|photoofyou|pictureofyou|picofyou|sendyourphoto|sendyourpicture|sendyourpic|sendyourselfphoto|sendyourselfpicture|sendmeaphoto|sendmeapicture|sendmeapic|showmeyourphoto|showmeyourpicture|showmeyourpic|showmeyourface|showyourface|letmeseeyou|showmeyou|showyourself|showmeyourlook|showmeyouroutfit|facepic|facephoto)$")
                || lower.matches(".*(send|show|give|share|take|snap).{0,20}(selfie|yourphoto|yourpicture|yourpic|photoofyou|pictureofyou|picofyou|face|outfit|look).*")
                || lower.matches(".*(generate|create|draw|make|render|produce).{0,24}(image|picture|photo|avatar|wallpaper|poster|meme|illustration|comic|landscape|portrait).*");
    }

    private static boolean looksUncivil(String text) {
        return matchesAny(text, "傻逼", "煞笔", "傻屌", "蠢货", "弱智", "脑残", "废物", "垃圾", "畜生", "叼毛", "吊毛", "屌毛", "狗东西", "狗玩意", "去死", "滚蛋", "你妈", "尼玛", "sb", "nmsl", "fuck", "shit");
    }

    public static boolean matchesAny(String text, String... terms) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            String t = term.toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && lower.contains(t)) {
                return true;
            }
        }
        return false;
    }

    public static String stripBotMention(String text, BotConfig config) {
        String value = text == null ? "" : text;
        if (config == null) {
            return value.trim();
        }
        String[] names = config.botNames.split("[,，\\n\\r]+");
        for (String name : names) {
            String n = name.trim();
            if (!n.isEmpty()) {
                value = value.replace("@" + n, "").replace(n, "");
            }
        }
        return value.replace(" ", "").trim();
    }

    private static String cleanCommand(String text) {
        return (text == null ? "" : text).replace('\u2005', ' ').replace('\u00a0', ' ').trim();
    }

    private static String dropBotPrefix(String text) {
        return text == null ? "" : text.trim();
    }

    private static int lastIndexOfAny(String value, String[] needles) {
        int best = -1;
        for (String needle : needles) {
            int index = value.lastIndexOf(needle);
            if (index > best) {
                best = index;
            }
        }
        return best;
    }

    private static String betweenAny(String value, String[] starts, String[] ends) {
        for (String start : starts) {
            int startIndex = value.lastIndexOf(start);
            if (startIndex < 0) {
                continue;
            }
            int from = startIndex + start.length();
            int endIndex = -1;
            for (String end : ends) {
                int index = value.indexOf(end, from);
                if (index >= 0 && (endIndex < 0 || index < endIndex)) {
                    endIndex = index;
                }
            }
            if (endIndex > from) {
                return value.substring(from, endIndex);
            }
        }
        return "";
    }

    private static String compact(String text) {
        String value = dropBotPrefix(cleanCommand(text));
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!isTargetSeparator(ch) && ch != '@') {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String sanitizeTarget(String value) {
        if (value == null) {
            return "";
        }
        String clean = cleanCommand(value);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char ch = clean.charAt(i);
            if (builder.length() == 0 && (ch == '@' || isTargetSeparator(ch))) {
                continue;
            }
            if (isTargetSeparator(ch)) {
                break;
            }
            if (ch != '@') {
                builder.append(ch);
            }
        }
        String target = builder.toString().trim();
        return target.length() > 40 ? target.substring(0, 40) : target;
    }

    private static String removeWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static boolean isTargetSeparator(char ch) {
        return Character.isWhitespace(ch) || TARGET_SEPARATORS.indexOf(ch) >= 0;
    }

    private static boolean isTtsPayloadSeparator(char ch) {
        return Character.isWhitespace(ch) || ch == ':' || ch == '：' || ch == ',' || ch == '，'
                || ch == ';' || ch == '；' || ch == '-' || ch == '—';
    }
}
