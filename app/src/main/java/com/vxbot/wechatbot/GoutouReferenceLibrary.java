package com.vxbot.wechatbot;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bundled relationship knowledge and practical references from the upstream project. */
public final class GoutouReferenceLibrary {
    private static volatile List<Reference> cache;

    private GoutouReferenceLibrary() { }

    public static JSONArray relevant(Context context, String query, String strategy) {
        List<Reference> refs = load(context);
        String normalized = normalize((query == null ? "" : query) + " " + (strategy == null ? "" : strategy));
        ArrayList<ReferenceScore> matches = new ArrayList<>();
        for (Reference ref : refs) {
            String title = normalize(ref.title);
            int score = commonBigrams(normalized, title) * 3;
            String strategyKey = normalize(strategy);
            if (!strategyKey.isEmpty() && title.contains(strategyKey)) score += 8;
            if (score > 0) matches.add(new ReferenceScore(ref, score));
        }
        matches.sort(Comparator.comparingInt((ReferenceScore row) -> row.score).reversed());
        JSONArray result = new JSONArray();
        int knowledge = 0, practical = 0;
        for (ReferenceScore match : matches) {
            boolean isPractical = match.reference.path.contains("/practical/");
            if ((isPractical && practical >= 1) || (!isPractical && knowledge >= 1)) continue;
            String excerpt = match.reference.text;
            if (excerpt.length() > 1700) excerpt = excerpt.substring(0, 1700);
            result.put(object("title", match.reference.title, "content", excerpt));
            if (isPractical) practical++; else knowledge++;
            if (result.length() == 2) break;
        }
        return result;
    }

    public static int count(Context context) { return load(context).size(); }

    private static List<Reference> load(Context context) {
        List<Reference> current = cache;
        if (current != null) return current;
        synchronized (GoutouReferenceLibrary.class) {
            if (cache != null) return cache;
            ArrayList<Reference> rows = new ArrayList<>();
            try { collect(context.getAssets(), "goutoujunshi/references", rows); } catch (Exception ignored) { }
            cache = rows;
            return cache;
        }
    }

    private static void collect(AssetManager assets, String path, List<Reference> output) throws Exception {
        String[] children = assets.list(path);
        if (children == null) return;
        for (String child : children) {
            String next = path + "/" + child;
            String[] nested = assets.list(next);
            if (nested != null && nested.length > 0) collect(assets, next, output);
            else if (child.endsWith(".md")) {
                String text = read(assets.open(next));
                output.add(new Reference(next, child.replaceFirst("\\.md$", ""), text));
            }
        }
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int commonBigrams(String left, String right) {
        if (left.length() < 2 || right.length() < 2) return 0;
        int score = 0;
        for (int i = 0; i + 1 < left.length(); i++) {
            String bigram = left.substring(i, i + 2);
            if ("这个那个我们你们他们什么怎么因为所以然后不是没有可以对方关系聊天回复消息自己需要一下现在可能已经如果但是".contains(bigram)) continue;
            if (right.contains(bigram)) score++;
        }
        return score;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”（）【】《》]+", "");
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            try { result.put(String.valueOf(pairs[i]), pairs[i + 1]); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        return result;
    }

    private static final class Reference {
        final String path, title, text;
        Reference(String path, String title, String text) { this.path = path; this.title = title; this.text = text; }
    }
    private static final class ReferenceScore {
        final Reference reference; final int score;
        ReferenceScore(Reference reference, int score) { this.reference = reference; this.score = score; }
    }
}
