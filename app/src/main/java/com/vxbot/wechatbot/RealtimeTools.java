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
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
            if (kind == MessageRouter.Kind.SPORTS) {
                return sports(text);
            }
            if (kind == MessageRouter.Kind.UTILITY) {
                return utility(text);
            }
            return "";
        } catch (Exception e) {
            BotLog.w(context, "tool.context.fail", "实时工具失败 mode=" + kind + " error=" + e.getMessage());
            return "实时工具失败：" + e.getMessage();
        }
    }

    public static String webSearch(Context context, String query) {
        String value = cleanWebQuery(query);
        if (value.isEmpty()) {
            return "实时工具失败：搜索词为空";
        }
        try {
            StringBuilder out = new StringBuilder("网页搜索工具：").append(value);
            int resultCount = appendBingWebResults(out, value, 6);
            if (looksTimeSensitiveQuery(value)) {
                resultCount += appendGoogleNewsResults(out, value, 4);
            }
            if (resultCount == 0) {
                resultCount += appendWikipediaResults(out, value, 4);
            }
            if (resultCount == 0) {
                return "实时工具失败：没有找到与“" + value + "”相关的可靠结果";
            }
            BotLog.i(context, "tool.web_search.done", "网页搜索完成 query=" + value
                    + " results=" + resultCount
                    + " chars=" + out.length());
            return out.toString();
        } catch (Exception e) {
            BotLog.w(context, "tool.web_search.fail", "网页搜索失败 query=" + value
                    + " error=" + e.getMessage());
            return "实时工具失败：" + e.getMessage();
        }
    }

    private static int appendBingWebResults(StringBuilder out, String query, int limit) {
        try {
            String markdown = getUtf8("https://r.jina.ai/http://www.bing.com/search?q=" + enc(query), 15000);
            Matcher matcher = Pattern.compile(
                    "(?ms)^\\d+\\.\\s+##\\s+\\[(.+?)\\]\\((https?://[^\\n]+)\\)\\s*\\n\\n(.*?)(?=^\\d+\\.\\s+##\\s+\\[|\\z)")
                    .matcher(markdown);
            int count = 0;
            while (matcher.find() && count < limit) {
                String title = cleanMarkdownText(matcher.group(1));
                String snippet = cleanMarkdownText(matcher.group(3));
                if (title.isEmpty() || snippet.isEmpty()) {
                    continue;
                }
                out.append("\n").append(++count).append(". ").append(title);
                out.append("\n摘要：").append(limitText(snippet, 360));
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int appendGoogleNewsResults(StringBuilder out, String query, int limit) {
        try {
            String rss = getUtf8("https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q="
                    + enc(query), 12000);
            Matcher item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(rss);
            int count = 0;
            while (item.find() && count < limit) {
                String block = item.group(1);
                String title = xmlText(block, "title").replaceAll(" - .*$", "").trim();
                String source = xmlText(block, "source");
                String date = xmlText(block, "pubDate");
                if (title.isEmpty()) {
                    continue;
                }
                out.append("\n新闻").append(++count).append(". ").append(title);
                if (!source.isEmpty()) {
                    out.append(" / ").append(source);
                }
                if (!date.isEmpty()) {
                    out.append(" / ").append(date);
                }
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int appendWikipediaResults(StringBuilder out, String query, int limit) {
        try {
            JSONObject data = new JSONObject(getUtf8(
                    "https://zh.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit="
                            + limit + "&srsearch=" + enc(query), 12000));
            JSONObject result = data.optJSONObject("query");
            JSONArray rows = result == null ? null : result.optJSONArray("search");
            if (rows == null) {
                return 0;
            }
            int count = 0;
            for (int i = 0; i < rows.length() && count < limit; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String title = cleanMarkdownText(row.optString("title", ""));
                String snippet = cleanMarkdownText(row.optString("snippet", ""));
                if (title.isEmpty() || snippet.isEmpty()) {
                    continue;
                }
                out.append("\n百科").append(++count).append(". ").append(title)
                        .append("：").append(limitText(snippet, 300));
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String cleanWebQuery(String query) {
        String value = clean(query)
                .replaceAll("(?i)\\[\\[?WEB_SEARCH[:：]", "")
                .replaceAll("\\s*\\]\\]?$", "")
                .replaceAll("(?i)^\\s*(?:请|麻烦)?(?:帮我)?(?:搜索|搜一下|查一下|查查|查询)\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private static boolean looksTimeSensitiveQuery(String query) {
        return matchesAny(query, "今天", "昨天", "现在", "当前", "目前", "最新", "最近", "刚刚",
                "实时", "新闻", "热搜", "价格", "行情", "汇率", "天气", "赛果", "发布", "上线",
                "更新", "政策", "规定", "2025", "2026");
    }

    private static String cleanMarkdownText(String text) {
        String value = text == null ? "" : text;
        value = value.replaceAll("<[^>]+>", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replace("**", "")
                .replace("__", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
        return value;
    }

    private static String limitText(String value, int maxChars) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
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
        if (matchesAny(compact, "早报", "晨报", "新闻早餐", "今日简报", "每日简报")) {
            return morningBriefing();
        }
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
            return morningBriefing();
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

    public static String morningBriefing() throws Exception {
        StringBuilder out = new StringBuilder("今日早报：");
        try {
            appendNumberedBlock(out, "微博热搜", hotWordsFromWeibo(5));
        } catch (Exception ignored) {
        }
        try {
            appendNumberedBlock(out, "百度热榜", hotWordsFromBaidu(5));
        } catch (Exception ignored) {
        }
        try {
            appendNumberedBlock(out, "焦点新闻", rssTitles("https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans", 6));
        } catch (Exception ignored) {
        }
        try {
            appendNumberedBlock(out, "财经科技", rssTitles("https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q="
                    + enc("财经 科技 OR AI"), 4));
        } catch (Exception ignored) {
        }
        if ("今日早报：".contentEquals(out)) {
            return baiduHot();
        }
        out.append("\n数据源：微博热搜、百度热榜、Google News RSS。");
        return out.toString();
    }

    private static String finance(String text) throws Exception {
        String value = clean(text);
        String metal = metalGramPrices(value);
        if (!metal.isEmpty()) {
            return metal;
        }
        if (looksLikeCryptoQuery(value)) {
            String crypto = cryptoQuote(value);
            if (!crypto.isEmpty()) {
                return crypto;
            }
            String query = cryptoSearchQuery(value);
            return "金融工具：没查到这个代币"
                    + (query.isEmpty() ? "" : "（" + query + "）")
                    + "，请换成币种英文简称、交易所交易对或合约地址。";
        }
        String symbol = yahooSymbol(value);
        if (!symbol.isEmpty()) {
            return yahooQuote(symbol);
        }
        String stockCode = extractChinaStock(value);
        if (!stockCode.isEmpty()) {
            return tencentQuote(stockCode);
        }
        String searchedStock = stockSearchQuote(value);
        if (!searchedStock.isEmpty()) {
            return searchedStock;
        }
        String overview = marketOverview(value);
        if (!overview.isEmpty()) {
            return overview;
        }
        return "金融工具：没识别到具体标的，请带上股票代码、币种简称、汇率对或市场名。";
    }

    private static boolean looksLikeCryptoQuery(String text) {
        String value = clean(text);
        String lower = value.toLowerCase(Locale.ROOT);
        return !extractContractAddress(value).isEmpty()
                || !cryptoAliasSymbol(value).isEmpty()
                || matchesAny(value, "虚拟币", "加密货币", "数字货币", "币价", "币圈", "代币", "链上", "合约地址", "合约", "市值", "24h", "bsc", "币安链", "dex", "dexscreener", "meme币")
                || value.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9]{1,24}(币|代币).*")
                || lower.matches(".*\\b(token|coin|crypto|usdt|usdc)\\b.*");
    }

    private static boolean looksLikeGenericCryptoOverview(String text) {
        String value = clean(text);
        String stripped = value.replaceAll("(?i)@?[A-Za-z0-9_\\-]{1,20}", "")
                .replaceAll("[\\s，。,.?!？！:：；;、]", "");
        return matchesAny(stripped, "虚拟币", "加密货币", "数字货币", "币圈", "币价", "代币行情")
                && cryptoSearchQuery(value).isEmpty();
    }

    private static String cryptoQuote(String text) throws Exception {
        String contract = extractContractAddress(text);
        if (!contract.isEmpty()) {
            String dex = dexScreenerTokenQuote(contract, text);
            return dex.isEmpty() ? "" : dex;
        }
        if (looksLikeGenericCryptoOverview(text)) {
            return cryptoMarketOverview();
        }
        String query = cryptoSearchQuery(text);
        if (query.isEmpty()) {
            return "";
        }
        String symbol = cryptoAliasSymbol(query);
        if (symbol.isEmpty()) {
            symbol = query.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        }
        if (!symbol.isEmpty()) {
            String binance = binanceTickerQuote(symbol, "https://api.binance.us", "Binance.US");
            if (!binance.isEmpty()) {
                return binance;
            }
            binance = binanceTickerQuote(symbol, "https://api.binance.com", "Binance");
            if (!binance.isEmpty()) {
                return binance;
            }
        }
        String dex = dexScreenerSearchQuote(query, text);
        if (!dex.isEmpty()) {
            return dex;
        }
        return coinGeckoQuote(text);
    }

    private static String cryptoMarketOverview() throws Exception {
        StringBuilder out = new StringBuilder("虚拟币行情工具：");
        int count = 0;
        for (String symbol : new String[]{"BTC", "ETH", "BNB", "SOL", "DOGE"}) {
            String quote = binanceTickerQuote(symbol, "https://api.binance.us", "Binance.US");
            if (quote.isEmpty()) {
                quote = binanceTickerQuote(symbol, "https://api.binance.com", "Binance");
            }
            if (quote.isEmpty()) {
                continue;
            }
            String line = quote.replaceFirst("^金融工具：", "");
            out.append("\n").append(++count).append(". ").append(line);
        }
        if (count > 0) {
            return out.toString();
        }
        return coinGeckoQuote("bitcoin") + "\n" + coinGeckoQuote("ethereum");
    }

    private static String binanceTickerQuote(String symbol, String baseUrl, String source) {
        String cleanSymbol = symbol == null ? "" : symbol.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (cleanSymbol.isEmpty()) {
            return "";
        }
        if (cleanSymbol.endsWith("USDT") && cleanSymbol.length() > 4) {
            cleanSymbol = cleanSymbol.substring(0, cleanSymbol.length() - 4);
        }
        String pair = cleanSymbol + "USDT";
        try {
            JSONObject data = new JSONObject(getUtf8(baseUrl + "/api/v3/ticker/24hr?symbol=" + enc(pair), 10000));
            if (data.has("code") && !data.has("lastPrice")) {
                return "";
            }
            double last = optJsonDouble(data, "lastPrice");
            if (Double.isNaN(last)) {
                return "";
            }
            double changePercent = optJsonDouble(data, "priceChangePercent");
            double high = optJsonDouble(data, "highPrice");
            double low = optJsonDouble(data, "lowPrice");
            double quoteVolume = optJsonDouble(data, "quoteVolume");
            return "金融工具：" + cleanSymbol + "/USDT 现价 $" + fmt(last)
                    + "，24h " + signed(changePercent) + "%"
                    + "，高低 $" + fmt(high) + " / $" + fmt(low)
                    + "，成交额 $" + compactMoney(quoteVolume)
                    + "，来源 " + source;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String coinGeckoQuote(String text) throws Exception {
        String query = cryptoSearchQuery(text);
        if (query.isEmpty()) {
            return "";
        }
        JSONObject search = new JSONObject(getUtf8("https://api.coingecko.com/api/v3/search?query=" + enc(query), 12000));
        JSONArray coins = search.optJSONArray("coins");
        if (coins == null || coins.length() == 0) {
            return "";
        }
        JSONObject best = chooseCoinGeckoCoin(coins, query);
        if (best == null) {
            return "";
        }
        String id = best.optString("id", "");
        if (id.isEmpty()) {
            return "";
        }
        JSONObject priceData = new JSONObject(getUtf8("https://api.coingecko.com/api/v3/simple/price?ids="
                + enc(id)
                + "&vs_currencies=usd,cny&include_24hr_change=true&include_market_cap=true", 12000));
        JSONObject price = priceData.optJSONObject(id);
        if (price == null) {
            return "";
        }
        String name = best.optString("name", id);
        String symbol = best.optString("symbol", "").toUpperCase(Locale.ROOT);
        double usd = price.optDouble("usd", Double.NaN);
        double cny = price.optDouble("cny", Double.NaN);
        double change = price.optDouble("usd_24h_change", Double.NaN);
        double marketCap = price.optDouble("usd_market_cap", Double.NaN);
        StringBuilder out = new StringBuilder("金融工具：").append(name);
        if (!symbol.isEmpty()) {
            out.append("(").append(symbol).append(")");
        }
        out.append(" 现价 ");
        if (!Double.isNaN(usd)) {
            out.append("$").append(fmt(usd));
        }
        if (!Double.isNaN(cny)) {
            if (!Double.isNaN(usd)) {
                out.append(" / ");
            }
            out.append("¥").append(fmt(cny));
        }
        if (!Double.isNaN(change)) {
            out.append("，24h ").append(signed(change)).append("%");
        }
        if (!Double.isNaN(marketCap) && marketCap > 0) {
            out.append("，市值 $").append(compactMoney(marketCap));
        }
        out.append("，来源 CoinGecko");
        return out.toString();
    }

    private static String dexScreenerTokenQuote(String contract, String text) throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://api.dexscreener.com/latest/dex/tokens/" + enc(contract), 12000));
        JSONArray pairs = data.optJSONArray("pairs");
        JSONObject best = chooseDexPair(pairs, text);
        return best == null ? "" : formatDexPair(best);
    }

    private static String dexScreenerSearchQuote(String query, String text) throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://api.dexscreener.com/latest/dex/search?q=" + enc(query), 12000));
        JSONArray pairs = data.optJSONArray("pairs");
        JSONObject best = chooseDexPair(pairs, text);
        return best == null ? "" : formatDexPair(best);
    }

    private static JSONObject chooseDexPair(JSONArray pairs, String text) {
        if (pairs == null || pairs.length() == 0) {
            return null;
        }
        String query = cryptoSearchQuery(text).toUpperCase(Locale.ROOT);
        String preferChain = preferredChain(text);
        JSONObject best = null;
        double bestScore = -1.0;
        for (int i = 0; i < pairs.length(); i++) {
            JSONObject pair = pairs.optJSONObject(i);
            if (pair == null) {
                continue;
            }
            JSONObject base = pair.optJSONObject("baseToken");
            String symbol = base == null ? "" : base.optString("symbol", "").toUpperCase(Locale.ROOT);
            String name = base == null ? "" : base.optString("name", "").toUpperCase(Locale.ROOT);
            double liquidity = nestedDouble(pair, "liquidity", "usd");
            double volume = nestedDouble(pair, "volume", "h24");
            double score = Math.max(liquidity, 0.0) + Math.max(volume, 0.0) * 0.2;
            if (!query.isEmpty() && (query.equals(symbol) || name.contains(query))) {
                score += 10_000_000_000.0;
            }
            if (!preferChain.isEmpty() && preferChain.equalsIgnoreCase(pair.optString("chainId", ""))) {
                score += 5_000_000_000.0;
            }
            if (score > bestScore) {
                bestScore = score;
                best = pair;
            }
        }
        return best;
    }

    private static String formatDexPair(JSONObject pair) {
        JSONObject base = pair.optJSONObject("baseToken");
        JSONObject quote = pair.optJSONObject("quoteToken");
        String name = base == null ? "" : base.optString("name", "");
        String symbol = base == null ? "" : base.optString("symbol", "");
        String quoteSymbol = quote == null ? "" : quote.optString("symbol", "");
        double price = optJsonDouble(pair, "priceUsd");
        double change = nestedDouble(pair, "priceChange", "h24");
        double volume = nestedDouble(pair, "volume", "h24");
        double liquidity = nestedDouble(pair, "liquidity", "usd");
        double marketCap = pair.optDouble("marketCap", pair.optDouble("fdv", Double.NaN));
        StringBuilder out = new StringBuilder("金融工具：");
        out.append(name.isEmpty() ? symbol : name);
        if (!symbol.isEmpty()) {
            out.append("(").append(symbol).append(")");
        }
        out.append(" 现价 $").append(fmt(price));
        if (!quoteSymbol.isEmpty() && !Double.isNaN(optJsonDouble(pair, "priceNative"))) {
            out.append(" / ").append(fmt(optJsonDouble(pair, "priceNative"))).append(" ").append(quoteSymbol);
        }
        out.append("，24h ").append(signed(change)).append("%");
        if (!Double.isNaN(volume)) {
            out.append("，24h成交 $").append(compactMoney(volume));
        }
        if (!Double.isNaN(liquidity)) {
            out.append("，流动性 $").append(compactMoney(liquidity));
        }
        if (!Double.isNaN(marketCap) && marketCap > 0) {
            out.append("，市值 $").append(compactMoney(marketCap));
        }
        out.append("，链 ").append(pair.optString("chainId", "--"))
                .append(" / ").append(pair.optString("dexId", "--"))
                .append("，来源 DexScreener");
        return out.toString();
    }

    private static JSONObject chooseCoinGeckoCoin(JSONArray coins, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONObject first = null;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) {
                continue;
            }
            if (first == null) {
                first = coin;
            }
            String id = coin.optString("id", "").toLowerCase(Locale.ROOT);
            String symbol = coin.optString("symbol", "").toLowerCase(Locale.ROOT);
            String name = coin.optString("name", "").toLowerCase(Locale.ROOT);
            if (q.equals(symbol) || q.equals(id) || q.equals(name)) {
                return coin;
            }
        }
        return first;
    }

    private static String cryptoSearchQuery(String text) {
        String value = clean(text);
        String alias = cryptoAliasSymbol(value);
        if (!alias.isEmpty()) {
            return alias.toLowerCase(Locale.ROOT);
        }
        String contract = extractContractAddress(value);
        if (!contract.isEmpty()) {
            return contract;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        Matcher ticker = Pattern.compile("\\b([A-Z0-9]{2,16})\\b").matcher(upper);
        while (ticker.find()) {
            String token = ticker.group(1);
            if (!isFinanceNoiseToken(token)) {
                return token.toLowerCase(Locale.ROOT);
            }
        }
        Matcher mixed = Pattern.compile("([A-Za-z][A-Za-z0-9_\\-]{1,24})\\s*(?:币|代币|coin|token)?", Pattern.CASE_INSENSITIVE).matcher(value);
        while (mixed.find()) {
            String token = mixed.group(1);
            if (!isFinanceNoiseToken(token.toUpperCase(Locale.ROOT))) {
                return token.toLowerCase(Locale.ROOT);
            }
        }
        Matcher chinese = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,24})(?:币|代币)").matcher(value);
        if (chinese.find()) {
            String token = chinese.group(1).trim();
            token = token.replaceAll("(看看|查询|查一下|一下|今天|现在|实时|最新|价格|行情|多少|虚拟|加密|数字)$", "");
            if (!token.isEmpty() && !matchesAny(token, "虚拟", "加密", "数字")) {
                return token;
            }
        }
        String compact = value.replaceAll("(?i)(@?慢一点|@?机器人|@?韵味|查询|查一下|看看|一下|今天|现在|实时|最新|币价|价格|行情|多少|多少钱|涨跌|市值|代币|虚拟币|加密货币|数字货币|币圈|coin|token|crypto|usdt|usdc)", "")
                .replaceAll("[\\s，。,.?!？！:：；;、]", "")
                .trim();
        return compact.length() >= 2 && compact.length() <= 24 ? compact : "";
    }

    private static boolean isFinanceNoiseToken(String token) {
        return matchesAny(token, "USD", "CNY", "RMB", "API", "HTTP", "HTTPS", "AI", "A股", "HK", "ETF", "24H", "TOKEN", "COIN", "CRYPTO", "USDT", "USDC", "BUSD", "BSC", "DEX");
    }

    private static String extractContractAddress(String text) {
        String value = clean(text);
        Matcher evm = Pattern.compile("\\b0x[a-fA-F0-9]{40}\\b").matcher(value);
        if (evm.find()) {
            return evm.group();
        }
        Matcher sol = Pattern.compile("\\b[1-9A-HJ-NP-Za-km-z]{32,44}\\b").matcher(value);
        while (sol.find()) {
            String token = sol.group();
            if (!isFinanceNoiseToken(token.toUpperCase(Locale.ROOT))) {
                return token;
            }
        }
        return "";
    }

    private static String preferredChain(String text) {
        String value = clean(text).toLowerCase(Locale.ROOT);
        if (matchesAny(value, "币安链", "bsc", "bnb chain", "bnbchain", "pancake")) {
            return "bsc";
        }
        if (matchesAny(value, "以太坊链", "eth链", "ethereum", "uniswap")) {
            return "ethereum";
        }
        if (matchesAny(value, "solana", "sol链", "raydium")) {
            return "solana";
        }
        if (matchesAny(value, "base链", "base")) {
            return "base";
        }
        if (matchesAny(value, "arbitrum", "arb链")) {
            return "arbitrum";
        }
        if (matchesAny(value, "polygon", "matic链")) {
            return "polygon";
        }
        return "";
    }

    private static String cryptoAliasSymbol(String text) {
        String value = clean(text);
        if (matchesAny(value, "比特币", "大饼") || hasTicker(value, "BTC")) return "BTC";
        if (matchesAny(value, "以太坊", "姨太") || hasTicker(value, "ETH")) return "ETH";
        if (matchesAny(value, "币安币") || hasTicker(value, "BNB")) return "BNB";
        if (matchesAny(value, "狗狗币") || hasTicker(value, "DOGE")) return "DOGE";
        if (matchesAny(value, "瑞波") || hasTicker(value, "XRP")) return "XRP";
        if (matchesAny(value, "波场") || hasTicker(value, "TRX")) return "TRX";
        if (matchesAny(value, "艾达币", "卡尔达诺") || hasTicker(value, "ADA")) return "ADA";
        if (matchesAny(value, "雪崩") || hasTicker(value, "AVAX")) return "AVAX";
        if (matchesAny(value, "波卡") || hasTicker(value, "DOT")) return "DOT";
        if (matchesAny(value, "莱特币") || hasTicker(value, "LTC")) return "LTC";
        if (matchesAny(value, "柴犬币") || hasTicker(value, "SHIB")) return "SHIB";
        if (matchesAny(value, "泰达币") || hasTicker(value, "USDT")) return "USDT";
        if (hasTicker(value, "USDC")) return "USDC";
        if (hasTicker(value, "SOL")) return "SOL";
        if (hasTicker(value, "PEPE")) return "PEPE";
        if (hasTicker(value, "TON")) return "TON";
        if (hasTicker(value, "LINK")) return "LINK";
        if (hasTicker(value, "BCH")) return "BCH";
        if (hasTicker(value, "UNI")) return "UNI";
        if (hasTicker(value, "MATIC")) return "MATIC";
        if (hasTicker(value, "POL")) return "POL";
        if (hasTicker(value, "ETC")) return "ETC";
        if (hasTicker(value, "FIL")) return "FIL";
        if (hasTicker(value, "ICP")) return "ICP";
        if (hasTicker(value, "ATOM")) return "ATOM";
        if (hasTicker(value, "NEAR")) return "NEAR";
        if (hasTicker(value, "ARB")) return "ARB";
        if (hasTicker(value, "APT")) return "APT";
        if (hasTicker(value, "SUI")) return "SUI";
        if (hasTicker(value, "OP")) return "OP";
        if (hasTicker(value, "AAVE")) return "AAVE";
        if (hasTicker(value, "OKB")) return "OKB";
        return "";
    }

    private static boolean hasTicker(String text, String symbol) {
        if (text == null || symbol == null || symbol.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?i)(?<![A-Z0-9])" + Pattern.quote(symbol) + "(?![A-Z0-9])").matcher(text).find();
    }

    private static String sports(String text) throws Exception {
        String value = clean(text);
        SportsLeague league = sportsLeague(value);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String dates = today.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + today.plusDays(7).format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = "https://site.api.espn.com/apis/site/v2/sports/" + league.path
                + "/scoreboard?dates=" + dates + "&limit=20";
        JSONObject data = new JSONObject(getUtf8(url, 12000));
        JSONArray events = data.optJSONArray("events");
        String leagueName = data.optJSONArray("leagues") == null || data.optJSONArray("leagues").length() == 0
                ? league.label : data.optJSONArray("leagues").optJSONObject(0).optString("name", league.label);
        if (events == null || events.length() == 0) {
            return "赛事工具：" + leagueName + " 最近一周没取到比赛。";
        }
        StringBuilder out = new StringBuilder("赛事工具：").append(leagueName);
        int count = 0;
        for (int i = 0; i < events.length() && count < 8; i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                continue;
            }
            JSONObject competition = event.optJSONArray("competitions") == null
                    || event.optJSONArray("competitions").length() == 0
                    ? null : event.optJSONArray("competitions").optJSONObject(0);
            if (competition == null) {
                continue;
            }
            JSONArray competitors = competition.optJSONArray("competitors");
            String home = "";
            String away = "";
            String homeScore = "";
            String awayScore = "";
            if (competitors != null) {
                for (int j = 0; j < competitors.length(); j++) {
                    JSONObject c = competitors.optJSONObject(j);
                    if (c == null) {
                        continue;
                    }
                    String name = c.optJSONObject("team") == null
                            ? c.optString("displayName", "")
                            : c.optJSONObject("team").optString("displayName", c.optJSONObject("team").optString("shortDisplayName", ""));
                    if ("home".equals(c.optString("homeAway"))) {
                        home = name;
                        homeScore = c.optString("score", "");
                    } else if ("away".equals(c.optString("homeAway"))) {
                        away = name;
                        awayScore = c.optString("score", "");
                    }
                }
            }
            JSONObject type = competition.optJSONObject("status") == null
                    ? null : competition.optJSONObject("status").optJSONObject("type");
            String state = type == null ? "" : type.optString("state", "");
            String detail = type == null ? "" : type.optString("shortDetail", type.optString("detail", ""));
            String time = formatBeijingTime(event.optString("date", ""));
            out.append("\n").append(++count).append(". ");
            if (!away.isEmpty() || !home.isEmpty()) {
                out.append(away.isEmpty() ? event.optString("shortName") : away)
                        .append(" vs ")
                        .append(home.isEmpty() ? "" : home);
            } else {
                out.append(event.optString("name", event.optString("shortName", "比赛")));
            }
            if ("in".equals(state) || "post".equals(state)) {
                out.append(" ").append(awayScore).append("-").append(homeScore);
            }
            if (!time.isEmpty()) {
                out.append(" / ").append(time);
            }
            if (!detail.isEmpty()) {
                out.append(" / ").append(detail);
            }
        }
        return out.toString();
    }

    private static String utility(String text) throws Exception {
        String value = clean(text);
        if (matchesAny(value, "北京时间", "现在几点", "几点了", "今天几号", "今天星期几", "今天周几", "当前时间", "当前日期")) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            return "本地工具：北京时间 " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA));
        }
        String conversion = unitConvert(value);
        if (!conversion.isEmpty()) {
            return conversion;
        }
        String expr = extractExpression(value);
        if (!expr.isEmpty()) {
            double result = new ExpressionParser(expr).parse();
            return "本地工具：" + expr + " = " + fmt(result);
        }
        return "本地工具：没识别到可计算内容。";
    }

    private static SportsLeague sportsLeague(String text) {
        String value = clean(text).toLowerCase(Locale.ROOT);
        if (matchesAny(value, "nba")) return new SportsLeague("basketball/nba", "NBA");
        if (matchesAny(value, "wnba")) return new SportsLeague("basketball/wnba", "WNBA");
        if (matchesAny(value, "nfl")) return new SportsLeague("football/nfl", "NFL");
        if (matchesAny(value, "nhl")) return new SportsLeague("hockey/nhl", "NHL");
        if (matchesAny(value, "mlb")) return new SportsLeague("baseball/mlb", "MLB");
        if (matchesAny(value, "欧冠", "champions")) return new SportsLeague("soccer/uefa.champions", "欧冠");
        if (matchesAny(value, "英超")) return new SportsLeague("soccer/eng.1", "英超");
        if (matchesAny(value, "西甲")) return new SportsLeague("soccer/esp.1", "西甲");
        if (matchesAny(value, "意甲")) return new SportsLeague("soccer/ita.1", "意甲");
        if (matchesAny(value, "德甲")) return new SportsLeague("soccer/ger.1", "德甲");
        if (matchesAny(value, "法甲")) return new SportsLeague("soccer/fra.1", "法甲");
        if (matchesAny(value, "世界杯", "world cup")) return new SportsLeague("soccer/fifa.world", "FIFA World Cup");
        if (matchesAny(value, "足球")) return new SportsLeague("soccer/fifa.world", "FIFA World Cup");
        if (matchesAny(value, "篮球")) return new SportsLeague("basketball/nba", "NBA");
        return new SportsLeague("soccer/fifa.world", "FIFA World Cup");
    }

    private static String formatBeijingTime(String iso) {
        try {
            ZonedDateTime time = ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.of("Asia/Shanghai"));
            return time.format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String unitConvert(String text) {
        Matcher matcher = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*([\\u4e00-\\u9fa5A-Za-z]+).*?(?:等于|换算|是多少|多少|转)\\s*([\\u4e00-\\u9fa5A-Za-z]+)").matcher(text);
        if (!matcher.find()) {
            matcher = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*([\\u4e00-\\u9fa5A-Za-z]+)\\s*(?:to|=|->)\\s*([\\u4e00-\\u9fa5A-Za-z]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (!matcher.find()) {
                return "";
            }
        }
        double value = Double.parseDouble(matcher.group(1));
        String from = normalizeUnit(matcher.group(2));
        String to = normalizeUnit(matcher.group(3));
        UnitType type = unitType(from);
        if (type == UnitType.UNKNOWN || type != unitType(to)) {
            return "";
        }
        double base = toBase(value, from, type);
        double result = fromBase(base, to, type);
        return "本地工具：" + fmt(value) + unitLabel(from) + " = " + fmt(result) + unitLabel(to);
    }

    private static String normalizeUnit(String raw) {
        String unit = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (unit.equals("千米")) return "公里";
        if (unit.equals("kg") || unit.equals("千克")) return "公斤";
        if (unit.equals("g")) return "克";
        if (unit.equals("l")) return "升";
        if (unit.equals("ml")) return "毫升";
        if (unit.equals("c") || unit.equals("摄氏度")) return "℃";
        if (unit.equals("f") || unit.equals("华氏度")) return "℉";
        if (unit.equals("rmb") || unit.equals("cny")) return "人民币";
        if (unit.equals("usd")) return "美元";
        return unit;
    }

    private static UnitType unitType(String unit) {
        if (matchesAny(unit, "公里", "米", "厘米", "毫米")) return UnitType.LENGTH;
        if (matchesAny(unit, "吨", "公斤", "斤", "克")) return UnitType.WEIGHT;
        if (matchesAny(unit, "升", "毫升")) return UnitType.VOLUME;
        if (matchesAny(unit, "℃", "℉")) return UnitType.TEMPERATURE;
        return UnitType.UNKNOWN;
    }

    private static double toBase(double value, String unit, UnitType type) {
        if (type == UnitType.LENGTH) {
            if (unit.equals("公里")) return value * 1000.0;
            if (unit.equals("厘米")) return value / 100.0;
            if (unit.equals("毫米")) return value / 1000.0;
            return value;
        }
        if (type == UnitType.WEIGHT) {
            if (unit.equals("吨")) return value * 1000.0;
            if (unit.equals("斤")) return value * 0.5;
            if (unit.equals("克")) return value / 1000.0;
            return value;
        }
        if (type == UnitType.VOLUME) {
            return unit.equals("毫升") ? value / 1000.0 : value;
        }
        if (type == UnitType.TEMPERATURE) {
            return unit.equals("℉") ? (value - 32.0) * 5.0 / 9.0 : value;
        }
        return value;
    }

    private static double fromBase(double base, String unit, UnitType type) {
        if (type == UnitType.LENGTH) {
            if (unit.equals("公里")) return base / 1000.0;
            if (unit.equals("厘米")) return base * 100.0;
            if (unit.equals("毫米")) return base * 1000.0;
            return base;
        }
        if (type == UnitType.WEIGHT) {
            if (unit.equals("吨")) return base / 1000.0;
            if (unit.equals("斤")) return base / 0.5;
            if (unit.equals("克")) return base * 1000.0;
            return base;
        }
        if (type == UnitType.VOLUME) {
            return unit.equals("毫升") ? base * 1000.0 : base;
        }
        if (type == UnitType.TEMPERATURE) {
            return unit.equals("℉") ? base * 9.0 / 5.0 + 32.0 : base;
        }
        return base;
    }

    private static String unitLabel(String unit) {
        return unit;
    }

    private static String extractExpression(String text) {
        Matcher matcher = Pattern.compile("([-+*/×÷().\\d\\s]+)").matcher(text);
        String best = "";
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.length() > best.length() && candidate.matches(".*\\d.*") && candidate.matches(".*[+\\-*/×÷].*")) {
                best = candidate;
            }
        }
        return best.replace('×', '*').replace('÷', '/').replaceAll("\\s+", "");
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
        List<String> words = hotWordsFromWeibo(10);
        if (words.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("微博热搜工具：");
        for (int i = 0; i < words.size(); i++) {
            out.append("\n").append(i + 1).append(". ").append(words.get(i));
        }
        return out.toString();
    }

    private static String baiduHot() throws Exception {
        List<String> words = hotWordsFromBaidu(10);
        if (words.isEmpty()) {
            return "热点工具：未获取到热榜数据";
        }
        StringBuilder out = new StringBuilder("百度热搜工具：");
        for (int i = 0; i < words.size(); i++) {
            out.append("\n").append(i + 1).append(". ").append(words.get(i));
        }
        return out.toString();
    }

    private static List<String> hotWordsFromWeibo(int limit) throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://weibo.com/ajax/side/hotSearch", 10000));
        JSONArray list = data.optJSONObject("data") == null ? null : data.optJSONObject("data").optJSONArray("realtime");
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.length() && out.size() < limit; i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null) {
                appendHotWord(out, item.optString("note"));
            }
        }
        return out;
    }

    private static List<String> hotWordsFromBaidu(int limit) throws Exception {
        JSONObject data = new JSONObject(getUtf8("https://top.baidu.com/api/board?platform=wise&tab=realtime", 10000));
        JSONArray list = data.optJSONObject("data") == null ? null : data.optJSONObject("data").optJSONArray("cards");
        List<String> out = new ArrayList<>();
        collectHotWords(list, out, limit);
        return out;
    }

    private static List<String> rssTitles(String url, int limit) throws Exception {
        String rss = getUtf8(url, 10000);
        Matcher item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(rss);
        List<String> out = new ArrayList<>();
        while (item.find() && out.size() < limit) {
            String block = item.group(1);
            String title = xmlText(block, "title").replaceAll(" - .*$", "").trim();
            String source = xmlText(block, "source");
            if (!title.isEmpty()) {
                appendHotWord(out, source.isEmpty() ? title : title + " / " + source);
            }
        }
        return out;
    }

    private static void appendNumberedBlock(StringBuilder out, String title, List<String> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        out.append("\n").append(title).append("：");
        for (int i = 0; i < rows.size(); i++) {
            out.append("\n").append(i + 1).append(". ").append(rows.get(i));
        }
    }

    private static void collectHotWords(Object node, List<String> out) {
        collectHotWords(node, out, 10);
    }

    private static void collectHotWords(Object node, List<String> out, int limit) {
        if (node == null || out.size() >= limit) {
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && out.size() < limit; i++) {
                collectHotWords(array.opt(i), out, limit);
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
        if (out.size() >= limit) {
            return;
        }
        collectHotWords(object.optJSONArray("content"), out, limit);
        collectHotWords(object.optJSONArray("cards"), out, limit);
        collectHotWords(object.optJSONObject("data"), out, limit);
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
            return "金融工具：未取到行情 " + code;
        }
        String[] parts = raw.substring(first + 1, last).split("~");
        if (parts.length < 40) {
            return "金融工具：行情格式异常 " + code;
        }
        return "金融工具：" + parts[1] + "(" + code + ") 最新 " + parts[3]
                + "，涨跌 " + parts[31] + "（" + parts[32] + "%）"
                + "，成交额 " + parts[37];
    }

    private static String stockSearchQuote(String text) throws Exception {
        String query = stockSearchQuery(text);
        if (query.isEmpty()) {
            return "";
        }
        String raw = getUtf8("https://smartbox.gtimg.cn/s3/?q=" + enc(query) + "&t=all", 10000);
        int first = raw.indexOf('"');
        int last = raw.lastIndexOf('"');
        if (first < 0 || last <= first) {
            return "";
        }
        String[] rows = decodeUnicodeEscapes(raw.substring(first + 1, last)).split("\\^");
        StockCandidate candidate = chooseStockCandidate(rows, text);
        if (candidate == null) {
            return "";
        }
        if ("sh".equals(candidate.market) || "sz".equals(candidate.market) || "hk".equals(candidate.market)) {
            return tencentQuote(candidate.market, candidate.code);
        }
        if ("us".equals(candidate.market)) {
            String symbol = usYahooSymbol(candidate.code);
            return symbol.isEmpty() ? "" : yahooQuote(symbol);
        }
        return "";
    }

    private static StockCandidate chooseStockCandidate(String[] rows, String text) {
        String value = clean(text);
        StockCandidate first = null;
        StockCandidate firstChina = null;
        StockCandidate firstHongKong = null;
        StockCandidate firstUs = null;
        for (String row : rows) {
            String[] parts = row.split("~");
            if (parts.length < 3) {
                continue;
            }
            StockCandidate c = new StockCandidate(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim(), parts[2].trim());
            if (c.market.isEmpty() || c.code.isEmpty() || c.name.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = c;
            }
            if (firstChina == null && ("sh".equals(c.market) || "sz".equals(c.market))) {
                firstChina = c;
            }
            if (firstHongKong == null && "hk".equals(c.market)) {
                firstHongKong = c;
            }
            if (firstUs == null && "us".equals(c.market)) {
                firstUs = c;
            }
        }
        if (matchesAny(value, "港股", "港交所", "hk", "HK")) {
            return firstHongKong != null ? firstHongKong : first;
        }
        if (matchesAny(value, "美股", "纳斯达克", "纽交所", "NASDAQ", "NYSE", "us", "US")) {
            return firstUs != null ? firstUs : first;
        }
        if (matchesAny(value, "A股", "a股", "沪股", "深股", "上交所", "深交所")) {
            return firstChina != null ? firstChina : first;
        }
        return firstChina != null ? firstChina : first;
    }

    private static String stockSearchQuery(String text) {
        String value = clean(text);
        if (value.matches(".*(虚拟币|加密货币|数字货币|币价|币圈|代币|token|coin|crypto).*")) {
            return "";
        }
        String cleaned = value.replaceAll("(?i)@?慢一点|@?机器人|@?韵味", "")
                .replaceAll("(?i)(查询|查一下|看看|看一下|帮我看|分析|预测|现在|今天|实时|最新|股价|股票|个股|行情|价格|涨跌|走势|多少|多少钱|A股|a股|美股|港股|沪股|深股|纳斯达克|纽交所|上交所|深交所|hk|us|market)", "")
                .replaceAll("[\\s，。,.?!？！:：；;、]", "")
                .trim();
        if (cleaned.matches("\\d{6}") || cleaned.matches("(?i)[A-Z]{1,8}(\\.[A-Z]{1,4})?")) {
            return "";
        }
        return cleaned.length() >= 2 && cleaned.length() <= 30 ? cleaned : "";
    }

    private static String usYahooSymbol(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        int dot = value.indexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        return value.replaceAll("[^A-Z0-9.-]", "");
    }

    private static String decodeUnicodeEscapes(String raw) {
        if (raw == null || raw.indexOf("\\u") < 0) {
            return raw == null ? "" : raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' && i + 5 < raw.length() && raw.charAt(i + 1) == 'u') {
                String hex = raw.substring(i + 2, i + 6);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (Exception ignored) {
                }
            }
            out.append(ch);
        }
        return out.toString();
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
        if (upper.contains("USDT") || text.contains("泰达币")) return "USDT-USD";
        if (upper.contains("USDC")) return "USDC-USD";
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

    private static double nestedDouble(JSONObject object, String objectKey, String valueKey) {
        if (object == null) {
            return Double.NaN;
        }
        JSONObject nested = object.optJSONObject(objectKey);
        return nested == null ? Double.NaN : optJsonDouble(nested, valueKey);
    }

    private static double optJsonDouble(JSONObject object, String key) {
        if (object == null || key == null) {
            return Double.NaN;
        }
        Object value = object.opt(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                String text = ((String) value).trim();
                return text.isEmpty() ? Double.NaN : Double.parseDouble(text);
            } catch (Exception ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
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

    private static String compactMoney(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000_000.0) {
            return String.format(Locale.US, "%.2fT", value / 1_000_000_000_000.0);
        }
        if (abs >= 1_000_000_000.0) {
            return String.format(Locale.US, "%.2fB", value / 1_000_000_000.0);
        }
        if (abs >= 1_000_000.0) {
            return String.format(Locale.US, "%.2fM", value / 1_000_000.0);
        }
        return fmt(value);
    }

    private static String signed(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }
        return (value >= 0 ? "+" : "-") + fmt(Math.abs(value));
    }

    private enum UnitType {
        LENGTH,
        WEIGHT,
        VOLUME,
        TEMPERATURE,
        UNKNOWN
    }

    private static final class SportsLeague {
        final String path;
        final String label;

        SportsLeague(String path, String label) {
            this.path = path;
            this.label = label;
        }
    }

    private static final class ExpressionParser {
        private final String input;
        private int index;

        ExpressionParser(String input) {
            this.input = input == null ? "" : input;
        }

        double parse() {
            index = 0;
            double value = parseExpression();
            if (index < input.length()) {
                throw new IllegalArgumentException("表达式多余字符: " + input.substring(index));
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (index < input.length()) {
                char op = input.charAt(index);
                if (op != '+' && op != '-') {
                    break;
                }
                index++;
                double right = parseTerm();
                value = op == '+' ? value + right : value - right;
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (index < input.length()) {
                char op = input.charAt(index);
                if (op != '*' && op != '/') {
                    break;
                }
                index++;
                double right = parseFactor();
                if (op == '/') {
                    if (Math.abs(right) < 1e-12) {
                        throw new IllegalArgumentException("除数不能为 0");
                    }
                    value /= right;
                } else {
                    value *= right;
                }
            }
            return value;
        }

        private double parseFactor() {
            if (index >= input.length()) {
                throw new IllegalArgumentException("表达式不完整");
            }
            char ch = input.charAt(index);
            if (ch == '+') {
                index++;
                return parseFactor();
            }
            if (ch == '-') {
                index++;
                return -parseFactor();
            }
            if (ch == '(') {
                index++;
                double value = parseExpression();
                if (index >= input.length() || input.charAt(index) != ')') {
                    throw new IllegalArgumentException("括号未闭合");
                }
                index++;
                return value;
            }
            int start = index;
            while (index < input.length()) {
                char c = input.charAt(index);
                if ((c >= '0' && c <= '9') || c == '.') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("数字格式错误");
            }
            return Double.parseDouble(input.substring(start, index));
        }
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

    private static final class StockCandidate {
        final String market;
        final String code;
        final String name;

        StockCandidate(String market, String code, String name) {
            this.market = market == null ? "" : market;
            this.code = code == null ? "" : code;
            this.name = name == null ? "" : name;
        }
    }
}
