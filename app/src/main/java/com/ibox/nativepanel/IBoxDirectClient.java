package com.ibox.nativepanel;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** iBox 官方接口的同步直连客户端，不经由本地面板服务。 */
public final class IBoxDirectClient {
    private static final String SMS_URL = "https://sail-api.ibox.art/sms-service/sms-messages";
    private static final String LOGIN_URL = "https://sail-api.ibox.art/box-server/api/v1/login/verify";
    private static final String ASSETS_URL = "https://sail-api.ibox.art/personal-center-service/users/digital-collection-groups";
    private static final String MARKET_URL = "https://sail-api.ibox.art/public-service/markets";
    private static final String APP_VERSION = "3.0.5";
    private static final String APP_BUILD = "30005";
    private static final String AES_KEY_CHARACTERS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String PUBLIC_KEY_HEX = "30819d300d06092a864886f70d010101050003818b0030818702818100984c206bc702184ef2a6be0dc2dbf14f9be338b2675e29722204e3425f338ec5f1dd3a0191bde6b52eda0147f909e0ea5e3ec17fd82372def4b9f755e16378a95006a9349a9350719fd352f863af8d29d8b2502d9572b2eb1048c01ae0ac587f3289c710e9b0447452435f46fe5574513d542691a8129d5bb94caab75c69cc71020103";
    private static final String RESPONSE_PRIVATE_KEY_B64 = "MIICdAIBADANBgkqhkiG9w0BAQEFAASCAl4wggJaAgEAAoGBAJhMIGvHAhhO8qa+DcLb8U+b4ziyZ14pciIE40JfM47F8d06AZG95rUu2gFH+Qng6l4+wX/YI3Le9Ln3VeFjeKlQBqk0mpNQcZ/TUvhjr40p2LJQLZVysusQSMAa4KxYfzKJxxDpsER0UkNfRv5VdFE9VCaRqBKdW7lMqrdcacxxAgEDAoGAGWIFZ0vVrrfTG8pXoHn9jUSl3shmj7GTBat7NbqIl8uoT4mq7Z+mc4fPADapgaV8ZQp1lU6wkyUoyak4+uXpcUtqqX3LbBRScLv3LcCePLYNtsGldoiqqDywtzzkVZDtqyyE0nZ+Bs10/o+OF7Ye8U0a3jvJYjzObb7xppbCrlMCQQDQ0CVT6jdi1LqNC44+E6cey6+HULLl7HFAN1TBfXUEjsIm3xVUDTkXV9vWqXy6UmOe7HXP4HwBIR6nptHKfLiFAkEAuraK7evTc65A3nxXoeZ5xrq6PvwbWMaIY+0f7Ak17l5tV8sMzq7ijDxwK0jzVmhFz8Z7Ww9JL2QIK1n+CVz9/QJBAIs1bjfxekHjJwiyXtQNGhSHylo1zJlIS4Ak4yuo+K20gW8/Y41eJg+P5+Rw/dGMQmny+TVAUqtracUZ4TGoewMCQHx5sfPyjPfJgJRS5RaZpoR8fCn9Z5CEWu1Iv/Kwzp7pnjqHXd8fQbLS9XIwojma2TUu/Odfhh+YBXI7/rDoqVMCQBUYFJvc5M8WnN3uOqxP11WwUSTKZpDugMiD63/bDcdESAcutglx5RMhszXFyfMr7K22Zc7F8BpY2HCzcRWp65o=";
    private static final long SMS_SESSION_TTL_MS = 2L * 60L * 1000L;
    private static final long LOGIN_SESSION_TTL_MS = 10L * 60L * 1000L;

    private final String deviceId;
    private final SecureRandom random = new SecureRandom();

    public IBoxDirectClient(String deviceId) {
        String value = deviceId == null ? "" : deviceId.trim();
        this.deviceId = value.isEmpty() ? UUID.randomUUID().toString() : value;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public SmsSession createSmsSession(String phone) throws ApiException {
        String normalizedPhone = phone == null ? "" : phone.trim();
        if (!normalizedPhone.matches("^\\d{11}$")) {
            throw new ApiException("手机号必须是 11 位数字");
        }
        return new SmsSession(normalizedPhone, System.currentTimeMillis() + SMS_SESSION_TTL_MS);
    }

    /** GeeTest 成功回调后调用，返回 confirmed 或 pending_code。 */
    public String sendSms(SmsSession session, CaptchaResult captcha) throws Exception {
        requireReadyForSms(session);
        if (captcha == null || !captcha.isComplete()) {
            throw new ApiException("缺少完整的 GeeTest 验证结果");
        }

        session.status = "sending";
        try {
            JSONObject body = new JSONObject();
            body.put("phone", session.phone);
            body.put("smsType", 1);
            EncryptedEnvelope request = createEncryptedEnvelope(body);
            Map<String, String> headers = guestHeaders(session);
            headers.put("Verify-Flag", "true");
            HttpResponse response = request("POST", appendCaptchaQuery(SMS_URL, captcha), headers, request.payload.toString(), session);
            Object wirePayload = parseJson(response, "短信验证码");
            boolean encrypted = isEncryptedEnvelope(wirePayload);
            Object payload = encrypted ? decryptWithRequestKey((JSONObject) wirePayload, request.aesKey) : wirePayload;

            if (isBusinessSuccess(payload)) {
                session.status = "sent";
                session.expiresAt = System.currentTimeMillis() + LOGIN_SESSION_TTL_MS;
                return "confirmed";
            }
            if (encrypted && payload == null) {
                session.status = "pending_code";
                session.expiresAt = System.currentTimeMillis() + LOGIN_SESSION_TTL_MS;
                return "pending_code";
            }
            session.status = "failed";
            throw businessException("短信验证码", response.status, payload);
        } catch (Exception error) {
            if (!(error instanceof ApiException) || !"pending_code".equals(session.status)) {
                session.status = "failed";
            }
            throw error;
        }
    }

    public Account login(SmsSession session, String code) throws Exception {
        return login(session, code, "");
    }

    public Account login(SmsSession session, String code, String invitationCode) throws Exception {
        requireReadyForLogin(session);
        String normalizedCode = code == null ? "" : code.trim();
        if (normalizedCode.isEmpty()) {
            throw new ApiException("请输入短信验证码");
        }

        JSONObject body = new JSONObject();
        body.put("phoneNumber", session.phone);
        body.put("code", normalizedCode);
        String invitation = invitationCode == null ? "" : invitationCode.trim();
        if (!invitation.isEmpty()) body.put("invitationCode", invitation);

        HttpResponse response = request("POST", LOGIN_URL, guestHeaders(session), body.toString(), session);
        JSONObject payload = authenticatedPayload(response, "登录");
        JSONObject data = payload.optJSONObject("data");
        String token = data == null ? "" : data.optString("token", "").trim();
        if (token.isEmpty()) {
            session.status = "failed";
            throw new ApiException("登录未返回访问令牌", response.status, "登录", "");
        }

        JSONObject userInfo = data.optJSONObject("userInfo");
        String userId = userInfo == null ? data.optString("userId", "") : userInfo.optString("userId", data.optString("userId", ""));
        String shopToken = data.optString("shopToken", data.optString("shop_token", "")).trim();
        session.status = "logged_in";
        return new Account(session.phone, token, userId.trim(), shopToken);
    }

    /** 返回已按面板字段归一化的资产结果。行情不可用时仍返回资产，marketAvailable 为 false。 */
    public JSONObject fetchAssets(Account account, int pageNo, int pageSize) throws Exception {
        requireAccount(account);
        JSONObject payload = authenticatedGet(
                ASSETS_URL + "?groupType=0&isMetaVerse=0&pageNo=" + encode(pageNo) + "&pageSize=" + encode(pageSize),
                account,
                "资产"
        );
        JSONArray assets = assetList(payload);
        JSONArray marketRows = new JSONArray();
        boolean marketAvailable = true;
        try {
            marketRows = fetchMarketRows(account);
        } catch (Exception ignored) {
            marketAvailable = false;
        }

        Map<String, JSONObject> marketById = new LinkedHashMap<>();
        for (int index = 0; index < marketRows.length(); index++) {
            JSONObject item = marketRows.optJSONObject(index);
            if (item == null) continue;
            String id = assetId(item);
            if (!id.isEmpty()) marketById.put(id, item);
        }

        JSONArray normalizedItems = new JSONArray();
        double total = 0;
        double estimatedValue = 0;
        double pricedQuantity = 0;
        int limit = Math.min(assets.length(), 200);
        for (int index = 0; index < limit; index++) {
            JSONObject item = assets.optJSONObject(index);
            if (item == null) continue;
            String id = assetId(item);
            JSONObject marketItem = marketById.get(id);
            double quantity = assetQuantity(item);
            total += quantity;

            JSONObject normalized = new JSONObject();
            normalized.put("id", id);
            String groupId = firstString(item, "groupId", "digitalCollectionGroupId", "collectionGroupId");
            normalized.put("groupId", groupId.isEmpty() ? id : groupId);
            normalized.put("name", assetName(item));
            normalized.put("quantity", numericValue(quantity));
            normalized.put("cover", firstNonEmpty(assetCover(marketItem), assetCover(item)));
            Double floorPrice = marketPrice(marketItem);
            normalized.put("floorPrice", floorPrice == null ? JSONObject.NULL : numericValue(floorPrice));
            normalized.put("marketState", !marketAvailable ? "unavailable" : (floorPrice == null ? "delisted" : "listed"));
            if (floorPrice != null) {
                estimatedValue += quantity * floorPrice;
                pricedQuantity += quantity;
            }
            normalizedItems.put(normalized);
        }

        JSONObject result = new JSONObject();
        result.put("total", numericValue(total));
        result.put("marketAvailable", marketAvailable);
        result.put("marketUpdatedAt", nowIso());
        result.put("estimatedValue", pricedQuantity > 0 ? numericValue(estimatedValue) : JSONObject.NULL);
        result.put("pricedQuantity", numericValue(pricedQuantity));
        result.put("items", normalizedItems);
        return result;
    }

    /** 获取完整行情行，供本地监控和交易引擎复用。 */
    public JSONArray fetchMarketRows(Account account) throws Exception {
        requireAccount(account);
        JSONObject payload = authenticatedGet(
                MARKET_URL + "?pageNo=1&pageSize=1000&sortField=2&sortType=0&segmentId=-1&timeRange=0",
                account,
                "行情"
        );
        return assetList(payload);
    }

    /** 按名称检索行情，返回与旧面板相同的基础显示字段，不生成服务端 watchToken。 */
    public JSONObject searchMarkets(Account account, String name, int pageNo, int pageSize) throws Exception {
        requireAccount(account);
        String query = name == null ? "" : name.trim();
        JSONArray items = normalizedMarketSearchItems(searchMarketRows(account, query, pageNo, pageSize), query, pageSize);
        String fallbackQuery = marketSearchPrefix(query);
        if (items.length() == 0 && !fallbackQuery.equals(query)) {
            items = normalizedMarketSearchItems(searchMarketRows(account, fallbackQuery, pageNo, pageSize), query, pageSize);
        }

        JSONObject result = new JSONObject();
        result.put("query", query);
        result.put("marketAvailable", true);
        result.put("marketUpdatedAt", nowIso());
        result.put("items", items);
        return result;
    }

    private JSONArray searchMarketRows(Account account, String query, int pageNo, int pageSize) throws Exception {
        JSONObject payload = authenticatedGet(
                MARKET_URL + "?sortType=0&pageNo=" + encode(pageNo)
                        + "&segmentId=-1&sortField=2&name=" + encode(query)
                        + "&pageSize=" + encode(pageSize) + "&timeRange=0",
                account,
                "行情搜索"
        );
        return assetList(payload);
    }

    private static JSONArray normalizedMarketSearchItems(JSONArray rows, String query, int pageSize) throws JSONException {
        JSONArray items = new JSONArray();
        String needle = query.toLowerCase(Locale.ROOT);
        int maximum = Math.max(0, pageSize);
        for (int index = 0; index < rows.length() && items.length() < maximum; index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item == null) continue;
            String itemName = assetName(item);
            if (!needle.isEmpty() && !itemName.toLowerCase(Locale.ROOT).contains(needle)) continue;
            String id = assetId(item);
            if (id.isEmpty() || itemName.isEmpty()) continue;

            JSONObject normalized = new JSONObject();
            normalized.put("id", id);
            String groupId = firstString(item, "groupId", "digitalCollectionGroupId", "collectionGroupId");
            normalized.put("groupId", groupId.isEmpty() ? id : groupId);
            normalized.put("name", itemName);
            normalized.put("cover", assetCover(item));
            Double floorPrice = marketPrice(item);
            normalized.put("floorPrice", floorPrice == null ? JSONObject.NULL : numericValue(floorPrice));
            items.put(normalized);
        }
        return items;
    }

    private static String marketSearchPrefix(String query) {
        int end = query.length();
        char[] separators = {'\u00b7', '\u2022', '\u30fb', '/', '|', '\uff5c', ' '};
        for (char separator : separators) {
            int index = query.indexOf(separator);
            if (index > 0 && index < end) end = index;
        }
        return query.substring(0, end).trim();
    }

    /**
     * Executes an authenticated official iBox request without going through a panel server.
     * Mutation endpoints that follow the Android app protocol should set encryptedBody to true.
     */
    public JSONObject requestAuthenticated(
            Account account,
            String method,
            String url,
            JSONObject body,
            JSONObject query,
            boolean encryptedBody,
            String operation
    ) throws Exception {
        return requestAuthenticated(account, method, url, body, query, null, encryptedBody, operation);
    }

    /** Adds the GeeTest result using the order API protocol: query parameters plus Verify-Flag. */
    public JSONObject requestAuthenticatedWithCaptcha(
            Account account,
            String method,
            String url,
            JSONObject body,
            JSONObject query,
            CaptchaResult captcha,
            boolean encryptedBody,
            String operation
    ) throws Exception {
        return requestAuthenticatedWithCaptcha(account, method, url, body, query, captcha, null, encryptedBody, operation);
    }

    /** Adds optional protocol headers together with GeeTest query parameters. */
    public JSONObject requestAuthenticatedWithCaptcha(
            Account account,
            String method,
            String url,
            JSONObject body,
            JSONObject query,
            CaptchaResult captcha,
            JSONObject extraHeaders,
            boolean encryptedBody,
            String operation
    ) throws Exception {
        if (captcha == null || !captcha.isComplete()) {
            return requestAuthenticated(account, method, url, body, query, extraHeaders, encryptedBody, operation);
        }
        JSONObject actualQuery = query == null ? new JSONObject() : new JSONObject(query.toString());
        actualQuery.put("lot_number", captcha.lotNumber);
        actualQuery.put("captcha_output", captcha.captchaOutput);
        actualQuery.put("pass_token", captcha.passToken);
        actualQuery.put("gen_time", captcha.genTime);
        JSONObject headers = extraHeaders == null ? new JSONObject() : new JSONObject(extraHeaders.toString());
        headers.put("Verify-Flag", "true");
        return requestAuthenticated(account, method, url, body, actualQuery, headers, encryptedBody, operation);
    }

    /**
     * Same as {@link #requestAuthenticated(Account, String, String, JSONObject, JSONObject, boolean, String)}
     * with optional protocol headers, used by order endpoints after GeeTest succeeds.
     */
    public JSONObject requestAuthenticated(
            Account account,
            String method,
            String url,
            JSONObject body,
            JSONObject query,
            JSONObject extraHeaders,
            boolean encryptedBody,
            String operation
    ) throws Exception {
        requireAccount(account);
        String requestMethod = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (requestMethod.isEmpty()) requestMethod = "GET";
        String target = appendQuery(url, query);
        String payload = null;
        if (body != null) {
            if (encryptedBody) {
                EncryptedEnvelope encrypted = createEncryptedEnvelope(body);
                payload = encrypted.payload.toString();
            } else {
                payload = body.toString();
            }
        }
        Map<String, String> headers = authHeaders(account.token);
        if (extraHeaders != null) {
            Iterator<String> keys = extraHeaders.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = extraHeaders.opt(key);
                if (key == null || key.trim().isEmpty() || value == null || value == JSONObject.NULL) continue;
                headers.put(safeHeaderValue(key, ""), safeHeaderValue(String.valueOf(value), ""));
            }
        }
        HttpResponse response = request(requestMethod, target, headers, payload, null);
        return authenticatedPayload(response, operation == null || operation.trim().isEmpty() ? "iBox 请求" : operation.trim());
    }

    private JSONObject authenticatedGet(String url, Account account, String operation) throws Exception {
        HttpResponse response = request("GET", url, authHeaders(account.token), null, null);
        return authenticatedPayload(response, operation);
    }

    private JSONObject authenticatedPayload(HttpResponse response, String operation) throws Exception {
        Object wirePayload = parseJson(response, operation);
        Object payload = decryptServerEnvelope(wirePayload, operation);
        if (response.status < 200 || response.status >= 300) {
            throw businessException(operation + "请求", response.status, payload);
        }
        if (!(payload instanceof JSONObject)) {
            throw new ApiException(operation + "返回格式无效", response.status, operation, "");
        }
        JSONObject object = (JSONObject) payload;
        if (object.has("code") && !isZero(object.opt("code"))) {
            throw businessException(operation, response.status, object);
        }
        return object;
    }

    private HttpResponse request(String method, String url, Map<String, String> headers, String body, SmsSession session) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            Map<String, List<String>> responseHeaders = connection.getHeaderFields();
            if (session != null) absorbCookies(session, responseHeaders);
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readResponse(stream, firstHeader(responseHeaders, "Content-Encoding"));
            return new HttpResponse(status, text, responseHeaders);
        } catch (IOException error) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            throw new ApiException("连接 iBox 官方服务失败：" + detail, 0, "网络", "");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer");
        headers.put("User-Agent", userAgent());
        headers.put("MSG-ID", UUID.randomUUID().toString() + "_android");
        headers.put("PLATFORM-TYPE", "1");
        headers.put("DEVICE-ID", deviceId);
        headers.put("APP-VERSION", APP_VERSION);
        headers.put("APP-VERSION-NUMBER", APP_BUILD);
        headers.put("AllowOutTest", "1");
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json");
        headers.put("Accept-Encoding", "gzip");
        headers.put("Connection", "Keep-Alive");
        return headers;
    }

    private Map<String, String> guestHeaders(SmsSession session) {
        Map<String, String> headers = baseHeaders();
        headers.remove("Authorization");
        String cookie = session.cookieHeader();
        if (!cookie.isEmpty()) headers.put("Cookie", cookie);
        return headers;
    }

    private Map<String, String> authHeaders(String token) {
        Map<String, String> headers = baseHeaders();
        headers.put("Authorization", "Bearer " + token.trim());
        return headers;
    }

    private String userAgent() {
        String version = safeHeaderValue(Build.VERSION.RELEASE, "14");
        String model = safeHeaderValue(Build.MODEL, "Android");
        return "ibox/" + APP_VERSION + "(Android;" + version + ";" + model + ")";
    }

    private EncryptedEnvelope createEncryptedEnvelope(JSONObject body) throws Exception {
        byte[] aesKey = randomAesKey();
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, requestPublicKey());
        String encryptKey = Base64.getEncoder().encodeToString(rsa.doFinal(aesKey));

        Cipher aes = Cipher.getInstance("AES/ECB/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        String data = Base64.getEncoder().encodeToString(aes.doFinal(body.toString().getBytes(StandardCharsets.UTF_8)));
        JSONObject payload = new JSONObject();
        payload.put("encryptKey", encryptKey);
        payload.put("data", data);
        return new EncryptedEnvelope(payload, aesKey);
    }

    private Object decryptServerEnvelope(Object wirePayload, String operation) throws Exception {
        if (!isEncryptedEnvelope(wirePayload)) return wirePayload;
        try {
            JSONObject envelope = (JSONObject) wirePayload;
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, responsePrivateKey());
            byte[] aesKey = rsa.doFinal(Base64.getDecoder().decode(envelope.getString("encryptKey")));
            if (aesKey.length != 16) throw new ApiException(operation + "响应密钥长度无效");
            return decryptAesJson(envelope.getString("data"), aesKey);
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw new ApiException(operation + "响应加密数据无法解密", 0, operation, "", error);
        }
    }

    private Object decryptWithRequestKey(JSONObject envelope, byte[] aesKey) {
        try {
            return decryptAesJson(envelope.getString("data"), aesKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object decryptAesJson(String encoded, byte[] aesKey) throws Exception {
        Cipher aes = Cipher.getInstance("AES/ECB/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        String text = new String(aes.doFinal(Base64.getDecoder().decode(encoded)), StandardCharsets.UTF_8);
        return new JSONTokener(text).nextValue();
    }

    private PublicKey requestPublicKey() throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePublic(new X509EncodedKeySpec(hexToBytes(PUBLIC_KEY_HEX)));
    }

    private PrivateKey responsePrivateKey() throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        byte[] encoded = Base64.getDecoder().decode(RESPONSE_PRIVATE_KEY_B64);
        return factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private byte[] randomAesKey() {
        char[] value = new char[16];
        for (int index = 0; index < value.length; index++) {
            value[index] = AES_KEY_CHARACTERS.charAt(random.nextInt(AES_KEY_CHARACTERS.length()));
        }
        return new String(value).getBytes(StandardCharsets.UTF_8);
    }

    private Object parseJson(HttpResponse response, String operation) throws ApiException {
        if (response.body == null || response.body.trim().isEmpty()) {
            throw new ApiException(operation + "返回为空（HTTP " + response.status + "）", response.status, operation, "");
        }
        try {
            return new JSONTokener(response.body).nextValue();
        } catch (JSONException error) {
            throw new ApiException(operation + "返回不是 JSON（HTTP " + response.status + "）", response.status, operation, "", error);
        }
    }

    private static boolean isEncryptedEnvelope(Object value) {
        if (!(value instanceof JSONObject)) return false;
        JSONObject object = (JSONObject) value;
        return !object.optString("encryptKey", "").isEmpty() && !object.optString("data", "").isEmpty();
    }

    private static boolean isBusinessSuccess(Object payload) {
        if (Boolean.TRUE.equals(payload)) return true;
        if (!(payload instanceof JSONObject)) return false;
        JSONObject object = (JSONObject) payload;
        return isZero(object.opt("code")) || object.optBoolean("success", false) || Boolean.TRUE.equals(object.opt("data"));
    }

    private static boolean isZero(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue() == 0d;
        return "0".equals(String.valueOf(value));
    }

    private static ApiException businessException(String operation, int status, Object payload) {
        String code = "";
        String detail = "";
        if (payload instanceof JSONObject) {
            JSONObject object = (JSONObject) payload;
            if (object.has("code")) code = String.valueOf(object.opt("code"));
            detail = firstNonEmpty(object.optString("message", ""), object.optString("msg", ""), object.optString("error", ""));
        }
        String message = operation + "失败";
        if (!detail.isEmpty()) message += "：" + detail;
        else if (status > 0) message += "（HTTP " + status + "）";
        return new ApiException(message, status, operation, code);
    }

    private static JSONArray assetList(JSONObject payload) {
        Object root = payload.has("data") ? payload.opt("data") : payload;
        if (root instanceof JSONArray) return (JSONArray) root;
        if (!(root instanceof JSONObject)) return new JSONArray();
        JSONObject object = (JSONObject) root;
        String[] keys = {"collectionAssets", "list", "records", "items", "rows", "data"};
        for (String key : keys) {
            JSONArray array = object.optJSONArray(key);
            if (array != null) return array;
        }
        return new JSONArray();
    }

    private static String assetId(JSONObject item) {
        String direct = firstString(item, "digitalCollectionId", "digitalCollectionID", "collectionId", "collectionID", "id");
        if (!direct.isEmpty()) return direct;
        JSONObject collection = item == null ? null : item.optJSONObject("digitalCollection");
        return collection == null ? "" : firstString(collection, "id");
    }

    private static String assetName(JSONObject item) {
        String direct = firstString(item, "name", "title", "digitalCollectionName", "collectionName");
        if (!direct.isEmpty()) return direct;
        JSONObject collection = item == null ? null : item.optJSONObject("digitalCollection");
        return collection == null ? "" : firstString(collection, "name", "title");
    }

    private static String assetCover(JSONObject item) {
        String direct = firstString(item, "coverPicUrl", "coverUrl", "headerPicUrl");
        if (!direct.isEmpty()) return direct;
        JSONObject collection = item == null ? null : item.optJSONObject("digitalCollection");
        return collection == null ? "" : firstString(collection, "coverPicUrl");
    }

    private static Double marketPrice(JSONObject item) {
        if (item == null || !item.has("floorPrice")) return null;
        Object raw = item.opt("floorPrice");
        if (raw == null || raw == JSONObject.NULL) return null;
        try {
            double value = Double.parseDouble(String.valueOf(raw));
            return Double.isFinite(value) && value >= 0d ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double assetQuantity(JSONObject item) {
        if (item == null) return 1d;
        String[] keys = {"holdNum", "holdCount", "quantity", "count", "num", "holdingQuantity", "total"};
        for (String key : keys) {
            Object raw = item.opt(key);
            if (raw == null || raw == JSONObject.NULL) continue;
            try {
                double value = Double.parseDouble(String.valueOf(raw));
                if (Double.isFinite(value) && value >= 0d) return value;
            } catch (NumberFormatException ignored) {
                // Continue checking the remaining compatible fields.
            }
        }
        return 1d;
    }

    private static Object numericValue(double value) {
        return value == Math.rint(value) ? (long) value : value;
    }

    private static String firstString(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String appendCaptchaQuery(String url, CaptchaResult captcha) throws Exception {
        return url + "?lot_number=" + encode(captcha.lotNumber)
                + "&captcha_output=" + encode(captcha.captchaOutput)
                + "&pass_token=" + encode(captcha.passToken)
                + "&gen_time=" + encode(captcha.genTime);
    }

    private static String appendQuery(String url, JSONObject query) throws Exception {
        if (query == null || query.length() == 0) return url;
        StringBuilder target = new StringBuilder(url == null ? "" : url);
        boolean hasQuery = target.indexOf("?") >= 0;
        Iterator<String> keys = query.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = query.opt(key);
            if (key == null || key.trim().isEmpty() || value == null || value == JSONObject.NULL) continue;
            target.append(hasQuery ? '&' : '?');
            hasQuery = true;
            target.append(encode(key)).append('=').append(encode(value));
        }
        return target.toString();
    }

    private static String encode(Object value) throws Exception {
        return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8.name());
    }

    private static String nowIso() {
        return Instant.ofEpochMilli(System.currentTimeMillis()).toString();
    }

    private static String safeHeaderValue(String value, String fallback) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid_hex_key");
            bytes[index] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    private static String readResponse(InputStream stream, String contentEncoding) throws IOException {
        if (stream == null) return "";
        try (InputStream source = isGzip(contentEncoding) ? new GZIPInputStream(stream) : stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean isGzip(String contentEncoding) {
        return contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(name)) continue;
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) return values.get(0);
        }
        return "";
    }

    private static void absorbCookies(SmsSession session, Map<String, List<String>> headers) {
        if (headers == null) return;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            List<String> values = entry.getValue() == null ? new ArrayList<>() : entry.getValue();
            for (String value : values) {
                if (value == null) continue;
                String pair = value.split(";", 2)[0];
                int separator = pair.indexOf('=');
                if (separator > 0) session.cookies.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
    }

    private static void requireAccount(Account account) throws ApiException {
        if (account == null || account.token.isEmpty()) throw new ApiException("账号登录令牌缺失，请重新登录");
    }

    private static void requireReadyForSms(SmsSession session) throws ApiException {
        if (session == null) throw new ApiException("短信会话不存在，请重新发起登录");
        if (session.isExpired()) throw new ApiException("短信会话已过期，请重新发起登录");
        if ("sending".equals(session.status)) throw new ApiException("短信验证码正在发送，请稍候");
        if ("sent".equals(session.status) || "pending_code".equals(session.status)) throw new ApiException("验证码已发送，请直接登录");
    }

    private static void requireReadyForLogin(SmsSession session) throws ApiException {
        if (session == null) throw new ApiException("短信会话不存在，请重新发起登录");
        if (session.isExpired()) throw new ApiException("短信会话已过期，请重新发起登录");
        if (!"sent".equals(session.status) && !"pending_code".equals(session.status)) {
            throw new ApiException("请先完成人机验证并发送短信验证码");
        }
    }

    private static final class EncryptedEnvelope {
        final JSONObject payload;
        final byte[] aesKey;

        EncryptedEnvelope(JSONObject payload, byte[] aesKey) {
            this.payload = payload;
            this.aesKey = aesKey;
        }
    }

    private static final class HttpResponse {
        final int status;
        final String body;
        final Map<String, List<String>> headers;

        HttpResponse(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    public static final class CaptchaResult {
        public final String lotNumber;
        public final String captchaOutput;
        public final String passToken;
        public final String genTime;

        public CaptchaResult(String lotNumber, String captchaOutput, String passToken, String genTime) {
            this.lotNumber = trim(lotNumber);
            this.captchaOutput = trim(captchaOutput);
            this.passToken = trim(passToken);
            this.genTime = trim(genTime);
        }

        public static CaptchaResult fromJson(JSONObject value) {
            JSONObject input = value == null ? new JSONObject() : value;
            return new CaptchaResult(
                    input.optString("lot_number", ""),
                    input.optString("captcha_output", ""),
                    input.optString("pass_token", ""),
                    input.optString("gen_time", "")
            );
        }

        boolean isComplete() {
            return !lotNumber.isEmpty() && !captchaOutput.isEmpty() && !passToken.isEmpty() && !genTime.isEmpty();
        }
    }

    public static final class SmsSession {
        private final String phone;
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private long expiresAt;
        private String status = "awaiting_captcha";

        private SmsSession(String phone, long expiresAt) {
            this.phone = phone;
            this.expiresAt = expiresAt;
        }

        public String getPhone() {
            return phone;
        }

        public String getStatus() {
            return status;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }

        private String cookieHeader() {
            StringBuilder value = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (value.length() > 0) value.append("; ");
                value.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return value.toString();
        }
    }

    public static final class Account {
        public final String phone;
        public final String token;
        public final String userId;
        public final String shopToken;

        public Account(String phone, String token, String userId, String shopToken) throws ApiException {
            this.phone = trim(phone);
            this.token = trim(token);
            this.userId = trim(userId);
            this.shopToken = trim(shopToken);
            if (!this.phone.matches("^\\d{11}$")) throw new ApiException("账号手机号格式无效");
            if (this.token.isEmpty()) throw new ApiException("账号登录令牌为空");
        }
    }

    public static final class ApiException extends Exception {
        public final int status;
        public final String operation;
        public final String businessCode;

        ApiException(String message) {
            this(message, 0, "", "");
        }

        ApiException(String message, int status, String operation, String businessCode) {
            super(message);
            this.status = status;
            this.operation = operation == null ? "" : operation;
            this.businessCode = businessCode == null ? "" : businessCode;
        }

        ApiException(String message, int status, String operation, String businessCode, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.operation = operation == null ? "" : operation;
            this.businessCode = businessCode == null ? "" : businessCode;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
