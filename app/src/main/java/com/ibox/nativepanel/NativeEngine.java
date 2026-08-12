package com.ibox.nativepanel;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local panel business engine. It keeps panel state in the APK and calls only
 * official iBox endpoints through {@link IBoxDirectClient}.
 */
public final class NativeEngine {
    public static final String CAPTCHA_ID = "0d4b08eac1cbdcad36bbf607c5bf3e1b";

    private static final String PREFIX = "/native";
    private static final String DEFAULT_BARK_SERVER = "https://api.day.app";
    private static final String TIME_API_URL = "https://timeapi.io/api/time/current/zone?timeZone=UTC";
    private static final String SYNTHESIS_ACTIVITY_LIST_URL = "https://sail-api.ibox.art/synthesis-service/synthetic/activity/list";
    private static final String SYNTHESIS_ACTIVITY_DETAIL_URL = "https://sail-api.ibox.art/synthesis-service/synthetic/activity/detail";
    private static final String SYNTHESIS_CENTER_URL = "https://sail-api.ibox.art/synthesis-service/synthetic/center";
    private static final String SYNTHESIS_CONFIRM_URL = "https://sail-api.ibox.art/synthesis-service/synthetic/center/confirm";
    private static final String SYNTHESIS_SUBMIT_URL = "https://sail-api.ibox.art/synthesis-service/synthetic/center/submit";
    private static final String FIRST_SALE_LIST_URL = "https://sail-api.ibox.art/public-service/sale-infos";
    private static final String FIRST_SALE_DETAIL_URL = "https://sail-api.ibox.art/public-service/digital-collection-groups";
    private static final String FIRST_SALE_ORDER_URL = "https://sail-api.ibox.art/order-create-service/sales";
    private static final String PAYMENT_PLATFORMS_URL = "https://sail-api.ibox.art/payment-service/payment-platforms";
    private static final String PAYMENT_CASHIER_URL = "https://sail-api.ibox.art/payment-service/cashiers/gain";
    private static final String MARKET_PUBLIC_URL = "https://sail-api.ibox.art/public-market-service/digital-collection-groups";
    private static final String MARKET_GROUP_URL = "https://sail-api.ibox.art/public-service/digital-collection-groups";
    private static final String MARKET_TRADE_PUBLIC_CONFIG_URL = "https://sail-api.ibox.art/public-service-qt/config/public";
    private static final String OWNED_COLLECTION_DETAIL_URL = "https://sail-api.ibox.art/public-service/digital-collections";
    private static final String MARKET_CONSIGNMENT_ORDER_URL = "https://sail-api.ibox.art/order-create-service/consignment-orders";
    private static final String CONSIGNMENT_ORDER_URL = "https://sail-api.ibox.art/order-service/consign-orders";
    private static final String MARKET_ADVANCE_ORDER_URL = "https://sail-api.ibox.art/order-create-service/advance-orders";
    private static final String MARKET_PURCHASE_CONSIGNMENT_URL = "https://sail-api.ibox.art/order-create-service/purchase-consignment-orders";
    private static final String OWNED_GROUP_URL = "https://sail-api.ibox.art/personal-center-service/users/digital-collection-groups";
    private static final String LOTTERY_HISTORY_URL = "https://sail-api.ibox.art/activity-service/lottery-activitys/history";
    private static final String LOTTERY_ACTIVITY_URL = "https://sail-api.ibox.art/activity-service/lottery-activitys";
    private static final String ORDER_LIST_URL = "https://sail-api.ibox.art/order-service/orders";
    private static final String PURCHASE_CONSIGNMENT_ORDER_LIST_URL = "https://sail-api.ibox.art/order-service/purchase-consignment-orders";
    private static final String ADVANCE_ORDER_CANCEL_URL = "https://sail-api.ibox.art/order-service/advance-orders";
    private static final long CAPTCHA_TTL_MS = 2L * 60L * 1000L;
    private static final long WATCH_NOTIFY_WINDOW_MS = 5L * 60L * 1000L;
    private static final long MARKET_WATCH_MIN_INTERVAL_MS = 15L * 1000L;
    private static final long TASK_CLOCK_SYNC_MS = 30L * 1000L;
    private static final long TASK_PREPARE_MS = 2500L;
    private static final long TASK_MAX_LATE_MS = 60L * 1000L;
    private static final long IMMEDIATE_PURCHASE_STALE_MS = 60L * 1000L;
    private static final long ORDER_TASK_LINK_WINDOW_MS = 10L * 60L * 1000L;
    private static final long BARK_DEDUPE_WINDOW_MS = 5L * 60L * 1000L;
    private static final int LOTTERY_DRAWS_PER_REQUEST = 5;
    private static final int LOTTERY_MAX_DRAWS_PER_RUN = 100;
    private static final double QUANT_NET_PROCEEDS_RATE = 0.955d;
    private static final int QUANT_PRICE_HISTORY_LIMIT = 24;
    private static final double QUANT_MAX_VOLATILITY_PERCENT = 100d;
    private static final int QUANT_MAX_COOLDOWN_MINUTES = 24 * 60;
    private static final Object TICK_LOCK = new Object();
    private static final Object CLOCK_LOCK = new Object();
    private static volatile long serverClockOffsetMs;
    private static volatile long serverClockSyncedAt;

    private final NativeStore store;
    private final IBoxDirectClient client;
    private final Map<String, IBoxDirectClient.SmsSession> smsSessions = new HashMap<>();
    private final Map<String, TaskCaptchaSession> taskCaptchaSessions = new HashMap<>();
    private final Set<String> submittingSyntheses = Collections.synchronizedSet(new HashSet<>());
    private volatile long lastMarketWatchAt;
    private volatile long lastLotteryRefreshAt;

    public NativeEngine(Context context) {
        store = new NativeStore(context);
        client = new IBoxDirectClient(store.getOrCreateDeviceId());
    }

    public NativeStore store() {
        return store;
    }

    /**
     * Transitional local router used by the existing native screens. No call
     * leaves the device for a panel host: external requests use iBox directly.
     */
    public JSONObject request(String method, String rawPath, JSONObject body) throws Exception {
        Route route = Route.parse(rawPath);
        String verb = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (verb.isEmpty()) verb = "GET";
        cleanupSessions();

        if ("/accounts".equals(route.path) && "GET".equals(verb)) return ok(accountList());
        if ("/sms/session".equals(route.path) && "POST".equals(verb)) return createSmsSession(body);
        if (route.path.startsWith("/sms/captcha/") && "POST".equals(verb)) return sendSms(route.tail("/sms/captcha/"), body);
        if ("/login".equals(route.path) && "POST".equals(verb)) return login(body);

        if (route.path.startsWith("/accounts/") && route.path.endsWith("/remove") && "POST".equals(verb)) {
            String phone = route.segment(2);
            return removeAccount(phone);
        }
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/market-trade/assets") && "GET".equals(verb)) {
            return marketTradeAssets(route.segment(2), route.query("groupId"));
        }
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/assets") && "GET".equals(verb)) {
            String phone = route.segment(2);
            return refreshAssets(phone, integer(route.query("pageNo"), 1), integer(route.query("pageSize"), 50));
        }
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/performance") && "GET".equals(verb)) {
            return accountPerformance(route.segment(2));
        }
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/orders") && "GET".equals(verb)) {
            return loadOrders(route.segment(2));
        }
        if (route.path.startsWith("/accounts/") && route.path.contains("/market-trade/") && route.path.endsWith("/wanted-preflight") && "GET".equals(verb)) {
            return marketTradeWantedPreflight(route.segment(2), route.segment(4));
        }
        if (route.path.startsWith("/accounts/") && route.path.contains("/market-trade/") && route.path.endsWith("/preflight") && "GET".equals(verb)) {
            return marketTradePreflight(route.segment(2), route.segment(4));
        }
        if (route.path.startsWith("/accounts/") && route.path.contains("/market-trade/") && route.path.endsWith("/listings") && "GET".equals(verb)) {
            return marketTradeListings(route.segment(2), route.segment(4));
        }
        if (route.path.startsWith("/accounts/") && route.path.contains("/market-trade/") && "GET".equals(verb)) {
            return marketTradeDetail(route.segment(2), route.segment(4));
        }

        if ("/market".equals(route.path) && "GET".equals(verb)) return searchMarket(route.query("phone"), route.query("name"), integer(route.query("pageNo"), 1), integer(route.query("pageSize"), 20));
        if ("/market/watches".equals(route.path) && "GET".equals(verb)) {
            if ("1".equals(route.query("refresh"))) refreshMarketWatches(true);
            return ok(objectOf("watches", marketWatchList(), "intervalMs", MARKET_WATCH_MIN_INTERVAL_MS));
        }
        if ("/market/watches".equals(route.path) && "POST".equals(verb)) return addMarketWatch(body);
        if (route.path.startsWith("/market/watches/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelMarketWatch(route.segment(3));
        if (route.path.startsWith("/market/watches/") && route.path.endsWith("/thresholds") && "PUT".equals(verb)) return updateMarketWatch(route.segment(3), body);

        if ("/synthesis/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", store.getSynthesisTasks()));
        if ("/synthesis/tasks".equals(route.path) && "POST".equals(verb)) return createSynthesisTask(body);
        if (route.path.startsWith("/synthesis/tasks/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelStoredTask(store.getSynthesisTasks(), route.segment(3), "synthesis");
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/synthesis/activities") && "GET".equals(verb)) return synthesisActivities(route.segment(2));
        if (route.path.startsWith("/accounts/") && route.path.contains("/synthesis/") && route.path.endsWith("/confirm") && "POST".equals(verb)) {
            return confirmSynthesis(route.segment(2), route.segment(4), body);
        }
        if (route.path.startsWith("/accounts/") && route.path.contains("/synthesis/") && "GET".equals(verb)) return synthesisCenter(route.segment(2), route.segment(4));
        if (route.path.startsWith("/accounts/") && route.path.contains("/synthesis/") && route.path.endsWith("/submit") && "POST".equals(verb)) {
            return submitSynthesis(route.segment(2), route.segment(4), body);
        }

        if ("/first-sales".equals(route.path) && "GET".equals(verb)) return firstSales(route.query("phone"));
        if ("/first-sales/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", store.getFirstSaleTasks()));
        if ("/first-sales/tasks".equals(route.path) && "POST".equals(verb)) return createFirstSaleTask(body);
        if ("/first-sales/orders".equals(route.path) && "POST".equals(verb)) return submitFirstSale(body, null);
        if (route.path.startsWith("/first-sales/tasks/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelStoredTask(store.getFirstSaleTasks(), route.segment(3), "first_sale");
        if (route.path.startsWith("/first-sales/tasks/") && "DELETE".equals(verb)) return deleteStoredTask(store.getFirstSaleTasks(), route.segment(3), "first_sale");
        if (route.path.startsWith("/first-sales/tasks/") && route.path.contains("/captcha/")) return taskCaptcha("first_sale", route, verb, body);
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/first-sales/payment-platforms") && "GET".equals(verb)) return firstSalePaymentPlatforms(route.segment(2));

        if ("/lottery/auto".equals(route.path) && "GET".equals(verb)) return lotteryState();
        if (route.path.startsWith("/lottery/auto/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setLotteryEnabled(route.segment(3), true);
        if (route.path.startsWith("/lottery/auto/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setLotteryEnabled(route.segment(3), false);

        if ("/quant/strategies".equals(route.path) && "GET".equals(verb)) return ok(objectOf("strategies", quantStrategyList()));
        if ("/quant/strategies".equals(route.path) && "POST".equals(verb)) return createQuantStrategy(body);
        if (route.path.startsWith("/quant/strategies/") && route.path.endsWith("/events") && "GET".equals(verb)) return quantEvents(route.segment(3));
        if (route.path.startsWith("/quant/strategies/") && route.path.contains("/captcha/")) return taskCaptcha("quant", route, verb, body);
        if (route.path.startsWith("/quant/strategies/") && "PUT".equals(verb)) return updateQuantStrategy(route.segment(3), body);
        if (route.path.startsWith("/quant/strategies/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setQuantEnabled(route.segment(3), true);
        if (route.path.startsWith("/quant/strategies/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setQuantEnabled(route.segment(3), false);
        if (route.path.startsWith("/quant/strategies/") && "DELETE".equals(verb)) return deleteQuantStrategy(route.segment(3));

        if ("/market/trade/tasks".equals(route.path) && "GET".equals(verb)) return marketTradeTaskState();
        if ("/market/trade/tasks".equals(route.path) && "POST".equals(verb)) return createTradeTask(body, "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.contains("/captcha/")) return taskCaptcha("market_trade", route, verb, body);
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setTradeEnabled(route.segment(4), true, "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setTradeEnabled(route.segment(4), false, "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/preflight") && "POST".equals(verb)) return preflightTradeTask(route.segment(4), "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/payment") && "POST".equals(verb)) return tradePayment(route.segment(4), "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && "DELETE".equals(verb)) return deleteTradeTask(route.segment(4), "market_trade");

        if ("/retired-market/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", retiredTasks()));
        if ("/retired-market/tasks".equals(route.path) && "POST".equals(verb)) return createTradeTask(body, "retired_market");
        if ("/retired-market/purchases".equals(route.path) && "POST".equals(verb)) return createImmediatePurchase(body);
        if (route.path.startsWith("/retired-market/tasks/") && route.path.contains("/captcha/")) return taskCaptcha("retired_market", route, verb, body);
        if (route.path.startsWith("/retired-market/tasks/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setTradeEnabled(route.segment(3), true, "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setTradeEnabled(route.segment(3), false, "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && route.path.endsWith("/payment") && "POST".equals(verb)) return tradePayment(route.segment(3), "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && "DELETE".equals(verb)) return deleteTradeTask(route.segment(3), "retired_market");

        if (route.path.startsWith("/orders/") && route.path.endsWith("/payment") && "POST".equals(verb)) return platformOrderPayment(route.segment(2), body);
        if (route.path.startsWith("/orders/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelPendingOrder(route.segment(2), body);
        if ("/orders".equals(route.path) && "GET".equals(verb)) return loadOrders(route.query("phone"));
        if (route.path.startsWith("/consignment-orders/") && route.path.endsWith("/cancel") && "POST".equals(verb)) {
            return cancelConsignmentOrder(route.segment(2), body);
        }
        if ("/notifications/bark".equals(route.path) && "GET".equals(verb)) return ok(barkSummary());
        if ("/notifications/bark".equals(route.path) && "PUT".equals(verb)) return saveBark(body);
        if ("/notifications/bark/test".equals(route.path) && "POST".equals(verb)) return testBark();
        if ("/settings/trade-password".equals(route.path) && "GET".equals(verb)) return tradePasswordSummary();
        if ("/settings/trade-password".equals(route.path) && "PUT".equals(verb)) return saveTradePassword(body);

        throw new NativeException("原生功能路径不存在：" + route.path);
    }

    public void tick() {
        synchronized (TICK_LOCK) {
            // 抢购和合成的时间窗口优先于低频行情刷新，避免刷新请求占住执行线程。
            try {
                processSynthesisTasks();
                processFirstSaleTasks();
            } catch (Exception ignored) {
                // Task state carries the error for the corresponding card.
            }
            try {
                refreshMarketWatches(false);
            } catch (Exception ignored) {
                // Individual watch errors are persisted by refreshMarketWatches.
            }
            try {
                refreshLottery(false);
                processLotteryAutoDraw();
            } catch (Exception ignored) {
                // Network failures must not terminate the foreground engine.
            }
            try {
                processQuantStrategies();
                processTradeTasks();
            } catch (Exception ignored) {
                // Task state carries the error for the corresponding card.
            }
        }
    }

    public JSONObject backgroundSummary() {
        JSONObject result = new JSONObject();
        try {
            result.put("watchCount", store.getMarketWatches().length());
            result.put("quantCount", store.getQuantStrategies().length());
            result.put("tradeCount", store.getTradeTasks().length());
            result.put("firstSaleCount", store.getFirstSaleTasks().length());
        } catch (JSONException ignored) {
            // JSONObject never throws for primitive values on Android.
        }
        return result;
    }

    private JSONObject createSmsSession(JSONObject body) throws Exception {
        String phone = string(body, "phone");
        IBoxDirectClient.SmsSession session = client.createSmsSession(phone);
        String id = UUID.randomUUID().toString();
        synchronized (smsSessions) {
            smsSessions.put(id, session);
        }
        return ok(objectOf("sessionId", id, "captchaId", CAPTCHA_ID, "expiresAt", session.getExpiresAt()));
    }

    private JSONObject sendSms(String id, JSONObject body) throws Exception {
        IBoxDirectClient.SmsSession session;
        synchronized (smsSessions) {
            session = smsSessions.get(id);
        }
        if (session == null || session.isExpired()) throw new NativeException("短信会话已过期，请重新获取验证码");
        JSONObject captcha = body == null ? null : body.optJSONObject("captcha");
        String state = client.sendSms(session, IBoxDirectClient.CaptchaResult.fromJson(captcha));
        return ok(objectOf("sessionId", id, "deliveryState", state));
    }

    private JSONObject login(JSONObject body) throws Exception {
        String id = string(body, "sessionId");
        String phone = string(body, "phone");
        String code = string(body, "code");
        IBoxDirectClient.SmsSession session;
        synchronized (smsSessions) {
            session = smsSessions.get(id);
        }
        if (session == null || !phone.equals(session.getPhone())) throw new NativeException("短信会话无效，请重新获取验证码");
        IBoxDirectClient.Account account = client.login(session, code);
        if (!store.saveAccount(account.phone, account.token, account.shopToken, account.userId, "")) {
            throw new NativeException("本地账号配置保存失败");
        }
        synchronized (smsSessions) {
            smsSessions.remove(id);
        }
        return ok(objectOf("userId", account.userId));
    }

    private JSONObject removeAccount(String phone) throws Exception {
        if (!store.removeAccount(phone)) throw new NativeException("账号不存在或无法移除");
        removeTasksForPhone(phone);
        return ok(new JSONObject());
    }

    private JSONObject refreshAssets(String phone, int pageNo, int pageSize) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject stored = store.getAccount(phone);
        try {
            JSONObject assets = client.fetchAssets(account, Math.max(pageNo, 1), Math.max(pageSize, 1));
            attachActiveConsignmentDetails(account, assets);
            reconcileFinishedConsignmentTasks(phone, assets);
            if (stored != null) {
                JSONObject cache = objectOf("data", assets, "updatedAt", Instant.now().toString(), "stale", false);
                stored.put("assetCache", cache);
                store.upsertAccount(stored);
            }
            return ok(assets);
        } catch (Exception error) {
            if (isExpiredLogin(error)) {
                throw new NativeException("账号登录已失效，请重新登录后同步资产");
            }
            JSONObject cache = stored == null ? null : stored.optJSONObject("assetCache");
            JSONObject snapshot = cache == null ? null : cache.optJSONObject("data");
            if (snapshot == null) throw error;
            JSONObject fallback = copy(snapshot);
            fallback.put("cacheStale", true);
            fallback.put("cacheUpdatedAt", cache.optString("updatedAt", ""));
            JSONArray items = fallback.optJSONArray("items");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item != null) item.put("consignmentStateVerified", false);
                }
            }
            cache.put("stale", true);
            if (stored != null) {
                stored.put("assetCache", cache);
                store.upsertAccount(stored);
            }
            return ok(fallback);
        }
    }

    private static boolean isExpiredLogin(Exception error) {
        return error instanceof IBoxDirectClient.ApiException
                && "401".equals(((IBoxDirectClient.ApiException) error).businessCode);
    }

    private void attachActiveConsignmentDetails(IBoxDirectClient.Account account, JSONObject assets) throws Exception {
        JSONArray items = assets == null ? null : assets.optJSONArray("items");
        if (items == null) return;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            boolean consigning = item.optBoolean("consigning", false);
            item.put("activeListings", new JSONArray());
            item.put("consignmentStateVerified", !consigning);
            if (!consigning) continue;

            try {
                JSONArray source = activeConsignmentAssets(account, item.optString("groupId"));
                JSONArray activeListings = new JSONArray();
                boolean resolved = true;
                for (int sourceIndex = 0; sourceIndex < source.length(); sourceIndex++) {
                    JSONObject asset = source.optJSONObject(sourceIndex);
                    String assetId = ownedCollectionId(asset, item.optString("groupId"));
                    if (!assetId.matches("\\d+")) {
                        resolved = false;
                        continue;
                    }
                    JSONObject detailResponse = client.requestAuthenticated(
                            account,
                            "GET",
                            OWNED_COLLECTION_DETAIL_URL + "/" + encodePath(assetId),
                            null,
                            null,
                            false,
                            "寄售资产详情"
                    );
                    JSONObject detail = object(data(detailResponse));
                    String detailAssetId = first(detail, "id");
                    String detailGroupId = first(detail, "groupId");
                    String orderId = first(detail, "orderId");
                    int collectionStatus = integer(first(detail, "digitalCollectionStatus"), -1);
                    if (!assetId.equals(detailAssetId)
                            || !item.optString("groupId").equals(detailGroupId)
                            || collectionStatus != 2
                            || !orderId.matches("\\d+")) {
                        resolved = false;
                        continue;
                    }
                    activeListings.put(objectOf(
                            "assetId", assetId,
                            "tokenId", first(detail, "tokenId"),
                            "orderId", orderId
                    ));
                }
                if (resolved) {
                    item.put("activeListings", activeListings);
                    item.put("consigning", activeListings.length() > 0);
                    item.put("consignmentStateVerified", true);
                }
            } catch (Exception ignored) {
                // Do not expose a cancellation action without a current official asset-detail response.
            }
        }
    }

    private void reconcileFinishedConsignmentTasks(String phone, JSONObject assets) {
        JSONArray groups = assets == null ? null : assets.optJSONArray("items");
        if (groups == null) return;
        JSONArray tasks = store.getTradeTasks();
        JSONArray remaining = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            String status = task.optString("status");
            boolean finishedConsignment = "consignment".equals(task.optString("type"))
                    && phone.equals(task.optString("phone"))
                    && !task.optBoolean("enabled", false)
                    && ("submitted".equals(status) || "cancelled".equals(status) || "failed".equals(status));
            if (finishedConsignment && consignmentInstanceVerifiedInactive(groups, task.optString("groupId"), task.optString("digitalCollectionId"))) {
                removed = true;
                continue;
            }
            remaining.put(task);
        }
        if (removed) store.saveTradeTasks(remaining);
    }

    private static boolean consignmentInstanceVerifiedInactive(JSONArray groups, String groupId, String digitalCollectionId) {
        if (groupId.isEmpty() || digitalCollectionId.isEmpty()) return false;
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null || !groupId.equals(group.optString("groupId"))
                    || !group.optBoolean("consignmentStateVerified", false)) continue;
            JSONArray listings = group.optJSONArray("activeListings");
            if (listings != null) {
                for (int listingIndex = 0; listingIndex < listings.length(); listingIndex++) {
                    JSONObject listing = listings.optJSONObject(listingIndex);
                    if (listing != null && digitalCollectionId.equals(listing.optString("assetId"))) return false;
                }
            }
            return true;
        }
        return false;
    }

    private JSONObject loadOrders(String requestedPhone) throws Exception {
        if (requestedPhone == null || requestedPhone.trim().isEmpty()) {
            JSONArray storedAccounts = store.getAccounts();
            if (storedAccounts.length() == 0) throw new NativeException("请先使用短信登录添加 iBox 账号");
            JSONArray accounts = new JSONArray();
            JSONArray collectionPendingOrders = new JSONArray();
            JSONArray wantedPendingOrders = new JSONArray();
            JSONArray pendingOrders = new JSONArray();
            JSONArray sellOrders = new JSONArray();
            JSONArray buyOrders = new JSONArray();
            JSONArray failures = new JSONArray();
            for (int index = 0; index < storedAccounts.length(); index++) {
                JSONObject stored = storedAccounts.optJSONObject(index);
                String phone = stored == null ? "" : stored.optString("phone");
                if (phone.isEmpty()) continue;
                try {
                    JSONObject result = loadOrders(phone).optJSONObject("data");
                    if (result == null) continue;
                    accounts.put(result);
                    appendArray(collectionPendingOrders, result.optJSONArray("collectionPendingOrders"));
                    appendArray(wantedPendingOrders, result.optJSONArray("wantedPendingOrders"));
                    appendArray(pendingOrders, result.optJSONArray("pendingOrders"));
                    appendArray(sellOrders, result.optJSONArray("sellOrders"));
                    appendArray(buyOrders, result.optJSONArray("buyOrders"));
                } catch (Exception error) {
                    failures.put(objectOf("phone", phone, "message", message(error)));
                }
            }
            return ok(objectOf(
                    "accounts", accounts,
                    "collectionPendingOrders", collectionPendingOrders,
                    "collectionPendingCount", collectionPendingOrders.length(),
                    "wantedPendingOrders", wantedPendingOrders,
                    "wantedPendingCount", wantedPendingOrders.length(),
                    "pendingOrders", pendingOrders,
                    "sellOrders", sellOrders,
                    "buyOrders", buyOrders,
                    "orderReadFailures", failures,
                    "updatedAt", Instant.now().toString()
            ));
        }
        IBoxDirectClient.Account account = requestedPhone == null || requestedPhone.trim().isEmpty() ? firstAccount() : account(requestedPhone);
        OrderPage collectionOrders = OrderPage.empty();
        OrderPage consignmentOrders = OrderPage.empty();
        JSONArray failures = new JSONArray();
        boolean collectionOrdersLoaded = false;
        try {
            collectionOrders = loadOrderPage(
                    account,
                    ORDER_LIST_URL,
                    objectOf("pageNo", 1, "pageSize", 40, "productType", 0, "orderType", 0, "orderStatus", 0),
                    "collection_pending"
            );
            collectionOrdersLoaded = true;
        } catch (Exception error) {
            failures.put("collection_pending");
        }
        try {
            consignmentOrders = loadOrderPage(
                    account,
                    PURCHASE_CONSIGNMENT_ORDER_LIST_URL,
                    objectOf("pageNo", 1, "pageSize", 40, "initiatorType", 2),
                    "consignment"
            );
        } catch (Exception error) {
            failures.put("consignment");
        }

        JSONArray sellOrders = new JSONArray();
        JSONArray buyOrders = new JSONArray();
        JSONArray wantedPendingOrders = new JSONArray();
        for (int index = 0; index < consignmentOrders.items.length(); index++) {
            JSONObject order = consignmentOrders.items.optJSONObject(index);
            if (order == null) continue;
            int orderType = integer(order.opt("orderType"), -1);
            if (orderType == 2) sellOrders.put(order);
            if (orderType == 1) {
                order.put("type", "wanted_pending");
                buyOrders.put(order);
                if (integer(order.opt("orderStatus"), -1) == 0) wantedPendingOrders.put(order);
            }
        }
        List<JSONObject> pending = new ArrayList<>();
        appendOrders(pending, collectionOrders.items);
        appendOrders(pending, wantedPendingOrders);
        Collections.sort(pending, (left, right) -> Long.compare(
                epoch(right.optString("createdAt")),
                epoch(left.optString("createdAt"))
        ));
        JSONArray pendingOrders = new JSONArray();
        for (JSONObject order : pending) pendingOrders.put(order);
        if (collectionOrdersLoaded) reconcileImmediatePurchaseTasks(account.phone, collectionOrders);
        return ok(objectOf(
                "phone", account.phone,
                "collectionPendingOrders", collectionOrders.items,
                "collectionPendingCount", collectionOrders.count,
                "wantedPendingOrders", wantedPendingOrders,
                "wantedPendingCount", wantedPendingOrders.length(),
                "pendingOrders", pendingOrders,
                "sellOrders", sellOrders,
                "buyOrders", buyOrders,
                "orderReadFailures", failures,
                "updatedAt", Instant.now().toString()
        ));
    }

    private OrderPage loadOrderPage(IBoxDirectClient.Account account, String url, JSONObject query, String type) throws Exception {
        JSONObject response = client.requestAuthenticated(account, "GET", url, null, query, false, "订单");
        Object root = data(response);
        JSONArray source = firstArray(root, "orders", "list", "records", "items", "rows");
        JSONArray items = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject order = source.optJSONObject(index);
            if (order != null) items.put(platformOrderSummary(order, type, account.phone));
        }
        return new OrderPage(integer(first(object(root), "total", "totalCount", "count"), 0), items);
    }

    private JSONObject accountPerformance(String phone) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        int pageNo = 1;
        int loaded = 0;
        int total = Integer.MAX_VALUE;
        int completed = 0;
        int costCovered = 0;
        double realizedRevenue = 0d;
        double realizedCost = 0d;
        double realizedProfit = 0d;
        while (pageNo <= 50 && loaded < total) {
            OrderPage page = loadOrderPage(
                    account,
                    PURCHASE_CONSIGNMENT_ORDER_LIST_URL,
                    objectOf("pageNo", pageNo, "pageSize", 100, "initiatorType", 2),
                    "consignment"
            );
            if (page.count > 0) total = page.count;
            if (page.items.length() == 0) break;
            loaded += page.items.length();
            for (int index = 0; index < page.items.length(); index++) {
                JSONObject order = page.items.optJSONObject(index);
                if (order == null || integer(order.opt("orderType"), -1) != 2
                        || integer(order.opt("orderStatus"), -1) != 7) continue;
                completed++;
                double price = decimal(order.opt("price"), Double.NaN);
                int quantity = Math.max(1, integer(order.opt("quantity"), 1));
                double cost = store.getAssetCost(phone, order.optString("groupId"));
                if (!Double.isFinite(price) || price <= 0d || !Double.isFinite(cost)) continue;
                double revenue = price * quantity * QUANT_NET_PROCEEDS_RATE;
                double costTotal = cost * quantity;
                realizedRevenue += revenue;
                realizedCost += costTotal;
                realizedProfit += revenue - costTotal;
                costCovered++;
            }
            if (page.items.length() < 100 && total == Integer.MAX_VALUE) total = loaded;
            pageNo++;
        }
        return ok(objectOf(
                "phone", phone,
                "realizedProfit", realizedProfit,
                "realizedRevenue", realizedRevenue,
                "realizedCost", realizedCost,
                "completedSales", completed,
                "costCoveredSales", costCovered,
                "historyLoaded", loaded,
                "historyTotal", total == Integer.MAX_VALUE ? loaded : total,
                "historyComplete", total == Integer.MAX_VALUE || loaded >= total,
                "feeRate", 1d - QUANT_NET_PROCEEDS_RATE,
                "updatedAt", Instant.now().toString()
        ));
    }

    private static JSONObject platformOrderSummary(JSONObject order, String type, String phone) throws JSONException {
        JSONObject preview = order.optJSONObject("productPreview");
        JSONObject collection = order.optJSONObject("digitalCollection");
        JSONObject result = new JSONObject();
        result.put("id", first(order, "id", "orderId", "orderNumber"));
        result.put("orderUuid", deepString(order, "orderUuid", "orderUUId", "orderUUID", "orderId", "orderNumber", "uuid"));
        result.put("orderId", deepString(order, "orderId"));
        result.put("orderNumber", deepString(order, "orderNumber"));
        result.put("listingOrderItemId", deepString(order, "listingOrderItemId"));
        result.put("type", type);
        String groupId = first(order, "groupId", "digitalCollectionGroupId", "collectionGroupId");
        if (groupId.isEmpty()) groupId = first(preview, "groupId", "digitalCollectionGroupId", "collectionGroupId");
        if (groupId.isEmpty()) groupId = first(collection, "groupId", "digitalCollectionGroupId", "collectionGroupId");
        result.put("groupId", groupId);
        String title = first(order, "name", "title", "productName", "digitalCollectionName");
        if (title.isEmpty()) title = assetName(preview);
        if (title.isEmpty()) title = assetName(collection);
        result.put("title", title.isEmpty() ? "未命名藏品" : title);
        String tokenId = first(order, "tokenId");
        if (tokenId.isEmpty()) tokenId = first(preview, "tokenId");
        if (tokenId.isEmpty()) tokenId = first(collection, "tokenId");
        result.put("tokenId", tokenId);
        String cover = assetCover(preview);
        if (cover.isEmpty()) cover = assetCover(collection);
        if (cover.isEmpty()) cover = assetCover(order);
        result.put("cover", cover);
        result.put("price", numericOrNull(first(order, "price", "salePrice", "totalPrice", "amount")));
        result.put("quantity", Math.max(1, integer(first(order, "quantity", "buyCount", "count"), 1)));
        putNullable(result, "orderStatus", firstNullable(order, "orderStatus", "status", "orderState"));
        putNullable(result, "orderType", firstNullable(order, "orderType"));
        putNullable(result, "initiatorType", firstNullable(order, "initiatorType"));
        result.put("createdAt", first(order, "createdAt", "createTime"));
        result.put("updatedAt", first(order, "updatedAt", "updateTime"));
        result.put("phone", phone);
        result.put("source", "platform_order_service");
        return result;
    }

    private static void appendOrders(List<JSONObject> target, JSONArray source) {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) target.add(item);
        }
    }

    private static void appendArray(JSONArray target, JSONArray source) {
        if (target == null || source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) target.put(item);
        }
    }

    private static String assetName(JSONObject source) {
        if (source == null) return "";
        String direct = first(source, "name", "title", "digitalCollectionName", "collectionName");
        return direct.isEmpty() ? first(source.optJSONObject("digitalCollection"), "name", "title") : direct;
    }

    private static String assetCover(JSONObject source) {
        if (source == null) return "";
        String direct = first(source, "coverPicUrl", "coverUrl", "headerPicUrl");
        return direct.isEmpty() ? first(source.optJSONObject("digitalCollection"), "coverPicUrl", "coverUrl", "headerPicUrl") : direct;
    }

    private static Object firstNullable(JSONObject source, String... keys) {
        if (source == null) return JSONObject.NULL;
        for (String key : keys) {
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return JSONObject.NULL;
    }

    private static void putNullable(JSONObject target, String key, Object value) throws JSONException {
        target.put(key, value == null ? JSONObject.NULL : value);
    }

    private static final class OrderPage {
        final int count;
        final JSONArray items;

        OrderPage(int count, JSONArray items) {
            this.count = Math.max(0, count);
            this.items = items == null ? new JSONArray() : items;
        }

        static OrderPage empty() {
            return new OrderPage(0, new JSONArray());
        }
    }

    private JSONObject searchMarket(String phone, String name, int pageNo, int pageSize) throws Exception {
        String query = name == null ? "" : name.trim();
        if (query.matches("\\d+")) {
            IBoxDirectClient.Account account = phone == null || phone.trim().isEmpty() ? firstAccount() : account(phone);
            JSONObject detail = loadMarketTradeDetail(account, query);
            JSONObject lowest = lowestListing(marketListings(account, query));
            JSONObject item = objectOf(
                    "id", query,
                    "groupId", query,
                    "name", first(detail, "name", "title", "groupName", "digitalCollectionName"),
                    "cover", first(detail, "cover", "coverPicUrl", "coverUrl"),
                    "floorPrice", lowest == null ? numericOrNull(first(detail, "floorPrice", "price")) : lowest.opt("price")
            );
            return ok(objectOf("items", new JSONArray().put(item), "total", 1, "pageNo", 1, "pageSize", 1));
        }
        return ok(searchMarketsWithFallback(phone, name, Math.max(pageNo, 1), clamp(pageSize, 1, 100)));
    }

    private JSONObject addMarketWatch(JSONObject body) throws Exception {
        JSONObject input = body == null ? new JSONObject() : body;
        String requestedId = first(input, "collectionId", "groupId", "id");
        String searchTerm = first(input, "searchTerm", "name", "title");
        String requestedName = first(input, "name", "title", "searchTerm");
        if (requestedId.isEmpty() || searchTerm.isEmpty()) throw new NativeException("行情监控缺少藏品编号或名称");
        JSONObject market = searchMarketsWithFallback(null, searchTerm, 1, 20);
        JSONObject item = matchMarketItem(market.optJSONArray("items"), requestedId, requestedName);
        if (item == null || item.isNull("floorPrice")) throw new NativeException("该藏品当前没有可用价格，暂不能监控");
        String collectionId = first(item, "id", "collectionId");
        double price = decimal(item.opt("floorPrice"), Double.NaN);
        if (collectionId.isEmpty() || !Double.isFinite(price) || price < 0d) {
            throw new NativeException("该藏品当前没有可用价格，暂不能监控");
        }
        String name = first(item, "name", "title");
        JSONArray watches = store.getMarketWatches();
        for (int index = 0; index < watches.length(); index++) {
            JSONObject existing = watches.optJSONObject(index);
            if (existing != null && collectionId.equals(existing.optString("collectionId")) && "active".equals(existing.optString("status", "active"))) {
                return ok(marketWatchSummary(existing));
            }
        }
        JSONObject watch = new JSONObject();
        String now = Instant.now().toString();
        watch.put("id", UUID.randomUUID().toString());
        watch.put("collectionId", collectionId);
        watch.put("name", name);
        watch.put("searchTerm", searchTerm);
        watch.put("cover", first(item, "cover", "coverUrl"));
        putNumberOrNull(watch, "initialPrice", price);
        putNumberOrNull(watch, "latestPrice", price);
        putNumberOrNull(watch, "lowestPrice", price);
        putNumberOrNull(watch, "highestPrice", price);
        watch.put("riseThresholdPercent", threshold(input.opt("riseThresholdPercent"), 3d));
        watch.put("fallThresholdPercent", threshold(input.opt("fallThresholdPercent"), 3d));
        watch.put("status", "active");
        watch.put("createdAt", now);
        watch.put("updatedAt", now);
        watch.put("lastObservedAt", now);
        if (Double.isFinite(price) && price > 0d) watch.put("lastNotifiedPrice", price);
        watches.put(watch);
        if (!store.saveMarketWatches(watches)) throw new NativeException("行情监控保存失败");
        return ok(marketWatchSummary(watch));
    }

    private JSONObject cancelMarketWatch(String id) throws Exception {
        JSONArray watches = store.getMarketWatches();
        JSONObject changed = null;
        for (int index = 0; index < watches.length(); index++) {
            JSONObject watch = watches.optJSONObject(index);
            if (watch == null || !id.equals(watch.optString("id"))) continue;
            watch.put("status", "cancelled");
            watch.put("updatedAt", Instant.now().toString());
            changed = watch;
            break;
        }
        if (changed == null) throw new NativeException("行情监控不存在");
        store.saveMarketWatches(watches);
        return ok(marketWatchSummary(changed));
    }

    private JSONObject updateMarketWatch(String id, JSONObject body) throws Exception {
        JSONArray watches = store.getMarketWatches();
        JSONObject changed = null;
        for (int index = 0; index < watches.length(); index++) {
            JSONObject watch = watches.optJSONObject(index);
            if (watch == null || !id.equals(watch.optString("id")) || !"active".equals(watch.optString("status", "active"))) continue;
            watch.put("riseThresholdPercent", threshold(body == null ? null : body.opt("riseThresholdPercent"), watch.optDouble("riseThresholdPercent", 3d)));
            watch.put("fallThresholdPercent", threshold(body == null ? null : body.opt("fallThresholdPercent"), watch.optDouble("fallThresholdPercent", 3d)));
            double latest = decimal(watch.opt("latestPrice"), Double.NaN);
            if (Double.isFinite(latest) && latest > 0d) watch.put("lastNotifiedPrice", latest);
            watch.put("lastNotifiedAt", "");
            watch.put("updatedAt", Instant.now().toString());
            changed = watch;
            break;
        }
        if (changed == null) throw new NativeException("行情监控不存在");
        store.saveMarketWatches(watches);
        return ok(marketWatchSummary(changed));
    }

    private JSONObject marketWatchSummary(JSONObject source) throws JSONException {
        JSONObject watch = copy(source);
        double initial = decimal(watch.opt("initialPrice"), Double.NaN);
        double latest = decimal(watch.opt("latestPrice"), Double.NaN);
        double change = Double.isFinite(initial) && Double.isFinite(latest) ? latest - initial : Double.NaN;
        putNumberOrNull(watch, "initialPrice", initial);
        putNumberOrNull(watch, "latestPrice", latest);
        putNumberOrNull(watch, "lowestPrice", decimal(watch.opt("lowestPrice"), Double.NaN));
        putNumberOrNull(watch, "highestPrice", decimal(watch.opt("highestPrice"), Double.NaN));
        putNumberOrNull(watch, "change", change);
        putNumberOrNull(watch, "changePercent", Double.isFinite(change) && initial > 0d ? Math.round(change / initial * 10000d) / 100d : Double.NaN);
        watch.put("updatedAt", watch.optString("updatedAt", ""));
        watch.put("lastObservedAt", watch.optString("lastObservedAt", ""));
        watch.put("lastError", watch.optString("lastError", ""));
        return watch;
    }

    private JSONArray marketWatchList() throws JSONException {
        JSONArray watches = store.getMarketWatches();
        JSONArray result = new JSONArray();
        for (int index = 0; index < watches.length(); index++) {
            JSONObject watch = watches.optJSONObject(index);
            if (watch != null && "active".equals(watch.optString("status", "active"))) result.put(marketWatchSummary(watch));
        }
        return result;
    }

    private JSONObject searchMarketsWithFallback(String preferredPhone, String name, int pageNo, int pageSize) throws Exception {
        List<String> phones = new ArrayList<>();
        String preferred = preferredPhone == null || preferredPhone.trim().isEmpty() ? store.getSelectedPhone() : preferredPhone.trim();
        if (!preferred.isEmpty()) phones.add(preferred);
        JSONArray accounts = store.getAccounts();
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject stored = accounts.optJSONObject(index);
            String phone = stored == null ? "" : stored.optString("phone");
            if (!phone.isEmpty() && !phones.contains(phone)) phones.add(phone);
        }
        Exception failure = null;
        for (String phone : phones) {
            try {
                return client.searchMarkets(account(phone), name, pageNo, pageSize);
            } catch (Exception error) {
                failure = error;
            }
        }
        if (failure != null) throw failure;
        throw new NativeException("请先使用短信登录添加 iBox 账号");
    }

    private JSONObject synthesisActivities(String phone) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject response = client.requestAuthenticated(account, "GET", SYNTHESIS_ACTIVITY_LIST_URL, null, null, false, "合成活动");
        JSONArray source = firstArray(data(response), "activities", "list", "records", "items", "rows");
        long now = taskNow();
        List<JSONObject> normalized = new ArrayList<>();
        for (int index = 0; index < source.length(); index++) {
            JSONObject activity = source.optJSONObject(index);
            if (activity == null) continue;
            JSONArray channels = firstArray(activity, "channels", "syntheticChannels", "syntheticActivityList", "synthetics");
            if (channels.length() == 0) {
                String activityId = first(activity, "id", "activityId", "syntheticActivityId");
                if (!activityId.isEmpty()) {
                    try {
                        JSONObject detailResponse = client.requestAuthenticated(
                                account,
                                "GET",
                                SYNTHESIS_ACTIVITY_DETAIL_URL,
                                null,
                                objectOf("id", integer(activityId, 0)),
                                false,
                                "合成活动详情"
                        );
                        channels = firstArray(data(detailResponse), "channels", "syntheticChannels", "syntheticActivityList", "synthetics");
                    } catch (Exception ignored) {
                        channels = new JSONArray();
                    }
                }
                if (channels.length() == 0) {
                    String directId = first(activity, "syntheticActivityId", "syntheticId");
                    if (!directId.isEmpty()) normalized.add(synthesisActivity(activity, activity, directId, now));
                    continue;
                }
            }
            for (int channelIndex = 0; channelIndex < channels.length(); channelIndex++) {
                JSONObject channel = channels.optJSONObject(channelIndex);
                if (channel == null) continue;
                String syntheticId = first(channel, "syntheticActivityId", "syntheticId", "id");
                if (!syntheticId.isEmpty()) normalized.add(synthesisActivity(activity, channel, syntheticId, now));
            }
        }
        List<JSONObject> visible = new ArrayList<>();
        for (JSONObject activity : normalized) {
            String phase = activity.optString("phase", "expired");
            if ("available".equals(phase) || "preparing".equals(phase)) visible.add(activity);
        }
        String mode = "current";
        if (!visible.isEmpty()) {
            Collections.sort(visible, (left, right) -> {
                String leftPhase = left.optString("phase", "expired");
                String rightPhase = right.optString("phase", "expired");
                if (!leftPhase.equals(rightPhase)) return "available".equals(leftPhase) ? -1 : 1;
                long leftTime = synthesisSortTime(left, leftPhase);
                long rightTime = synthesisSortTime(right, rightPhase);
                return Long.compare(leftTime, rightTime);
            });
        } else {
            JSONObject latestExpired = null;
            long latestAt = Long.MIN_VALUE;
            for (JSONObject activity : normalized) {
                if (!"expired".equals(activity.optString("phase", ""))) continue;
                long endedAt = synthesisExpiredTime(activity);
                if (latestExpired == null || endedAt > latestAt) {
                    latestAt = endedAt;
                    latestExpired = activity;
                }
            }
            visible.clear();
            if (latestExpired != null) visible.add(latestExpired);
            mode = "recent_expired";
        }
        JSONArray activities = new JSONArray();
        for (JSONObject activity : visible) activities.put(activity);
        return ok(objectOf("mode", mode, "activities", activities, "updatedAt", Instant.now().toString()));
    }

    private JSONObject synthesisCenter(String phone, String syntheticId) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject response = client.requestAuthenticated(account, "GET", SYNTHESIS_CENTER_URL + "/" + encodePath(syntheticId), null, null, false, "合成材料");
        JSONObject summary = synthesisCenterSummary(data(response), syntheticId);
        return ok(summary);
    }

    private JSONObject submitSynthesis(String phone, String syntheticId, JSONObject body) throws Exception {
        int count = Math.max(1, body == null ? 1 : body.optInt("syntheticNum", 1));
        String key = phone + ":" + syntheticId;
        if (!submittingSyntheses.add(key)) throw new NativeException("该合成正在提交中");
        try {
            return submitPreparedSynthesis(phone, prepareSynthesis(phone, syntheticId, count));
        } finally {
            submittingSyntheses.remove(key);
        }
    }

    private JSONObject confirmSynthesis(String phone, String syntheticId, JSONObject body) throws Exception {
        int count = Math.max(1, body == null ? 1 : body.optInt("syntheticNum", 1));
        IBoxDirectClient.Account account = account(phone);
        if (account.userId.isEmpty()) throw new NativeException("合成需要账号用户标识，请重新登录");
        JSONObject request = prepareSynthesis(phone, syntheticId, count);
        JSONObject confirmQuery = objectOf("uid", account.userId);
        JSONObject confirm = client.requestAuthenticated(account, "POST", SYNTHESIS_CONFIRM_URL, request, confirmQuery, true, "合成预检");
        return ok(objectOf("confirm", data(confirm), "request", request));
    }

    private JSONObject prepareSynthesis(String phone, String syntheticId, int count) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        if (account.userId.isEmpty()) throw new NativeException("合成需要账号用户标识，请重新登录");
        JSONObject centerResponse = client.requestAuthenticated(account, "GET", SYNTHESIS_CENTER_URL + "/" + encodePath(syntheticId), null, null, false, "合成材料");
        return synthesisRequest(data(centerResponse), syntheticId, count);
    }

    private JSONObject submitPreparedSynthesis(String phone, JSONObject request) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        if (account.userId.isEmpty()) throw new NativeException("合成需要账号用户标识，请重新登录");
        JSONObject confirmQuery = objectOf("uid", account.userId);
        JSONObject confirm = client.requestAuthenticated(account, "POST", SYNTHESIS_CONFIRM_URL, request, confirmQuery, true, "合成预检");
        JSONObject submit = client.requestAuthenticated(account, "POST", SYNTHESIS_SUBMIT_URL, request, null, true, "提交合成");
        return ok(objectOf("confirm", data(confirm), "submit", data(submit), "request", request));
    }

    private JSONObject createSynthesisTask(JSONObject body) throws Exception {
        JSONObject task = copy(body);
        JSONArray phones = taskPhones(task);
        String phone = first(task, "sourcePhone", "phone");
        if (phone.isEmpty() && phones.length() > 0) phone = phones.optString(0);
        String syntheticId = first(task, "syntheticId", "id");
        if (phones.length() == 0 || phone.isEmpty() || syntheticId.isEmpty()) throw new NativeException("合成任务缺少账号或合成编号");
        if (!contains(phones, phone)) throw new NativeException("合成任务来源账号未包含在执行账号中");
        for (int index = 0; index < phones.length(); index++) account(phones.optString(index));
        JSONObject activityData = synthesisActivities(phone).optJSONObject("data");
        JSONArray activities = firstArray(activityData, "activities", "items", "list");
        JSONObject matched = null;
        for (int index = 0; index < activities.length(); index++) {
            JSONObject candidate = activities.optJSONObject(index);
            if (candidate != null && syntheticId.equals(first(candidate, "syntheticId", "id"))) {
                matched = candidate;
                break;
            }
        }
        if (matched == null || !"preparing".equals(matched.optString("phase"))) {
            throw new NativeException("该活动暂不能加入定时任务");
        }
        long officialStartAt = epoch(first(matched, "startAt", "startTime", "beginTime"));
        if (officialStartAt <= taskNow()) throw new NativeException("合成活动开始时间已过");
        task.put("id", UUID.randomUUID().toString());
        task.put("phone", phone);
        task.put("sourcePhone", phone);
        task.put("phones", phones);
        task.put("syntheticId", syntheticId);
        task.put("title", first(matched, "title", "name", "activityName", syntheticId));
        task.put("syntheticNum", Math.max(1, task.optInt("syntheticNum", 1)));
        task.put("status", "scheduled");
        task.put("enabled", true);
        task.put("runs", new JSONObject());
        task.put("createdAt", Instant.now().toString());
        task.put("updatedAt", Instant.now().toString());
        task.put("startAt", Instant.ofEpochMilli(officialStartAt).toString());
        long scheduledAt = officialStartAt;
        JSONArray tasks = store.getSynthesisTasks();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject existing = tasks.optJSONObject(index);
            if (existing == null || !"scheduled".equals(existing.optString("status"))) continue;
            if (!syntheticId.equals(existing.optString("syntheticId"))
                    || scheduledAt != epoch(existing.optString("startAt"))
                    || task.optInt("syntheticNum", 1) != existing.optInt("syntheticNum", 1)) continue;
            JSONArray mergedPhones = executionPhones(existing);
            for (int phoneIndex = 0; phoneIndex < phones.length(); phoneIndex++) {
                addTaskPhone(mergedPhones, phones.optString(phoneIndex));
            }
            existing.put("phones", mergedPhones);
            existing.put("updatedAt", Instant.now().toString());
            if (!store.saveSynthesisTasks(tasks)) throw new NativeException("合成任务保存失败");
            return ok(existing);
        }
        tasks.put(task);
        if (!store.saveSynthesisTasks(tasks)) throw new NativeException("合成任务保存失败");
        if (scheduledAt <= taskNow()) {
            processSynthesisTasks();
            JSONObject executed = findById(store.getSynthesisTasks(), task.optString("id"));
            return ok(executed == null ? task : executed);
        }
        return ok(task);
    }

    private JSONObject firstSales(String phone) throws Exception {
        IBoxDirectClient.Account account = phone == null || phone.trim().isEmpty() ? firstAccount() : account(phone);
        JSONObject query = objectOf("pageNo", 1, "pageSize", 40, "sortField", 0, "sortType", 1);
        JSONObject response = client.requestAuthenticated(account, "GET", FIRST_SALE_LIST_URL, null, query, false, "首发列表");
        JSONArray source = firstArray(data(response), "saleInfos", "list", "records", "items", "rows");
        long now = taskNow();
        List<JSONObject> normalized = new ArrayList<>();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) normalized.add(firstSaleSummary(item, now));
        }
        List<JSONObject> selected = new ArrayList<>();
        for (JSONObject item : normalized) {
            int status = integer(item.opt("saleStatus"), Integer.MIN_VALUE);
            if (status == 0 || status == 1 || status == 5) selected.add(item);
        }
        String mode = "current";
        if (selected.isEmpty()) {
            JSONObject first = normalized.isEmpty() ? null : normalized.get(0);
            if (first != null && ("ended".equals(first.optString("phase")) || "sold_out".equals(first.optString("phase")))) {
                selected.add(expiredFirstSale(first));
            } else {
                JSONObject latest = null;
                long latestAt = Long.MIN_VALUE;
                for (JSONObject item : normalized) {
                    String phase = item.optString("phase", "");
                    if (!"ended".equals(phase) && !"sold_out".equals(phase)) continue;
                    long recentAt = firstSaleRecentTime(item);
                    if (recentAt > 0L && recentAt <= now && recentAt > latestAt) {
                        latestAt = recentAt;
                        latest = item;
                    }
                }
                if (latest != null) selected.add(expiredFirstSale(latest));
            }
            mode = "recent_expired";
        }
        JSONArray items = new JSONArray();
        for (JSONObject item : selected) items.put(item);
        return ok(objectOf("mode", mode, "items", items, "updatedAt", Instant.now().toString()));
    }

    private JSONObject firstSalePaymentPlatforms(String phone) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject response = client.requestAuthenticated(account, "GET", PAYMENT_PLATFORMS_URL, null, objectOf("placeOrderMethod", 0), false, "首发支付通道");
        JSONArray source = firstArray(data(response), "paymentPlatforms", "platforms", "paymentPlatformList", "paymentMethods", "list", "records", "items", "rows");
        JSONArray items = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry == null) continue;
            JSONObject platform = new JSONObject();
            platform.put("code", first(entry, "paymentPlatformCode", "platformCode", "code"));
            platform.put("name", first(entry, "platformName", "paymentPlatformName", "name", "paymentType", "platformType"));
            Object activationValue = paymentPlatformActivationValue(entry);
            platform.put("activationStatus", activationValue instanceof Boolean ? (bool(activationValue) ? 1 : 0) : integer(activationValue, 0));
            platform.put("enabled", entry.optBoolean("enabled", entry.optBoolean("isEnabled", true)));
            platform.put("available", entry.optBoolean("available", entry.optBoolean("isAvailable", true)));
            items.put(platform);
        }
        return ok(objectOf("items", items));
    }

    private JSONObject firstSaleDetail(IBoxDirectClient.Account account, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("首发藏品编号无效");
        JSONObject response = client.requestAuthenticated(account, "GET", FIRST_SALE_DETAIL_URL + "/" + encodePath(groupId) + "/sale-info", null, null, false, "首发详情");
        return firstSaleSummary(object(data(response)), taskNow());
    }

    private JSONObject prepareFirstSaleTaskAccount(JSONObject task, String phone, String requiredAction) throws Exception {
        return prepareFirstSaleTaskAccount(task, phone, new String[]{requiredAction});
    }

    private JSONObject prepareFirstSaleTaskAccount(JSONObject task, String phone, String[] allowedActions) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject detail = firstSaleDetail(account, task.optString("groupId"));
        String saleId = detail.optString("saleId");
        if (saleId.isEmpty()) throw new NativeException("首发详情未返回发售编号");
        String expectedSaleId = task.optString("saleId");
        if (!expectedSaleId.isEmpty() && !expectedSaleId.equals(saleId)) throw new NativeException("首发项目已变更，请重新创建任务");
        String action = detail.optString("action");
        boolean allowed = false;
        if (allowedActions != null) {
            for (String allowedAction : allowedActions) {
                if (allowedAction != null && allowedAction.equals(action)) {
                    allowed = true;
                    break;
                }
            }
        }
        if (!allowed) {
            boolean scheduledAllowed = false;
            if (allowedActions != null) {
                for (String allowedAction : allowedActions) {
                    if ("scheduled".equals(allowedAction)) {
                        scheduledAllowed = true;
                        break;
                    }
                }
            }
            throw new NativeException(scheduledAllowed ? "首发项目当前不在准备阶段" : "首发项目当前不可购买");
        }
        int limit = detail.optInt("userOnceMaxBuyNum", 0);
        if (limit <= 0) throw new NativeException("首发项目购买额度无效");
        if (Math.max(1, task.optInt("num", 1)) > limit) throw new NativeException("购买数量超过单次限制");
        int paymentCode = paymentPlatformCode(account, 0, task.optInt("paymentPlatformCode", 0));
        return objectOf("saleId", saleId, "paymentPlatformCode", paymentCode, "detail", detail);
    }

    private JSONObject createFirstSaleTask(JSONObject body) throws Exception {
        JSONObject task = copy(body);
        JSONArray phones = taskPhones(task);
        String phone = first(task, "sourcePhone", "phone");
        if (phone.isEmpty() && phones.length() > 0) phone = phones.optString(0);
        String saleId = first(task, "saleId", "id");
        String groupId = first(task, "groupId", "collectionId");
        if (phones.length() == 0 || phone.isEmpty() || saleId.isEmpty() || !groupId.matches("\\d+")) throw new NativeException("首发任务缺少账号或发售编号");
        if (!contains(phones, phone)) throw new NativeException("首发任务来源账号未包含在执行账号中");
        for (int index = 0; index < phones.length(); index++) account(phones.optString(index));
        boolean immediate = "immediate".equals(task.optString("mode"));
        task.put("groupId", groupId);
        task.put("saleId", saleId);
        task.put("num", Math.max(1, task.optInt("num", 1)));
        task.put("paymentPlatformCode", Math.max(0, task.optInt("paymentPlatformCode", 0)));
        JSONObject sourcePlan = prepareFirstSaleTaskAccount(task, phone, immediate ? "immediate" : "scheduled");
        for (int index = 0; index < phones.length(); index++) {
            String executionPhone = phones.optString(index);
            if (phone.equals(executionPhone)) continue;
            prepareFirstSaleTaskAccount(task, executionPhone, immediate ? "immediate" : "scheduled");
        }
        JSONObject detail = sourcePlan.getJSONObject("detail");
        saleId = sourcePlan.optString("saleId");
        long scheduledAt = immediate ? taskNow() : epoch(detail.optString("startAt"));
        if (scheduledAt <= 0L) throw new NativeException("首发任务需要有效开始时间");
        if (!immediate && taskNow() >= scheduledAt) throw new NativeException("首发开始时间已过，请刷新项目后重试");
        task.put("id", UUID.randomUUID().toString());
        task.put("phone", phone);
        task.put("sourcePhone", phone);
        task.put("phones", phones);
        task.put("saleId", saleId);
        task.put("title", first(task, "title", "name", "groupName", "saleId"));
        if (task.optString("title").isEmpty()) task.put("title", detail.optString("title", "首发 " + saleId));
        task.put("cover", first(task, "cover", "image", "coverUrl"));
        if (task.optString("cover").isEmpty()) task.put("cover", detail.optString("cover"));
        task.put("price", detail.opt("price"));
        task.put("mode", immediate ? "immediate" : "scheduled");
        task.put("enabled", true);
        task.put("status", "scheduled");
        task.put("runs", new JSONObject());
        task.put("createdAt", Instant.now().toString());
        task.put("updatedAt", Instant.now().toString());
        task.put("startAt", Instant.ofEpochMilli(scheduledAt).toString());
        task.put("startTime", detail.optString("startTime"));
        task.put("eligibility", firstSaleEligibility(detail));
        JSONArray tasks = store.getFirstSaleTasks();
        if (!immediate) {
            for (int index = 0; index < tasks.length(); index++) {
                JSONObject existing = tasks.optJSONObject(index);
                if (existing == null || !"scheduled".equals(existing.optString("status"))) continue;
                if (!saleId.equals(existing.optString("saleId"))
                        || scheduledAt != epoch(existing.optString("startAt"))
                        || task.optInt("num", 1) != existing.optInt("num", 1)) continue;
                JSONArray mergedPhones = executionPhones(existing);
                for (int phoneIndex = 0; phoneIndex < phones.length(); phoneIndex++) {
                    addTaskPhone(mergedPhones, phones.optString(phoneIndex));
                }
                existing.put("phones", mergedPhones);
                existing.put("sourcePhone", phone);
                existing.put("paymentPlatformCode", task.optInt("paymentPlatformCode", 0));
                existing.put("updatedAt", Instant.now().toString());
                if (!store.saveFirstSaleTasks(tasks)) throw new NativeException("首发任务保存失败");
                return ok(existing);
            }
        }
        tasks.put(task);
        if (!store.saveFirstSaleTasks(tasks)) throw new NativeException("首发任务保存失败");
        if (immediate) {
            processFirstSaleTasks();
            JSONObject executed = findById(store.getFirstSaleTasks(), task.optString("id"));
            return ok(executed == null ? task : executed);
        }
        return ok(task);
    }

    private JSONObject submitFirstSale(JSONObject body, JSONObject captcha) throws Exception {
        JSONObject input = body == null ? new JSONObject() : body;
        String phone = first(input, "sourcePhone", "phone");
        String saleId = first(input, "saleId", "id");
        if (phone.isEmpty() || saleId.isEmpty()) throw new NativeException("首发下单缺少账号或发售编号");
        int count = Math.max(1, input.optInt("num", 1));
        int paymentCode = input.optInt("paymentPlatformCode", 0);
        if (paymentCode <= 0) {
            JSONObject platformData = firstSalePaymentPlatforms(phone).optJSONObject("data");
            JSONArray platforms = firstArray(platformData, "items");
            for (int index = 0; index < platforms.length(); index++) {
                JSONObject platform = platforms.optJSONObject(index);
                if (platform != null && platform.optInt("activationStatus", 0) == 1 && platform.optBoolean("enabled", true) && platform.optBoolean("available", true)) {
                    paymentCode = integer(platform.opt("code"), 0);
                    break;
                }
            }
        }
        if (paymentCode <= 0) throw new NativeException("未找到可用的首发支付通道");
        JSONObject request = objectOf("num", count, "paymentPlatformCode", paymentCode);
        JSONObject response = client.requestAuthenticatedWithCaptcha(
                account(phone),
                "POST",
                FIRST_SALE_ORDER_URL + "/" + encodePath(saleId) + "/orders",
                request,
                null,
                IBoxDirectClient.CaptchaResult.fromJson(captcha),
                objectOf("Verify-Flag", "true"),
                true,
                "首发下单"
        );
        return ok(data(response));
    }

    private JSONObject lotteryState() throws Exception {
        refreshLottery(true);
        return ok(objectOf("activities", store.getLotteryTasks(), "intervalMs", 3000));
    }

    private void refreshLottery(boolean force) throws Exception {
        long now = taskNow();
        if (!force && now - lastLotteryRefreshAt < 3000L) return;
        IBoxDirectClient.Account account = firstAccount();
        JSONObject historyResponse = client.requestAuthenticated(account, "GET", LOTTERY_HISTORY_URL, null, null, false, "抽奖历史");
        JSONArray history = firstArray(data(historyResponse), "activities", "list", "records", "items");
        JSONArray activities = new JSONArray();
        int maximum = Math.min(history.length(), 30);
        for (int index = 0; index < maximum; index++) {
            JSONObject row = history.optJSONObject(index);
            String id = first(row, "id", "drawId", "lotteryActivityId");
            if (!id.matches("\\d+")) continue;
            JSONObject activity = new JSONObject();
            activity.put("id", id);
            JSONObject detailResponse = client.requestAuthenticated(account, "GET", LOTTERY_ACTIVITY_URL + "/" + encodePath(id), null, null, false, "抽奖活动");
            applyLotteryDetail(activity, object(data(detailResponse)), now);
            String phase = activity.optString("phase");
            // WebView 抽奖资源没有手动开关，活动进入监听列表后始终自动执行。
            activity.put("enabled", true);
            activity.put("updatedAt", Instant.now().toString());
            JSONObject accounts = new JSONObject();
            JSONArray localAccounts = store.getAccounts();
            for (int accountIndex = 0; accountIndex < localAccounts.length(); accountIndex++) {
                JSONObject stored = localAccounts.optJSONObject(accountIndex);
                if (stored == null) continue;
                String phone = stored.optString("phone");
                try {
                    IBoxDirectClient.Account candidate = account(phone);
                    if (!"open".equals(phase)) {
                        accounts.put(phone, objectOf("availableCount", 0, "status", phase, "updatedAt", Instant.now().toString()));
                    } else {
                        int count = availableLotteryCount(candidate, id);
                        accounts.put(phone, objectOf("availableCount", count, "status", count > 0 ? "ready" : "no_chance", "updatedAt", Instant.now().toString()));
                    }
                } catch (Exception error) {
                    accounts.put(phone, objectOf("availableCount", 0, "status", "failed", "message", message(error), "updatedAt", Instant.now().toString()));
                }
            }
            activity.put("accounts", accounts);
            activities.put(activity);
        }
        store.saveLotteryTasks(keepLatestEndedLottery(activities));
        lastLotteryRefreshAt = now;
    }

    private static JSONArray keepLatestEndedLottery(JSONArray activities) {
        JSONArray visible = new JSONArray();
        JSONObject latestEnded = null;
        long latestEndedAt = Long.MIN_VALUE;
        for (int index = 0; index < activities.length(); index++) {
            JSONObject activity = activities.optJSONObject(index);
            if (activity == null) continue;
            if (!"ended".equals(activity.optString("phase"))) {
                visible.put(activity);
                continue;
            }
            long endedAt = epoch(activity.optString("endedAt", activity.optString("endTime")));
            if (latestEnded == null || endedAt > latestEndedAt) {
                latestEnded = activity;
                latestEndedAt = endedAt;
            }
        }
        if (latestEnded != null) visible.put(latestEnded);
        return visible;
    }

    private JSONObject setLotteryEnabled(String id, boolean enabled) throws Exception {
        JSONArray activities = store.getLotteryTasks();
        for (int index = 0; index < activities.length(); index++) {
            JSONObject activity = activities.optJSONObject(index);
            if (activity == null || !id.equals(activity.optString("id"))) continue;
            activity.put("enabled", enabled);
            activity.put("updatedAt", Instant.now().toString());
            store.saveLotteryTasks(activities);
            return ok(activity);
        }
        throw new NativeException("抽奖活动不存在，请先刷新活动列表");
    }

    private void validateQuantStrategy(JSONObject strategy) throws Exception {
        String phone = first(strategy, "phone", "sourcePhone");
        String groupId = first(strategy, "groupId", "collectionId", "id");
        if (!phone.matches("\\d{11}")) throw new NativeException("量化策略账号无效");
        account(phone);
        if (!groupId.matches("\\d+")) throw new NativeException("量化藏品编号无效");
        String executionMode = strategy.optString("executionMode", "live").trim().toLowerCase(Locale.ROOT);
        if (!"live".equals(executionMode)) throw new NativeException("量化策略仅支持自动执行");
        String preset = strategy.optString("strategyPreset", "custom").trim();
        if (!("smart_value_buy".equals(preset) || "cost_profit_exit".equals(preset)
                || "trailing_profit_exit".equals(preset) || "custom".equals(preset))) {
            throw new NativeException("量化交易逻辑无效");
        }
        int intervalValue = strategy.has("intervalValue") ? integer(strategy.opt("intervalValue"), -1) : 15;
        if (intervalValue < 1 || intervalValue > 86_400) throw new NativeException("量化监控间隔无效");
        String intervalUnit = strategy.optString("intervalUnit", "seconds").trim().toLowerCase(Locale.ROOT);
        if (!("seconds".equals(intervalUnit) || "minutes".equals(intervalUnit) || "hours".equals(intervalUnit))) throw new NativeException("量化监控间隔单位无效");
        int maxPosition = strategy.has("maxPosition") ? integer(strategy.opt("maxPosition"), -1) : 1;
        if (maxPosition < 1) throw new NativeException("量化最大持仓必须大于 0");
        double minimumProfit = strategy.has("minNetProfit") ? decimal(strategy.opt("minNetProfit"), Double.NaN) : 0d;
        if (!Double.isFinite(minimumProfit) || minimumProfit < 0d) throw new NativeException("量化最低净利无效");
        if ("smart_value_buy".equals(preset)) {
            double discount = decimal(strategy.opt("entryDiscountPercent"), Double.NaN);
            int minimumSamples = integer(strategy.opt("minimumHistorySamples"), -1);
            int historyLimit = integer(strategy.opt("priceHistoryLimit"), -1);
            if (!Double.isFinite(discount) || discount < 0.1d || discount > 50d) throw new NativeException("智能折价比例无效");
            if (minimumSamples < 3 || minimumSamples > QUANT_PRICE_HISTORY_LIMIT
                    || historyLimit < minimumSamples || historyLimit > QUANT_PRICE_HISTORY_LIMIT) {
                throw new NativeException("智能行情样本参数无效");
            }
        }
        if ("cost_profit_exit".equals(preset) || "trailing_profit_exit".equals(preset)) {
            if (!Double.isFinite(store.getAssetCost(phone, groupId))) {
                throw new NativeException("请先在资产页长按该持仓并录入成本");
            }
            if (minimumProfit <= 0d) throw new NativeException("退出策略最低净利必须大于 0");
        }
        if ("trailing_profit_exit".equals(preset)) {
            double activation = decimal(strategy.opt("trailingActivationPercent"), Double.NaN);
            double drawdown = decimal(strategy.opt("trailingDrawdownPercent"), Double.NaN);
            double retention = decimal(strategy.opt("trailingRetentionPercent"), Double.NaN);
            if (!Double.isFinite(activation) || activation < 0.1d || activation > 100d
                    || !Double.isFinite(drawdown) || drawdown < 0.1d || drawdown > 50d
                    || !Double.isFinite(retention) || retention < 0d || retention >= activation) {
                throw new NativeException("峰值回撤参数无效");
            }
        }
        double volatilityLimit = strategy.has("volatilityLimitPercent") ? decimal(strategy.opt("volatilityLimitPercent"), Double.NaN) : 0d;
        if (!Double.isFinite(volatilityLimit) || volatilityLimit < 0d || volatilityLimit > QUANT_MAX_VOLATILITY_PERCENT) {
            throw new NativeException("量化波动上限无效");
        }
        int cooldownMinutes = strategy.has("cooldownMinutes") ? integer(strategy.opt("cooldownMinutes"), -1) : 10;
        if (cooldownMinutes < 0 || cooldownMinutes > QUANT_MAX_COOLDOWN_MINUTES) throw new NativeException("量化冷却时间无效");
        JSONObject buy = strategy.optJSONObject("buy");
        JSONObject sell = strategy.optJSONObject("sell");
        JSONObject stopLoss = strategy.optJSONObject("stopLoss");
        boolean buyEnabled = buy != null && buy.optBoolean("enabled", false);
        boolean sellEnabled = sell != null && sell.optBoolean("enabled", false);
        boolean stopLossEnabled = stopLoss != null && stopLoss.optBoolean("enabled", false);
        if (!buyEnabled && !sellEnabled && !stopLossEnabled) throw new NativeException("量化策略至少需要启用一条规则");
        if ("smart_value_buy".equals(preset) && (!buyEnabled || sellEnabled || stopLossEnabled)) {
            throw new NativeException("智能折价买入规则配置无效");
        }
        if (("cost_profit_exit".equals(preset) || "trailing_profit_exit".equals(preset))
                && (buyEnabled || !sellEnabled || stopLossEnabled)) {
            throw new NativeException("智能退出规则配置无效");
        }
        if (buyEnabled) {
            double maxPrice = decimal(buy.opt("maxPrice"), Double.NaN);
            int quantity = integer(buy.opt("quantity"), -1);
            if (!Double.isFinite(maxPrice) || maxPrice <= 0d) throw new NativeException("量化买入价格无效");
            if (quantity != 1) throw new NativeException("量化买入每次只能提交 1 件");
        }
        if (sellEnabled) {
            double minPrice = decimal(sell.opt("minPrice"), Double.NaN);
            double sellPrice = decimal(sell.opt("sellPrice"), Double.NaN);
            int quantity = integer(sell.opt("quantity"), -1);
            if (!Double.isFinite(minPrice) || minPrice <= 0d || !Double.isFinite(sellPrice) || sellPrice <= 0d) {
                throw new NativeException("量化卖出价格无效");
            }
            if (sellPrice != Math.rint(sellPrice)) throw new NativeException("量化寄售价必须为整数");
            if (quantity != 1) throw new NativeException("量化卖出每次只能提交 1 件");
        }
        if (stopLossEnabled) {
            double triggerPrice = decimal(stopLoss.opt("triggerPrice"), Double.NaN);
            double sellPrice = decimal(stopLoss.opt("sellPrice"), Double.NaN);
            int quantity = sell == null ? 1 : integer(sell.opt("quantity"), 1);
            if (!Double.isFinite(triggerPrice) || triggerPrice <= 0d || !Double.isFinite(sellPrice) || sellPrice <= 0d) {
                throw new NativeException("量化止损价格无效");
            }
            if (sellPrice != Math.rint(sellPrice)) throw new NativeException("量化止损寄售价必须为整数");
            if (quantity != 1) throw new NativeException("量化止损每次只能提交 1 件");
        }
        if (sellEnabled || stopLossEnabled) requiredTradePassword(strategy);
    }

    private static boolean hasLiveQuantConflict(JSONArray strategies, String phone, String groupId, String exceptId) {
        if (strategies == null) return false;
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject item = strategies.optJSONObject(index);
            if (item == null || exceptId.equals(item.optString("id"))) continue;
            if (!"live".equals(item.optString("executionMode")) || !phone.equals(item.optString("phone")) || !groupId.equals(item.optString("groupId"))) continue;
            String status = item.optString("status");
            if (item.optBoolean("enabled", false) || "verification_required".equals(status) || "needs_sell_password".equals(status) || "payment_pending".equals(status)) return true;
        }
        return false;
    }

    private static void resetQuantRuntimeState(JSONObject strategy) {
        String[] keys = {
                "priceHistory", "fairPrice", "highestObservedPrice", "assetCost", "dynamicSellTargetPrice",
                "trailingActivated", "historyReady", "historySampleCount", "entryMaximum", "estimatedNetProfit",
                "latestFloorPrice", "lastVolatilityPercent", "lowestListingPrice", "listingCount", "holdings",
                "platformPriceRange", "lastSignal", "lastSignalPrice"
        };
        for (String key : keys) strategy.remove(key);
    }

    private JSONObject createQuantStrategy(JSONObject body) throws Exception {
        JSONObject strategy = copy(body);
        String phone = first(strategy, "phone", "sourcePhone");
        String groupId = first(strategy, "groupId", "collectionId", "id");
        if (phone.isEmpty() || groupId.isEmpty()) throw new NativeException("量化策略缺少账号或藏品编号");
        if (!groupId.matches("\\d+")) throw new NativeException("量化藏品编号无效");
        strategy.put("phone", phone);
        strategy.put("groupId", groupId);
        strategy.put("executionMode", "live");
        resetQuantRuntimeState(strategy);
        validateQuantStrategy(strategy);
        if (hasLiveQuantConflict(store.getQuantStrategies(), phone, groupId, "")) {
            throw new NativeException("同账号同藏品已有运行中的实时量化策略");
        }
        int intervalValue = strategy.optInt("intervalValue", 15);
        String intervalUnit = strategy.optString("intervalUnit", "seconds").trim().toLowerCase(Locale.ROOT);
        String now = Instant.now().toString();
        strategy.put("id", UUID.randomUUID().toString());
        strategy.put("phone", phone);
        strategy.put("groupId", groupId);
        strategy.put("title", first(strategy, "title", "name", "groupName", "groupId"));
        strategy.put("enabled", true);
        strategy.put("status", "monitoring");
        strategy.put("intervalValue", intervalValue);
        strategy.put("intervalUnit", intervalUnit);
        strategy.put("createdAt", now);
        strategy.put("updatedAt", now);
        strategy.put("lastCheckAt", "");
        strategy.put("events", new JSONArray());
        JSONArray strategies = store.getQuantStrategies();
        strategies.put(strategy);
        if (!store.saveQuantStrategies(strategies)) throw new NativeException("量化策略保存失败");
        return ok(strategy);
    }

    private JSONObject updateQuantStrategy(String id, JSONObject body) throws Exception {
        JSONArray strategies = store.getQuantStrategies();
        JSONObject updated = null;
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !id.equals(strategy.optString("id"))) continue;
            String currentStatus = strategy.optString("status");
            if ("submitted".equals(currentStatus) || "payment_pending".equals(currentStatus)) {
                throw new NativeException("已提交或待支付的量化策略不能修改");
            }
            JSONObject candidate = copy(strategy);
            merge(candidate, body);
            candidate.put("executionMode", "live");
            validateQuantStrategy(candidate);
            String candidatePhone = first(candidate, "phone", "sourcePhone");
            String candidateGroupId = first(candidate, "groupId", "collectionId", "id");
            if (hasLiveQuantConflict(strategies, candidatePhone, candidateGroupId, id)) {
                throw new NativeException("同账号同藏品已有运行中的实时量化策略");
            }
            merge(strategy, body);
            resetQuantRuntimeState(strategy);
            strategy.put("phone", candidatePhone);
            strategy.put("groupId", candidateGroupId);
            strategy.put("executionMode", "live");
            strategy.put("enabled", true);
            strategy.put("status", "monitoring");
            strategy.put("lastSignal", "idle");
            strategy.put("lastCheckAt", "");
            strategy.put("nextCheckAt", "");
            strategy.put("consecutiveFailures", 0);
            strategy.put("lastFailureAt", "");
            strategy.put("lastRetryAfterMs", 0);
            strategy.put("lastResult", "strategy_updated");
            strategy.put("paymentStatus", "not_started");
            strategy.remove("digitalCollectionId");
            strategy.remove("resolvedPaymentPlatformCode");
            strategy.remove("orderUuid");
            strategy.remove("listingOrderItemId");
            strategy.remove("cashierLink");
            strategy.remove("result");
            strategy.remove("submittedAt");
            strategy.remove("triggeredAt");
            strategy.remove("cancelledAt");
            strategy.remove("pendingAction");
            strategy.put("updatedAt", Instant.now().toString());
            addEvent(strategy, "updated", "策略已更新");
            updated = strategy;
            break;
        }
        if (updated == null) throw new NativeException("量化策略不存在");
        store.saveQuantStrategies(strategies);
        return ok(updated);
    }

    private JSONObject setQuantEnabled(String id, boolean enabled) throws Exception {
        JSONArray strategies = store.getQuantStrategies();
        JSONObject changed = null;
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !id.equals(strategy.optString("id"))) continue;
            if (enabled && terminalTaskStatus(strategy.optString("status"))) {
                throw new NativeException("已提交或待支付的量化策略不能重新启动");
            }
            if (enabled) {
                strategy.put("executionMode", "live");
                validateQuantStrategy(strategy);
                if (hasLiveQuantConflict(strategies, strategy.optString("phone"), strategy.optString("groupId"), id)) {
                    throw new NativeException("同账号同藏品已有运行中的实时量化策略");
                }
                resetQuantRuntimeState(strategy);
                IBoxDirectClient.Account direct = this.account(strategy.optString("phone"));
                JSONObject evaluated = evaluateQuantStrategy(strategy, direct);
                applyQuantSnapshot(strategy, evaluated);
                strategy.remove("lastCheckAt");
                strategy.remove("nextCheckAt");
            }
            strategy.put("enabled", enabled);
            strategy.put("status", enabled ? "monitoring" : "paused");
            strategy.put("updatedAt", Instant.now().toString());
            addEvent(strategy, enabled ? "enabled" : "paused", enabled ? "策略已启动" : "策略已暂停");
            changed = strategy;
            break;
        }
        if (changed == null) throw new NativeException("量化策略不存在");
        store.saveQuantStrategies(strategies);
        if (!enabled) sendBarkBestEffort(changed, "notifyStrategyPaused", "iBox 量化策略已暂停", changed.optString("title"), "active");
        return ok(changed);
    }

    private JSONObject deleteQuantStrategy(String id) throws Exception {
        JSONArray strategies = store.getQuantStrategies();
        JSONArray remaining = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject item = strategies.optJSONObject(index);
            if (item != null && id.equals(item.optString("id"))) removed = true;
            else if (item != null) remaining.put(item);
        }
        if (!removed) throw new NativeException("量化策略不存在");
        store.saveQuantStrategies(remaining);
        return ok(new JSONObject());
    }

    private JSONObject quantEvents(String id) throws Exception {
        JSONArray strategies = store.getQuantStrategies();
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy != null && id.equals(strategy.optString("id"))) return ok(objectOf("events", strategy.optJSONArray("events") == null ? new JSONArray() : strategy.optJSONArray("events")));
        }
        throw new NativeException("量化策略不存在");
    }

    private JSONArray quantStrategyList() {
        JSONArray strategies = store.getQuantStrategies();
        boolean changed = false;
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !"monitor".equals(strategy.optString("executionMode"))) continue;
            putQuietly(strategy, "executionMode", "live");
            putQuietly(strategy, "enabled", false);
            putQuietly(strategy, "status", "paused");
            putQuietly(strategy, "lastResult", "mode_removed_requires_review");
            putQuietly(strategy, "updatedAt", Instant.now().toString());
            addEventQuietly(strategy, "mode_migrated", "仅监控模式已移除，请确认后重新启动");
            changed = true;
        }
        if (changed) store.saveQuantStrategies(strategies);
        return strategies;
    }

    private JSONObject createTradeTask(JSONObject body, String kind) throws Exception {
        JSONObject task = copy(body);
        String phone = first(task, "phone", "sourcePhone");
        String groupId = first(task, "groupId", "collectionId", "id");
        if (phone.isEmpty() || groupId.isEmpty()) throw new NativeException("交易任务缺少账号或藏品编号");
        if (!groupId.matches("\\d+")) throw new NativeException("交易藏品编号无效");
        if ("market_trade".equals(kind)) {
            String type = task.optString("type");
            if (!("wanted".equals(type) || "consignment".equals(type))) throw new NativeException("交易任务类型无效");
            double price = decimal(task.opt("price"), Double.NaN);
            if (!Double.isFinite(price) || price <= 0d) throw new NativeException("交易价格必须大于 0");
            if ("wanted".equals(type) && !task.optBoolean("agreementAccepted", false)) {
                throw new NativeException("求购任务需要确认交易服务协议");
            }
            if ("consignment".equals(type)) {
                String digitalCollectionId = first(task, "digitalCollectionId");
                if (!digitalCollectionId.matches("\\d+")) throw new NativeException("寄售任务需要选择持仓资产");
                double triggerPrice = decimal(task.opt("triggerPrice"), Double.NaN);
                if (!Double.isFinite(triggerPrice) || triggerPrice <= 0d) throw new NativeException("寄售任务需要触发行情价");
                requiredTradePassword(task);
            }
        } else {
            double minimum = decimal(task.opt("minPrice"), 0d);
            double maximum = decimal(task.opt("maxPrice"), Double.NaN);
            if (minimum < 0d || !Double.isFinite(maximum) || maximum <= 0d || minimum > maximum) {
                throw new NativeException("捡漏价格范围无效");
            }
        }
        String now = Instant.now().toString();
        task.put("id", UUID.randomUUID().toString());
        task.put("localType", kind);
        task.put("phone", phone);
        task.put("groupId", groupId);
        task.put("title", first(task, "title", "name", "groupName", "groupId"));
        task.put("enabled", task.optBoolean("autoStart", true));
        task.put("status", task.optBoolean("autoStart", true) ? "scheduled" : "paused");
        task.put("createdAt", now);
        task.put("updatedAt", now);
        task.put("events", new JSONArray());
        JSONObject preflightPlan = null;
        if ("retired_market".equals(kind)) {
            validateRetiredMarketTask(task);
        } else {
            preflightPlan = preflightMarketTradeTask(task);
            applyMarketTradePreflight(task, preflightPlan, false);
        }
        JSONArray tasks = store.getTradeTasks();
        tasks.put(task);
        if (!store.saveTradeTasks(tasks)) throw new NativeException("交易任务保存失败");
        if (task.optBoolean("enabled", false)) {
            processTradeTasks(task.optString("id"), preflightPlan);
            JSONObject updated = findTask(store.getTradeTasks(), task.optString("id"), kind);
            if (updated != null) task = updated;
        }
        return ok(task);
    }

    private JSONArray retiredTasks() {
        JSONArray all = store.getTradeTasks();
        JSONArray result = new JSONArray();
        for (int index = 0; index < all.length(); index++) {
            JSONObject task = all.optJSONObject(index);
            if (task != null && "retired_market".equals(task.optString("localType"))) result.put(task);
        }
        return result;
    }

    private JSONObject createImmediatePurchase(JSONObject body) throws Exception {
        JSONObject task = copy(body);
        String phone = first(task, "phone", "sourcePhone");
        String groupId = first(task, "groupId", "collectionId", "id");
        double maxPrice = decimal(task.opt("maxPrice"), Double.NaN);
        if (phone.isEmpty() || !phone.matches("\\d{11}")) throw new NativeException("立即买入缺少有效账号");
        if (!groupId.matches("\\d+")) throw new NativeException("立即买入藏品编号无效");
        if (!Double.isFinite(maxPrice) || maxPrice <= 0d) throw new NativeException("立即买入最高价无效");

        IBoxDirectClient.Account account = account(phone);
        JSONObject candidate = currentPurchaseCandidate(account, groupId, "", maxPrice);
        String now = Instant.now().toString();
        task.put("id", UUID.randomUUID().toString());
        task.put("localType", "retired_market");
        task.put("executionMode", "immediate_purchase");
        task.put("phone", phone);
        task.put("groupId", groupId);
        task.put("title", first(task, "title", "name", "groupName", "groupId"));
        task.put("enabled", true);
        task.put("status", "submitting");
        task.put("pendingDigitalCollectionId", candidate.optString("digitalCollectionId"));
        task.put("pendingPrice", candidate.opt("price"));
        task.put("createdAt", now);
        task.put("updatedAt", now);
        task.put("events", new JSONArray());
        addEvent(task, "immediate_purchase", "已确认当前挂单，正在创建订单");

        JSONArray tasks = store.getTradeTasks();
        tasks.put(task);
        if (!store.saveTradeTasks(tasks)) throw new NativeException("立即买入任务保存失败");
        try {
            processImmediatePurchaseTask(task);
        } catch (Exception error) {
            String failure = message(error);
            task.put("lastResult", failure);
            task.put("lastCheckAt", Instant.now().toString());
            task.put("enabled", captchaRequired(error));
            task.put("status", captchaRequired(error) ? "verification_required" : "failed");
            task.put("updatedAt", Instant.now().toString());
            store.saveTradeTasks(tasks);
            throw error;
        }
        store.saveTradeTasks(tasks);
        return ok(task);
    }

    private JSONArray marketTradeTasks() {
        JSONArray all = store.getTradeTasks();
        JSONArray result = new JSONArray();
        for (int index = 0; index < all.length(); index++) {
            JSONObject task = all.optJSONObject(index);
            if (task != null && "market_trade".equals(task.optString("localType", "market_trade"))) result.put(task);
        }
        return result;
    }

    private JSONObject marketTradeTaskState() throws Exception {
        JSONObject sync = reconcileConsignmentTasksFromPlatform();
        return ok(objectOf("tasks", marketTradeTasks(), "consignmentSync", sync));
    }

    private JSONObject reconcileConsignmentTasksFromPlatform() throws JSONException {
        JSONArray tasks = store.getTradeTasks();
        Map<String, Set<String>> activeInstances = new HashMap<>();
        Set<String> failedGroups = new HashSet<>();
        JSONArray failures = new JSONArray();
        int checkedGroups = 0;

        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (!finishedConsignmentTask(task)) continue;
            String phone = task.optString("phone");
            String groupId = task.optString("groupId");
            String key = phone + ":" + groupId;
            if (activeInstances.containsKey(key) || failedGroups.contains(key)) continue;
            try {
                JSONArray active = activeConsignmentAssets(account(phone), groupId);
                Set<String> ids = new HashSet<>();
                for (int activeIndex = 0; activeIndex < active.length(); activeIndex++) {
                    String instanceId = ownedCollectionId(active.optJSONObject(activeIndex), groupId);
                    if (!instanceId.matches("\\d+")) throw new NativeException("平台寄售实例编号无效");
                    ids.add(instanceId);
                }
                activeInstances.put(key, ids);
                checkedGroups++;
            } catch (Exception error) {
                failedGroups.add(key);
                failures.put(objectOf("phone", phone, "groupId", groupId, "message", message(error)));
            }
        }

        JSONArray remaining = new JSONArray();
        int removedTasks = 0;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            if (finishedConsignmentTask(task)) {
                String key = task.optString("phone") + ":" + task.optString("groupId");
                Set<String> ids = activeInstances.get(key);
                if (ids != null && !ids.contains(task.optString("digitalCollectionId"))) {
                    removedTasks++;
                    continue;
                }
            }
            remaining.put(task);
        }
        if (removedTasks > 0 && !store.saveTradeTasks(remaining)) {
            failures.put(objectOf("message", "本地寄售任务状态保存失败"));
            removedTasks = 0;
        }
        return objectOf(
                "checkedAt", Instant.now().toString(),
                "checkedGroups", checkedGroups,
                "removedTasks", removedTasks,
                "failures", failures
        );
    }

    private static boolean finishedConsignmentTask(JSONObject task) {
        if (task == null || !"consignment".equals(task.optString("type")) || task.optBoolean("enabled", false)
                || !task.optString("digitalCollectionId").matches("\\d+")) return false;
        String status = task.optString("status");
        return "submitted".equals(status) || "cancelled".equals(status) || "failed".equals(status);
    }

    private JSONObject setTradeEnabled(String id, boolean enabled, String kind) throws Exception {
        JSONArray tasks = store.getTradeTasks();
        JSONObject changed = findTask(tasks, id, kind);
        if (changed == null) throw new NativeException("交易任务不存在");
        if (enabled && terminalTaskStatus(changed.optString("status"))) {
            throw new NativeException("已提交或待支付的交易任务不能重新启动");
        }
        if (enabled) {
            if ("retired_market".equals(kind) && changed.optJSONArray("baselineListingKeys") == null) {
                validateRetiredMarketTask(changed);
            } else if ("market_trade".equals(kind)) {
                JSONObject plan = preflightMarketTradeTask(changed);
                applyMarketTradePreflight(changed, plan, false);
            }
        }
        changed.put("enabled", enabled);
        changed.put("status", enabled ? changed.optString("preflightStatus", "scheduled") : "paused");
        if (enabled) changed.remove("lastCheckAt");
        changed.put("updatedAt", Instant.now().toString());
        addEvent(changed, enabled ? "enabled" : "paused", enabled ? "任务已启动" : "任务已暂停");
        store.saveTradeTasks(tasks);
        if (enabled) {
            processTradeTasks();
            JSONObject updated = findTask(store.getTradeTasks(), id, kind);
            if (updated != null) changed = updated;
        }
        return ok(changed);
    }

    private JSONObject preflightTradeTask(String id, String kind) throws Exception {
        JSONArray tasks = store.getTradeTasks();
        JSONObject task = findTask(tasks, id, kind);
        if (task == null) throw new NativeException("交易任务不存在");
        JSONObject plan;
        if ("retired_market".equals(kind)) {
            plan = validateRetiredMarketTask(task);
        } else {
            plan = preflightMarketTradeTask(task);
            applyMarketTradePreflight(task, plan, true);
        }
        task.put("lastCheckAt", Instant.now().toString());
        task.put("lastResult", "preflight_ready");
        task.put("updatedAt", Instant.now().toString());
        addEvent(task, "preflight", "预检已完成");
        store.saveTradeTasks(tasks);
        return ok(objectOf("task", task, "plan", plan));
    }

    private JSONObject preflightMarketTradeTask(JSONObject task) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        JSONObject detail = loadMarketTradeDetail(account, task.optString("groupId"));
        String type = task.optString("type");
        JSONObject result = objectOf("detail", detail, "paymentPlatformCode", JSONObject.NULL, "owned", new JSONArray(), "publicConfig", JSONObject.NULL);
        if ("consignment".equals(type)) {
            if (explicitFalse(detail.opt("enableConsignment"))) throw new NativeException("当前藏品暂不支持寄售");
            JSONObject config = marketTradePublicConfig(account);
            String priceError = marketTradeConsignmentPriceError(decimal(task.opt("price"), Double.NaN), config);
            if (!priceError.isEmpty()) throw new NativeException(priceError);
            JSONArray owned = ownedCollections(account, task.optString("groupId"));
            if (owned.length() == 0 && hasActiveConsignment(account, task.optString("groupId"))) {
                throw new NativeException("当前藏品正在寄售中");
            }
            String collectionId = resolveOwnedCollectionId(owned, task);
            result.put("owned", owned);
            result.put("digitalCollectionId", collectionId);
            result.put("publicConfig", config);
            result.put("paymentPlatformCode", paymentPlatformCode(account, 1, 0));
            return result;
        }
        if (!"wanted".equals(type)) throw new NativeException("交易任务类型无效");
        if (!task.optBoolean("agreementAccepted", false)) throw new NativeException("求购任务需要确认交易服务协议");
        int requestedCode = task.optInt("paymentPlatformCode", 0);
        if (requestedCode <= 0) throw new NativeException("求购任务需要选择支付通道");
        JSONObject wanted = detail.optJSONObject("wanted");
        if (explicitFalse(detail.opt("enablePurchase")) || (wanted != null && explicitFalse(wanted.opt("enabled")))) {
            throw new NativeException("当前藏品暂不支持求购");
        }
        double price = decimal(task.opt("price"), Double.NaN);
        if (!Double.isFinite(price) || price <= 0d) throw new NativeException("求购价格无效");
        double minimum = wanted == null ? Double.NaN : decimal(wanted.opt("lowLimitPrice"), Double.NaN);
        double maximum = wanted == null ? Double.NaN : decimal(wanted.opt("highLimitPrice"), Double.NaN);
        if (Double.isFinite(minimum) && price < minimum) throw new NativeException("求购价格低于平台下限");
        if (Double.isFinite(maximum) && price > maximum) throw new NativeException("求购价格高于平台上限");
        JSONArray allowedCodes = wanted == null ? new JSONArray() : firstArray(wanted, "paymentPlatformCodes", "platformCodes");
        result.put("paymentPlatformCode", paymentPlatformCode(account, 2, requestedCode, allowedCodes));
        return result;
    }

    private void applyMarketTradePreflight(JSONObject task, JSONObject plan, boolean recordCheck) {
        JSONObject detail = plan == null ? null : plan.optJSONObject("detail");
        double floor = detail == null ? Double.NaN : decimal(detail.opt("floorPrice"), Double.NaN);
        putQuietly(task, "latestFloorPrice", Double.isFinite(floor) ? floor : JSONObject.NULL);
        if (plan != null && !plan.isNull("paymentPlatformCode")) putQuietly(task, "resolvedPaymentPlatformCode", plan.opt("paymentPlatformCode"));
        JSONObject config = plan == null ? null : plan.optJSONObject("publicConfig");
        if (config != null) putQuietly(task, "platformPriceRange", config);
        boolean waiting = marketTradePriceGate(task, detail);
        putQuietly(task, "preflightStatus", waiting ? "waiting_price" : "scheduled");
        if (task.optBoolean("enabled", false)) putQuietly(task, "status", waiting ? "waiting_price" : "scheduled");
        if (recordCheck) putQuietly(task, "lastCheckAt", Instant.now().toString());
        putQuietly(task, "lastResult", waiting ? "waiting_price" : "ready");
    }

    private JSONObject validateRetiredMarketTask(JSONObject task) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        JSONObject detail = loadMarketTradeDetail(account, task.optString("groupId"));
        JSONArray listings = marketListings(account, task.optString("groupId"));
        JSONArray baseline = new JSONArray();
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            String key = listingKey(listing);
            if (!key.isEmpty() && !contains(baseline, key)) baseline.put(key);
        }
        String now = Instant.now().toString();
        if (task.optString("title").isEmpty()) putQuietly(task, "title", detail.optString("name", "藏品 " + task.optString("groupId")));
        if (task.optString("cover").isEmpty()) putQuietly(task, "cover", detail.optString("cover"));
        putQuietly(task, "baselineListingKeys", baseline);
        putQuietly(task, "baselineEmptyAt", now);
        putQuietly(task, "monitorReadyAt", now);
        putQuietly(task, "lastResult", "monitor_ready");
        putQuietly(task, "updatedAt", now);
        return objectOf("detail", detail, "listings", listings);
    }

    private static boolean explicitFalse(Object value) {
        if (value == null || value == JSONObject.NULL || String.valueOf(value).trim().isEmpty()) return false;
        return !bool(value);
    }

    private static String marketTradeConsignmentPriceError(double price, JSONObject config) {
        if (!Double.isFinite(price) || price <= 0d) return "寄售价格无效";
        if (price != Math.rint(price)) return "寄售价格必须为整数";
        double minimum = config == null ? Double.NaN : decimal(config.opt("minPrice"), Double.NaN);
        double maximum = config == null ? Double.NaN : decimal(config.opt("maxPrice"), Double.NaN);
        if (Double.isFinite(minimum) && price < minimum) return "寄售价格低于平台下限";
        if (Double.isFinite(maximum) && price > maximum) return "寄售价格高于平台上限";
        return "";
    }

    private static boolean marketTradePriceGate(JSONObject task, JSONObject detail) {
        if (task == null || !"consignment".equals(task.optString("type"))) return false;
        if (task.optBoolean("immediate", false)) return false;
        double floor = detail == null ? Double.NaN : decimal(detail.opt("floorPrice"), Double.NaN);
        double trigger = decimal(task.opt("triggerPrice"), Double.NaN);
        return !Double.isFinite(floor) || !Double.isFinite(trigger) || floor < trigger;
    }

    private JSONObject tradePayment(String id, String kind) throws Exception {
        JSONArray tasks = store.getTradeTasks();
        JSONObject task = findTask(tasks, id, kind);
        if (task == null) throw new NativeException("交易任务不存在");
        String link = first(task, "cashierLink", "paymentUrl");
        if (link.isEmpty() && !task.optString("orderUuid").isEmpty()) {
            IBoxDirectClient.Account account = account(task.optString("phone"));
            int initiator = "retired_market".equals(kind) ? 0 : 2;
            link = cashierLink(account, task.optString("orderUuid"), initiator);
            task.put("cashierLink", link);
            task.put("paymentStatus", "pending");
            task.put("status", "payment_pending");
            task.put("updatedAt", Instant.now().toString());
            store.saveTradeTasks(tasks);
        }
        if (link.isEmpty()) throw new NativeException("当前任务没有待支付订单");
        return ok(objectOf("cashierLink", link));
    }

    private JSONObject platformOrderPayment(String orderUuid, JSONObject body) throws Exception {
        String phone = first(body, "phone");
        int initiatorType = integer(body.opt("paymentInitiatorType"), -1);
        if (orderUuid.isEmpty() || phone.isEmpty()) throw new NativeException("待支付订单缺少订单号或账号");
        if (initiatorType != 0 && initiatorType != 2) throw new NativeException("待支付订单支付类型无效");
        return ok(objectOf("cashierLink", cashierLink(account(phone), orderUuid, initiatorType)));
    }

    private JSONObject cancelPendingOrder(String orderIdentifier, JSONObject body) throws Exception {
        String phone = first(body, "phone", "sourcePhone");
        if (phone.isEmpty()) throw new NativeException("取消待支付订单需要指定账号");
        if (orderIdentifier == null || orderIdentifier.trim().isEmpty()) throw new NativeException("待支付订单编号无效");
        IBoxDirectClient.Account account = account(phone);
        JSONObject current = loadOrders(phone).optJSONObject("data");
        JSONObject order = findPendingOrder(current, orderIdentifier.trim());
        if (order == null) throw new NativeException("平台当前待支付列表中没有该订单");

        String type = order.optString("type");
        String officialOrderId;
        String url;
        if ("collection_pending".equals(type)) {
            officialOrderId = first(order, "orderUuid", "orderId", "id");
            if (officialOrderId.isEmpty()) throw new NativeException("普通购买待支付订单缺少订单号");
            url = ORDER_LIST_URL + "/" + encodePath(officialOrderId) + "/cancel";
        } else if ("wanted_pending".equals(type)) {
            officialOrderId = first(order, "id", "orderId");
            if (!officialOrderId.matches("\\d+")) throw new NativeException("求购待支付订单缺少平台订单编号");
            url = ADVANCE_ORDER_CANCEL_URL + "/" + encodePath(officialOrderId) + "/cancel";
        } else {
            throw new NativeException("平台待支付订单类型无法确认");
        }

        Exception cancellationFailure = null;
        try {
            client.requestAuthenticated(account, "POST", url, null, null, false, "取消待支付订单");
        } catch (Exception error) {
            cancellationFailure = error;
        }
        if (cancellationFailure != null) {
            JSONObject refreshed;
            try {
                refreshed = loadOrders(phone).optJSONObject("data");
            } catch (Exception ignored) {
                throw cancellationFailure;
            }
            if (orderReadFailed(refreshed, type) || findPendingOrder(refreshed, orderIdentifier.trim()) != null) {
                throw cancellationFailure;
            }
        }
        String orderUuid = first(order, "orderUuid", "orderUUId", "orderId", "id");
        JSONObject local = clearCancelledPendingOrderSources(phone, order);
        try {
            sendBark("notifyCancelSuccess", "iBox 待支付订单已取消", "订单 " + orderUuid + " 已从平台待支付列表移除。", "active");
        } catch (Exception ignored) {
            // 平台取消已经成功，通知失败不能反向覆盖取消结果。
        }
        return ok(objectOf(
                "phone", phone,
                "orderUuid", orderUuid,
                "type", type,
                "local", local
        ));
    }

    private JSONObject findPendingOrder(JSONObject data, String identifier) {
        if (data == null || identifier == null || identifier.isEmpty()) return null;
        JSONArray[] groups = {
                data.optJSONArray("collectionPendingOrders"),
                data.optJSONArray("wantedPendingOrders")
        };
        for (JSONArray group : groups) {
            if (group == null) continue;
            for (int index = 0; index < group.length(); index++) {
                JSONObject order = group.optJSONObject(index);
                if (order == null || integer(order.opt("orderStatus"), -1) != 0) continue;
                String[] ids = {
                        first(order, "orderUuid"),
                        first(order, "orderId"),
                        first(order, "id"),
                        first(order, "orderNumber")
                };
                for (String id : ids) if (identifier.equals(id)) return order;
            }
        }
        return null;
    }

    private static boolean orderReadFailed(JSONObject data, String type) {
        JSONArray failures = data == null ? null : data.optJSONArray("orderReadFailures");
        String source = "wanted_pending".equals(type) ? "consignment" : "collection_pending";
        if (failures == null) return false;
        for (int index = 0; index < failures.length(); index++) {
            if (source.equals(String.valueOf(failures.opt(index)))) return true;
        }
        return false;
    }

    private JSONObject clearCancelledPendingOrderSources(String phone, JSONObject order) throws Exception {
        String orderUuid = first(order, "orderUuid", "orderUUId", "orderId", "id");
        int removedTasks = 0;
        JSONArray tasks = store.getTradeTasks();
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && cancelledOrderMatchesTask(phone, order, task)) {
                removedTasks++;
            } else if (task != null) {
                remaining.put(task);
            }
        }
        if (removedTasks > 0 && !store.saveTradeTasks(remaining)) throw new NativeException("取消订单成功，但本地交易任务清理失败");

        int strategiesChanged = 0;
        JSONArray strategies = store.getQuantStrategies();
        String now = Instant.now().toString();
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !phone.equals(strategy.optString("phone")) || !orderUuid.equals(first(strategy, "orderUuid", "orderId"))) continue;
            strategy.put("enabled", false);
            strategy.put("status", "cancelled");
            strategy.put("lastResult", "payment_order_cancelled");
            strategy.put("cancelledAt", now);
            strategy.remove("orderUuid");
            strategy.remove("cashierLink");
            strategy.remove("paymentStatus");
            addEventQuietly(strategy, "payment_order_cancelled", "待支付订单已取消");
            strategiesChanged++;
        }
        if (strategiesChanged > 0 && !store.saveQuantStrategies(strategies)) throw new NativeException("取消订单成功，但量化策略状态保存失败");

        int firstSaleRunsChanged = clearCancelledFirstSaleRuns(phone, orderUuid, now);
        return objectOf(
                "removedTradeTasks", removedTasks,
                "updatedQuantStrategies", strategiesChanged,
                "updatedFirstSaleRuns", firstSaleRunsChanged
        );
    }

    private void reconcileImmediatePurchaseTasks(String phone, OrderPage orders) throws Exception {
        if (orders == null || orders.count > orders.items.length()) return;
        JSONArray tasks = store.getTradeTasks();
        JSONArray remaining = new JSONArray();
        Set<String> claimedOrders = new HashSet<>();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            if (!isImmediatePurchaseTask(phone, task)) {
                remaining.put(task);
                continue;
            }
            String status = task.optString("status");
            JSONObject pending = findImmediatePurchaseOrder(task, orders.items, claimedOrders);
            if (pending != null) {
                String identifier = first(pending, "orderUuid", "orderId", "id", "orderNumber");
                if (!identifier.isEmpty()) claimedOrders.add(identifier);
                if (("submitting".equals(status) || "failed".equals(status)) && !identifier.isEmpty()) {
                    task.put("orderUuid", identifier);
                    task.put("enabled", false);
                    task.put("status", "payment_pending");
                    task.put("lastResult", "payment_pending");
                    task.put("lockedAt", first(pending, "createdAt", "createTime"));
                    task.put("updatedAt", Instant.now().toString());
                    changed = true;
                }
                remaining.put(task);
                continue;
            }
            long createdAt = epoch(first(task, "createdAt", "updatedAt"));
            boolean staleSubmitting = "submitting".equals(status)
                    && createdAt > 0L && now - createdAt >= IMMEDIATE_PURCHASE_STALE_MS;
            if ("payment_pending".equals(status) || staleSubmitting) {
                changed = true;
                continue;
            }
            remaining.put(task);
        }
        if (changed && !store.saveTradeTasks(remaining)) throw new NativeException("平台订单同步成功，但本地立即买入任务清理失败");
    }

    private static JSONObject findImmediatePurchaseOrder(JSONObject task, JSONArray orders, Set<String> claimedOrders) {
        JSONObject closest = null;
        long closestDistance = Long.MAX_VALUE;
        String taskGroupId = first(task, "groupId", "collectionGroupId", "digitalCollectionGroupId");
        long taskCreatedAt = epoch(first(task, "createdAt", "updatedAt"));
        for (int index = 0; index < orders.length(); index++) {
            JSONObject order = orders.optJSONObject(index);
            if (order == null || integer(order.opt("orderStatus"), -1) != 0) continue;
            String identifier = first(order, "orderUuid", "orderId", "id", "orderNumber");
            if (!identifier.isEmpty() && claimedOrders.contains(identifier)) continue;
            if (sameOrderIdentifier(task, order)) return order;
            String orderGroupId = first(order, "groupId", "collectionGroupId", "digitalCollectionGroupId");
            long orderCreatedAt = epoch(first(order, "createdAt", "createTime"));
            if (taskGroupId.isEmpty() || !taskGroupId.equals(orderGroupId) || taskCreatedAt <= 0L || orderCreatedAt <= 0L) continue;
            long distance = Math.abs(taskCreatedAt - orderCreatedAt);
            if (distance <= ORDER_TASK_LINK_WINDOW_MS && distance < closestDistance) {
                closest = order;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static boolean cancelledOrderMatchesTask(String phone, JSONObject order, JSONObject task) {
        if (!phone.equals(task.optString("phone"))) return false;
        if (sameOrderIdentifier(task, order)) return true;
        if (!isImmediatePurchaseTask(phone, task)) return false;
        String taskGroupId = first(task, "groupId", "collectionGroupId", "digitalCollectionGroupId");
        String orderGroupId = first(order, "groupId", "collectionGroupId", "digitalCollectionGroupId");
        long taskCreatedAt = epoch(first(task, "createdAt", "updatedAt"));
        long orderCreatedAt = epoch(first(order, "createdAt", "createTime"));
        return !taskGroupId.isEmpty() && taskGroupId.equals(orderGroupId)
                && taskCreatedAt > 0L && orderCreatedAt > 0L
                && Math.abs(taskCreatedAt - orderCreatedAt) <= ORDER_TASK_LINK_WINDOW_MS;
    }

    private static boolean isImmediatePurchaseTask(String phone, JSONObject task) {
        return task != null && phone.equals(task.optString("phone"))
                && "retired_market".equals(task.optString("localType"))
                && "immediate_purchase".equals(task.optString("executionMode"));
    }

    private static boolean sameOrderIdentifier(JSONObject left, JSONObject right) {
        String[] keys = {"orderUuid", "orderUUId", "orderUUID", "orderId", "orderNumber", "id"};
        for (String leftKey : keys) {
            String leftValue = first(left, leftKey);
            if (leftValue.isEmpty()) continue;
            for (String rightKey : keys) {
                if (leftValue.equals(first(right, rightKey))) return true;
            }
        }
        return false;
    }

    private int clearCancelledFirstSaleRuns(String phone, String orderUuid, String now) throws Exception {
        JSONArray tasks = store.getFirstSaleTasks();
        int changed = 0;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            JSONObject runs = task == null ? null : task.optJSONObject("runs");
            JSONObject run = runs == null ? null : runs.optJSONObject(phone);
            if (run == null || !orderUuid.equals(first(run, "orderUuid", "orderId"))) continue;
            run.put("status", "cancelled");
            run.put("lastResult", "payment_order_cancelled");
            run.put("message", "待支付订单已取消");
            run.put("updatedAt", now);
            run.remove("orderUuid");
            run.remove("cashierLink");
            run.remove("paymentStatus");
            int total = 0;
            int cancelled = 0;
            int completed = 0;
            Iterator<String> phones = runs.keys();
            while (phones.hasNext()) {
                JSONObject current = runs.optJSONObject(phones.next());
                if (current == null) continue;
                total++;
                String status = current.optString("status");
                if ("cancelled".equals(status)) cancelled++;
                if ("payment_pending".equals(status) || "submitted".equals(status)) completed++;
            }
            task.put("enabled", false);
            task.put("status", cancelled == total && total > 0 ? "cancelled" : completed > 0 ? "partial" : "failed");
            task.put("lastResult", "payment_order_cancelled");
            task.put("updatedAt", now);
            changed++;
        }
        if (changed > 0 && !store.saveFirstSaleTasks(tasks)) throw new NativeException("取消订单成功，但首发任务状态保存失败");
        return changed;
    }

    private JSONObject deleteTradeTask(String id, String kind) throws Exception {
        JSONArray tasks = store.getTradeTasks();
        JSONArray remaining = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && id.equals(task.optString("id")) && kind.equals(task.optString("localType"))) {
                removed = true;
            } else if (task != null) {
                remaining.put(task);
            }
        }
        if (!removed) throw new NativeException("交易任务不存在");
        store.saveTradeTasks(remaining);
        return ok(new JSONObject());
    }

    private JSONObject cancelConsignmentOrder(String orderId, JSONObject body) throws Exception {
        String phone = first(body, "phone", "sourcePhone");
        String digitalCollectionId = first(body, "digitalCollectionId", "assetId", "instanceId");
        String listingOrderItemId = first(body, "listingOrderItemId");
        if (phone.isEmpty()) throw new NativeException("取消寄售需要指定账号");
        if (!orderId.matches("\\d+")) throw new NativeException("寄售订单编号无效");
        IBoxDirectClient.Account account = account(phone);
        client.requestAuthenticated(
                account,
                "POST",
                CONSIGNMENT_ORDER_URL + "/" + encodePath(orderId) + "/cancel",
                null,
                null,
                false,
                "取消寄售"
        );

        String now = Instant.now().toString();
        boolean tasksRemoved = removeCancelledConsignmentTasks(phone, digitalCollectionId, listingOrderItemId);
        boolean strategiesChanged = markCancelledQuantStrategies(phone, digitalCollectionId, listingOrderItemId, now);
        try {
            sendBark("notifyCancelSuccess", "iBox 寄售已取消", "实例 " + (digitalCollectionId.isEmpty() ? orderId : digitalCollectionId) + " 已取消寄售。", "active");
        } catch (Exception ignored) {
            // 平台取消已经成功，通知失败不能反向覆盖取消结果。
        }
        return ok(objectOf(
                "orderId", orderId,
                "phone", phone,
                "digitalCollectionId", digitalCollectionId,
                "tasksRemoved", tasksRemoved,
                "strategiesChanged", strategiesChanged
        ));
    }

    private boolean removeCancelledConsignmentTasks(String phone, String digitalCollectionId, String listingOrderItemId) {
        JSONArray tasks = store.getTradeTasks();
        JSONArray remaining = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            if ("consignment".equals(task.optString("type"))
                    && phone.equals(task.optString("phone"))
                    && matchesConsignment(task, digitalCollectionId, listingOrderItemId)) {
                removed = true;
                continue;
            }
            remaining.put(task);
        }
        return removed && store.saveTradeTasks(remaining);
    }

    private boolean markCancelledQuantStrategies(String phone, String digitalCollectionId, String listingOrderItemId, String now) {
        JSONArray strategies = store.getQuantStrategies();
        boolean changed = false;
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !"submitted".equals(strategy.optString("status"))
                    || !phone.equals(strategy.optString("phone"))
                    || !matchesConsignment(strategy, digitalCollectionId, listingOrderItemId)) continue;
            putQuietly(strategy, "enabled", false);
            putQuietly(strategy, "status", "cancelled");
            putQuietly(strategy, "lastResult", "consignment_cancelled");
            putQuietly(strategy, "cancelledAt", now);
            putQuietly(strategy, "updatedAt", now);
            addEventQuietly(strategy, "consignment_cancelled", "量化寄售已取消");
            changed = true;
        }
        return changed && store.saveQuantStrategies(strategies);
    }

    private static boolean matchesConsignment(JSONObject value, String digitalCollectionId, String listingOrderItemId) {
        return (!digitalCollectionId.isEmpty() && digitalCollectionId.equals(value.optString("digitalCollectionId")))
                || (!listingOrderItemId.isEmpty() && listingOrderItemId.equals(value.optString("listingOrderItemId")));
    }

    private JSONObject taskCaptcha(String kind, Route route, String method, JSONObject body) throws Exception {
        String taskId = taskId(kind, route);
        if (taskId.isEmpty()) throw new NativeException("人机验证任务编号为空");
        if (route.path.endsWith("/captcha/session") && "POST".equals(method)) {
            String id = UUID.randomUUID().toString();
            synchronized (taskCaptchaSessions) {
                taskCaptchaSessions.put(id, new TaskCaptchaSession(id, kind, taskId, System.currentTimeMillis() + CAPTCHA_TTL_MS));
            }
            return ok(objectOf("sessionId", id, "captchaId", CAPTCHA_ID));
        }
        if (!"POST".equals(method)) throw new NativeException("人机验证请求方式不支持");
        String sessionId = route.tailAfter("/captcha/");
        TaskCaptchaSession session;
        synchronized (taskCaptchaSessions) {
            session = taskCaptchaSessions.remove(sessionId);
        }
        if (session == null || session.expiresAt <= System.currentTimeMillis() || !kind.equals(session.kind) || !taskId.equals(session.taskId)) {
            throw new NativeException("人机验证会话已过期，请重新发起");
        }
        JSONObject captcha = body == null ? null : body.optJSONObject("captcha");
        IBoxDirectClient.CaptchaResult result = IBoxDirectClient.CaptchaResult.fromJson(captcha);
        if (!result.isComplete()) throw new NativeException("人机验证结果不完整");
        JSONObject normalized = new JSONObject();
        normalized.put("lot_number", result.lotNumber);
        normalized.put("captcha_output", result.captchaOutput);
        normalized.put("pass_token", result.passToken);
        normalized.put("gen_time", result.genTime);
        applyTaskCaptcha(kind, taskId, normalized);
        return ok(objectOf("taskId", taskId, "verified", true));
    }

    private JSONObject barkSummary() {
        JSONObject config = store.getBarkConfig();
        boolean configured = !config.optString("server").trim().isEmpty() && !config.optString("deviceKey").trim().isEmpty();
        try {
            config.put("configured", configured);
            config.put("enabled", config.optBoolean("enabled", false) && configured);
            config.put("endpoint", formatBarkEndpoint(config.optString("server"), config.optString("deviceKey")));
        } catch (JSONException ignored) {
            // JSONObject supports primitive fields.
        }
        return config;
    }

    private JSONObject saveBark(JSONObject body) throws Exception {
        JSONObject input = body == null ? new JSONObject() : body;
        boolean enabled = input.optBoolean("enabled", false);
        BarkEndpoint endpoint = input.has("endpoint")
                ? parseBarkEndpoint(input.optString("endpoint", ""))
                : new BarkEndpoint(normalizeBarkServer(input.optString("server", "")), input.optString("deviceKey", "").trim());
        String server = endpoint.server;
        String key = endpoint.deviceKey;
        int hour = input.optInt("dailySummaryHour", 9);
        if (server.isEmpty()) throw new NativeException("Bark 服务地址无效");
        if (hour < 0 || hour > 23) throw new NativeException("每日汇总小时必须在 0 到 23 之间");
        if (enabled && !validBarkKey(key)) throw new NativeException("Bark 设备 Key 无效");
        JSONObject config = copy(input);
        config.remove("endpoint");
        config.put("enabled", enabled);
        config.put("server", server);
        config.put("deviceKey", key);
        config.put("dailySummaryHour", hour);
        if (!store.saveBarkConfig(config)) throw new NativeException("Bark 配置保存失败");
        return ok(barkSummary());
    }

    private JSONObject testBark() throws Exception {
        JSONObject config = barkSummary();
        if (!config.optBoolean("enabled", false)) throw new NativeException("请先保存并启用 Bark 通知");
        sendBark("test", "iBox Bark 已连接", "通知推送已启用。", "active");
        return ok(new JSONObject());
    }

    private JSONObject tradePasswordSummary() throws JSONException {
        return ok(objectOf("configured", !store.getTradePassword().isEmpty()));
    }

    private JSONObject saveTradePassword(JSONObject body) throws Exception {
        String password = first(body, "tradePassword", "password").trim();
        if (password.isEmpty()) throw new NativeException("请输入交易密码");
        if (!store.saveTradePassword(password)) throw new NativeException("交易密码保存失败");
        return tradePasswordSummary();
    }

    private String requiredTradePassword(JSONObject legacyTask) throws NativeException {
        String password = store.getTradePassword();
        if (password.isEmpty()) password = first(legacyTask, "consignPassword", "paymentPassword").trim();
        if (password.isEmpty()) throw new NativeException("请先在设置中保存交易密码");
        return password;
    }

    private void refreshMarketWatches(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && now - lastMarketWatchAt < MARKET_WATCH_MIN_INTERVAL_MS) return;
        JSONArray watches = store.getMarketWatches();
        if (watches.length() == 0) return;
        boolean changed = false;
        for (int index = 0; index < watches.length(); index++) {
            JSONObject watch = watches.optJSONObject(index);
            if (watch == null || !"active".equals(watch.optString("status", "active"))) continue;
            try {
                JSONObject market = searchMarketsWithFallback(null, first(watch, "searchTerm", "name"), 1, 20);
                JSONObject match = matchMarketItem(market.optJSONArray("items"), watch.optString("collectionId"), watch.optString("name"));
                if (match == null || match.isNull("floorPrice")) {
                    watch.put("lastError", "market_item_not_found");
                    watch.put("updatedAt", Instant.now().toString());
                    changed = true;
                    continue;
                }
                double price = match.getDouble("floorPrice");
                boolean withinWindow = withinWatchWindow(watch, now);
                double latest = decimal(watch.opt("latestPrice"), Double.NaN);
                double reference = decimal(watch.opt("lastNotifiedPrice"), decimal(watch.opt("initialPrice"), latest));
                if (!Double.isFinite(reference) || reference <= 0d) reference = price;
                double change = reference == 0d ? 0d : (price - reference) / reference * 100d;
                watch.put("latestPrice", price);
                watch.put("lowestPrice", Math.min(decimal(watch.opt("lowestPrice"), price), price));
                watch.put("highestPrice", Math.max(decimal(watch.opt("highestPrice"), price), price));
                watch.put("cover", first(match, "cover", "coverUrl"));
                watch.put("lastObservedAt", Instant.now().toString());
                watch.put("updatedAt", Instant.now().toString());
                watch.put("lastError", "");
                double rise = threshold(watch.opt("riseThresholdPercent"), 3d);
                double fall = threshold(watch.opt("fallThresholdPercent"), 3d);
                if (!withinWindow) {
                    watch.put("lastNotifiedPrice", price);
                    watch.put("lastNotifiedAt", "");
                } else if (change >= rise || change <= -fall) {
                    String direction = change >= rise ? "上涨" : "下跌";
                    watch.put("lastNotifiedAt", Instant.now().toString());
                    watch.put("lastNotifiedPrice", price);
                    sendBark("always", "iBox 行情阈值提醒 · " + watch.optString("name"),
                            watch.optString("name") + " 当前市价 ¥" + price + "，较提醒基准 ¥" + reference + direction + " " + String.format(Locale.US, "%.2f", Math.abs(change)) + "%", "active");
                }
                changed = true;
            } catch (Exception error) {
                watch.put("lastError", message(error));
                watch.put("updatedAt", Instant.now().toString());
                changed = true;
            }
        }
        if (changed) store.saveMarketWatches(watches);
        lastMarketWatchAt = now;
    }

    private void processSynthesisTasks() {
        JSONArray tasks = store.getSynthesisTasks();
        boolean changed = false;
        long now = taskNow();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optBoolean("enabled", true) || !"scheduled".equals(task.optString("status"))) continue;
            long startAt = epoch(task.optString("startAt"));
            if (startAt <= 0L) {
                finishSynthesisTask(task, "failed", "合成任务开始时间无效");
                changed = true;
                continue;
            }
            if (now > startAt + TASK_MAX_LATE_MS) {
                finishSynthesisTask(task, "expired", "合成任务已超过允许执行时间");
                changed = true;
                continue;
            }
            if (now >= startAt - TASK_PREPARE_MS && !task.optBoolean("preflightDone", false)) {
                preflightSynthesisTask(task);
                changed = true;
            }
            if (now < startAt) continue;
            runSynthesisTask(task);
            changed = true;
        }
        if (changed) store.saveSynthesisTasks(tasks);
    }

    private void preflightSynthesisTask(JSONObject task) {
        JSONObject prepared = task.optJSONObject("preparedRequests");
        if (prepared == null) {
            prepared = new JSONObject();
            putQuietly(task, "preparedRequests", prepared);
        }
        JSONArray phones = executionPhones(task);
        for (int index = 0; index < phones.length(); index++) {
            String phone = phones.optString(index);
            if (prepared.optJSONObject(phone) != null) continue;
            try {
                JSONObject request = prepareSynthesis(phone, task.optString("syntheticId"), Math.max(1, task.optInt("syntheticNum", 1)));
                putQuietly(prepared, phone, request);
                taskRun(task, phone, "ready", "");
            } catch (Exception error) {
                taskRun(task, phone, "failed", message(error));
            }
        }
        putQuietly(task, "preflightDone", true);
        putQuietly(task, "preparedAt", Instant.now().toString());
    }

    private void runSynthesisTask(JSONObject task) {
        putQuietly(task, "status", "running");
        putQuietly(task, "startedAt", task.optString("startedAt", Instant.now().toString()));
        JSONObject prepared = task.optJSONObject("preparedRequests");
        if (prepared == null) {
            prepared = new JSONObject();
            putQuietly(task, "preparedRequests", prepared);
        }
        JSONObject results = new JSONObject();
        JSONArray phones = executionPhones(task);
        for (int index = 0; index < phones.length(); index++) {
            String phone = phones.optString(index);
            JSONObject request = prepared.optJSONObject(phone);
            if (request == null) {
                try {
                    request = prepareSynthesis(phone, task.optString("syntheticId"), Math.max(1, task.optInt("syntheticNum", 1)));
                    putQuietly(prepared, phone, request);
                    taskRun(task, phone, "ready", "");
                } catch (Exception error) {
                    taskRun(task, phone, "failed", message(error));
                    continue;
                }
            }
            try {
                taskRun(task, phone, "submitting", "");
                JSONObject result = submitPreparedSynthesis(phone, request);
                putQuietly(results, phone, result.opt("data"));
                taskRun(task, phone, "submitted", "");
            } catch (Exception error) {
                taskRun(task, phone, "failed", message(error));
            }
        }
        putQuietly(task, "result", results);
        finishSynthesisTask(task, "", "");
    }

    private void finishSynthesisTask(JSONObject task, String forcedStatus, String detail) {
        if (!forcedStatus.isEmpty()) {
            putQuietly(task, "status", forcedStatus);
            putQuietly(task, "lastResult", detail);
        } else {
            JSONObject runs = task.optJSONObject("runs");
            int submitted = 0;
            int total = 0;
            if (runs != null) {
                Iterator<String> phones = runs.keys();
                while (phones.hasNext()) {
                    JSONObject run = runs.optJSONObject(phones.next());
                    if (run == null) continue;
                    total++;
                    if ("submitted".equals(run.optString("status"))) submitted++;
                }
            }
            putQuietly(task, "status", submitted == total && submitted > 0 ? "submitted" : submitted > 0 ? "partial" : "failed");
            putQuietly(task, "lastResult", submitted > 0 ? "submitted" : "all_accounts_failed");
        }
        putQuietly(task, "enabled", false);
        putQuietly(task, "finishedAt", Instant.now().toString());
        putQuietly(task, "updatedAt", Instant.now().toString());
    }

    private void processFirstSaleTasks() {
        JSONArray tasks = store.getFirstSaleTasks();
        boolean changed = false;
        long now = taskNow();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optBoolean("enabled", true) || !"scheduled".equals(task.optString("status"))) continue;
            long startAt = epoch(task.optString("startAt"));
            if (startAt <= 0L) {
                finishFirstSaleTask(task, "failed", "首发任务开始时间无效");
                changed = true;
                continue;
            }
            if (!"immediate".equals(task.optString("mode")) && now > startAt + TASK_MAX_LATE_MS) {
                finishFirstSaleTask(task, "expired", "首发任务已超过允许执行时间");
                changed = true;
                continue;
            }
            if (!"immediate".equals(task.optString("mode")) && now >= startAt - TASK_PREPARE_MS && !task.optBoolean("preflightDone", false)) {
                preflightFirstSaleTask(task);
                changed = true;
            }
            if (now < startAt) continue;
            runFirstSaleTask(task);
            changed = true;
        }
        if (changed) store.saveFirstSaleTasks(tasks);
    }

    private void preflightFirstSaleTask(JSONObject task) {
        JSONArray phones = executionPhones(task);
        for (int index = 0; index < phones.length(); index++) {
            String phone = phones.optString(index);
            try {
                prepareFirstSaleTaskAccount(task, phone, new String[]{"scheduled", "immediate"});
                taskRun(task, phone, "ready", "");
            } catch (Exception error) {
                taskRun(task, phone, "failed", message(error));
            }
        }
        putQuietly(task, "preflightDone", true);
        putQuietly(task, "preparedAt", Instant.now().toString());
    }

    private void runFirstSaleTask(JSONObject task) {
        putQuietly(task, "status", "running");
        putQuietly(task, "startedAt", task.optString("startedAt", Instant.now().toString()));
        JSONArray phones = executionPhones(task);
        for (int index = 0; index < phones.length(); index++) {
            String phone = phones.optString(index);
            JSONObject existing = task.optJSONObject("runs") == null ? null : task.optJSONObject("runs").optJSONObject(phone);
            String runStatus = existing == null ? "" : existing.optString("status");
            if ("submitted".equals(runStatus) || "payment_pending".equals(runStatus)) continue;
            try {
                JSONObject plan = prepareFirstSaleTaskAccount(task, phone, "immediate");
                taskRun(task, phone, "submitting", "");
                JSONObject request = objectOf(
                        "phone", phone,
                        "saleId", plan.optString("saleId"),
                        "num", Math.max(1, task.optInt("num", 1)),
                        "paymentPlatformCode", plan.optInt("paymentPlatformCode", 0)
                );
                JSONObject result = submitFirstSale(request, task.optJSONObject("captcha"));
                Object order = result.opt("data");
                String orderUuid = deepString(order, "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
                if (orderUuid.isEmpty()) throw new NativeException("首发下单未返回订单编号");
                String cashier = deepString(order, "cashierLink", "link");
                String cashierMessage = "";
                if (cashier.isEmpty()) {
                    try {
                        cashier = cashierLink(account(phone), orderUuid, 0);
                    } catch (Exception error) {
                        cashierMessage = message(error);
                    }
                }
                taskRun(task, phone, "payment_pending", cashierMessage,
                        objectOf("orderUuid", orderUuid, "cashierLink", cashier, "paymentStatus", "pending", "result", order));
                sendBarkBestEffort(task, "notifyPaymentPending", "iBox 首发订单待支付", task.optString("title") + " · 账号 " + phone + " · 订单 " + orderUuid, "active", cashier);
            } catch (Exception error) {
                if (captchaRequired(error)) {
                    taskRun(task, phone, "verification_required", message(error));
                    putQuietly(task, "status", "verification_required");
                    putQuietly(task, "lastResult", "first_sale_verification_required");
                } else {
                    taskRun(task, phone, "failed", message(error));
                }
            }
        }
        if ("verification_required".equals(task.optString("status"))) {
            putQuietly(task, "enabled", true);
            putQuietly(task, "updatedAt", Instant.now().toString());
            return;
        }
        finishFirstSaleTask(task, "", "");
    }

    private void finishFirstSaleTask(JSONObject task, String forcedStatus, String detail) {
        if (!forcedStatus.isEmpty()) {
            putQuietly(task, "status", forcedStatus);
            putQuietly(task, "lastResult", detail);
            putQuietly(task, "enabled", false);
        } else {
            JSONObject runs = task.optJSONObject("runs");
            int total = 0;
            int pending = 0;
            int submitted = 0;
            if (runs != null) {
                Iterator<String> phones = runs.keys();
                while (phones.hasNext()) {
                    JSONObject run = runs.optJSONObject(phones.next());
                    if (run == null) continue;
                    total++;
                    if ("payment_pending".equals(run.optString("status"))) pending++;
                    if ("submitted".equals(run.optString("status"))) submitted++;
                }
            }
            String status = pending + submitted == total && pending > 0 ? "payment_pending"
                    : pending + submitted > 0 ? "partial"
                    : submitted == total && submitted > 0 ? "submitted" : "failed";
            putQuietly(task, "status", status);
            putQuietly(task, "lastResult", status);
            putQuietly(task, "enabled", false);
        }
        putQuietly(task, "finishedAt", Instant.now().toString());
        putQuietly(task, "updatedAt", Instant.now().toString());
    }

    private void processQuantStrategies() {
        JSONArray strategies = store.getQuantStrategies();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !strategy.optBoolean("enabled", false)
                    || !"monitoring".equals(strategy.optString("status")) || !quantDue(strategy, now)) continue;
            try {
                IBoxDirectClient.Account account = account(strategy.optString("phone"));
                JSONObject evaluated = evaluateQuantStrategy(strategy, account);
                applyQuantSnapshot(strategy, evaluated);
                String signal = evaluated.optString("signal");
                if (evaluated.optBoolean("volatilityGuardBlocked", false)) {
                    putQuietly(strategy, "enabled", false);
                    putQuietly(strategy, "status", "volatility_paused");
                    putQuietly(strategy, "lastResult", "price_volatility_paused");
                    addEvent(strategy, "volatility_paused", "单周期波动 " + String.format(Locale.US, "%.2f", decimal(evaluated.opt("volatilityPercent"), 0d)) + "%");
                    sendBarkBestEffort(strategy, "notifyStrategyPaused", "iBox 量化策略已暂停", strategy.optString("title") + " · 行情波动触发熔断", "active");
                } else if (!evaluated.optString("sellPriceRangeError").isEmpty()) {
                    putQuietly(strategy, "enabled", false);
                    putQuietly(strategy, "status", "price_out_of_range");
                    putQuietly(strategy, "lastResult", evaluated.optString("sellPriceRangeError"));
                    addEvent(strategy, "price_range_blocked", strategy.optString("lastResult"));
                    sendBarkBestEffort(strategy, "notifyStrategyPaused", "iBox 量化策略已暂停", strategy.optString("title") + " · " + strategy.optString("lastResult"), "active");
                } else {
                    boolean profitBlocked = evaluated.optBoolean("profitGuardBlocked", false);
                    boolean collectingHistory = "smart_value_buy".equals(strategy.optString("strategyPreset"))
                            && !evaluated.optBoolean("historyReady", false);
                    putQuietly(strategy, "status", "monitoring");
                    putQuietly(strategy, "lastResult", collectingHistory ? "collecting_price_history"
                            : profitBlocked ? "profit_below_minimum" : "signal_updated");
                    if (profitBlocked) addEvent(strategy, "profit_guard_blocked", "预估净利低于设置阈值");
                    if ("live".equals(strategy.optString("executionMode")) && !"idle".equals(signal)) {
                        JSONObject detail = evaluated.optJSONObject("detail");
                        if ("buy".equals(signal) && explicitFalse(detail == null ? null : detail.opt("enablePurchase"))) {
                            throw new NativeException("当前藏品暂不支持量化买入");
                        }
                        if (("sell".equals(signal) || "stop_loss".equals(signal)) && explicitFalse(detail == null ? null : detail.opt("enableConsignment"))) {
                            throw new NativeException("当前藏品暂不支持量化寄售");
                        }
                        boolean submitted;
                        if ("buy".equals(signal)) {
                            submitted = tryQuantBuy(strategy, account, evaluated.optJSONObject("buyCandidate"));
                        } else {
                            submitted = tryQuantSell(strategy, account, evaluated.optJSONObject("sellAsset"), decimal(evaluated.opt("sellPrice"), Double.NaN), evaluated.optJSONObject("publicConfig"));
                        }
                        if (submitted) {
                            putQuietly(strategy, "triggeredAt", Instant.now().toString());
                            putQuietly(strategy, "pendingAction", "");
                            addEvent(strategy, "buy".equals(signal) ? "buy_submitted" : "stop_loss".equals(signal) ? "stop_loss_submitted" : "sell_submitted",
                                    "buy".equals(signal) ? "已创建买入订单，等待钱包支付" : "寄售订单已提交");
                        }
                    }
                }
                putQuietly(strategy, "consecutiveFailures", 0);
                putQuietly(strategy, "lastFailureAt", "");
                putQuietly(strategy, "lastRetryAfterMs", 0);
                putQuietly(strategy, "nextCheckAt", "");
            } catch (Exception error) {
                String failure = message(error);
                putQuietly(strategy, "lastResult", failure);
                if (captchaRequired(error)) {
                    putQuietly(strategy, "status", "verification_required");
                    putQuietly(strategy, "enabled", true);
                    putQuietly(strategy, "nextCheckAt", "");
                    addEventQuietly(strategy, "verification_required", failure);
                } else if (failure.contains("价格") && (failure.contains("下限") || failure.contains("上限") || failure.contains("整数"))) {
                    putQuietly(strategy, "enabled", false);
                    putQuietly(strategy, "status", "price_out_of_range");
                    putQuietly(strategy, "nextCheckAt", "");
                    addEventQuietly(strategy, "price_range_blocked", failure);
                } else if (failure.contains("交易密码")) {
                    putQuietly(strategy, "status", "needs_sell_password");
                    putQuietly(strategy, "nextCheckAt", "");
                    addEventQuietly(strategy, "password_required", failure);
                } else if (failure.contains("暂不支持") || failure.contains("账号不存在")) {
                    putQuietly(strategy, "enabled", false);
                    putQuietly(strategy, "status", "blocked");
                    putQuietly(strategy, "nextCheckAt", "");
                    addEventQuietly(strategy, "blocked", failure);
                } else {
                    int failures = Math.min(6, Math.max(0, strategy.optInt("consecutiveFailures", 0)) + 1);
                    long delay = Math.min(60_000L, 1000L * (1L << Math.min(5, failures)));
                    putQuietly(strategy, "status", "monitoring");
                    putQuietly(strategy, "consecutiveFailures", failures);
                    putQuietly(strategy, "lastFailureAt", Instant.now().toString());
                    putQuietly(strategy, "lastRetryAfterMs", delay);
                    putQuietly(strategy, "nextCheckAt", Instant.ofEpochMilli(System.currentTimeMillis() + delay).toString());
                    addEventQuietly(strategy, "retry_scheduled", failure);
                }
                sendBarkBestEffort(strategy, "notifyApiError", "iBox 量化接口异常", strategy.optString("title") + " · " + failure, "active");
                if (!strategy.optBoolean("enabled", false)) {
                    sendBarkBestEffort(strategy, "notifyStrategyPaused", "iBox 量化策略已暂停", strategy.optString("title") + " · " + failure, "active");
                }
            }
            putQuietly(strategy, "lastCheckAt", Instant.now().toString());
            putQuietly(strategy, "updatedAt", Instant.now().toString());
            changed = true;
        }
        if (changed) store.saveQuantStrategies(strategies);
    }

    private boolean quantDue(JSONObject strategy, long now) {
        long next = epoch(strategy.optString("nextCheckAt"));
        if (next > now) return false;
        long triggeredAt = epoch(strategy.optString("triggeredAt"));
        int cooldown = Math.max(0, strategy.optInt("cooldownMinutes", 0));
        if (triggeredAt > 0L && cooldown > 0 && now < triggeredAt + cooldown * 60_000L) return false;
        return taskDue(strategy, "intervalValue", "intervalUnit", 15, now);
    }

    private JSONObject evaluateQuantStrategy(JSONObject strategy, IBoxDirectClient.Account account) throws Exception {
        JSONObject detail = loadMarketTradeDetail(account, strategy.optString("groupId"));
        JSONArray listings = marketListings(account, strategy.optString("groupId"));
        JSONArray owned = ownedCollections(account, strategy.optString("groupId"));
        String preset = strategy.optString("strategyPreset", "custom");
        JSONObject buy = strategy.optJSONObject("buy");
        if (buy == null) buy = new JSONObject();
        JSONObject sell = strategy.optJSONObject("sell");
        if (sell == null) sell = new JSONObject();
        JSONObject stopLoss = strategy.optJSONObject("stopLoss");
        if (stopLoss == null) stopLoss = new JSONObject();
        int holdings = ownedQuantity(owned);
        JSONObject lowest = lowestListing(listings);
        double lowestPrice = lowest == null ? Double.NaN : decimal(lowest.opt("price"), Double.NaN);
        double detailFloor = decimal(detail.opt("floorPrice"), Double.NaN);
        double marketPrice = Double.isFinite(lowestPrice) ? lowestPrice : detailFloor;
        JSONArray priceHistory = "smart_value_buy".equals(preset)
                ? appendQuantPrice(strategy.optJSONArray("priceHistory"), marketPrice, strategy.optInt("priceHistoryLimit", QUANT_PRICE_HISTORY_LIMIT))
                : null;
        int minimumSamples = strategy.optInt("minimumHistorySamples", 6);
        boolean historyReady = priceHistory != null && priceHistory.length() >= minimumSamples;
        double fairPrice = historyReady ? quantMedian(priceHistory) : Double.NaN;
        double assetCost = ("cost_profit_exit".equals(preset) || "trailing_profit_exit".equals(preset))
                ? store.getAssetCost(strategy.optString("phone"), strategy.optString("groupId")) : Double.NaN;
        double highestObservedPrice = Double.NaN;
        if ("trailing_profit_exit".equals(preset) && Double.isFinite(marketPrice) && marketPrice > 0d) {
            highestObservedPrice = Math.max(marketPrice, decimal(strategy.opt("highestObservedPrice"), marketPrice));
        }
        JSONObject publicConfig = (buy.optBoolean("enabled", false) || sell.optBoolean("enabled", false) || stopLoss.optBoolean("enabled", false))
                ? marketTradePublicConfig(account) : new JSONObject();
        JSONObject buyCandidate = null;
        double buyMaximum = decimal(buy.opt("maxPrice"), Double.NaN);
        double entryMaximum = buyMaximum;
        if ("smart_value_buy".equals(preset)) {
            double discount = decimal(strategy.opt("entryDiscountPercent"), 8d);
            entryMaximum = historyReady && Double.isFinite(fairPrice)
                    ? Math.min(buyMaximum, fairPrice * (1d - discount / 100d)) : Double.NaN;
        }
        if (buy.optBoolean("enabled", false) && Double.isFinite(entryMaximum) && entryMaximum > 0d) {
            for (int index = 0; index < listings.length(); index++) {
                JSONObject listing = listings.optJSONObject(index);
                double price = listing == null ? Double.NaN : decimal(listing.opt("price"), Double.NaN);
                if (listing == null || listing.optBoolean("locked", false) || !Double.isFinite(price) || price > entryMaximum
                        || ownsCollection(owned, listing.optString("digitalCollectionId"))) continue;
                if (buyCandidate == null || price < decimal(buyCandidate.opt("price"), Double.NaN)) buyCandidate = listing;
            }
        }
        double minimumProfit = Math.max(0d, decimal(strategy.opt("minNetProfit"), 0d));
        int sellQuantity = Math.max(1, sell.optInt("quantity", 1));
        double configuredSellPrice = decimal(sell.opt("sellPrice"), Double.NaN);
        double dynamicSellTargetPrice = Double.NaN;
        double estimatedProfit = Double.NaN;
        boolean profitBlocked = false;
        boolean stopLossReady = false;
        boolean sellReady = false;
        boolean trailingActivated = false;
        if ("smart_value_buy".equals(preset)) {
            estimatedProfit = buyCandidate == null || !Double.isFinite(fairPrice) ? Double.NaN
                    : fairPrice * QUANT_NET_PROCEEDS_RATE - decimal(buyCandidate.opt("price"), Double.NaN);
            profitBlocked = Double.isFinite(estimatedProfit) && estimatedProfit < minimumProfit;
        } else if ("cost_profit_exit".equals(preset)) {
            dynamicSellTargetPrice = Math.ceil((assetCost + minimumProfit) / QUANT_NET_PROCEEDS_RATE);
            sellReady = sell.optBoolean("enabled", false) && holdings >= sellQuantity
                    && Double.isFinite(marketPrice) && marketPrice >= dynamicSellTargetPrice;
            estimatedProfit = Double.isFinite(dynamicSellTargetPrice)
                    ? dynamicSellTargetPrice * QUANT_NET_PROCEEDS_RATE - assetCost : Double.NaN;
        } else if ("trailing_profit_exit".equals(preset)) {
            double activationPercent = decimal(strategy.opt("trailingActivationPercent"), 10d);
            double drawdownPercent = decimal(strategy.opt("trailingDrawdownPercent"), 5d);
            double retentionPercent = decimal(strategy.opt("trailingRetentionPercent"), 3d);
            double retainedProfit = Math.max(minimumProfit, assetCost * retentionPercent / 100d);
            double protectedSellPrice = Math.ceil((assetCost + retainedProfit) / QUANT_NET_PROCEEDS_RATE);
            trailingActivated = Double.isFinite(highestObservedPrice)
                    && highestObservedPrice * QUANT_NET_PROCEEDS_RATE >= assetCost * (1d + activationPercent / 100d);
            double executableSellPrice = Double.isFinite(marketPrice) ? Math.floor(marketPrice) : Double.NaN;
            sellReady = sell.optBoolean("enabled", false) && holdings >= sellQuantity && trailingActivated
                    && marketPrice <= highestObservedPrice * (1d - drawdownPercent / 100d)
                    && executableSellPrice >= protectedSellPrice;
            dynamicSellTargetPrice = sellReady ? executableSellPrice : protectedSellPrice;
            estimatedProfit = sellReady ? executableSellPrice * QUANT_NET_PROCEEDS_RATE - assetCost : Double.NaN;
        } else {
            estimatedProfit = buyCandidate == null || !sell.optBoolean("enabled", false) || !Double.isFinite(configuredSellPrice)
                    ? Double.NaN : configuredSellPrice * QUANT_NET_PROCEEDS_RATE - decimal(buyCandidate.opt("price"), Double.NaN);
            profitBlocked = Double.isFinite(estimatedProfit) && estimatedProfit < minimumProfit;
            stopLossReady = stopLoss.optBoolean("enabled", false)
                    && decimal(stopLoss.opt("triggerPrice"), Double.NaN) > 0d
                    && decimal(stopLoss.opt("sellPrice"), Double.NaN) > 0d
                    && holdings >= sellQuantity && Double.isFinite(marketPrice)
                    && marketPrice <= decimal(stopLoss.opt("triggerPrice"), Double.NaN);
            sellReady = sell.optBoolean("enabled", false) && decimal(sell.opt("minPrice"), Double.NaN) > 0d
                    && configuredSellPrice > 0d && holdings >= sellQuantity && Double.isFinite(marketPrice)
                    && marketPrice >= decimal(sell.opt("minPrice"), Double.NaN);
        }
        String sellAction = stopLossReady ? "stop_loss" : sellReady ? "sell" : "";
        JSONObject sellAsset = sellAction.isEmpty() ? null : firstOwnedAsset(owned, sellQuantity);
        double volatility = quantVolatility(decimal(strategy.opt("latestFloorPrice"), Double.NaN), marketPrice);
        double limit = threshold(strategy.opt("volatilityLimitPercent"), 0d);
        boolean volatilityBlocked = !stopLossReady && limit > 0d && Double.isFinite(volatility) && volatility >= limit;
        int maxPosition = Math.max(1, strategy.optInt("maxPosition", 1));
        String signal = stopLossReady ? "stop_loss" : volatilityBlocked ? "idle"
                : buyCandidate != null && !profitBlocked && holdings < maxPosition ? "buy" : sellReady ? "sell" : "idle";
        double actionSellPrice = "stop_loss".equals(signal) ? decimal(stopLoss.opt("sellPrice"), Double.NaN)
                : "sell".equals(signal) && ("cost_profit_exit".equals(preset) || "trailing_profit_exit".equals(preset))
                ? dynamicSellTargetPrice : "sell".equals(signal) ? configuredSellPrice : Double.NaN;
        String sellPriceRangeError = Double.isFinite(actionSellPrice) ? marketTradeConsignmentPriceError(actionSellPrice, publicConfig) : "";
        return objectOf(
                "detail", detail,
                "listings", listings,
                "owned", owned,
                "holdings", holdings,
                "lowestListingPrice", Double.isFinite(lowestPrice) ? lowestPrice : JSONObject.NULL,
                "marketPrice", Double.isFinite(marketPrice) ? marketPrice : JSONObject.NULL,
                "priceHistory", priceHistory == null ? JSONObject.NULL : priceHistory,
                "historySampleCount", priceHistory == null ? 0 : priceHistory.length(),
                "historyReady", historyReady,
                "fairPrice", Double.isFinite(fairPrice) ? fairPrice : JSONObject.NULL,
                "entryMaximum", Double.isFinite(entryMaximum) ? entryMaximum : JSONObject.NULL,
                "assetCost", Double.isFinite(assetCost) ? assetCost : JSONObject.NULL,
                "highestObservedPrice", Double.isFinite(highestObservedPrice) ? highestObservedPrice : JSONObject.NULL,
                "trailingActivated", trailingActivated,
                "dynamicSellTargetPrice", Double.isFinite(dynamicSellTargetPrice) ? dynamicSellTargetPrice : JSONObject.NULL,
                "buyCandidate", buyCandidate == null ? JSONObject.NULL : buyCandidate,
                "estimatedNetProfit", Double.isFinite(estimatedProfit) ? estimatedProfit : JSONObject.NULL,
                "profitGuardBlocked", profitBlocked,
                "sellAsset", sellAsset == null ? JSONObject.NULL : sellAsset,
                "sellPrice", Double.isFinite(actionSellPrice) ? actionSellPrice : JSONObject.NULL,
                "sellPriceRangeError", sellPriceRangeError,
                "publicConfig", publicConfig,
                "volatilityPercent", Double.isFinite(volatility) ? volatility : JSONObject.NULL,
                "volatilityGuardBlocked", volatilityBlocked,
                "signal", signal
        );
    }

    private void applyQuantSnapshot(JSONObject strategy, JSONObject evaluated) {
        putQuietly(strategy, "holdings", evaluated.optInt("holdings", 0));
        JSONArray listings = evaluated.optJSONArray("listings");
        putQuietly(strategy, "listingCount", listings == null ? 0 : listings.length());
        putQuietly(strategy, "lowestListingPrice", evaluated.opt("lowestListingPrice"));
        putQuietly(strategy, "latestFloorPrice", evaluated.opt("marketPrice"));
        putQuietly(strategy, "lastVolatilityPercent", evaluated.opt("volatilityPercent"));
        putQuietly(strategy, "platformPriceRange", evaluated.optJSONObject("publicConfig"));
        JSONArray priceHistory = evaluated.optJSONArray("priceHistory");
        if (priceHistory != null) {
            putQuietly(strategy, "priceHistory", priceHistory);
            putQuietly(strategy, "historySampleCount", evaluated.optInt("historySampleCount", priceHistory.length()));
            putQuietly(strategy, "historyReady", evaluated.optBoolean("historyReady", false));
            putQuietly(strategy, "fairPrice", evaluated.opt("fairPrice"));
            putQuietly(strategy, "entryMaximum", evaluated.opt("entryMaximum"));
        }
        if (!evaluated.isNull("assetCost")) putQuietly(strategy, "assetCost", evaluated.opt("assetCost"));
        if (!evaluated.isNull("highestObservedPrice")) putQuietly(strategy, "highestObservedPrice", evaluated.opt("highestObservedPrice"));
        if (!evaluated.isNull("dynamicSellTargetPrice")) putQuietly(strategy, "dynamicSellTargetPrice", evaluated.opt("dynamicSellTargetPrice"));
        putQuietly(strategy, "trailingActivated", evaluated.optBoolean("trailingActivated", false));
        putQuietly(strategy, "estimatedNetProfit", evaluated.opt("estimatedNetProfit"));
        putQuietly(strategy, "lastSignal", evaluated.optString("signal"));
        String signal = evaluated.optString("signal");
        Object signalPrice = "buy".equals(signal) && evaluated.optJSONObject("buyCandidate") != null
                ? evaluated.optJSONObject("buyCandidate").opt("price") : evaluated.opt("marketPrice");
        putQuietly(strategy, "lastSignalPrice", signalPrice);
        JSONObject buy = strategy.optJSONObject("buy");
        if (buy != null) putQuietly(buy, "signal", "buy".equals(signal) ? "triggered" : "waiting");
        JSONObject sell = strategy.optJSONObject("sell");
        if (sell != null) putQuietly(sell, "signal", ("sell".equals(signal) || "stop_loss".equals(signal)) ? "triggered" : "waiting");
    }

    private void processTradeTasks() {
        processTradeTasks("", null);
    }

    private void processTradeTasks(String preflightTaskId, JSONObject preflightPlan) {
        JSONArray tasks = store.getTradeTasks();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            String status = task == null ? "" : task.optString("status");
            int fallbackSeconds = task != null && "retired_market".equals(task.optString("localType")) ? 3 : 5;
            if (task == null || !task.optBoolean("enabled", false)
                    || !("scheduled".equals(status) || "waiting_price".equals(status))
                    || !taskDue(task, "monitorIntervalValue", "monitorIntervalUnit", fallbackSeconds, now)) continue;
            try {
                if ("retired_market".equals(task.optString("localType"))) {
                    if ("immediate_purchase".equals(task.optString("executionMode"))) processImmediatePurchaseTask(task);
                    else processRetiredMarketTask(task);
                } else {
                    processMarketTradeTask(task, task.optString("id").equals(preflightTaskId) ? preflightPlan : null);
                }
            } catch (Exception error) {
                String failure = message(error);
                putQuietly(task, "lastResult", failure);
                if (captchaRequired(error)) {
                    putQuietly(task, "status", "verification_required");
                } else if (task.optBoolean("immediate", false)) {
                    putQuietly(task, "enabled", false);
                    putQuietly(task, "status", "failed");
                } else if ("immediate_purchase".equals(task.optString("executionMode"))) {
                    putQuietly(task, "enabled", false);
                    putQuietly(task, "status", "failed");
                } else if (failure.contains("寄售价格") || failure.contains("价格低于平台") || failure.contains("价格高于平台")) {
                    putQuietly(task, "enabled", false);
                    putQuietly(task, "status", "price_out_of_range");
                } else if (failure.contains("暂不支持") || failure.contains("账号不存在")) {
                    putQuietly(task, "enabled", false);
                    putQuietly(task, "status", "blocked");
                }
                putQuietly(task, "lastCheckAt", Instant.now().toString());
                sendBarkBestEffort(task, "notifyApiError", "iBox 交易接口异常", task.optString("title") + " · " + failure, "active");
            }
            putQuietly(task, "updatedAt", Instant.now().toString());
            changed = true;
        }
        if (changed) store.saveTradeTasks(tasks);
    }

    private void processMarketTradeTask(JSONObject task) throws Exception {
        processMarketTradeTask(task, null);
    }

    private void processMarketTradeTask(JSONObject task, JSONObject preflightPlan) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        String type = task.optString("type");
        JSONObject plan = preflightPlan == null ? preflightMarketTradeTask(task) : preflightPlan;
        applyMarketTradePreflight(task, plan, true);
        JSONObject detail = plan.optJSONObject("detail");
        if (marketTradePriceGate(task, detail)) return;
        if ("consignment".equals(type)) {
            String collectionId = plan.optString("digitalCollectionId");
            int paymentCode = plan.optInt("paymentPlatformCode", 0);
            String password = requiredTradePassword(task);
            if (collectionId.isEmpty() || paymentCode <= 0) throw new NativeException("寄售预检结果无效");
            JSONObject request = objectOf(
                    "digitalCollectionId", integer(collectionId, 0),
                    "price", decimal(task.opt("price"), 0d),
                    "paymentPlatformCodes", new JSONArray().put(paymentCode),
                    "consignPassword", password
            );
            JSONObject response = client.requestAuthenticatedWithCaptcha(account, "POST", MARKET_CONSIGNMENT_ORDER_URL, request, null, IBoxDirectClient.CaptchaResult.fromJson(task.optJSONObject("captcha")), true, "提交寄售");
            Object result = data(response);
            task.put("listingOrderItemId", deepString(result, "listingOrderItemId"));
            task.put("result", result);
            task.put("enabled", false);
            task.put("status", "submitted");
            task.put("lastResult", "submitted");
            task.put("paymentStatus", "submitted");
            task.put("submittedAt", Instant.now().toString());
            sendBarkBestEffort(task, "notifyConsignmentSuccess", "iBox 寄售已提交", task.optString("title") + " · 寄售价 ¥" + task.optString("price"), "active");
            return;
        }
        if (!"wanted".equals(type)) throw new NativeException("交易任务类型无效");
        int paymentCode = plan.optInt("paymentPlatformCode", 0);
        if (paymentCode <= 0) throw new NativeException("求购预检未返回支付通道");
        String orderUuid = task.optString("orderUuid");
        if (orderUuid.isEmpty()) {
            JSONObject request = objectOf(
                    "groupId", integer(task.opt("groupId"), 0),
                    "buyCount", Math.max(1, task.optInt("quantity", 1)),
                    "price", decimal(task.opt("price"), 0d),
                    "paymentPlatformCode", paymentCode
            );
            JSONObject response = client.requestAuthenticatedWithCaptcha(account, "POST", MARKET_ADVANCE_ORDER_URL, request, null, IBoxDirectClient.CaptchaResult.fromJson(task.optJSONObject("captcha")), true, "提交求购");
            orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
            if (orderUuid.isEmpty()) throw new NativeException("求购订单未返回订单编号");
            task.put("orderUuid", orderUuid);
            task.put("paymentStatus", "created");
        }
        String cashier = task.optString("cashierLink");
        String cashierError = "";
        if (cashier.isEmpty()) {
            try {
                cashier = cashierLink(account, orderUuid, 2);
            } catch (Exception error) {
                cashierError = message(error);
            }
        }
        task.put("orderUuid", orderUuid);
        task.put("cashierLink", cashier);
        task.put("paymentStatus", cashier.isEmpty() ? "unavailable" : "pending");
        task.put("cashierError", cashierError);
        task.put("enabled", false);
        task.put("status", "payment_pending");
        task.put("lastResult", "payment_pending");
        task.put("submittedAt", Instant.now().toString());
        try {
            sendBark("notifyPaymentPending", "iBox 求购待支付", task.optString("title") + " · 订单 " + orderUuid + " 已创建，等待钱包支付。", "active", cashier);
        } catch (Exception error) {
            task.put("notificationError", message(error));
        }
    }

    private void processRetiredMarketTask(JSONObject task) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        JSONArray baseline = task.optJSONArray("baselineListingKeys");
        if (baseline == null || task.optString("baselineEmptyAt").isEmpty()) throw new NativeException("捡漏监控基线缺失，请重新预检任务");
        JSONArray listings = marketListings(account, task.optString("groupId"));
        double min = decimal(task.opt("minPrice"), 0d);
        double max = decimal(task.opt("maxPrice"), Double.NaN);
        if (!Double.isFinite(max) || max <= 0d) throw new NativeException("捡漏任务价格范围无效");
        JSONObject candidate = null;
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            if (listing == null || listing.optBoolean("locked", false) || contains(baseline, listingKey(listing))) continue;
            double price = decimal(listing.opt("price"), Double.NaN);
            if (Double.isFinite(price) && price >= min && price <= max) {
                candidate = listing;
                break;
            }
        }
        task.put("lastCheckAt", Instant.now().toString());
        if (candidate == null) {
            task.put("status", "waiting_price");
            task.put("lastResult", "waiting_price");
            return;
        }
        int paymentCode = paymentPlatformCode(account, 1, 0);
        JSONObject request = objectOf(
                "digitalCollectionId", integer(candidate.opt("digitalCollectionId"), 0),
                "paymentPlatformCode", paymentCode
        );
        JSONObject response = client.requestAuthenticatedWithCaptcha(account, "POST", MARKET_PURCHASE_CONSIGNMENT_URL, request, null, IBoxDirectClient.CaptchaResult.fromJson(task.optJSONObject("captcha")), true, "锁定捡漏订单");
        String orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
        if (orderUuid.isEmpty()) throw new NativeException("捡漏订单未返回订单编号");
        String cashier = "";
        String cashierError = "";
        try {
            cashier = cashierLink(account, orderUuid, 0);
        } catch (Exception error) {
            cashierError = message(error);
        }
        task.put("pendingDigitalCollectionId", "");
        task.put("pendingPrice", JSONObject.NULL);
        task.put("lockedDigitalCollectionId", candidate.optString("digitalCollectionId"));
        task.put("lockedPrice", candidate.opt("price"));
        task.put("orderUuid", orderUuid);
        task.put("cashierLink", cashier);
        task.put("paymentStatus", cashier.isEmpty() ? "unavailable" : "pending");
        task.put("enabled", false);
        task.put("status", "payment_pending");
        task.put("lastResult", cashier.isEmpty() ? cashierError : "payment_pending");
        task.put("lockedAt", Instant.now().toString());
        sendBarkBestEffort(task, "notifyLockSuccess", "iBox 捡漏锁单成功", task.optString("title") + " · ¥" + candidate.optString("price"), "active");
        sendBarkBestEffort(task, "notifyPaymentPending", "iBox 捡漏订单待支付", task.optString("title") + " · ¥" + candidate.optString("price") + "，等待钱包支付。", "active", cashier);
    }

    private void processImmediatePurchaseTask(JSONObject task) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        String groupId = task.optString("groupId");
        String digitalCollectionId = task.optString("pendingDigitalCollectionId");
        double maxPrice = decimal(task.opt("maxPrice"), Double.NaN);
        JSONObject candidate = currentPurchaseCandidate(account, groupId, digitalCollectionId, maxPrice);
        int paymentCode = paymentPlatformCode(account, 1, 0);
        JSONObject request = objectOf(
                "digitalCollectionId", integer(candidate.opt("digitalCollectionId"), 0),
                "paymentPlatformCode", paymentCode
        );
        JSONObject response = client.requestAuthenticatedWithCaptcha(account, "POST", MARKET_PURCHASE_CONSIGNMENT_URL, request, null, IBoxDirectClient.CaptchaResult.fromJson(task.optJSONObject("captcha")), true, "立即买入");
        String orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
        if (orderUuid.isEmpty()) throw new NativeException("立即买入未返回订单编号");

        String cashier = "";
        String cashierError = "";
        try {
            cashier = cashierLink(account, orderUuid, 0);
        } catch (Exception error) {
            cashierError = message(error);
        }
        task.put("resolvedPaymentPlatformCode", paymentCode);
        task.put("lockedDigitalCollectionId", candidate.optString("digitalCollectionId"));
        task.put("lockedPrice", candidate.opt("price"));
        task.put("orderUuid", orderUuid);
        task.put("cashierLink", cashier);
        task.put("paymentStatus", cashier.isEmpty() ? "unavailable" : "pending");
        task.put("enabled", false);
        task.put("status", "payment_pending");
        task.put("lastResult", cashier.isEmpty() ? cashierError : "payment_pending");
        task.put("lockedAt", Instant.now().toString());
        task.put("updatedAt", Instant.now().toString());
        addEvent(task, "payment_pending", "订单已创建，等待钱包支付");
        sendBarkBestEffort(task, "notifyLockSuccess", "iBox 立即买入锁单成功", task.optString("title") + " · ¥" + candidate.optString("price"), "active");
        sendBarkBestEffort(task, "notifyPaymentPending", "iBox 立即买入待支付", task.optString("title") + " · 订单 " + orderUuid, "active", cashier);
    }

    private boolean tryQuantBuy(JSONObject strategy, IBoxDirectClient.Account account, JSONObject candidate) throws Exception {
        JSONObject buy = strategy.optJSONObject("buy");
        if (buy == null || !buy.optBoolean("enabled", false)) return false;
        if (candidate == null || candidate.optBoolean("locked", false)) return false;
        String digitalCollectionId = candidate.optString("digitalCollectionId");
        if (!digitalCollectionId.matches("\\d+")) throw new NativeException("量化买入挂单实例编号无效");
        double ceiling = decimal(buy.opt("maxPrice"), Double.NaN);
        double price = decimal(candidate.opt("price"), Double.NaN);
        if (!Double.isFinite(ceiling) || !Double.isFinite(price) || price > ceiling) return false;
        double minimumNetProfit = Math.max(0d, decimal(strategy.opt("minNetProfit"), 0d));
        if ("smart_value_buy".equals(strategy.optString("strategyPreset"))) {
            double fairPrice = decimal(strategy.opt("fairPrice"), Double.NaN);
            double discount = decimal(strategy.opt("entryDiscountPercent"), Double.NaN);
            if (!strategy.optBoolean("historyReady", false) || !Double.isFinite(fairPrice) || !Double.isFinite(discount)
                    || price > fairPrice * (1d - discount / 100d)
                    || fairPrice * QUANT_NET_PROCEEDS_RATE - price < minimumNetProfit) {
                strategy.put("lastResult", "profit_below_minimum");
                return false;
            }
        } else {
            JSONObject sell = strategy.optJSONObject("sell");
            double targetSellPrice = sell == null ? Double.NaN : decimal(sell.opt("sellPrice"), Double.NaN);
            if (minimumNetProfit > 0d && Double.isFinite(targetSellPrice)
                    && targetSellPrice * QUANT_NET_PROCEEDS_RATE - price < minimumNetProfit) {
                strategy.put("lastResult", "profit_below_minimum");
                return false;
            }
        }
        int paymentCode = paymentPlatformCode(account, 1, 0);
        JSONObject request = objectOf("digitalCollectionId", integer(digitalCollectionId, 0), "paymentPlatformCode", paymentCode);
        JSONObject response = client.requestAuthenticatedWithCaptcha(account, "POST", MARKET_PURCHASE_CONSIGNMENT_URL, request, null, IBoxDirectClient.CaptchaResult.fromJson(strategy.optJSONObject("captcha")), true, "量化买入");
        String orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
        if (orderUuid.isEmpty()) throw new NativeException("量化买入未返回订单编号");
        String cashier = "";
        String cashierError = "";
        try {
            cashier = cashierLink(account, orderUuid, 0);
        } catch (Exception error) {
            cashierError = message(error);
        }
        strategy.put("orderUuid", orderUuid);
        strategy.put("cashierLink", cashier);
        strategy.put("paymentStatus", cashier.isEmpty() ? "unavailable" : "pending");
        strategy.put("enabled", false);
        strategy.put("status", "payment_pending");
        strategy.put("lastResult", cashier.isEmpty() ? cashierError : "payment_pending");
        strategy.put("submittedAt", Instant.now().toString());
        sendBarkBestEffort(strategy, "notifyLockSuccess", "iBox 量化锁单成功", strategy.optString("title") + " · ¥" + candidate.optString("price"), "active");
        sendBarkBestEffort(strategy, "notifyPaymentPending", "iBox 量化订单待支付", strategy.optString("title") + " · 订单 " + orderUuid, "active", cashier);
        return true;
    }

    private boolean tryQuantSell(JSONObject strategy, IBoxDirectClient.Account account, JSONObject asset, double salePrice, JSONObject publicConfig) throws Exception {
        JSONObject sell = strategy.optJSONObject("sell");
        JSONObject stopLoss = strategy.optJSONObject("stopLoss");
        String preset = strategy.optString("strategyPreset", "custom");
        boolean dynamicExit = "cost_profit_exit".equals(preset) || "trailing_profit_exit".equals(preset);
        boolean stopLossAction = "stop_loss".equals(strategy.optString("lastSignal"));
        if (sell == null || (!sell.optBoolean("enabled", false) && !stopLossAction)) return false;
        double trigger = stopLossAction && stopLoss != null ? decimal(stopLoss.opt("triggerPrice"), Double.NaN) : decimal(sell.opt("minPrice"), Double.NaN);
        if (!Double.isFinite(salePrice) || (dynamicExit && !"sell".equals(strategy.optString("lastSignal")))) return false;
        if (!dynamicExit && !Double.isFinite(trigger)) return false;
        double marketPrice = decimal(strategy.opt("latestFloorPrice"), Double.NaN);
        if (!Double.isFinite(marketPrice)) return false;
        if (!dynamicExit && (stopLossAction ? marketPrice > trigger : marketPrice < trigger)) return false;
        if (dynamicExit) {
            JSONObject freshLowest = lowestListing(marketListings(account, strategy.optString("groupId")));
            double freshMarketPrice = freshLowest == null ? Double.NaN : decimal(freshLowest.opt("price"), Double.NaN);
            double cost = store.getAssetCost(strategy.optString("phone"), strategy.optString("groupId"));
            double minimumProfit = Math.max(0d, decimal(strategy.opt("minNetProfit"), 0d));
            if (!Double.isFinite(freshMarketPrice) || !Double.isFinite(cost)) return false;
            if ("cost_profit_exit".equals(preset)) {
                double target = Math.ceil((cost + minimumProfit) / QUANT_NET_PROCEEDS_RATE);
                if (freshMarketPrice < target || salePrice < target) return false;
            } else {
                double activation = decimal(strategy.opt("trailingActivationPercent"), 10d);
                double drawdown = decimal(strategy.opt("trailingDrawdownPercent"), 5d);
                double retention = decimal(strategy.opt("trailingRetentionPercent"), 3d);
                double highest = decimal(strategy.opt("highestObservedPrice"), Double.NaN);
                double retainedProfit = Math.max(minimumProfit, cost * retention / 100d);
                if (!Double.isFinite(highest)
                        || highest * QUANT_NET_PROCEEDS_RATE < cost * (1d + activation / 100d)
                        || freshMarketPrice > highest * (1d - drawdown / 100d)
                        || salePrice > Math.floor(freshMarketPrice)
                        || salePrice * QUANT_NET_PROCEEDS_RATE - cost < retainedProfit) return false;
            }
        }
        if (asset == null) return false;
        String digitalCollectionId = asset.optString("instanceId");
        if (!digitalCollectionId.matches("\\d+")) throw new NativeException("量化寄售持仓实例编号无效");
        int quantity = Math.max(1, sell.optInt("quantity", 1));
        if (asset.optInt("quantity", 0) < quantity || asset.optBoolean("locked", false)) return false;
        JSONObject freshConfig = marketTradePublicConfig(account);
        String priceError = marketTradeConsignmentPriceError(salePrice, freshConfig);
        if (!priceError.isEmpty()) throw new NativeException(priceError);
        String password = requiredTradePassword(strategy);
        int paymentCode = paymentPlatformCode(account, 1, 0);
        JSONObject request = objectOf(
                "digitalCollectionId", integer(digitalCollectionId, 0),
                "price", salePrice,
                "paymentPlatformCodes", new JSONArray().put(paymentCode),
                "consignPassword", password
        );
        JSONObject response = client.requestAuthenticatedWithCaptcha(account, "POST", MARKET_CONSIGNMENT_ORDER_URL, request, null, IBoxDirectClient.CaptchaResult.fromJson(strategy.optJSONObject("captcha")), true, "量化寄售");
        Object result = data(response);
        strategy.put("digitalCollectionId", digitalCollectionId);
        strategy.put("resolvedPaymentPlatformCode", paymentCode);
        strategy.put("orderUuid", deepString(result, "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid"));
        strategy.put("listingOrderItemId", deepString(result, "listingOrderItemId"));
        strategy.put("result", result);
        strategy.put("enabled", false);
        strategy.put("status", "submitted");
        strategy.put("lastResult", "consignment_submitted");
        strategy.put("paymentStatus", "submitted");
        strategy.put("submittedAt", Instant.now().toString());
        sendBarkBestEffort(strategy, "notifyConsignmentSuccess", "iBox 量化寄售已提交", strategy.optString("title") + " · 寄售价 ¥" + salePrice, "active");
        return true;
    }

    private void processLotteryAutoDraw() {
        JSONArray activities = store.getLotteryTasks();
        boolean changed = false;
        long now = taskNow();
        for (int index = 0; index < activities.length(); index++) {
            JSONObject activity = activities.optJSONObject(index);
            if (activity == null || !activity.optBoolean("enabled", true)) continue;
            JSONObject accounts = activity.optJSONObject("accounts");
            if (accounts == null) continue;
            Iterator<String> phones = accounts.keys();
            while (phones.hasNext()) {
                String phone = phones.next();
                JSONObject state = accounts.optJSONObject(phone);
                if (state == null || "drawing".equals(state.optString("status"))) continue;
                try {
                    IBoxDirectClient.Account account = account(phone);
                    refreshLotteryActivity(account, activity);
                    now = taskNow();
                    if (!lotteryOpen(activity, now)) {
                        putQuietly(state, "availableCount", 0);
                        putQuietly(state, "status", activity.optString("phase", "not_started"));
                        putQuietly(state, "updatedAt", Instant.now().toString());
                        changed = true;
                        continue;
                    }
                    int drawCount = 0;
                    int available = availableLotteryCount(account, activity.optString("id"));
                    while (available > 0 && drawCount < LOTTERY_MAX_DRAWS_PER_RUN) {
                        refreshLotteryActivity(account, activity);
                        if (!lotteryOpen(activity, taskNow())) {
                            available = 0;
                            break;
                        }
                        available = availableLotteryCount(account, activity.optString("id"));
                        if (available <= 0) break;
                        int requested = available >= LOTTERY_DRAWS_PER_REQUEST ? LOTTERY_DRAWS_PER_REQUEST : 1;
                        putQuietly(state, "status", "drawing");
                        JSONObject response = client.requestAuthenticated(account, "POST", LOTTERY_ACTIVITY_URL + "/" + encodePath(activity.optString("id")) + "/draw", objectOf("drawCount", requested), null, true, "自动抽奖");
                        int remaining = availableLotteryCount(account, activity.optString("id"));
                        if (remaining >= available) throw new NativeException("抽奖次数未减少，停止重复提交");
                        drawCount += requested;
                        available = remaining;
                        putQuietly(state, "availableCount", available);
                        putQuietly(state, "lastDrawCount", requested);
                        putQuietly(state, "drawnCount", state.optInt("drawnCount", 0) + requested);
                        putQuietly(state, "result", data(response));
                        putQuietly(state, "updatedAt", Instant.now().toString());
                    }
                    putQuietly(state, "status", available > 0 ? "ready" : "drawn");
                    putQuietly(state, "availableCount", available);
                    putQuietly(state, "updatedAt", Instant.now().toString());
                    if (drawCount > 0) sendBark("always", "iBox 自动抽奖 · " + activity.optString("title"), "账号 " + phone + " 已提交 " + drawCount + " 次抽奖。", "active");
                } catch (Exception error) {
                    putQuietly(state, "status", captchaRequired(error) ? "verification_required" : "failed");
                    putQuietly(state, "message", message(error));
                    putQuietly(state, "updatedAt", Instant.now().toString());
                }
                changed = true;
            }
            putQuietly(activity, "updatedAt", Instant.now().toString());
        }
        if (changed) store.saveLotteryTasks(activities);
    }

    private void refreshLotteryActivity(IBoxDirectClient.Account account, JSONObject activity) throws Exception {
        String id = activity == null ? "" : activity.optString("id");
        if (id.isEmpty()) throw new NativeException("抽奖活动编号为空");
        JSONObject response = client.requestAuthenticated(account, "GET", LOTTERY_ACTIVITY_URL + "/" + encodePath(id), null, null, false, "抽奖活动");
        applyLotteryDetail(activity, object(data(response)), taskNow());
    }

    private static void applyLotteryDetail(JSONObject activity, JSONObject detail, long now) throws JSONException {
        activity.put("title", first(detail, "title", "name", "activityName"));
        activity.put("onlineStatus", integer(first(detail, "onlineStatus", "status"), 0));
        activity.put("enableOpen", bool(detail.opt("enableOpen")));
        activity.put("startedAt", first(detail, "startedAt", "startTime", "beginTime"));
        activity.put("endedAt", first(detail, "endedAt", "endTime", "finishedAt"));
        activity.put("startTime", activity.optString("startedAt"));
        activity.put("endTime", activity.optString("endedAt"));
        activity.put("phase", lotteryPhase(activity, now));
    }

    private int availableLotteryCount(IBoxDirectClient.Account account, String drawId) throws Exception {
        if (account.userId.isEmpty()) throw new NativeException("抽奖账号缺少用户标识，请重新登录");
        JSONObject response = client.requestAuthenticated(account, "GET", LOTTERY_ACTIVITY_URL + "/" + encodePath(drawId) + "/users/" + encodePath(account.userId) + "/available-count", null, null, false, "抽奖次数");
        JSONObject value = object(data(response));
        return Math.max(0, value.optInt("availableCount", integer(value.opt("count"), 0)));
    }

    private boolean lotteryOpen(JSONObject activity, long now) {
        if (activity.optInt("onlineStatus", 0) != 1 || !activity.optBoolean("enableOpen", false)) return false;
        long starts = epoch(activity.optString("startedAt"));
        long ends = epoch(activity.optString("endedAt"));
        return starts > 0 && ends > 0 && now >= starts && now < ends;
    }

    private static String lotteryPhase(JSONObject activity, long now) {
        int onlineStatus = activity == null ? -1 : activity.optInt("onlineStatus", -1);
        if (onlineStatus == 2) return "ended";
        if (onlineStatus == 0) return "not_started";
        if (activity == null || onlineStatus != 1 || !activity.optBoolean("enableOpen", false)) return "offline";
        long startedAt = epoch(activity.optString("startedAt"));
        long endedAt = epoch(activity.optString("endedAt"));
        if (now <= 0L) return "time_unverified";
        if (endedAt > 0L && now >= endedAt) return "ended";
        if (startedAt > 0L && now < startedAt) return "not_started";
        return startedAt > 0L && endedAt > 0L && now >= startedAt && now < endedAt ? "open" : "time_unverified";
    }

    private JSONObject synthesisActivity(JSONObject parent, JSONObject channel, String syntheticId, long now) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("syntheticId", syntheticId);
        value.put("title", first(channel, "syntheticName", "channelName", "name", "title", "activityName"));
        if (value.optString("title").isEmpty()) value.put("title", first(parent, "activityName", "name", "title", "syntheticName"));
        value.put("name", value.optString("title"));
        value.put("status", first(channel, "syntheticStatus", "status", "workStatus"));
        if (value.optString("status").isEmpty()) value.put("status", first(parent, "syntheticStatus", "status", "activityStatus", "workStatus"));
        value.put("startTime", first(channel, "startTime", "beginTime"));
        if (value.optString("startTime").isEmpty()) value.put("startTime", first(parent, "startTime", "beginTime"));
        value.put("endTime", first(channel, "endTime", "finishTime"));
        if (value.optString("endTime").isEmpty()) value.put("endTime", first(parent, "endTime", "finishTime"));
        value.put("startAt", isoTime(value.optString("startTime")));
        value.put("endAt", isoTime(value.optString("endTime")));
        value.put("phase", synthesisPhase(value, now));
        Object supportAssistant = channel.has("supportAssistant") ? channel.opt("supportAssistant") : parent.opt("supportAssistant");
        value.put("supportAssistant", bool(supportAssistant));
        return value;
    }

    private JSONObject synthesisCenterSummary(Object source, String syntheticId) throws Exception {
        JSONObject root = object(source);
        JSONObject result = new JSONObject();
        result.put("syntheticId", first(root, "id", "syntheticId"));
        if (result.optString("syntheticId").isEmpty()) result.put("syntheticId", syntheticId);
        result.put("title", first(root, "title", "activityName", "name", "syntheticName"));
        boolean needsSlider = bool(root.opt("needSlider"));
        result.put("needSlider", needsSlider);
        JSONArray groups = synthesisGroupSummaries(root, 1);
        try {
            JSONObject request = synthesisRequest(root, syntheticId, 1);
            result.put("canSubmit", !needsSlider);
            result.put("preferentialAlbumIds", request.optJSONArray("preferentialAlbumIds"));
        } catch (Exception error) {
            result.put("canSubmit", false);
            result.put("reason", message(error));
        }
        result.put("groups", groups);
        return result;
    }

    private JSONArray synthesisGroupSummaries(JSONObject root, int syntheticNum) throws JSONException {
        JSONArray groups = firstArray(root, "burnAlbums", "materials", "materialGroups");
        JSONArray result = new JSONArray();
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) continue;
            int required = Math.max(1, integer(first(group, "quantity", "needNum", "burnNum", "requireNum", "num", "count"), 1));
            int requiredTotal = required * Math.max(1, syntheticNum);
            String groupId = first(group, "groupId", "syntheticGroupId", "id");
            if (groupId.isEmpty()) groupId = String.valueOf(index + 1);
            JSONObject summary = objectOf(
                    "groupId", groupId,
                    "required", required,
                    "requiredTotal", requiredTotal
            );
            JSONArray choices = firstArray(group, "albums", "albumList", "materials", "items");
            JSONArray options = new JSONArray();
            JSONObject selected = null;
            for (int optionIndex = 0; optionIndex < choices.length(); optionIndex++) {
                JSONObject option = choices.optJSONObject(optionIndex);
                if (option == null) continue;
                JSONObject album = objectOf(
                        "id", first(option, "digitalCollectionId", "digitalCollectionID", "albumId", "collectionId", "id"),
                        "name", first(option, "albumName", "digitalCollectionName", "collectionName", "name", "title"),
                        "usableNum", integer(first(option, "usableNum", "availableNum", "holdNum", "quantity", "num"), 0)
                );
                options.put(album);
                if (selected == null && integer(album.opt("usableNum"), 0) >= requiredTotal && !album.optString("id").isEmpty()) {
                    selected = album;
                }
            }
            if (selected != null) summary.put("album", selected);
            else summary.put("options", options);
            result.put(summary);
        }
        return result;
    }

    private JSONObject synthesisRequest(Object source, String syntheticId, int syntheticNum) throws Exception {
        JSONObject root = object(source);
        if (bool(root.opt("needSlider"))) throw new NativeException("当前合成需要人机验证，暂不能自动提交");
        JSONArray groups = firstArray(root, "burnAlbums", "materials", "materialGroups");
        if (groups.length() == 0) throw new NativeException("合成材料组为空");
        JSONArray albums = new JSONArray();
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) throw new NativeException("合成材料组无效");
            int required = Math.max(1, integer(first(group, "quantity", "needNum", "burnNum", "requireNum", "num", "count"), 1));
            int requiredTotal = required * syntheticNum;
            JSONArray choices = firstArray(group, "albums", "albumList", "materials", "items");
            String selected = "";
            for (int optionIndex = 0; optionIndex < choices.length(); optionIndex++) {
                JSONObject option = choices.optJSONObject(optionIndex);
                if (option == null) continue;
                int usable = integer(first(option, "usableNum", "availableNum", "holdNum", "quantity", "num"), 0);
                String id = first(option, "digitalCollectionId", "digitalCollectionID", "albumId", "collectionId", "id");
                if (usable >= requiredTotal && !id.isEmpty()) {
                    selected = id;
                    break;
                }
            }
            if (selected.isEmpty()) throw new NativeException("合成材料不足");
            albums.put(integer(selected, 0));
        }
        int id = integer(syntheticId, 0);
        if (id <= 0) throw new NativeException("合成编号无效");
        return objectOf("syntheticId", id, "syntheticNum", syntheticNum, "preferentialAlbumIds", albums);
    }

    private JSONObject firstSaleSummary(JSONObject source) throws JSONException {
        return firstSaleSummary(source, System.currentTimeMillis());
    }

    private JSONObject firstSaleSummary(JSONObject source, long now) throws JSONException {
        JSONObject group = source.optJSONObject("digitalCollectionGroup");
        JSONObject value = group == null ? copy(source) : merge(copy(group), source);
        String saleId = first(value, "id", "saleId", "saleInfoId");
        String groupId = first(value, "digitalCollectionGroupId", "collectionGroupId", "groupId", "digitalCollectionId");
        int status = integer(first(value, "saleStatus", "status"), -1);
        String start = first(value, "onSaleTime", "saleTime", "startTime", "beginTime");
        String end = first(value, "offSaleTime", "saleEndTime", "endTime", "finishTime", "endAt");
        JSONObject result = new JSONObject();
        result.put("saleId", saleId);
        result.put("groupId", groupId);
        result.put("title", first(value, "digitalCollectionName", "collectionName", "name", "title", "groupName"));
        result.put("cover", first(value, "coverPicUrl", "coverUrl", "headerPicUrl", "picUrl", "imageUrl"));
        result.put("price", numericOrNull(first(value, "price", "salePrice", "amount")));
        result.put("saleStatus", status);
        result.put("saleStatusLabel", firstSaleStatusLabel(status));
        result.put("action", status == 0 ? "scheduled" : status == 5 ? "immediate" : "disabled");
        result.put("phase", firstSalePhase(value, now));
        result.put("startTime", start);
        result.put("startAt", isoTime(start));
        result.put("endTime", end);
        result.put("endAt", isoTime(end));
        result.put("userOnceMaxBuyNum", integer(first(value, "userOnceMaxBuyNum", "onceMaxBuyNum", "maxBuyNum", "limitNum"), 0));
        putNullable(result, "forNew", firstNullable(value, "forNew"));
        putNullable(result, "priorityNum", firstNullable(value, "priorityNum"));
        putNullable(result, "isMembership", firstNullable(value, "isMembership"));
        putNullable(result, "buttonStatus", firstNullable(value, "buttonStatus"));
        return result;
    }

    private static String firstSalePhase(JSONObject value, long now) {
        int status = integer(first(value, "saleStatus", "status"), Integer.MIN_VALUE);
        if (status == 2) return "sold_out";
        if (status == 0) return "preparing";
        if (status == 5) return "available";
        long endAt = epoch(first(value, "offSaleTime", "saleEndTime", "endTime", "finishTime", "endAt"));
        return status == Integer.MIN_VALUE && endAt > 0L && endAt <= now ? "ended" : "unavailable";
    }

    private static JSONObject expiredFirstSale(JSONObject source) throws JSONException {
        JSONObject result = copy(source);
        result.put("phase", "expired_placeholder");
        result.put("action", "disabled");
        return result;
    }

    private static long firstSaleRecentTime(JSONObject item) {
        long endAt = epoch(first(item, "endAt", "endTime"));
        if (endAt > 0L) return endAt;
        return epoch(first(item, "startAt", "startTime"));
    }

    private static String synthesisPhase(JSONObject item, long now) {
        int status = integer(first(item, "syntheticStatus", "status", "activityStatus", "workStatus"), Integer.MIN_VALUE);
        long startAt = epoch(first(item, "startTime", "beginTime", "startAt"));
        long endAt = epoch(first(item, "endTime", "finishTime", "endAt"));
        if (endAt > 0L && endAt <= now) return "expired";
        if (status == 1) return "available";
        if (status == 0) return "preparing";
        if (startAt > 0L && startAt > now) return "preparing";
        if (endAt > 0L && endAt > now) return "available";
        return "expired";
    }

    private static long synthesisSortTime(JSONObject item, String phase) {
        String key = "available".equals(phase) ? "endAt" : "startAt";
        long value = epoch(first(item, key, "available".equals(phase) ? "endTime" : "startTime"));
        return value > 0L ? value : Long.MAX_VALUE;
    }

    private static long synthesisExpiredTime(JSONObject item) {
        long endAt = epoch(first(item, "endAt", "endTime"));
        if (endAt > 0L) return endAt;
        long startAt = epoch(first(item, "startAt", "startTime"));
        return startAt > 0L ? startAt : Long.MIN_VALUE;
    }

    private static String firstSaleStatusLabel(int status) {
        switch (status) {
            case 0: return "即将开售";
            case 1: return "暂停售卖";
            case 2: return "已售罄";
            case 3: return "即将抽签";
            case 4: return "去抽签";
            case 5: return "立即购买";
            case 6: return "余量待售";
            case 8: return "可空投获得";
            case 18: return "可合成获得";
            default: return "状态未识别";
        }
    }

    private void applyTaskCaptcha(String kind, String taskId, JSONObject captcha) throws Exception {
        if ("first_sale".equals(kind)) {
            JSONArray tasks = store.getFirstSaleTasks();
            JSONObject task = findById(tasks, taskId);
            if (task == null) throw new NativeException("首发任务不存在");
            if (!"verification_required".equals(task.optString("status"))) throw new NativeException("当前首发任务不需要人机验证");
            task.put("captcha", captcha);
            task.put("enabled", true);
            task.put("status", "scheduled");
            task.put("updatedAt", Instant.now().toString());
            store.saveFirstSaleTasks(tasks);
            return;
        }
        if ("quant".equals(kind)) {
            JSONArray strategies = store.getQuantStrategies();
            JSONObject strategy = findById(strategies, taskId);
            if (strategy == null) throw new NativeException("量化策略不存在");
            if (!"verification_required".equals(strategy.optString("status"))) throw new NativeException("当前量化策略不需要人机验证");
            strategy.put("captcha", captcha);
            strategy.put("enabled", true);
            strategy.put("status", "monitoring");
            strategy.put("updatedAt", Instant.now().toString());
            addEvent(strategy, "captcha_verified", "人机验证已保存");
            store.saveQuantStrategies(strategies);
            return;
        }
        JSONArray tasks = store.getTradeTasks();
        JSONObject task = findTask(tasks, taskId, kind);
        if (task == null) throw new NativeException("交易任务不存在");
        if (!"verification_required".equals(task.optString("status"))) throw new NativeException("当前交易任务不需要人机验证");
        task.put("captcha", captcha);
        task.put("enabled", true);
        task.put("status", "scheduled");
        task.put("updatedAt", Instant.now().toString());
        addEvent(task, "captcha_verified", "人机验证已保存");
        store.saveTradeTasks(tasks);
    }

    private static String taskId(String kind, Route route) {
        return "market_trade".equals(kind) ? route.segment(4) : route.segment(3);
    }

    private long taskNow() {
        syncTaskClockIfDue();
        return System.currentTimeMillis() + serverClockOffsetMs;
    }

    private void syncTaskClockIfDue() {
        long now = System.currentTimeMillis();
        if (now - serverClockSyncedAt < TASK_CLOCK_SYNC_MS) return;
        synchronized (CLOCK_LOCK) {
            now = System.currentTimeMillis();
            if (now - serverClockSyncedAt < TASK_CLOCK_SYNC_MS) return;
            HttpURLConnection connection = null;
            try {
                long sentAt = System.currentTimeMillis();
                connection = (HttpURLConnection) new URL(TIME_API_URL).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = stream == null ? "" : readUtf8(stream);
                long receivedAt = System.currentTimeMillis();
                if (status < 200 || status >= 300) throw new NativeException("网络校时失败（HTTP " + status + "）");
                JSONObject payload = new JSONObject(text);
                long timestamp = timeApiTimestamp(payload);
                if (timestamp <= 0L) throw new NativeException("网络校时返回无效时间");
                serverClockOffsetMs = timestamp - ((sentAt + receivedAt) / 2L);
            } catch (Exception ignored) {
                // Keep the last known offset and fall back to local time when the time service is unavailable.
            } finally {
                serverClockSyncedAt = System.currentTimeMillis();
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static long timeApiTimestamp(JSONObject payload) {
        if (payload == null) return 0L;
        int year = payload.optInt("year", 0);
        int month = payload.optInt("month", 0);
        int day = payload.optInt("day", 0);
        int hour = payload.optInt("hour", -1);
        int minute = payload.optInt("minute", -1);
        int second = payload.optInt("seconds", payload.optInt("second", -1));
        if (year > 0 && month > 0 && day > 0 && hour >= 0 && minute >= 0 && second >= 0) {
            try {
                return LocalDateTime.of(year, month, day, hour, minute, second, payload.optInt("milliSeconds", 0))
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli();
            } catch (Exception ignored) {
                return 0L;
            }
        }
        return epoch(first(payload, "dateTime", "datetime"));
    }

    private static String readUtf8(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            byte[] buffer = new byte[4096];
            StringBuilder output = new StringBuilder();
            int count;
            while ((count = input.read(buffer)) >= 0) output.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            return output.toString();
        }
    }

    private static JSONArray taskPhones(JSONObject task) throws JSONException {
        JSONArray phones = new JSONArray();
        Object raw = task == null ? null : task.opt("phones");
        if (raw instanceof JSONArray) {
            JSONArray values = (JSONArray) raw;
            for (int index = 0; index < values.length(); index++) addTaskPhone(phones, values.optString(index));
        } else if (raw != null && raw != JSONObject.NULL) {
            addTaskPhone(phones, String.valueOf(raw));
        }
        if (task != null) {
            addTaskPhone(phones, first(task, "sourcePhone", "phone"));
            addTaskPhone(phones, first(task, "phone"));
        }
        return phones;
    }

    private static JSONArray executionPhones(JSONObject task) {
        try {
            return taskPhones(task);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void addTaskPhone(JSONArray phones, String value) throws JSONException {
        String phone = value == null ? "" : value.trim();
        if (!phone.matches("\\d{11}") || contains(phones, phone)) return;
        phones.put(phone);
    }

    private static void taskRun(JSONObject task, String phone, String status, String message) {
        taskRun(task, phone, status, message, null);
    }

    private static void taskRun(JSONObject task, String phone, String status, String message, JSONObject details) {
        if (task == null || phone == null || phone.trim().isEmpty()) return;
        JSONObject runs = task.optJSONObject("runs");
        if (runs == null) {
            runs = new JSONObject();
            putQuietly(task, "runs", runs);
        }
        JSONObject run = runs.optJSONObject(phone);
        if (run == null) {
            run = new JSONObject();
            putQuietly(runs, phone, run);
        }
        putQuietly(run, "status", status);
        putQuietly(run, "message", message == null ? "" : message);
        if (details != null) {
            Iterator<String> keys = details.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                putQuietly(run, key, details.opt(key));
            }
        }
        putQuietly(run, "updatedAt", Instant.now().toString());
        putQuietly(task, "updatedAt", Instant.now().toString());
    }

    private static JSONObject firstSaleEligibility(JSONObject detail) throws JSONException {
        return objectOf(
                "saleStatus", detail.opt("saleStatus"),
                "saleStatusLabel", detail.optString("saleStatusLabel"),
                "action", detail.optString("action"),
                "phase", detail.optString("phase"),
                "userOnceMaxBuyNum", detail.opt("userOnceMaxBuyNum"),
                "forNew", detail.opt("forNew"),
                "priorityNum", detail.opt("priorityNum"),
                "isMembership", detail.opt("isMembership"),
                "buttonStatus", detail.opt("buttonStatus")
        );
    }

    private void removeTasksForPhone(String phone) {
        store.saveSynthesisTasks(removeByPhone(store.getSynthesisTasks(), phone));
        store.saveFirstSaleTasks(removeByPhone(store.getFirstSaleTasks(), phone));
        store.saveQuantStrategies(removeByPhone(store.getQuantStrategies(), phone));
        store.saveTradeTasks(removeByPhone(store.getTradeTasks(), phone));
    }

    private JSONObject cancelStoredTask(JSONArray tasks, String id, String kind) throws Exception {
        JSONObject task = findById(tasks, id);
        if (task == null) throw new NativeException("任务不存在");
        task.put("enabled", false);
        task.put("status", "cancelled");
        task.put("updatedAt", Instant.now().toString());
        if ("synthesis".equals(kind)) store.saveSynthesisTasks(tasks);
        else store.saveFirstSaleTasks(tasks);
        return ok(task);
    }

    private JSONObject deleteStoredTask(JSONArray tasks, String id, String kind) throws Exception {
        JSONArray remaining = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && id.equals(task.optString("id"))) {
                removed = true;
            } else if (task != null) {
                remaining.put(task);
            }
        }
        if (!removed) throw new NativeException("任务不存在");
        if ("synthesis".equals(kind)) store.saveSynthesisTasks(remaining);
        else store.saveFirstSaleTasks(remaining);
        return ok(new JSONObject());
    }

    private static JSONObject findTask(JSONArray tasks, String id, String kind) {
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && id.equals(task.optString("id")) && kind.equals(task.optString("localType", "market_trade"))) return task;
        }
        return null;
    }

    private static JSONObject findById(JSONArray values, String id) {
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null && id.equals(value.optString("id"))) return value;
        }
        return null;
    }

    private JSONArray marketListings(IBoxDirectClient.Account account, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        JSONObject query = objectOf(
                "pageNo", 1,
                "pageSize", 20,
                "sortField", 1,
                "sortType", 0,
                "lowPrice", "",
                "highPrice", "",
                "minTokenId", "",
                "maxTokenId", "",
                "level", "",
                "sortValues", ""
        );
        JSONObject response = client.requestAuthenticated(account, "GET", MARKET_PUBLIC_URL + "/" + encodePath(groupId) + "/consignment-orders", null, query, false, "市场挂单");
        JSONArray source = firstArray(data(response), "list", "records", "items", "rows", "data");
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry == null) continue;
            JSONObject collection = entry.optJSONObject("digitalCollection");
            JSONObject normalized = new JSONObject();
            normalized.put("digitalCollectionId", first(entry, "digitalCollectionId", "collectionId", "id"));
            if (normalized.optString("digitalCollectionId").isEmpty() && collection != null) normalized.put("digitalCollectionId", first(collection, "id"));
            normalized.put("tokenId", first(entry, "tokenId"));
            if (normalized.optString("tokenId").isEmpty() && collection != null) normalized.put("tokenId", first(collection, "tokenId"));
            normalized.put("price", numericOrNull(first(entry, "price", "salePrice")));
            int lockStatus = collection == null ? -1 : integer(first(collection, "lockStatus", "lockedStatus"), -1);
            int lockType = collection == null ? -1 : integer(first(collection, "lockType", "locktType"), -1);
            normalized.put("locked", lockStatus == 2 && lockType != 4);
            normalized.put("name", collection == null ? first(entry, "name", "title") : first(collection, "name", "title", "digitalCollectionName"));
            normalized.put("cover", collection == null ? first(entry, "coverPicUrl", "coverUrl") : first(collection, "coverPicUrl", "coverUrl"));
            if (!normalized.optString("digitalCollectionId").isEmpty()) result.put(normalized);
        }
        return result;
    }

    private JSONObject marketTradeAssets(String phone, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        IBoxDirectClient.Account account = account(phone);
        JSONArray items = ownedCollections(account, groupId);
        return ok(objectOf(
                "groupId", groupId,
                "items", items,
                "consigning", items.length() == 0 && hasActiveConsignment(account, groupId)
        ));
    }

    private JSONObject marketTradeListings(String phone, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        IBoxDirectClient.Account account = account(phone);
        JSONArray listings = marketListings(account, groupId);
        return ok(objectOf("groupId", groupId, "total", listings.length(), "items", listings, "available", true));
    }

    private JSONObject marketTradeDetail(String phone, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        IBoxDirectClient.Account account = account(phone);
        return ok(loadMarketTradeDetail(account, groupId));
    }

    private JSONObject marketTradePreflight(String phone, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        IBoxDirectClient.Account account = account(phone);
        JSONObject detail = loadMarketTradeDetail(account, groupId);
        JSONArray listings = marketListings(account, groupId);
        JSONArray owned = ownedCollections(account, groupId);
        JSONObject priceRange = marketTradePublicConfig(account);
        JSONObject wantedPayment = paymentPlatformAvailability(account, 2);
        JSONObject consignmentPayment = paymentPlatformAvailability(account, 1);
        return ok(objectOf(
                "detail", detail,
                "listings", listings,
                "listingCount", listings.length(),
                "owned", owned,
                "ownedQuantity", ownedQuantity(owned),
                "wantedPayment", wantedPayment,
                "consignmentPayment", consignmentPayment,
                "priceRange", priceRange,
                "updatedAt", Instant.now().toString()
        ));
    }

    private JSONObject marketTradeWantedPreflight(String phone, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        IBoxDirectClient.Account account = account(phone);
        JSONObject detail = loadMarketTradeDetail(account, groupId);
        JSONObject wanted = detail.optJSONObject("wanted");
        JSONArray allowedCodes = wanted == null ? new JSONArray() : firstArray(wanted, "paymentPlatformCodes", "platformCodes");
        JSONObject payment = paymentPlatformAvailability(account, 2, allowedCodes);
        return ok(objectOf("detail", detail, "wantedPayment", payment, "updatedAt", Instant.now().toString()));
    }

    private JSONObject loadMarketTradeDetail(IBoxDirectClient.Account account, String groupId) throws Exception {
        JSONObject detailResponse = client.requestAuthenticated(account, "GET", MARKET_GROUP_URL + "/" + encodePath(groupId), null, null, false, "交易详情");
        JSONObject root = object(data(detailResponse));
        JSONObject source = root;
        JSONObject group = root.optJSONObject("digitalCollectionGroup");
        if (group != null) source = merge(copy(group), source);
        JSONObject wanted = new JSONObject();
        try {
            JSONObject response = client.requestAuthenticated(account, "GET", MARKET_PUBLIC_URL + "/" + encodePath(groupId) + "/purchase-consignment-info", null, objectOf("configType", 2), false, "求购配置");
            wanted = object(data(response));
        } catch (Exception ignored) {
            // The detail endpoint remains useful when the optional wanted-price endpoint is unavailable.
        }
        String title = first(source, "name", "title", "groupName", "digitalCollectionName");
        JSONObject latest = null;
        if (!title.isEmpty()) {
            try {
                JSONObject market = client.searchMarkets(account, title, 1, 20);
                latest = matchMarketItem(market.optJSONArray("items"), groupId, title);
            } catch (Exception ignored) {
                // A temporary market-search failure must not erase the official detail response.
            }
        }
        double floor = latest == null ? decimal(source.opt("floorPrice"), Double.NaN) : decimal(latest.opt("floorPrice"), Double.NaN);
        Object consignment = root.has("enableConsignment") ? root.opt("enableConsignment") : JSONObject.NULL;
        Object purchase = root.has("enablePurchase") ? root.opt("enablePurchase") : JSONObject.NULL;
        Object wantedEnabled = wanted.has("enablePurchase") ? wanted.opt("enablePurchase") : purchase;
        return objectOf(
                "groupId", groupId,
                "name", title,
                "cover", first(source, "coverPicUrl", "coverUrl", "headerPicUrl", "imageUrl"),
                "floorPrice", Double.isFinite(floor) ? floor : JSONObject.NULL,
                "enableConsignment", consignment,
                "enablePurchase", purchase,
                "wanted", objectOf(
                        "enabled", wantedEnabled,
                        "lowLimitPrice", numericOrNull(first(wanted, "lowLimitPrice", "minPrice", "lowestPrice")),
                        "highLimitPrice", numericOrNull(first(wanted, "highLimitPrice", "maxPrice", "highestPrice")),
                        "paymentPlatformCodes", firstArray(wanted, "paymentPlatformCodes", "platformCodes")
                ),
                "updatedAt", Instant.now().toString()
        );
    }

    private JSONObject marketTradePublicConfig(IBoxDirectClient.Account account) throws Exception {
        JSONObject response = client.requestAuthenticated(account, "POST", MARKET_TRADE_PUBLIC_CONFIG_URL, new JSONObject(), null, true, "交易价格配置");
        JSONObject root = object(data(response));
        JSONObject config = root.optJSONObject("pub");
        if (config == null) config = root;
        JSONObject range = config.optJSONObject("priceRange");
        if (range == null) range = new JSONObject();
        return objectOf(
                "minPrice", numericOrNull(first(range, "min", "minPrice", "lowPrice")),
                "maxPrice", numericOrNull(first(range, "max", "maxPrice", "highPrice")),
                "lessLowPriceRate", numericOrNull(first(config, "lessLowPriceRate"))
        );
    }

    private JSONArray ownedCollections(IBoxDirectClient.Account account, String groupId) throws Exception {
        if (groupId == null || !groupId.trim().matches("\\d+")) throw new NativeException("交易藏品编号无效");
        JSONObject query = objectOf("pageNo", 1, "pageSize", 100, "lockStatus", 0);
        JSONObject response = client.requestAuthenticated(account, "GET", OWNED_GROUP_URL + "/" + encodePath(groupId), null, query, false, "持仓资产");
        JSONArray source = firstArray(data(response), "collectionAssets", "list", "records", "items", "rows", "data");
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry == null) continue;
            JSONObject collection = entry.optJSONObject("digitalCollection");
            JSONObject normalized = new JSONObject();
            String instanceId = ownedCollectionId(entry, groupId);
            normalized.put("id", instanceId);
            normalized.put("instanceId", instanceId);
            normalized.put("quantity", integer(first(entry, "holdNum", "holdCount", "quantity", "count", "num"), 1));
            normalized.put("locked", integer(first(entry, "lockStatus", "lockedStatus"), 0) > 0);
            normalized.put("name", collection == null ? first(entry, "name", "title") : first(collection, "name", "title"));
            if (!normalized.optString("id").isEmpty() && !normalized.optBoolean("locked", false)) result.put(normalized);
        }
        return result;
    }

    private boolean hasActiveConsignment(IBoxDirectClient.Account account, String groupId) throws Exception {
        return activeConsignmentAssets(account, groupId).length() > 0;
    }

    private JSONArray activeConsignmentAssets(IBoxDirectClient.Account account, String groupId) throws Exception {
        JSONObject query = objectOf("pageNo", 1, "pageSize", 100, "lockStatus", 1);
        JSONObject response = client.requestAuthenticated(account, "GET", OWNED_GROUP_URL + "/" + encodePath(groupId), null, query, false, "寄售状态");
        JSONArray source = firstArray(data(response), "collectionAssets", "list", "records", "items", "rows", "data");
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject entry = source.optJSONObject(index);
            if (entry == null) continue;
            int digitalStatus = integer(first(entry, "digitalCollectionStatus"), -1);
            int consignmentStatus = integer(first(entry, "consignmentStatus"), -1);
            int consignmentNum = integer(first(entry, "consignmentNum", "consignNum"), 0);
            if (digitalStatus == 2 || (consignmentStatus == 1 && consignmentNum > 0)) result.put(entry);
        }
        return result;
    }

    private static String ownedCollectionId(JSONObject entry, String groupId) {
        String instanceId = first(entry, "id");
        if (instanceId.matches("\\d+") && !instanceId.equals(groupId)) return instanceId;
        return "";
    }

    private static String resolveOwnedCollectionId(JSONArray assets, JSONObject task) throws NativeException {
        String requested = task.optString("digitalCollectionId");
        if (requested.isEmpty()) throw new NativeException("寄售任务需要选择持仓资产");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset != null && requested.equals(asset.optString("id"))) return requested;
        }
        throw new NativeException("所选持仓资产已不可寄售，请重新读取资产");
    }

    private static int ownedQuantity(JSONArray assets) {
        int total = 0;
        if (assets == null) return 0;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset == null || asset.optBoolean("locked", false)) continue;
            total += Math.max(0, asset.optInt("quantity", 0));
        }
        return total;
    }

    private static boolean ownsCollection(JSONArray assets, String collectionId) {
        if (assets == null || collectionId == null || collectionId.trim().isEmpty()) return false;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset != null && collectionId.equals(asset.optString("id")) && !asset.optBoolean("locked", false)
                    && asset.optInt("quantity", 0) > 0) return true;
        }
        return false;
    }

    private static JSONObject firstOwnedAsset(JSONArray assets, int quantity) {
        if (assets == null) return null;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset != null && !asset.optBoolean("locked", false) && asset.optInt("quantity", 0) >= quantity) return asset;
        }
        return null;
    }

    private static JSONArray appendQuantPrice(JSONArray source, double price, int requestedLimit) {
        int limit = Math.max(1, Math.min(QUANT_PRICE_HISTORY_LIMIT, requestedLimit));
        List<Double> values = new ArrayList<>();
        if (source != null) {
            for (int index = 0; index < source.length(); index++) {
                double value = decimal(source.opt(index), Double.NaN);
                if (Double.isFinite(value) && value > 0d) values.add(value);
            }
        }
        if (Double.isFinite(price) && price > 0d) values.add(price);
        JSONArray result = new JSONArray();
        int start = Math.max(0, values.size() - limit);
        for (int index = start; index < values.size(); index++) result.put(values.get(index));
        return result;
    }

    private static double quantMedian(JSONArray source) {
        if (source == null || source.length() == 0) return Double.NaN;
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < source.length(); index++) {
            double value = decimal(source.opt(index), Double.NaN);
            if (Double.isFinite(value) && value > 0d) values.add(value);
        }
        if (values.isEmpty()) return Double.NaN;
        Collections.sort(values);
        int middle = values.size() / 2;
        return (values.size() & 1) == 1 ? values.get(middle) : (values.get(middle - 1) + values.get(middle)) / 2d;
    }

    private static double quantVolatility(double previous, double current) {
        if (!Double.isFinite(previous) || previous <= 0d || !Double.isFinite(current) || current <= 0d) return Double.NaN;
        return Math.abs(current - previous) / previous * 100d;
    }

    private int paymentPlatformCode(IBoxDirectClient.Account account, int placeOrderMethod, int requestedCode) throws Exception {
        return paymentPlatformCode(account, placeOrderMethod, requestedCode, new JSONArray());
    }

    private int paymentPlatformCode(IBoxDirectClient.Account account, int placeOrderMethod, int requestedCode, JSONArray allowedCodes) throws Exception {
        JSONObject response = client.requestAuthenticated(account, "GET", PAYMENT_PLATFORMS_URL, null, objectOf("placeOrderMethod", placeOrderMethod), false, "支付通道");
        JSONArray platforms = firstArray(data(response), "paymentPlatforms", "platforms", "paymentPlatformList", "paymentMethods", "list", "records", "items", "rows", "data");
        boolean restrictAllowedCodes = allowedCodes != null && allowedCodes.length() > 0;
        int fallback = 0;
        for (int index = 0; index < platforms.length(); index++) {
            JSONObject item = platforms.optJSONObject(index);
            if (item == null) continue;
            int code = integer(first(item, "paymentPlatformCode", "platformCode", "code"), 0);
            if (restrictAllowedCodes && !contains(allowedCodes, String.valueOf(code))) continue;
            Object activationValue = paymentPlatformActivationValue(item);
            int activation = activationValue instanceof Boolean ? (bool(activationValue) ? 1 : 0) : integer(activationValue, 0);
            boolean enabled = item.optBoolean("enabled", item.optBoolean("isEnabled", true));
            boolean available = item.optBoolean("available", item.optBoolean("isAvailable", true));
            if (code <= 0 || activation != 1 || !enabled || !available) continue;
            if (requestedCode > 0 && code == requestedCode) return code;
            if (fallback == 0) fallback = code;
        }
        if (requestedCode > 0) throw new NativeException("指定支付通道当前不可用");
        if (fallback <= 0) throw new NativeException("未找到可用支付通道");
        return fallback;
    }

    private static Object paymentPlatformActivationValue(JSONObject item) {
        if (item == null) return JSONObject.NULL;
        if (item.has("activationStatus")) return item.opt("activationStatus");
        if (item.has("status")) return item.opt("status");
        if (item.has("isOpen")) return item.opt("isOpen");
        if (item.has("enabled")) return item.opt("enabled");
        return item.opt("available");
    }

    private JSONObject paymentPlatformAvailability(IBoxDirectClient.Account account, int placeOrderMethod) throws Exception {
        return paymentPlatformAvailability(account, placeOrderMethod, new JSONArray());
    }

    private JSONObject paymentPlatformAvailability(IBoxDirectClient.Account account, int placeOrderMethod, JSONArray allowedCodes) throws Exception {
        try {
            JSONArray platforms = paymentPlatformOptions(account, placeOrderMethod, allowedCodes);
            int code = 0;
            for (int index = 0; index < platforms.length(); index++) {
                JSONObject platform = platforms.optJSONObject(index);
                if (platform != null && platform.optBoolean("selectable", false)) {
                    code = platform.optInt("code", 0);
                    if (code > 0) break;
                }
            }
            if (code <= 0) throw new NativeException("未找到可用支付通道");
            return objectOf("available", true, "code", code, "platforms", platforms);
        } catch (Exception error) {
            return objectOf("available", false, "code", JSONObject.NULL, "platforms", new JSONArray(), "message", message(error));
        }
    }

    private JSONArray paymentPlatformOptions(IBoxDirectClient.Account account, int placeOrderMethod, JSONArray allowedCodes) throws Exception {
        JSONObject response = client.requestAuthenticated(account, "GET", PAYMENT_PLATFORMS_URL, null, objectOf("placeOrderMethod", placeOrderMethod), false, "支付通道");
        JSONArray source = firstArray(data(response), "paymentPlatforms", "platforms", "paymentPlatformList", "paymentMethods", "list", "records", "items", "rows", "data");
        boolean restrictAllowedCodes = allowedCodes != null && allowedCodes.length() > 0;
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            int code = integer(first(item, "paymentPlatformCode", "platformCode", "code"), 0);
            if (code <= 0 || (restrictAllowedCodes && !contains(allowedCodes, String.valueOf(code)))) continue;
            Object activationValue = paymentPlatformActivationValue(item);
            int activation = activationValue instanceof Boolean ? (bool(activationValue) ? 1 : 0) : integer(activationValue, 0);
            boolean enabled = item.optBoolean("enabled", item.optBoolean("isEnabled", true));
            boolean available = item.optBoolean("available", item.optBoolean("isAvailable", true));
            result.put(objectOf(
                    "code", code,
                    "name", first(item, "platformName", "paymentPlatformName", "name", "paymentType", "platformType"),
                    "activationStatus", activation,
                    "enabled", enabled,
                    "available", available,
                    "selectable", activation == 1 && enabled && available
            ));
        }
        return result;
    }

    private String cashierLink(IBoxDirectClient.Account account, String orderUuid, int initiatorType) throws Exception {
        JSONObject query = objectOf("orderUUId", orderUuid, "paymentInitiatorType", initiatorType);
        JSONObject response = client.requestAuthenticated(account, "GET", PAYMENT_CASHIER_URL, null, query, false, "支付链接");
        String link = deepString(data(response), "cashierLink", "link");
        if (link.isEmpty()) throw new NativeException("支付服务未返回钱包链接");
        return link;
    }

    private static JSONObject lowestListing(JSONArray listings) {
        JSONObject result = null;
        double lowest = Double.NaN;
        if (listings == null) return null;
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            if (listing == null || listing.optBoolean("locked", false)) continue;
            double price = decimal(listing.opt("price"), Double.NaN);
            if (Double.isFinite(price) && (!Double.isFinite(lowest) || price < lowest)) {
                result = listing;
                lowest = price;
            }
        }
        return result;
    }

    private JSONObject currentPurchaseCandidate(IBoxDirectClient.Account account, String groupId, String expectedDigitalCollectionId, double maxPrice) throws Exception {
        if (!Double.isFinite(maxPrice) || maxPrice <= 0d) throw new NativeException("立即买入最高价无效");
        String expected = expectedDigitalCollectionId == null ? "" : expectedDigitalCollectionId.trim();
        JSONObject candidate = null;
        double lowest = Double.NaN;
        JSONArray listings = marketListings(account, groupId);
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            if (listing == null || listing.optBoolean("locked", false)) continue;
            String collectionId = listing.optString("digitalCollectionId");
            if (collectionId.isEmpty() || (!expected.isEmpty() && !expected.equals(collectionId))) continue;
            double price = decimal(listing.opt("price"), Double.NaN);
            if (!Double.isFinite(price) || price <= 0d || price > maxPrice) continue;
            if (candidate == null || price < lowest) {
                candidate = listing;
                lowest = price;
            }
        }
        if (candidate == null) {
            throw new NativeException(expected.isEmpty() ? "当前价格范围内没有可购买挂单" : "目标挂单已变化，请重新确认");
        }
        return candidate;
    }

    private static String listingKey(JSONObject listing) {
        return (listing == null ? "" : listing.optString("digitalCollectionId")) + ":" + (listing == null ? "" : listing.optString("tokenId"));
    }

    private static boolean contains(JSONArray values, String expected) {
        if (values == null) return false;
        for (int index = 0; index < values.length(); index++) if (expected.equals(String.valueOf(values.opt(index)))) return true;
        return false;
    }

    private static String deepString(Object source, String... keys) {
        return deepString(source, keys, 0);
    }

    private static String deepString(Object source, String[] keys, int depth) {
        if (source == null || source == JSONObject.NULL || depth > 6) return "";
        if (source instanceof JSONObject) {
            JSONObject object = (JSONObject) source;
            String direct = first(object, keys);
            if (!direct.isEmpty()) return direct;
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String value = deepString(object.opt(names.next()), keys, depth + 1);
                if (!value.isEmpty()) return value;
            }
        } else if (source instanceof JSONArray) {
            JSONArray values = (JSONArray) source;
            for (int index = 0; index < values.length(); index++) {
                String value = deepString(values.opt(index), keys, depth + 1);
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private JSONArray accountList() throws Exception {
        JSONArray source = store.getAccounts();
        JSONArray result = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject account = source.optJSONObject(index);
            if (account == null) continue;
            JSONObject summary = copy(account);
            summary.remove("token");
            summary.remove("shopToken");
            summary.put("tokenPresent", !account.optString("token").trim().isEmpty());
            summary.put("shopTokenPresent", !account.optString("shopToken").trim().isEmpty());
            result.put(summary);
        }
        return result;
    }

    private IBoxDirectClient.Account firstAccount() throws Exception {
        JSONArray accounts = store.getAccounts();
        JSONObject first = accounts.optJSONObject(0);
        if (first == null) throw new NativeException("请先使用短信登录添加 iBox 账号");
        return account(first.optString("phone"));
    }

    private IBoxDirectClient.Account account(String phone) throws Exception {
        JSONObject stored = store.getAccount(phone);
        if (stored == null) throw new NativeException("账号不存在，请重新登录");
        return new IBoxDirectClient.Account(
                stored.optString("phone"),
                stored.optString("token"),
                stored.optString("userId"),
                stored.optString("shopToken")
        );
    }

    private void cleanupSessions() {
        synchronized (smsSessions) {
            Iterator<Map.Entry<String, IBoxDirectClient.SmsSession>> values = smsSessions.entrySet().iterator();
            while (values.hasNext()) {
                Map.Entry<String, IBoxDirectClient.SmsSession> entry = values.next();
                if (entry.getValue() == null || entry.getValue().isExpired()) values.remove();
            }
        }
        synchronized (taskCaptchaSessions) {
            Iterator<Map.Entry<String, TaskCaptchaSession>> values = taskCaptchaSessions.entrySet().iterator();
            long now = System.currentTimeMillis();
            while (values.hasNext()) {
                TaskCaptchaSession session = values.next().getValue();
                if (session == null || session.expiresAt <= now) values.remove();
            }
        }
    }

    private synchronized void sendBark(String eventKey, String title, String body, String level) throws Exception {
        sendBark(eventKey, title, body, level, "");
    }

    private synchronized void sendBark(String eventKey, String title, String body, String level, String actionUrl) throws Exception {
        JSONObject config = barkSummary();
        if (!config.optBoolean("enabled", false)) return;
        if (eventKey != null && eventKey.startsWith("notify") && !config.optBoolean(eventKey, false)) return;
        String url = actionUrl == null ? "" : actionUrl.trim();
        String signature = Integer.toHexString((String.valueOf(eventKey) + "\n" + title + "\n" + body + "\n" + url).hashCode());
        long now = System.currentTimeMillis();
        if (!"test".equals(eventKey) && store.wasBarkSentRecently(signature, now, BARK_DEDUPE_WINDOW_MS)) return;
        JSONObject payload = objectOf(
                "device_key", config.optString("deviceKey"),
                "title", limit(title, 120),
                "body", limit(body, 1000),
                "group", "iBox",
                "level", level == null || level.trim().isEmpty() ? "active" : level.trim()
        );
        if (!url.isEmpty()) payload.put("url", url);
        HttpURLConnection connection = null;
        try {
            URL target = new URL(config.optString("server") + "/push");
            connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream ignored = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (ignored != null) ignored.close();
            if (status < 200 || status >= 300) throw new NativeException("Bark 推送失败（HTTP " + status + "）");
            if (!"test".equals(eventKey)) store.markBarkSent(signature, now);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void sendBarkBestEffort(JSONObject state, String eventKey, String title, String body, String level) {
        sendBarkBestEffort(state, eventKey, title, body, level, "");
    }

    private void sendBarkBestEffort(JSONObject state, String eventKey, String title, String body, String level, String actionUrl) {
        try {
            sendBark(eventKey, title, body, level, actionUrl);
            state.remove("notificationError");
        } catch (Exception error) {
            putQuietly(state, "notificationError", message(error));
        }
    }

    private static String normalizeBarkServer(String source) {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) return DEFAULT_BARK_SERVER;
        if (!(value.startsWith("https://") || value.startsWith("http://"))) return "";
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static BarkEndpoint parseBarkEndpoint(String source) throws NativeException {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) return new BarkEndpoint(DEFAULT_BARK_SERVER, "");
        if (!value.contains("://")) {
            if (!validBarkKey(value)) throw new NativeException("Bark 设备 Key 无效");
            return new BarkEndpoint(DEFAULT_BARK_SERVER, value);
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new NativeException("Bark 地址无效");
            }
            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (rawPath.endsWith("/")) rawPath = rawPath.substring(0, rawPath.length() - 1);
            int separator = rawPath.lastIndexOf('/');
            if (separator < 0 || separator == rawPath.length() - 1) throw new NativeException("Bark 地址缺少设备 Key");
            String rawKey = rawPath.substring(separator + 1);
            String key = new URI("https://bark.local/" + rawKey).getPath().substring(1);
            if (!validBarkKey(key)) throw new NativeException("Bark 设备 Key 无效");
            String basePath = rawPath.substring(0, separator);
            String server = scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + basePath;
            return new BarkEndpoint(server, key);
        } catch (URISyntaxException error) {
            throw new NativeException("Bark 地址无效");
        }
    }

    private static String formatBarkEndpoint(String server, String deviceKey) {
        String key = deviceKey == null ? "" : deviceKey.trim();
        if (key.isEmpty()) return "";
        String base = server == null ? "" : server.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty() || DEFAULT_BARK_SERVER.equalsIgnoreCase(base)) return key;
        return base + "/" + key;
    }

    private static boolean validBarkKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 512) return false;
        for (int index = 0; index < key.length(); index++) {
            char value = key.charAt(index);
            if (Character.isWhitespace(value) || value == '/' || value == '\\' || value == '?' || value == '#') return false;
        }
        return true;
    }

    private static final class BarkEndpoint {
        final String server;
        final String deviceKey;

        BarkEndpoint(String server, String deviceKey) {
            this.server = server;
            this.deviceKey = deviceKey;
        }
    }

    private static boolean captchaRequired(Exception error) {
        String value = message(error).toLowerCase(Locale.ROOT);
        return value.contains("captcha") || value.contains("geetest") || value.contains("人机") || value.contains("滑块") || value.contains("验证");
    }

    private static boolean terminalTaskStatus(String status) {
        return "payment_pending".equals(status) || "submitted".equals(status) || "cancelled".equals(status);
    }

    private static boolean withinWatchWindow(JSONObject watch, long now) {
        long observed = epoch(watch.optString("lastObservedAt", watch.optString("updatedAt", "")));
        return observed <= 0 || now - observed <= WATCH_NOTIFY_WINDOW_MS;
    }

    private static JSONObject matchMarketItem(JSONArray items, String collectionId, String title) {
        if (items == null) return null;
        JSONObject grouped = null;
        JSONObject named = null;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            String id = first(item, "id", "collectionId");
            if (!collectionId.isEmpty() && collectionId.equals(id)) return item;
            if (grouped == null && !collectionId.isEmpty() && collectionId.equals(first(item, "groupId", "digitalCollectionGroupId", "collectionGroupId"))) {
                grouped = item;
            }
            if (named == null && !title.isEmpty() && title.equals(first(item, "name", "title"))) named = item;
        }
        return grouped == null ? named : grouped;
    }

    private static JSONArray removeByPhone(JSONArray values, String phone) {
        JSONArray result = new JSONArray();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null && !phone.equals(first(value, "phone", "sourcePhone"))) result.put(value);
        }
        return result;
    }

    private static void addEvent(JSONObject target, String type, String detail) throws JSONException {
        JSONArray events = target.optJSONArray("events");
        if (events == null) {
            events = new JSONArray();
            target.put("events", events);
        }
        events.put(objectOf("type", type, "message", detail, "time", Instant.now().toString()));
        while (events.length() > 20) events.remove(0);
    }

    private static void addEventQuietly(JSONObject target, String type, String detail) {
        try {
            addEvent(target, type, detail);
        } catch (JSONException ignored) {
            // 状态记录失败不能覆盖正在处理的原始任务异常。
        }
    }

    private static JSONObject ok(Object data) throws JSONException {
        return objectOf("success", true, "data", data == null ? new JSONObject() : data);
    }

    private static JSONObject objectOf(Object... values) throws JSONException {
        if ((values.length & 1) != 0) throw new JSONException("object_values_not_pairs");
        JSONObject result = new JSONObject();
        for (int index = 0; index < values.length; index += 2) {
            String key = String.valueOf(values[index]);
            Object value = values[index + 1];
            result.put(key, value == null ? JSONObject.NULL : value);
        }
        return result;
    }

    private static JSONObject copy(JSONObject source) throws JSONException {
        return source == null ? new JSONObject() : new JSONObject(source.toString());
    }

    private static void putQuietly(JSONObject target, String key, Object value) {
        if (target == null) return;
        try {
            target.put(key, value);
        } catch (JSONException ignored) {
            // State values above are JSON primitives; retain the task loop if a malformed value slips through.
        }
    }

    private static JSONObject merge(JSONObject target, JSONObject extra) throws JSONException {
        if (extra == null) return target;
        Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, extra.opt(key));
        }
        return target;
    }

    private static Object data(JSONObject response) {
        if (response == null) return new JSONObject();
        Object value = response.opt("data");
        return value == null || value == JSONObject.NULL ? response : value;
    }

    private static JSONObject object(Object value) {
        return value instanceof JSONObject ? (JSONObject) value : new JSONObject();
    }

    private static JSONArray firstArray(Object source, String... keys) {
        if (source instanceof JSONArray) return (JSONArray) source;
        JSONObject object = object(source);
        for (String key : keys) {
            JSONArray value = object.optJSONArray(key);
            if (value != null) return value;
        }
        return new JSONArray();
    }

    private static String first(JSONObject source, String... keys) {
        if (source == null) return "";
        for (String key : keys) {
            Object value = source.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String string(JSONObject source, String key) {
        return source == null ? "" : source.optString(key, "").trim();
    }

    private static int integer(Object value, int fallback) {
        if (value == null || value == JSONObject.NULL) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double decimal(Object value, double fallback) {
        if (value == null || value == JSONObject.NULL) return fallback;
        try {
            double result = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(result) ? result : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Object numericOrNull(String value) {
        double number = decimal(value, Double.NaN);
        if (!Double.isFinite(number)) return JSONObject.NULL;
        return number == Math.rint(number) ? (long) number : number;
    }

    private static void putNumberOrNull(JSONObject target, String key, double value) throws JSONException {
        target.put(key, Double.isFinite(value) ? (value == Math.rint(value) ? (long) value : value) : JSONObject.NULL);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static double threshold(Object value, double fallback) {
        double number = decimal(value, fallback);
        return number < 0.1d || number > 100d ? fallback : Math.round(number * 100d) / 100d;
    }

    private static boolean taskDue(JSONObject task, String valueKey, String unitKey, int fallbackSeconds, long now) {
        long lastCheck = epoch(task == null ? "" : task.optString("lastCheckAt"));
        return lastCheck <= 0L || now - lastCheck >= intervalMillis(task, valueKey, unitKey, fallbackSeconds);
    }

    private static long intervalMillis(JSONObject task, String valueKey, String unitKey, int fallbackSeconds) {
        int value = integer(task == null ? null : task.opt(valueKey), fallbackSeconds);
        String unit = task == null ? "seconds" : task.optString(unitKey, "seconds").trim().toLowerCase(Locale.ROOT);
        long multiplier;
        if ("minutes".equals(unit)) multiplier = 60_000L;
        else if ("hours".equals(unit)) multiplier = 3_600_000L;
        else multiplier = 1_000L;
        long fallback = Math.max(1, fallbackSeconds) * 1_000L;
        if (value < 1 || value > 86_400 || value > Long.MAX_VALUE / multiplier) return fallback;
        long result = value * multiplier;
        return result > 86_400_000L ? fallback : result;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String limit(String value, int maximum) {
        String text = value == null ? "" : value;
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static String message(Throwable error) {
        if (error == null) return "请求失败";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }

    private static String encodePath(String value) {
        return Uri.encode(value == null ? "" : value.trim());
    }

    private static String isoTime(String value) {
        long timestamp = epoch(value);
        return timestamp <= 0 ? (value == null ? "" : value.trim()) : Instant.ofEpochMilli(timestamp).toString();
    }

    private static long epoch(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return -1L;
        try {
            long raw = Long.parseLong(text);
            return raw > 0L && raw < 100000000000L ? raw * 1000L : raw;
        } catch (Exception ignored) {
            // Continue with the timestamp formats returned by iBox.
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // iBox also uses China-local timestamps without an offset.
        }
        try {
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");
            return LocalDateTime.parse(text, format).toInstant(ZoneOffset.ofHours(8)).toEpochMilli();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static final class TaskCaptchaSession {
        final String id;
        final String kind;
        final String taskId;
        final long expiresAt;

        TaskCaptchaSession(String id, String kind, String taskId, long expiresAt) {
            this.id = id;
            this.kind = kind;
            this.taskId = taskId;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Route {
        final String path;
        final Uri uri;
        final String[] segments;

        private Route(String path, Uri uri) {
            this.path = path;
            this.uri = uri;
            String clean = path.startsWith("/") ? path.substring(1) : path;
            this.segments = clean.isEmpty() ? new String[0] : clean.split("/");
        }

        static Route parse(String raw) throws NativeException {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) throw new NativeException("原生功能路径为空");
            if (!value.startsWith(PREFIX)) throw new NativeException("原生功能路径无效");
            Uri uri = Uri.parse("https://native.invalid" + value);
            String path = uri.getPath();
            if (path == null || !path.startsWith(PREFIX)) throw new NativeException("原生功能路径无效");
            String relative = path.substring(PREFIX.length());
            return new Route(relative.isEmpty() ? "/" : relative, uri);
        }

        String query(String key) {
            String value = uri.getQueryParameter(key);
            return value == null ? "" : value;
        }

        String segment(int oneBasedIndex) {
            int index = oneBasedIndex - 1;
            return index < 0 || index >= segments.length ? "" : Uri.decode(segments[index]);
        }

        String tail(String prefix) {
            return path.startsWith(prefix) ? Uri.decode(path.substring(prefix.length())) : "";
        }

        String tailAfter(String marker) {
            int index = path.indexOf(marker);
            return index < 0 ? "" : Uri.decode(path.substring(index + marker.length()));
        }
    }

    public static final class NativeException extends Exception {
        NativeException(String message) {
            super(message);
        }
    }
}
