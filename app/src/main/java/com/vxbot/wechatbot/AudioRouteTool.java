package com.vxbot.wechatbot;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AudioRouteTool {
    private static final int AUDIO_DEVICE_TYPE_REMOTE_SUBMIX = readRemoteSubmixType();
    private static final int[] CAPTURE_PRESETS = new int[] {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
    };

    private AudioRouteTool() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "list";
        System.out.print(runForContext(systemContext(), mode));
    }

    public static String runForContext(Context context, String mode) throws Exception {
        exemptHiddenApi();
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            throw new IllegalStateException("AudioManager missing");
        }
        StringBuilder out = new StringBuilder();

        if ("list".equalsIgnoreCase(mode)) {
            appendDevices(out, audioManager);
            appendCapturePresetMethods(out);
        } else if ("set-submix".equalsIgnoreCase(mode) || "prefer-submix".equalsIgnoreCase(mode)) {
            AudioDeviceInfo submix = findDevice(audioManager, AudioManager.GET_DEVICES_INPUTS, AUDIO_DEVICE_TYPE_REMOTE_SUBMIX);
            if (submix == null) {
                throw new IllegalStateException("Remote Submix input missing");
            }
            Object deviceAttr = newAudioDeviceAttributes(submix);
            for (int preset : CAPTURE_PRESETS) {
                out.append("preset=").append(preset)
                        .append(" set=").append(setPreferredCaptureDevice(audioManager, preset, deviceAttr))
                        .append('\n');
            }
            appendPreferred(out, audioManager);
        } else if ("clear".equalsIgnoreCase(mode)) {
            for (int preset : CAPTURE_PRESETS) {
                out.append("preset=").append(preset)
                        .append(" clear=").append(clearPreferredCaptureDevice(audioManager, preset))
                        .append('\n');
            }
            appendPreferred(out, audioManager);
        } else if ("dump".equalsIgnoreCase(mode) || "status".equalsIgnoreCase(mode)) {
            appendDevices(out, audioManager);
            appendPreferred(out, audioManager);
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
        return out.toString();
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static void printDevices(AudioManager audioManager) {
        System.out.println("INPUTS");
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            System.out.println(describe(device));
        }
        System.out.println("OUTPUTS");
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            System.out.println(describe(device));
        }
    }

    private static void appendDevices(StringBuilder out, AudioManager audioManager) {
        out.append("INPUTS\n");
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            out.append(describe(device)).append('\n');
        }
        out.append("OUTPUTS\n");
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            out.append(describe(device)).append('\n');
        }
    }

    private static AudioDeviceInfo findDevice(AudioManager audioManager, int flag, int type) {
        for (AudioDeviceInfo device : audioManager.getDevices(flag)) {
            if (device != null && device.getType() == type) {
                return device;
            }
        }
        return null;
    }

    private static Object newAudioDeviceAttributes(AudioDeviceInfo device) throws Exception {
        Class<?> clazz = Class.forName("android.media.AudioDeviceAttributes");
        try {
            Constructor<?> constructor = clazz.getConstructor(AudioDeviceInfo.class);
            return constructor.newInstance(device);
        } catch (NoSuchMethodException ignored) {
        }
        int roleInput = clazz.getField("ROLE_INPUT").getInt(null);
        Constructor<?> constructor = clazz.getConstructor(int.class, int.class, String.class);
        return constructor.newInstance(roleInput, device.getType(), device.getAddress());
    }

    private static boolean setPreferredCaptureDevice(AudioManager audioManager, int preset, Object deviceAttr) throws Exception {
        Class<?> attrClass = Class.forName("android.media.AudioDeviceAttributes");
        try {
            Method method = AudioManager.class.getMethod("setPreferredDeviceForCapturePreset", int.class, attrClass);
            return Boolean.TRUE.equals(method.invoke(audioManager, preset, deviceAttr));
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method method = AudioManager.class.getMethod("setPreferredDevicesForCapturePreset", int.class, List.class);
            return Boolean.TRUE.equals(method.invoke(audioManager, preset, Collections.singletonList(deviceAttr)));
        } catch (NoSuchMethodException ignored) {
        }
        Method method = AudioManager.class.getMethod("setDevicesRoleForCapturePreset", int.class, int.class, List.class);
        int rolePreferred = deviceRolePreferred();
        return Boolean.TRUE.equals(method.invoke(audioManager, preset, rolePreferred, Collections.singletonList(deviceAttr)));
    }

    private static boolean clearPreferredCaptureDevice(AudioManager audioManager, int preset) throws Exception {
        try {
            Method method = AudioManager.class.getMethod("clearPreferredDevicesForCapturePreset", int.class);
            return Boolean.TRUE.equals(method.invoke(audioManager, preset));
        } catch (NoSuchMethodException ignored) {
        }
        Method method = AudioManager.class.getMethod("clearDevicesRoleForCapturePreset", int.class, int.class);
        return Boolean.TRUE.equals(method.invoke(audioManager, preset, deviceRolePreferred()));
    }

    private static void dumpPreferred(AudioManager audioManager) throws Exception {
        for (int preset : CAPTURE_PRESETS) {
            try {
                Method method = AudioManager.class.getMethod("getPreferredDevicesForCapturePreset", int.class);
                Object value = method.invoke(audioManager, preset);
                System.out.println("preferred preset=" + preset + " value=" + value);
                continue;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method method = AudioManager.class.getMethod("getDevicesForCapturePreset", int.class);
                Object value = method.invoke(audioManager, preset);
                System.out.println("devices preset=" + preset + " value=" + value);
            } catch (NoSuchMethodException e) {
                System.out.println("preferred preset=" + preset + " value=<no getter>");
            }
        }
    }

    private static void appendPreferred(StringBuilder out, AudioManager audioManager) throws Exception {
        for (int preset : CAPTURE_PRESETS) {
            try {
                Method method = AudioManager.class.getMethod("getPreferredDevicesForCapturePreset", int.class);
                Object value = method.invoke(audioManager, preset);
                out.append("preferred preset=").append(preset).append(" value=").append(value).append('\n');
                continue;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method method = AudioManager.class.getMethod("getDevicesForCapturePreset", int.class);
                Object value = method.invoke(audioManager, preset);
                out.append("devices preset=").append(preset).append(" value=").append(value).append('\n');
            } catch (NoSuchMethodException e) {
                out.append("preferred preset=").append(preset).append(" value=<no getter>\n");
            }
        }
    }

    private static int deviceRolePreferred() {
        try {
            return AudioManager.class.getField("DEVICE_ROLE_PREFERRED").getInt(null);
        } catch (Exception ignored) {
            return 1;
        }
    }

    private static void printCapturePresetMethods() {
        List<String> names = new ArrayList<>();
        for (Method method : AudioManager.class.getMethods()) {
            String name = method.getName();
            if (name.contains("CapturePreset") || name.contains("DevicesRole")) {
                names.add(method.toString());
            }
        }
        Collections.sort(names);
        System.out.println("CAPTURE_METHODS");
        for (String name : names) {
            System.out.println(name);
        }
    }

    private static void appendCapturePresetMethods(StringBuilder out) {
        List<String> names = new ArrayList<>();
        for (Method method : AudioManager.class.getMethods()) {
            String name = method.getName();
            if (name.contains("CapturePreset") || name.contains("DevicesRole")) {
                names.add(method.toString());
            }
        }
        Collections.sort(names);
        out.append("CAPTURE_METHODS\n");
        for (String name : names) {
            out.append(name).append('\n');
        }
    }

    private static String describe(AudioDeviceInfo device) {
        return "id=" + device.getId()
                + " type=" + device.getType()
                + " sink=" + device.isSink()
                + " source=" + device.isSource()
                + " name=" + device.getProductName()
                + " address=" + device.getAddress();
    }

    private static int readRemoteSubmixType() {
        try {
            return AudioDeviceInfo.class.getField("TYPE_REMOTE_SUBMIX").getInt(null);
        } catch (Exception ignored) {
            return 25;
        }
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    String[].class
            );
            Object runtime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[] {
                    "Landroid/app/",
                    "Landroid/media/"
            });
            System.out.println("hidden_api_exemption=ok");
        } catch (Throwable e) {
            System.out.println("hidden_api_exemption=ignored " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }
}
