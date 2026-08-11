package com.ibox.nativepanel;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import com.geetest.captcha.GTCaptcha4Client;
import com.geetest.captcha.GTCaptcha4Config;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Native iBox panel client. The page never embeds a panel web document. */
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
    private final int success = Color.rgb(67, 170, 110);
    private final int danger = Color.rgb(205, 88, 103);
    private final int amber = Color.rgb(196, 145, 50);
    private final int infoBlue = Color.rgb(75, 132, 202);
    private final int background = Color.rgb(248, 251, 252);
    private final int surface = Color.WHITE;

    private SharedPreferences preferences;
    private ExecutorService io;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private NativeEngine engine;
    private LinearLayout content;
    private ScrollView pageScroll;
    private TextView pageTitle;
    private TextView statusView;
    private String selectedPhone;
    private JSONArray accounts = new JSONArray();
    private String currentPage = "accounts";
    private final List<Button> navButtons = new ArrayList<>();
    private String smsSessionId = "";
    private String smsPhone = "";
    private int bottomSystemInset;
    private TextView transientMessage;
    private final Set<String> assetSyncInFlight = new HashSet<>();
    private final LruCache<String, Bitmap> coverCache = new LruCache<>(48);
    private LinearLayout marketWatchBox;
    private LinearLayout tradeTaskBox;
    private TextView tradePendingSourceTitle;
    private LinearLayout tradePendingSourceBox;
    private LinearLayout tradeOrderBox;
    private final Runnable dismissTransientMessage = () -> {
        if (transientMessage != null) transientMessage.setVisibility(View.GONE);
    };
    private final Runnable marketWatchRefresh = new Runnable() {
        @Override
        public void run() {
            if (!"market".equals(currentPage) || marketWatchBox == null || isFinishing() || isDestroyed()) return;
            loadWatches(marketWatchBox, true, false);
            refreshHandler.postDelayed(this, 15_000L);
        }
    };
    private final Runnable tradeRefresh = new Runnable() {
        @Override
        public void run() {
            if (!"trade".equals(currentPage) || tradeTaskBox == null || tradePendingSourceBox == null || tradeOrderBox == null || isFinishing() || isDestroyed()) return;
            loadTradeDashboard(false);
            refreshHandler.postDelayed(this, 15_000L);
        }
    };

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
        scrollContentToTop();
        loadAccounts();
        if (engine.store().getAccounts().length() > 0 && backgroundSyncEnabled()) startPanelSyncService();
    }

    @Override
    protected void onDestroy() {
        stopMarketWatchRefresh();
        stopTradeRefresh();
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
        Dialog dialog = new Dialog(this, R.style.ProjectDialogTheme);
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
            bottomSystemInset = insets.getSystemWindowInsetBottom();
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            updateTransientMessageMargin();
            return insets;
        });
        LinearLayout toolbar = horizontal(surface);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(10), dp(14), dp(10));
        pageTitle = text("资产总览", 22, ink, Typeface.BOLD);
        toolbar.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));

        LinearLayout navContainer = vertical(surface);
        navContainer.setPadding(dp(12), dp(8), dp(12), dp(8));
        int navColumns = getResources().getConfiguration().screenWidthDp >= 600 ? PAGE_KEYS.length : 4;
        int navRows = (PAGE_KEYS.length + navColumns - 1) / navColumns;
        LinearLayout navRow = null;
        navButtons.clear();
        for (int i = 0; i < PAGE_KEYS.length; i++) {
            if (i % navColumns == 0) {
                navRow = horizontal(Color.TRANSPARENT);
                navRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(42));
                if (i > 0) rowParams.setMargins(0, dp(6), 0, 0);
                navContainer.addView(navRow, rowParams);
            }
            final String key = PAGE_KEYS[i];
            Button tab = button(PAGE_LABELS[i], i == 0);
            tab.setTextSize(12);
            tab.setPadding(dp(6), 0, dp(6), 0);
            tab.setOnClickListener(v -> selectPage(key));
            navButtons.add(tab);
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i % navColumns != 0) tabParams.setMargins(dp(6), 0, 0, 0);
            navRow.addView(tab, tabParams);
        }
        root.addView(navContainer, new LinearLayout.LayoutParams(-1, dp(navRows * 42 + (navRows - 1) * 6 + 16)));

        ScrollView scroll = new ScrollView(this);
        styleScroll(scroll);
        scroll.setFillViewport(true);
        scroll.setSaveEnabled(false);
        pageScroll = scroll;
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
        if (!"market".equals(key)) stopMarketWatchRefresh();
        if (!"trade".equals(key)) stopTradeRefresh();
        scrollContentToTop();
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

    private void scrollContentToTop() {
        if (pageScroll != null) pageScroll.post(() -> pageScroll.scrollTo(0, 0));
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
        renderAccountCards();
        syncAccountAssets();
    }

    private void renderFirstRunWorkspace() {
        pageTitle.setText("资产总览");
        LinearLayout account = card();
        account.addView(text("登录 iBox 账号", 20, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(14)));
        Button addAccount = button("短信登录", true);
        addAccount.setContentDescription("短信登录并添加账号");
        addAccount.setOnClickListener(v -> showLoginDialog());
        account.addView(addAccount, new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(account, marginParams(-1, -2, 0, 0, 0, dp(12)));
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
            JSONArray items = findArray(assetData, "items", "collections", "records", "list");
            card.addView(portfolioHeadline(assetData, cache != null && cache.optBoolean("stale", false)), marginParams(-1, -2, 0, 0, 0, dp(12)));
            card.addView(metricGrid(assetData, items, phone), marginParams(-1, -2, 0, 0, 0, dp(14)));
            if (items != null && items.length() > 0) renderAssetRows(card, items, phone);
            else card.addView(text(cache == null ? "资产同步中" : "暂无资产明细", 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, 0, 0, dp(4)));
            content.addView(card, marginParams(-1, -2, 0, 0, 0, dp(14)));
        }
    }

    private void renderAssetRows(LinearLayout card, JSONArray items, String phone) {
        card.addView(text("持仓明细 · " + items.length(), 14, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(4)));
        int limit = Math.min(8, items.length());
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = first(item, "name", "title", "collectionName", "digitalCollectionName", "id");
            String id = first(item, "id", "groupId", "collectionId", "digitalCollectionId");
            double quantity = Math.max(0d, parseDouble(first(item, "quantity", "num", "count", "holdNum"), 1d));
            double floorPrice = parseDouble(first(item, "floorPrice", "price", "valuation", "marketPrice"), Double.NaN);
            double unitCost = engine.store().getAssetCost(phone, id);
            boolean hasCost = Double.isFinite(unitCost);
            boolean hasPrice = Double.isFinite(floorPrice);
            boolean delisted = "delisted".equals(item.optString("marketState"));
            boolean consigning = item.optBoolean("consigning", false);
            boolean consignmentStateVerified = item.optBoolean("consignmentStateVerified", false);
            JSONArray activeListings = item.optJSONArray("activeListings");
            boolean canCancelConsignment = consigning && consignmentStateVerified
                    && activeListings != null && activeListings.length() > 0;
            double rate = hasCost && hasPrice && unitCost > 0d ? (floorPrice - unitCost) / unitCost * 100d : Double.NaN;
            LinearLayout row = vertical(Color.TRANSPARENT);
            row.setPadding(0, dp(12), 0, dp(12));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(ripple(0x1965c68b, shape(i > 0 ? 0xfff5f7f6 : Color.TRANSPARENT, 0, 6)));
            row.setOnLongClickListener(v -> {
                showAssetCostDialog(phone, id, name, unitCost);
                return true;
            });
            LinearLayout header = horizontal(Color.TRANSPARENT);
            header.setGravity(Gravity.CENTER_VERTICAL);
            ImageView cover = coverImage(first(item, "cover", "image", "imageUrl", "coverUrl"), name);
            header.addView(cover, marginParams(dp(52), dp(52), 0, 0, dp(12), 0));
            LinearLayout info = vertical(Color.TRANSPARENT);
            info.addView(text(name, 14, ink, Typeface.BOLD));
            String position = "持仓 " + compactNumber(quantity) + " 件 · 成本 " + (hasCost ? money(String.valueOf(unitCost)) : "--");
            info.addView(text(position, 11, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(3), 0, 0));
            if (consigning) {
                info.addView(text(consignmentStateVerified ? "寄售中" : "寄售状态待同步", 11, amber, Typeface.BOLD), marginParams(-1, -2, 0, dp(3), 0, 0));
            }
            header.addView(info, new LinearLayout.LayoutParams(0, -2, 0.76f));
            LinearLayout valuation = vertical(Color.TRANSPARENT);
            valuation.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            TextView floor = text(hasPrice ? money(String.valueOf(floorPrice)) : (delisted ? "已退市" : "--"), 14, hasPrice ? success : muted, Typeface.BOLD);
            floor.setGravity(Gravity.RIGHT);
            floor.setMaxLines(1);
            floor.setEllipsize(TextUtils.TruncateAt.END);
            floor.setIncludeFontPadding(false);
            valuation.addView(floor, new LinearLayout.LayoutParams(-1, -2));
            TextView floorLabel = text(hasPrice ? "地板价" : "", 10, muted, Typeface.NORMAL);
            floorLabel.setGravity(Gravity.RIGHT);
            floorLabel.setMaxLines(1);
            floorLabel.setEllipsize(TextUtils.TruncateAt.END);
            floorLabel.setIncludeFontPadding(false);
            valuation.addView(floorLabel, marginParams(-1, -2, 0, dp(3), 0, 0));
            header.addView(valuation, new LinearLayout.LayoutParams(0, dp(52), 0.24f));
            row.addView(header, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout footer = horizontal(Color.TRANSPARENT);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            double totalValue = hasPrice ? quantity * floorPrice : 0d;
            String performance = "总值 " + money(String.valueOf(totalValue)) + " · 收益率 " + (Double.isFinite(rate) ? signedPercent(rate) : "0%");
            footer.addView(text(performance, 12, hasPrice ? success : muted, Typeface.BOLD), new LinearLayout.LayoutParams(0, dp(32), 1));
            if (canCancelConsignment) {
                Button cancel = button("取消寄售", false);
                cancel.setTextColor(danger);
                cancel.setOnClickListener(v -> showConsignmentCancelPicker(phone, name, activeListings));
                footer.addView(cancel, new LinearLayout.LayoutParams(dp(96), dp(32)));
            } else if (!consigning && consignmentStateVerified) {
                Button consignment = button("寄售", false);
                consignment.setOnClickListener(v -> showAssetConsignmentDialog(phone, item));
                footer.addView(consignment, new LinearLayout.LayoutParams(dp(76), dp(32)));
            }
            row.addView(footer, marginParams(-1, dp(32), 0, dp(6), 0, 0));
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void showConsignmentCancelPicker(String phone, String name, JSONArray listings) {
        if (listings == null || listings.length() == 0) {
            toast("寄售挂单尚未同步");
            return;
        }
        if (listings.length() == 1) {
            showConsignmentCancelConfirm(phone, name, listings.optJSONObject(0));
            return;
        }
        LinearLayout choices = vertical(Color.TRANSPARENT);
        final Dialog[] picker = new Dialog[1];
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            if (listing == null) continue;
            String assetId = first(listing, "assetId");
            String tokenId = first(listing, "tokenId");
            Button choice = button("实例 " + assetId + (tokenId.isEmpty() ? "" : " · Token " + tokenId), false);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setOnClickListener(v -> {
                if (picker[0] != null) picker[0].dismiss();
                showConsignmentCancelConfirm(phone, name, listing);
            });
            choices.addView(choice, marginParams(-1, dp(48), 0, 0, 0, dp(8)));
        }
        picker[0] = showProjectDialog("选择要取消的寄售", choices, null, null);
    }

    private void showConsignmentCancelConfirm(String phone, String name, JSONObject listing) {
        String orderId = first(listing, "orderId");
        if (!orderId.matches("\\d+")) {
            toast("寄售挂单尚未同步");
            return;
        }
        String assetId = first(listing, "assetId");
        String detail = assetId.isEmpty() ? name : name + " · 实例 " + assetId;
        showProjectDialog("取消寄售", text("确认取消 " + detail + " 的寄售？", 14, muted, Typeface.NORMAL), "确认取消", dialog -> {
            dialog.dismiss();
            JSONObject body = new JSONObject();
            try {
                body.put("phone", phone);
            } catch (Exception ignored) {
            }
            request("取消寄售", "POST", "/native/consignment-orders/" + Uri.encode(orderId) + "/cancel", body,
                    result -> request("同步资产", "GET", "/native/accounts/" + Uri.encode(phone) + "/assets?refresh=1", null,
                            refreshed -> {
                                content.removeAllViews();
                                renderAccountCards();
                            }));
        });
    }

    private void showAssetCostDialog(String phone, String assetId, String name, double existingCost) {
        if (assetId.isEmpty()) {
            toast("该藏品缺少可保存的编号");
            return;
        }
        LinearLayout form = vertical(Color.TRANSPARENT);
        form.addView(text("录入单件实际买入成本，用于计算未实现收益和收益率。数据仅保存在本机。", 13, muted, Typeface.NORMAL), marginParams(-1, -2, 0, 0, 0, dp(10)));
        EditText cost = numberInput("单件持仓成本", Double.isFinite(existingCost) ? moneyValue(String.valueOf(existingCost)) : "");
        form.addView(cost, new LinearLayout.LayoutParams(-1, dp(46)));
        showProjectDialog("持仓成本 · " + name, form, "保存成本", dialog -> {
            double value = doubleValue(cost, Double.NaN);
            if (!Double.isFinite(value) || value <= 0d) {
                toast("请输入大于 0 的单件成本");
                return;
            }
            if (!engine.store().saveAssetCost(phone, assetId, value)) {
                toast("持仓成本保存失败");
                return;
            }
            dialog.dismiss();
            content.removeAllViews();
            renderAccountCards();
        });
    }

    private void syncAccountAssets() {
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            String phone = account == null ? "" : account.optString("phone");
            if (phone.isEmpty() || !assetSyncInFlight.add(phone)) continue;
            request("同步资产", "GET", "/native/accounts/" + Uri.encode(phone) + "/assets?refresh=1", null, result -> {
                assetSyncInFlight.remove(phone);
                accounts = engine.store().getAccounts();
                if ("accounts".equals(currentPage)) {
                    content.removeAllViews();
                    renderAccountCards();
                }
            }, () -> assetSyncInFlight.remove(phone));
        }
    }

    private void confirmRemoveAccount(String phone) {
        showProjectDialog("移除账号", text("移除后会暂停该账号未完成任务。", 14, muted, Typeface.NORMAL), "确认移除", dialog -> {
            dialog.dismiss();
            request("移除账号", "POST", "/native/accounts/" + Uri.encode(phone) + "/remove", null, result -> {
                if (phone.equals(selectedPhone)) selectedPhone = engine.store().getSelectedPhone();
                loadAccounts();
            });
        });
    }

    private void showSynthesis() {
        content.removeAllViews();
        addSectionTitle("定时任务");
        LinearLayout taskBox = vertical(Color.TRANSPARENT);
        content.addView(taskBox, marginParams(-1, -2, 0, 0, 0, dp(14)));
        addSectionTitle("合成活动");
        LinearLayout activityBox = vertical(Color.TRANSPARENT);
        content.addView(activityBox, new LinearLayout.LayoutParams(-1, -2));
        renderLoading(taskBox, "任务同步中");
        request("同步合成任务", "GET", "/native/synthesis/tasks", null, result -> {
            JSONArray tasks = findArray(result.optJSONObject("data"), "tasks", "items", "list");
            taskBox.removeAllViews();
            renderTaskList(tasks, "合成任务", taskBox, true);
        }, () -> renderRetry(taskBox, "合成任务同步失败", () -> showSynthesis()));
        if (selectedPhone.isEmpty()) activityBox.addView(empty("请先登录账号"));
        else loadSynthesisActivities(activityBox);
    }

    private void loadSynthesisActivities(LinearLayout target) {
        renderLoading(target, "活动同步中");
        request("同步合成活动", "GET", "/native/accounts/" + Uri.encode(selectedPhone) + "/synthesis/activities", null, result -> {
            target.removeAllViews();
            JSONObject data = result.optJSONObject("data");
            JSONArray activities = findArray(data, "activities", "items", "list");
            if (activities == null || activities.length() == 0) {
                target.addView(empty("暂无可用合成活动"));
                return;
            }
            for (int i = 0; i < activities.length(); i++) {
                JSONObject activity = activities.optJSONObject(i);
                if (activity == null) continue;
                String title = first(activity, "title", "name", "activityName", "id");
                String rawPhase = first(activity, "phase", "status");
                boolean recentExpired = "recent_expired".equals(data == null ? "" : data.optString("mode")) || "recent_expired".equals(rawPhase);
                boolean expired = recentExpired || "expired".equals(rawPhase) || "ended".equals(rawPhase);
                String phase = recentExpired ? "最近结束" : synthesisPhaseLabel(rawPhase);
                String period = expired ? first(activity, "endTime", "endAt", "startTime", "startAt") : first(activity, "startTime", "startAt");
                LinearLayout row = card();
                row.addView(text(title, 15, ink, Typeface.BOLD));
                row.addView(text(phase + (period.isEmpty() ? "" : " · " + time(period)), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
                LinearLayout detail = vertical(Color.TRANSPARENT);
                detail.addView(empty("材料同步中"));
                row.addView(detail, marginParams(-1, -2, 0, dp(4), 0, 0));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
                String syntheticId = first(activity, "syntheticId", "id");
                if (!syntheticId.isEmpty()) loadSynthesisCenter(syntheticId, activity, detail);
            }
        }, () -> renderRetry(target, "合成活动同步失败", () -> loadSynthesisActivities(target)));
    }

    private void loadSynthesisCenter(String syntheticId, JSONObject activity, LinearLayout target) {
        if (syntheticId.isEmpty()) return;
        request("读取材料", "GET", "/native/accounts/" + Uri.encode(selectedPhone) + "/synthesis/" + Uri.encode(syntheticId), null, result -> {
            JSONObject data = result.optJSONObject("data");
            renderSynthesisDetail(activity, data, target);
        }, () -> {
            target.removeAllViews();
            target.addView(text("合成材料读取失败", 12, danger, Typeface.NORMAL));
        });
    }

    private void renderSynthesisDetail(JSONObject activity, JSONObject data, LinearLayout target) {
        target.removeAllViews();
        if (data == null) {
            target.addView(text("未读取到合成材料", 12, muted, Typeface.NORMAL));
            return;
        }
        renderSynthesisGroups(findArray(data, "groups", "materials"), target);
        String rawPhase = first(activity, "phase", "status");
        boolean preparing = "preparing".equals(rawPhase);
        boolean available = "available".equals(rawPhase);
        boolean needsSlider = data.optBoolean("needSlider", false);
        boolean canSubmit = data.optBoolean("canSubmit", false) && !needsSlider;
        boolean canSchedule = preparing && !first(activity, "startAt", "startTime").isEmpty() && canSubmit;
        boolean canImmediate = available && canSubmit;
        String reason = first(data, "reason");
        if (preparing && canSchedule) {
            target.addView(text("开始时将按秒确认并提交", 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
        } else if (needsSlider) {
            target.addView(text("该合成需要人工完成滑块验证", 12, danger, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
        } else if (!canSubmit && !reason.isEmpty()) {
            target.addView(text(reason, 12, danger, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
        } else if ("expired".equals(rawPhase) || "ended".equals(rawPhase)) {
            target.addView(text("该项目已结束，仅作材料参考", 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
        }
        if (!canSchedule && !canImmediate) return;
        LinearLayout actions = horizontal(Color.TRANSPARENT);
        EditText count = numberInput("合成数量", "1");
        Button action = button(canSchedule ? "加入任务" : "立即提交", true);
        TextView state = text("", 12, muted, Typeface.NORMAL);
        actions.addView(count, new LinearLayout.LayoutParams(0, dp(46), 1));
        actions.addView(action, marginParams(dp(112), dp(46), dp(8), 0, 0, 0));
        target.addView(actions, marginParams(-1, -2, 0, dp(4), 0, 0));
        target.addView(state, marginParams(-1, -2, 0, dp(6), 0, 0));
        if (canSchedule) {
            action.setOnClickListener(v -> showSynthesisDialog(activity, true, count.getText().toString()));
        } else {
            action.setOnClickListener(v -> submitSynthesisNow(first(activity, "syntheticId", "id"), count, action, state));
        }
    }

    private void renderSynthesisGroups(JSONArray groups, LinearLayout target) {
        if (groups == null || groups.length() == 0) {
            target.addView(text("未读取到材料", 12, muted, Typeface.NORMAL));
            return;
        }
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) continue;
            JSONObject album = group.optJSONObject("album");
            if (album == null) {
                JSONArray options = group.optJSONArray("options");
                album = options == null ? null : options.optJSONObject(0);
            }
            String name = album == null ? "未选择材料" : first(album, "name", "title", "id");
            String usable = album == null ? "0" : first(album, "usableNum", "quantity", "num");
            String required = first(group, "required", "requiredTotal", "quantity");
            target.addView(text(name + " · 可用 " + usable + " · 每次消耗 " + (required.isEmpty() ? "1" : required), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, 0, 0, dp(4)));
        }
    }

    private void submitSynthesisNow(String syntheticId, EditText count, Button action, TextView state) {
        JSONObject body = new JSONObject();
        try {
            body.put("syntheticNum", intValue(count, 1));
        } catch (Exception ignored) {
        }
        action.setEnabled(false);
        action.setText("提交中");
        state.setText("正在确认并提交");
        request("提交合成", "POST", "/native/accounts/" + Uri.encode(selectedPhone) + "/synthesis/" + Uri.encode(syntheticId) + "/submit", body, result -> {
            state.setTextColor(success);
            state.setText("合成已提交");
            action.setEnabled(true);
            action.setText("立即提交");
        }, () -> {
            state.setTextColor(danger);
            state.setText("合成提交失败，请刷新后重试");
            action.setEnabled(true);
            action.setText("立即提交");
        });
    }

    private void showSynthesisDialog(JSONObject activity, boolean scheduled) {
        showSynthesisDialog(activity, scheduled, "1");
    }

    private void showSynthesisDialog(JSONObject activity, boolean scheduled, String initialCount) {
        if (selectedPhone.isEmpty()) {
            toast("请先选择账号");
            return;
        }
        String syntheticId = first(activity, "syntheticId", "id");
        if (syntheticId.isEmpty()) {
            toast("合成编号为空");
            return;
        }
        EditText count = numberInput("合成数量", initialCount);
        LinearLayout form = vertical(Color.TRANSPARENT);
        form.addView(text(first(activity, "title", "name", "activityName", syntheticId), 15, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(10)));
        form.addView(count, new LinearLayout.LayoutParams(-1, dp(46)));
        List<CheckBox> taskAccounts = accountSelection(form);
        String title = scheduled ? "加入合成定时任务" : "立即提交合成";
        showProjectDialog(title, form, "确认", dialog -> {
            JSONObject body = new JSONObject();
            try {
                body.put("syntheticId", syntheticId);
                body.put("syntheticNum", intValue(count, 1));
                body.put("phones", selectedPhones(taskAccounts));
                body.put("sourcePhone", selectedPhone);
                body.put("startAt", scheduled ? first(activity, "startAt", "startTime", "beginTime") : java.time.Instant.now().toString());
            } catch (Exception ignored) {
            }
            String path = "/native/synthesis/tasks";
            request(scheduled ? "保存合成任务" : "提交合成", "POST", path, body, result -> {
                dialog.dismiss();
                toast(scheduled ? "合成任务已保存" : "合成任务已提交");
                showSynthesis();
            });
        });
    }

    private void showMarket() {
        stopMarketWatchRefresh();
        content.removeAllViews();
        LinearLayout search = horizontal(Color.TRANSPARENT);
        EditText query = input("搜索藏品名称");
        Button searchButton = button("搜索", true);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        search.addView(searchButton, marginParams(dp(76), dp(48), dp(8), 0, 0, 0));
        content.addView(search, marginParams(-1, -2, 0, 0, 0, dp(12)));
        LinearLayout resultBox = vertical(Color.TRANSPARENT);
        content.addView(resultBox, marginParams(-1, -2, 0, 0, 0, dp(8)));
        addSectionTitle("监控项目");
        LinearLayout watchBox = vertical(Color.TRANSPARENT);
        marketWatchBox = watchBox;
        content.addView(watchBox, new LinearLayout.LayoutParams(-1, -2));
        searchButton.setOnClickListener(v -> searchMarket(query.getText().toString(), resultBox, query, watchBox));
        loadWatches(watchBox, true);
        refreshHandler.postDelayed(marketWatchRefresh, 15_000L);
    }

    private void searchMarket(String name, LinearLayout target, EditText query, LinearLayout watchBox) {
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
                LinearLayout header = horizontal(Color.TRANSPARENT);
                ImageView cover = coverImage(first(item, "cover", "image", "imageUrl", "coverUrl"), first(item, "name", "title", "groupId", "id"));
                header.addView(cover, marginParams(dp(50), dp(50), 0, 0, dp(12), 0));
                LinearLayout details = vertical(Color.TRANSPARENT);
                details.addView(text(first(item, "name", "title", "groupId", "id"), 15, ink, Typeface.BOLD));
                details.addView(text("编号 " + first(item, "groupId", "id") + " · 地板 " + money(first(item, "floorPrice", "price")), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(6), 0, 0));
                header.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(header, marginParams(-1, -2, 0, 0, 0, dp(10)));
                Button add = button("加入监控", false);
                add.setOnClickListener(v -> addWatch(item, target, query, watchBox));
                row.addView(add, new LinearLayout.LayoutParams(-1, dp(42)));
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        });
    }

    private void loadWatches(LinearLayout target) {
        loadWatches(target, false);
    }

    private void loadWatches(LinearLayout target, boolean refresh) {
        loadWatches(target, refresh, true);
    }

    private void loadWatches(LinearLayout target, boolean refresh, boolean showLoading) {
        if (showLoading) renderLoading(target, "行情同步中");
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
                LinearLayout header = horizontal(Color.TRANSPARENT);
                ImageView cover = coverImage(first(watch, "cover", "image", "imageUrl", "coverUrl"), first(watch, "name", "collectionId", "id"));
                header.addView(cover, marginParams(dp(50), dp(50), 0, 0, dp(12), 0));
                LinearLayout details = vertical(Color.TRANSPARENT);
                details.addView(text(first(watch, "name", "collectionId", "id"), 15, ink, Typeface.BOLD));
                String price = money(first(watch, "latestPrice", "currentPrice", "price"));
                String delta = watchDelta(first(watch, "change"), first(watch, "changePercent"));
                double lowest = parseDouble(first(watch, "lowestPrice"), Double.NaN);
                double highest = parseDouble(first(watch, "highestPrice"), Double.NaN);
                String range = Double.isFinite(lowest) && Double.isFinite(highest)
                        ? "区间 " + money(String.valueOf(lowest)) + " - " + money(String.valueOf(highest))
                        : "等待报价";
                String updatedAt = time(first(watch, "updatedAt", "lastObservedAt"));
                String lastError = first(watch, "lastError");
                String meta = price + " · " + delta + "\n" + range + " · 更新于 " + updatedAt
                        + "\n提醒 涨≥" + number(watch, "riseThresholdPercent", "3") + "% / 跌≥" + number(watch, "fallThresholdPercent", "3") + "%";
                if (!lastError.isEmpty()) meta += "\n报价失败：" + lastError;
                details.addView(text(meta, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(6), 0, 0));
                header.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(header, marginParams(-1, -2, 0, 0, 0, dp(10)));
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
        }, () -> renderRetry(target, "行情监控同步失败", () -> loadWatches(target, refresh)));
    }

    private void stopMarketWatchRefresh() {
        refreshHandler.removeCallbacks(marketWatchRefresh);
        marketWatchBox = null;
    }

    private void stopTradeRefresh() {
        refreshHandler.removeCallbacks(tradeRefresh);
        tradeTaskBox = null;
        tradePendingSourceTitle = null;
        tradePendingSourceBox = null;
        tradeOrderBox = null;
    }

    private void addWatch(JSONObject item, LinearLayout resultBox, EditText query, LinearLayout watchBox) {
        JSONObject body = new JSONObject();
        try {
                body.put("collectionId", first(item, "id", "collectionId", "groupId"));
                body.put("name", first(item, "name", "title", "groupId", "id"));
                body.put("searchTerm", first(item, "searchTerm", "name", "title"));
                body.put("floorPrice", first(item, "floorPrice", "price"));
                body.put("watchToken", first(item, "watchToken", "token"));
            body.put("cover", first(item, "cover", "image", "imageUrl", "coverUrl"));
            body.put("riseThresholdPercent", 3);
            body.put("fallThresholdPercent", 3);
        } catch (Exception ignored) { }
        request("加入行情监控", "POST", "/native/market/watches", body, result -> {
            resultBox.removeAllViews();
            query.setText("");
            loadWatches(watchBox, true);
            toast("已加入行情监控");
        });
    }

    private void showThresholdDialog(JSONObject watch, LinearLayout target) {
        LinearLayout form = vertical(Color.TRANSPARENT);
        EditText rise = numberInput("上涨阈值 %", number(watch, "riseThresholdPercent", "3"));
        EditText fall = numberInput("下跌阈值 %", number(watch, "fallThresholdPercent", "3"));
        form.addView(rise, marginParams(-1, dp(48), 0, 0, 0, dp(10)));
        form.addView(fall, new LinearLayout.LayoutParams(-1, dp(48)));
        showProjectDialog("行情提醒阈值", form, "保存", dialog -> {
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
        });
    }

    private void cancelWatch(String id, LinearLayout target) {
        if (id.isEmpty()) return;
        request("停止监控", "POST", "/native/market/watches/" + Uri.encode(id) + "/cancel", null, result -> loadWatches(target));
    }

    private void showQuant() {
        content.removeAllViews();
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
        OptionField mode = optionField("执行方式", new String[]{"monitor", "live"}, existing == null ? "monitor" : existing.optString("executionMode", "monitor"));
        form.addView(labelled("执行方式", mode));
        ProjectToggle buyEnabled = toggle("启用买入", existing == null || existing.optJSONObject("buy") == null || existing.optJSONObject("buy").optBoolean("enabled", true));
        EditText buyPrice = numberInput("最高买入价", existing == null ? moneyValue(first(target, "floorPrice", "price")) : number(existing.optJSONObject("buy"), "maxPrice", ""));
        EditText buyQuantity = numberInput("买入数量", existing == null ? "1" : number(existing.optJSONObject("buy"), "quantity", "1"));
        form.addView(buyEnabled); form.addView(buyPrice, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(buyQuantity, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        ProjectToggle sellEnabled = toggle("启用卖出", existing != null && existing.optJSONObject("sell") != null && existing.optJSONObject("sell").optBoolean("enabled", false));
        EditText sellTrigger = numberInput("卖出触发行情价", existing == null ? "" : number(existing.optJSONObject("sell"), "minPrice", ""));
        EditText sellPrice = numberInput("寄售价（整数）", existing == null ? "" : number(existing.optJSONObject("sell"), "sellPrice", ""));
        EditText sellQuantity = numberInput("卖出数量", existing == null ? "1" : number(existing.optJSONObject("sell"), "quantity", "1"));
        form.addView(sellEnabled); form.addView(sellTrigger, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(sellPrice, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(sellQuantity, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        JSONObject existingStopLoss = existing == null ? null : existing.optJSONObject("stopLoss");
        ProjectToggle stopLossEnabled = toggle("启用止损", existingStopLoss != null && existingStopLoss.optBoolean("enabled", false));
        EditText stopTrigger = numberInput("止损触发价", existingStopLoss == null ? "" : number(existingStopLoss, "triggerPrice", ""));
        EditText stopPrice = numberInput("止损寄售价（整数）", existingStopLoss == null ? "" : number(existingStopLoss, "sellPrice", ""));
        form.addView(stopLossEnabled); form.addView(stopTrigger, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(stopPrice, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText maxPosition = numberInput("最大持仓", existing == null ? "1" : number(existing, "maxPosition", "1"));
        EditText minProfit = numberInput("最低单件预期净利", existing == null ? "0" : number(existing, "minNetProfit", "0"));
        EditText volatility = numberInput("单周期最大波动 %", existing == null ? "0" : number(existing, "volatilityLimitPercent", "0"));
        EditText cooldown = numberInput("触发后冷却分钟", existing == null ? "10" : number(existing, "cooldownMinutes", "10"));
        EditText interval = numberInput("监控间隔", existing == null ? "15" : number(existing, "intervalValue", "15"));
        OptionField intervalUnit = optionField("间隔单位", new String[]{"seconds", "minutes", "hours"}, existing == null ? "seconds" : existing.optString("intervalUnit", "seconds"));
        EditText password = passwordInput("交易密码（真实执行时填写）");
        form.addView(maxPosition, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(minProfit, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(volatility, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(cooldown, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(interval, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(labelled("间隔单位", intervalUnit)); form.addView(password, marginParams(-1, dp(46), 0, 0, 0, 0));
        showProjectDialog(existing == null ? "配置量化策略" : "修改量化策略", scroll, "保存", dialog -> {
            JSONObject body = new JSONObject();
            try {
                body.put("phone", selectedPhone); body.put("groupId", first(target, "groupId", "id")); body.put("title", title); body.put("cover", first(target, "cover", "image"));
                body.put("executionMode", mode.value()); body.put("intervalValue", intValue(interval, 15)); body.put("intervalUnit", intervalUnit.value());
                body.put("maxPosition", intValue(maxPosition, 1)); body.put("minNetProfit", doubleValue(minProfit, 0)); body.put("volatilityLimitPercent", doubleValue(volatility, 0)); body.put("cooldownMinutes", intValue(cooldown, 10));
                JSONObject buy = new JSONObject(); buy.put("enabled", buyEnabled.isChecked()); buy.put("maxPrice", doubleValue(buyPrice, 0)); buy.put("quantity", intValue(buyQuantity, 1)); body.put("buy", buy);
                JSONObject sell = new JSONObject(); sell.put("enabled", sellEnabled.isChecked()); sell.put("minPrice", doubleValue(sellTrigger, 0)); sell.put("sellPrice", intValue(sellPrice, 0)); sell.put("quantity", intValue(sellQuantity, 1)); body.put("sell", sell);
                JSONObject stopLoss = new JSONObject(); stopLoss.put("enabled", stopLossEnabled.isChecked()); stopLoss.put("triggerPrice", doubleValue(stopTrigger, 0)); stopLoss.put("sellPrice", intValue(stopPrice, 0)); body.put("stopLoss", stopLoss);
                if (!password.getText().toString().trim().isEmpty()) body.put("consignPassword", password.getText().toString().trim());
            } catch (Exception ignored) { }
            String path = existing == null ? "/native/quant/strategies" : "/native/quant/strategies/" + Uri.encode(existing.optString("id"));
            request("保存策略", existing == null ? "POST" : "PUT", path, body, result -> { dialog.dismiss(); selectPage("quant"); });
        });
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
        stopTradeRefresh();
        content.removeAllViews();
        LinearLayout search = horizontal(Color.TRANSPARENT);
        EditText query = input("输入藏品名称");
        Button find = button("搜索", true);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1)); search.addView(find, marginParams(dp(76), dp(48), dp(8), 0, 0, 0));
        content.addView(search, marginParams(-1, -2, 0, 0, 0, dp(12)));
        LinearLayout results = vertical(Color.TRANSPARENT); content.addView(results, marginParams(-1, -2, 0, 0, 0, dp(10)));
        find.setOnClickListener(v -> searchTrade(query.getText().toString(), results));
        addSectionTitle("交易任务");
        tradeTaskBox = vertical(Color.TRANSPARENT); content.addView(tradeTaskBox, marginParams(-1, -2, 0, 0, 0, dp(12)));
        tradePendingSourceTitle = text("待支付来源", 17, ink, Typeface.BOLD);
        tradePendingSourceTitle.setVisibility(View.GONE);
        content.addView(tradePendingSourceTitle, marginParams(-1, -2, 0, 0, 0, dp(8)));
        tradePendingSourceBox = vertical(Color.TRANSPARENT);
        tradePendingSourceBox.setVisibility(View.GONE);
        content.addView(tradePendingSourceBox, marginParams(-1, -2, 0, 0, 0, dp(12)));
        addSectionTitle("订单记录");
        tradeOrderBox = vertical(Color.TRANSPARENT); content.addView(tradeOrderBox, new LinearLayout.LayoutParams(-1, -2));
        loadTradeDashboard(true);
        refreshHandler.postDelayed(tradeRefresh, 15_000L);
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
                Button wanted = button("求购", false); wanted.setOnClickListener(v -> showWantedTaskDialog(item));
                Button retired = button("捡漏", false); retired.setOnClickListener(v -> showRetiredDialog(item));
                Button purchase = button("立即买入", true); purchase.setOnClickListener(v -> showImmediatePurchaseDialog(item));
                actions.addView(wanted, new LinearLayout.LayoutParams(0, dp(42), 1));
                actions.addView(retired, marginParams(dp(76), dp(42), dp(8), 0, 0, 0));
                actions.addView(purchase, marginParams(dp(84), dp(42), dp(8), 0, 0, 0));
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
        showProjectDialog("配置捡漏任务", form, "保存并启动", dialog -> {
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
        });
    }

    private void showImmediatePurchaseDialog(JSONObject item) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        LinearLayout form = vertical(Color.TRANSPARENT);
        EditText maxPrice = numberInput("最高买入价", moneyValue(first(item, "floorPrice", "price")));
        form.addView(maxPrice, new LinearLayout.LayoutParams(-1, dp(46)));
        showProjectDialog("立即买入当前挂单", form, "创建待支付订单", dialog -> {
            JSONObject body = new JSONObject();
            try {
                body.put("phone", selectedPhone);
                body.put("groupId", first(item, "groupId", "id"));
                body.put("title", first(item, "name", "title"));
                body.put("cover", first(item, "cover", "image"));
                body.put("maxPrice", doubleValue(maxPrice, 0));
            } catch (Exception ignored) {
            }
            request("创建待支付订单", "POST", "/native/retired-market/purchases", body, result -> {
                dialog.dismiss();
                JSONObject task = result.optJSONObject("data");
                toast("payment_pending".equals(task == null ? "" : task.optString("status")) ? "订单已创建，等待钱包支付" : "订单状态已更新");
                showTrade();
            });
        });
    }

    private void showWantedTaskDialog(JSONObject item) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        String groupId = first(item, "groupId", "id");
        request("核实求购钱包", "GET", "/native/accounts/" + Uri.encode(selectedPhone) + "/market-trade/" + Uri.encode(groupId) + "/wanted-preflight", null, preflight -> {
            JSONObject data = preflight.optJSONObject("data");
            JSONObject payment = data == null ? null : data.optJSONObject("wantedPayment");
            int paymentCodeValue = payment == null ? 0 : payment.optInt("code", 0);
            if (payment == null || !payment.optBoolean("available", false) || paymentCodeValue <= 0) {
                toast("当前账号没有可用求购支付钱包");
                return;
            }
            LinearLayout form = vertical(Color.TRANSPARENT);
            EditText price = numberInput("求购价格", moneyValue(first(item, "floorPrice", "price"))); form.addView(price, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
            EditText quantity = numberInput("数量", "1"); form.addView(quantity, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
            JSONArray platforms = payment.optJSONArray("platforms");
            final int[] selectedPaymentCode = new int[]{paymentCodeValue};
            Button paymentPicker = button(paymentPlatformLabel(platforms, paymentCodeValue), false);
            paymentPicker.setOnClickListener(v -> showPaymentPlatformDialog(platforms, selectedPaymentCode, paymentPicker));
            form.addView(labelled("求购支付钱包", paymentPicker));
            ProjectToggle agreement = toggle("我已阅读并同意交易服务协议", false); form.addView(agreement, marginParams(-1, -2, 0, 0, 0, dp(4)));
            showProjectDialog("创建求购任务", form, "保存并启动", dialog -> {
                JSONObject body = new JSONObject();
                try {
                    body.put("type", "wanted"); body.put("phone", selectedPhone); body.put("groupId", groupId); body.put("title", first(item, "name", "title")); body.put("cover", first(item, "cover", "image"));
                    body.put("price", doubleValue(price, 0)); body.put("quantity", intValue(quantity, 1)); body.put("autoStart", true);
                    body.put("paymentPlatformCode", selectedPaymentCode[0]); body.put("agreementAccepted", agreement.isChecked());
                } catch (Exception ignored) { }
                request("创建交易任务", "POST", "/native/market/trade/tasks", body, result -> { dialog.dismiss(); selectPage("trade"); });
            });
        });
    }

    private String paymentPlatformLabel(JSONArray platforms, int code) {
        if (platforms != null) {
            for (int index = 0; index < platforms.length(); index++) {
                JSONObject platform = platforms.optJSONObject(index);
                if (platform != null && platform.optInt("code", 0) == code) {
                    String name = first(platform, "name");
                    return (name.isEmpty() ? "支付钱包 " + code : name) + " · " + code;
                }
            }
        }
        return "支付钱包 " + code;
    }

    private void showPaymentPlatformDialog(JSONArray platforms, int[] selectedCode, Button picker) {
        if (platforms == null || platforms.length() == 0) {
            toast("没有读取到可用求购支付钱包");
            return;
        }
        LinearLayout options = vertical(Color.TRANSPARENT);
        final Dialog[] dialog = new Dialog[1];
        for (int index = 0; index < platforms.length(); index++) {
            JSONObject platform = platforms.optJSONObject(index);
            if (platform == null) continue;
            int code = platform.optInt("code", 0);
            boolean selectable = platform.optBoolean("selectable", false);
            Button option = button(paymentPlatformLabel(platforms, code), selectable && code == selectedCode[0]);
            option.setEnabled(selectable);
            option.setAlpha(selectable ? 1f : 0.5f);
            option.setContentDescription(paymentPlatformLabel(platforms, code) + (selectable ? "，可用" : "，不可用"));
            option.setOnClickListener(v -> {
                selectedCode[0] = code;
                picker.setText(paymentPlatformLabel(platforms, code));
                if (dialog[0] != null) dialog[0].dismiss();
            });
            options.addView(option, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        }
        dialog[0] = showProjectDialog("选择求购支付钱包", options, null, null);
    }

    private void showAssetConsignmentDialog(String phone, JSONObject asset) {
        String groupId = first(asset, "groupId", "digitalCollectionGroupId", "collectionGroupId");
        if (!groupId.matches("\\d+")) { toast("该持仓缺少藏品组编号"); return; }
        request("读取可寄售资产", "GET", "/native/accounts/" + Uri.encode(phone) + "/market-trade/assets?groupId=" + Uri.encode(groupId), null, result -> {
            JSONObject data = result.optJSONObject("data");
            JSONArray assets = findArray(data, "items", "assets", "list");
            if (assets == null || assets.length() == 0) { toast(data != null && data.optBoolean("consigning", false) ? "当前藏品正在寄售中" : "当前没有可寄售资产"); return; }
            showAssetConsignmentDialog(phone, asset, assets);
        });
    }

    private void showAssetConsignmentDialog(String phone, JSONObject asset, JSONArray assets) {
        final JSONObject[] selectedAsset = new JSONObject[]{assets.optJSONObject(0)};
        LinearLayout form = vertical(Color.TRANSPARENT);
        Button assetPicker = button(assetLabel(selectedAsset[0]), false);
        assetPicker.setOnClickListener(v -> showOwnedAssetPicker(assets, selectedAsset, assetPicker));
        form.addView(labelled("寄售资产", assetPicker));
        double floorPrice = parseDouble(first(asset, "floorPrice", "price"), Double.NaN);
        EditText price = numberInput("寄售价格", Double.isFinite(floorPrice) && floorPrice == Math.rint(floorPrice) ? compactNumber(floorPrice) : "");
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(price, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        EditText password = passwordInput("寄售交易密码"); form.addView(password, new LinearLayout.LayoutParams(-1, dp(46)));
        showProjectDialog("确认寄售", form, "确认寄售", dialog -> {
            if (selectedAsset[0] == null) { toast("请选择可寄售资产"); return; }
            double salePrice = doubleValue(price, Double.NaN);
            if (!Double.isFinite(salePrice) || salePrice <= 0d || salePrice != Math.rint(salePrice)) {
                toast("寄售价格需为正整数");
                return;
            }
            if (!Double.isFinite(floorPrice) || floorPrice <= 0d) {
                toast("当前无可用地板价，暂不能立即寄售");
                return;
            }
            if (password.getText().toString().trim().isEmpty()) {
                toast("请输入寄售交易密码");
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("type", "consignment"); body.put("phone", phone); body.put("groupId", first(asset, "groupId", "digitalCollectionGroupId", "collectionGroupId")); body.put("title", first(asset, "name", "title")); body.put("cover", first(asset, "cover", "image"));
                body.put("price", salePrice); body.put("quantity", 1); body.put("autoStart", true); body.put("immediate", true);
                String instanceId = selectedAsset[0].optString("instanceId");
                if (!instanceId.matches("\\d+")) { toast("所选持仓缺少实例编号，请重新读取资产"); return; }
                body.put("digitalCollectionId", instanceId); body.put("triggerPrice", floorPrice); body.put("monitorIntervalValue", 5); body.put("monitorIntervalUnit", "seconds"); body.put("consignPassword", password.getText().toString().trim());
            } catch (Exception ignored) { }
            request("提交寄售", "POST", "/native/market/trade/tasks", body, result -> {
                dialog.dismiss();
                JSONObject task = result.optJSONObject("data");
                if ("submitted".equals(task == null ? "" : task.optString("status"))) {
                    toast("寄售已提交");
                    showAccounts();
                    return;
                }
                toast(task == null ? "寄售任务未创建" : task.optString("lastResult", "寄售任务已启动"));
                selectPage("trade");
            });
        });
    }

    private void showOwnedAssetPicker(JSONArray assets, JSONObject[] selectedAsset, Button target) {
        LinearLayout choices = vertical(Color.TRANSPARENT);
        final Dialog[] picker = new Dialog[1];
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null || first(asset, "instanceId").isEmpty()) continue;
            Button choice = button(assetLabel(asset), false);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setOnClickListener(v -> {
                selectedAsset[0] = asset;
                target.setText(assetLabel(asset));
                if (picker[0] != null) picker[0].dismiss();
            });
            choices.addView(choice, marginParams(-1, dp(48), 0, 0, 0, dp(8)));
        }
        picker[0] = showProjectDialog("选择寄售资产", choices, null, null);
    }

    private String assetLabel(JSONObject asset) {
        if (asset == null) return "当前账号没有可寄售资产";
        String name = first(asset, "name", "title", "instanceId");
        String id = first(asset, "instanceId");
        String quantity = first(asset, "quantity", "holdNum", "count");
        return name + " · 编号 " + id + (quantity.isEmpty() ? "" : " · 可用 " + quantity + " 件");
    }

    private void loadTradeDashboard(boolean showLoading) {
        final LinearLayout taskTarget = tradeTaskBox;
        final TextView sourceTitle = tradePendingSourceTitle;
        final LinearLayout sourceTarget = tradePendingSourceBox;
        final LinearLayout orderTarget = tradeOrderBox;
        if (taskTarget == null || sourceTitle == null || sourceTarget == null || orderTarget == null) return;
        if (showLoading) renderLoading(taskTarget, "交易任务同步中");
        if (showLoading) renderLoading(orderTarget, "订单同步中");
        if (showLoading) {
            sourceTitle.setVisibility(View.GONE);
            sourceTarget.setVisibility(View.GONE);
            sourceTarget.removeAllViews();
        }
        final JSONArray[] tradeTasks = new JSONArray[1];
        final JSONArray[] retiredTasks = new JSONArray[1];
        final JSONObject[] ordersData = new JSONObject[1];
        final boolean[] completed = new boolean[3];
        final boolean[] failed = new boolean[3];
        Runnable render = () -> {
            if (!completed[0] || !completed[1] || !completed[2]) return;
            taskTarget.removeAllViews();
            orderTarget.removeAllViews();
            sourceTitle.setVisibility(View.GONE);
            sourceTarget.setVisibility(View.GONE);
            sourceTarget.removeAllViews();
            if (failed[0] && failed[1]) {
                renderRetry(taskTarget, "交易任务同步失败", () -> loadTradeDashboard(true));
            } else {
                int visible = renderTradeTaskRows(tradeTasks[0], false, taskTarget);
                visible += renderTradeTaskRows(retiredTasks[0], true, taskTarget);
                if (visible == 0) taskTarget.addView(empty("暂无市场交易任务"));
            }
            if (failed[2]) {
                renderRetry(orderTarget, "订单同步失败", () -> loadTradeDashboard(true));
                return;
            }
            renderPlatformOrders(ordersData[0], orderTarget);
            if (failed[0] && failed[1]) return;
            Set<String> pendingOrderIds = pendingOrderIds(ordersData[0]);
            int sourceCount = renderPendingTaskSources(tradeTasks[0], false, pendingOrderIds, sourceTarget);
            sourceCount += renderPendingTaskSources(retiredTasks[0], true, pendingOrderIds, sourceTarget);
            if (sourceCount > 0) {
                sourceTitle.setVisibility(View.VISIBLE);
                sourceTarget.setVisibility(View.VISIBLE);
            }
        };
        request("同步交易任务", "GET", "/native/market/trade/tasks", null, result -> {
            tradeTasks[0] = findArray(result.optJSONObject("data"), "tasks", "items", "list");
            completed[0] = true;
            render.run();
        }, () -> {
            failed[0] = true;
            completed[0] = true;
            render.run();
        });
        request("同步捡漏任务", "GET", "/native/retired-market/tasks", null, result -> {
            retiredTasks[0] = findArray(result.optJSONObject("data"), "tasks", "items", "list");
            completed[1] = true;
            render.run();
        }, () -> {
            failed[1] = true;
            completed[1] = true;
            render.run();
        });
        request("同步订单", "GET", "/native/orders?refresh=1", null, result -> {
            ordersData[0] = result.optJSONObject("data");
            completed[2] = true;
            render.run();
        }, () -> {
            failed[2] = true;
            completed[2] = true;
            render.run();
        });
    }

    private void loadOrders(LinearLayout target) {
        loadOrders(target, true);
    }

    private void loadOrders(LinearLayout target, boolean showLoading) {
        if (showLoading) renderLoading(target, "订单同步中");
        request("同步订单", "GET", "/native/orders?refresh=1", null, result -> {
            target.removeAllViews();
            renderPlatformOrders(result.optJSONObject("data"), target);
        }, () -> renderRetry(target, "订单同步失败", () -> loadOrders(target)));
    }

    private int renderTradeTaskRows(JSONArray tasks, boolean retired, LinearLayout target) {
        if (tasks == null) return 0;
        int rendered = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null) continue;
            String status = task.optString("status", "");
            if (isTerminalTaskStatus(status)) continue;
            String type = retired ? ("immediate_purchase".equals(task.optString("executionMode")) ? "立即买入" : "捡漏") : tradeTypeLabel(task.optString("type", ""));
            LinearLayout row = card();
            row.addView(text(type + " · " + first(task, "title", "name", "groupId", "id") + " · " + tradeStatusLabel(status, retired), 14, ink, Typeface.BOLD));
            String amount;
            if (retired) {
                amount = "价格 " + money(first(task, "minPrice")) + " 至 " + money(first(task, "maxPrice"));
                if ("locked".equals(status)) amount += " · 锁定 " + money(first(task, "lockedPrice"));
            } else if ("consignment".equals(task.optString("type"))) {
                amount = "挂牌 " + money(first(task, "price")) + " · 行情不低于 " + money(first(task, "triggerPrice"));
            } else {
                amount = money(first(task, "price")) + " · " + Math.max(1, intValue(first(task, "quantity"), 1)) + " 件";
            }
            String last = task.optString("lastResult", "");
            String metadata = "账号 " + first(task, "phone", "sourcePhone", "-") + " · " + amount;
            if (!last.isEmpty()) metadata += "\n" + taskResultLabel(last);
            row.addView(text(metadata, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));

            String id = task.optString("id", "");
            if (!id.isEmpty()) {
                String base = retired ? "/native/retired-market/tasks/" : "/native/market/trade/tasks/";
                LinearLayout actions = horizontal(Color.TRANSPARENT);
                if ("verification_required".equals(status)) {
                    Button verify = button("完成人机验证", true);
                    verify.setOnClickListener(v -> startTaskCaptcha(retired ? "捡漏" : "交易", id, first(task, "phone", "sourcePhone")));
                    actions.addView(verify, new LinearLayout.LayoutParams(0, dp(42), 1));
                } else if ((retired && ("draft".equals(status) || "paused".equals(status) || "blocked".equals(status)))
                        || (!retired && ("draft".equals(status) || "paused".equals(status) || "blocked".equals(status)))) {
                    Button start = button("启动监控", false);
                    start.setOnClickListener(v -> request("启动任务", "POST", base + Uri.encode(id) + "/enable", null, result -> showTrade()));
                    actions.addView(start, new LinearLayout.LayoutParams(0, dp(42), 1));
                } else if ("scheduled".equals(status) || "waiting_price".equals(status)) {
                    Button pause = button("暂停", false);
                    pause.setOnClickListener(v -> request("暂停任务", "POST", base + Uri.encode(id) + "/disable", null, result -> showTrade()));
                    actions.addView(pause, new LinearLayout.LayoutParams(0, dp(42), 1));
                }
                Button delete = button("删除", false);
                delete.setTextColor(danger);
                delete.setOnClickListener(v -> showProjectDialog("删除任务", text("确认删除这条任务？", 14, muted, Typeface.NORMAL), "删除", dialog -> {
                    dialog.dismiss();
                    request("删除任务", "DELETE", base + Uri.encode(id), null, result -> showTrade());
                }));
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(actions.getChildCount() == 0 ? -1 : dp(76), dp(42));
                if (actions.getChildCount() > 0) deleteParams.setMargins(dp(8), 0, 0, 0);
                actions.addView(delete, deleteParams);
                row.addView(actions, marginParams(-1, -2, 0, dp(10), 0, 0));
            }
            target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            rendered++;
        }
        return rendered;
    }

    private Set<String> pendingOrderIds(JSONObject data) {
        Set<String> result = new HashSet<>();
        if (data == null) return result;
        JSONArray pending = pendingOrders(mergeOrderGroups(
                findArray(data, "collectionPendingOrders"),
                findArray(data, "wantedPendingOrders"),
                findArray(data, "pendingOrders", "pending")
        ));
        for (int i = 0; i < pending.length(); i++) {
            JSONObject order = pending.optJSONObject(i);
            String orderUuid = first(order, "orderUuid", "orderUUId", "orderNumber", "id");
            if (!orderUuid.isEmpty()) result.add(orderUuid);
        }
        return result;
    }

    private int renderPendingTaskSources(JSONArray tasks, boolean retired, Set<String> pendingOrderIds, LinearLayout target) {
        if (tasks == null) return 0;
        int rendered = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null) continue;
            String status = task.optString("status", "");
            if (!"payment_pending".equals(status) || !pendingOrderIds.contains(task.optString("orderUuid"))) continue;
            String type = retired ? ("immediate_purchase".equals(task.optString("executionMode")) ? "立即买入" : "捡漏") : tradeTypeLabel(task.optString("type", ""));
            LinearLayout row = card();
            row.addView(text(type + " · " + first(task, "title", "name", "groupId", "id") + " · " + tradeStatusLabel(status, retired), 14, ink, Typeface.BOLD));
            String amount;
            if (retired) {
                amount = "价格 " + money(first(task, "minPrice")) + " 至 " + money(first(task, "maxPrice"));
                if (!first(task, "lockedPrice").isEmpty()) amount += " · 锁定 " + money(first(task, "lockedPrice"));
            } else if ("consignment".equals(task.optString("type"))) {
                amount = "挂牌 " + money(first(task, "price"));
            } else {
                amount = money(first(task, "price")) + " · " + Math.max(1, intValue(first(task, "quantity"), 1)) + " 件";
            }
            String metadata = "账号 " + first(task, "phone", "sourcePhone", "-") + " · " + amount;
            metadata += "\n已创建待支付订单，支付入口在订单记录";
            String lockedAt = first(task, "lockedAt", "submittedAt", "updatedAt");
            if (!lockedAt.isEmpty()) metadata += " · " + time(lockedAt);
            row.addView(text(metadata, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, 0));
            target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            rendered++;
        }
        return rendered;
    }

    private void renderPlatformOrders(JSONObject data, LinearLayout target) {
        if (data == null) {
            target.addView(empty("暂无订单"));
            return;
        }
        JSONArray pending = pendingOrders(mergeOrderGroups(
                findArray(data, "collectionPendingOrders"),
                findArray(data, "wantedPendingOrders"),
                findArray(data, "pendingOrders", "pending")
        ));
        JSONArray sells = arrayOrEmpty(findArray(data, "sellOrders"));
        JSONArray buys = withoutPendingBuys(findArray(data, "buyOrders"));
        if (pending.length() == 0 && sells.length() == 0 && buys.length() == 0) {
            target.addView(empty("暂无订单"));
            return;
        }
        renderOrderGroup("待支付订单", pending, true, target);
        renderOrderGroup("卖出订单 · 寄售", sells, false, target);
        renderOrderGroup("买入订单 · 求购", buys, false, target);
    }

    private JSONArray mergeOrderGroups(JSONArray... sources) {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONArray source : sources) {
            if (source == null) continue;
            for (int i = 0; i < source.length(); i++) {
                JSONObject order = source.optJSONObject(i);
                if (order == null) continue;
                String key = first(order, "orderUuid", "orderUUId", "orderNumber", "id");
                if (key.isEmpty()) key = first(order, "type") + ":" + first(order, "createdAt", "createTime") + ":" + first(order, "price", "amount");
                if (seen.add(key)) result.put(order);
            }
        }
        return result;
    }

    private JSONArray withoutPendingBuys(JSONArray orders) {
        JSONArray result = new JSONArray();
        if (orders == null) return result;
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.optJSONObject(i);
            if (order != null && intValue(first(order, "orderStatus", "status", "orderState"), -1) != 0) result.put(order);
        }
        return result;
    }

    private JSONArray pendingOrders(JSONArray orders) {
        JSONArray result = new JSONArray();
        if (orders == null) return result;
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.optJSONObject(i);
            if (order != null && intValue(first(order, "orderStatus", "status", "orderState"), -1) == 0) result.put(order);
        }
        return result;
    }

    private void renderOrderGroup(String title, JSONArray orders, boolean pending, LinearLayout target) {
        TextView heading = text(title + " · " + orders.length(), 14, ink, Typeface.BOLD);
        target.addView(heading, marginParams(-1, -2, 0, 0, 0, dp(8)));
        if (orders.length() == 0) {
            target.addView(empty("暂无" + title));
            return;
        }
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.optJSONObject(i);
            if (order == null) continue;
            String titleText = first(order, "title", "name", "groupId", "id");
            LinearLayout row = card();
            LinearLayout header = horizontal(Color.TRANSPARENT);
            ImageView cover = coverImage(first(order, "cover", "image", "imageUrl", "coverUrl"), titleText);
            header.addView(cover, marginParams(dp(48), dp(48), 0, 0, dp(12), 0));
            LinearLayout details = vertical(Color.TRANSPARENT);
            details.addView(text(titleText + " · " + platformOrderStatusLabel(order, pending), 14, ink, Typeface.BOLD));
            String orderId = first(order, "orderUuid", "orderUUId", "orderNumber", "id");
            String metadata = "账号 " + first(order, "phone", "-") + " · " + (orderId.isEmpty() ? "平台订单" : "订单 " + orderId);
            String createdAt = first(order, "createdAt", "createTime");
            if (!createdAt.isEmpty()) metadata += " · " + time(createdAt);
            details.addView(text(metadata, 11, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(5), 0, 0));
            header.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(text(money(first(order, "price", "salePrice", "totalPrice", "amount")), 13, pending ? amber : success, Typeface.BOLD), new LinearLayout.LayoutParams(-2, -2));
            row.addView(header, new LinearLayout.LayoutParams(-1, -2));
            if (pending && !orderId.isEmpty()) {
                Button payment = button("进入支付", true);
                payment.setOnClickListener(v -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("phone", first(order, "phone", selectedPhone));
                        body.put("paymentInitiatorType", "collection_pending".equals(order.optString("type")) ? 0 : 2);
                    } catch (Exception ignored) {
                    }
                    request("获取支付链接", "POST", "/native/orders/" + Uri.encode(orderId) + "/payment", body,
                            result -> openWallet(first(result.optJSONObject("data"), "cashierLink", "paymentUrl")));
                });
                row.addView(payment, marginParams(-1, dp(40), 0, dp(10), 0, 0));
            }
            target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
        }
    }

    private void renderFirstSaleTaskList(JSONArray tasks, LinearLayout target) {
        target.removeAllViews();
        if (tasks == null || tasks.length() == 0) {
            target.addView(empty("暂无抢购任务"));
            return;
        }
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null) continue;
            String status = task.optString("status", "");
            String mode = "immediate".equals(task.optString("mode")) ? "立即抢购" : "定时抢购";
            LinearLayout row = card();
            LinearLayout header = horizontal(Color.TRANSPARENT);
            ImageView cover = coverImage(first(task, "cover", "image", "imageUrl", "coverUrl"), first(task, "title", "saleId", "id"));
            header.addView(cover, marginParams(dp(50), dp(50), 0, 0, dp(12), 0));
            header.addView(text(first(task, "title", "saleId", "id") + " · " + mode + " · " + firstSaleTaskStatusLabel(status), 14, ink, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(header, marginParams(-1, -2, 0, 0, 0, dp(2)));
            String eventTime = "immediate".equals(task.optString("mode"))
                    ? first(task, "startedAt", "createdAt")
                    : first(task, "startTime", "startAt");
            JSONArray boundPhones = task.optJSONArray("phones");
            int boundCount = boundPhones == null ? (first(task, "phone", "sourcePhone").isEmpty() ? 0 : 1) : boundPhones.length();
            String metadata = (eventTime.isEmpty() ? "时间待定" : time(eventTime))
                    + " · 数量 " + Math.max(1, intValue(first(task, "num"), 1))
                    + " · 已绑定 " + boundCount + " 个账号";
            row.addView(text(metadata, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(8), 0, dp(4)));
            JSONObject runs = task.optJSONObject("runs");
            if (runs != null) {
                Iterator<String> phones = runs.keys();
                while (phones.hasNext()) {
                    String phone = phones.next();
                    JSONObject run = runs.optJSONObject(phone);
                    LinearLayout runRow = horizontal(Color.TRANSPARENT);
                    runRow.setGravity(Gravity.CENTER_VERTICAL);
                    runRow.addView(text(phone + " · " + firstSaleRunLabel(run), 12, muted, Typeface.NORMAL), new LinearLayout.LayoutParams(0, dp(34), 1));
                    if (run != null && "verification_required".equals(run.optString("status"))) {
                        Button verify = button("验证", true);
                        verify.setOnClickListener(v -> startTaskCaptcha("首发", task.optString("id"), phone));
                        runRow.addView(verify, new LinearLayout.LayoutParams(dp(72), dp(34)));
                    } else if (run != null && "payment_pending".equals(run.optString("status")) && !first(run, "cashierLink", "paymentUrl").isEmpty()) {
                        Button pay = button("支付", true);
                        String link = first(run, "cashierLink", "paymentUrl");
                        pay.setOnClickListener(v -> openWallet(link));
                        runRow.addView(pay, new LinearLayout.LayoutParams(dp(72), dp(34)));
                    }
                    row.addView(runRow, new LinearLayout.LayoutParams(-1, dp(34)));
                }
            }
            if (("scheduled".equals(status) || "verification_required".equals(status)) && !task.optString("id").isEmpty()) {
                Button cancel = button("取消任务", false);
                cancel.setTextColor(danger);
                cancel.setOnClickListener(v -> showProjectDialog("取消任务", text("取消后不会再按计划执行，是否继续？", 14, muted, Typeface.NORMAL), "确认取消", dialog -> {
                    dialog.dismiss();
                    request("取消首发任务", "POST", "/native/first-sales/tasks/" + Uri.encode(task.optString("id")) + "/cancel", null, result -> showFirstSale());
                }));
                row.addView(cancel, marginParams(-1, dp(40), 0, dp(8), 0, 0));
            }
            if (!task.optString("id").isEmpty() && !"scheduled".equals(status) && !"verification_required".equals(status)) {
                Button delete = button("删除记录", false);
                delete.setTextColor(danger);
                delete.setOnClickListener(v -> showProjectDialog("删除抢购记录", text("只删除本机记录，不会再次提交该任务。", 14, muted, Typeface.NORMAL), "删除", dialog -> {
                    dialog.dismiss();
                    request("删除首发记录", "DELETE", "/native/first-sales/tasks/" + Uri.encode(task.optString("id")), null, result -> showFirstSale());
                }));
                row.addView(delete, marginParams(-1, dp(40), 0, dp(8), 0, 0));
            }
            target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
        }
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
                if ("scheduled".equals(status)) {
                    Button cancel = button("取消任务", false);
                    cancel.setTextColor(danger);
                    cancel.setOnClickListener(v -> showProjectDialog("取消任务", text("取消后不会再按计划执行，是否继续？", 14, muted, Typeface.NORMAL), "确认取消", dialog -> {
                        dialog.dismiss();
                        String path = prefix.startsWith("合成")
                                ? "/native/synthesis/tasks/" + Uri.encode(id) + "/cancel"
                                : "/native/first-sales/tasks/" + Uri.encode(id) + "/cancel";
                        request("取消" + prefix, "POST", path, null, result -> {
                            target.removeAllViews();
                            if (prefix.startsWith("合成")) showSynthesis(); else showFirstSale();
                        });
                    }));
                    row.addView(cancel, new LinearLayout.LayoutParams(-1, dp(40)));
                }
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
                delete.setOnClickListener(v -> showProjectDialog("删除任务", text("确认删除这条任务？", 14, muted, Typeface.NORMAL), "删除", dialog -> {
                    dialog.dismiss();
                    request("删除任务", "DELETE", base + Uri.encode(id), null, result -> showTrade());
                }));
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
        content.removeAllViews();
        LinearLayout list = vertical(Color.TRANSPARENT);
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        loadLottery(list);
    }

    private void loadLottery(LinearLayout target) {
        renderLoading(target, "抽奖同步中");
        request("同步抽奖状态", "GET", "/native/lottery/auto", null, result -> {
            target.removeAllViews(); JSONArray activities = findArray(result.optJSONObject("data"), "activities", "items", "list");
            if (activities == null || activities.length() == 0) { target.addView(empty("暂无抽奖活动")); return; }
            for (int i = 0; i < activities.length(); i++) {
                JSONObject activity = activities.optJSONObject(i); if (activity == null) continue;
                LinearLayout row = card();
                row.addView(text(first(activity, "title", "name", "id") + " · " + lotteryPhaseLabel(activity), 15, ink, Typeface.BOLD));
                String starts = first(activity, "startedAt", "startTime");
                String ends = first(activity, "endedAt", "endTime");
                row.addView(text((starts.isEmpty() ? "时间待定" : time(starts)) + (ends.isEmpty() ? "" : " 至 " + time(ends)), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(6), 0, dp(8)));
                JSONObject accountStates = activity.optJSONObject("accounts");
                if (accountStates != null) {
                    Iterator<String> phones = accountStates.keys();
                    while (phones.hasNext()) {
                        String phone = phones.next();
                        JSONObject snapshot = accountStates.optJSONObject(phone);
                        int available = snapshot == null ? -1 : snapshot.optInt("availableCount", -1);
                        String count = available < 0 ? "次数待同步" : "剩余 " + available + " 次";
                        String status = lotteryAccountStatusLabel(snapshot);
                        row.addView(text(phone + " · " + count + " · " + status, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, 0, 0, dp(5)));
                    }
                }
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        }, () -> renderRetry(target, "抽奖活动同步失败", () -> loadLottery(target)));
    }

    private void showFirstSale() {
        content.removeAllViews();
        addSectionTitle("发售项目");
        LinearLayout sales = vertical(Color.TRANSPARENT);
        content.addView(sales, marginParams(-1, -2, 0, 0, 0, dp(14)));
        addSectionTitle("抢购任务");
        LinearLayout tasks = vertical(Color.TRANSPARENT);
        content.addView(tasks, new LinearLayout.LayoutParams(-1, -2));
        if (selectedPhone.isEmpty()) sales.addView(empty("请先登录账号"));
        else loadFirstSales(sales);
        loadFirstSaleTasks(tasks);
    }

    private void loadFirstSales(LinearLayout target) {
        renderLoading(target, "首发项目同步中");
        request("同步首发项目", "GET", "/native/first-sales?refresh=1&phone=" + Uri.encode(selectedPhone), null, result -> {
            target.removeAllViews(); JSONArray items = findArray(result.optJSONObject("data"), "items", "sales", "list");
            if (items == null || items.length() == 0) { target.addView(empty("暂无首发项目")); return; }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                String title = first(item, "title", "name", "saleId", "id");
                LinearLayout row = card();
                LinearLayout header = horizontal(Color.TRANSPARENT);
                ImageView cover = coverImage(first(item, "cover", "image", "imageUrl", "coverUrl"), title);
                header.addView(cover, marginParams(dp(54), dp(54), 0, 0, dp(12), 0));
                LinearLayout details = vertical(Color.TRANSPARENT);
                details.addView(text(title + " · " + firstSalePhaseLabel(item), 15, ink, Typeface.BOLD));
                String limit = first(item, "userOnceMaxBuyNum", "onceMaxBuyNum", "limitNum");
                details.addView(text(money(first(item, "price", "salePrice")) + " · " + (limit.isEmpty() ? "限购待确认" : "限购 " + limit + " 件"), 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(6), 0, 0));
                header.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(header, marginParams(-1, -2, 0, 0, 0, dp(8)));
                String mode = item.optString("action", "");
                boolean canPrepare = ("scheduled".equals(mode) || "immediate".equals(mode))
                        && !"expired_placeholder".equals(item.optString("phase", ""))
                        && !first(item, "saleId", "id").isEmpty()
                        && !first(item, "groupId", "digitalCollectionGroupId").isEmpty()
                        && intValue(limit, 0) > 0;
                if (canPrepare) {
                    Button prepare = button("immediate".equals(mode) ? "立即抢购" : "准备抢购", "immediate".equals(mode));
                    prepare.setOnClickListener(v -> showFirstSaleDialog(item, mode));
                    row.addView(prepare, new LinearLayout.LayoutParams(-1, dp(42)));
                }
                target.addView(row, marginParams(-1, -2, 0, 0, 0, dp(10)));
            }
        }, () -> renderRetry(target, "首发项目同步失败", () -> loadFirstSales(target)));
    }

    private void loadFirstSaleTasks(LinearLayout target) {
        renderLoading(target, "抢购任务同步中");
        request("同步首发任务", "GET", "/native/first-sales/tasks", null, result -> {
            JSONArray tasks = findArray(result.optJSONObject("data"), "tasks", "items", "list");
            renderFirstSaleTaskList(tasks, target);
        }, () -> renderRetry(target, "抢购任务同步失败", () -> loadFirstSaleTasks(target)));
    }

    private void showFirstSaleDialog(JSONObject item, String initialMode) {
        if (selectedPhone.isEmpty()) { toast("请先选择账号"); return; }
        LinearLayout form = vertical(Color.TRANSPARENT);
        OptionField mode = optionField("提交方式", new String[]{initialMode}, initialMode);
        EditText count = numberInput("购买数量", "1"); EditText paymentCode = numberInput("支付通道编号", "");
        EditText paymentPassword = passwordInput("支付密码");
        form.addView(labelled("提交方式", mode));
        form.addView(count, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(paymentCode, marginParams(-1, dp(46), 0, 0, 0, dp(8))); form.addView(paymentPassword, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        List<CheckBox> taskAccounts = accountSelection(form);
        showProjectDialog("加入首发抢购", form, "保存任务", dialog -> {
            JSONObject body = new JSONObject();
            try {
                body.put("groupId", first(item, "groupId", "id"));
                body.put("saleId", first(item, "saleId", "id"));
                body.put("title", first(item, "title", "name", "saleId", "id"));
                body.put("cover", first(item, "cover", "image", "imageUrl", "coverUrl"));
                body.put("num", intValue(count, 1));
                body.put("paymentPlatformCode", intValue(paymentCode, 0));
                body.put("paymentPassword", paymentPassword.getText().toString().trim());
                body.put("phones", selectedPhones(taskAccounts));
                body.put("sourcePhone", selectedPhone);
                body.put("mode", mode.value());
                boolean immediate = "immediate".equals(mode.value());
                body.put("startAt", immediate ? java.time.Instant.now().toString() : first(item, "startAt", "startTime", "onSaleTime", "saleTime"));
            } catch (Exception ignored) {
            }
            boolean immediate = "immediate".equals(mode.value());
            request(immediate ? "立即提交首发" : "保存首发任务", "POST", "/native/first-sales/tasks", body, result -> { dialog.dismiss(); toast(immediate ? "首发任务已提交" : "首发任务已保存"); showFirstSale(); });
        });
    }

    private void showSettings() {
        content.removeAllViews();

        LinearLayout bark = card(); bark.addView(text("Bark 通知", 16, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(10)));
        ProjectToggle enabled = toggle("启用推送", false); bark.addView(enabled);
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
        ProjectToggle backgroundSync = toggle("保持状态通知", backgroundSyncEnabled());
        backgroundCard.addView(backgroundSync);
        String lastSummary = preferences.getString("last_sync_summary", "尚未同步");
        backgroundCard.addView(text("最近：" + lastSummary, 12, muted, Typeface.NORMAL), marginParams(-1, -2, 0, dp(2), 0, dp(8)));
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

    private void fillBark(JSONObject result, ProjectToggle enabled, EditText server, EditText key, EditText hour) {
        JSONObject data = result.optJSONObject("data"); if (data == null) data = result;
        enabled.setChecked(data.optBoolean("enabled", false)); server.setText(data.optString("server", "")); key.setText(data.optString("deviceKey", "")); hour.setText(String.valueOf(data.optInt("dailySummaryHour", 9)));
    }

    private void showJsonDialog(String title, JSONObject data) {
        ScrollView scroll = new ScrollView(this); TextView body = text(pretty(data), 12, ink, Typeface.NORMAL); body.setTextIsSelectable(true); body.setPadding(dp(4), dp(4), dp(4), dp(4)); scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        showProjectDialog(title, scroll, null, null);
    }

    private Dialog showProjectDialog(String title, View body, String actionLabel, Consumer<Dialog> action) {
        Dialog dialog = new Dialog(this, R.style.ProjectDialogTheme);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = vertical(surface);
        sheet.setPadding(dp(20), dp(18), dp(20), dp(20));
        sheet.setBackground(shape(surface, 0xffe5eeeb, 16));

        LinearLayout header = horizontal(Color.TRANSPARENT);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(title, 20, ink, Typeface.BOLD), new LinearLayout.LayoutParams(0, dp(44), 1));
        Button close = button("×", false);
        close.setTextSize(22);
        close.setPadding(0, 0, 0, 0);
        close.setTextColor(ink);
        close.setBackground(ripple(0x1965c68b, shape(0xffedf4f1, 0, 22)));
        close.setContentDescription("关闭弹层");
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        sheet.addView(header, marginParams(-1, dp(44), 0, 0, 0, dp(14)));

        if (body != null) {
            View dialogBody = body;
            boolean scrollable = body instanceof ScrollView;
            // Long native forms must keep the header and action visible on small screens.
            if (!scrollable && body instanceof LinearLayout && ((LinearLayout) body).getChildCount() >= 4) {
                ScrollView scroll = new ScrollView(this);
                scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
                dialogBody = scroll;
                scrollable = true;
            }
            if (dialogBody instanceof ScrollView) {
                styleScroll((ScrollView) dialogBody);
                ((ScrollView) dialogBody).setFillViewport(true);
            }
            int bodyHeight = scrollable ? dialogScrollHeight() : ViewGroup.LayoutParams.WRAP_CONTENT;
            sheet.addView(dialogBody, marginParams(-1, bodyHeight, 0, 0, 0, actionLabel == null ? 0 : dp(14)));
        }
        if (actionLabel != null && action != null) {
            Button submit = button(actionLabel, true);
            submit.setOnClickListener(v -> action.accept(dialog));
            sheet.addView(submit, new LinearLayout.LayoutParams(-1, dp(48)));
        }

        dialog.setContentView(sheet);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(32));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        return dialog;
    }

    private int dialogScrollHeight() {
        int available = getResources().getDisplayMetrics().heightPixels - dp(196);
        return Math.max(dp(160), Math.min(dp(460), available));
    }

    private void styleScroll(ScrollView scroll) {
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private void request(String label, String method, String path, JSONObject body, Consumer<JSONObject> successCallback) {
        request(label, method, path, body, successCallback, null);
    }

    private void request(String label, String method, String path, JSONObject body, Consumer<JSONObject> successCallback, Runnable failureCallback) {
        if (engine == null) engine = new NativeEngine(this);
        setStatus(label + "…", muted);
        io.execute(() -> {
            try {
                JSONObject result = engine.request(method, path, body);
                runOnUiThread(() -> { setStatus("已同步", success); successCallback.accept(result); });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setStatus(error.getMessage() == null ? "请求失败" : error.getMessage(), danger);
                    toast(error.getMessage() == null ? "请求失败" : error.getMessage());
                    if (failureCallback != null) failureCallback.run();
                });
            }
        });
    }

    private void setStatus(String value, int color) {
        if (statusView != null) { statusView.setText(value); statusView.setTextColor(color); }
    }

    private void renderLoading(LinearLayout target, String label) {
        target.removeAllViews();
        target.addView(empty(label));
    }

    private void renderRetry(LinearLayout target, String label, Runnable retry) {
        target.removeAllViews();
        target.addView(empty(label), marginParams(-1, -2, 0, 0, 0, dp(8)));
        Button retryButton = button("重试", false);
        retryButton.setOnClickListener(v -> retry.run());
        target.addView(retryButton, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    private void addSectionTitle(String title) { content.addView(text(title, 17, ink, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(8))); }

    private ImageView coverImage(String source, String name) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(shape(0xffedf4f1, 0xffdce8e4, 10));
        image.setContentDescription((name == null || name.isEmpty() ? "藏品" : name) + "封面");
        loadCover(image, source);
        return image;
    }

    private void loadCover(ImageView target, String source) {
        String url = coverUrl(source);
        if (url.isEmpty()) return;
        target.setTag(url);
        Bitmap cached = coverCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = decodeCover(url);
            if (bitmap == null) return;
            coverCache.put(url, bitmap);
            runOnUiThread(() -> {
                Object active = target.getTag();
                if (url.equals(active)) target.setImageBitmap(bitmap);
            });
        });
    }

    private String coverUrl(String source) {
        String value = source == null ? "" : source.trim();
        if (value.startsWith("//")) value = "https:" + value;
        if (value.isEmpty()) return "";
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return "";
        return value;
    }

    private Bitmap decodeCover(String source) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return null;
            try (InputStream input = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) return null;
                int edge = dp(112);
                int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
                if (longest <= edge) return bitmap;
                float ratio = edge / (float) longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)), Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
                if (scaled != bitmap) bitmap.recycle();
                return scaled;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void openWallet(String link) {
        if (link == null || link.trim().isEmpty()) {
            toast("支付入口不可用");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (Exception error) {
            toast("无法打开支付入口");
        }
    }

    private LinearLayout portfolioHeadline(JSONObject data, boolean stale) {
        LinearLayout headline = horizontal(Color.TRANSPARENT);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout value = vertical(Color.TRANSPARENT);
        value.addView(text("组合市值", 12, muted, Typeface.BOLD));
        String estimate = first(data, "estimatedValue");
        JSONArray items = findArray(data, "items", "collections", "records", "list");
        String pricedQuantity = first(data, "pricedQuantity");
        String valuationCoverage = "已估值 " + (pricedQuantity.isEmpty() ? "0" : compactNumber(parseDouble(pricedQuantity, 0d)))
                + " / " + (items == null ? "0" : compactNumber(items.length()));
        value.addView(text(money(estimate.isEmpty() ? "0" : estimate), 28, ink, Typeface.BOLD), marginParams(-1, -2, 0, dp(4), 0, 0));
        value.addView(text(valuationCoverage, 11, muted, Typeface.NORMAL));
        headline.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        if (stale) {
            TextView status = text("缓存", 11, amber, Typeface.BOLD);
            status.setGravity(Gravity.CENTER);
            status.setPadding(dp(10), 0, dp(10), 0);
            status.setBackground(shape(0xfffff4df, 0, 18));
            headline.addView(status, new LinearLayout.LayoutParams(-2, dp(32)));
        }
        return headline;
    }

    private LinearLayout metricGrid(JSONObject data, JSONArray items, String phone) {
        LinearLayout grid = vertical(Color.TRANSPARENT);
        double costBasis = 0d;
        double costQuantity = 0d;
        double pricedCostValue = 0d;
        double pricedCostQuantity = 0d;
        int itemCount = items == null ? 0 : items.length();
        for (int index = 0; index < itemCount; index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            String id = first(item, "id", "groupId", "collectionId", "digitalCollectionId");
            double quantity = Math.max(0d, parseDouble(first(item, "quantity", "num", "count", "holdNum"), 1d));
            double unitCost = engine.store().getAssetCost(phone, id);
            double floorPrice = parseDouble(first(item, "floorPrice", "price", "valuation", "marketPrice"), Double.NaN);
            if (Double.isFinite(unitCost)) {
                costBasis += quantity * unitCost;
                costQuantity += quantity;
                if (Double.isFinite(floorPrice)) {
                    pricedCostValue += quantity * floorPrice;
                    pricedCostQuantity += quantity;
                }
            }
        }
        boolean hasReturn = costQuantity > 0d && Double.compare(pricedCostQuantity, costQuantity) == 0;
        double returnRate = hasReturn && costBasis > 0d ? (pricedCostValue - costBasis) / costBasis * 100d : Double.NaN;
        String cost = costQuantity > 0d ? money(String.valueOf(costBasis)) : "待录入";
        double estimatedValue = parseDouble(first(data, "estimatedValue"), 0d);
        String totalValue = money(String.valueOf(Double.isFinite(estimatedValue) && estimatedValue >= 0d ? estimatedValue : 0d));
        String rate = Double.isFinite(returnRate) ? signedPercent(returnRate) : "0%";
        String[][] values = {
                {"持仓数量", first(data, "total", "totalCount", "count")},
                {"持仓成本", cost},
                {"总值", totalValue},
                {"收益率", rate}
        };
        for (int index = 0; index < values.length; index += 2) {
            LinearLayout row = horizontal(Color.TRANSPARENT);
            row.setWeightSum(2f);
            addMetricCell(row, values[index][0], values[index][1], ink, false);
            addMetricCell(row, values[index + 1][0], values[index + 1][1], index + 1 == 3 && Double.isFinite(returnRate) ? (returnRate >= 0d ? success : danger) : ink, true);
            grid.addView(row, marginParams(-1, dp(62), 0, index == 0 ? 0 : dp(6), 0, 0));
        }
        return grid;
    }

    private void addMetricCell(LinearLayout row, String label, String value, int valueColor, boolean last) {
        LinearLayout cell = vertical(0xfff3f8f5);
        cell.setPadding(dp(11), dp(8), dp(11), dp(7));
        cell.addView(text(label, 11, muted, Typeface.NORMAL));
        cell.addView(text(value == null || value.isEmpty() ? "--" : value, 15, valueColor, Typeface.BOLD), marginParams(-1, -2, 0, dp(3), 0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        if (!last) params.setMargins(0, 0, dp(6), 0);
        row.addView(cell, params);
    }

    private LinearLayout labelled(String title, View view) { LinearLayout box = vertical(Color.TRANSPARENT); box.addView(text(title, 12, muted, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(4))); box.addView(view, marginParams(-1, dp(46), 0, 0, 0, dp(8))); return box; }

    private List<CheckBox> accountSelection(LinearLayout form) {
        List<CheckBox> checks = new ArrayList<>();
        if (accounts.length() == 0) return checks;
        form.addView(text("执行账号", 12, muted, Typeface.BOLD), marginParams(-1, -2, 0, 0, 0, dp(4)));
        LinearLayout box = vertical(Color.TRANSPARENT);
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject item = accounts.optJSONObject(index);
            if (item == null) continue;
            String phone = item.optString("phone", "").trim();
            if (phone.isEmpty()) continue;
            CheckBox check = new CheckBox(this);
            check.setText(phone);
            check.setTextColor(ink);
            check.setTextSize(14);
            check.setChecked(phone.equals(selectedPhone));
            checks.add(check);
            box.addView(check, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        form.addView(box, marginParams(-1, -2, 0, 0, 0, dp(8)));
        return checks;
    }

    private JSONArray selectedPhones(List<CheckBox> checks) throws Exception {
        JSONArray result = new JSONArray();
        if (checks == null) return result;
        for (CheckBox check : checks) if (check != null && check.isChecked()) result.put(check.getText().toString().trim());
        if (result.length() == 0 && !selectedPhone.isEmpty()) result.put(selectedPhone);
        return result;
    }

    private ProjectToggle toggle(String label, boolean checked) { return new ProjectToggle(label, checked); }

    private OptionField optionField(String title, String[] values, String selected) { return new OptionField(title, values, selected); }

    private void showOptionDialog(OptionField field) {
        LinearLayout options = vertical(Color.TRANSPARENT);
        final Dialog[] dialog = new Dialog[1];
        for (String value : field.values) {
            Button option = button(optionLabel(value), value.equals(field.value()));
            option.setContentDescription(field.title + "：" + optionLabel(value));
            option.setOnClickListener(v -> {
                field.setValue(value);
                if (dialog[0] != null) dialog[0].dismiss();
            });
            options.addView(option, marginParams(-1, dp(46), 0, 0, 0, dp(8)));
        }
        dialog[0] = showProjectDialog("选择" + field.title, options, null, null);
    }

    private String optionLabel(String value) {
        if ("monitor".equals(value)) return "仅监控";
        if ("live".equals(value)) return "真实执行";
        if ("seconds".equals(value)) return "秒";
        if ("minutes".equals(value)) return "分钟";
        if ("hours".equals(value)) return "小时";
        if ("wanted".equals(value)) return "求购";
        if ("consignment".equals(value)) return "寄售";
        if ("scheduled".equals(value)) return "定时提交";
        if ("immediate".equals(value)) return "立即提交";
        return value;
    }

    private interface ToggleChangeListener {
        void onCheckedChanged(ProjectToggle toggle, boolean checked);
    }

    private final class ProjectToggle extends LinearLayout {
        private final String label;
        private final TextView state;
        private boolean checked;
        private ToggleChangeListener listener;

        ProjectToggle(String label, boolean checked) {
            super(MainActivity.this);
            this.label = label;
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(48));
            TextView name = text(label, 13, ink, Typeface.NORMAL);
            addView(name, new LinearLayout.LayoutParams(0, dp(40), 1));
            state = text("", 12, Color.WHITE, Typeface.BOLD);
            state.setGravity(Gravity.CENTER);
            addView(state, new LinearLayout.LayoutParams(dp(46), dp(32)));
            setClickable(true);
            setFocusable(true);
            setBackground(ripple(0x1965c68b, shape(Color.TRANSPARENT, 0, 10)));
            setOnClickListener(v -> setChecked(!this.checked));
            setChecked(checked);
        }

        boolean isChecked() { return checked; }

        void setChecked(boolean value) {
            boolean changed = checked != value;
            checked = value;
            state.setText(value ? "开启" : "关闭");
            state.setTextColor(value ? Color.WHITE : muted);
            state.setBackground(shape(value ? primary : surface, value ? 0 : 0xffdce8e4, 10));
            setContentDescription(label + (value ? "，已开启" : "，已关闭"));
            if (changed && listener != null) listener.onCheckedChanged(this, value);
        }

        void setOnCheckedChangeListener(ToggleChangeListener listener) { this.listener = listener; }
    }

    private final class OptionField extends Button {
        private final String title;
        private final String[] values;
        private String selected;

        OptionField(String title, String[] values, String selected) {
            super(MainActivity.this);
            this.title = title;
            this.values = values;
            setAllCaps(false);
            setTextSize(14);
            setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(13), 0, dp(13), 0);
            styleButton(this, false);
            setValue(selected);
            setOnClickListener(v -> showOptionDialog(this));
        }

        String value() { return selected; }

        void setValue(String value) {
            selected = value;
            setText(optionLabel(value) + " \u2304");
            setContentDescription(title + "，当前" + optionLabel(value));
        }
    }

    private EditText numberInput(String hint, String value) { EditText input = input(hint); input.setText(value == null ? "" : value); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); return input; }
    private EditText passwordInput(String hint) { EditText input = input(hint); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return input; }
    private EditText input(String hint) { EditText input = new EditText(this); input.setHint(hint); input.setHintTextColor(0xff8795a0); input.setTextColor(ink); input.setTextSize(14); input.setSingleLine(true); input.setPadding(dp(13), 0, dp(13), 0); input.setBackground(shape(surface, 0xffdbe7e4, 12)); return input; }

    private LinearLayout card() { LinearLayout card = vertical(surface); card.setPadding(dp(17), dp(16), dp(17), dp(16)); card.setBackground(shape(surface, 0xffe5eeeb, 12)); card.setElevation(dp(2)); return card; }
    private LinearLayout vertical(int color) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setBackgroundColor(color); return view; }
    private LinearLayout horizontal(int color) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setBackgroundColor(color); return view; }
    private TextView empty(String value) { TextView view = text(value, 13, muted, Typeface.NORMAL); view.setGravity(Gravity.CENTER); view.setPadding(dp(12), dp(24), dp(12), dp(24)); view.setBackground(shape(surface, 0xffe5eeeb, 16)); return view; }
    private TextView text(String value, float size, int color, int style) { TextView view = new TextView(this); view.setText(value == null ? "" : value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(Typeface.DEFAULT, style); view.setLineSpacing(dp(2), 1f); return view; }
    private Button button(String value, boolean active) { Button button = new Button(this); button.setAllCaps(false); button.setText(value); button.setTextSize(13); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setMinHeight(dp(42)); button.setPadding(dp(10), 0, dp(10), 0); styleButton(button, active); return button; }
    private void styleButton(Button button, boolean active) { button.setTextColor(active ? Color.WHITE : ink); button.setBackground(ripple(active ? 0x33ffffff : 0x1965c68b, shape(active ? primary : surface, active ? primary : 0xffdce8e4, 12))); button.setElevation(active ? dp(1) : 0); }
    private GradientDrawable shape(int fill, int stroke, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); if (stroke != 0) drawable.setStroke(dp(1), stroke); drawable.setCornerRadius(dp(radius)); return drawable; }
    private RippleDrawable ripple(int color, GradientDrawable content) { return new RippleDrawable(ColorStateList.valueOf(color), content, null); }
    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height); params.setMargins(left, top, right, bottom); return params; }
    private void toast(String message) {
        if (isFinishing() || isDestroyed()) return;
        if (transientMessage == null) {
            transientMessage = text("", 13, Color.WHITE, Typeface.BOLD);
            transientMessage.setGravity(Gravity.CENTER);
            transientMessage.setMaxLines(2);
            transientMessage.setPadding(dp(16), dp(11), dp(16), dp(11));
            transientMessage.setBackground(shape(ink, 0, 12));
            transientMessage.setElevation(dp(8));
            transientMessage.setVisibility(View.GONE);
            addContentView(transientMessage, transientMessageParams());
        }
        transientMessage.removeCallbacks(dismissTransientMessage);
        transientMessage.setText(message == null || message.isEmpty() ? "操作完成" : message);
        transientMessage.setVisibility(View.VISIBLE);
        transientMessage.postDelayed(dismissTransientMessage, 2600);
    }

    private FrameLayout.LayoutParams transientMessageParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        params.setMargins(dp(16), 0, dp(16), bottomSystemInset + dp(44));
        return params;
    }

    private void updateTransientMessageMargin() {
        if (transientMessage != null) transientMessage.setLayoutParams(transientMessageParams());
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String value(JSONObject object, String key, String fallback) { return object == null ? fallback : object.optString(key, fallback); }
    private String first(JSONObject object, String... keys) { if (object == null) return ""; for (String key : keys) { Object value = object.opt(key); if (value != null && value != JSONObject.NULL && !String.valueOf(value).isEmpty()) return String.valueOf(value); } return ""; }
    private String number(JSONObject object, String key, String fallback) { return object == null ? fallback : String.valueOf(object.opt(key) == null || object.opt(key) == JSONObject.NULL ? fallback : object.opt(key)); }
    private String money(String value) { if (value == null || value.isEmpty()) return "价格不可用"; try { return "¥" + String.format(Locale.US, "%.2f", Double.parseDouble(value)); } catch (Exception error) { return value; } }
    private String moneyValue(String value) { if (value == null || value.isEmpty()) return ""; try { return String.format(Locale.US, "%.2f", Double.parseDouble(value)); } catch (Exception error) { return value; } }
    private String signedMoney(double value) { return (value >= 0d ? "+" : "-") + money(String.valueOf(Math.abs(value))); }
    private String signedPercent(double value) { return (value >= 0d ? "+" : "") + String.format(Locale.US, "%.2f", value) + "%"; }
    private String compactNumber(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.2f", value); }
    private String watchDelta(String changeValue, String percentValue) { double change = parseDouble(changeValue, Double.NaN); double percent = parseDouble(percentValue, Double.NaN); if (!Double.isFinite(change)) return "基准价"; String sign = change > 0d ? "+" : ""; String percentText = Double.isFinite(percent) ? " (" + (percent > 0d ? "+" : "") + String.format(Locale.US, "%.2f", percent) + "%)" : ""; return "变化 " + sign + String.format(Locale.US, "%.2f", change) + percentText; }
    private String time(String value) { if (value == null || value.isEmpty()) return "-"; try { return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date.from(java.time.Instant.parse(value))); } catch (Exception error) { return value.length() > 16 ? value.substring(0, 16).replace('T', ' ') : value; } }
    private double parseDouble(String value, double fallback) { try { return Double.parseDouble(value.trim()); } catch (Exception error) { return fallback; } }
    private double doubleValue(EditText input, double fallback) { return parseDouble(input.getText().toString(), fallback); }
    private int intValue(EditText input, int fallback) { try { return Integer.parseInt(input.getText().toString().trim()); } catch (Exception error) { return fallback; } }
    private int intValue(String value, int fallback) { try { return Integer.parseInt(value == null ? "" : value.trim()); } catch (Exception error) { return fallback; } }
    private boolean isTerminalTaskStatus(String status) { return "payment_pending".equals(status) || "submitted".equals(status) || "cancelled".equals(status); }
    private String synthesisPhaseLabel(String value) {
        if ("preparing".equals(value)) return "即将开始";
        if ("available".equals(value)) return "可合成";
        if ("expired".equals(value) || "ended".equals(value)) return "已结束";
        return value == null || value.isEmpty() ? "状态待确认" : value;
    }
    private String lotteryPhaseLabel(JSONObject activity) {
        String phase = activity == null ? "" : activity.optString("phase", "");
        if ("open".equals(phase)) return "进行中";
        if ("not_started".equals(phase)) return "未开始";
        if ("ended".equals(phase)) return "已结束";
        if ("time_unverified".equals(phase)) return "时间未确认";
        int status = activity == null ? -1 : activity.optInt("onlineStatus", -1);
        if (status == 1 && activity.optBoolean("enableOpen", false)) return "进行中";
        if (status == 0) return "未开始";
        if (status == 2) return "已结束";
        return "未开放";
    }
    private String lotteryAccountStatusLabel(JSONObject snapshot) {
        String status = snapshot == null ? "" : snapshot.optString("status", "");
        if ("ready".equals(status)) return "待抽奖";
        if ("drawing".equals(status)) return "抽奖中";
        if ("drawn".equals(status)) return "已抽取";
        if ("submitted".equals(status)) return "已完成";
        if ("no_chance".equals(status)) return "无抽奖次数";
        if ("not_started".equals(status)) return "等待开始";
        if ("ended".equals(status)) return "活动已结束";
        if ("failed".equals(status)) return first(snapshot, "message", "status");
        return "等待同步";
    }
    private String firstSalePhaseLabel(JSONObject item) {
        String phase = item == null ? "" : item.optString("phase", "");
        if ("expired_placeholder".equals(phase)) return "最近结束";
        String label = first(item, "saleStatusLabel");
        if (!label.isEmpty()) return label;
        if ("preparing".equals(phase)) return "即将开售";
        if ("available".equals(phase)) return "立即购买";
        if ("expired_placeholder".equals(phase)) return "最近结束";
        if ("sold_out".equals(phase)) return "已售罄";
        if ("ended".equals(phase)) return "已结束";
        return "状态未识别";
    }
    private String firstSaleTaskStatusLabel(String status) {
        if ("scheduled".equals(status)) return "等待开始";
        if ("running".equals(status)) return "提交中";
        if ("verification_required".equals(status)) return "等待人工验证";
        if ("payment_pending".equals(status)) return "待支付";
        if ("submitted".equals(status)) return "已提交";
        if ("partial".equals(status)) return "部分完成";
        if ("failed".equals(status)) return "提交失败";
        if ("expired".equals(status)) return "已过期";
        if ("cancelled".equals(status)) return "已取消";
        return "状态未知";
    }
    private String firstSaleRunLabel(JSONObject run) {
        String status = run == null ? "" : run.optString("status", "");
        String label;
        if ("ready".equals(status)) label = "已就绪";
        else if ("submitting".equals(status)) label = "提交中";
        else if ("verification_required".equals(status)) label = "需要人机验证";
        else if ("payment_pending".equals(status)) label = "待支付";
        else if ("submitted".equals(status)) label = "已提交";
        else if ("failed".equals(status)) label = "失败";
        else label = "等待同步";
        String message = first(run, "message");
        return message.isEmpty() ? label : label + " · " + message;
    }
    private String tradeTypeLabel(String type) {
        if ("consignment".equals(type)) return "寄售";
        if ("wanted".equals(type)) return "求购";
        return "交易";
    }
    private String tradeStatusLabel(String status, boolean retired) {
        if (retired) {
            if ("draft".equals(status)) return "待启动";
            if ("scheduled".equals(status)) return "监控中";
            if ("waiting_price".equals(status)) return "监控中·等待行情";
            if ("paused".equals(status)) return "已暂停";
            if ("blocked".equals(status)) return "不可锁单";
            if ("verification_required".equals(status)) return "需人机验证";
            if ("locked".equals(status)) return "已锁单";
            if ("payment_pending".equals(status)) return "待钱包支付";
            return "待处理";
        }
        if ("draft".equals(status)) return "待启动";
        if ("scheduled".equals(status)) return "监控中";
        if ("waiting_price".equals(status)) return "监控中·等待行情";
        if ("paused".equals(status)) return "已暂停";
        if ("blocked".equals(status)) return "账号受限";
        if ("verification_required".equals(status)) return "需人机验证";
        if ("submitted".equals(status)) return "已提交";
        if ("payment_pending".equals(status)) return "待钱包支付";
        if ("cancelled".equals(status)) return "寄售已取消";
        return "待处理";
    }
    private String taskResultLabel(String value) {
        if ("waiting_price".equals(value)) return "等待行情触发";
        if ("armed".equals(value)) return "已准备，等待提交条件";
        if ("ready".equals(value)) return "已就绪";
        if ("monitoring".equals(value)) return "监控中";
        if ("monitor_ready".equals(value)) return "监控已就绪";
        if ("locking".equals(value)) return "正在锁单";
        if ("locked".equals(value)) return "已锁单";
        if ("submitted".equals(value)) return "已提交";
        if ("consignment_cancelled".equals(value)) return "寄售已取消";
        if ("payment_pending".equals(value) || "retired_market_payment_pending".equals(value)) return "已创建订单，等待钱包支付";
        return value == null || value.isEmpty() || value.contains("_") ? "任务状态已更新" : value;
    }
    private String platformOrderStatusLabel(JSONObject order, boolean pending) {
        if (pending) return "待支付";
        int status = intValue(first(order, "orderStatus", "status", "orderState"), -1);
        int type = intValue(first(order, "orderType"), -1);
        if (status == 0) return "待付款";
        if (status == 1) return "支付成功";
        if (status == 2) return type == 1 ? "求购中" : "寄售中";
        if (status == 3) return "购买锁定";
        if (status == 4) return "取消购买";
        if (status == 5) return "已售出转移中";
        if (status == 6) return "结算中";
        if (status == 7) return "订单完成";
        if (status == 8) return "求购退款中";
        if (status == 9) return "退款重试中";
        if (status == 10) return "订单取消";
        if (status == 11) return "退款成功";
        return "平台已记录";
    }
    private String strategyStatus(String status) { if (status == null) return "待处理"; switch (status) { case "monitoring": return "监控中"; case "paused": return "已暂停"; case "verification_required": return "需验证"; case "payment_pending": return "待支付"; case "submitted": return "已提交"; case "scheduled": return "已排程"; case "waiting_price": return "等待行情"; default: return status.isEmpty() ? "待处理" : status; } }
    private String strategyMeta(JSONObject strategy) { return (strategy.optString("executionMode", "monitor").equals("live") ? "真实执行" : "仅监控") + " · 账号 " + first(strategy, "phone", "sourcePhone") + " · 行情 " + money(first(strategy, "latestFloorPrice", "floorPrice")) + "\n买入 " + money(first(strategy.optJSONObject("buy"), "maxPrice")) + " · 卖出 " + money(first(strategy.optJSONObject("sell"), "sellPrice")) + " · 更新 " + time(first(strategy, "updatedAt", "lastCheckAt")); }
    private JSONArray findArray(JSONObject object, String... keys) { if (object == null) return null; for (String key : keys) { JSONArray array = object.optJSONArray(key); if (array != null) return array; } return null; }
    private JSONArray arrayOrEmpty(JSONArray source) { return source == null ? new JSONArray() : source; }
    private String pretty(JSONObject object) { if (object == null) return "暂无数据"; StringBuilder output = new StringBuilder(); Iterator<String> keys = object.keys(); while (keys.hasNext()) { String key = keys.next(); Object value = object.opt(key); output.append(key).append("：").append(value).append('\n'); } return output.toString(); }
}
