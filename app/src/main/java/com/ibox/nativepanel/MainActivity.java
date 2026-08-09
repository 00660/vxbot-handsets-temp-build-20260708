package com.ibox.nativepanel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import com.geetest.captcha.GTCaptcha4Client;
import com.geetest.captcha.GTCaptcha4Config;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Native iBox panel client. The page never embeds the 5000 HTML document. */
public final class MainActivity extends Activity {
    private static final String PREFS = "ibox_native_panel";
    private static final String[] PAGE_KEYS = {
            "accounts", "synthesis", "market", "quant", "trade", "lottery", "first-sale", "settings"
    };
    private static final String[] PAGE_LABELS = {
            "资产总览", "合成任务", "行情监控", "量化策略", "交易执行", "自动抽奖", "首发抢购", "设置"
    };

    private final int ink = Color.rgb(30, 42, 52);
    private final int muted = Color.rgb(126, 137, 148);
    private final int primary = Color.rgb(101, 198, 139);
    private final int primaryDark = Color.rgb(73, 168, 112);
    private final int success = Color.rgb(67, 170, 110);
    private final int danger = Color.rgb(205, 88, 103);
    private final int amber = Color.rgb(196, 145, 50);
    private final int infoBlue = Color.rgb(75, 132, 202);
    private final int background = Color.rgb(248, 251, 252);
    private final int surface = Color.WHITE;

    private SharedPreferences preferences;
    private ExecutorService io;
    private NativeEngine engine;
    private LinearLayout content;
    private LinearLayout nav;
    private TextView pageTitle;
    private TextView statusView;
    private String selectedPhone;
    private JSONArray accounts = new JSONArray();
    private String currentPage = "accounts";
    private final List<Button> navButtons = new ArrayList<>();
    private String smsSessionId = "";
    private String smsPhone = "";

    private interface CaptchaCallback {
        void onResult(JSONObject result);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        engine = new NativeEngine(this);
        selectedPhone = engine.store().getSelectedPhone();
        accounts = engine.store().getAccounts();
        io = Executors.newFixedThreadPool(3);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setAppShell();
        showAccounts();
        loadAccounts();
        requestNotificationPermission();
        if (engine.store().getAccounts().length() > 0 && backgroundSyncEnabled()) startPanelSyncService();
    }

    @Override
    protected void onDestroy() {
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 304);
        }
    }

    private void startPanelSyncService() {
        Intent intent = new Intent(this, PanelSyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void stopPanelSyncService() {
        stopService(new Intent(this, PanelSyncService.class));
    }

    private boolean backgroundSyncEnabled() {
        return preferences.getBoolean("backgroundSync", true);
    }

    private void showLoginDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = vertical(surface);
        sheet.setPadding(dp(20), dp(18), dp(20), dp(20));
        sheet.setBackground(shape(surface, 0xffe5eeeb, 16));

        LinearLayout header = horizontal(Color.TRANSPARENT);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("短信登录", 21, ink, Typeface.BOLD), new LinearLayout.LayoutParams(0, dp(44), 1));
        Button close = button("×", false);
        close.setTextSize(22);
        close.setPadding(0, 0, 0, 0);
        close.setContentDescription("关闭登录");
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        sheet.addView(header, marginParams(-1, dp(44), 0, 0, 0, dp(14)));

        EditText phone = input("11 位手机号");
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        EditText code = input("短信验证码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setEnabled(false);
        Button send = button("获取验证码", true);
        Button login = button("登录", false);
        login.setEnabled(false);
        login.setAlpha(0.55f);
        TextView state = text("", 12, muted, Typeface.NORMAL);
        state.setMinHeight(dp(22));

        sheet.addView(labelled("手机号", phone));
        LinearLayout codeRow = horizontal(Color.TRANSPARENT);
        codeRow.addView(code, new LinearLayout.LayoutParams(0, dp(46), 1));
        codeRow.addView(send, marginParams(dp(124), dp(46), dp(8), 0, 0, 0));
        sheet.addView(text("短信验证码", 12, muted, Typeface.BOLD), marginParams(-1, -2, 0, dp(2), 0, dp(4)));
        sheet.addView(codeRow, marginParams(-1, -2, 0, 0, 0, dp(6)));
        sheet.addView(state, marginParams(-1, dp(22), 0, dp(4), 0, dp(12)));
        sheet.addView(login, new LinearLayout.LayoutParams(-1, dp(48)));

        send.setOnClickListener(v -> {
            String value = phone.getText().toString().trim();
            if (!value.matches("\\d{11}")) {
                state.setText("请输入 11 位手机号");
                state.setTextColor(danger);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("phone", value);
            } catch (Exception ignored) {
            }
            send.setEnabled(false);
            send.setAlpha(0.55f);
            state.setText("正在创建验证会话…");
            state.setTextColor(muted);
            request("创建短信会话", "POST", "/native/sms/session", body, result -> {
                JSONObject data = result.optJSONObject("data");
                smsSessionId = data == null ? "" : data.optString("sessionId", "");
                smsPhone = value;
                String captchaId = data == null ? "" : data.optString("captchaId", "");
                if (smsSessionId.isEmpty() || captchaId.isEmpty()) {
                    send.setEnabled(true);
                    send.setAlpha(1f);
                    state.setText("服务未返回验证参数");
                    state.setTextColor(danger);
                    return;
                }
                state.setText("请完成人机验证");
                showCaptchaDialog(captchaId, captcha -> {
                    JSONObject captchaBody = new JSONObject();
                    try {
                        captchaBody.put("captcha", captcha);
                    } catch (Exception ignored) {
                    }
                    request("发送短信", "POST", "/native/sms/captcha/" + Uri.encode(smsSessionId), captchaBody, smsResult -> {
                        code.setEnabled(true);
                        code.setAlpha(1f);
                        send.setEnabled(false);
                        styleButton(send, false);
                        send.setText("已发送");
                        state.setText("短信已发送，请输入验证码");
                        state.setTextColor(success);
                        login.setEnabled(true);
                        login.setAlpha(1f);
                        styleButton(login, true);
                        code.requestFocus();
                    });
                });
            });
        });
        login.setOnClickListener(v -> {
            String value = code.getText().toString().trim();
            if (smsSessionId.isEmpty() || smsPhone.isEmpty()) {
                state.setText("请先获取短信验证码");
                state.setTextColor(danger);
                return;
            }
            if (value.isEmpty()) {
                state.setText("请输入短信验证码");
                state.setTextColor(danger);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("phone", smsPhone);
                body.put("code", value);
                body.put("sessionId", smsSessionId);
            } catch (Exception ignored) {
            }
            request("登录账号", "POST", "/native/login", body, result -> {
                smsSessionId = "";
                smsPhone = "";
                dialog.dismiss();
                loadAccounts();
                if (backgroundSyncEnabled()) startPanelSyncService();
                toast("账号已添加");
            });
        });
        dialog.setOnDismissListener(v -> {
            smsSessionId = "";
            smsPhone = "";
        });
        dialog.setContentView(sheet);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(480), getResources().getDisplayMetrics().widthPixels - dp(32));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    private void showCaptchaDialog(String captchaId, CaptchaCallback callback) {
        if (captchaId == null || captchaId.trim().isEmpty()) {
            toast("验证参数为空");
            return;
        }
        GTCaptcha4Client client = GTCaptcha4Client.getClient(this);
        GTCaptcha4Config config = new GTCaptcha4Config.Builder()
                .setLanguage("zho")
                .setCanceledOnTouchOutside(false)
                .setTimeOut(10000)
                .build();
        client.init(captchaId, config)
                .addOnSuccessListener((success, value) -> {
                    client.destroy();
                    if (!success || value == null || value.trim().isEmpty()) {
                        runOnUiThread(() -> toast("人机验证未通过"));
                        return;
                    }
                    runOnUiThread(() -> {
                        try {
                            callback.onResult(new JSONObject(value));
                        } catch (Exception error) {
                            toast("验证结果格式无效");
                        }
                    });
                })
                .addOnFailureListener(error -> {
                    client.destroy();
                    runOnUiThread(() -> toast("人机验证失败：" + (error == null ? "未知错误" : error)));
                })
                .verifyWithCaptcha();
    }

    private void setAppShell() {
        LinearLayout root = vertical(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = horizontal(surface);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(10), dp(14), dp(10));
        pageTitle = text("资产总览", 22, ink, Typeface.BOLD);
        toolbar.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView live = text("本机运行", 12, success, Typeface.BOLD);
        live.setGravity(Gravity.CENTER);
        toolbar.addView(live, marginParams(-2, dp(36), dp(8), 0, 0, 0));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        nav = horizontal(Color.TRANSPARENT);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(12), dp(8), dp(12), dp(8));
        navButtons.clear();
        for (int i = 0; i < PAGE_KEYS.length; i++) {
            final String key = PAGE_KEYS[i];
            Button tab = button(PAGE_LABELS[i], i == 0);
            tab.setTextSize(12);
            tab.setMinWidth(dp(82));
            tab.setPadding(dp(12), 0, dp(12), 0);
            tab.setOnClickListener(v -> selectPage(key));
            navButtons.add(tab);
            nav.addView(tab, marginParams(-2, dp(42), 0, 0, dp(6), 0));
        }
        navScroll.addView(nav, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = vertical(Color.TRANSPARENT);
        content.setPadding(dp(16), dp(14), dp(16), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        statusView = text("", 12, muted, Typeface.NORMAL);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(16), 0, dp(16), 0);
        root.addView(statusView, new LinearLayout.LayoutParams(-1, dp(30)));
        setContentView(root);
        root.requestApplyInsets();
    }

    private void selectPage(String key) {
        if (content == null) return;
        currentPage = key;
        for (int i = 0; i < navButtons.size(); i++) {
            boolean selected = PAGE_KEYS[i].equals(key);
            styleButton(navButtons.get(i), selected);
        }
        pageTitle.setText(pageTitle(key));
        switch (key) {
            case "accounts": showAccounts(); break;
            case "synthesis": showSynthesis(); break;
            case "market": showMarket(); break;
            case "quant": showQuant(); break;
            case "trade": showTrade(); break;
            case "lottery": showLottery(); break;
            case "first-sale": showFirstSale(); break;
            case "settings": showSettings(); break;
            default: showAccounts();
        }
    }

    private String pageTitle(String key) {
        for (int i = 0; i < PAGE_KEYS.length; i++) if (PAGE_KEYS[i].equals(key)) return PAGE_LABELS[i];
        return "资产总览";
    }

    private void showAccounts() {
        content.removeAllViews();
        if (accounts.length() == 0) {
            renderFirstRunWorkspace();
            return;
        }
        pageTitle.setText("资产总览");
        addPageHeading("资产概览", "账号与数字资产");
        LinearLayout welcome = horizontal(0xffe9f7ef);
        welcome.setGravity(Gravity.CENTER_VERTICAL);
        welcome.setPadding(dp(16), dp(14), dp(16), dp(14));
        welcome.setBackground(shape(0xffe9f7ef, 0xffd5ecdd, 18));
        LinearLayout welcomeCopy = vertical(Color.TRANSPARENT);
        welcomeCopy.addView(text("今天也要稳稳运行", 15, ink, Typeface.BOLD));
        welcomeCopy.addView(text("原生引擎已就绪，任务状态随时可查", 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(4), 0, 0));
        welcome.addView(welcomeCopy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text("在线", 12, success, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), 0, dp(10), 0);
        badge.setBackground(shape(0xffd8f0e1, 0xffc0e5cc, 18));
        welcome.addView(badge, new LinearLayout.LayoutParams(dp(54), dp(32)));
        content.addView(welcome, marginParams(-1, -2, 0, 0, 0, dp(12)));
        Button refresh = button("刷新账号", false);
        refresh.setOnClickListener(v -> loadAccounts());
        content.addView(refresh, marginParams(-1, dp(44), 0, 0, 0, dp(12)));
        Button add = button("短信登录 / 添加账号", true);
        add.setOnClickListener(v -> showLoginDialog());
        content.addView(add, marginParams(-1, dp(44), 0, 0, 0, dp(12)));
        renderAccountCards();
    }

    private void renderFirstRunWorkspace() {
        pageTitle.setText("工作台");
        LinearLayout account = card();
        LinearLayout accountHeader = horizontal(Color.TRANSPARENT);
        accountHeader.setGravity(Gravity.CENTER_VERTICAL);
        accountHeader.addView(text("账号", 16, ink, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = text("未登录", 11, amber, Typeface.BOLD);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(10), 0, dp(10), 0);
        state.setBackground(shape(0xfffff6dd, 0xfff0d99a, 14));
        accountHeader.addView(state, new LinearLayout.LayoutParams(-2, dp(28)));
        account.addView(accountHeader, new LinearLayout.LayoutParams(-1, -2));
        account.addView(text("未登录 iBox 账号", 21, ink, Typeface.BOLD), marginParams(-1, -2, 0, dp(12), 0, dp(14)));
        Button addAccount = button("短信登录", true);
        addAccount.setContentDescription("短信登录并添加账号");
        addAccount.setOnClickListener(v -> showLoginDialog());
        account.addView(addAccount, new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(account, marginParams(-1, -2, 0, 0, 0, dp(18)));

        int watchCount = engine.store().getMarketWatches().length();
        int quantCount = engine.store().getQuantStrategies().length();
        int tradeCount = engine.store().getTradeTasks().length();
        int scheduledCount = engine.store().getSynthesisTasks().length()
                + engine.store().getLotteryTasks().length()
                + engine.store().getFirstSaleTasks().length();
        addSectionTitle("运行状态");
        LinearLayout firstRow = horizontal(Color.TRANSPARENT);
        addHomeMetric(firstRow, "行情监控", String.valueOf(watchCount), 0xffedf5fc, 0);
        addHomeMetric(firstRow, "量化策略", String.valueOf(quantCount), 0xffeef8f1, dp(8));
        content.addView(firstRow, marginParams(-1, dp(72), 0, 0, 0, dp(8)));

        LinearLayout secondRow = horizontal(Color.TRANSPARENT);
        addHomeMetric(secondRow, "交易任务", String.valueOf(tradeCount), 0xfffff4e8, 0);
        addHomeMetric(secondRow, "定时任务", String.valueOf(scheduledCount), 0xfffff0f2, dp(8));
        content.addView(secondRow, marginParams(-1, dp(72), 0, 0, 0, dp(18)));

        addSectionTitle("快捷入口");
        addHomeFeatureRow("行情监控", watchCount + " 个监控", "market", infoBlue, "量化策略", quantCount + " 个策略", "quant", primaryDark);
        addHomeFeatureRow("交易执行", tradeCount + " 个任务", "trade", amber, "首发抢购", engine.store().getFirstSaleTasks().length() + " 个任务", "first-sale", danger);
        addHomeFeatureRow("自动抽奖", engine.store().getLotteryTasks().length() + " 个任务", "lottery", 0xff8064bd, "合成任务", engine.store().getSynthesisTasks().length() + " 个任务", "synthesis", 0xff3f9a9e);
        addHomeFeatureRow("资产总览", "未登录", "accounts", 0xff398b96, "设置", backgroundSyncEnabled() ? "后台已开启" : "后台已关闭", "settings", 0xff65707c);
    }

    private void addHomeFeatureRow(String leftTitle, String leftState, String leftPage, int leftAccent, String rightTitle, String rightState, String rightPage, int rightAccent) {
        LinearLayout row = horizontal(Color.TRANSPARENT);
        row.addView(homeFeature(leftTitle, leftState, leftPage, leftAccent), new LinearLayout.LayoutParams(0, dp(92), 1));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(92), 1);
        rightParams.setMargins(dp(8), 0, 0, 0);
        row.addView(homeFeature(rightTitle, rightState, rightPage, rightAccent), rightParams);
        content.addView(row, marginParams(-1, dp(92), 0, 0, 0, dp(8)));
    }

    private void addHomeMetric(LinearLayout row, String label, String value, int fill, int leftMargin) {
        LinearLayout item = vertical(fill);
        item.setPadding(dp(12), dp(10), dp(12), dp(9));
        item.setBackground(shape(fill, 0xffdce8e4, 12));
        item.addView(text(label, 11, muted, Typeface.BOLD));
        item.addView(text(value, 21, ink, Typeface.BOLD), marginParams(-1, -2, 0, dp(3), 0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMargins(leftMargin, 0, 0, 0);
        row.addView(item, params);
    }

    private LinearLayout homeFeature(String title, String subtitle, String page, int accent) {
        LinearLayout feature = card();
        feature.setPadding(dp(14), dp(12), dp(14), dp(11));
        feature.setGravity(Gravity.CENTER_VERTICAL);
        feature.setClickable(true);
        feature.setFocusable(true);
        feature.setContentDescription("打开" + title);
        feature.setOnClickListener(v -> selectPage(page));

        LinearLayout heading = horizontal(Color.TRANSPARENT);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView marker = new TextView(this);
        marker.setBackground(shape(accent, 0, 6));
        heading.addView(marker, new LinearLayout.LayoutParams(dp(9), dp(9)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(dp(8), 0, 0, 0);
        heading.addView(text(title, 15, ink, Typeface.BOLD), titleParams);
        TextView arrow = text(">", 16, accent, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        heading.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(28)));
        feature.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        feature.addView(text(subtitle, 11, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(6), 0, 0));
        return feature;
    }

    private void loadAccounts() {
        request("同步账号", "GET", "/native/accounts", null, result -> {
            accounts = result.optJSONArray("data");
            if (accounts == null) accounts = new JSONArray();
            if (selectedPhone.isEmpty() && accounts.length() > 0) {
                selectedPhone = accounts.optJSONObject(0).optString("phone");
                engine.store().setSelectedPhone(selectedPhone);
            }
            if ("accounts".equals(currentPage)) {
                content.removeAllViews();
                showAccounts();
            }
        });
    }

    private void renderAccounts(JSONObject result) {
        accounts = result.optJSONArray("data");
        if (accounts == null) accounts = new JSONArray();
        if (accounts.length() > 0 && selectedPhone.isEmpty()) {
            selectedPhone = accounts.optJSONObject(0).optString("phone");
            engine.store().setSelectedPhone(selectedPhone);
        }
        selectPage("accounts");
    }

    private void renderAccountCards() {
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account == null) continue;
            String phone = account.optString("phone", "未知账号");
            LinearLayout card = card();
            LinearLayout head = horizontal(Color.TRANSPARENT);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout identity = vertical(Color.TRANSPARENT);
            identity.addView(text(phone, 19, ink, Typeface.BOLD));
            identity.addView(text("用户 ID：" + value(account, "userId", "-") + "\n最近登录：" + time(value(account, "lastLoginAt", "")), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(4), 0, 0));
            head.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            TextView selected = text(phone.equals(selectedPhone) ? "当前" : "切换", 12, phone.equals(selectedPhone) ? success : primary, Typeface.BOLD);
            selected.setGravity(Gravity.CENTER);
            selected.setPadding(dp(10), 0, dp(10), 0);
            selected.setBackground(shape(phone.equals(selectedPhone) ? 0xffe8f7ee : 0xffedf5fc, 0xffdbe9e1, 20));
            selected.setOnClickListener(v -> {
                selectedPhone = phone;
                engine.store().setSelectedPhone(phone);
                content.removeAllViews();
                showAccounts();
            });
            head.addView(selected, marginParams(dp(62), dp(34), dp(8), 0, 0, 0));
            Button remove = button("移除", false);
            remove.setTextColor(danger);
            remove.setOnClickListener(v -> confirmRemoveAccount(phone));
            head.addView(remove, new LinearLayout.LayoutParams(dp(62), dp(34)));
            card.addView(head, marginParams(-1, -2, 0, 0, 0, dp(14)));

            JSONObject cache = account.optJSONObject("assetCache");
            JSONObject assetData = cache == null ? null : cache.optJSONObject("data");
            card.addView(text("数字资产" + (cache != null && cache.optBoolean("stale", false) ? " · 缓存" : ""), 15, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(8)));
            card.addView(metricGrid(assetData), marginParams(-1, -2, 0, 0, 0, dp(12)));
            JSONArray items = findArray(assetData, "items", "collections", "records", "list");
            if (items != null && items.length() > 0) renderAssetRows(card, items);
            else card.addView(text("暂无资产明细，点击刷新读取", 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, 0, 0, dp(10)));
            Button refresh = button("刷新资产", false);
            refresh.setOnClickListener(v -> loadAssets(phone));
            card.addView(refresh, new LinearLayout.LayoutParams(-1, dp(42)));
            content.addView(card, marginParams(-1, -2, 0, 0, 0, dp(14)));
        }
    }

    private void renderAssetRows(LinearLayout card, JSONArray items) {
        int limit = Math.min(8, items.length());
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = first(item, "name", "title", "collectionName", "digitalCollectionName", "id");
            String price = money(first(item, "floorPrice", "price", "valuation", "marketPrice"));
            String quantity = first(item, "quantity", "num", "count", "holdNum");
            LinearLayout row = horizontal(Color.TRANSPARENT);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            LinearLayout info = vertical(Color.TRANSPARENT);
            info.addView(text(name, 14, ink, Typeface.BOLD));
            info.addView(text("数量 " + (quantity.isEmpty() ? "1" : quantity), 11, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(3), 0, 0));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text(price, 14, success, Typeface.BOLD), new LinearLayout.LayoutParams(-2, -2));
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void loadAssets(String phone) {
        request("刷新资产", "GET", "/native/accounts/" + Uri.encode(phone) + "/assets?refresh=1", null, result -> {
            toast("资产已同步");
            loadAccounts();
        });
    }

    private void confirmRemoveAccount(String phone) {
        new AlertDialog.Builder(this).setTitle("移除账号").setMessage("移除后会暂停该账号未完成任务。")
                .setNegativeButton("取消", null).setPositiveButton("确认移除", (dialog, which) ->
                        request("移除账号", "POST", "/native/accounts/" + Uri.encode(phone) + "/remove", null, result -> {
                            if (phone.equals(selectedPhone)) selectedPhone = engine.store().getSelectedPhone();
                            loadAccounts();
                        })).show();
    }

    private void showSynthesis() {
        content.removeAllViews();
        addPageHeading("合成任务", "活动、材料与定时任务");
        Button refresh = button("刷新任务", false);
        refresh.setOnClickListener(v -> showSynthesis());
        content.addView(refresh, marginParams(-1, dp(44), 0, 0, 0, dp(12)));
        request("同步合成任务", "GET", "/native/synthesis/tasks", null, result -> {
            JSONArray tasks = findArray(result.optJSONObject("data"), "tasks", "items", "list");
            renderTaskList(tasks, "合成任务", content, true);
            if (!selectedPhone.isEmpty()) {
                Button activities = button("读取当前账号活动", false);
                activities.setOnClickListener(v -> loadSynthesisActivities());
                content.addView(activities, marginParams(-1, dp(44), 0, 0, 0, dp(12)));
            }
        });
    }

    private void loadSynthesisActivities() {
        request("同步合成活动", "GET", "/native/accounts/" + Uri.encode(selectedPhone) + "/synthesis/activities", null, result -> {
            JSONObject data = result.optJSONObject("data");
            JSONArray activities = findArray(data, "activities", "items", "list");
            if (activities == null || activities.length() == 0) {
                content.addView(empty("当前没有可用合成活动"));
                return;
            }
            addSectionTitle("可用活动");
            for (int i = 0; i < activities.length(); i++) {
                JSONObject activity = activities.optJSONObject(i);
                if (activity == null) continue;
                String title = first(activity, "title", "name", "activityName", "id");
                String phase = first(activity, "phase", "status", "startTime");
                LinearLayout row = card();
                row.addView(text(title, 15, ink, Typeface.BOLD));
                row.addView(text(phase, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
                String syntheticId = first(activity, "syntheticId", "id");
                LinearLayout actions = horizontal(Color.TRANSPARENT);
                Button detail = button("材料", false);
                detail.setOnClickListener(v -> loadSynthesisCenter(syntheticId));
                Button immediate = button("立即合成", true);
                immediate.setOnClickListener(v -> showSynthesisDialog(activity, false));
                Button schedule = button("定时", false);
                schedule.setOnClickListener(v -> showSynthesisDialog(activity, true));
                actions.addView(detail, new LinearLayout.LayoutParams(0, dp(42), 1));
                actions.addView(immediate, marginParams(dp(92), dp(42), dp(8), 0, 0, 0));
                actions.addView(schedule, marginParams(dp(72), dp(42), dp(8), 0, 0, 0));
                row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
                content.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void loadSynthesisCenter(String syntheticId) {
        if (syntheticId.isEmpty()) return;
        request("读取材料", "GET", "/native/accounts/" + Uri.encode(selectedPhone) + "/synthesis/" + Uri.encode(syntheticId), null, result -> {
            JSONObject data = result.optJSONObject("data");
            showJsonDialog("合成材料", data);
        });
    }

    private void showSynthesisDialog(JSONObject activity, boolean scheduled) {
        if (selectedPhone.isEmpty()) {
            toast("请先选择账号");
            return;
        }
        String syntheticId = first(activity, "syntheticId", "id");
        if (syntheticId.isEmpty()) {
            toast("合成编号为空");
            return;
        }
        EditText count = numberInput("合成数量", "1");
        LinearLayout form = vertical(Color.TRANSPARENT);
        form.addView(text(first(activity, "title", "name", "activityName", syntheticId), 15, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(10)));
        form.addView(count, new LinearLayout.LayoutParams(-1, dp(46)));
        String title = scheduled ? "加入合成定时任务" : "立即提交合成";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(form)
                .setNegativeButton("取消", null).setPositiveButton("确认", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            JSONObject body = new JSONObject();
            try {
                body.put("syntheticId", syntheticId);
                body.put("syntheticNum", intValue(count, 1));
                body.put("phones", new JSONArray().put(selectedPhone));
                body.put("sourcePhone", selectedPhone);
                if (scheduled) body.put("startAt", first(activity, "startAt", "startTime", "beginTime"));
            } catch (Exception ignored) {
            }
            String path = scheduled ? "/native/synthesis/tasks" : "/native/accounts/" + Uri.encode(selectedPhone) + "/synthesis/" + Uri.encode(syntheticId) + "/submit";
            request(scheduled ? "保存合成任务" : "提交合成", "POST", path, body, result -> {
                dialog.dismiss();
                if (scheduled) toast("合成任务已保存");
                else showJsonDialog("合成结果", result.optJSONObject("data"));
                showSynthesis();
            });
        }));
        dialog.show();
    }

    private void showMarket() {
        content.removeAllViews();
        addPageHeading("行情监控", "价格基准与涨跌阈值");
        LinearLayout search = horizontal(Color.TRANSPARENT);
        EditText query = input("搜索藏品名称");
        Button searchButton = button("搜索", true);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        search.addView(searchButton, marginParams(dp(76), dp(48), dp(8), 0, 0, 0));
        content.addView(search, marginParams(-1, -2, 0, 0, 0, dp(12)));
        LinearLayout resultBox = vertical(Color.TRANSPARENT);
        content.addView(resultBox, marginParams(-1, -2, 0, 0, 0, dp(8)));
        searchButton.setOnClickListener(v -> searchMarket(query.getText().toString(), resultBox));
        Button refresh = button("刷新监控", false);
        content.addView(refresh, marginParams(-1, dp(42), 0, 0, 0, dp(12)));
        LinearLayout watchBox = vertical(Color.TRANSPARENT);
        content.addView(watchBox, new LinearLayout.LayoutParams(-1, -2));
        refresh.setOnClickListener(v -> loadWatches(watchBox, true));
        loadWatches(watchBox);
    }

    private void searchMarket(String name, LinearLayout target) {
        if (name == null || name.trim().isEmpty()) {
            target.removeAllViews();
            target.addView(empty("输入名称后搜索"));
            return;
        }
        request("搜索行情", "GET", "/native/market?name=" + Uri.encode(name.trim()) + "&pageSize=20&phone=" + Uri.encode(selectedPhone), null, result -> {
            target.removeAllViews();
            JSONArray items = findArray(result.optJSONObject("data"), "items", "list", "records");
            if (items == null || items.length() == 0) {
                target.addView(empty("没有找到结果"));
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                LinearLayout row = card();
                row.addView(text(first(item, "name", "title", "groupId", "id"), 15, ink, Typeface.BOLD));
                row.addView(text("编号 " + first(item, "groupId", "id") + " · 地板 " + money(first(item, "floorPrice", "price")), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
                Button add = button("加入监控", false);
                add.setOnClickListener(v -> addWatch(item));
                row.addView(add, new LinearLayout.LayoutParams(-1, dp(42)));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void loadWatches(LinearLayout target) {
        loadWatches(target, false);
    }

    private void loadWatches(LinearLayout target, boolean refresh) {
        request(refresh ? "刷新行情监控" : "同步行情监控", "GET", "/native/market/watches" + (refresh ? "?refresh=1" : ""), null, result -> {
            target.removeAllViews();
            JSONArray watches = findArray(result.optJSONObject("data"), "watches", "items", "list");
            if (watches == null || watches.length() == 0) {
                target.addView(empty("暂无价格监控项目"));
                return;
            }
            for (int i = 0; i < watches.length(); i++) {
                JSONObject watch = watches.optJSONObject(i);
                if (watch == null) continue;
                LinearLayout row = card();
                row.addView(text(first(watch, "name", "collectionId", "id"), 15, ink, Typeface.BOLD));
                String price = money(first(watch, "latestPrice", "currentPrice", "price"));
                String change = first(watch, "changePercent", "change");
                row.addView(text(price + " · 变化 " + (change.isEmpty() ? "--" : change + "%") + "\n提醒 涨≥" + number(watch, "riseThresholdPercent", "3") + "% / 跌≥" + number(watch, "fallThresholdPercent", "3") + "%", 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(10), 0, 0));
                LinearLayout actions = horizontal(Color.TRANSPARENT);
                Button threshold = button("阈值", false);
                threshold.setOnClickListener(v -> showThresholdDialog(watch, target));
                Button stop = button("停止", false);
                stop.setTextColor(danger);
                stop.setOnClickListener(v -> cancelWatch(watch.optString("id"), target));
                actions.addView(threshold, new LinearLayout.LayoutParams(0, dp(42), 1));
                actions.addView(stop, marginParams(dp(82), dp(42), dp(8), 0, 0, 0));
                row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void addWatch(JSONObject item) {
        JSONObject body = new JSONObject();
        try {
                body.put("collectionId", first(item, "collectionId", "groupId", "id"));
                body.put("name", first(item, "name", "title", "groupId", "id"));
                body.put("searchTerm", first(item, "searchTerm", "name", "title"));
                body.put("floorPrice", first(item, "floorPrice", "price"));
                body.put("watchToken", first(item, "watchToken", "token"));
            body.put("riseThresholdPercent", 3);
            body.put("fallThresholdPercent", 3);
        } catch (Exception ignored) { }
        request("加入行情监控", "POST", "/native/market/watches", body, result -> toast("已加入行情监控"));
    }

    private void showThresholdDialog(JSONObject watch, LinearLayout target) {
        LinearLayout form = vertical(Color.TRANSPARENT);
        EditText rise = numberInput("上涨阈值 %", number(watch, "riseThresholdPercent", "3"));
        EditText fall = numberInput("下跌阈值 %", number(watch, "fallThresholdPercent", "3"));
        form.addView(rise, marginParams(-1, dp(48), 0, 0, 0, dp(10)));
        form.addView(fall, new LinearLayout.LayoutParams(-1, dp(48)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("行情提醒阈值").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            double riseValue = parseDouble(rise.getText().toString(), -1);
            double fallValue = parseDouble(fall.getText().toString(), -1);
            if (riseValue < .1 || riseValue > 100 || fallValue < .1 || fallValue > 100) {
                toast("阈值范围为 0.1% 到 100%");
                return;
            }
            JSONObject body = new JSONObject();
            try { body.put("riseThresholdPercent", riseValue); body.put("fallThresholdPercent", fallValue); } catch (Exception ignored) { }
            request("保存阈值", "PUT", "/native/market/watches/" + Uri.encode(watch.optString("id")) + "/thresholds", body, result -> {
                dialog.dismiss();
                loadWatches(target);
            });
        }));
        dialog.show();
    }

    private void cancelWatch(String id, LinearLayout target) {
        if (id.isEmpty()) return;
        request("停止监控", "POST", "/native/market/watches/" + Uri.encode(id) + "/cancel", null, result -> loadWatches(target));
    }

    private void showQuant() {
        content.removeAllViews();
        addPageHeading("量化策略", "监控、执行与事件记录");
        LinearLayout search = horizontal(Color.TRANSPARENT);
        EditText query = input("搜索策略目标");
        Button find = button("搜索", true);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        search.addView(find, marginParams(dp(76), dp(48), dp(8), 0, 0, 0));
        content.addView(search, marginParams(-1, -2, 0, 0, 0, dp(12)));
        LinearLayout targetBox = vertical(Color.TRANSPARENT);
        content.addView(targetBox, marginParams(-1, -2, 0, 0, 0, dp(10)));
        find.setOnClickListener(v -> searchQuant(query.getText().toString(), targetBox));
        LinearLayout list = vertical(Color.TRANSPARENT);
        content.addView(text("已配置策略", 17, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(8)));
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        loadQuantStrategies(list);
    }

    private void searchQuant(String name, LinearLayout target) {
        if (name == null || name.trim().isEmpty()) return;
        request("搜索量化目标", "GET", "/native/market?name=" + Uri.encode(name.trim()) + "&pageSize=20&phone=" + Uri.encode(selectedPhone), null, result -> {
            target.removeAllViews();
            JSONArray items = findArray(result.optJSONObject("data"), "items", "list", "records");
            if (items == null || items.length() == 0) { target.addView(empty("没有找到目标")); return; }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                LinearLayout row = card();
                row.addView(text(first(item, "name", "title", "groupId", "id"), 15, ink, Typeface.BOLD));
                row.addView(text("当前价 " + money(first(item, "floorPrice", "price")), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
                Button configure = button("配置策略", false);
                configure.setOnClickListener(v -> showQuantDialog(item, null));
                row.addView(configure, new LinearLayout.LayoutParams(-1, dp(42)));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void loadQuantStrategies(LinearLayout target) {
        request("同步量化策略", "GET", "/native/quant/strategies", null, result -> {
            target.removeAllViews();
            JSONArray strategies = findArray(result.optJSONObject("data"), "strategies", "items", "list");
            if (strategies == null || strategies.length() == 0) { target.addView(empty("暂无策略")); return; }
            for (int i = 0; i < strategies.length(); i++) {
                JSONObject strategy = strategies.optJSONObject(i);
                if (strategy == null) continue;
                LinearLayout row = card();
                String status = strategyStatus(strategy.optString("status"));
                row.addView(text(first(strategy, "title", "groupId", "id") + " · " + status, 15, ink, Typeface.BOLD));
                row.addView(text(strategyMeta(strategy), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(10), 0, 0));
                LinearLayout actions = horizontal(Color.TRANSPARENT);
                String strategyState = strategy.optString("status");
                if ("verification_required".equals(strategyState)) {
                    Button verify = button("验证", true);
                    verify.setOnClickListener(v -> startTaskCaptcha("量化", strategy.optString("id"), strategy.optString("phone")));
                    actions.addView(verify, new LinearLayout.LayoutParams(0, dp(42), 1));
                } else if ("monitoring".equals(strategyState)) {
                    Button pause = button("暂停", false);
                    pause.setOnClickListener(v -> quantAction(strategy.optString("id"), "disable", target));
                    actions.addView(pause, new LinearLayout.LayoutParams(0, dp(42), 1));
                } else if (!isTerminalTaskStatus(strategyState)) {
                    Button start = button("启动", false);
                    start.setOnClickListener(v -> quantAction(strategy.optString("id"), "enable", target));
                    actions.addView(start, new LinearLayout.LayoutParams(0, dp(42), 1));
                }
                Button events = button("记录", false);
                events.setOnClickListener(v -> loadQuantEvents(strategy.optString("id")));
                actions.addView(events, marginParams(dp(76), dp(42), dp(8), 0, 0, 0));
                String cashierLink = first(strategy, "cashierLink", "paymentUrl");
                if (!cashierLink.isEmpty() && "payment_pending".equals(strategy.optString("status"))) {
                    final String link = cashierLink;
                    Button pay = button("支付", true);
                    pay.setOnClickListener(v -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                        } catch (Exception error) {
                            toast("无法打开支付入口");
                        }
                    });
                    actions.addView(pay, marginParams(dp(76), dp(42), dp(8), 0, 0, 0));
                }
                Button delete = button("删除", false);
                delete.setTextColor(danger);
                delete.setOnClickListener(v -> quantDelete(strategy.optString("id"), target));
                actions.addView(delete, new LinearLayout.LayoutParams(dp(76), dp(42)));
                row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void showQuantDialog(JSONObject target, JSONObject existing) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = vertical(Color.TRANSPARENT);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        String title = first(target, "name", "title", "groupId", "id");
        form.addView(text(title, 16, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(12)));
        Spinner mode = spinner(new String[]{"monitor", "live"}, existing == null ? "monitor" : existing.optString("executionMode", "monitor"));
        form.addView(labelled("执行方式", mode));
        CheckBox buyEnabled = check("启用买入", existing == null || existing.optJSONObject("buy") == null || existing.optJSONObject("buy").optBoolean("enabled", true));
        EditText buyPrice = numberInput("最高买入价", existing == null ? moneyValue(first(target, "floorPrice", "price")) : number(existing.optJSONObject("buy"), "maxPrice", ""));
        form.addView(buyEnabled); form.addView(buyPrice, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        CheckBox sellEnabled = check("启用卖出", existing != null && existing.optJSONObject("sell") != null && existing.optJSONObject("sell").optBoolean("enabled", false));
        EditText sellTrigger = numberInput("卖出触发行情价", existing == null ? "" : number(existing.optJSONObject("sell"), "minPrice", ""));
        EditText sellPrice = numberInput("寄售价（整数）", existing == null ? "" : number(existing.optJSONObject("sell"), "sellPrice", ""));
        form.addView(sellEnabled); form.addView(sellTrigger, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(sellPrice, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText maxPosition = numberInput("最大持仓", existing == null ? "1" : number(existing, "maxPosition", "1"));
        EditText minProfit = numberInput("最低单件预期净利", existing == null ? "0" : number(existing, "minNetProfit", "0"));
        EditText volatility = numberInput("单周期最大波动 %", existing == null ? "0" : number(existing, "volatilityLimitPercent", "0"));
        EditText cooldown = numberInput("触发后冷却分钟", existing == null ? "10" : number(existing, "cooldownMinutes", "10"));
        EditText interval = numberInput("监控间隔", existing == null ? "15" : number(existing, "intervalValue", "15"));
        Spinner intervalUnit = spinner(new String[]{"seconds", "minutes", "hours"}, existing == null ? "seconds" : existing.optString("intervalUnit", "seconds"));
        EditText password = passwordInput("交易密码（真实执行时填写）");
        form.addView(maxPosition, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(minProfit, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(volatility, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(cooldown, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(interval, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(labelled("间隔单位", intervalUnit)); form.addView(password, marginParams(-1, dp(46), 0, 0, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "配置量化策略" : "修改量化策略").setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            JSONObject body = new JSONObject();
            try {
                body.put("phone", selectedPhone); body.put("groupId", first(target, "groupId", "id")); body.put("title", title); body.put("cover", first(target, "cover", "image"));
                body.put("executionMode", mode.getSelectedItem().toString()); body.put("intervalValue", intValue(interval, 15)); body.put("intervalUnit", intervalUnit.getSelectedItem().toString());
                body.put("maxPosition", intValue(maxPosition, 1)); body.put("minNetProfit", doubleValue(minProfit, 0)); body.put("volatilityLimitPercent", doubleValue(volatility, 0)); body.put("cooldownMinutes", intValue(cooldown, 10));
                JSONObject buy = new JSONObject(); buy.put("enabled", buyEnabled.isChecked()); buy.put("maxPrice", doubleValue(buyPrice, 0)); buy.put("quantity", 1); body.put("buy", buy);
                JSONObject sell = new JSONObject(); sell.put("enabled", sellEnabled.isChecked()); sell.put("minPrice", doubleValue(sellTrigger, 0)); sell.put("sellPrice", intValue(sellPrice, 0)); sell.put("quantity", 1); body.put("sell", sell);
                JSONObject stopLoss = new JSONObject(); stopLoss.put("enabled", false); body.put("stopLoss", stopLoss);
                if (!password.getText().toString().trim().isEmpty()) body.put("consignPassword", password.getText().toString().trim());
            } catch (Exception ignored) { }
            String path = existing == null ? "/native/quant/strategies" : "/native/quant/strategies/" + Uri.encode(existing.optString("id"));
            request("保存策略", existing == null ? "POST" : "PUT", path, body, result -> { dialog.dismiss(); selectPage("quant"); });
        }));
        dialog.show();
    }

    private void quantAction(String id, String action, ViewGroup target) {
        request(action.equals("enable") ? "启动策略" : "暂停策略", "POST", "/native/quant/strategies/" + Uri.encode(id) + "/" + action, null, result -> loadQuantStrategies((LinearLayout) target));
    }

    private void quantDelete(String id, ViewGroup target) {
        request("删除策略", "DELETE", "/native/quant/strategies/" + Uri.encode(id), null, result -> loadQuantStrategies((LinearLayout) target));
    }

    private void loadQuantEvents(String id) {
        request("读取执行记录", "GET", "/native/quant/strategies/" + Uri.encode(id) + "/events", null, result -> showJsonDialog("量化执行记录", result.optJSONObject("data")));
    }

    private void showTrade() {
        content.removeAllViews();
        addPageHeading("交易执行", "市场搜索、任务与订单");
        LinearLayout search = horizontal(Color.TRANSPARENT);
        EditText query = input("输入藏品名称");
        Button find = button("搜索", true);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1)); search.addView(find, marginParams(dp(76), dp(48), dp(8), 0, 0, 0));
        content.addView(search, marginParams(-1, -2, 0, 0, 0, dp(12)));
        LinearLayout results = vertical(Color.TRANSPARENT); content.addView(results, marginParams(-1, -2, 0, 0, 0, dp(10)));
        find.setOnClickListener(v -> searchTrade(query.getText().toString(), results));
        addSectionTitle("交易任务");
        LinearLayout tasks = vertical(Color.TRANSPARENT); content.addView(tasks, marginParams(-1, -2, 0, 0, 0, dp(12)));
        loadTradeTasks(tasks);
        addSectionTitle("订单记录");
        LinearLayout orders = vertical(Color.TRANSPARENT); content.addView(orders, new LinearLayout.LayoutParams(-1, -2));
        loadOrders(orders);
    }

    private void searchTrade(String name, LinearLayout target) {
        if (name == null || name.trim().isEmpty()) return;
        request("搜索交易目标", "GET", "/native/market?name=" + Uri.encode(name.trim()) + "&pageSize=20&phone=" + Uri.encode(selectedPhone), null, result -> {
            target.removeAllViews(); JSONArray items = findArray(result.optJSONObject("data"), "items", "list", "records");
            if (items == null || items.length() == 0) { target.addView(empty("没有可交易藏品")); return; }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                LinearLayout row = card(); row.addView(text(first(item, "name", "title", "groupId", "id"), 15, ink, Typeface.BOLD));
                row.addView(text("编号 " + first(item, "groupId", "id") + " · 地板 " + money(first(item, "floorPrice", "price")), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
                LinearLayout actions = horizontal(Color.TRANSPARENT);
                Button configure = button("交易任务", false); configure.setOnClickListener(v -> showTradeDialog(item));
                Button retired = button("捡漏", false); retired.setOnClickListener(v -> showRetiredDialog(item));
                actions.addView(configure, new LinearLayout.LayoutParams(0, dp(42), 1));
                actions.addView(retired, marginParams(dp(76), dp(42), dp(8), 0, 0, 0));
                row.addView(actions, new LinearLayout.LayoutParams(-1, -2)); target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void showRetiredDialog(JSONObject item) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        LinearLayout form = vertical(Color.TRANSPARENT);
        EditText min = numberInput("区间最低价", "0");
        EditText max = numberInput("区间最高价", moneyValue(first(item, "floorPrice", "price")));
        form.addView(min, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        form.addView(max, new LinearLayout.LayoutParams(-1, dp(46)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("配置捡漏任务").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存并启动", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            JSONObject body = new JSONObject();
            try {
                body.put("phone", selectedPhone);
                body.put("groupId", first(item, "groupId", "id"));
                body.put("title", first(item, "name", "title"));
                body.put("cover", first(item, "cover", "image"));
                body.put("minPrice", doubleValue(min, 0));
                body.put("maxPrice", doubleValue(max, 0));
                body.put("autoStart", true);
            } catch (Exception ignored) {
            }
            request("保存捡漏任务", "POST", "/native/retired-market/tasks", body, result -> {
                dialog.dismiss();
                toast("捡漏任务已保存");
                showTrade();
            });
        }));
        dialog.show();
    }

    private void showTradeDialog(JSONObject item) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        ScrollView scroll = new ScrollView(this); LinearLayout form = vertical(Color.TRANSPARENT); scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        Spinner type = spinner(new String[]{"wanted", "consignment"}, "wanted"); form.addView(labelled("任务类型", type));
        EditText price = numberInput("价格", moneyValue(first(item, "floorPrice", "price"))); form.addView(price, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText trigger = numberInput("寄售触发行价（寄售时填写）", ""); form.addView(trigger, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText quantity = numberInput("数量", "1"); form.addView(quantity, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText interval = numberInput("监控间隔（秒）", "5"); form.addView(interval, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText paymentCode = numberInput("求购支付通道编号", ""); form.addView(paymentCode, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        CheckBox agreement = check("我已阅读并同意交易服务协议", false); form.addView(agreement, marginParams(-1, -2, 0, 0, 0, dp(4)));
        EditText password = passwordInput("寄售交易密码"); form.addView(password, new LinearLayout.LayoutParams(-1, dp(46)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("创建交易任务").setView(scroll).setNegativeButton("取消", null).setPositiveButton("提交", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            JSONObject body = new JSONObject();
            try {
                String taskType = type.getSelectedItem().toString(); body.put("type", taskType); body.put("phone", selectedPhone); body.put("groupId", first(item, "groupId", "id")); body.put("digitalCollectionId", first(item, "digitalCollectionId", "collectionId", "id")); body.put("title", first(item, "name", "title")); body.put("cover", first(item, "cover", "image"));
                body.put("price", doubleValue(price, 0)); body.put("quantity", intValue(quantity, 1)); body.put("autoStart", true);
                body.put("paymentPlatformCode", intValue(paymentCode, 0)); body.put("agreementAccepted", agreement.isChecked());
                if ("consignment".equals(taskType)) { body.put("triggerPrice", doubleValue(trigger, 0)); body.put("monitorIntervalValue", intValue(interval, 5)); body.put("monitorIntervalUnit", "seconds"); body.put("consignPassword", password.getText().toString().trim()); }
            } catch (Exception ignored) { }
            request("创建交易任务", "POST", "/native/market/trade/tasks", body, result -> { dialog.dismiss(); selectPage("trade"); });
        }));
        dialog.show();
    }

    private void loadTradeTasks(LinearLayout target) {
        request("同步交易任务", "GET", "/native/market/trade/tasks", null, tradeResult -> request("同步捡漏任务", "GET", "/native/retired-market/tasks", null, retiredResult -> {
            target.removeAllViews();
            renderTaskList(findArray(tradeResult.optJSONObject("data"), "tasks", "items", "list"), "交易", target, false);
            renderTaskList(findArray(retiredResult.optJSONObject("data"), "tasks", "items", "list"), "捡漏", target, false);
        }));
    }

    private void loadOrders(LinearLayout target) {
        request("同步订单", "GET", "/native/orders?phone=" + Uri.encode(selectedPhone), null, result -> {
            target.removeAllViews(); JSONObject data = result.optJSONObject("data");
            if (data == null) { target.addView(empty("暂无订单")); return; }
            JSONArray pending = findArray(data, "pendingOrders", "pending", "orders");
            if (pending == null || pending.length() == 0) { target.addView(empty("暂无订单")); return; }
            renderTaskList(pending, "平台订单", target, false);
        });
    }

    private void renderTaskList(JSONArray tasks, String prefix, LinearLayout target, boolean allowCancel) {
        if (tasks == null || tasks.length() == 0) { if (target.getChildCount() == 0) target.addView(empty("暂无" + prefix)); return; }
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            LinearLayout row = card(); row.addView(text(prefix + " · " + first(task, "title", "name", "groupId", "id"), 14, ink, Typeface.BOLD));
            row.addView(text("状态 " + strategyStatus(first(task, "status", "lastResult", "phase")) + "\n账号 " + first(task, "phone", "sourcePhone", "-"), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
            String id = task.optString("id");
            String status = task.optString("status", "");
            if ("verification_required".equals(status)) {
                Button verify = button("完成人机验证", true);
                String phone = first(task, "phone", "sourcePhone");
                verify.setOnClickListener(v -> startTaskCaptcha(prefix, id, phone));
                row.addView(verify, marginParams(-1, dp(40), 0, dp(4), 0, dp(4)));
            }
            String cashierLink = first(task, "cashierLink", "paymentUrl");
            if (cashierLink.isEmpty() && task.optJSONObject("runs") != null) cashierLink = firstCashierLink(task.optJSONObject("runs"));
            if (!cashierLink.isEmpty() && ("payment_pending".equals(status) || "partial".equals(status))) {
                final String link = cashierLink;
                Button wallet = button("打开钱包支付", true);
                wallet.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                    } catch (Exception error) {
                        toast("无法打开支付入口");
                    }
                });
                row.addView(wallet, marginParams(-1, dp(40), 0, dp(4), 0, dp(4)));
            }
            if (!id.isEmpty() && allowCancel) {
                Button cancel = button("取消任务", false);
                cancel.setTextColor(danger);
                cancel.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("取消任务")
                        .setMessage("取消后不会再按计划执行，是否继续？")
                        .setNegativeButton("返回", null)
                        .setPositiveButton("确认取消", (dialog, which) -> {
                            String path = prefix.startsWith("合成")
                                    ? "/native/synthesis/tasks/" + Uri.encode(id) + "/cancel"
                                    : "/native/first-sales/tasks/" + Uri.encode(id) + "/cancel";
                            request("取消" + prefix, "POST", path, null, result -> {
                                target.removeAllViews();
                                if (prefix.startsWith("合成")) showSynthesis(); else showFirstSale();
                            });
                        }).show());
                row.addView(cancel, new LinearLayout.LayoutParams(-1, dp(40)));
            } else if (!id.isEmpty() && (prefix.equals("交易") || prefix.equals("捡漏"))) {
                boolean enabled = task.optBoolean("enabled", false);
                String base = prefix.equals("交易") ? "/native/market/trade/tasks/" : "/native/retired-market/tasks/";
                LinearLayout actions = horizontal(Color.TRANSPARENT);
                if (!isTerminalTaskStatus(status)) {
                    Button toggle = button(enabled ? "暂停" : "启动", false);
                    toggle.setOnClickListener(v -> {
                        String action = enabled ? "disable" : "enable";
                        request(enabled ? "暂停任务" : "启动任务", "POST", base + Uri.encode(id) + "/" + action, null, result -> showTrade());
                    });
                    actions.addView(toggle, new LinearLayout.LayoutParams(0, dp(40), 1));
                }
                if ("payment_pending".equals(task.optString("status"))) {
                    Button payment = button("支付", true);
                    payment.setOnClickListener(v -> request("获取支付链接", "POST", base + Uri.encode(id) + "/payment", null, result -> {
                        String link = first(result.optJSONObject("data"), "cashierLink", "paymentUrl");
                        if (!link.isEmpty()) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                            } catch (Exception error) {
                                toast("无法打开支付链接");
                            }
                        } else showJsonDialog("支付信息", result.optJSONObject("data"));
                    }));
                    actions.addView(payment, marginParams(dp(76), dp(40), dp(8), 0, 0, 0));
                }
                Button delete = button("删除", false);
                delete.setTextColor(danger);
                delete.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("删除任务").setMessage("确认删除这条任务？")
                        .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> request("删除任务", "DELETE", base + Uri.encode(id), null, result -> showTrade())).show());
                actions.addView(delete, marginParams(dp(76), dp(40), dp(8), 0, 0, 0));
                row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            } else if (!id.isEmpty() && !prefix.contains("订单")) {
                Button action = button("刷新状态", false);
                action.setOnClickListener(v -> toast("任务状态已请求刷新"));
                row.addView(action, new LinearLayout.LayoutParams(-1, dp(40)));
            }
            target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
        }
    }

    private String firstCashierLink(JSONObject runs) {
        Iterator<String> keys = runs.keys();
        while (keys.hasNext()) {
            JSONObject run = runs.optJSONObject(keys.next());
            String link = first(run, "cashierLink", "paymentUrl");
            if (!link.isEmpty()) return link;
        }
        return "";
    }

    private void startTaskCaptcha(String prefix, String taskId, String phone) {
        if (taskId == null || taskId.isEmpty()) return;
        String kind;
        String base;
        if (prefix.startsWith("首发")) {
            kind = "first-sale";
            base = "/native/first-sales/tasks/" + Uri.encode(taskId) + "/captcha";
        } else if (prefix.startsWith("量化")) {
            kind = "quant";
            base = "/native/quant/strategies/" + Uri.encode(taskId) + "/captcha";
        } else if (prefix.startsWith("捡漏")) {
            kind = "retired";
            base = "/native/retired-market/tasks/" + Uri.encode(taskId) + "/captcha";
        } else {
            kind = "trade";
            base = "/native/market/trade/tasks/" + Uri.encode(taskId) + "/captcha";
        }
        JSONObject body = null;
        if ("first-sale".equals(kind)) {
            body = new JSONObject();
            try {
                body.put("phone", phone);
            } catch (Exception ignored) {
            }
        }
        JSONObject sessionBody = body;
        request("创建验证会话", "POST", base + "/session", sessionBody, result -> {
            JSONObject data = result.optJSONObject("data");
            String sessionId = data == null ? "" : data.optString("sessionId", "");
            String captchaId = data == null ? "" : data.optString("captchaId", "");
            showCaptchaDialog(captchaId, captcha -> {
                JSONObject submit = new JSONObject();
                try {
                    submit.put("captcha", captcha);
                    if ("first-sale".equals(kind)) submit.put("phone", phone);
                } catch (Exception ignored) {
                }
                request("提交人机验证", "POST", base + "/" + Uri.encode(sessionId), submit, response -> {
                    toast("验证已提交");
                    if (kind.equals("quant")) selectPage("quant");
                    else if (kind.equals("first-sale")) showFirstSale();
                    else showTrade();
                });
            });
        });
    }

    private void showLottery() {
        content.removeAllViews(); addPageHeading("自动抽奖", "活动与账号状态");
        Button refresh = button("刷新抽奖状态", false); content.addView(refresh, marginParams(-1, dp(44), 0, 0, 0, dp(12)));
        LinearLayout list = vertical(Color.TRANSPARENT); content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        refresh.setOnClickListener(v -> loadLottery(list)); loadLottery(list);
    }

    private void loadLottery(LinearLayout target) {
        request("同步抽奖状态", "GET", "/native/lottery/auto", null, result -> {
            target.removeAllViews(); JSONArray activities = findArray(result.optJSONObject("data"), "activities", "items", "list");
            if (activities == null || activities.length() == 0) { target.addView(empty("暂无抽奖活动")); return; }
            for (int i = 0; i < activities.length(); i++) {
                JSONObject activity = activities.optJSONObject(i); if (activity == null) continue;
                LinearLayout row = card(); row.addView(text(first(activity, "title", "name", "id"), 15, ink, Typeface.BOLD));
                row.addView(text("状态 " + first(activity, "phase", "status", "onlineStatus") + "\n" + first(activity, "startTime", "endTime", "updatedAt"), 12, muted, Typeface.NORMAL));
                String id = activity.optString("id");
                boolean enabled = activity.optBoolean("enabled", false);
                Button toggle = button(enabled ? "停止自动抽奖" : "开启自动抽奖", enabled);
                toggle.setOnClickListener(v -> request(enabled ? "停止自动抽奖" : "开启自动抽奖", "POST", "/native/lottery/auto/" + Uri.encode(id) + "/" + (enabled ? "disable" : "enable"), null, ignored -> loadLottery(target)));
                row.addView(toggle, marginParams(-1, dp(42), 0, dp(8), 0, 0));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void showFirstSale() {
        content.removeAllViews(); addPageHeading("首发抢购", "发售项目与任务");
        Button refresh = button("刷新首发项目", false); content.addView(refresh, marginParams(-1, dp(44), 0, 0, 0, dp(12)));
        LinearLayout list = vertical(Color.TRANSPARENT); content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        refresh.setOnClickListener(v -> loadFirstSales(list)); loadFirstSales(list);
    }

    private void loadFirstSales(LinearLayout target) {
        request("同步首发项目", "GET", "/native/first-sales?refresh=1&phone=" + Uri.encode(selectedPhone), null, result -> {
            target.removeAllViews(); JSONArray items = findArray(result.optJSONObject("data"), "items", "sales", "list");
            if (items == null || items.length() == 0) { target.addView(empty("暂无首发项目")); return; }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                LinearLayout row = card(); row.addView(text(first(item, "title", "name", "saleId", "id"), 15, ink, Typeface.BOLD));
                row.addView(text(money(first(item, "price", "salePrice")) + " · " + first(item, "phase", "status", "startTime"), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
                Button prepare = button("加入任务", false); prepare.setOnClickListener(v -> showFirstSaleDialog(item));
                row.addView(prepare, new LinearLayout.LayoutParams(-1, dp(42))); target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
            loadFirstSaleTasks(target);
        });
    }

    private void loadFirstSaleTasks(LinearLayout target) {
        request("同步首发任务", "GET", "/native/first-sales/tasks", null, result -> {
            JSONArray tasks = findArray(result.optJSONObject("data"), "tasks", "items", "list");
            addSectionTitle("抢购任务"); renderTaskList(tasks, "首发", target, true);
        });
    }

    private void showFirstSaleDialog(JSONObject item) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        LinearLayout form = vertical(Color.TRANSPARENT);
        Spinner mode = spinner(new String[]{"scheduled", "immediate"}, "scheduled");
        EditText count = numberInput("购买数量", "1"); EditText paymentCode = numberInput("支付通道编号", "");
        form.addView(labelled("提交方式", mode));
        form.addView(count, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(paymentCode, new LinearLayout.LayoutParams(-1, dp(46)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("加入首发抢购").setView(form).setNegativeButton("取消", null).setPositiveButton("保存任务", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            JSONObject body = new JSONObject();
            try {
                body.put("groupId", first(item, "groupId", "id"));
                body.put("saleId", first(item, "saleId", "id"));
                body.put("title", first(item, "title", "name", "saleId", "id"));
                body.put("num", intValue(count, 1));
                body.put("paymentPlatformCode", intValue(paymentCode, 0));
                body.put("sourcePhone", selectedPhone);
                boolean immediate = "immediate".equals(mode.getSelectedItem().toString());
                body.put("startAt", immediate ? java.time.Instant.now().toString() : first(item, "startAt", "startTime", "onSaleTime", "saleTime"));
            } catch (Exception ignored) {
            }
            boolean immediate = "immediate".equals(mode.getSelectedItem().toString());
            request(immediate ? "立即提交首发" : "保存首发任务", "POST", "/native/first-sales/tasks", body, result -> { dialog.dismiss(); toast(immediate ? "首发任务已提交" : "首发任务已保存"); showFirstSale(); });
        }));
        dialog.show();
    }

    private void showSettings() {
        content.removeAllViews(); addPageHeading("设置", "通知与本地执行");
        LinearLayout runtime = card();
        runtime.addView(text("独立运行", 16, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(8)));
        runtime.addView(text("账号、策略、任务和通知配置保存在本机；无需填写面板服务地址。", 12, muted, Typeface.NORMAL));
        content.addView(runtime, marginParams(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout bark = card(); bark.addView(text("Bark 通知", 16, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(10)));
        CheckBox enabled = check("启用推送", false); bark.addView(enabled);
        EditText server = input("服务地址"); EditText key = passwordInput("设备密钥"); EditText hour = numberInput("每日汇总小时（0-23）", "9");
        bark.addView(server, marginParams(-1, dp(46), 0, 0, 0, dp(8))); bark.addView(key, marginParams(-1, dp(46), 0, 0, 0, dp(8))); bark.addView(hour, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        LinearLayout barkActions = horizontal(Color.TRANSPARENT); Button load = button("读取", false); Button save = button("保存", true); Button test = button("测试", false);
        barkActions.addView(load, new LinearLayout.LayoutParams(0, dp(42), 1)); barkActions.addView(save, marginParams(dp(86), dp(42), dp(8), 0, 0, 0)); barkActions.addView(test, marginParams(dp(86), dp(42), dp(8), 0, 0, 0)); bark.addView(barkActions, new LinearLayout.LayoutParams(-1, -2));
        load.setOnClickListener(v -> request("读取 Bark", "GET", "/native/notifications/bark", null, result -> fillBark(result, enabled, server, key, hour)));
        save.setOnClickListener(v -> { JSONObject body = new JSONObject(); try { body.put("enabled", enabled.isChecked()); body.put("server", server.getText().toString().trim()); body.put("deviceKey", key.getText().toString().trim()); body.put("dailySummaryHour", intValue(hour, 9)); } catch (Exception ignored) { } request("保存 Bark", "PUT", "/native/notifications/bark", body, result -> toast("Bark 配置已保存")); });
        test.setOnClickListener(v -> request("发送 Bark 测试", "POST", "/native/notifications/bark/test", null, result -> toast("测试请求已提交")));
        content.addView(bark, marginParams(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout backgroundCard = card();
        backgroundCard.addView(text("后台同步", 16, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(8)));
        CheckBox backgroundSync = check("保持状态通知", backgroundSyncEnabled());
        backgroundCard.addView(backgroundSync);
        String lastSummary = preferences.getString("last_sync_summary", "尚未同步");
        backgroundCard.addView(text("前台服务执行行情监控、任务调度和状态通知\n最近：" + lastSummary, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(2), 0, dp(8)));
        backgroundSync.setOnCheckedChangeListener((view, checked) -> {
            preferences.edit().putBoolean("backgroundSync", checked).apply();
            if (checked) {
                requestNotificationPermission();
                startPanelSyncService();
                toast("后台同步已开启");
            } else {
                stopPanelSyncService();
                toast("后台同步已关闭");
            }
        });
        content.addView(backgroundCard, marginParams(-1, -2, 0, 0, 0, dp(12)));

        Button addAccount = button("短信登录 / 添加账号", true);
        addAccount.setOnClickListener(v -> showLoginDialog());
        content.addView(addAccount, marginParams(-1, dp(44), 0, 0, 0, dp(10)));
        load.performClick();
    }

    private void fillBark(JSONObject result, CheckBox enabled, EditText server, EditText key, EditText hour) {
        JSONObject data = result.optJSONObject("data"); if (data == null) data = result;
        enabled.setChecked(data.optBoolean("enabled", false)); server.setText(data.optString("server", "")); key.setText(data.optString("deviceKey", "")); hour.setText(String.valueOf(data.optInt("dailySummaryHour", 9)));
    }

    private void showJsonDialog(String title, JSONObject data) {
        ScrollView scroll = new ScrollView(this); TextView body = text(pretty(data), 12, ink, Typeface.NORMAL); body.setTextIsSelectable(true); body.setPadding(dp(4), dp(4), dp(4), dp(4)); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void request(String label, String method, String path, JSONObject body, Consumer<JSONObject> successCallback) {
        if (engine == null) engine = new NativeEngine(this);
        setStatus(label + "…", muted);
        io.execute(() -> {
            try {
                JSONObject result = engine.request(method, path, body);
                runOnUiThread(() -> { setStatus("已同步", success); successCallback.accept(result); });
            } catch (Exception error) {
                runOnUiThread(() -> { setStatus(error.getMessage() == null ? "请求失败" : error.getMessage(), danger); toast(error.getMessage() == null ? "请求失败" : error.getMessage()); });
            }
        });
    }

    private void setStatus(String value, int color) {
        if (statusView != null) { statusView.setText(value); statusView.setTextColor(color); }
    }

    private void addPageHeading(String kicker, String title) {
        content.addView(text(kicker, 11, primary, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(3)));
        content.addView(text(title, 20, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(14)));
    }

    private void addSectionTitle(String title) { content.addView(text(title, 17, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(8))); }

    private LinearLayout metricGrid(JSONObject data) {
        LinearLayout grid = horizontal(Color.TRANSPARENT); grid.setWeightSum(2f);
        boolean marketAvailable = data != null && data.optBoolean("marketAvailable", false);
        String estimatedValue = first(data, "estimatedValue");
        String estimate = estimatedValue.isEmpty() ? (marketAvailable ? "暂无报价" : "--") : money(estimatedValue);
        String[][] values = {{"藏品总数", first(data, "total", "totalCount", "count")}, {"市值估算", estimate}, {"已估值数量", first(data, "pricedQuantity")}, {"行情状态", marketAvailable ? "已连接" : "暂不可用"}};
        for (String[] value : values) { LinearLayout box = vertical(0xfff0f8f4); box.setPadding(dp(10), dp(10), dp(10), dp(10)); box.addView(text(value[0], 11, muted, Typeface.NORMAL)); box.addView(text(value[1].isEmpty() ? "--" : value[1], 16, ink, Typeface.BOLD), marginParams(-1, -2, 0, dp(4), 0, 0)); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(68), 1f); params.setMargins(0, 0, dp(6), 0); grid.addView(box, params); }
        return grid;
    }

    private LinearLayout labelled(String title, View view) { LinearLayout box = vertical(Color.TRANSPARENT); box.addView(text(title, 12, muted, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(4))); box.addView(view, marginParams(-1, dp(46), 0, 0, 0, dp(8))); return box; }

    private CheckBox check(String label, boolean checked) { CheckBox box = new CheckBox(this); box.setText(label); box.setTextColor(ink); box.setTextSize(13); box.setChecked(checked); box.setMinHeight(dp(44)); return box; }
    private Spinner spinner(String[] values, String selected) { Spinner spinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values); spinner.setAdapter(adapter); for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) spinner.setSelection(i); return spinner; }
    private EditText numberInput(String hint, String value) { EditText input = input(hint); input.setText(value == null ? "" : value); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); return input; }
    private EditText passwordInput(String hint) { EditText input = input(hint); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return input; }
    private EditText input(String hint) { EditText input = new EditText(this); input.setHint(hint); input.setHintTextColor(0xff8795a0); input.setTextColor(ink); input.setTextSize(14); input.setSingleLine(true); input.setPadding(dp(13), 0, dp(13), 0); input.setBackground(shape(surface, 0xffdbe7e4, 12)); return input; }

    private LinearLayout card() { LinearLayout card = vertical(surface); card.setPadding(dp(17), dp(16), dp(17), dp(16)); card.setBackground(shape(surface, 0xffe5eeeb, 12)); card.setElevation(dp(2)); return card; }
    private LinearLayout vertical(int color) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setBackgroundColor(color); return view; }
    private LinearLayout horizontal(int color) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setBackgroundColor(color); return view; }
    private TextView empty(String value) { TextView view = text(value, 13, muted, Typeface.NORMAL); view.setGravity(Gravity.CENTER); view.setPadding(dp(12), dp(24), dp(12), dp(24)); view.setBackground(shape(surface, 0xffe5eeeb, 16)); return view; }
    private TextView text(String value, float size, int color, int style) { TextView view = new TextView(this); view.setText(value == null ? "" : value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(Typeface.DEFAULT, style); view.setLineSpacing(dp(2), 1f); return view; }
    private Button button(String value, boolean active) { Button button = new Button(this); button.setAllCaps(false); button.setText(value); button.setTextSize(13); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setMinHeight(dp(42)); button.setPadding(dp(10), 0, dp(10), 0); styleButton(button, active); return button; }
    private void styleButton(Button button, boolean active) { button.setTextColor(active ? Color.WHITE : ink); button.setBackground(shape(active ? primary : surface, active ? primary : 0xffdce8e4, 12)); button.setElevation(active ? dp(1) : 0); }
    private GradientDrawable shape(int fill, int stroke, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); if (stroke != 0) drawable.setStroke(dp(1), stroke); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height); params.setMargins(left, top, right, bottom); return params; }
    private void toast(String message) { if (!isFinishing()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String value(JSONObject object, String key, String fallback) { return object == null ? fallback : object.optString(key, fallback); }
    private String first(JSONObject object, String... keys) { if (object == null) return ""; for (String key : keys) { Object value = object.opt(key); if (value != null && value != JSONObject.NULL && !String.valueOf(value).isEmpty()) return String.valueOf(value); } return ""; }
    private String number(JSONObject object, String key, String fallback) { return object == null ? fallback : String.valueOf(object.opt(key) == null || object.opt(key) == JSONObject.NULL ? fallback : object.opt(key)); }
    private String money(String value) { if (value == null || value.isEmpty()) return "价格不可用"; try { return "¥" + String.format(Locale.US, "%.2f", Double.parseDouble(value)); } catch (Exception error) { return value; } }
    private String moneyValue(String value) { if (value == null || value.isEmpty()) return ""; try { return String.format(Locale.US, "%.2f", Double.parseDouble(value)); } catch (Exception error) { return value; } }
    private String time(String value) { if (value == null || value.isEmpty()) return "-"; try { return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date.from(java.time.Instant.parse(value))); } catch (Exception error) { return value.length() > 16 ? value.substring(0, 16).replace('T', ' ') : value; } }
    private double parseDouble(String value, double fallback) { try { return Double.parseDouble(value.trim()); } catch (Exception error) { return fallback; } }
    private double doubleValue(EditText input, double fallback) { return parseDouble(input.getText().toString(), fallback); }
    private int intValue(EditText input, int fallback) { try { return Integer.parseInt(input.getText().toString().trim()); } catch (Exception error) { return fallback; } }
    private boolean isTerminalTaskStatus(String status) { return "payment_pending".equals(status) || "submitted".equals(status) || "cancelled".equals(status); }
    private String strategyStatus(String status) { if (status == null) return "待处理"; switch (status) { case "monitoring": return "监控中"; case "paused": return "已暂停"; case "verification_required": return "需验证"; case "payment_pending": return "待支付"; case "submitted": return "已提交"; case "scheduled": return "已排程"; case "waiting_price": return "等待行情"; default: return status.isEmpty() ? "待处理" : status; } }
    private String strategyMeta(JSONObject strategy) { return (strategy.optString("executionMode", "monitor").equals("live") ? "真实执行" : "仅监控") + " · 账号 " + first(strategy, "phone", "sourcePhone") + " · 行情 " + money(first(strategy, "latestFloorPrice", "floorPrice")) + "\n买入 " + money(first(strategy.optJSONObject("buy"), "maxPrice")) + " · 卖出 " + money(first(strategy.optJSONObject("sell"), "sellPrice")) + " · 更新 " + time(first(strategy, "updatedAt", "lastCheckAt")); }
    private JSONArray findArray(JSONObject object, String... keys) { if (object == null) return null; for (String key : keys) { JSONArray array = object.optJSONArray(key); if (array != null) return array; } return null; }
    private String pretty(JSONObject object) { if (object == null) return "暂无数据"; StringBuilder output = new StringBuilder(); Iterator<String> keys = object.keys(); while (keys.hasNext()) { String key = keys.next(); Object value = object.opt(key); output.append(key).append("：").append(value).append('\n'); } return output.toString(); }
}
