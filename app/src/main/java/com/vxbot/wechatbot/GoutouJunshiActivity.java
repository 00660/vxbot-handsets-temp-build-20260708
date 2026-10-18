package com.vxbot.wechatbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Management and review screens for the integrated dog-head strategist. */
public final class GoutouJunshiActivity extends Activity {
    private static final int BG = 0xFFF6F8FB;
    private static final int INK = 0xFF182033;
    private static final int MUTED = 0xFF5E6B82;
    private static final int NAV = 0xFF0F172A;
    private static final int BLUE = 0xFF1677FF;
    private static final int TEAL = 0xFF0EA5A3;
    private static final int TAB_CONTACTS = 0;
    private static final int TAB_KNOWLEDGE = 1;
    private static final int TAB_TRENDS = 2;
    private static final int TAB_ANALYSIS = 3;
    private static final int CSV_REQUEST = 54;
    private LinearLayout body;
    private int selectedTab;
    private final List<TextView> navTabs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedTab = getIntent().getIntExtra("tab", TAB_CONTACTS);
        buildShell();
        render();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(14));
        header.setBackgroundColor(NAV);
        TextView title = text("狗头军师", 22, Color.WHITE, true);
        header.addView(title);
        TextView subtitle = text("自动判断七类策略 · 生成候选 · 按排序自动回复", 12, 0xFFB8C2D3, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        header.addView(subtitle);
        root.addView(header);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        nav.setBackground(round(0xFFE9EEF5, dp(14), 0, 0));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, dp(48));
        navParams.setMargins(dp(16), dp(12), dp(16), dp(4));
        root.addView(nav, navParams);
        String[] labels = {"联系人", "知识", "趋势", "分析"};
        navTabs.clear();
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            TextView tab = text(labels[i], 13, MUTED, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), 0, dp(4), 0);
            tab.setOnClickListener(v -> { selectedTab = target; updateNavigation(); render(); });
            navTabs.add(tab);
            nav.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(20));
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        updateNavigation();
    }

    private void updateNavigation() {
        for (int i = 0; i < navTabs.size(); i++) {
            TextView tab = navTabs.get(i);
            boolean selected = i == selectedTab;
            tab.setTextColor(selected ? Color.WHITE : MUTED);
            tab.setBackground(round(selected ? NAV : Color.TRANSPARENT, dp(11), 0, 0));
        }
    }

    private void render() {
        body.removeAllViews();
        switch (selectedTab) {
            case TAB_CONTACTS: renderContacts(); break;
            case TAB_KNOWLEDGE: renderKnowledge(); break;
            case TAB_TRENDS: renderTrends(); break;
            default: renderAnalysis(); break;
        }
    }

    private void renderContacts() {
        body.addView(text("联系人关系档案", 18, INK, true), topMargin());
        body.addView(text("联系人资料是可选补充；不填写时，普通聊天仍由狗头军师自动判断策略。", 13, MUTED, false));
        body.addView(primaryAction("查看实时处理", v -> openStrategyOverlay()), topMarginSmall());
        body.addView(text("悬浮窗会显示消息路由和机器人处理记录。", 12, MUTED, false), topMarginSmall());
        body.addView(action("添加联系人", v -> editProfile(null)), topMargin());
        List<GoutouJunshiStore.Profile> profiles = GoutouJunshiStore.profiles(this);
        if (profiles.isEmpty()) body.addView(text("暂无联系人资料。留空不影响普通聊天的自动策略。", 14, MUTED, false), topMargin());
        for (GoutouJunshiStore.Profile profile : profiles) {
            LinearLayout card = card();
            card.addView(text(profile.name + (profile.session.isEmpty() ? "" : " · " + profile.session), 16, INK, true));
            card.addView(text("关系：" + show(profile.relationship) + "\n阶段：" + show(profile.stage)
                    + "\n本轮目标：" + show(profile.goal) + "\n背景：" + show(profile.background), 13, 0xFF475467, false));
            LinearLayout actions = row();
            actions.addView(smallButton("编辑", v -> editProfile(profile)), weight());
            actions.addView(smallButton("删除", v -> confirm("删除联系人资料？", () -> {
                GoutouJunshiStore.deleteProfile(this, profile.id); render();
            })), weight());
            card.addView(actions);
            body.addView(card, topMargin());
        }
    }

    private void editProfile(GoutouJunshiStore.Profile source) {
        GoutouJunshiStore.Profile profile = source == null ? new GoutouJunshiStore.Profile() : copy(source);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = field(fields, "联系人昵称", profile.name, false);
        EditText session = field(fields, "微信会话名（留空表示全部会话）", profile.session, false);
        EditText aliases = field(fields, "别名，用逗号分开", profile.aliases, false);
        EditText relationship = field(fields, "关系", profile.relationship, false);
        EditText stage = field(fields, "关系阶段", profile.stage, false);
        EditText goal = field(fields, "本轮目标", profile.goal, false);
        EditText background = field(fields, "补充背景、偏好、共同经历或边界", profile.background, true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        showStyled(new AlertDialog.Builder(this).setTitle(source == null ? "添加联系人" : "编辑联系人")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    profile.name = value(name); profile.session = value(session); profile.aliases = value(aliases);
                    profile.relationship = value(relationship); profile.stage = value(stage); profile.goal = value(goal);
                    profile.background = value(background);
                    if (profile.name.isEmpty()) toast("请填写联系人名"); else { GoutouJunshiStore.saveProfile(this, profile); render(); }
                }).create());
    }

    private void openStrategyOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("先授予悬浮窗权限，返回后再点一次");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        BotConfig.prefs(this).edit()
                .putBoolean("enableLogOverlay", true)
                .putBoolean("keepLogOverlayDuringOperation", false)
                .apply();
        Intent service = new Intent(this, BotService.class);
        if (BotService.isRunning()) service.setAction(BotService.ACTION_SHOW_LOG_OVERLAY);
        else service.setAction(BotService.ACTION_START).putExtra("showLogOverlay", true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        toast("实时处理悬浮窗已打开；关闭后回到控制圆点，可从竖排菜单再次打开操作日志");
    }

    private void renderKnowledge() {
        body.addView(text("知识笔记", 18, INK, true), topMargin());
        body.addView(text("笔记可按标题、标签和最近消息匹配；设为常驻后会参与每次分析。", 13, MUTED, false));
        body.addView(action("查看关系知识库", v -> showReferenceLibrary()), topMarginSmall());
        body.addView(primaryAction("添加笔记", v -> editNote(null)), topMargin());
        for (GoutouJunshiStore.Note note : GoutouJunshiStore.notes(this)) {
            LinearLayout card = card();
            card.addView(text(note.title + (note.alwaysOn ? " · 常驻" : "") + (!note.enabled ? " · 已停用" : ""), 16, INK, true));
            card.addView(text(note.content + (note.tags.isEmpty() ? "" : "\n标签：" + note.tags), 13, 0xFF475467, false));
            LinearLayout actions = row();
            actions.addView(smallButton("编辑", v -> editNote(note)), weight());
            actions.addView(smallButton("删除", v -> confirm("删除这条知识笔记？", () -> {
                GoutouJunshiStore.deleteNote(this, note.id); render();
            })), weight());
            card.addView(actions);
            body.addView(card, topMargin());
        }
    }

    private void showReferenceLibrary() {
        try {
            ArrayList<String> files = new ArrayList<>();
            collectAssets("goutoujunshi/references", files);
            StringBuilder list = new StringBuilder("随应用完整保留的关系知识与实用指南：\n");
            for (String path : files) list.append("• ").append(path.substring(path.lastIndexOf('/') + 1)).append('\n');
            list.append("\nCSV 示例案例：\n");
            collectAssets("goutoujunshi/relationship_cases", files);
            for (String path : files) if (path.endsWith(".csv")) list.append("• ").append(path.substring(path.lastIndexOf('/') + 1)).append('\n');
            list.append("\n这些资料作为知识库素材供你查看和整理；策略会结合你维护的笔记与会话内容，不会未经证据把资料当作当前聊天事实。");
            ScrollView view = new ScrollView(this); TextView text = text(list.toString(), 13, 0xFF344054, false);
            text.setPadding(dp(16), dp(10), dp(16), dp(10)); view.addView(text);
            showStyled(new AlertDialog.Builder(this).setTitle("内置知识资料").setView(view).setPositiveButton("关闭", null).create());
        } catch (Exception e) { toast("知识资料读取失败：" + e.getMessage()); }
    }

    private void collectAssets(String path, List<String> output) throws Exception {
        String[] children = getAssets().list(path);
        if (children == null) return;
        for (String child : children) {
            String next = path + "/" + child;
            String[] nested = getAssets().list(next);
            if (nested != null && nested.length > 0) collectAssets(next, output);
            else output.add(next);
        }
    }

    private void editNote(GoutouJunshiStore.Note source) {
        GoutouJunshiStore.Note note = source == null ? new GoutouJunshiStore.Note() : copy(source);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText title = field(fields, "标题", note.title, false);
        EditText content = field(fields, "知识内容", note.content, true);
        EditText tags = field(fields, "匹配标签，逗号分开", note.tags, false);
        CheckBox always = checkBox("每次分析都使用", note.alwaysOn); fields.addView(always);
        CheckBox enabled = checkBox("启用", note.enabled); fields.addView(enabled);
        showStyled(new AlertDialog.Builder(this).setTitle(source == null ? "添加笔记" : "编辑笔记")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    note.title = value(title); note.content = value(content); note.tags = value(tags);
                    note.alwaysOn = always.isChecked(); note.enabled = enabled.isChecked();
                    if (note.title.isEmpty() || note.content.isEmpty()) toast("标题和内容都要填写");
                    else { GoutouJunshiStore.saveNote(this, note); render(); }
                }).create());
    }

    private void renderTrends() {
        body.addView(text("关系趋势", 18, INK, true), topMargin());
        body.addView(text("图表只显示消息方向的净差（对方 +1、自己 −1），不代表关系质量或成功率。可查看示例走势，或导入 CSV 聊天记录。", 13, MUTED, false));
        body.addView(primaryAction("导入聊天记录", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/*");
            startActivityForResult(intent, CSV_REQUEST);
        }), topMarginSmall());
        body.addView(text("支持 CSV 文件，列名为 timestamp、sender、message；sender 填 me 或 other，时间按升序排列。", 12, MUTED, false), topMarginSmall());
        body.addView(text("示例走势", 16, INK, true), topMargin());
        String[] examples = {"双向升温", "热聊后降温", "冲突后修复", "忙但仍兑现", "明确边界后收线"};
        for (String name : examples) body.addView(action("查看" + name + "示例", v -> {
            List<GoutouJunshiStore.TrendCandle> data = exampleTrend(name);
            showTrend(name, data);
        }), topMarginSmall());
        for (GoutouJunshiStore.TrendCandle candle : GoutouJunshiStore.trend(this, "CSV 导入")) {
            body.addView(text(candle.date + "  净差 " + candle.open + " → " + candle.close, 12, 0xFF475467, false));
        }
    }

    private void renderAnalysis() {
        body.addView(text("最近分析", 18, INK, true), topMargin());
        body.addView(text("自动策略在后台运行；机器人会直接发送排序第一的候选。下面可查看事实、未知、下一步、停止条件、候选理由和更贴近你口吻的版本。", 13, MUTED, false));
        List<JSONObject> analyses = GoutouJunshiStore.recentAnalyses(this);
        if (analyses.isEmpty()) body.addView(text("还没有分析记录。", 14, MUTED, false), topMargin());
        for (JSONObject analysis : analyses) {
            LinearLayout card = card();
            card.addView(text(analysis.optString("session") + " · " + analysis.optString("sender"), 15, 0xFF17212B, true));
            card.addView(text("策略：" + analysis.optString("strategy") + "\n自动回复：" + show(analysis.optString("selected")), 13, 0xFF475467, false));
            card.addView(smallButton("查看分析详情", v -> showReport(analysis)), topMarginSmall());
            body.addView(card, topMargin());
        }
    }

    private void showReport(JSONObject analysis) {
        String report = GoutouJunshiStore.report(analysis);
        String personal = analysis.optString("more_like_me");
        if (!personal.isEmpty()) report += "\n\n更像我一点：\n" + personal;
        ScrollView scroll = new ScrollView(this);
        TextView content = text(report, 14, 0xFF344054, false);
        content.setPadding(dp(18), dp(10), dp(18), dp(14));
        scroll.addView(content);
        showStyled(new AlertDialog.Builder(this).setTitle("完整策略分析").setView(scroll).setPositiveButton("关闭", null).create());
    }

    private void showTrend(String name, List<GoutouJunshiStore.TrendCandle> candles) {
        if (candles == null || candles.isEmpty()) return;
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        TextView caveat = text(name + "\n消息方向净差，不是关系质量或成功率。", 13, 0xFF667085, false);
        caveat.setPadding(dp(12), dp(8), dp(12), dp(4)); content.addView(caveat);
        content.addView(new TrendChart(this, candles), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
        showStyled(new AlertDialog.Builder(this).setTitle("关系趋势").setView(content).setPositiveButton("关闭", null).create());
    }

    private List<GoutouJunshiStore.TrendCandle> exampleTrend(String name) {
        int[][] values;
        if ("双向升温".equals(name)) values = new int[][]{{50,56,48,54},{54,64,53,61},{61,72,60,70},{70,76,68,74},{74,82,72,79},{79,86,77,83}};
        else if ("热聊后降温".equals(name)) values = new int[][]{{50,59,48,57},{57,65,55,63},{63,66,58,60},{60,63,51,54},{54,56,43,46},{46,52,44,49},{49,50,45,47},{47,49,34,38}};
        else if ("冲突后修复".equals(name)) values = new int[][]{{55,61,53,59},{59,61,28,36},{36,49,34,45},{45,63,43,60},{60,68,58,65}};
        else if ("忙但仍兑现".equals(name)) values = new int[][]{{50,55,48,53},{53,62,51,59},{59,60,55,58},{58,60,55,58},{58,65,56,63},{63,76,61,73},{73,80,71,77}};
        else values = new int[][]{{50,54,48,51},{51,53,38,41},{41,42,35,37}};
        ArrayList<GoutouJunshiStore.TrendCandle> result = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            GoutouJunshiStore.TrendCandle candle = new GoutouJunshiStore.TrendCandle();
            candle.date = "示例 " + (i + 1); candle.open = values[i][0]; candle.high = values[i][1];
            candle.low = values[i][2]; candle.close = values[i][3]; result.add(candle);
        }
        return result;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CSV_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            List<GoutouJunshiStore.TrendCandle> rows = parseCsv(input);
            GoutouJunshiStore.saveTrend(this, "CSV 导入", rows);
            showTrend("CSV 导入", rows);
            render();
        } catch (Exception e) { toast("CSV 导入失败：" + e.getMessage()); }
    }

    private List<GoutouJunshiStore.TrendCandle> parseCsv(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("无法打开文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read;
        while ((read = input.read(buffer)) != -1) { bytes.write(buffer, 0, read); if (bytes.size() > 4_000_000) throw new IllegalArgumentException("CSV 超过 4 MB"); }
        List<List<String>> records = csvRecords(new String(bytes.toByteArray(), StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", ""));
        if (records.size() < 2 || records.size() > 20001) throw new IllegalArgumentException("CSV 需要 1 至 20000 条聊天记录");
        List<String> header = records.get(0); int time = header.indexOf("timestamp"), sender = header.indexOf("sender"), message = header.indexOf("message");
        if (time < 0 || sender < 0 || message < 0) throw new IllegalArgumentException("CSV 需要 timestamp,sender,message 三列");
        ArrayList<GoutouJunshiStore.TrendCandle> result = new ArrayList<>();
        LocalDateTime previous = null; int balance = 0;
        for (int i = 1; i < records.size(); i++) {
            List<String> row = records.get(i);
            if (row.size() <= Math.max(time, Math.max(sender, message))) throw new IllegalArgumentException("第 " + (i + 1) + " 行列不完整");
            LocalDateTime at;
            try { at = LocalDateTime.parse(row.get(time).trim().replace(' ', 'T')); }
            catch (Exception e) { throw new IllegalArgumentException("时间格式须为 YYYY-MM-DD HH:MM:SS"); }
            if (previous != null && at.isBefore(previous)) throw new IllegalArgumentException("聊天时间须按升序排列");
            previous = at;
            String side = row.get(sender).trim();
            if (!("me".equals(side) || "other".equals(side))) throw new IllegalArgumentException("sender 只接受 me 或 other");
            if (row.get(message).trim().isEmpty()) throw new IllegalArgumentException("存在空消息");
            String date = at.toLocalDate().toString();
            if (result.isEmpty() || !date.equals(result.get(result.size() - 1).date)) {
                GoutouJunshiStore.TrendCandle candle = new GoutouJunshiStore.TrendCandle();
                candle.date = date; candle.open = balance; candle.high = balance; candle.low = balance; candle.close = balance;
                result.add(candle);
            }
            balance += "other".equals(side) ? 1 : -1;
            GoutouJunshiStore.TrendCandle candle = result.get(result.size() - 1);
            candle.high = Math.max(candle.high, balance); candle.low = Math.min(candle.low, balance); candle.close = balance;
        }
        return result;
    }

    private List<List<String>> csvRecords(String text) {
        ArrayList<List<String>> records = new ArrayList<>(); ArrayList<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
            else if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { row.add(cell.toString()); cell.setLength(0); }
            else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (hasText(row)) records.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (quoted) throw new IllegalArgumentException("CSV 引号没有闭合");
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); if (hasText(row)) records.add(row); }
        return records;
    }

    private boolean hasText(List<String> row) { for (String value : row) if (!value.trim().isEmpty()) return true; return false; }

    private EditText field(LinearLayout parent, String hint, String value, boolean multiline) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setText(value); edit.setTextSize(14);
        edit.setTextColor(INK); edit.setHintTextColor(0xFF98A2B3);
        edit.setPadding(dp(13), dp(10), dp(13), dp(10));
        edit.setBackground(round(0xFFF8FAFC, dp(12), dp(1), 0xFFD9E1EC));
        if (multiline) { edit.setMinLines(3); edit.setGravity(Gravity.TOP); }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(5), 0, dp(7));
        parent.addView(edit, params);
        return edit;
    }

    private CheckBox checkBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label); box.setTextColor(INK); box.setTextSize(14); box.setChecked(checked);
        box.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{TEAL, 0xFF98A2B3}));
        box.setPadding(dp(2), dp(6), 0, dp(6));
        return box;
    }

    private void showStyled(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(round(Color.WHITE, dp(18), dp(1), 0xFFE3E8EF));
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(BLUE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(MUTED);
    }

    private void confirm(String message, Runnable yes) {
        showStyled(new AlertDialog.Builder(this).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> yes.run()).create());
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private String value(EditText edit) { return edit.getText().toString().trim(); }
    private String show(String value) { return value == null || value.trim().isEmpty() ? "未填写" : value; }
    private GoutouJunshiStore.Profile copy(GoutouJunshiStore.Profile p) {
        GoutouJunshiStore.Profile n = new GoutouJunshiStore.Profile(); n.id=p.id; n.session=p.session; n.name=p.name;
        n.aliases=p.aliases; n.relationship=p.relationship; n.stage=p.stage; n.goal=p.goal; n.background=p.background; return n;
    }
    private GoutouJunshiStore.Note copy(GoutouJunshiStore.Note p) {
        GoutouJunshiStore.Note n = new GoutouJunshiStore.Note(); n.id=p.id; n.title=p.title; n.content=p.content;
        n.tags=p.tags; n.alwaysOn=p.alwaysOn; n.enabled=p.enabled; return n;
    }
    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(15), dp(13), dp(15), dp(13));
        view.setBackground(round(Color.WHITE, dp(16), dp(1), 0xFFE3E8EF));
        return view;
    }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout.LayoutParams topMargin() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(12); return p; }
    private LinearLayout.LayoutParams topMarginSmall() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(6); return p; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1); p.setMargins(dp(3), dp(4), dp(3), dp(2)); return p; }
    private TextView smallButton(String title, View.OnClickListener click) {
        boolean destructive = "删除".equals(title);
        TextView button = text(title, 14, destructive ? 0xFFB42318 : BLUE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(42));
        button.setBackground(ripple(round(destructive ? 0xFFFFF1F0 : 0xFFF0F6FF, dp(11), dp(1),
                destructive ? 0xFFFECACA : 0xFFD8E7FF), destructive ? 0x18B42318 : 0x181677FF));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(title);
        button.setOnClickListener(click);
        return button;
    }

    private View action(String title, View.OnClickListener click) {
        return actionButton(title, click, false);
    }

    private View primaryAction(String title, View.OnClickListener click) {
        return actionButton(title, click, true);
    }

    private LinearLayout actionButton(String title, View.OnClickListener click, boolean primary) {
        int foreground = primary ? Color.WHITE : INK;
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), 0, dp(14), 0);
        button.setMinimumHeight(dp(52));
        button.setBackground(ripple(round(primary ? BLUE : Color.WHITE, dp(14), primary ? 0 : dp(1), 0xFFE3E8EF),
                primary ? 0x33FFFFFF : 0x141677FF));
        TextView label = text(title, 15, foreground, true);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = text("›", 25, primary ? 0xFFDCEBFF : 0xFF667085, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(arrow, new LinearLayout.LayoutParams(dp(24), -2));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(title);
        button.setOnClickListener(click);
        return button;
    }

    private RippleDrawable ripple(GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private GradientDrawable round(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(radius);
        if (strokeWidth > 0) bg.setStroke(strokeWidth, strokeColor);
        return bg;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class TrendChart extends View {
        private final List<GoutouJunshiStore.TrendCandle> data;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TrendChart(Activity context, List<GoutouJunshiStore.TrendCandle> data) { super(context); this.data = data; setBackgroundColor(Color.WHITE); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (data.isEmpty()) return;
            int min = 0, max = 0;
            for (GoutouJunshiStore.TrendCandle c : data) { min = Math.min(min, c.low); max = Math.max(max, c.high); }
            if (min == max) max = min + 1;
            float left = dp(28), right = getWidth() - dp(12), top = dp(18), bottom = getHeight() - dp(30);
            paint.setColor(0xFFE4E7EC); paint.setStrokeWidth(dp(1));
            for (int i = 0; i < 4; i++) { float y = top + (bottom - top) * i / 3f; canvas.drawLine(left, y, right, y, paint); }
            float step = (right - left) / data.size();
            for (int i = 0; i < data.size(); i++) {
                GoutouJunshiStore.TrendCandle c = data.get(i); float x = left + step * (i + 0.5f);
                float highY = y(c.high, min, max, top, bottom), lowY = y(c.low, min, max, top, bottom);
                float openY = y(c.open, min, max, top, bottom), closeY = y(c.close, min, max, top, bottom);
                paint.setColor(c.close >= c.open ? 0xFF12B76A : 0xFFF04438); paint.setStrokeWidth(dp(2));
                canvas.drawLine(x, highY, x, lowY, paint); canvas.drawLine(x - dp(5), openY, x, openY, paint);
                canvas.drawLine(x, closeY, x + dp(5), closeY, paint);
                if (data.size() <= 10) { paint.setTextSize(dp(9)); canvas.drawText(c.date, x - dp(13), getHeight() - dp(8), paint); }
            }
        }
        private float y(int value, int min, int max, float top, float bottom) { return bottom - (value - min) * (bottom - top) / (float) (max - min); }
    }
}
