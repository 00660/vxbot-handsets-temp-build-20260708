package com.ibox.nativepanel;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Local panel business engine. It keeps panel state in the APK and calls only
 * official iBox endpoints through {@link IBoxDirectClient}.
 */
public final class NativeEngine {
    public static final String CAPTCHA_ID = "0d4b08eac1cbdcad36bbf607c5bf3e1b";

    private static final String PREFIX = "/native";
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
    private static final String MARKET_CONSIGNMENT_ORDER_URL = "https://sail-api.ibox.art/order-create-service/consignment-orders";
    private static final String MARKET_ADVANCE_ORDER_URL = "https://sail-api.ibox.art/order-create-service/advance-orders";
    private static final String MARKET_PURCHASE_CONSIGNMENT_URL = "https://sail-api.ibox.art/order-create-service/purchase-consignment-orders";
    private static final String OWNED_GROUP_URL = "https://sail-api.ibox.art/personal-center-service/users/digital-collection-groups";
    private static final String LOTTERY_HISTORY_URL = "https://sail-api.ibox.art/activity-service/lottery-activitys/history";
    private static final String LOTTERY_ACTIVITY_URL = "https://sail-api.ibox.art/activity-service/lottery-activitys";
    private static final String ORDER_LIST_URL = "https://sail-api.ibox.art/order-service/orders";
    private static final String PURCHASE_CONSIGNMENT_ORDER_LIST_URL = "https://sail-api.ibox.art/order-service/purchase-consignment-orders";
    private static final long CAPTCHA_TTL_MS = 2L * 60L * 1000L;
    private static final long WATCH_NOTIFY_WINDOW_MS = 5L * 60L * 1000L;
    private static final long MARKET_WATCH_MIN_INTERVAL_MS = 15L * 1000L;
    private static final Object TICK_LOCK = new Object();

    private final NativeStore store;
    private final IBoxDirectClient client;
    private final Map<String, IBoxDirectClient.SmsSession> smsSessions = new HashMap<>();
    private final Map<String, TaskCaptchaSession> taskCaptchaSessions = new HashMap<>();
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
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/assets") && "GET".equals(verb)) {
            String phone = route.segment(2);
            return refreshAssets(phone, integer(route.query("pageNo"), 1), integer(route.query("pageSize"), 50));
        }
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/orders") && "GET".equals(verb)) {
            return loadOrders(route.segment(2));
        }

        if ("/market".equals(route.path) && "GET".equals(verb)) return searchMarket(route.query("phone"), route.query("name"), integer(route.query("pageNo"), 1), integer(route.query("pageSize"), 20));
        if ("/market/watches".equals(route.path) && "GET".equals(verb)) {
            if ("1".equals(route.query("refresh"))) refreshMarketWatches(true);
            return ok(objectOf("watches", marketWatchList()));
        }
        if ("/market/watches".equals(route.path) && "POST".equals(verb)) return addMarketWatch(body);
        if (route.path.startsWith("/market/watches/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelMarketWatch(route.segment(3));
        if (route.path.startsWith("/market/watches/") && route.path.endsWith("/thresholds") && "PUT".equals(verb)) return updateMarketWatch(route.segment(3), body);

        if ("/synthesis/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", store.getSynthesisTasks()));
        if ("/synthesis/tasks".equals(route.path) && "POST".equals(verb)) return createSynthesisTask(body);
        if (route.path.startsWith("/synthesis/tasks/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelStoredTask(store.getSynthesisTasks(), route.segment(3), "synthesis");
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/synthesis/activities") && "GET".equals(verb)) return synthesisActivities(route.segment(2));
        if (route.path.startsWith("/accounts/") && route.path.contains("/synthesis/") && "GET".equals(verb)) return synthesisCenter(route.segment(2), route.segment(4));
        if (route.path.startsWith("/accounts/") && route.path.contains("/synthesis/") && route.path.endsWith("/submit") && "POST".equals(verb)) {
            return submitSynthesis(route.segment(2), route.segment(4), body);
        }

        if ("/first-sales".equals(route.path) && "GET".equals(verb)) return firstSales(route.query("phone"));
        if ("/first-sales/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", store.getFirstSaleTasks()));
        if ("/first-sales/tasks".equals(route.path) && "POST".equals(verb)) return createFirstSaleTask(body);
        if ("/first-sales/orders".equals(route.path) && "POST".equals(verb)) return submitFirstSale(body, null);
        if (route.path.startsWith("/first-sales/tasks/") && route.path.endsWith("/cancel") && "POST".equals(verb)) return cancelStoredTask(store.getFirstSaleTasks(), route.segment(3), "first_sale");
        if (route.path.startsWith("/first-sales/tasks/") && route.path.contains("/captcha/")) return taskCaptcha("first_sale", route, verb, body);
        if (route.path.startsWith("/accounts/") && route.path.endsWith("/first-sales/payment-platforms") && "GET".equals(verb)) return firstSalePaymentPlatforms(route.segment(2));

        if ("/lottery/auto".equals(route.path) && "GET".equals(verb)) return lotteryState();
        if (route.path.startsWith("/lottery/auto/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setLotteryEnabled(route.segment(3), true);
        if (route.path.startsWith("/lottery/auto/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setLotteryEnabled(route.segment(3), false);

        if ("/quant/strategies".equals(route.path) && "GET".equals(verb)) return ok(objectOf("strategies", store.getQuantStrategies()));
        if ("/quant/strategies".equals(route.path) && "POST".equals(verb)) return createQuantStrategy(body);
        if (route.path.startsWith("/quant/strategies/") && route.path.endsWith("/events") && "GET".equals(verb)) return quantEvents(route.segment(3));
        if (route.path.startsWith("/quant/strategies/") && route.path.contains("/captcha/")) return taskCaptcha("quant", route, verb, body);
        if (route.path.startsWith("/quant/strategies/") && "PUT".equals(verb)) return updateQuantStrategy(route.segment(3), body);
        if (route.path.startsWith("/quant/strategies/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setQuantEnabled(route.segment(3), true);
        if (route.path.startsWith("/quant/strategies/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setQuantEnabled(route.segment(3), false);
        if (route.path.startsWith("/quant/strategies/") && "DELETE".equals(verb)) return deleteQuantStrategy(route.segment(3));

        if ("/market/trade/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", marketTradeTasks()));
        if ("/market/trade/tasks".equals(route.path) && "POST".equals(verb)) return createTradeTask(body, "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.contains("/captcha/")) return taskCaptcha("market_trade", route, verb, body);
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setTradeEnabled(route.segment(4), true, "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setTradeEnabled(route.segment(4), false, "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/preflight") && "POST".equals(verb)) return preflightTradeTask(route.segment(4), "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && route.path.endsWith("/payment") && "POST".equals(verb)) return tradePayment(route.segment(4), "market_trade");
        if (route.path.startsWith("/market/trade/tasks/") && "DELETE".equals(verb)) return deleteTradeTask(route.segment(4), "market_trade");

        if ("/retired-market/tasks".equals(route.path) && "GET".equals(verb)) return ok(objectOf("tasks", retiredTasks()));
        if ("/retired-market/tasks".equals(route.path) && "POST".equals(verb)) return createTradeTask(body, "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && route.path.contains("/captcha/")) return taskCaptcha("retired_market", route, verb, body);
        if (route.path.startsWith("/retired-market/tasks/") && route.path.endsWith("/enable") && "POST".equals(verb)) return setTradeEnabled(route.segment(3), true, "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && route.path.endsWith("/disable") && "POST".equals(verb)) return setTradeEnabled(route.segment(3), false, "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && route.path.endsWith("/payment") && "POST".equals(verb)) return tradePayment(route.segment(3), "retired_market");
        if (route.path.startsWith("/retired-market/tasks/") && "DELETE".equals(verb)) return deleteTradeTask(route.segment(3), "retired_market");

        if ("/orders".equals(route.path) && "GET".equals(verb)) return loadOrders(route.query("phone"));
        if ("/notifications/bark".equals(route.path) && "GET".equals(verb)) return ok(barkSummary());
        if ("/notifications/bark".equals(route.path) && "PUT".equals(verb)) return saveBark(body);
        if ("/notifications/bark/test".equals(route.path) && "POST".equals(verb)) return testBark();

        throw new NativeException("原生功能路径不存在：" + route.path);
    }

    public void tick() {
        synchronized (TICK_LOCK) {
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
                processSynthesisTasks();
                processFirstSaleTasks();
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
        JSONObject assets = client.fetchAssets(account, Math.max(pageNo, 1), Math.max(pageSize, 1));
        JSONObject stored = store.getAccount(phone);
        if (stored != null) {
            JSONObject cache = objectOf("data", assets, "updatedAt", Instant.now().toString(), "stale", false);
            stored.put("assetCache", cache);
            store.upsertAccount(stored);
        }
        return ok(assets);
    }

    private JSONObject loadOrders(String requestedPhone) throws Exception {
        IBoxDirectClient.Account account = requestedPhone == null || requestedPhone.trim().isEmpty() ? firstAccount() : account(requestedPhone);
        OrderPage collectionOrders = OrderPage.empty();
        OrderPage consignmentOrders = OrderPage.empty();
        JSONArray failures = new JSONArray();
        try {
            collectionOrders = loadOrderPage(
                    account,
                    ORDER_LIST_URL,
                    objectOf("pageNo", 1, "pageSize", 40, "productType", 0, "orderType", 0, "orderStatus", 0),
                    "collection_pending"
            );
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

    private static JSONObject platformOrderSummary(JSONObject order, String type, String phone) throws JSONException {
        JSONObject preview = order.optJSONObject("productPreview");
        JSONObject collection = order.optJSONObject("digitalCollection");
        JSONObject result = new JSONObject();
        result.put("id", first(order, "id", "orderId", "orderNumber"));
        result.put("orderUuid", deepString(order, "orderUuid", "orderUUId"));
        result.put("listingOrderItemId", deepString(order, "listingOrderItemId"));
        result.put("type", type);
        result.put("groupId", first(order, "groupId", "digitalCollectionGroupId", "collectionGroupId"));
        String title = first(order, "name", "title", "productName", "digitalCollectionName");
        if (title.isEmpty()) title = assetName(preview);
        if (title.isEmpty()) title = assetName(collection);
        result.put("title", title.isEmpty() ? "未命名藏品" : title);
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
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) target.add(item);
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
        IBoxDirectClient.Account account = phone == null || phone.trim().isEmpty() ? firstAccount() : account(phone);
        return ok(client.searchMarkets(account, name, Math.max(pageNo, 1), clamp(pageSize, 1, 100)));
    }

    private JSONObject addMarketWatch(JSONObject body) throws Exception {
        JSONObject input = body == null ? new JSONObject() : body;
        String collectionId = first(input, "collectionId", "groupId", "id");
        String name = first(input, "name", "title", "searchTerm");
        if (collectionId.isEmpty() || name.isEmpty()) throw new NativeException("行情监控缺少藏品编号或名称");
        JSONArray watches = store.getMarketWatches();
        for (int index = 0; index < watches.length(); index++) {
            JSONObject existing = watches.optJSONObject(index);
            if (existing != null && collectionId.equals(existing.optString("collectionId")) && "active".equals(existing.optString("status", "active"))) {
                return ok(marketWatchSummary(existing));
            }
        }
        JSONObject watch = new JSONObject();
        double price = decimal(first(input, "floorPrice", "price"), Double.NaN);
        String now = Instant.now().toString();
        watch.put("id", UUID.randomUUID().toString());
        watch.put("collectionId", collectionId);
        watch.put("name", name);
        watch.put("searchTerm", first(input, "searchTerm", "name", "title"));
        watch.put("cover", first(input, "cover", "image"));
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
            if (watch == null || !id.equals(watch.optString("id"))) continue;
            watch.put("riseThresholdPercent", threshold(body == null ? null : body.opt("riseThresholdPercent"), watch.optDouble("riseThresholdPercent", 3d)));
            watch.put("fallThresholdPercent", threshold(body == null ? null : body.opt("fallThresholdPercent"), watch.optDouble("fallThresholdPercent", 3d)));
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
        if (Double.isFinite(initial) && initial > 0d && Double.isFinite(latest)) {
            watch.put("changePercent", Math.round((latest - initial) / initial * 10000d) / 100d);
        } else {
            watch.put("changePercent", JSONObject.NULL);
        }
        return watch;
    }

    private JSONArray marketWatchList() throws JSONException {
        JSONArray watches = store.getMarketWatches();
        JSONArray result = new JSONArray();
        for (int index = 0; index < watches.length(); index++) {
            JSONObject watch = watches.optJSONObject(index);
            if (watch != null) result.put(marketWatchSummary(watch));
        }
        return result;
    }

    private JSONObject synthesisActivities(String phone) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject response = client.requestAuthenticated(account, "GET", SYNTHESIS_ACTIVITY_LIST_URL, null, null, false, "合成活动");
        JSONArray source = firstArray(data(response), "activities", "list", "records", "items", "rows");
        JSONArray activities = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject activity = source.optJSONObject(index);
            if (activity == null) continue;
            JSONArray channels = firstArray(activity, "channels", "syntheticChannels", "syntheticActivityList", "synthetics");
            if (channels.length() == 0) {
                String directId = first(activity, "syntheticActivityId", "syntheticId", "id");
                if (!directId.isEmpty()) activities.put(synthesisActivity(activity, activity, directId));
                continue;
            }
            for (int channelIndex = 0; channelIndex < channels.length(); channelIndex++) {
                JSONObject channel = channels.optJSONObject(channelIndex);
                if (channel == null) continue;
                String syntheticId = first(channel, "syntheticActivityId", "syntheticId", "id");
                if (!syntheticId.isEmpty()) activities.put(synthesisActivity(activity, channel, syntheticId));
            }
        }
        return ok(objectOf("activities", activities, "updatedAt", Instant.now().toString()));
    }

    private JSONObject synthesisCenter(String phone, String syntheticId) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        JSONObject response = client.requestAuthenticated(account, "GET", SYNTHESIS_CENTER_URL + "/" + encodePath(syntheticId), null, null, false, "合成材料");
        JSONObject summary = synthesisCenterSummary(data(response), syntheticId);
        return ok(summary);
    }

    private JSONObject submitSynthesis(String phone, String syntheticId, JSONObject body) throws Exception {
        IBoxDirectClient.Account account = account(phone);
        if (account.userId.isEmpty()) throw new NativeException("合成需要账号用户标识，请重新登录");
        int count = Math.max(1, body == null ? 1 : body.optInt("syntheticNum", 1));
        JSONObject centerResponse = client.requestAuthenticated(account, "GET", SYNTHESIS_CENTER_URL + "/" + encodePath(syntheticId), null, null, false, "合成材料");
        JSONObject request = synthesisRequest(data(centerResponse), syntheticId, count);
        JSONObject confirmQuery = objectOf("uid", account.userId);
        JSONObject confirm = client.requestAuthenticated(account, "POST", SYNTHESIS_CONFIRM_URL, request, confirmQuery, true, "合成预检");
        JSONObject submit = client.requestAuthenticated(account, "POST", SYNTHESIS_SUBMIT_URL, request, null, true, "提交合成");
        return ok(objectOf("confirm", data(confirm), "submit", data(submit), "request", request));
    }

    private JSONObject createSynthesisTask(JSONObject body) throws Exception {
        JSONObject task = copy(body);
        String phone = first(task, "sourcePhone", "phone");
        String syntheticId = first(task, "syntheticId", "id");
        if (phone.isEmpty() || syntheticId.isEmpty()) throw new NativeException("合成任务缺少账号或合成编号");
        task.put("id", UUID.randomUUID().toString());
        task.put("phone", phone);
        task.put("sourcePhone", phone);
        task.put("syntheticId", syntheticId);
        task.put("syntheticNum", Math.max(1, task.optInt("syntheticNum", 1)));
        task.put("status", "scheduled");
        task.put("enabled", true);
        task.put("createdAt", Instant.now().toString());
        task.put("updatedAt", Instant.now().toString());
        String startAt = first(task, "startAt", "startTime");
        long scheduledAt = epoch(startAt);
        if (scheduledAt <= 0L) throw new NativeException("合成定时任务需要有效开始时间");
        task.put("startAt", Instant.ofEpochMilli(scheduledAt).toString());
        JSONArray tasks = store.getSynthesisTasks();
        tasks.put(task);
        if (!store.saveSynthesisTasks(tasks)) throw new NativeException("合成任务保存失败");
        return ok(task);
    }

    private JSONObject firstSales(String phone) throws Exception {
        IBoxDirectClient.Account account = phone == null || phone.trim().isEmpty() ? firstAccount() : account(phone);
        JSONObject query = objectOf("pageNo", 1, "pageSize", 40, "sortField", 0, "sortType", 1);
        JSONObject response = client.requestAuthenticated(account, "GET", FIRST_SALE_LIST_URL, null, query, false, "首发列表");
        JSONArray source = firstArray(data(response), "saleInfos", "list", "records", "items", "rows");
        JSONArray items = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null) items.put(firstSaleSummary(item));
        }
        return ok(objectOf("mode", "current", "items", items, "updatedAt", Instant.now().toString()));
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
            platform.put("activationStatus", integer(first(entry, "activationStatus", "status", "isOpen", "enabled", "available"), 0));
            platform.put("enabled", entry.optBoolean("enabled", entry.optBoolean("isEnabled", true)));
            platform.put("available", entry.optBoolean("available", entry.optBoolean("isAvailable", true)));
            items.put(platform);
        }
        return ok(objectOf("items", items));
    }

    private JSONObject createFirstSaleTask(JSONObject body) throws Exception {
        JSONObject task = copy(body);
        String phone = first(task, "sourcePhone", "phone");
        String saleId = first(task, "saleId", "id");
        if (phone.isEmpty() || saleId.isEmpty()) throw new NativeException("首发任务缺少账号或发售编号");
        task.put("id", UUID.randomUUID().toString());
        task.put("phone", phone);
        task.put("sourcePhone", phone);
        task.put("saleId", saleId);
        task.put("num", Math.max(1, task.optInt("num", 1)));
        task.put("mode", "immediate".equals(task.optString("mode")) ? "immediate" : "scheduled");
        task.put("phones", new JSONArray().put(phone));
        task.put("enabled", true);
        task.put("status", "scheduled");
        task.put("createdAt", Instant.now().toString());
        task.put("updatedAt", Instant.now().toString());
        String startAt = first(task, "startAt", "startTime");
        long scheduledAt = epoch(startAt);
        if (scheduledAt <= 0L) throw new NativeException("首发定时任务需要有效开始时间");
        task.put("startAt", Instant.ofEpochMilli(scheduledAt).toString());
        JSONArray tasks = store.getFirstSaleTasks();
        tasks.put(task);
        if (!store.saveFirstSaleTasks(tasks)) throw new NativeException("首发任务保存失败");
        if (scheduledAt <= System.currentTimeMillis()) {
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
        JSONObject response = client.requestAuthenticated(
                account(phone),
                "POST",
                FIRST_SALE_ORDER_URL + "/" + encodePath(saleId) + "/orders",
                request,
                captcha,
                objectOf("VERIFY-FLAG", "true"),
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
        long now = System.currentTimeMillis();
        if (!force && now - lastLotteryRefreshAt < 3000L) return;
        IBoxDirectClient.Account account = firstAccount();
        JSONObject historyResponse = client.requestAuthenticated(account, "GET", LOTTERY_HISTORY_URL, null, null, false, "抽奖历史");
        JSONArray history = firstArray(data(historyResponse), "activities", "list", "records", "items");
        JSONArray existing = store.getLotteryTasks();
        Map<String, JSONObject> previous = byId(existing);
        JSONArray activities = new JSONArray();
        int maximum = Math.min(history.length(), 30);
        for (int index = 0; index < maximum; index++) {
            JSONObject row = history.optJSONObject(index);
            String id = first(row, "id", "drawId", "lotteryActivityId");
            if (id.isEmpty()) continue;
            JSONObject detailResponse = client.requestAuthenticated(account, "GET", LOTTERY_ACTIVITY_URL + "/" + encodePath(id), null, null, false, "抽奖活动");
            JSONObject detail = object(data(detailResponse));
            JSONObject activity = new JSONObject();
            activity.put("id", id);
            activity.put("title", first(detail, "title", "name", "activityName"));
            activity.put("onlineStatus", integer(first(detail, "onlineStatus", "status"), 0));
            activity.put("enableOpen", bool(detail.opt("enableOpen")));
            activity.put("startedAt", first(detail, "startedAt", "startTime", "beginTime"));
            activity.put("endedAt", first(detail, "endedAt", "endTime", "finishedAt"));
            activity.put("startTime", activity.optString("startedAt"));
            activity.put("endTime", activity.optString("endedAt"));
            activity.put("enabled", previous.containsKey(id) ? previous.get(id).optBoolean("enabled", false) : false);
            activity.put("updatedAt", Instant.now().toString());
            JSONObject accounts = new JSONObject();
            JSONArray localAccounts = store.getAccounts();
            for (int accountIndex = 0; accountIndex < localAccounts.length(); accountIndex++) {
                JSONObject stored = localAccounts.optJSONObject(accountIndex);
                if (stored == null) continue;
                String phone = stored.optString("phone");
                try {
                    IBoxDirectClient.Account candidate = account(phone);
                    int count = availableLotteryCount(candidate, id);
                    accounts.put(phone, objectOf("availableCount", count, "status", count > 0 ? "ready" : "no_chance", "updatedAt", Instant.now().toString()));
                } catch (Exception error) {
                    accounts.put(phone, objectOf("availableCount", 0, "status", "failed", "message", message(error), "updatedAt", Instant.now().toString()));
                }
            }
            activity.put("accounts", accounts);
            activities.put(activity);
        }
        store.saveLotteryTasks(activities);
        lastLotteryRefreshAt = now;
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

    private JSONObject createQuantStrategy(JSONObject body) throws Exception {
        JSONObject strategy = copy(body);
        String phone = first(strategy, "phone", "sourcePhone");
        String groupId = first(strategy, "groupId", "collectionId", "id");
        if (phone.isEmpty() || groupId.isEmpty()) throw new NativeException("量化策略缺少账号或藏品编号");
        if (!groupId.matches("\\d+")) throw new NativeException("量化藏品编号无效");
        String executionMode = strategy.optString("executionMode", "monitor");
        if (!("monitor".equals(executionMode) || "live".equals(executionMode))) throw new NativeException("量化执行方式无效");
        JSONObject sell = strategy.optJSONObject("sell");
        if ("live".equals(executionMode) && sell != null && sell.optBoolean("enabled", false)
                && strategy.optString("consignPassword").trim().isEmpty()) {
            throw new NativeException("真实量化寄售需要交易密码");
        }
        String now = Instant.now().toString();
        strategy.put("id", UUID.randomUUID().toString());
        strategy.put("phone", phone);
        strategy.put("groupId", groupId);
        strategy.put("title", first(strategy, "title", "name", "groupName", "groupId"));
        strategy.put("enabled", true);
        strategy.put("status", "monitoring");
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
            merge(strategy, body);
            strategy.put("updatedAt", Instant.now().toString());
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
            strategy.put("enabled", enabled);
            strategy.put("status", enabled ? "monitoring" : "paused");
            strategy.put("updatedAt", Instant.now().toString());
            addEvent(strategy, enabled ? "enabled" : "paused", enabled ? "策略已启动" : "策略已暂停");
            changed = strategy;
            break;
        }
        if (changed == null) throw new NativeException("量化策略不存在");
        store.saveQuantStrategies(strategies);
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
                double triggerPrice = decimal(task.opt("triggerPrice"), Double.NaN);
                if (!Double.isFinite(triggerPrice) || triggerPrice <= 0d) throw new NativeException("寄售任务需要触发行情价");
                if (task.optString("consignPassword").trim().isEmpty()) throw new NativeException("寄售任务需要交易密码");
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
        JSONArray tasks = store.getTradeTasks();
        tasks.put(task);
        if (!store.saveTradeTasks(tasks)) throw new NativeException("交易任务保存失败");
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

    private JSONArray marketTradeTasks() {
        JSONArray all = store.getTradeTasks();
        JSONArray result = new JSONArray();
        for (int index = 0; index < all.length(); index++) {
            JSONObject task = all.optJSONObject(index);
            if (task != null && "market_trade".equals(task.optString("localType", "market_trade"))) result.put(task);
        }
        return result;
    }

    private JSONObject setTradeEnabled(String id, boolean enabled, String kind) throws Exception {
        JSONArray tasks = store.getTradeTasks();
        JSONObject changed = findTask(tasks, id, kind);
        if (changed == null) throw new NativeException("交易任务不存在");
        if (enabled && terminalTaskStatus(changed.optString("status"))) {
            throw new NativeException("已提交或待支付的交易任务不能重新启动");
        }
        changed.put("enabled", enabled);
        changed.put("status", enabled ? "scheduled" : "paused");
        changed.put("updatedAt", Instant.now().toString());
        addEvent(changed, enabled ? "enabled" : "paused", enabled ? "任务已启动" : "任务已暂停");
        store.saveTradeTasks(tasks);
        return ok(changed);
    }

    private JSONObject preflightTradeTask(String id, String kind) throws Exception {
        JSONArray tasks = store.getTradeTasks();
        JSONObject task = findTask(tasks, id, kind);
        if (task == null) throw new NativeException("交易任务不存在");
        JSONObject market = client.searchMarkets(account(task.optString("phone")), task.optString("title", task.optString("groupId")), 1, 20);
        JSONArray items = market.optJSONArray("items");
        JSONObject match = matchMarketItem(items, task.optString("groupId"), task.optString("title"));
        if (match == null) throw new NativeException("未找到交易目标的最新行情");
        task.put("latestFloorPrice", match.opt("floorPrice"));
        task.put("lastCheckAt", Instant.now().toString());
        task.put("lastResult", "preflight_ready");
        task.put("updatedAt", Instant.now().toString());
        addEvent(task, "preflight", "预检已完成");
        store.saveTradeTasks(tasks);
        return ok(objectOf("task", task, "market", match));
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
        } catch (JSONException ignored) {
            // JSONObject supports primitive fields.
        }
        return config;
    }

    private JSONObject saveBark(JSONObject body) throws Exception {
        JSONObject input = body == null ? new JSONObject() : body;
        boolean enabled = input.optBoolean("enabled", false);
        String server = normalizeBarkServer(input.optString("server", ""));
        String key = input.optString("deviceKey", "").trim();
        int hour = input.optInt("dailySummaryHour", 9);
        if (server.isEmpty()) throw new NativeException("Bark 服务地址无效");
        if (hour < 0 || hour > 23) throw new NativeException("每日汇总小时必须在 0 到 23 之间");
        if (enabled && (key.isEmpty() || key.length() > 512 || key.contains(" "))) throw new NativeException("Bark 设备密钥无效");
        if (!store.saveBarkConfig(enabled, server, key, hour)) throw new NativeException("Bark 配置保存失败");
        return ok(barkSummary());
    }

    private JSONObject testBark() throws Exception {
        JSONObject config = barkSummary();
        if (!config.optBoolean("enabled", false)) throw new NativeException("请先保存并启用 Bark 通知");
        sendBark("iBox Bark 已连接", "通知推送已启用。", "active");
        return ok(new JSONObject());
    }

    private void refreshMarketWatches(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && now - lastMarketWatchAt < MARKET_WATCH_MIN_INTERVAL_MS) return;
        JSONArray watches = store.getMarketWatches();
        if (watches.length() == 0) return;
        IBoxDirectClient.Account account = firstAccount();
        boolean changed = false;
        for (int index = 0; index < watches.length(); index++) {
            JSONObject watch = watches.optJSONObject(index);
            if (watch == null || !"active".equals(watch.optString("status", "active"))) continue;
            try {
                JSONObject market = client.searchMarkets(account, first(watch, "searchTerm", "name"), 1, 20);
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
                if ((change >= rise || change <= -fall) && withinWindow) {
                    String direction = change >= rise ? "上涨" : "下跌";
                    watch.put("lastNotifiedAt", Instant.now().toString());
                    watch.put("lastNotifiedPrice", price);
                    sendBark("iBox 行情阈值提醒 · " + watch.optString("name"),
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
        long now = System.currentTimeMillis();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optBoolean("enabled", true) || !"scheduled".equals(task.optString("status"))) continue;
            long startAt = epoch(task.optString("startAt"));
            if (startAt <= 0 || now < startAt) continue;
            try {
                JSONObject result = submitSynthesis(task.optString("phone"), task.optString("syntheticId"), task);
                task.put("status", "submitted");
                task.put("result", result.opt("data"));
                task.put("finishedAt", Instant.now().toString());
            } catch (Exception error) {
                putQuietly(task, "status", "failed");
                putQuietly(task, "lastResult", message(error));
            }
            putQuietly(task, "enabled", false);
            putQuietly(task, "updatedAt", Instant.now().toString());
            changed = true;
        }
        if (changed) store.saveSynthesisTasks(tasks);
    }

    private void processFirstSaleTasks() {
        JSONArray tasks = store.getFirstSaleTasks();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null || !task.optBoolean("enabled", true) || !"scheduled".equals(task.optString("status"))) continue;
            long startAt = epoch(task.optString("startAt"));
            if (startAt <= 0 || now < startAt) continue;
            try {
                JSONObject result = submitFirstSale(task, task.optJSONObject("captcha"));
                task.put("status", "submitted");
                task.put("result", result.opt("data"));
                task.put("enabled", false);
                task.put("finishedAt", Instant.now().toString());
            } catch (Exception error) {
                putQuietly(task, "lastResult", message(error));
                putQuietly(task, "status", captchaRequired(error) ? "verification_required" : "failed");
                if (!"verification_required".equals(task.optString("status"))) putQuietly(task, "enabled", false);
            }
            putQuietly(task, "updatedAt", Instant.now().toString());
            changed = true;
        }
        if (changed) store.saveFirstSaleTasks(tasks);
    }

    private void processQuantStrategies() {
        JSONArray strategies = store.getQuantStrategies();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int index = 0; index < strategies.length(); index++) {
            JSONObject strategy = strategies.optJSONObject(index);
            if (strategy == null || !strategy.optBoolean("enabled", false)
                    || !"monitoring".equals(strategy.optString("status"))
                    || !taskDue(strategy, "intervalValue", "intervalUnit", 15, now)) continue;
            try {
                IBoxDirectClient.Account account = account(strategy.optString("phone"));
                JSONArray listings = marketListings(account, strategy.optString("groupId"));
                JSONArray owned = ownedCollections(account, strategy.optString("groupId"));
                JSONObject lowest = lowestListing(listings);
                double marketPrice = lowest == null ? Double.NaN : decimal(lowest.opt("price"), Double.NaN);
                double previousPrice = decimal(strategy.opt("latestFloorPrice"), Double.NaN);
                if (Double.isFinite(marketPrice)) {
                    strategy.put("latestFloorPrice", marketPrice);
                    if (Double.isFinite(previousPrice) && previousPrice > 0d) {
                        double volatility = Math.abs(marketPrice - previousPrice) / previousPrice * 100d;
                        strategy.put("lastVolatilityPercent", volatility);
                        double limit = threshold(strategy.opt("volatilityLimitPercent"), 0d);
                        if (limit > 0d && volatility >= limit) {
                            strategy.put("enabled", false);
                            strategy.put("status", "volatility_paused");
                            strategy.put("lastResult", "price_volatility_paused");
                            strategy.put("lastCheckAt", Instant.now().toString());
                            strategy.put("updatedAt", Instant.now().toString());
                            addEvent(strategy, "volatility_paused", "单周期波动 " + String.format(Locale.US, "%.2f", volatility) + "%");
                            changed = true;
                            continue;
                        }
                    }
                }
                strategy.put("holdings", owned.length());
                strategy.put("lastCheckAt", Instant.now().toString());
                strategy.put("status", "monitoring");
                strategy.put("lastResult", "market_refreshed");
                if ("live".equals(strategy.optString("executionMode")) && lowest != null) {
                    if (tryQuantBuy(strategy, account, lowest, owned)) {
                        addEvent(strategy, "buy_submitted", "已创建买入订单，等待钱包支付");
                    } else if (tryQuantSell(strategy, account, owned, marketPrice)) {
                        addEvent(strategy, "sell_submitted", "寄售订单已提交");
                    }
                }
            } catch (Exception error) {
                putQuietly(strategy, "lastResult", message(error));
                if (captchaRequired(error)) putQuietly(strategy, "status", "verification_required");
                putQuietly(strategy, "lastCheckAt", Instant.now().toString());
            }
            putQuietly(strategy, "updatedAt", Instant.now().toString());
            changed = true;
        }
        if (changed) store.saveQuantStrategies(strategies);
    }

    private void processTradeTasks() {
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
                    processRetiredMarketTask(task);
                } else {
                    processMarketTradeTask(task);
                }
            } catch (Exception error) {
                putQuietly(task, "lastResult", message(error));
                if (captchaRequired(error)) putQuietly(task, "status", "verification_required");
                putQuietly(task, "lastCheckAt", Instant.now().toString());
            }
            putQuietly(task, "updatedAt", Instant.now().toString());
            changed = true;
        }
        if (changed) store.saveTradeTasks(tasks);
    }

    private void processMarketTradeTask(JSONObject task) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        String type = task.optString("type");
        JSONArray listings = marketListings(account, task.optString("groupId"));
        JSONObject lowest = lowestListing(listings);
        double floor = lowest == null ? Double.NaN : decimal(lowest.opt("price"), Double.NaN);
        if (Double.isFinite(floor)) task.put("latestFloorPrice", floor);
        task.put("lastCheckAt", Instant.now().toString());
        if ("consignment".equals(type)) {
            double trigger = decimal(task.opt("triggerPrice"), Double.NaN);
            if (!Double.isFinite(trigger) || trigger <= 0d) throw new NativeException("寄售任务缺少触发行情价");
            if (!Double.isFinite(floor) || floor < trigger) {
                task.put("status", "waiting_price");
                task.put("lastResult", "waiting_price");
                return;
            }
            String collectionId = resolveOwnedCollectionId(account, task);
            int paymentCode = paymentPlatformCode(account, 1, 0);
            String password = task.optString("consignPassword").trim();
            if (password.isEmpty()) throw new NativeException("寄售任务缺少交易密码");
            JSONObject request = objectOf(
                    "digitalCollectionId", integer(collectionId, 0),
                    "price", decimal(task.opt("price"), 0d),
                    "paymentPlatformCodes", new JSONArray().put(paymentCode),
                    "consignPassword", password
            );
            JSONObject response = client.requestAuthenticated(account, "POST", MARKET_CONSIGNMENT_ORDER_URL, request, null, task.optJSONObject("captcha"), true, "提交寄售");
            Object result = data(response);
            task.put("listingOrderItemId", deepString(result, "listingOrderItemId"));
            task.put("result", result);
            task.put("enabled", false);
            task.put("status", "submitted");
            task.put("lastResult", "submitted");
            task.put("submittedAt", Instant.now().toString());
            sendBark("iBox 寄售已提交", task.optString("title") + " · 寄售价 ¥" + task.optString("price"), "active");
            return;
        }
        if (!"wanted".equals(type)) throw new NativeException("交易任务类型无效");
        if (!task.optBoolean("agreementAccepted", false)) throw new NativeException("求购任务需要确认交易服务协议");
        int requested = task.optInt("paymentPlatformCode", 0);
        int paymentCode = paymentPlatformCode(account, 2, requested);
        JSONObject request = objectOf(
                "groupId", integer(task.opt("groupId"), 0),
                "buyCount", Math.max(1, task.optInt("quantity", 1)),
                "price", decimal(task.opt("price"), 0d),
                "paymentPlatformCode", paymentCode
        );
        JSONObject response = client.requestAuthenticated(account, "POST", MARKET_ADVANCE_ORDER_URL, request, null, task.optJSONObject("captcha"), true, "提交求购");
        String orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
        if (orderUuid.isEmpty()) throw new NativeException("求购订单未返回订单编号");
        String cashier = cashierLink(account, orderUuid, 2);
        task.put("orderUuid", orderUuid);
        task.put("cashierLink", cashier);
        task.put("paymentStatus", "pending");
        task.put("enabled", false);
        task.put("status", "payment_pending");
        task.put("lastResult", "payment_pending");
        task.put("submittedAt", Instant.now().toString());
        sendBark("iBox 求购待支付", task.optString("title") + " · 订单 " + orderUuid + " 已创建，等待钱包支付。", "active");
    }

    private void processRetiredMarketTask(JSONObject task) throws Exception {
        IBoxDirectClient.Account account = account(task.optString("phone"));
        JSONArray listings = marketListings(account, task.optString("groupId"));
        JSONArray baseline = task.optJSONArray("baselineListingKeys");
        if (baseline == null) {
            baseline = new JSONArray();
            for (int index = 0; index < listings.length(); index++) {
                JSONObject listing = listings.optJSONObject(index);
                if (listing != null) baseline.put(listingKey(listing));
            }
            task.put("baselineListingKeys", baseline);
            task.put("baselineEmptyAt", Instant.now().toString());
            task.put("monitorReadyAt", Instant.now().toString());
            task.put("lastCheckAt", Instant.now().toString());
            task.put("status", "scheduled");
            task.put("lastResult", "monitoring");
            return;
        }
        double min = decimal(task.opt("minPrice"), 0d);
        double max = decimal(task.opt("maxPrice"), Double.NaN);
        if (!Double.isFinite(max) || max <= 0d) throw new NativeException("捡漏任务价格范围无效");
        JSONObject candidate = null;
        for (int index = 0; index < listings.length(); index++) {
            JSONObject listing = listings.optJSONObject(index);
            if (listing == null || contains(baseline, listingKey(listing))) continue;
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
        JSONObject response = client.requestAuthenticated(account, "POST", MARKET_PURCHASE_CONSIGNMENT_URL, request, null, task.optJSONObject("captcha"), true, "锁定捡漏订单");
        String orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
        if (orderUuid.isEmpty()) throw new NativeException("捡漏订单未返回订单编号");
        String cashier = cashierLink(account, orderUuid, 0);
        task.put("pendingDigitalCollectionId", "");
        task.put("pendingPrice", JSONObject.NULL);
        task.put("lockedDigitalCollectionId", candidate.optString("digitalCollectionId"));
        task.put("lockedPrice", candidate.opt("price"));
        task.put("orderUuid", orderUuid);
        task.put("cashierLink", cashier);
        task.put("paymentStatus", "pending");
        task.put("enabled", false);
        task.put("status", "payment_pending");
        task.put("lastResult", "payment_pending");
        task.put("lockedAt", Instant.now().toString());
        sendBark("iBox 捡漏订单待支付", task.optString("title") + " · ¥" + candidate.optString("price") + "，等待钱包支付。", "active");
    }

    private boolean tryQuantBuy(JSONObject strategy, IBoxDirectClient.Account account, JSONObject candidate, JSONArray owned) throws Exception {
        JSONObject buy = strategy.optJSONObject("buy");
        if (buy == null || !buy.optBoolean("enabled", false)) return false;
        if (owned.length() >= Math.max(1, strategy.optInt("maxPosition", 1))) return false;
        double ceiling = decimal(buy.opt("maxPrice"), Double.NaN);
        double price = decimal(candidate.opt("price"), Double.NaN);
        if (!Double.isFinite(ceiling) || !Double.isFinite(price) || price > ceiling) return false;
        double minimumNetProfit = Math.max(0d, decimal(strategy.opt("minNetProfit"), 0d));
        JSONObject sell = strategy.optJSONObject("sell");
        double targetSellPrice = sell == null ? Double.NaN : decimal(sell.opt("sellPrice"), Double.NaN);
        if (minimumNetProfit > 0d && Double.isFinite(targetSellPrice)
                && targetSellPrice * 0.955d - price < minimumNetProfit) {
            strategy.put("lastResult", "profit_below_minimum");
            return false;
        }
        int paymentCode = paymentPlatformCode(account, 1, 0);
        JSONObject request = objectOf("digitalCollectionId", integer(candidate.opt("digitalCollectionId"), 0), "paymentPlatformCode", paymentCode);
        JSONObject response = client.requestAuthenticated(account, "POST", MARKET_PURCHASE_CONSIGNMENT_URL, request, null, strategy.optJSONObject("captcha"), true, "量化买入");
        String orderUuid = deepString(data(response), "orderId", "orderUUId", "orderUuid", "orderUUID", "uuid");
        if (orderUuid.isEmpty()) throw new NativeException("量化买入未返回订单编号");
        strategy.put("orderUuid", orderUuid);
        strategy.put("cashierLink", cashierLink(account, orderUuid, 0));
        strategy.put("paymentStatus", "pending");
        strategy.put("enabled", false);
        strategy.put("status", "payment_pending");
        return true;
    }

    private boolean tryQuantSell(JSONObject strategy, IBoxDirectClient.Account account, JSONArray owned, double marketPrice) throws Exception {
        JSONObject sell = strategy.optJSONObject("sell");
        if (sell == null || !sell.optBoolean("enabled", false)) return false;
        double trigger = decimal(sell.opt("minPrice"), Double.NaN);
        double salePrice = decimal(sell.opt("sellPrice"), Double.NaN);
        if (!Double.isFinite(trigger) || !Double.isFinite(salePrice) || !Double.isFinite(marketPrice) || marketPrice < trigger) return false;
        JSONObject asset = owned.optJSONObject(0);
        if (asset == null) return false;
        String password = strategy.optString("consignPassword").trim();
        if (password.isEmpty()) throw new NativeException("量化寄售缺少交易密码");
        int paymentCode = paymentPlatformCode(account, 1, 0);
        JSONObject request = objectOf(
                "digitalCollectionId", integer(asset.opt("id"), 0),
                "price", salePrice,
                "paymentPlatformCodes", new JSONArray().put(paymentCode),
                "consignPassword", password
        );
        JSONObject response = client.requestAuthenticated(account, "POST", MARKET_CONSIGNMENT_ORDER_URL, request, null, strategy.optJSONObject("captcha"), true, "量化寄售");
        strategy.put("listingOrderItemId", deepString(data(response), "listingOrderItemId"));
        strategy.put("enabled", false);
        strategy.put("status", "submitted");
        return true;
    }

    private void processLotteryAutoDraw() {
        JSONArray activities = store.getLotteryTasks();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (int index = 0; index < activities.length(); index++) {
            JSONObject activity = activities.optJSONObject(index);
            if (activity == null || !activity.optBoolean("enabled", false) || !lotteryOpen(activity, now)) continue;
            JSONObject accounts = activity.optJSONObject("accounts");
            if (accounts == null) continue;
            Iterator<String> phones = accounts.keys();
            while (phones.hasNext()) {
                String phone = phones.next();
                JSONObject state = accounts.optJSONObject(phone);
                if (state == null || state.optInt("availableCount", 0) <= 0 || "drawing".equals(state.optString("status"))) continue;
                try {
                    IBoxDirectClient.Account account = account(phone);
                    int count = Math.max(1, state.optInt("availableCount", 1));
                    JSONObject response = client.requestAuthenticated(account, "POST", LOTTERY_ACTIVITY_URL + "/" + encodePath(activity.optString("id")) + "/draw", objectOf("drawCount", count), null, true, "自动抽奖");
                    state.put("status", "drawn");
                    state.put("availableCount", 0);
                    state.put("result", data(response));
                    state.put("updatedAt", Instant.now().toString());
                    sendBark("iBox 自动抽奖 · " + activity.optString("title"), "账号 " + phone + " 已提交 " + count + " 次抽奖。", "active");
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

    private JSONObject synthesisActivity(JSONObject parent, JSONObject channel, String syntheticId) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("syntheticId", syntheticId);
        value.put("title", first(channel, "syntheticName", "channelName", "name", "title", "activityName"));
        if (value.optString("title").isEmpty()) value.put("title", first(parent, "activityName", "name", "title", "syntheticName"));
        value.put("name", value.optString("title"));
        value.put("status", first(channel, "syntheticStatus", "status", "workStatus"));
        value.put("startTime", first(channel, "startTime", "beginTime"));
        if (value.optString("startTime").isEmpty()) value.put("startTime", first(parent, "startTime", "beginTime"));
        value.put("endTime", first(channel, "endTime", "finishTime"));
        if (value.optString("endTime").isEmpty()) value.put("endTime", first(parent, "endTime", "finishTime"));
        value.put("supportAssistant", bool(channel.opt("supportAssistant")));
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
        try {
            JSONObject request = synthesisRequest(root, syntheticId, 1);
            result.put("canSubmit", !needsSlider);
            result.put("preferentialAlbumIds", request.optJSONArray("preferentialAlbumIds"));
        } catch (Exception error) {
            result.put("canSubmit", false);
            result.put("reason", message(error));
        }
        result.put("materials", firstArray(root, "burnAlbums", "materials", "materialGroups"));
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
        result.put("phase", status == 0 ? "preparing" : status == 5 ? "available" : status == 2 ? "sold_out" : "unavailable");
        result.put("startTime", start);
        result.put("startAt", isoTime(start));
        result.put("endTime", end);
        result.put("endAt", isoTime(end));
        result.put("userOnceMaxBuyNum", integer(first(value, "userOnceMaxBuyNum", "onceMaxBuyNum", "maxBuyNum", "limitNum"), 0));
        return result;
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
            normalized.put("locked", bool(entry.opt("locked")) || (collection != null && integer(first(collection, "lockStatus", "lockedStatus"), 0) > 0));
            normalized.put("name", collection == null ? first(entry, "name", "title") : first(collection, "name", "title", "digitalCollectionName"));
            normalized.put("cover", collection == null ? first(entry, "coverPicUrl", "coverUrl") : first(collection, "coverPicUrl", "coverUrl"));
            if (!normalized.optString("digitalCollectionId").isEmpty()) result.put(normalized);
        }
        return result;
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
            normalized.put("id", first(entry, "digitalCollectionId", "digitalCollectionID", "collectionId", "id"));
            if (normalized.optString("id").isEmpty() && collection != null) normalized.put("id", first(collection, "id"));
            normalized.put("quantity", integer(first(entry, "holdNum", "holdCount", "quantity", "count", "num"), 1));
            normalized.put("locked", integer(first(entry, "lockStatus", "lockedStatus"), 0) > 0);
            normalized.put("name", collection == null ? first(entry, "name", "title") : first(collection, "name", "title"));
            if (!normalized.optString("id").isEmpty() && !normalized.optBoolean("locked", false)) result.put(normalized);
        }
        return result;
    }

    private String resolveOwnedCollectionId(IBoxDirectClient.Account account, JSONObject task) throws Exception {
        JSONArray assets = ownedCollections(account, task.optString("groupId"));
        String requested = task.optString("digitalCollectionId");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.optJSONObject(index);
            if (asset != null && requested.equals(asset.optString("id"))) return requested;
        }
        JSONObject first = assets.optJSONObject(0);
        if (first == null || first.optString("id").isEmpty()) throw new NativeException("账号没有可寄售的该藏品资产");
        String resolved = first.optString("id");
        task.put("digitalCollectionId", resolved);
        return resolved;
    }

    private int paymentPlatformCode(IBoxDirectClient.Account account, int placeOrderMethod, int requestedCode) throws Exception {
        JSONObject response = client.requestAuthenticated(account, "GET", PAYMENT_PLATFORMS_URL, null, objectOf("placeOrderMethod", placeOrderMethod), false, "支付通道");
        JSONArray platforms = firstArray(data(response), "paymentPlatforms", "platforms", "paymentPlatformList", "paymentMethods", "list", "records", "items", "rows", "data");
        int fallback = 0;
        for (int index = 0; index < platforms.length(); index++) {
            JSONObject item = platforms.optJSONObject(index);
            if (item == null) continue;
            int code = integer(first(item, "paymentPlatformCode", "platformCode", "code"), 0);
            int activation = integer(first(item, "activationStatus", "status", "isOpen", "enabled", "available"), 0);
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

    private void sendBark(String title, String body, String level) throws Exception {
        JSONObject config = barkSummary();
        if (!config.optBoolean("enabled", false)) return;
        JSONObject payload = objectOf(
                "device_key", config.optString("deviceKey"),
                "title", limit(title, 120),
                "body", limit(body, 1000),
                "group", "iBox",
                "level", level == null || level.trim().isEmpty() ? "active" : level.trim()
        );
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
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String normalizeBarkServer(String source) {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) return "https://api.day.app";
        if (!(value.startsWith("https://") || value.startsWith("http://"))) return "";
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
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

    private static JSONObject matchMarketItem(JSONArray items, String groupId, String title) {
        if (items == null) return null;
        JSONObject named = null;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            String id = first(item, "groupId", "collectionId", "id");
            if (!groupId.isEmpty() && groupId.equals(id)) return item;
            if (named == null && !title.isEmpty() && title.equals(first(item, "name", "title"))) named = item;
        }
        return named;
    }

    private static Map<String, JSONObject> byId(JSONArray values) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null && !value.optString("id").isEmpty()) result.put(value.optString("id"), value);
        }
        return result;
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
