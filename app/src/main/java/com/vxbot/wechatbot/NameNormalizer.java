package com.vxbot.wechatbot;

final class NameNormalizer {
    private NameNormalizer() {
    }

    static String nameKey(String value) {
        if (value == null) {
            return "";
        }
        String text = value
                .replaceAll("(?i)[\\[\\(（【](表情|动画表情|贴纸|emoji|emoj|sticker)[\\]\\)）】]", "")
                .replaceAll("[（(]\\d+\\s*人?[）)]", "")
                .replaceAll("[（(]\\d+[）)]$", "")
                .replace("：", ":")
                .replace(" ", "")
                .trim();
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isEmojiOrEmojiGlue(cp) || Character.isWhitespace(cp) || !Character.isLetterOrDigit(cp)) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    static String contentKey(String value) {
        return nameKey(value)
                .replace("別", "别")
                .replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "")
                .trim();
    }

    static boolean sameName(String left, String right) {
        String a = nameKey(left);
        String b = nameKey(right);
        return !a.isEmpty() && !b.isEmpty() && a.equals(b);
    }

    static boolean looseNameMatch(String text, String name) {
        String a = contentKey(text);
        String b = contentKey(name);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        if (a.length() > b.length() + 8) {
            return false;
        }
        int hit = 0;
        for (int i = 0; i < b.length(); i++) {
            if (a.indexOf(b.charAt(i)) >= 0) {
                hit++;
            }
        }
        return hit >= Math.max(2, (int) Math.ceil(b.length() * 0.6d));
    }

    private static boolean isEmojiOrEmojiGlue(int cp) {
        return cp == 0x200D
                || cp == 0x20E3
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF);
    }
}
