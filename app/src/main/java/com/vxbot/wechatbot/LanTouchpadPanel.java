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
                + "<title>HS 虚拟触控板</title><style>"
                + "*{box-sizing:border-box}body{margin:0;background:#101827;color:#e8eef8;font:15px system-ui,-apple-system,sans-serif}"
                + "main{max-width:560px;margin:auto;padding:16px}.head{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}"
                + "h1{font-size:19px;margin:0}.sub{font-size:12px;color:#9eabc0;margin-top:4px}.pill{font-size:12px;color:#a8f0cf;background:#123b35;padding:6px 10px;border-radius:99px}"
                + ".screen{position:relative;width:min(100%,34vh);aspect-ratio:9/19.5;margin:0 auto;background:#202c40;border:1px solid #42516b;border-radius:18px;overflow:hidden;touch-action:none;box-shadow:0 12px 32px #0006}"
                + "canvas{display:block;width:100%;height:100%;touch-action:none}.actions{display:flex;gap:8px;margin:12px 0}.btn{flex:1;border:1px solid #394861;border-radius:10px;background:#1b2739;color:#eef4ff;padding:11px 8px;font-size:14px}"
                + ".hint{font-size:12px;line-height:1.5;color:#9eabc0}.coords{font-variant-numeric:tabular-nums;color:#9fd3ff}"
                + "</style><main><div class=head><div><h1>局域网虚拟触控板</h1><div class=sub id=meta>正在连接手机…</div></div><div class=pill id=state>HID 触控</div></div>"
                + "<div class=screen id=screen><canvas id=c></canvas></div><div class=actions><button class=btn id=shot>抓取当前屏幕作参考</button><button class=btn id=clear>清除参考画面</button></div>"
                + "<div class=hint>画布按手机绝对分辨率映射，触摸/拖动直接发送 BLE HID 触控。画面是手动抓取的参考帧，不参与坐标计算，减少截屏带来的延迟。<br>坐标：<span class=coords id=coords>—</span></div></main>"
                + "<script>const token='" + token + "',c=document.getElementById('c'),ctx=c.getContext('2d'),meta=document.getElementById('meta'),state=document.getElementById('state'),coords=document.getElementById('coords');"
                + "let W=1080,H=2400,down=false,last=null,frame=0,img=null,busy=false,pendingMove=null,releasePending=false,moveTimer=0,lastMoveAt=0;const base='/touchpad/'+token;"
                + "function draw(){ctx.fillStyle='#172338';ctx.fillRect(0,0,W,H);if(img)ctx.drawImage(img,0,0,W,H);ctx.strokeStyle='#ffffff25';ctx.lineWidth=Math.max(1,W/540);for(let i=1;i<4;i++){ctx.beginPath();ctx.moveTo(W*i/4,0);ctx.lineTo(W*i/4,H);ctx.stroke();ctx.beginPath();ctx.moveTo(0,H*i/4);ctx.lineTo(W,H*i/4);ctx.stroke()}ctx.fillStyle='#ffffffaa';ctx.font=Math.round(W/28)+'px system-ui';ctx.fillText('0,0',12,Math.round(W/24));ctx.fillText(W+' × '+H,12,H-16);if(last){ctx.beginPath();ctx.arc(last.x,last.y,W/65,0,Math.PI*2);ctx.fillStyle='#47d7ff';ctx.fill()}}"
                + "function point(e){let r=c.getBoundingClientRect();return{x:Math.max(0,Math.min(W-1,Math.round((e.clientX-r.left)*W/r.width))),y:Math.max(0,Math.min(H-1,Math.round((e.clientY-r.top)*H/r.height)))}}"
                + "function request(action,p){return fetch(base+'/event',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,x:p?p.x:0,y:p?p.y:0})}).then(r=>{if(!r.ok)throw Error('HID '+r.status);state.textContent='HID 已发送';state.style.background='#123b35'}).catch(()=>{state.textContent='HID 未连接';state.style.background='#512b32'})}"
                + "function sendUp(){if(busy){releasePending=true;return}releasePending=false;busy=true;request('up',last).finally(()=>{busy=false;if(down&&pendingMove)scheduleMove()})}"
                + "function pumpMove(){if(busy||!down||!pendingMove)return;let p=pendingMove;pendingMove=null;busy=true;lastMoveAt=performance.now();request('move',p).finally(()=>{busy=false;if(releasePending)sendUp();else if(pendingMove)scheduleMove()})}"
                + "function scheduleMove(){if(busy||moveTimer||!down||!pendingMove)return;let wait=Math.max(0,35-(performance.now()-lastMoveAt));moveTimer=setTimeout(()=>{moveTimer=0;pumpMove()},wait)}"
                + "c.addEventListener('pointerdown',e=>{e.preventDefault();if(busy||down)return;c.setPointerCapture(e.pointerId);down=true;pendingMove=null;last=point(e);coords.textContent=last.x+', '+last.y;draw();busy=true;request('down',last).finally(()=>{busy=false;if(releasePending)sendUp();else if(pendingMove)scheduleMove()})});"
                + "c.addEventListener('pointermove',e=>{if(!down)return;e.preventDefault();last=point(e);pendingMove=last;coords.textContent=last.x+', '+last.y;if(!frame)frame=requestAnimationFrame(()=>{frame=0;draw()});scheduleMove()});"
                + "function release(e){if(!down)return;down=false;releasePending=true;pendingMove=null;if(moveTimer){clearTimeout(moveTimer);moveTimer=0}if(frame){cancelAnimationFrame(frame);frame=0}last=point(e);coords.textContent=last.x+', '+last.y;draw();if(!busy)sendUp()}c.addEventListener('pointerup',release);c.addEventListener('pointercancel',release);window.addEventListener('pagehide',()=>{navigator.sendBeacon(base+'/event',new Blob([JSON.stringify({action:'up',x:0,y:0})],{type:'application/json'}))});"
                + "document.getElementById('shot').onclick=async()=>{state.textContent='正在抓屏…';try{let im=new Image();im.src=base+'/screen.jpg?t='+Date.now();await im.decode();img=im;draw();state.textContent='参考画面已更新'}catch(e){state.textContent='抓屏失败'}};document.getElementById('clear').onclick=()=>{img=null;draw()};"
                + "fetch(base+'/info').then(r=>r.json()).then(v=>{W=v.width;H=v.height;c.width=W;c.height=H;document.querySelector('.screen').style.aspectRatio=W+'/'+H;meta.textContent='手机画布 '+W+' × '+H+' · 坐标映射已就绪';draw()}).catch(()=>{meta.textContent='无法连接手机服务'});draw();</script></html>";
    }
}
