package com.ibox.nativepanel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Process-local persistence for the native iBox engine.
 *
 * <p>Every mutable JSON value is copied on input and output so callers cannot
 * mutate the stored state without an explicit save call.</p>
 */
public final class NativeStore {
    private static final String PREFS_NAME = "ibox_native_store";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SELECTED_PHONE = "selected_phone";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_MARKET_WATCHES = "market_watches_json";
    private static final String KEY_QUANT_STRATEGIES = "quant_strategies_json";
    private static final String KEY_TRADE_TASKS = "trade_tasks_json";
    private static final String KEY_SYNTHESIS_TASKS = "synthesis_tasks_json";
    private static final String KEY_LOTTERY_TASKS = "lottery_tasks_json";
    private static final String KEY_FIRST_SALE_TASKS = "first_sale_tasks_json";
    private static final String KEY_ASSET_COSTS = "asset_costs_json";
    private static final String KEY_BARK_CONFIG = "bark_config_json";
    private static final String KEY_TRADE_PASSWORD = "trade_password";
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    public NativeStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getOrCreateDeviceId() {
        synchronized (LOCK) {
            String deviceId = trim(preferences.getString(KEY_DEVICE_ID, ""));
            if (!deviceId.isEmpty()) return deviceId;
            deviceId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_DEVICE_ID, deviceId).commit();
            return deviceId;
        }
    }

    public boolean saveDeviceId(String deviceId) {
        synchronized (LOCK) {
            String value = trim(deviceId);
            if (value.isEmpty()) return false;
            return preferences.edit().putString(KEY_DEVICE_ID, value).commit();
        }
    }

    public String getSelectedPhone() {
        synchronized (LOCK) {
            return trim(preferences.getString(KEY_SELECTED_PHONE, ""));
        }
    }

    public boolean setSelectedPhone(String phone) {
        synchronized (LOCK) {
            String value = trim(phone);
            if (!value.isEmpty() && findAccount(readArray(KEY_ACCOUNTS), value) == null) return false;
            return preferences.edit().putString(KEY_SELECTED_PHONE, value).commit();
        }
    }

    public JSONArray getAccounts() {
        synchronized (LOCK) {
            return copyArray(readArray(KEY_ACCOUNTS));
        }
    }

    public JSONObject getAccount(String phone) {
        synchronized (LOCK) {
            JSONObject account = findAccount(readArray(KEY_ACCOUNTS), trim(phone));
            return account == null ? null : copyObject(account);
        }
    }

    public boolean saveAccounts(JSONArray accounts) {
        synchronized (LOCK) {
            JSONArray normalized = normalizeAccounts(accounts);
            String selectedPhone = trim(preferences.getString(KEY_SELECTED_PHONE, ""));
            if (findAccount(normalized, selectedPhone) == null) {
                JSONObject first = normalized.optJSONObject(0);
                selectedPhone = first == null ? "" : first.optString("phone", "");
            }
            return preferences.edit()
                    .putString(KEY_ACCOUNTS, normalized.toString())
                    .putString(KEY_SELECTED_PHONE, selectedPhone)
                    .commit();
        }
    }

    public boolean saveAccount(String phone, String token, String shopToken, String userId, String name) {
        JSONObject account = new JSONObject();
        try {
            account.put("phone", trim(phone));
            account.put("token", trim(token));
            account.put("shopToken", trim(shopToken));
            account.put("userId", trim(userId));
            account.put("name", trim(name));
            account.put("lastLoginAt", Instant.now().toString());
        } catch (JSONException ignored) {
            return false;
        }
        return upsertAccount(account);
    }

    public boolean upsertAccount(JSONObject account) {
        synchronized (LOCK) {
            JSONObject incoming = normalizeAccount(account);
            if (incoming == null) return false;
            JSONArray accounts = readArray(KEY_ACCOUNTS);
            JSONArray updated = new JSONArray();
            String phone = incoming.optString("phone", "");
            boolean replaced = false;
            for (int index = 0; index < accounts.length(); index++) {
                JSONObject current = accounts.optJSONObject(index);
                if (current != null && phone.equals(trim(current.optString("phone", "")))) {
                    updated.put(mergeAccounts(current, incoming));
                    replaced = true;
                } else if (current != null) {
                    updated.put(normalizeAccount(current));
                }
            }
            if (!replaced) updated.put(incoming);
            String selectedPhone = trim(preferences.getString(KEY_SELECTED_PHONE, ""));
            if (selectedPhone.isEmpty()) selectedPhone = phone;
            return preferences.edit()
                    .putString(KEY_ACCOUNTS, updated.toString())
                    .putString(KEY_SELECTED_PHONE, selectedPhone)
                    .commit();
        }
    }

    public boolean removeAccount(String phone) {
        synchronized (LOCK) {
            String target = trim(phone);
            if (target.isEmpty()) return false;
            JSONArray accounts = readArray(KEY_ACCOUNTS);
            JSONArray remaining = new JSONArray();
            boolean removed = false;
            for (int index = 0; index < accounts.length(); index++) {
                JSONObject account = accounts.optJSONObject(index);
                if (account == null) continue;
                if (target.equals(trim(account.optString("phone", "")))) {
                    removed = true;
                } else {
                    remaining.put(normalizeAccount(account));
                }
            }
            if (!removed) return false;
            String selectedPhone = trim(preferences.getString(KEY_SELECTED_PHONE, ""));
            if (target.equals(selectedPhone)) {
                JSONObject first = remaining.optJSONObject(0);
                selectedPhone = first == null ? "" : first.optString("phone", "");
            }
            JSONObject costs = readObject(KEY_ASSET_COSTS);
            costs.remove(target);
            return preferences.edit()
                    .putString(KEY_ACCOUNTS, remaining.toString())
                    .putString(KEY_SELECTED_PHONE, selectedPhone)
                    .putString(KEY_ASSET_COSTS, costs.toString())
                    .commit();
        }
    }

    public JSONArray getMarketWatches() {
        return getJsonArray(KEY_MARKET_WATCHES);
    }

    public boolean saveMarketWatches(JSONArray watches) {
        return saveJsonArray(KEY_MARKET_WATCHES, watches);
    }

    public JSONArray getQuantStrategies() {
        return getJsonArray(KEY_QUANT_STRATEGIES);
    }

    public boolean saveQuantStrategies(JSONArray strategies) {
        return saveJsonArray(KEY_QUANT_STRATEGIES, strategies);
    }

    public JSONArray getTradeTasks() {
        return getJsonArray(KEY_TRADE_TASKS);
    }

    public boolean saveTradeTasks(JSONArray tasks) {
        return saveJsonArray(KEY_TRADE_TASKS, tasks);
    }

    public JSONArray getSynthesisTasks() {
        return getJsonArray(KEY_SYNTHESIS_TASKS);
    }

    public boolean saveSynthesisTasks(JSONArray tasks) {
        return saveJsonArray(KEY_SYNTHESIS_TASKS, tasks);
    }

    public JSONArray getLotteryTasks() {
        return getJsonArray(KEY_LOTTERY_TASKS);
    }

    public boolean saveLotteryTasks(JSONArray tasks) {
        return saveJsonArray(KEY_LOTTERY_TASKS, tasks);
    }

    public JSONArray getFirstSaleTasks() {
        return getJsonArray(KEY_FIRST_SALE_TASKS);
    }

    public boolean saveFirstSaleTasks(JSONArray tasks) {
        return saveJsonArray(KEY_FIRST_SALE_TASKS, tasks);
    }

    public String getTradePassword() {
        synchronized (LOCK) {
            return trim(preferences.getString(KEY_TRADE_PASSWORD, ""));
        }
    }

    public boolean saveTradePassword(String password) {
        synchronized (LOCK) {
            String value = trim(password);
            if (value.isEmpty()) return false;
            JSONArray strategies = stripTaskPasswords(readArray(KEY_QUANT_STRATEGIES));
            JSONArray tradeTasks = stripTaskPasswords(readArray(KEY_TRADE_TASKS));
            JSONArray firstSaleTasks = stripTaskPasswords(readArray(KEY_FIRST_SALE_TASKS));
            return preferences.edit()
                    .putString(KEY_TRADE_PASSWORD, value)
                    .putString(KEY_QUANT_STRATEGIES, strategies.toString())
                    .putString(KEY_TRADE_TASKS, tradeTasks.toString())
                    .putString(KEY_FIRST_SALE_TASKS, firstSaleTasks.toString())
                    .commit();
        }
    }

    public double getAssetCost(String phone, String assetId) {
        synchronized (LOCK) {
            JSONObject accountCosts = readObject(KEY_ASSET_COSTS).optJSONObject(trim(phone));
            if (accountCosts == null) return Double.NaN;
            Object raw = accountCosts.opt(trim(assetId));
            if (raw == null || raw == JSONObject.NULL) return Double.NaN;
            try {
                double value = Double.parseDouble(String.valueOf(raw));
                return Double.isFinite(value) && value > 0d ? value : Double.NaN;
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
    }

    public boolean saveAssetCost(String phone, String assetId, double unitCost) {
        synchronized (LOCK) {
            String normalizedPhone = trim(phone);
            String normalizedAssetId = trim(assetId);
            if (normalizedPhone.isEmpty() || normalizedAssetId.isEmpty()
                    || !Double.isFinite(unitCost) || unitCost <= 0d) return false;
            JSONObject costs = readObject(KEY_ASSET_COSTS);
            JSONObject accountCosts = costs.optJSONObject(normalizedPhone);
            if (accountCosts == null) accountCosts = new JSONObject();
            try {
                accountCosts.put(normalizedAssetId, unitCost);
                costs.put(normalizedPhone, accountCosts);
            } catch (JSONException ignored) {
                return false;
            }
            return preferences.edit().putString(KEY_ASSET_COSTS, costs.toString()).commit();
        }
    }

    public JSONObject getBarkConfig() {
        synchronized (LOCK) {
            return normalizeBark(readObject(KEY_BARK_CONFIG));
        }
    }

    public boolean saveBarkConfig(JSONObject config) {
        synchronized (LOCK) {
            return preferences.edit()
                    .putString(KEY_BARK_CONFIG, normalizeBark(config).toString())
                    .commit();
        }
    }

    public boolean saveBarkConfig(boolean enabled, String server, String deviceKey, int dailySummaryHour) {
        JSONObject config = new JSONObject();
        try {
            config.put("enabled", enabled);
            config.put("server", trim(server));
            config.put("deviceKey", trim(deviceKey));
            config.put("dailySummaryHour", dailySummaryHour);
        } catch (JSONException ignored) {
            return false;
        }
        return saveBarkConfig(config);
    }

    private JSONArray getJsonArray(String key) {
        synchronized (LOCK) {
            return copyArray(readArray(key));
        }
    }

    private boolean saveJsonArray(String key, JSONArray values) {
        synchronized (LOCK) {
            return preferences.edit().putString(key, copyArray(values).toString()).commit();
        }
    }

    private JSONArray readArray(String key) {
        String raw = preferences.getString(key, "[]");
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private JSONObject readObject(String key) {
        String raw = preferences.getString(key, "{}");
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private JSONArray normalizeAccounts(JSONArray source) {
        JSONArray result = new JSONArray();
        Set<String> phones = new HashSet<>();
        if (source == null) return result;
        for (int index = 0; index < source.length(); index++) {
            JSONObject account = normalizeAccount(source.optJSONObject(index));
            if (account == null) continue;
            String phone = account.optString("phone", "");
            if (phones.add(phone)) result.put(account);
        }
        return result;
    }

    private JSONObject normalizeAccount(JSONObject source) {
        if (source == null) return null;
        JSONObject account = copyObject(source);
        String phone = trim(account.optString("phone", ""));
        if (phone.isEmpty()) return null;
        try {
            account.put("phone", phone);
            account.put("token", trim(account.optString("token", "")));
            account.put("shopToken", trim(account.optString("shopToken", "")));
            account.put("userId", trim(account.optString("userId", "")));
            String name = trim(account.optString("name", ""));
            if (name.isEmpty()) name = trim(account.optString("nickname", account.optString("userName", "")));
            account.put("name", name);
            if (!account.has("lastLoginAt")) account.put("lastLoginAt", "");
        } catch (JSONException ignored) {
            return null;
        }
        return account;
    }

    private JSONObject mergeAccounts(JSONObject previous, JSONObject incoming) {
        JSONObject merged = copyObject(previous);
        Iterator<String> keys = incoming.keys();
        try {
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = incoming.opt(key);
                if (preserveExistingAccountValue(key, value)
                        && merged.has(key)) {
                    continue;
                }
                merged.put(key, value);
            }
        } catch (JSONException ignored) {
            return incoming;
        }
        return normalizeAccount(merged);
    }

    private boolean preserveExistingAccountValue(String key, Object value) {
        if (!("token".equals(key) || "shopToken".equals(key) || "userId".equals(key)
                || "name".equals(key) || "lastLoginAt".equals(key))) {
            return false;
        }
        return value == null || value == JSONObject.NULL
                || (value instanceof String && trim((String) value).isEmpty());
    }

    private JSONObject findAccount(JSONArray accounts, String phone) {
        if (accounts == null || phone == null || phone.isEmpty()) return null;
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject account = accounts.optJSONObject(index);
            if (account != null && phone.equals(trim(account.optString("phone", "")))) return account;
        }
        return null;
    }

    private JSONObject normalizeBark(JSONObject source) {
        JSONObject config = copyObject(source);
        try {
            config.put("enabled", config.optBoolean("enabled", false));
            config.put("server", trim(config.optString("server", "")));
            config.put("deviceKey", trim(config.optString("deviceKey", "")));
            int hour = config.optInt("dailySummaryHour", 9);
            config.put("dailySummaryHour", hour < 0 || hour > 23 ? 9 : hour);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
        return config;
    }

    private JSONArray stripTaskPasswords(JSONArray source) {
        JSONArray sanitized = copyArray(source);
        for (int index = 0; index < sanitized.length(); index++) {
            JSONObject item = sanitized.optJSONObject(index);
            if (item == null) continue;
            item.remove("consignPassword");
            item.remove("paymentPassword");
        }
        return sanitized;
    }

    private JSONArray copyArray(JSONArray source) {
        if (source == null) return new JSONArray();
        try {
            return new JSONArray(source.toString());
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private JSONObject copyObject(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
