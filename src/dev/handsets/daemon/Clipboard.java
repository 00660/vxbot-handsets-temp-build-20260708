package dev.handsets.daemon;

import android.content.ClipData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Clipboard read/write/watch via the {@code IClipboard} binder.
 *
 * AOSP's ClipboardService unconditionally allows shell UID:
 *   {@code if (callingUid == Process.SHELL_UID) return true;}
 * so we don't need foreground-window tricks or runtime grants — just
 * reach the binder and call getPrimaryClip / setPrimaryClip directly.
 *
 * Watch mode polls every {@code interval_ms} (default 500); listener
 * callback wiring would need a custom Binder stub for
 * IOnPrimaryClipChangedListener, which is more code than it's worth
 * given how cheap a clip-read is.
 */
final class Clipboard {

    private static final String DEFAULT_CALLER = "com.android.shell";

    private final String caller;
    private volatile Object svc;
    private volatile Method getClip;
    private volatile Method setClip;

    Clipboard() {
        this(DEFAULT_CALLER);
    }

    Clipboard(String caller) {
        this.caller = (caller == null || caller.isEmpty()) ? DEFAULT_CALLER : caller;
    }

    private synchronized Object svc() {
        if (svc == null) {
            svc = Binders.asInterface(
                    "android.content.IClipboard$Stub",
                    Binders.service("clipboard"));
        }
        return svc;
    }

    // ---------- read ----------

    byte[] get() {
        byte[] direct = directGet();
        if (needsShellFallback(direct)) return shellClip("get", null);
        return direct;
    }

    byte[] directGet() {
        try {
            Object service = svc();
            Method m = getOrFindGet(service.getClass());
            if (m == null) return err("get-method-not-found");
            ClipData cd = (ClipData) m.invoke(service, buildArgs(m, null));
            if (cd == null) return new byte[0];
            return clipDataToBytes(cd);
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            return err("get-failed:" + c.getClass().getSimpleName() + ":" + c.getMessage());
        }
    }

    // ---------- write ----------

    byte[] set(String text) {
        if (text == null) text = "";
        byte[] direct = directSet(text);
        if (needsShellFallback(direct)) return shellClip("set", text);
        return direct;
    }

    byte[] directSet(String text) {
        if (text == null) text = "";
        try {
            ClipData cd = ClipData.newPlainText("hs", text);
            Object service = svc();
            Method m = getOrFindSet(service.getClass());
            if (m == null) return err("set-method-not-found");
            m.invoke(service, buildArgs(m, cd));
            return "ok".getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            return err("set-failed:" + c.getClass().getSimpleName() + ":" + c.getMessage());
        }
    }

    private static boolean needsShellFallback(byte[] resp) {
        if (android.os.Process.myUid() != 0 || resp == null) return false;
        String s = new String(resp, StandardCharsets.UTF_8);
        return s.startsWith("ERR:") && s.contains("does not belong to 0");
    }

    private static byte[] shellClip(String mode, String text) {
        String busybox = firstExisting(
                "/data/adb/magisk/busybox",
                "/system/addon.d/magisk/busybox");
        if (busybox == null) return err("shell-fallback-no-busybox");
        String cp = System.getenv("CLASSPATH");
        if (cp == null || cp.isEmpty()) cp = System.getProperty("java.class.path");
        if (cp == null || cp.isEmpty()) cp = "/data/local/tmp/hs.jar";

        java.util.ArrayList<String> args = new java.util.ArrayList<String>();
        args.add(busybox);
        args.add("setuidgid");
        args.add("1000:1000");
        args.add("/system/bin/app_process");
        args.add("/system/bin");
        args.add("--nice-name=hs-clip");
        args.add(ClipboardShell.class.getName());
        args.add(mode);
        args.add("android");
        if ("set".equals(mode)) {
            args.add(android.util.Base64.encodeToString(
                    (text == null ? "" : text).getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP));
        }

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.environment().put("CLASSPATH", cp);
            p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            byte[] out = readAll(p.getInputStream());
            byte[] errOut = readAll(p.getErrorStream());
            if (!done) {
                p.destroyForcibly();
                return err("shell-fallback-timeout");
            }
            if (p.exitValue() == 0) return out;
            String detail = new String(out, StandardCharsets.UTF_8)
                    + new String(errOut, StandardCharsets.UTF_8);
            return err("shell-fallback-exit-" + p.exitValue() + ":" + detail.trim());
        } catch (Throwable t) {
            return err("shell-fallback-failed:" + t.getClass().getSimpleName()
                    + ":" + t.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String firstExisting(String... paths) {
        for (String p : paths) {
            if (new java.io.File(p).isFile()) return p;
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---------- watch (polled streaming) ----------

    /** Polls the clipboard at {@code intervalMs} cadence. Each time the
     *  text changes, writes one length-prefixed frame with the new
     *  content. Terminates on IO error (client gone) with a 0-length
     *  frame so the host sees a clean end. */
    void watch(DataOutputStream out, long intervalMs) {
        if (intervalMs <= 0) intervalMs = 500;
        String last = currentText();
        // Emit the current value immediately so callers don't have to
        // race the first poll.
        if (last != null) {
            if (!emit(out, last)) return;
        } else {
            last = "";
        }
        while (true) {
            try { Thread.sleep(intervalMs); }
            catch (InterruptedException e) { break; }
            String now = currentText();
            if (now == null) continue;
            if (!now.equals(last)) {
                if (!emit(out, now)) return;
                last = now;
            }
        }
        // Best-effort terminator.
        try {
            synchronized (out) { out.writeInt(0); out.flush(); }
        } catch (IOException ignored) {}
    }

    private String currentText() {
        try {
            Object service = svc();
            Method m = getOrFindGet(service.getClass());
            if (m == null) return null;
            ClipData cd = (ClipData) m.invoke(service, buildArgs(m, null));
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence cs = cd.getItemAt(0).getText();
            return cs == null ? "" : cs.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean emit(DataOutputStream out, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        try {
            synchronized (out) {
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------- reflection helpers ----------

    private Method getOrFindGet(Class<?> cls) {
        Method cached = getClip;
        if (cached != null) return cached;
        synchronized (this) {
            if (getClip != null) return getClip;
            for (Method m : cls.getMethods()) {
                if (!"getPrimaryClip".equals(m.getName())) continue;
                if (m.getReturnType() != ClipData.class) continue;
                // Prefer the longest (most-recent) overload.
                if (getClip == null
                        || m.getParameterTypes().length > getClip.getParameterTypes().length) {
                    getClip = m;
                }
            }
            return getClip;
        }
    }

    private Method getOrFindSet(Class<?> cls) {
        Method cached = setClip;
        if (cached != null) return cached;
        synchronized (this) {
            if (setClip != null) return setClip;
            for (Method m : cls.getMethods()) {
                if (!"setPrimaryClip".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 1 || p[0] != ClipData.class) continue;
                if (setClip == null
                        || p.length > setClip.getParameterTypes().length) {
                    setClip = m;
                }
            }
            return setClip;
        }
    }

    /** Build the arg array for getPrimaryClip / setPrimaryClip. The
     *  signatures across API levels share the same shape:
     *  {@code [ClipData?] String pkg [, String attributionTag] [, int userId] [, int deviceId]} */
    private Object[] buildArgs(Method m, ClipData clipDataOrNull) {
        Class<?>[] pt = m.getParameterTypes();
        Object[] args = new Object[pt.length];
        int i = 0;
        if (clipDataOrNull != null && pt[0] == ClipData.class) {
            args[i++] = clipDataOrNull;
        }
        boolean stringSlotConsumed = false;
        for (; i < pt.length; i++) {
            Class<?> c = pt[i];
            if (c == String.class) {
                // First String → calling pkg; any later String → attributionTag (null is fine).
                args[i] = stringSlotConsumed ? null : caller;
                stringSlotConsumed = true;
            } else if (c == int.class) {
                args[i] = 0;            // userId / deviceId — 0 = current / default
            } else if (c == boolean.class) {
                args[i] = Boolean.FALSE; // e.g. autoSelectionAllowed flag added in newer APIs
            } else if (c == long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    // ---------- ClipData → bytes ----------

    private static byte[] clipDataToBytes(ClipData cd) {
        if (cd.getItemCount() == 0) return new byte[0];
        ClipData.Item item = cd.getItemAt(0);
        CharSequence cs = item.getText();
        if (cs != null) return cs.toString().getBytes(StandardCharsets.UTF_8);
        // No text item — emit a short note describing what's there.
        StringBuilder sb = new StringBuilder();
        if (item.getUri() != null) sb.append("uri:").append(item.getUri());
        else if (item.getIntent() != null) sb.append("intent:").append(item.getIntent());
        else sb.append("");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] err(String tail) {
        return ("ERR:" + tail).getBytes(StandardCharsets.UTF_8);
    }
}
