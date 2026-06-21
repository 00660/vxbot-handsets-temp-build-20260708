package com.vxbot.wechatbot;

import android.util.Base64;

import com.iwebpp.crypto.TweetNaclFast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class HappyCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private HappyCrypto() {
    }

    static byte[] randomBytes(int size) {
        byte[] out = new byte[size];
        RANDOM.nextBytes(out);
        return out;
    }

    static String encodeBase64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    static byte[] decodeBase64Any(String value) {
        String text = value == null ? "" : value.trim();
        int flags = Base64.NO_WRAP;
        if (text.indexOf('-') >= 0 || text.indexOf('_') >= 0) {
            flags |= Base64.URL_SAFE;
        }
        return Base64.decode(text, flags);
    }

    static String encodeBase64Url(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    static TweetNaclFast.Box.KeyPair newBoxKeyPair() {
        return TweetNaclFast.Box.keyPair_fromSecretKey(randomBytes(32));
    }

    static byte[] encryptBox(byte[] data, byte[] recipientPublicKey) {
        TweetNaclFast.Box.KeyPair ephemeral = newBoxKeyPair();
        byte[] nonce = randomBytes(TweetNaclFast.Box.nonceLength);
        byte[] encrypted = new TweetNaclFast.Box(recipientPublicKey, ephemeral.getSecretKey()).box(data, nonce);
        if (encrypted == null) {
            throw new IllegalStateException("Happy box encrypt failed");
        }
        byte[] out = new byte[32 + nonce.length + encrypted.length];
        System.arraycopy(ephemeral.getPublicKey(), 0, out, 0, 32);
        System.arraycopy(nonce, 0, out, 32, nonce.length);
        System.arraycopy(encrypted, 0, out, 32 + nonce.length, encrypted.length);
        return out;
    }

    static byte[] decryptBoxBundle(byte[] bundle, byte[] recipientSecretKey) {
        if (bundle == null || bundle.length < 32 + TweetNaclFast.Box.nonceLength) {
            return null;
        }
        byte[] ephemeralPublicKey = Arrays.copyOfRange(bundle, 0, 32);
        byte[] nonce = Arrays.copyOfRange(bundle, 32, 32 + TweetNaclFast.Box.nonceLength);
        byte[] encrypted = Arrays.copyOfRange(bundle, 32 + TweetNaclFast.Box.nonceLength, bundle.length);
        return new TweetNaclFast.Box(ephemeralPublicKey, recipientSecretKey).open(encrypted, nonce);
    }

    static TweetNaclFast.Box.KeyPair deriveContentKeyPair(byte[] accountSecret) throws Exception {
        byte[] seed = deriveKey(accountSecret, "Happy EnCoder", new String[]{"content"});
        byte[] hashed = MessageDigest.getInstance("SHA-512").digest(seed);
        return TweetNaclFast.Box.keyPair_fromSecretKey(Arrays.copyOfRange(hashed, 0, 32));
    }

    static byte[] encryptDataKey(String json, byte[] key) throws Exception {
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encryptedWithTag = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[1 + nonce.length + encryptedWithTag.length];
        out[0] = 0;
        System.arraycopy(nonce, 0, out, 1, nonce.length);
        System.arraycopy(encryptedWithTag, 0, out, 1 + nonce.length, encryptedWithTag.length);
        return out;
    }

    static byte[] encryptJson(String json, byte[] key, String variant) throws Exception {
        if ("legacy".equals(variant)) {
            return encryptLegacy(json, key);
        }
        return encryptDataKey(json, key);
    }

    static String decryptToJson(byte[] encrypted, byte[] key, String variant) throws Exception {
        if ("legacy".equals(variant)) {
            return decryptLegacy(encrypted, key);
        }
        return decryptDataKey(encrypted, key);
    }

    private static String decryptDataKey(byte[] bundle, byte[] key) throws Exception {
        if (bundle == null || bundle.length < 1 + 12 + 16 || bundle[0] != 0) {
            return null;
        }
        byte[] nonce = Arrays.copyOfRange(bundle, 1, 13);
        byte[] encryptedWithTag = Arrays.copyOfRange(bundle, 13, bundle.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(encryptedWithTag);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static String decryptLegacy(byte[] bundle, byte[] key) {
        if (bundle == null || bundle.length < TweetNaclFast.SecretBox.nonceLength + TweetNaclFast.SecretBox.overheadLength) {
            return null;
        }
        byte[] nonce = Arrays.copyOfRange(bundle, 0, TweetNaclFast.SecretBox.nonceLength);
        byte[] encrypted = Arrays.copyOfRange(bundle, TweetNaclFast.SecretBox.nonceLength, bundle.length);
        byte[] plain = new TweetNaclFast.SecretBox(key).open(encrypted, nonce);
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }

    private static byte[] encryptLegacy(String json, byte[] key) {
        byte[] nonce = randomBytes(TweetNaclFast.SecretBox.nonceLength);
        byte[] encrypted = new TweetNaclFast.SecretBox(key).box(json.getBytes(StandardCharsets.UTF_8), nonce);
        if (encrypted == null) {
            throw new IllegalStateException("Happy legacy encrypt failed");
        }
        byte[] out = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
        return out;
    }

    private static byte[] deriveKey(byte[] master, String usage, String[] path) throws Exception {
        KeyTreeState state = deriveRoot(master, usage);
        for (String item : path) {
            state = deriveChild(state.chainCode, item);
        }
        return state.key;
    }

    private static KeyTreeState deriveRoot(byte[] seed, String usage) throws Exception {
        byte[] out = hmacSha512((usage + " Master Seed").getBytes(StandardCharsets.UTF_8), seed);
        return new KeyTreeState(Arrays.copyOfRange(out, 0, 32), Arrays.copyOfRange(out, 32, 64));
    }

    private static KeyTreeState deriveChild(byte[] chainCode, String index) throws Exception {
        byte[] text = index.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[text.length + 1];
        data[0] = 0;
        System.arraycopy(text, 0, data, 1, text.length);
        byte[] out = hmacSha512(chainCode, data);
        return new KeyTreeState(Arrays.copyOfRange(out, 0, 32), Arrays.copyOfRange(out, 32, 64));
    }

    private static byte[] hmacSha512(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key, "HmacSHA512"));
        return mac.doFinal(data);
    }

    private static final class KeyTreeState {
        final byte[] key;
        final byte[] chainCode;

        KeyTreeState(byte[] key, byte[] chainCode) {
            this.key = key;
            this.chainCode = chainCode;
        }
    }
}
