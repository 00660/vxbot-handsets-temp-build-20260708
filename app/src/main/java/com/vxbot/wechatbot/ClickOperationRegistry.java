package com.vxbot.wechatbot;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ClickOperationRegistry {
    public static final String INPUT_MODE_TOGGLE = "input_mode_toggle";
    public static final String TEXT_INPUT = "text_input";
    public static final String SEND_BUTTON = "send_button";
    public static final String WECHAT_SEARCH_ENTRY = "wechat_search_entry";

    private static final List<Operation> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
            new Operation(WECHAT_SEARCH_ENTRY, "微信右上角搜索"),
            new Operation(INPUT_MODE_TOGGLE, "文字/语音模式切换"),
            new Operation(TEXT_INPUT, "聊天输入框"),
            new Operation(SEND_BUTTON, "聊天发送按钮")
    ));

    private ClickOperationRegistry() {
    }

    public static List<Operation> all() {
        return OPERATIONS;
    }

    public static final class Operation {
        public final String id;
        public final String label;

        private Operation(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }
}
