# HS 微信机器人 APK

这是机器人 APK 的干净源码仓库，只保留编译和协同开发需要的内容：

- `app/`：Android APK 主工程。
- `src/dev/handsets/daemon/`：APK 内置 hs daemon 源码，供 `HsDaemonManager` 通过 root/shizuku 链路启动。
- `.github/workflows/wechatbot-apk.yml`：GitHub Actions 远端构建 APK，并把最新 APK 发布到 `apk-build-output` 分支。

仓库不保存本地调试截图、日志、APK 产物、历史备份、交接草稿或 handsets 原项目文档。

## 当前版本

- `applicationId`：`com.vxbot.wechatbot`
- `versionCode`：`77`
- `versionName`：`0.1.76-boot-keepawake-manual`
- 默认上游文字接口：`http://192.168.2.157:8317/v1/chat/completions`
- 默认图片接口：`http://192.168.3.1:3002/v1`

## 已实现功能

- 微信通知监听：从系统通知提取群名、发送人、消息文本，按白名单群处理。
- 白名单隔离：上下文、发起人锁、喷子目标、恋人目标均按群隔离。
- 普通聊天：调用 OpenAI 兼容接口回复，支持文字/语音回复开关。
- 语音回复：TTS 生成后通过微信“按住说话”录音发送，支持发音人和语速配置。
- 输入态缓存：每个白名单群单独保存文字/语音输入态；语音模式缺少按压坐标时，进入该群扫描 `按住说话` 并保存该群坐标。
- 图片自拍：支持人物参考图、清凉/泳装风格参考图、前置/后置话术、图片发送。
- 图片分析/表情：支持引用图点开截图后传上游分析或生成表情图。
- 群发消息：从配置面板向白名单群依次发送自定义消息。
- 每日问好：定时向白名单群发送随机问好。
- 金融/天气/新闻/羊毛：内置实时工具分流与回复。
- 注册机：对接注册码/授权码查询链路，固定文字回复。
- 赛博喷子模式：支持触发、目标锁定、退出指令。
- 恋人模式：支持 `撩一下$name`、`表白$name`、`跟$name表白` 等触发。
- 悬浮日志：支持开关、拖动、收起为小圆点。
- 支付监听：独立线程监听微信收款通知并回调后端。
- 保活：前台服务、BootReceiver、KeepAliveScheduler、root/hs daemon 恢复检查。
- 无 root 保活：`shizuku` 模式可开启低亮透明防熄屏窗口，支持指令恢复亮度。
- 菜单指令：群里发送 `菜单`、`操作手册`、`帮助`、`指令` 可回复机器人操作手册。
- 屏幕指令：`屏幕最暗` 开启低亮防熄屏，`屏幕最亮` 关闭低亮窗口并尝试恢复系统亮度。
- 续聊免 @：可按群持久化最初 @ 的发起人；只有“续聊控制人白名单”里的微信名能获得发起人锁，避免群友先 @ 后抢控制权。
- 垃圾清理：定期清理截图、生成图、TTS 缓存等运行产物。

## 关键链路

- 配置面板：`app/src/main/java/com/vxbot/wechatbot/MainActivity.java`
- 配置读取：`app/src/main/java/com/vxbot/wechatbot/BotConfig.java`
- 前台服务：`app/src/main/java/com/vxbot/wechatbot/BotService.java`
- 通知监听：`app/src/main/java/com/vxbot/wechatbot/WxNotificationListener.java`
- 消息分类：`app/src/main/java/com/vxbot/wechatbot/MessageRouter.java`
- 上游文字请求：`app/src/main/java/com/vxbot/wechatbot/ChatClient.java`
- 微信操作：`app/src/main/java/com/vxbot/wechatbot/WechatDriver.java`
- OCR/找图找色：`app/src/main/java/com/vxbot/wechatbot/OcrHelper.java`
- 图片流程：`app/src/main/java/com/vxbot/wechatbot/ImageFlow.java`
- 表情流程：`app/src/main/java/com/vxbot/wechatbot/StickerFlow.java`
- 语音流程：`app/src/main/java/com/vxbot/wechatbot/VoiceReply.java`、`VoiceDemoService.java`
- hs 启动：`app/src/main/java/com/vxbot/wechatbot/HsDaemonManager.java`
- hs 客户端：`app/src/main/java/com/vxbot/wechatbot/HsClient.java`
- 支付通知：`app/src/main/java/com/vxbot/wechatbot/PaymentNoticeFlow.java`

## 构建方式

优先使用 GitHub Actions 远端构建，不在本机跑 Android 编译。

手动触发：

```bash
POST /repos/00660/vxbot-handsets/actions/workflows/wechatbot-apk.yml/dispatches
{"ref":"main"}
```

构建成功后：

- 最新 APK：`apk-build-output` 分支的 `hs-wechatbot-latest.apk`
- 下载页：GitHub Release `latest` 附件 `hs-wechatbot-latest.apk`
- 构建信息：`apk-build-output` 分支的 `BUILD_INFO.txt`
- Actions artifact：`hs-wechatbot-debug-apk`

本地只在排查构建问题时使用：

```bash
gradle :app:assembleDebug --stacktrace
```

## 安装验证

安装到设备后确认版本：

```bash
pm install -r -d /data/local/tmp/hs-wechatbot-latest.apk
dumpsys package com.vxbot.wechatbot | grep -E 'versionCode|versionName'
```

启动服务：

```bash
am start-foreground-service -n com.vxbot.wechatbot/.BotService -a com.vxbot.wechatbot.START
```

重点日志：

- `bot.service.create`：服务实例创建。
- `bot.start`：机器人启动结果。
- `input.mode.whitelist.sync`：已按配置同步白名单群文字/语音输入态。
- `input.mode.voice_point_rebuilt`：某群语音按压坐标重建成功。
- `screen.dim.enable` / `screen.dim.disable`：低亮防熄屏开启或关闭。
- `screen.brightness.set`：系统亮度写入成功。
- `screen.brightness.skip`：未授权 `WRITE_SETTINGS`，只使用低亮窗口。
- `notice.raw`：收到微信通知。
- `send.text` / `voice.reply.done`：文字/语音发送完成。

## 最近交接

- 2026-06-11：新增 Release 下载页发布、无 root 低亮防熄屏开关、菜单/操作手册指令、屏幕最暗/最亮指令、续聊控制人白名单、自拍气质和北京时间实景约束。
- 本次修改前本地备份目录：`.backup-20260611-171112`、`.backup-20260611-172627-followup`、`.backup-20260611-173501-controller-whitelist`。备份目录仅供本机回滚，不提交到仓库。

## 维护注意

- 不要把 `*.bak-*`、调试截图、日志、APK 产物、临时 dump、交接草稿提交进仓库。
- 修改微信操作链路前，优先在真机小 demo 或单步截图验证，不要直接重写主链路。
- 微信 UI 控件很多不可读，判断会话页、通知栏、输入态、发送按钮时优先用 OCR/找色/找图，避免写死坐标。
- 语音输入态按白名单群独立缓存；全局语音开关开启时，所有白名单群应写为 `voice`，坐标在该群首次进入会话后扫描保存。
- 图片、表情、分析、群发、TTS、普通聊天共用 `BotService` 单队列，避免多个并发链路抢微信前台。
