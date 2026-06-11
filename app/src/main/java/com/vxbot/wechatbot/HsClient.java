package com.vxbot.wechatbot;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class HsClient {
    private final int port;

    public HsClient(int port) {
        this.port = port;
    }

    public String command(String command) throws Exception {
        byte[] frame = commandBytes(command);
        return new String(frame, StandardCharsets.UTF_8);
    }

    public String streamCommand(String command) throws Exception {
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                int len = in.readInt();
                if (len == 0) {
                    break;
                }
                if (len < 0 || len > 50 * 1024 * 1024) {
                    throw new IllegalStateException("bad frame length: " + len);
                }
                byte[] frame = new byte[len];
                in.readFully(frame);
                if (buffer.size() > 0) {
                    buffer.write('\n');
                }
                buffer.write(frame);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public byte[] commandBytes(String command) throws Exception {
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(10000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            return readFrame(new DataInputStream(socket.getInputStream()));
        }
    }

    public boolean ping() {
        try {
            return "pong".equals(command("ping"));
        } catch (Exception ignored) {
            return false;
        }
    }

    public String healthLabel() {
        return ping() ? "端口响应" : "端口未响应";
    }

    public String tap(int x, int y) throws Exception {
        return command("tap x=" + x + " y=" + y);
    }

    public String down(int x, int y) throws Exception {
        return command("down x=" + x + " y=" + y);
    }

    public String up(int x, int y) throws Exception {
        return command("up x=" + x + " y=" + y);
    }

    public String key(String name) throws Exception {
        return command("key " + name);
    }

    public String keyCode(int code) throws Exception {
        return command("key code=" + code);
    }

    public String clipSet(String text) throws Exception {
        return command("clip_set " + (text == null ? "" : text));
    }

    public String startActivity(String component) throws Exception {
        return command("am_start n=" + component);
    }

    public String dumpActive() throws Exception {
        return command("dump_active");
    }

    public String info() throws Exception {
        return command("info");
    }

    public String stateTop() throws Exception {
        return command("state top");
    }

    public byte[] screenshotJpeg() throws Exception {
        return commandBytes("screenshot max=1 q=90 fmt=jpeg");
    }

    public String shell(String... args) throws Exception {
        StringBuilder builder = new StringBuilder("shell");
        if (args != null) {
            for (String arg : args) {
                if (arg == null || arg.isEmpty()) {
                    continue;
                }
                builder.append(' ').append(arg);
            }
        }
        return streamCommand(builder.toString());
    }

    public String textInput(String text) throws Exception {
        return command("text " + (text == null ? "" : text));
    }

    public String setText(String selector, String value) throws Exception {
        String sel = selector == null ? "" : selector.trim();
        String prefix = sel.isEmpty() ? "" : sel + " ";
        return command("node_set_text " + prefix + "value=" + quote(value));
    }

    public String clickNode(String selector) throws Exception {
        return command("node_click " + selector);
    }

    public String focusNode(String selector) throws Exception {
        return command("node_focus " + selector);
    }

    public String waitForText(String text, long timeoutMs) throws Exception {
        return command("wait_for_text text=" + quote(text) + " match=exact timeout_ms=" + timeoutMs);
    }

    public String paste(String selector) throws Exception {
        String sel = selector == null ? "" : selector.trim();
        return sel.isEmpty() ? command("paste") : command("paste " + sel);
    }

    public String submit(String selector) throws Exception {
        String sel = selector == null ? "" : selector.trim();
        return sel.isEmpty() ? command("submit") : command("submit " + sel);
    }

    public static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static byte[] readFrame(DataInputStream in) throws Exception {
        int len = in.readInt();
        if (len < 0 || len > 50 * 1024 * 1024) {
            throw new IllegalStateException("bad frame length: " + len);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(len);
        byte[] chunk = new byte[Math.min(8192, Math.max(1, len))];
        int remaining = len;
        while (remaining > 0) {
            int read = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (read < 0) {
                throw new IllegalStateException("socket closed");
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return buffer.toByteArray();
    }

}
