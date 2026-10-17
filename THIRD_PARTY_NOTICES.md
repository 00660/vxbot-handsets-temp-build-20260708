# 第三方来源与许可

本项目的狗头军师功能基于并适配 [goutoujunshi-jev-chat](https://github.com/shengjidaguai-china/goutoujunshi-jev-chat)，来源仓库、Android 集成源码、参考资料与示例均保存在 [`third_party/goutoujunshi-jev-chat`](third_party/goutoujunshi-jev-chat)。功能实现按其 Android 适配提供的证据判断、策略、候选、联系人知识、历史与趋势能力接入 HS 微信机器人，并复用本项目的微信通知、OCR、消息历史和回复链路。

- `third_party/goutoujunshi-jev-chat/LICENSE`：goutoujunshi-jev-chat（Copyright (c) 2026 powerycy），MIT License。
- `third_party/goutoujunshi-jev-chat/integrations/jev_android/LICENSE` 与 `NOTICE`：Jev Chat Android 适配来源（Copyright (c) 2026 Finderchangchang and the jev-chat contributors），MIT License。NOTICE 要求分发时保留许可与署名。

自动发送由 HS 微信机器人的现有自动回复链路完成；第三方 Android 预览应用本身只生成草稿。HS 自动策略启用时，会依次判断、生成候选和排序，自动采用第一名。用户可以在功能开关中关闭狗头军师；本机长期聊天历史另有独立开关，默认关闭。
