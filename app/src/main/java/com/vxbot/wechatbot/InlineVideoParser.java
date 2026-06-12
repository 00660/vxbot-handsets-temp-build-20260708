package com.vxbot.wechatbot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InlineVideoParser {
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s，。！？；;\"'<>]+|www\\.[^\\s，。！？；;\"'<>]+)", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1";
    private static final String PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
    private final Context context;
    private final int timeoutMs;

    InlineVideoParser(Context context, int timeoutMs) {
        this.context = context.getApplicationContext();
        this.timeoutMs = Math.max(5000, timeoutMs);
    }

    ParseInfo parse(String shareText) throws Exception {
        String url = extractUrl(shareText);
        if (url.isEmpty()) {
            throw new IllegalArgumentException("没有找到分享链接");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        BotLog.i(context, "video.inline.route", "使用 APK 内置解析器 url=" + compactUrl(url));
        if (containsAny(lower, "v.douyin.com", "iesdouyin.com", "douyin.com")) {
            return parseDouyin(url);
        }
        if (containsAny(lower, "v.kuaishou.com", "kuaishou.com")) {
            return parseKuaishou(url);
        }
        if (containsAny(lower, "xiaohongshu.com", "xhslink.com")) {
            return parseRedBook(url);
        }
        if (containsAny(lower, "bilibili.com", "b23.tv")) {
            return parseBilibili(url);
        }
        if (containsAny(lower, "weibo.com")) {
            return parseWeibo(url);
        }
        if (containsAny(lower, "weibo.cn")) {
            return parseLvzhou(url);
        }
        if (containsAny(lower, "x.com", "twitter.com", "t.co")) {
            return parseTwitter(url);
        }
        if (containsAny(lower, "v.qq.com", "m.v.qq.com")) {
            return parseQqVideo(url);
        }
        if (containsAny(lower, "tv.sohu.com", "my.tv.sohu.com")) {
            return parseSohu(url);
        }
        if (containsAny(lower, "tv.cctv.cn", "tv.cctv.com")) {
            return parseCctv(url);
        }
        if (containsAny(lower, "v.ixigua.com", "ixigua.com")) {
            return parseXigua(url);
        }
        if (containsAny(lower, "h5.pipix.com", "pipix.com")) {
            return parsePipixia(url);
        }
        if (containsAny(lower, "h5.pipigx.com", "pipigx.com")) {
            return parsePipigaoxiao(url);
        }
        if (containsAny(lower, "share.huoshan.com", "huoshan.com")) {
            return parseHuoshan(url);
        }
        if (containsAny(lower, "isee.weishi.qq.com")) {
            return parseWeishi(url);
        }
        if (containsAny(lower, "share.xiaochuankeji.cn")) {
            return parseZuiyou(url);
        }
        if (containsAny(lower, "xspshare.baidu.com")) {
            return parseQuanmin(url);
        }
        if (containsAny(lower, "www.pearvideo.com", "pearvideo.com")) {
            return parseLishipin(url);
        }
        if (containsAny(lower, "v.huya.com", "huya.com")) {
            return parseHuya(url);
        }
        if (containsAny(lower, "www.acfun.cn", "acfun.cn")) {
            return parseAcfun(url);
        }
        if (containsAny(lower, "doupai.cc")) {
            return parseDoupai(url);
        }
        if (containsAny(lower, "meipai.com")) {
            return parseMeipai(url);
        }
        if (containsAny(lower, "kg.qq.com")) {
            return parseQuanminKge(url);
        }
        if (containsAny(lower, "6.cn")) {
            return parseSixroom(url);
        }
        if (containsAny(lower, "xinpianchang.com")) {
            return parseXinpianchang(url);
        }
        if (containsAny(lower, "haokan.baidu.com", "haokan.hao123.com")) {
            return parseHaokan(url);
        }
        throw new IllegalArgumentException("暂不支持这个链接平台");
    }

    private ParseInfo parseDouyin(String shareUrl) throws Exception {
        URI uri = URI.create(shareUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains("v.douyin.com")) {
            HttpResult redirect = request("GET", shareUrl, headers(DEFAULT_UA), null, false);
            String location = redirect.location;
            if (location.isEmpty()) {
                throw new IllegalStateException("抖音短链没有返回跳转地址");
            }
            URI target = URI.create(resolve(shareUrl, location));
            String id = extractDouyinId(target);
            if (target.getHost() != null && target.getHost().contains("ixigua.com")) {
                return parseXiguaById(id);
            }
            return parseDouyinById(id);
        }
        return parseDouyinById(extractDouyinId(uri));
    }

    private ParseInfo parseDouyinById(String videoId) throws Exception {
        if (videoId.isEmpty()) {
            throw new IllegalArgumentException("抖音作品 ID 为空");
        }
        String pageUrl = "https://www.iesdouyin.com/share/video/" + videoId;
        String html = request("GET", pageUrl, headers(DEFAULT_UA), null, true).body;
        boolean isNote = false;
        String canonical = match(html, "<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        if (canonical.contains("/note/")) {
            isNote = true;
        }
        JSONObject data = null;
        if (isNote) {
            String webId = "75" + fixedNumber(15);
            String api = "https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/?reflow_source=reflow_page&web_id="
                    + webId + "&device_id=" + webId + "&aweme_ids=%5B" + videoId
                    + "%5D&request_source=200&a_bogus=" + randomSeq(64);
            JSONObject root = new JSONObject(request("GET", api, headers(DEFAULT_UA), null, true).body);
            data = objectAt(root, "aweme_details", "0");
            if (data == null) {
                isNote = false;
            }
        }
        if (!isNote) {
            JSONObject root = scriptJson(html, "window\\._ROUTER_DATA\\s*=\\s*(.*?)</script>");
            JSONObject loader = objectAt(root, "loaderData");
            JSONObject page = loader == null ? null : loader.optJSONObject("video_(id)/page");
            data = objectAt(page, "videoInfoRes", "item_list", "0");
        }
        if (data == null) {
            throw new IllegalStateException("抖音作品数据为空");
        }
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "desc");
        out.authorUid = stringAt(data, "author", "sec_uid");
        out.authorName = stringAt(data, "author", "nickname");
        out.authorAvatar = stringAt(data, "author", "avatar_thumb", "url_list", "0");
        JSONArray images = arrayAt(data, "images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String imageUrl = preferNonWebp(arrayAt(image, "url_list"));
                if (!imageUrl.isEmpty()) {
                    ImageItem item = new ImageItem();
                    item.url = imageUrl;
                    item.livePhotoUrl = stringAt(image, "video", "play_addr", "url_list", "0");
                    out.images.add(item);
                }
            }
        }
        out.coverUrl = preferNonWebp(arrayAt(data, "video", "cover", "url_list"));
        if (out.images.isEmpty()) {
            out.videoUrl = stringAt(data, "video", "play_addr", "url_list", "0").replace("playwm", "play");
            out.musicUrl = "";
            if (!out.videoUrl.isEmpty()) {
                out.videoUrl = redirectUrl(out.videoUrl, DEFAULT_UA);
            }
        } else {
            out.musicUrl = stringAt(data, "video", "play_addr", "uri");
        }
        ensureMedia(out, "抖音");
        return out;
    }

    private ParseInfo parseKuaishou(String shareUrl) throws Exception {
        String location = followKuaishouRedirect(shareUrl);
        location = location.replace("/fw/long-video/", "/fw/photo/");
        String html = request("GET", location, headers(DEFAULT_UA), null, true).body;
        JSONObject root = scriptJson(html, "window\\.INIT_STATE\\s*=\\s*(.*?)</script>");
        JSONObject item = null;
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            JSONObject candidate = root.optJSONObject(keys.next());
            if (candidate != null && candidate.has("result") && candidate.has("photo")) {
                item = candidate;
                break;
            }
        }
        if (item == null || item.optInt("result") != 1) {
            throw new IllegalStateException("快手作品数据为空");
        }
        JSONObject photo = item.optJSONObject("photo");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(photo, "caption");
        out.authorName = stringAt(photo, "userName");
        out.authorAvatar = stringAt(photo, "headUrl");
        out.videoUrl = stringAt(photo, "mainMvUrls", "0", "url");
        out.coverUrl = stringAt(photo, "coverUrls", "0", "url");
        String cdn = stringAt(photo, "ext_params", "atlas", "cdn", "0");
        JSONArray list = arrayAt(photo, "ext_params", "atlas", "list");
        if (!cdn.isEmpty() && list != null) {
            for (int i = 0; i < list.length(); i++) {
                ImageItem image = new ImageItem();
                image.url = "https://" + cdn + "/" + list.optString(i);
                out.images.add(image);
            }
        }
        ensureMedia(out, "快手");
        return out;
    }

    private ParseInfo parseRedBook(String shareUrl) throws Exception {
        String html = request("GET", shareUrl, headers(PC_UA), null, true).body;
        JSONObject root = scriptJson(html, "window\\.__INITIAL_STATE__\\s*=\\s*(.*?)</script>");
        String noteId = stringAt(root, "note", "currentNoteId");
        JSONObject data = objectAt(root, "note", "noteDetailMap", noteId, "note");
        if (data == null) {
            throw new IllegalStateException("小红书作品数据为空");
        }
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "title");
        out.authorUid = stringAt(data, "user", "userId");
        out.authorName = stringAt(data, "user", "nickname");
        out.authorAvatar = stringAt(data, "user", "avatar");
        out.videoUrl = stringAt(data, "video", "media", "stream", "h264", "0", "masterUrl");
        out.coverUrl = stringAt(data, "imageList", "0", "urlDefault");
        if (out.videoUrl.isEmpty()) {
            JSONArray images = arrayAt(data, "imageList");
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject image = images.optJSONObject(i);
                    String imageUrl = stringAt(image, "urlDefault");
                    if (imageUrl.isEmpty()) {
                        continue;
                    }
                    ImageItem item = new ImageItem();
                    item.url = normalizeRedBookImage(imageUrl);
                    if (image != null && image.optBoolean("livePhoto")) {
                        JSONArray streams = arrayAt(image, "stream", "h264");
                        if (streams != null) {
                            for (int j = 0; j < streams.length(); j++) {
                                String live = stringAt(streams.optJSONObject(j), "masterUrl");
                                if (!live.isEmpty()) {
                                    item.livePhotoUrl = live;
                                }
                            }
                        }
                    }
                    out.images.add(item);
                }
            }
        }
        ensureMedia(out, "小红书");
        return out;
    }

    private ParseInfo parseBilibili(String shareUrl) throws Exception {
        String bvid = extractBvid(shareUrl);
        JSONObject view = new JSONObject(request("GET", "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid,
                headers(PC_UA, "Referer", "https://www.bilibili.com/"), null, true).body);
        if (view.optInt("code") != 0) {
            throw new IllegalStateException("B站接口错误: " + view.optString("message"));
        }
        JSONObject data = view.optJSONObject("data");
        String cid = firstNonEmpty(stringAt(data, "cid"), stringAt(data, "pages", "0", "cid"));
        if (cid.isEmpty() || "0".equals(cid)) {
            throw new IllegalStateException("B站 cid 为空");
        }
        BotLog.i(context, "video.bili.ids", "bvid=" + bvid + " cid=" + cid);
        JSONObject play = new JSONObject(request("GET",
                "https://api.bilibili.com/x/player/playurl?otype=json&fnver=0&fnval=0&qn=80&bvid=" + bvid
                        + "&cid=" + cid + "&platform=html5",
                headers(PC_UA, "Referer", "https://www.bilibili.com/"), null, true).body);
        if (play.optInt("code") != 0) {
            throw new IllegalStateException("B站播放接口错误: " + play.optString("message") + " cid=" + cid);
        }
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "title");
        out.coverUrl = stringAt(data, "pic");
        out.authorUid = stringAt(data, "owner", "mid");
        out.authorName = stringAt(data, "owner", "name");
        out.authorAvatar = stringAt(data, "owner", "face");
        out.videoUrl = stringAt(play, "data", "durl", "0", "url");
        ensureMedia(out, "B站");
        return out;
    }

    private ParseInfo parseWeibo(String shareUrl) throws Exception {
        URI uri = URI.create(shareUrl);
        String url = shareUrl;
        if (url.contains("show?fid=")) {
            return parseWeiboVideo(query(uri, "fid"));
        }
        if (url.contains("/tv/show/")) {
            return parseWeiboVideo(uri.getPath().replace("/tv/show/", ""));
        }
        String[] parts = trimSlash(uri.getPath()).split("/");
        if (parts.length >= 2) {
            String postId = parts[parts.length - 1];
            try {
                JSONObject root = new JSONObject(request("GET", "https://m.weibo.cn/statuses/show?id=" + postId,
                        headers("Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0 Mobile/15E148 Safari/604.1",
                                "Referer", "https://m.weibo.cn/",
                                "X-Requested-With", "XMLHttpRequest"), null, true).body);
                JSONObject data = root.optJSONObject("data");
                if (data != null) {
                    return parseWeiboPostData(data);
                }
            } catch (Exception ignored) {
            }
            String html = request("GET", shareUrl, headers(PC_UA), null, true).body;
            String json = match(html, "\\$render_data\\s*=\\s*(.*?)\\[0\\]", Pattern.DOTALL);
            if (!json.isEmpty()) {
                JSONArray arr = new JSONArray(json + "[0]");
                JSONObject status = objectAt(arr.optJSONObject(0), "status");
                return parseWeiboStatus(status);
            }
        }
        throw new IllegalStateException("微博链接格式不支持");
    }

    private ParseInfo parseWeiboVideo(String videoId) throws Exception {
        String body = "data={\"Component_Play_Playinfo\":{\"oid\":\"" + videoId + "\"}}";
        JSONObject root = new JSONObject(request("POST", "https://h5.video.weibo.com/api/component?page=/show/" + videoId,
                headers(DEFAULT_UA,
                        "Referer", "https://h5.video.weibo.com/show/" + videoId,
                        "Content-Type", "application/x-www-form-urlencoded",
                        "Cookie", "SUB=_2AkMXuScYf8NxqwJRmf8RzmnhaoxwzwDEieKh5dbDJRMxHRl-yT9jqhALtRB6PDkJ9w8OaqJAbsgjdEWtIcilcZxHG7rw"),
                body, true).body);
        JSONObject data = objectAt(root, "data", "Component_Play_Playinfo");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "title");
        out.coverUrl = prefixHttps(stringAt(data, "cover_image"));
        out.authorName = stringAt(data, "author");
        out.authorAvatar = prefixHttps(stringAt(data, "avatar"));
        JSONObject urls = objectAt(data, "urls");
        if (urls != null) {
            Iterator<String> keys = urls.keys();
            if (keys.hasNext()) {
                out.videoUrl = prefixHttps(urls.optString(keys.next()));
            }
        }
        ensureMedia(out, "微博视频");
        return out;
    }

    private ParseInfo parseWeiboPostData(JSONObject data) {
        return parseWeiboStatus(data);
    }

    private ParseInfo parseWeiboStatus(JSONObject data) {
        ParseInfo out = new ParseInfo();
        out.title = stripHtml(stringAt(data, "text"));
        out.authorName = stringAt(data, "user", "screen_name");
        out.authorAvatar = stringAt(data, "user", "avatar_large");
        JSONArray pics = arrayAt(data, "pics");
        if (pics != null) {
            for (int i = 0; i < pics.length(); i++) {
                JSONObject pic = pics.optJSONObject(i);
                String url = firstNonEmpty(
                        stringAt(pic, "large", "url"),
                        stringAt(pic, "original", "url"),
                        stringAt(pic, "bmiddle", "url"),
                        stringAt(pic, "url"));
                if (!url.isEmpty()) {
                    ImageItem item = new ImageItem();
                    item.url = url;
                    out.images.add(item);
                }
            }
        }
        return out;
    }

    private ParseInfo parseTwitter(String shareUrl) throws Exception {
        if (shareUrl.contains("t.co/")) {
            HttpResult redirect = request("GET", shareUrl, headers(DEFAULT_UA), null, false);
            if (!redirect.location.isEmpty()) {
                shareUrl = resolve(shareUrl, redirect.location);
            }
        }
        String tweetId = match(shareUrl, "(?:twitter\\.com|x\\.com)/[^/]+/status(?:es)?/(\\d+)", 0);
        if (tweetId.isEmpty()) {
            throw new IllegalArgumentException("无法提取推文 ID");
        }
        double tokenNum = (Double.parseDouble(tweetId) / 1e15d) * Math.PI;
        String token = Double.toString(tokenNum).replace("0", "").replace(".", "");
        JSONObject root = new JSONObject(request("GET",
                "https://cdn.syndication.twimg.com/tweet-result?id=" + tweetId + "&token=" + token,
                headers(PC_UA, "Accept", "application/json", "Referer", "https://platform.twitter.com/"), null, true).body);
        ParseInfo out = new ParseInfo();
        out.authorUid = stringAt(root, "user", "id_str");
        out.authorName = firstNonEmpty(stringAt(root, "user", "name"), stringAt(root, "user", "screen_name"));
        out.authorAvatar = stringAt(root, "user", "profile_image_url_https");
        out.title = stringAt(root, "text");
        JSONArray media = arrayAt(root, "mediaDetails");
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject item = media.optJSONObject(i);
                String type = stringAt(item, "type");
                if ("video".equals(type) || "animated_gif".equals(type)) {
                    out.coverUrl = stringAt(item, "media_url_https");
                    out.videoUrl = bestMp4(arrayAt(item, "video_info", "variants"));
                    break;
                }
            }
            if (out.videoUrl.isEmpty()) {
                for (int i = 0; i < media.length(); i++) {
                    JSONObject item = media.optJSONObject(i);
                    if ("photo".equals(stringAt(item, "type"))) {
                        String image = stringAt(item, "media_url_https");
                        if (!image.isEmpty()) {
                            ImageItem img = new ImageItem();
                            img.url = image;
                            out.images.add(img);
                        }
                    }
                }
            }
        }
        if (out.videoUrl.isEmpty()) {
            out.coverUrl = stringAt(root, "video", "poster");
            out.videoUrl = bestMp4(arrayAt(root, "video", "variants"));
        }
        if (out.coverUrl.isEmpty() && !out.images.isEmpty()) {
            out.coverUrl = out.images.get(0).url;
        }
        ensureMedia(out, "Twitter/X");
        return out;
    }

    private ParseInfo parseQqVideo(String shareUrl) throws Exception {
        URI uri = URI.create(shareUrl);
        String host = uri.getHost() == null ? "" : uri.getHost();
        String vid;
        if (host.contains("m.v.qq.com")) {
            vid = query(uri, "vid");
        } else {
            vid = match(uri.getPath(), "/x/(?:page|cover)/(?:[^/]+/)?(\\w+)\\.html", 0);
        }
        if (vid.isEmpty()) {
            throw new IllegalArgumentException("腾讯视频 ID 为空");
        }
        String body = request("GET", "https://vv.video.qq.com/getinfo?vids=" + vid + "&platform=101001&otype=json&defn=shd",
                headers(DEFAULT_UA), null, true).body;
        String json = body.replaceFirst("^QZOutputJson=", "").replaceFirst(";$", "");
        JSONObject root = new JSONObject(json);
        JSONObject vi = objectAt(root, "vl", "vi", "0");
        String base = stringAt(vi, "ul", "ui", "0", "url");
        String fn = stringAt(vi, "fn");
        String fvkey = stringAt(vi, "fvkey");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(vi, "ti");
        out.videoUrl = base.isEmpty() || fn.isEmpty() || fvkey.isEmpty() ? "" : base + fn + "?vkey=" + fvkey;
        out.coverUrl = "https://puui.qpic.cn/vpic_cover/" + vid + "/" + vid + "_hz.jpg/496";
        ensureMedia(out, "腾讯视频");
        return out;
    }

    private ParseInfo parseSohu(String shareUrl) throws Exception {
        String vid = match(shareUrl, "/v/([A-Za-z0-9+/=]+)\\.html", 0);
        if (!vid.isEmpty()) {
            String decoded = new String(Base64.getDecoder().decode(vid), StandardCharsets.UTF_8);
            vid = match(decoded, "/?us/\\d+/(\\d+)\\.shtml", 0);
        } else {
            vid = match(shareUrl, "/?us/\\d+/(\\d+)\\.shtml", 0);
        }
        if (vid.isEmpty()) {
            throw new IllegalArgumentException("搜狐视频 ID 为空");
        }
        JSONObject root = new JSONObject(request("GET",
                "https://api.tv.sohu.com/v4/video/info/" + vid + ".json?site=2&api_key=9854b2afa779e1a6bcdd07b217417549&sver=6.2.0",
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = root.optJSONObject("data");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "video_name");
        out.videoUrl = firstNonEmpty(stringAt(data, "url_high_mp4"), stringAt(data, "download_url"));
        out.coverUrl = stringAt(data, "originalCutCover");
        out.authorUid = stringAt(data, "user", "user_id");
        out.authorName = stringAt(data, "user", "nickname");
        out.authorAvatar = stringAt(data, "user", "small_pic");
        ensureMedia(out, "搜狐视频");
        return out;
    }

    private ParseInfo parseCctv(String shareUrl) throws Exception {
        String html = request("GET", shareUrl, headers(DEFAULT_UA), null, true).body;
        String guid = match(html, "var\\s+guid\\s*=\\s*\"([^\"]+)\"", 0);
        if (guid.isEmpty()) {
            throw new IllegalArgumentException("央视视频 GUID 为空");
        }
        JSONObject root = new JSONObject(request("GET", "https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=" + guid,
                headers(DEFAULT_UA), null, true).body);
        ParseInfo out = new ParseInfo();
        out.title = stringAt(root, "title");
        out.videoUrl = stringAt(root, "hls_url");
        out.coverUrl = stringAt(root, "image");
        out.authorName = stringAt(root, "play_channel");
        ensureMedia(out, "央视网");
        return out;
    }

    private ParseInfo parseXigua(String shareUrl) throws Exception {
        HttpResult redirect = request("GET", shareUrl, headers(DEFAULT_UA), null, false);
        URI target = URI.create(resolve(shareUrl, redirect.location));
        String id = trimSlash(target.getPath()).replace("video/", "");
        return parseXiguaById(id);
    }

    private ParseInfo parseXiguaById(String videoId) throws Exception {
        String api = "https://m.ixigua.com/douyin/share/video/" + videoId
                + "?aweme_type=107&schema_type=1&utm_source=copy&utm_campaign=client_share&utm_medium=android&app=aweme";
        String html = request("GET", api, headers(PC_UA,
                "Cookie", "MONITOR_WEB_ID=7892c49b-296e-4499-8704-e47c1b150c18; ixigua-a-s=1"), null, true).body;
        JSONObject root = scriptJson(html, "window\\._ROUTER_DATA\\s*=\\s*(.*?)</script>");
        JSONObject loader = objectAt(root, "loaderData");
        JSONObject page = loader == null ? null : loader.optJSONObject("video_(id)/page");
        JSONObject data = objectAt(page, "videoInfoRes", "item_list", "0");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "desc");
        out.authorUid = stringAt(data, "author", "user_id");
        out.authorName = stringAt(data, "author", "nickname");
        out.authorAvatar = stringAt(data, "author", "avatar_thumb", "url_list", "0");
        out.videoUrl = stringAt(data, "video", "play_addr", "url_list", "0");
        out.coverUrl = stringAt(data, "video", "cover", "url_list", "0");
        ensureMedia(out, "西瓜视频");
        return out;
    }

    private ParseInfo parsePipixia(String shareUrl) throws Exception {
        HttpResult redirect = request("GET", shareUrl, headers(DEFAULT_UA), null, false);
        String id = trimSlash(URI.create(resolve(shareUrl, redirect.location)).getPath()).replace("item/", "");
        JSONObject root = new JSONObject(request("GET",
                "https://api.pipix.com/bds/cell/cell_comment/?offset=0&cell_type=1&api_version=1&cell_id=" + id
                        + "&ac=wifi&channel=huawei_1319_64&aid=1319&app_name=super",
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = objectAt(root, "data", "cell_comments", "0", "comment_info", "item");
        ParseInfo out = new ParseInfo();
        out.authorName = stringAt(data, "author", "name");
        out.authorAvatar = stringAt(data, "author", "avatar", "download_list", "0", "url");
        out.title = stringAt(data, "content");
        out.videoUrl = stringAt(data, "video", "video_high", "url_list", "0", "url");
        out.coverUrl = stringAt(data, "cover", "url_list", "0", "url");
        JSONArray images = arrayAt(data, "note", "multi_image");
        addImages(out, images, "url_list", "0", "url");
        ensureMedia(out, "皮皮虾");
        return out;
    }

    private ParseInfo parsePipigaoxiao(String shareUrl) throws Exception {
        String id = trimSlash(URI.create(shareUrl).getPath()).replace("pp/post/", "");
        JSONObject root = new JSONObject(request("POST", "https://share.ippzone.com/ppapi/share/fetch_content",
                headers(PC_UA, "Referer", "https://share.ippzone.com/ppapi/share/fetch_content"),
                "{\"pid\":" + id + ",\"type\":\"post\",\"mid\":null}", true).body);
        JSONObject data = objectAt(root, "data", "post");
        String imgId = stringAt(data, "imgs", "0", "id");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "content");
        out.videoUrl = stringAt(data, "videos", imgId, "url");
        out.coverUrl = imgId.isEmpty() ? "" : "https://file.ippzone.com/img/view/id/" + imgId;
        ensureMedia(out, "皮皮搞笑");
        return out;
    }

    private ParseInfo parseHuoshan(String shareUrl) throws Exception {
        HttpResult redirect = request("GET", shareUrl, headers(DEFAULT_UA), null, false);
        String id = query(URI.create(resolve(shareUrl, redirect.location)), "item_id");
        JSONObject root = new JSONObject(request("GET", "https://share.huoshan.com/api/item/info?item_id=" + id,
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = objectAt(root, "data", "item_info");
        ParseInfo out = new ParseInfo();
        out.videoUrl = stringAt(data, "url");
        out.coverUrl = stringAt(data, "cover");
        ensureMedia(out, "火山");
        return out;
    }

    private ParseInfo parseWeishi(String shareUrl) throws Exception {
        String id = query(URI.create(shareUrl), "id");
        JSONObject root = new JSONObject(request("GET", "https://h5.weishi.qq.com/webapp/json/weishi/WSH5GetPlayPage?feedid=" + id,
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = objectAt(root, "data", "feeds", "0");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "feed_desc_withat");
        out.videoUrl = stringAt(data, "video_url");
        out.coverUrl = stringAt(data, "images", "0", "url");
        out.authorName = stringAt(data, "poster", "nick");
        out.authorAvatar = stringAt(data, "poster", "avatar");
        ensureMedia(out, "微视");
        return out;
    }

    private ParseInfo parseZuiyou(String shareUrl) throws Exception {
        String id = query(URI.create(shareUrl), "pid");
        JSONObject root = new JSONObject(request("POST", "https://share.xiaochuankeji.cn/planck/share/post/detail_h5",
                headers(DEFAULT_UA, "Content-Type", "application/json"),
                "{\"h_av\":\"5.2.13.011\",\"pid\":" + id + "}", true).body);
        JSONObject data = objectAt(root, "data", "post");
        String videoKey = stringAt(data, "imgs", "0", "id");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "content");
        out.videoUrl = stringAt(data, "videos", videoKey, "url");
        out.coverUrl = stringAt(data, "videos", videoKey, "cover_urls", "0");
        out.authorName = stringAt(data, "member", "name");
        out.authorAvatar = stringAt(data, "member", "avatar_urls", "origin", "urls", "0");
        ensureMedia(out, "最右");
        return out;
    }

    private ParseInfo parseQuanmin(String shareUrl) throws Exception {
        String id = query(URI.create(shareUrl), "vid");
        JSONObject root = new JSONObject(request("GET", "https://quanmin.hao222.com/wise/growth/api/sv/immerse?source=share-h5&pd=qm_share_mvideo&_format=json&vid=" + id,
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = objectAt(root, "data");
        ParseInfo out = new ParseInfo();
        out.title = firstNonEmpty(stringAt(data, "meta", "title"), stringAt(data, "shareInfo", "title"));
        out.videoUrl = stringAt(data, "meta", "video_info", "clarityUrl", "1", "url");
        out.coverUrl = stringAt(data, "meta", "image");
        out.authorUid = stringAt(data, "author", "id");
        out.authorName = stringAt(data, "author", "name");
        out.authorAvatar = stringAt(data, "author", "icon");
        ensureMedia(out, "度小视");
        return out;
    }

    private ParseInfo parseLishipin(String shareUrl) throws Exception {
        String id = trimSlash(URI.create(shareUrl).getPath()).replace("detail_", "");
        JSONObject root = new JSONObject(request("GET", "https://www.pearvideo.com/videoStatus.jsp?contId=" + id + "&mrd=" + (System.currentTimeMillis() / 1000),
                headers(PC_UA, "Referer", "https://www.pearvideo.com/detail_" + id), null, true).body);
        JSONObject data = objectAt(root, "videoInfo");
        String src = stringAt(data, "videos", "srcUrl");
        String timer = stringAt(root, "systemTime");
        ParseInfo out = new ParseInfo();
        out.videoUrl = src.replace(timer, "cont-" + id);
        out.coverUrl = stringAt(data, "video_image");
        ensureMedia(out, "梨视频");
        return out;
    }

    private ParseInfo parseHuya(String shareUrl) throws Exception {
        String id = match(shareUrl, "/(\\d+)\\.html", 0);
        JSONObject root = new JSONObject(request("GET", "https://liveapi.huya.com/moment/getMomentContent?videoId=" + id,
                headers(PC_UA, "Referer", "https://v.huya.com/"), null, true).body);
        JSONObject data = objectAt(root, "data", "moment", "videoInfo");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "videoTitle");
        out.videoUrl = stringAt(data, "definitions", "0", "url");
        out.coverUrl = stringAt(data, "videoCover");
        out.authorUid = stringAt(data, "uid");
        out.authorName = stringAt(data, "actorNick");
        out.authorAvatar = stringAt(data, "actorAvatarUrl");
        ensureMedia(out, "虎牙");
        return out;
    }

    private ParseInfo parseAcfun(String shareUrl) throws Exception {
        String html = request("GET", shareUrl, headers("Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1"), null, true).body;
        ParseInfo out = new ParseInfo();
        String videoInfo = match(html, "var videoInfo =\\s(.*?);", Pattern.DOTALL);
        if (!videoInfo.isEmpty()) {
            JSONObject video = new JSONObject(videoInfo);
            out.title = stringAt(video, "title");
            out.coverUrl = stringAt(video, "cover");
        }
        String playInfo = match(html, "var playInfo =\\s(.*?);", Pattern.DOTALL);
        if (!playInfo.isEmpty()) {
            out.videoUrl = stringAt(new JSONObject(playInfo), "streams", "0", "playUrls", "0");
        }
        ensureMedia(out, "A站");
        return out;
    }

    private ParseInfo parseDoupai(String shareUrl) throws Exception {
        String id = query(URI.create(shareUrl), "id");
        JSONObject root = new JSONObject(request("GET", "https://v2.doupai.cc/topic/" + id + ".json",
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = objectAt(root, "data");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "name");
        out.videoUrl = stringAt(data, "videoUrl");
        out.coverUrl = stringAt(data, "imageUrl");
        out.authorUid = stringAt(data, "userId", "id");
        out.authorName = stringAt(data, "userId", "name");
        out.authorAvatar = stringAt(data, "userId", "avatar");
        ensureMedia(out, "逗拍");
        return out;
    }

    private ParseInfo parseMeipai(String shareUrl) throws Exception {
        String html = request("GET", shareUrl, headers(PC_UA), null, true).body;
        String videoBase64 = match(html, "id=[\"']shareMediaBtn[\"'][^>]+data-video=[\"']([^\"']+)[\"']", 0);
        ParseInfo out = new ParseInfo();
        out.title = stripHtml(match(html, "class=[\"']detail-cover-title[\"'][^>]*>(.*?)</", Pattern.DOTALL));
        out.coverUrl = match(html, "id=[\"']detailVideo[\"'][\\s\\S]*?<img[^>]+src=[\"']([^\"']+)[\"']", 0);
        out.authorName = match(html, "class=[\"']detail-avatar[\"'][^>]+alt=[\"']([^\"']+)[\"']", 0);
        out.authorAvatar = prefixHttps(match(html, "class=[\"']detail-avatar[\"'][^>]+src=[\"']([^\"']+)[\"']", 0));
        if (!videoBase64.isEmpty()) {
            out.videoUrl = decodeMeipaiUrl(videoBase64);
        }
        ensureMedia(out, "美拍");
        return out;
    }

    private ParseInfo parseQuanminKge(String shareUrl) throws Exception {
        String id = query(URI.create(shareUrl), "s");
        String html = request("GET", "https://kg.qq.com/node/play?s=" + id, headers(PC_UA), null, true).body;
        JSONObject data = objectAt(new JSONObject(match(html, "window\\.__DATA__ = (.*?);", Pattern.DOTALL)), "detail");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "content");
        out.videoUrl = stringAt(data, "playurl_video");
        out.coverUrl = stringAt(data, "cover");
        out.authorUid = stringAt(data, "uid");
        out.authorName = stringAt(data, "nick");
        out.authorAvatar = stringAt(data, "avatar");
        ensureMedia(out, "全民K歌");
        return out;
    }

    private ParseInfo parseSixroom(String shareUrl) throws Exception {
        String id = match(shareUrl, "/(\\d+)\\.html", 0);
        if (id.isEmpty()) {
            id = query(URI.create(shareUrl), "vid");
        }
        JSONObject data = new JSONObject(request("GET", "https://v.6.cn/coop/mobile/index.php?padapi=minivideo-watchVideo.php&av=3.0&encpass=&logiuid=&isnew=1&from=0&vid=" + id,
                headers(DEFAULT_UA, "Referer", "https://m.6.cn/v/" + id), null, true).body);
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "content", "title");
        out.videoUrl = stringAt(data, "content", "playurl");
        out.coverUrl = stringAt(data, "content", "picurl");
        out.authorName = stringAt(data, "content", "alias");
        out.authorAvatar = stringAt(data, "content", "picuser");
        ensureMedia(out, "六间房");
        return out;
    }

    private ParseInfo parseXinpianchang(String shareUrl) throws Exception {
        String html = request("GET", shareUrl, headers(PC_UA,
                "Upgrade-Insecure-Requests", "1",
                "Referer", "https://www.xinpianchang.com/"), null, true).body;
        String json = match(html, "<script[^>]+id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", Pattern.DOTALL);
        JSONObject root = new JSONObject(json);
        JSONObject video = objectAt(root, "props", "pageProps", "detail");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(video, "title");
        out.coverUrl = stringAt(video, "cover");
        out.videoUrl = stringAt(video, "video", "content", "progressive", "0", "url");
        out.authorName = stringAt(video, "author", "userinfo", "username");
        out.authorAvatar = stringAt(video, "author", "userinfo", "avatar");
        ensureMedia(out, "新片场");
        return out;
    }

    private ParseInfo parseHaokan(String shareUrl) throws Exception {
        String vid = query(URI.create(shareUrl), "vid");
        JSONObject root = new JSONObject(request("GET", "https://haokan.baidu.com/v?_format=json&vid=" + vid,
                headers(DEFAULT_UA), null, true).body);
        JSONObject data = objectAt(root, "data", "apiData", "curVideoMeta");
        ParseInfo out = new ParseInfo();
        out.title = stringAt(data, "title");
        out.videoUrl = stringAt(data, "playurl");
        out.coverUrl = stringAt(data, "poster");
        out.authorUid = stringAt(data, "mth", "mthid");
        out.authorName = stringAt(data, "mth", "author_name");
        out.authorAvatar = stringAt(data, "mth", "author_photo");
        ensureMedia(out, "好看视频");
        return out;
    }

    private ParseInfo parseLvzhou(String shareUrl) throws Exception {
        String html = request("GET", shareUrl, headers(DEFAULT_UA), null, true).body;
        ParseInfo out = new ParseInfo();
        out.videoUrl = match(html, "<video[^>]+src=[\"']([^\"']+)[\"']", 0);
        out.authorAvatar = match(html, "<a[^>]+class=[\"']avatar[\"'][\\s\\S]*?<img[^>]+src=[\"']([^\"']+)[\"']", 0);
        String style = match(html, "class=[\"']video-cover[\"'][^>]+style=[\"']([^\"']+)[\"']", 0);
        out.coverUrl = match(style, "background-image:url\\((.*)\\)", 0);
        out.title = stripHtml(match(html, "class=[\"']status-title[\"'][^>]*>(.*?)</", Pattern.DOTALL));
        out.authorName = stripHtml(match(html, "class=[\"']nickname[\"'][^>]*>(.*?)</", Pattern.DOTALL));
        ensureMedia(out, "绿洲");
        return out;
    }

    private static String extractUrl(String text) {
        Matcher matcher = URL_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return "";
        }
        String url = matcher.group(1);
        return url.startsWith("www.") ? "https://" + url : url;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private HttpResult request(String method, String rawUrl, Map<String, String> headers, String body, boolean followRedirects) throws Exception {
        String current = rawUrl;
        for (int i = 0; i < (followRedirects ? 8 : 1); i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (body != null) {
                conn.setDoOutput(true);
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(data.length));
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (followRedirects && code >= 300 && code < 400 && location != null && !location.isEmpty()) {
                current = resolve(current, location);
                method = "GET";
                body = null;
                continue;
            }
            String response = readAll(code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream());
            BotLog.i(context, "video.inline.http", "code=" + code + " bytes=" + response.getBytes(StandardCharsets.UTF_8).length
                    + " url=" + compactUrl(current));
            if (code < 200 || code >= 400) {
                throw new IllegalStateException("HTTP " + code + " " + trim(response, 200));
            }
            HttpResult result = new HttpResult();
            result.code = code;
            result.body = response;
            result.location = location == null ? "" : location;
            result.finalUrl = current;
            return result;
        }
        throw new IllegalStateException("重定向过多");
    }

    private String followKuaishouRedirect(String shareUrl) throws Exception {
        String current = shareUrl;
        for (int i = 0; i < 8; i++) {
            HttpResult res = request("GET", current, headers(DEFAULT_UA, "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), null, false);
            if (res.location.isEmpty()) {
                return res.finalUrl;
            }
            String next = resolve(current, res.location);
            String path = URI.create(next).getPath();
            if (path != null && path.matches("^/short-video/[^/]+/?$")) {
                current = next;
                continue;
            }
            return next;
        }
        throw new IllegalStateException("快手短链跳转过多");
    }

    private static Map<String, String> headers(String ua, String... pairs) {
        Map<String, String> map = new HashMap<>();
        map.put("User-Agent", ua);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static JSONObject scriptJson(String html, String regex) throws Exception {
        String json = match(html, regex, Pattern.DOTALL).trim();
        if (json.endsWith(";")) {
            json = json.substring(0, json.length() - 1).trim();
        }
        json = json.replace(":undefined", ":null");
        return new JSONObject(json);
    }

    private static String match(String value, String regex, int flags) {
        Matcher matcher = Pattern.compile(regex, flags).matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static JSONObject objectAt(Object root, String... path) {
        Object current = traverse(root, path);
        return current instanceof JSONObject ? (JSONObject) current : null;
    }

    private static JSONArray arrayAt(Object root, String... path) {
        Object current = traverse(root, path);
        return current instanceof JSONArray ? (JSONArray) current : null;
    }

    private static String stringAt(Object root, String... path) {
        Object current = traverse(root, path);
        if (current == null || current == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(current).trim();
    }

    private static Object traverse(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (current == null || current == JSONObject.NULL || key == null || key.isEmpty()) {
                return null;
            }
            if (current instanceof JSONObject) {
                current = ((JSONObject) current).opt(key);
            } else if (current instanceof JSONArray) {
                try {
                    current = ((JSONArray) current).opt(Integer.parseInt(key));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private static String preferNonWebp(JSONArray urls) {
        if (urls == null || urls.length() == 0) {
            return "";
        }
        String first = "";
        for (int i = 0; i < urls.length(); i++) {
            String url;
            Object value = urls.opt(i);
            if (value instanceof JSONObject) {
                url = ((JSONObject) value).optString("url", ((JSONObject) value).optString("url_list"));
            } else {
                url = String.valueOf(value);
            }
            if (first.isEmpty()) {
                first = url;
            }
            if (!url.toLowerCase(Locale.ROOT).contains(".webp")) {
                return url;
            }
        }
        return first;
    }

    private static String bestMp4(JSONArray variants) {
        String best = "";
        int bestRate = -1;
        if (variants == null) {
            return "";
        }
        for (int i = 0; i < variants.length(); i++) {
            JSONObject item = variants.optJSONObject(i);
            if (item == null || !"video/mp4".equals(item.optString("content_type"))) {
                continue;
            }
            int rate = item.optInt("bitrate", 0);
            String url = item.optString("url");
            if (!url.isEmpty() && (best.isEmpty() || rate > bestRate)) {
                best = url;
                bestRate = rate;
            }
        }
        return best;
    }

    private static void addImages(ParseInfo out, JSONArray array, String... path) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            String url = stringAt(array.opt(i), path);
            if (!url.isEmpty()) {
                ImageItem item = new ImageItem();
                item.url = url;
                out.images.add(item);
            }
        }
    }

    private static String normalizeRedBookImage(String imageUrl) {
        if (!imageUrl.contains("notes_pre_post")) {
            return imageUrl;
        }
        int slash = imageUrl.lastIndexOf('/');
        String tail = slash >= 0 ? imageUrl.substring(slash + 1) : imageUrl;
        int bang = tail.indexOf('!');
        String id = bang >= 0 ? tail.substring(0, bang) : tail;
        String spectrum = imageUrl.contains("spectrum") ? "spectrum/" : "";
        return "https://ci.xiaohongshu.com/notes_pre_post/" + spectrum + id + "?imageView2/format/jpg";
    }

    private static String extractDouyinId(URI uri) {
        String modal = query(uri, "modal_id");
        if (!modal.isEmpty()) {
            return modal;
        }
        String[] parts = trimSlash(uri.getPath()).split("/");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private String redirectUrl(String url, String ua) {
        try {
            HttpResult res = request("GET", url, headers(ua), null, false);
            return res.location.isEmpty() ? url : resolve(url, res.location);
        } catch (Exception ignored) {
            return url;
        }
    }

    private String extractBvid(String shareUrl) throws Exception {
        URI uri = URI.create(shareUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains("b23.tv")) {
            HttpResult redirect = request("GET", shareUrl, headers(PC_UA), null, false);
            if (redirect.location.isEmpty()) {
                throw new IllegalStateException("B站短链没有跳转地址");
            }
            return extractBvid(resolve(shareUrl, redirect.location));
        }
        String[] parts = trimSlash(uri.getPath()).split("/");
        for (String part : parts) {
            if (part.startsWith("BV")) {
                return part;
            }
        }
        throw new IllegalArgumentException("B站 BV 号为空");
    }

    private static String query(URI uri, String name) {
        String query = uri == null ? "" : uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            String key = idx >= 0 ? part.substring(0, idx) : part;
            if (name.equals(key)) {
                return idx >= 0 ? part.substring(idx + 1) : "";
            }
        }
        return "";
    }

    private static String fixedNumber(int length) {
        Random random = new Random(System.currentTimeMillis());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(random.nextInt(10));
        }
        return out.toString();
    }

    private static String randomSeq(int length) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random random = new Random(System.currentTimeMillis());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(chars.charAt(random.nextInt(chars.length())));
        }
        return out.toString();
    }

    private static String decodeMeipaiUrl(String encoded) throws Exception {
        if (encoded.length() < 4) {
            return "";
        }
        String hex = new StringBuilder(encoded.substring(0, 4)).reverse().toString();
        String str = encoded.substring(4);
        int n = Integer.parseInt(hex, 16);
        String digits = String.valueOf(n);
        List<Integer> pre = new ArrayList<>();
        List<Integer> tail = new ArrayList<>();
        int tmp = n;
        for (int i = 0; i < digits.length(); i++) {
            int digit = tmp % 10;
            tmp = (tmp - digit) / 10;
            if (i >= digits.length() - 2) {
                pre.add(0, digit);
            } else {
                tail.add(0, digit);
            }
        }
        String d = meipaiSub(str, pre);
        int start = d.length() - tail.get(0) - tail.get(1);
        String kk = meipaiSub(d, listOf(start, tail.get(1)));
        return "https:" + new String(Base64.getDecoder().decode(kk), StandardCharsets.UTF_8);
    }

    private static String meipaiSub(String s, List<Integer> b) {
        String c = s.substring(0, b.get(0));
        String d = s.substring(b.get(0), b.get(0) + b.get(1));
        return c + s.substring(b.get(0)).replace(d, "");
    }

    private static List<Integer> listOf(int a, int b) {
        List<Integer> list = new ArrayList<>();
        list.add(a);
        list.add(b);
        return list;
    }

    private static String resolve(String base, String location) throws Exception {
        if (location == null || location.isEmpty()) {
            return base;
        }
        return new URI(base).resolve(location).toString();
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

    private static void ensureMedia(ParseInfo info, String source) {
        if (info == null || info.isEmpty()) {
            throw new IllegalStateException(source + " 没有解析到视频或图集资源");
        }
    }

    private static String trimSlash(String value) {
        String text = value == null ? "" : value;
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String stripHtml(String value) {
        return (value == null ? "" : value).replaceAll("<[^>]*>", "").trim();
    }

    private static String prefixHttps(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String url = value.trim();
        return url.startsWith("//") ? "https:" + url : url;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String compactUrl(String value) {
        String url = value == null ? "" : value.trim();
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    static final class ParseInfo {
        String authorUid = "";
        String authorName = "";
        String authorAvatar = "";
        String title = "";
        String videoUrl = "";
        String musicUrl = "";
        String coverUrl = "";
        final List<ImageItem> images = new ArrayList<>();

        boolean isEmpty() {
            return videoUrl.isEmpty() && images.isEmpty() && coverUrl.isEmpty() && musicUrl.isEmpty();
        }
    }

    static final class ImageItem {
        String url = "";
        String livePhotoUrl = "";
    }

    private static final class HttpResult {
        int code;
        String body = "";
        String location = "";
        String finalUrl = "";
    }
}
