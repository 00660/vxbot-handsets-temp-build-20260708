package dev.handsets.daemon;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public final class ClipboardShell {

    public static void main(String[] args) throws Exception {
        liftHiddenApiRestrictions();
        String caller = args.length > 1 ? args[1] : null;
        Clipboard clip = new Clipboard(caller);
        byte[] out;
        if (args.length > 0 && "set".equals(args[0])) {
            String encoded = args.length > 2 ? args[2] : "";
            byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
            out = clip.directSet(new String(bytes, StandardCharsets.UTF_8));
        } else if (args.length > 0 && "get".equals(args[0])) {
            out = clip.directGet();
        } else {
            out = "ERR:usage:ClipboardShell get|set BASE64".getBytes(StandardCharsets.UTF_8);
        }
        System.out.write(out);
        System.out.flush();
        if (startsWithErr(out)) System.exit(2);
    }

    private static boolean startsWithErr(byte[] out) {
        return new String(out, StandardCharsets.UTF_8).startsWith("ERR:");
    }

    private static void liftHiddenApiRestrictions() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {}
    }

    private ClipboardShell() {}
}
