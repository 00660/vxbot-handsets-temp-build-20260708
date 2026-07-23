package com.vxbot.wechatbot;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class GxazMachineActivation {
    private static final String AUTH_SECRET = "gxaz-auth-secret-2026";
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int MACHINE_CODE_LENGTH = 10;
    private static final int DEFAULT_DAYS = 30;
    private static final TimeZone CHINA_TIME_ZONE = TimeZone.getTimeZone("GMT+08:00");

    private GxazMachineActivation() {
    }

    public static boolean isMachineCode(String value) {
        String normalized = normalizeMachineCode(value);
        return normalized.matches("^[0-9a-z]{" + MACHINE_CODE_LENGTH + "}$");
    }

    public static String replyFor(String machineCode) {
        String endTime = endDateAfterDays(DEFAULT_DAYS);
        return "GXAZ 机器绑定激活码（30 天）：\n"
                + activationCodeFor(machineCode, endTime)
                + "\n到期：" + endTime;
    }

    public static String activationCodeFor(String machineCode, String endTime) {
        String normalizedMachineCode = normalizeMachineCode(machineCode);
        if (!isMachineCode(normalizedMachineCode)) {
            throw new IllegalArgumentException("机器码必须为 10 位字母数字");
        }
        String normalizedEndTime = endTime == null ? "" : endTime.trim();
        if (!normalizedEndTime.matches("^20\\d{2}-\\d{2}-\\d{2}$")) {
            throw new IllegalArgumentException("到期日格式错误");
        }
        String payload = normalizedEndTime.substring(2, 4)
                + normalizedEndTime.substring(5, 7)
                + normalizedEndTime.substring(8, 10)
                + normalizedMachineCode;
        return "ces" + payload + signatureFor(payload);
    }

    private static String endDateAfterDays(int days) {
        Calendar calendar = Calendar.getInstance(CHINA_TIME_ZONE, Locale.ROOT);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String signatureFor(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(AUTH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return digestToBase36(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), 5);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate GXAZ activation code", exception);
        }
    }

    private static String digestToBase36(byte[] digest, int length) {
        BigInteger value = new BigInteger(1, digest);
        BigInteger base = BigInteger.valueOf(ALPHABET.length());
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index += 1) {
            BigInteger[] parts = value.divideAndRemainder(base);
            result.append(ALPHABET.charAt(parts[1].intValue()));
            value = parts[0];
        }
        return result.toString();
    }

    private static String normalizeMachineCode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
