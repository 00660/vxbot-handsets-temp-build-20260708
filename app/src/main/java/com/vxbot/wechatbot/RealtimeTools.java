package com.vxbot.wechatbot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RealtimeTools {
    private static final double TROY_OUNCE_GRAMS = 31.1034768;
    private static final double POUND_GRAMS = 453.59237;

    private RealtimeTools() {
    }

    public static String buildContext(Context context, String text, MessageRouter.Kind kind) {
        try {
            if (kind == MessageRouter.Kind.WEATHER) {
                return weather(text);
            }
            if (kind == MessageRouter.Kind.NEWS) {
                return news(text);
            }
            if (kind == MessageRouter.Kind.FINANCE) {
                return finance(text);
            }
            return "";
        } catch (Exception e) {
            BotLog.w(context, "tool.context.fail", "实时工具失败 mode=" + kind + " error=" + e.getMessage());
            return "实时工具失败：" + e.getMessage();
        }
    }

    private static String weather(String text) throws Exception {
        String city = extractCity(text);
        JSONObject geo = new JSONObject(getUtf8("https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="
                + enc(city), 8000));
        JSONArray results = geo.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return "天气工具：没有找到地点 " + city;
        }
        JSONObject first = results.getJSONObject(0);
        double lat = first.getDouble("latitude");
        double lon = first.getDouble("longitude");
        String name = first.optString("name", city);
        String country = first.optString("country", "");
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=3";
        JSONObject data = new JSONObject(getUtf8(url, 8000));
        JSONObject current = data.getJSONObject("current");
        JSONObject daily = data.getJSONObject("daily");
        StringBuilder out = new StringBuilder();
        out.append("天气工具：").append(name);
        if (!country.isEmpty()) {
            out.append(" ").append(country);
        }
        out.append("\n当前：")
                .append(current.optDouble("temperature_2m")).append("℃，体感 ")
                .append(current.optDouble("apparent_temperature")).append("℃，湿度 ")
                .append(current.optInt("relative_humidity_2m")).append("%，风速 ")
                .append(current.optDouble("wind_speed_10m")).append("km/h，")
                .append(weatherCode(current.optInt("weather_code")));
        JSONArray times = daily.optJSONArray("time");
        JSONArray max = daily.optJSONArray("temperature_2m_max");
        JSONArray min = daily.optJSONArray("temperature_2m_min");
        JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        JSONArray code = daily.optJSONArray("weather_code");
        if (times != null) {
            out.append("\n三天预报：");
            for (int i = 0; i < Math.min(3, times.length()); i++) {
                out.append("\n").append(times.optString(i)).append(" ")
                        .append(weatherCode(code == null ? 0 : code.optInt(i)))
                        .append(" ").append(min == null ? "" : min.optDouble(i)).append("-")
                        .append(max == null ? "" : max.optDouble(i)).append("℃")
                        .append(" 降雨概率").append(rain == null ? 0 : rain.optInt(i)).append("%");
            }
        }
        return out.toString();
    }

    private static String news(String text) throws Exception {
        String compact = clean(text);
        if (compact.contains("微博")) {
            String hot = weiboHot();
            if (!hot.isEmpty()) {
                return hot;
            }
        }
        if (compact.contains("百度") || compact.contains("热搜") || compact.contains("热点") || compact.contains("热榜")) {
            String hot = baiduHot();
            if (!hot.isEmpty()) {
                return hot;
            }
        }
        String query = compact.replaceAll(".*?(新闻|热点|热搜|头条|快讯|资讯)", "").trim();
        if (query.isEmpty()) {
            query = "今日新闻";
        }
        String rss = getUtf8("https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q=" + enc(query), 10000);
        Matcher item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(rss);
        StringBuilder out = new StringBuilder("新闻工具：").append(query);
        int count = 0;
        while (item.find() && count < 6) {
            String block = item.group(1);
            String title = xmlText(block, "title");
            String source = xmlText(block, "source");
            if (!title.isEmpty()) {
                out.append("\n").append(++count).append(". ").append(title.replaceAll(" - .*$", ""));
                if (!source.isEmpty()) {
                    out.append(" / ").append(source);
                }
            }
        }
        if (count == 0) {
            return baiduHot();
        }
        return out.toString();
    }

    private static String finance(String text) throws Exception {
        String value = clean(text);
        String metal = metalGramPrices(value);
        if (!metal.isEmpty()) {
            return metal;
        }
        String symbol = yahooSymbol(value);
        if (!symbol.isEmpty()) {
            return yahooQuote(symbol);
        }
        String stockCode = extractChinaStock(value);
        if (!stockCode.isEmpty()) {
            return tencentQuote(stockCode);
        }
        String overview = marketOverview(value);
        if (!overview.isEmpty()) {
            return overview;
        }
        return yahooQuote("BTC-USD") + "\n" + yahooQuote("ETH-USD");
    }

    private static String metalGramPrices(String text) throws Exception {
        String value = clean(text);
        String upper = value.toUpperCase(Locale.ROOT);
        boolean allMetals = matchesAny(value,
                "贵金属", "贵金属克价", "金属克价", "金银铂钯", "金银铜", "金银价格");
        boolean genericGram = matchesAny(value, "克价", "每克", "一克", "多少钱一克");
        boolean goldRetail = matchesAny(value,
                "金店", "回收价", "回收金", "回收黄金", "黄金回收", "今日金价", "实时金价",
                "金价查询", "金价多少", "金价一克", "黄金多少钱", "金多少钱一克",
                "足金", "足金999", "足金9999", "千足金", "万足金", "金饰", "饰金",
                "投资金条", "银行金条", "周大福", "周生生", "老凤祥", "老庙", "六福", "菜百", "中国黄金");
        boolean gold = allMetals || goldRetail
                || matchesAny(value, "黄金", "金价", "金条", "现货黄金", "伦敦金", "沪金")
                || upper.contains("GOLD") || upper.contains("XAU") || upper.contains("AU999");
        boolean silver = allMetals
                || matchesAny(value, "白银", "银价", "银条", "银饰", "银多少钱", "银子多少钱", "现货白银", "伦敦银", "沪银")
                || upper.contains("SILVER") || upper.contains("XAG") || upper.contains("AG999");
        boolean platinum = allMetals
                || matchesAny(value, "铂金", "白金", "铂价", "铂多少钱", "铂金多少钱", "白金多少钱", "铂950", "铂990", "铂999")
                || upper.contains("PLATINUM") || upper.contains("XPT") || upper.contains("PT950") || upper.contains("PT990") || upper.contains("PT999");
        boolean palladium = allMetals
                || matchesAny(value, "钯金", "钯价", "钯多少钱", "钯金多少钱", "钯950", "钯990", "钯999")
                || upper.contains("PALLADIUM") || upper.contains("XPD") || upper.contains("PD950") || upper.contains("PD990") || upper.contains("PD999");
        boolean copper = matchesAny(value, "铜价", "铜", "黄铜", "伦铜", "沪铜", "电解铜", "铜期货") || upper.contains("COPPER");
        if (genericGram && !gold && !silver && !platinum && !palladium && !copper) {
            gold = true;
            silver = true;
            platinum = true;
            palladium = true;
        }
        if (!gold && !silver && !platinum && !palladium && !copper) {
            return "";
        }
        double usdCny = yahooPrice("USDCNY=X");
        StringBuilder out = new StringBuilder("贵金属工具：");
        boolean any = false;
        if (gold) {
            any |= appendTroyOunceMetal(out, "黄金", "GC=F", usdCny);
        }
        if (silver) {
            any |= appendTroyOunceMetal(out, "白银", "SI=F", usdCny);
        }
        if (platinum) {
            any |= appendTroyOunceMetal(out, "铂金", "PL=F", usdCny);
        }
        if (palladium) {
            any |= appendTroyOunceMetal(out, "钯金", "PA=F", usdCny);
        }
        if (copper) {
            any |= appendCopper(out, usdCny);
        }
        if (!any) {
            return "";
        }
        out.append("\n换算：按 1 金衡盎司=31.1035 克，USD/CNY=")
                .append(fmt(usdCny))
                .append(" 粗算；金店/品牌零售、回收价、工费和税费会在这个基准上浮动。");
        return out.toString();
    }

    private static boolean appendTroyOunceMetal(StringBuilder out, String name, String symbol, double usdCny) throws Exception {
        Quote quote = yahooQuoteData(symbol);
        if (Double.isNaN(quote.price)) {
            return false;
        }
        double cnyGram = quote.price * usdCny / TROY_OUNCE_GRAMS;
        out.append("\n").append(name)
                .append("：").append(fmt(quote.price)).append(" 美元/盎司")
                .append("，约 ").append(fmt(cnyGram)).append(" 元/克")
                .append("，涨跌 ").append(signed(quote.change))
                .append("（").append(signed(quote.percent)).append("%）");
        return true;
    }

    private static boolean appendCopper(StringBuilder out, double usdCny) throws Exception {
        Quote quote = yahooQuoteData("HG=F");
        if (Double.isNaN(quote.price)) {
            return false;
        }
        double cnyGram = quote.price * usdCny / POUND_GRAMS;
        out.append("\n铜：").append(fmt(quote.price)).append(" 美元/磅")
                .append("，约 ").append(fmt(cnyGram)).append(" 元/克")
                .append("，约 ").append(fmt(cnyGram * 1000.0)).append(" 元/公斤")
                .append("，涨跌 ").append(signed(quote.change))
                .append("（").append(signed(quote.percent)).append("%）");
        return true;
    }

    private static String weiboHot() throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://weibo.com/ajax/side/hotSearch", 10000));
        JSONArray list = data.optJSONObject("data") == null ? null : data.optJSONObject("data").optJSONArray("realtime");
        if (list == null || list.length() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder("微博热搜工具：");
        for (int i = 0; i < Math.min(10, list.length()); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null) {
                out.append("\n").append(i + 1).append(". ").append(item.optString("note"));
            }
        }
        return out.toString();
    }

    private static String baiduHot() throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://top.baidu.com/api/board?platform=wise&tab=realtime", 10000));
        JSONArray list = data.optJSONObject("data") == null ? null : data.optJSONObject("data").optJSONArray("cards");
        if (list == null || list.length() == 0) {
            return "热点工具：未获取到热榜数据";
        }
        List<String> words = new ArrayList<>();
        collectHotWords(list, words);
        StringBuilder out = new StringBuilder("百度热搜工具：");
        for (int i = 0; i < Math.min(10, words.size()); i++) {
            out.append("\n").append(i + 1).append(". ").append(words.get(i));
        }
        if (words.isEmpty()) {
            out.append("\n未解析到热榜标题");
        }
        return out.toString();
    }

    private static void collectHotWords(Object node, List<String> out) {
        if (node == null || out.size() >= 10) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && out.size() < 10; i++) {
                collectHotWords(array.opt(i), out);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject object = (JSONObject) node;
        appendHotWord(out, object.optString("query", ""));
        appendHotWord(out, object.optString("word", ""));
        appendHotWord(out, object.optString("title", ""));
        appendHotWord(out, object.optString("note", ""));
        if (out.size() >= 10) {
            return;
        }
        collectHotWords(object.optJSONArray("content"), out);
        collectHotWords(object.optJSONArray("cards"), out);
        collectHotWords(object.optJSONObject("data"), out);
    }

    private static void appendHotWord(List<String> out, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.startsWith("http")) {
            return;
        }
        for (String existing : out) {
            if (existing.equals(clean)) {
                return;
            }
        }
        out.add(clean);
    }

    private static String yahooQuote(String symbol) throws Exception {
        Quote quote = yahooQuoteData(symbol);
        String label = quote.name.isEmpty() || quote.name.equals(quote.symbol) ? quote.symbol : quote.name + "(" + quote.symbol + ")";
        return "金融工具：" + label
                + " 现价 " + fmt(quote.price)
                + "，涨跌 " + signed(quote.change)
                + "（" + signed(quote.percent) + "%）"
                + "，交易所 " + quote.exchange;
    }

    private static Quote yahooQuoteData(String symbol) throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://query1.finance.yahoo.com/v8/finance/chart/" + enc(symbol) + "?interval=1m&range=1d", 10000));
        JSONObject result = data.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = result.getJSONObject("meta");
        double price = meta.optDouble("regularMarketPrice", Double.NaN);
        double previous = meta.optDouble("chartPreviousClose", Double.NaN);
        double change = Double.isNaN(price) || Double.isNaN(previous) ? Double.NaN : price - previous;
        double pct = Double.isNaN(change) || previous == 0 ? Double.NaN : change / previous * 100.0;
        String name = meta.optString("shortName", "");
        if (name.isEmpty()) {
            name = meta.optString("longName", "");
        }
        String code = meta.optString("symbol", symbol);
        return new Quote(code, name, meta.optString("exchangeName", ""), price, change, pct);
    }

    private static double yahooPrice(String symbol) throws Exception {
        return yahooQuoteData(symbol).price;
    }

    private static String tencentQuote(String code) throws Exception {
        String prefix = code.startsWith("6") ? "sh" : "sz";
        return tencentQuote(prefix, code);
    }

    private static String tencentQuote(String prefix, String code) throws Exception {
        String raw = getBytesText("https://qt.gtimg.cn/q=" + prefix + code, 10000, Charset.forName("GB18030"));
        int first = raw.indexOf('"');
        int last = raw.lastIndexOf('"');
        if (first < 0 || last <= first) {
            return "金融工具：未取到 A 股行情 " + code;
        }
        String[] parts = raw.substring(first + 1, last).split("~");
        if (parts.length < 40) {
            return "金融工具：A 股行情格式异常 " + code;
        }
        return "金融工具：" + parts[1] + "(" + code + ") 最新 " + parts[3]
                + "，涨跌 " + parts[31] + "（" + parts[32] + "%）"
                + "，成交额 " + parts[37];
    }

    private static String marketOverview(String text) throws Exception {
        if (text.contains("A股") || text.contains("a股") || text.contains("股票")) {
            return joinQuotes(
                    tencentQuote("sh", "000001"),
                    tencentQuote("sz", "399001"),
                    tencentQuote("sz", "399006"));
        }
        if (text.contains("美股")) {
            return joinQuotes(
                    yahooQuote("^IXIC"),
                    yahooQuote("^GSPC"),
                    yahooQuote("^DJI"));
        }
        if (text.contains("港股")) {
            return joinQuotes(
                    yahooQuote("^HSI"),
                    yahooQuote("0700.HK"),
                    yahooQuote("9988.HK"));
        }
        return "";
    }

    private static String joinQuotes(String... quotes) {
        StringBuilder out = new StringBuilder();
        for (String quote : quotes) {
            if (quote == null || quote.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(quote.trim());
        }
        return out.toString();
    }

    private static String yahooSymbol(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("BTC") || text.contains("比特币")) return "BTC-USD";
        if (upper.contains("BNB") || text.contains("币安币")) return "BNB-USD";
        if (upper.contains("ETH") || text.contains("以太坊")) return "ETH-USD";
        if (upper.contains("SOL")) return "SOL-USD";
        if (upper.contains("XRP") || text.contains("瑞波")) return "XRP-USD";
        if (upper.contains("ADA") || text.contains("艾达币") || text.contains("卡尔达诺")) return "ADA-USD";
        if (upper.contains("TRX") || text.contains("波场")) return "TRX-USD";
        if (upper.contains("AVAX") || text.contains("雪崩")) return "AVAX-USD";
        if (upper.contains("LINK") || text.contains("链环")) return "LINK-USD";
        if (upper.contains("DOT") || text.contains("波卡")) return "DOT-USD";
        if (upper.contains("LTC") || text.contains("莱特币")) return "LTC-USD";
        if (upper.contains("BCH") || text.contains("比特现金")) return "BCH-USD";
        if (upper.contains("TON")) return "TON11419-USD";
        if (upper.contains("SHIB") || text.contains("柴犬币")) return "SHIB-USD";
        if (upper.contains("PEPE")) return "PEPE24478-USD";
        if (upper.contains("UNI") || text.contains("uniswap")) return "UNI7083-USD";
        if (upper.contains("MATIC")) return "MATIC-USD";
        if (upper.contains("POL")) return "POL28321-USD";
        if (upper.contains("ETC") || text.contains("以太经典")) return "ETC-USD";
        if (upper.contains("FIL") || text.contains("filecoin")) return "FIL-USD";
        if (upper.contains("ICP")) return "ICP-USD";
        if (upper.contains("ATOM")) return "ATOM-USD";
        if (upper.contains("NEAR")) return "NEAR-USD";
        if (upper.contains("ARB")) return "ARB11841-USD";
        if (upper.contains("APT")) return "APT21794-USD";
        if (upper.contains("SUI")) return "SUI20947-USD";
        if (upper.contains("OP")) return "OP-USD";
        if (upper.contains("AAVE")) return "AAVE-USD";
        if (upper.contains("OKB")) return "OKB-USD";
        if (upper.contains("DOGE")) return "DOGE-USD";
        if (upper.contains("USDCNH") || text.contains("离岸人民币")) return "USDCNH=X";
        if (upper.contains("USDCNY") || text.contains("美元兑人民币") || text.contains("美元人民币") || text.contains("人民币汇率")) return "USDCNY=X";
        if (upper.contains("EURCNY") || text.contains("欧元兑人民币") || text.contains("欧元人民币")) return "EURCNY=X";
        if (upper.contains("JPYCNY") || text.contains("日元兑人民币") || text.contains("日元人民币")) return "JPYCNY=X";
        if (upper.contains("HKDCNY") || text.contains("港币兑人民币") || text.contains("港币人民币") || text.contains("港元人民币")) return "HKDCNY=X";
        if (upper.contains("GBPCNY") || text.contains("英镑兑人民币") || text.contains("英镑人民币")) return "GBPCNY=X";
        if (upper.contains("AUDCNY") || text.contains("澳元兑人民币") || text.contains("澳元人民币")) return "AUDCNY=X";
        if (upper.contains("CADCNY") || text.contains("加元兑人民币") || text.contains("加元人民币")) return "CADCNY=X";
        if (upper.contains("SGDCNY") || text.contains("新币兑人民币") || text.contains("新加坡元人民币")) return "SGDCNY=X";
        if (upper.contains("CHFCNY") || text.contains("瑞郎兑人民币") || text.contains("瑞郎人民币")) return "CHFCNY=X";
        if (text.contains("黄金") || upper.contains("GOLD")) return "GC=F";
        if (text.contains("白银") || text.contains("银价") || upper.contains("SILVER")) return "SI=F";
        if (text.contains("铂金") || text.contains("白金") || upper.contains("PLATINUM")) return "PL=F";
        if (text.contains("钯金") || upper.contains("PALLADIUM")) return "PA=F";
        if (text.contains("布伦特")) return "BZ=F";
        if (text.contains("天然气") || upper.contains("NATURALGAS")) return "NG=F";
        if (text.contains("汽油")) return "RB=F";
        if (text.contains("原油") || text.contains("油价") || upper.contains("WTI")) return "CL=F";
        if (text.contains("铜价") || text.contains("伦铜") || text.contains("沪铜") || upper.contains("COPPER")) return "HG=F";
        if (text.contains("纳指") || text.contains("纳斯达克")) return "^IXIC";
        if (text.contains("标普")) return "^GSPC";
        if (text.contains("道指") || text.contains("道琼斯")) return "^DJI";
        if (text.contains("恒生") || text.contains("恒指")) return "^HSI";
        if (text.contains("富时中国A50") || text.contains("a50") || text.contains("A50")) return "XIN9.FGI";
        if (text.contains("特斯拉")) return "TSLA";
        if (text.contains("英伟达") || text.contains("辉达")) return "NVDA";
        if (text.contains("超微") || text.contains("AMD")) return "AMD";
        if (text.contains("苹果")) return "AAPL";
        if (text.contains("微软")) return "MSFT";
        if (text.contains("谷歌")) return "GOOGL";
        if (text.contains("亚马逊")) return "AMZN";
        if (text.contains("脸书") || text.contains("meta")) return "META";
        if (text.contains("阿里巴巴") || text.contains("阿里")) return "BABA";
        if (text.contains("拼多多")) return "PDD";
        if (text.contains("腾讯")) return "0700.HK";
        if (text.contains("小米")) return "1810.HK";
        if (text.contains("美团")) return "3690.HK";
        Matcher ticker = Pattern.compile("\\b(NVDA|AAPL|TSLA|MSFT|GOOGL|GOOG|AMZN|META|AMD|NFLX|BABA|PDD|COIN|MSTR|TSM|ASML|ARM|INTC|ORCL|SHOP|PLTR|SMCI|QQQ|SPY|DIA)\\b").matcher(upper);
        if (ticker.find()) {
            return ticker.group(1);
        }
        return "";
    }

    private static String extractChinaStock(String text) {
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)").matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String extractCity(String text) {
        String value = clean(text).replace("天气", "").replace("预报", "").replace("气温", "").trim();
        Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5]{2,8})(?:的)?(?:天气|气温|温度|会不会下雨|下雨|预报)").matcher(text == null ? "" : text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return value.isEmpty() ? "深圳" : value;
    }

    private static String weatherCode(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code >= 95) return "雷雨";
        return "天气码" + code;
    }

    private static String xmlText(String block, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(block);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1)
                .replace("<![CDATA[", "")
                .replace("]]>", "")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim();
    }

    private static String getUtf8(String url, int timeoutMs) throws Exception {
        return getBytesText(url, timeoutMs, StandardCharsets.UTF_8);
    }

    private static String getBytesText(String url, int timeoutMs, Charset charset) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 VXBotAPK/1.0");
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + new String(out.toByteArray(), charset));
        }
        return new String(out.toByteArray(), charset);
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(" ", "").trim();
    }

    private static boolean matchesAny(String text, String... terms) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term != null && !term.isEmpty() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }
        double abs = Math.abs(value);
        if (abs >= 10.0) {
            return String.format(Locale.US, "%.2f", value);
        }
        if (abs >= 0.01) {
            return String.format(Locale.US, "%.4f", value);
        }
        if (abs >= 0.0001) {
            return String.format(Locale.US, "%.6f", value);
        }
        return String.format(Locale.US, "%.8f", value);
    }

    private static String signed(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }
        return (value >= 0 ? "+" : "-") + fmt(Math.abs(value));
    }

    private static final class Quote {
        final String symbol;
        final String name;
        final String exchange;
        final double price;
        final double change;
        final double percent;

        Quote(String symbol, String name, String exchange, double price, double change, double percent) {
            this.symbol = symbol == null ? "" : symbol;
            this.name = name == null ? "" : name;
            this.exchange = exchange == null ? "" : exchange;
            this.price = price;
            this.change = change;
            this.percent = percent;
        }
    }
}
