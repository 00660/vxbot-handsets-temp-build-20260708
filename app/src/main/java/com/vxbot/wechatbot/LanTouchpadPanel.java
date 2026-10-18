package com.vxbot.wechatbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;
import java.security.SecureRandom;

/** Browser panel for sending absolute BLE HID touchscreen reports over the local network. */
final class LanTouchpadPanel {
    private static final String PREFS = "bot_config";
    private static final String TOKEN_KEY = "robotPushAuthToken";
    private static final String PORT_KEY = "robotPushHttpPort";
    private static final int DEFAULT_PORT = 18234;

    private LanTouchpadPanel() {}

    static String urlFor(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(TOKEN_KEY, "");
        if (token == null || token.trim().isEmpty()) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            StringBuilder generated = new StringBuilder(32);
            for (byte value : bytes) generated.append(String.format(Locale.US, "%02x", value));
            token = generated.toString();
            prefs.edit().putString(TOKEN_KEY, token).apply();
        }
        int configuredPort = prefs.getInt(PORT_KEY, DEFAULT_PORT);
        int port = configuredPort >= 1024 && configuredPort <= 65535 ? configuredPort : DEFAULT_PORT;
        String ip = localIpv4();
        return ip.isEmpty() ? "未连接 Wi-Fi" : "http://" + ip + ":" + port + "/touchpad/" + token;
    }

    private static String localIpv4() {
        String fallback = "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip.startsWith("192.168.")) return ip;
                    if (address.isSiteLocalAddress() && fallback.isEmpty()) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static String html(String token) {
        return "<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                + "<title>手机画面参考</title><style>*{box-sizing:border-box}body{margin:0;background:#101827;color:#e8eef8;font:15px system-ui,sans-serif;text-align:center}main{padding:14px}img{display:block;max-height:78vh;max-width:96vw;margin:10px auto;border-radius:12px}small{color:#9eabc0}</style>"
                + "<main><h3>手机画面参考</h3><small>触控命令由蓝牙广播直达 CH591D，本页面只提供画面。</small><img id=s><div id=i>正在读取画面…</div></main>"
                + "<script>let b='/touchpad/" + token + "',s=document.getElementById('s'),i=document.getElementById('i');function f(){s.src=b+'/screen.jpg?t='+Date.now();s.onload=()=>i.textContent='画面更新 '+new Date().toLocaleTimeString();s.onerror=()=>i.textContent='画面读取失败'}f();setInterval(f,500)</script></html>";
    }
}
