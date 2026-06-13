# HS 微信机器人 APK

这是机器人 APK 的干净源码仓库，只保留编译和协同开发需要的内容：

- `app/`：Android APK 主工程。
- `src/dev/handsets/daemon/`：APK 内置 hs daemon 源码，供 `HsDaemonManager` 通过 root/shizuku 链路启动。
- `.github/workflows/wechatbot-apk.yml`：GitHub Actions 远端构建 APK，并把最新 APK 发布到 `apk-build-output` 分支。

仓库不保存本地调试截图、日志、APK 产物、历史备份、交接草稿或 handsets 原项目文档。

## 当前版本

- `applicationId`：`com.vxbot.wechatbot`
- `versionCode`：`94`
- `versionName`：`0.1.93-doubao-cookie-file-only`
- 默认上游文字接口：`http://192.168.2.157:8317/v1/chat/completions`
- 默认图片接口：`http://192.168.3.1:3002/v1`

## 已实现功能

- 微信通知监听：从系统通知提取群名、发送人、消息文本，按白名单群处理。
- 名字匹配归一化：白名单、续聊发起人、喷子/恋人目标、OCR 会话名、图片/视频分享目标统一过滤括号表情、真实 emoji 和非文字符号。
- 白名单隔离：上下文、发起人锁、喷子目标、恋人目标均按群隔离。
- 普通聊天：调用 OpenAI 兼容接口回复，支持文字/语音回复开关。
- 语音回复：TTS 生成后通过微信“按住说话”录音发送，支持千问/豆包/MiMo TTS 下拉切换、发音人和语速配置；豆包支持浏览器 Cookie JSON 文件或完整 Cookie 文件导入，界面不展示 Cookie 原文，三段 Cookie 仅作兜底，MiMo 使用 `api-key` 调用小米 `mimo-v2.5-tts`，失败自动回退千问。
- TTS 试听：功能页提供 `试听 TTS`，按当前 provider、角色、语速或控制参数生成一句测试语并在 APK 内播放。
- 输入态缓存：默认按每个白名单群自动识别文字/语音/输入法弹出态并缓存；语音真正按下前每次实时 OCR 当前屏幕的 `按住` 或 `说话` 区域，不使用缓存按压坐标。
- 图片自拍：支持人物参考图、清凉/泳装风格参考图、前置/后置话术、图片发送；`几张自拍` 默认串行生成 3 张，`2-7张自拍` 按数量排队，`七情自拍/喜怒哀乐悲恐惊自拍` 串行生成 7 张不同情绪自拍。
- 图片分析/表情：支持引用图点开截图后传上游分析或生成表情图。
- 群发消息：从配置面板向白名单群依次发送自定义消息。
- 每日问好：定时向白名单群发送随机问好。
- 金融/天气/新闻/体育/本地工具/羊毛：内置实时工具分流与回复；金融支持股票代码、A/HK/US 个股名称搜索、指数、汇率、贵金属克价、常见虚拟币和 CoinGecko 代币搜索，不再用 BTC/ETH 兜底冒充未知代币；体育支持世界杯、足球联赛、NBA/WNBA/NFL/NHL/MLB 等赛程比分；赛事分析类问题会带实时赛程上下文请求上游分析。
- 短视频/图集解析：命中抖音、快手、小红书、微博、B站等分享链接后，APK 内置 parse-video 平台解析逻辑直接解析，下载无水印视频、图集或 LivePhoto，并复用图片分享链路发回当前群。
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
- 名字归一化：`app/src/main/java/com/vxbot/wechatbot/NameNormalizer.java`
- 前台服务：`app/src/main/java/com/vxbot/wechatbot/BotService.java`
- 通知监听：`app/src/main/java/com/vxbot/wechatbot/WxNotificationListener.java`
- 消息分类：`app/src/main/java/com/vxbot/wechatbot/MessageRouter.java`
- 上游文字请求：`app/src/main/java/com/vxbot/wechatbot/ChatClient.java`
- 微信操作：`app/src/main/java/com/vxbot/wechatbot/WechatDriver.java`
- OCR/找图找色：`app/src/main/java/com/vxbot/wechatbot/OcrHelper.java`
- 图片流程：`app/src/main/java/com/vxbot/wechatbot/ImageFlow.java`
- 短视频解析：`app/src/main/java/com/vxbot/wechatbot/VideoParseFlow.java`
- 内置平台解析器：`app/src/main/java/com/vxbot/wechatbot/InlineVideoParser.java`
- 表情流程：`app/src/main/java/com/vxbot/wechatbot/StickerFlow.java`
- 语音流程：`app/src/main/java/com/vxbot/wechatbot/VoiceReply.java`、`VoiceDemoService.java`
- TTS 缓存与上游：`app/src/main/java/com/vxbot/wechatbot/TtsCache.java`、`DoubaoWebTtsClient.java`、`MimoTtsClient.java`
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
- `input.mode.whitelist.sync`：开启“语音开关批量同步输入态”后，按配置同步白名单群文字/语音输入态。
- `input.mode.cache.sync.skip` / `input.mode.whitelist.sync.skip`：批量同步输入态关闭，保留每群自动识别缓存。
- `input.mode.inspect`：输入态识别结果，包含 `pressTalk`、`voiceBarVisual`、`keyboardVisual`、`imeVisible` 等三态判断信号。
- `input.mode.current_matches`：缓存输入态与现场不一致时，现场已是目标态，已更新缓存并跳过切换。
- `voice.demo.press.point.realtime.scan`：语音按下前实时 OCR 未命中 `按住` 或 `说话` 区域。
- `voice.demo.press.point.realtime.hit`：语音按下前实时 OCR 命中 `按住` 或 `说话` 区域。
- `tts.doubao.start` / `tts.doubao.done`：豆包网页 TTS 实验链路开始和生成完成。
- `tts.mimo.start` / `tts.mimo.done`：MiMo TTS 开始和生成完成。
- `tts.preview.fail` / `tts.preview.play.fail`：面板试听生成或播放失败。
- `tts.prepare.fallback`：豆包三段 Cookie 缺失、MiMo API Key 缺失、失效或请求失败后，自动回退千问 TTS。
- `chat.tool.direct`：新闻、金融、天气、体育和本地工具命中后直接使用实时工具结果回复。
- `video.parse.start` / `video.parse.done`：短视频或图集解析开始和完成。
- `video.inline.http`：APK 内置解析器请求平台页面/API 的 HTTP 返回状态和响应体大小。
- `video.bili.ids`：B 站解析到的 `bvid` 和 `cid`，`cid` 必须按字符串/long 处理，不能用 32 位 int。
- `video.media.download`：解析出的媒体文件已下载到本机缓存。
- `media.share.done`：视频、图集或封面通过微信分享链路发送完成。
- `image.batch.start`：批量自拍队列启动，后续每张仍走 `image.api.start`、`image.save` 和图片分享链路。
- `screen.dim.enable` / `screen.dim.disable`：低亮防熄屏开启或关闭。
- `screen.brightness.set`：系统亮度写入成功。
- `screen.brightness.skip`：未授权 `WRITE_SETTINGS`，只使用低亮窗口。
- `notice.raw`：收到微信通知。
- `send.text` / `voice.reply.done`：文字/语音发送完成。

## 最近交接

- 2026-06-13：豆包 TTS Cookie 支持浏览器导出的 JSON 数组文件导入。配置页新增“导入 Cookie 文件”按钮，导入后将 JSON 内所有 `name/value` 转成完整 Cookie Header 保存；已用用户提供的浏览器导出 Cookie 转换后跑通原始 `doubao-tts.zip` 客户端，生成 `taozi.aac` 大小 `10639` 字节。
- 2026-06-13：豆包 TTS 增加完整 Cookie Header 输入，完整 Cookie 优先，三段 `sessionid/sid_guard/uid_tt` 仅作兜底；同时把豆包 `device_id/web_id` 改为 SharedPreferences 持久化，首次生成后长期复用，避免每次请求随机设备指纹触发风控。
- 2026-06-13：豆包 TTS 角色配置按 `doubao-tts.zip` 原客户端 `SPEAKERS` 映射修正，面板显示 `taozi/shuangkuai/tianmei/qingche/yangguang/chenwen/rap/en_female/en_male` alias，保存时仍写完整 speaker id；旧配置若填 alias 也能正确归一化。当前实测手机里的三段 Cookie 调原客户端返回 `710022002 block`，这是服务端认证/频率/IP 阻断，不是单个角色 ID 拼写错误。
- 2026-06-13：TTS 配置 UI 改为按 provider 动态显示，只展示当前选择的千问/豆包/MiMo 配置表单；语音发送改为按住微信说话后先等待 500ms 再播放 TTS，避免漏开头；长回复会按微信 60 秒语音限制估算切分，多段之间等待 800ms 后连续发送。
- 2026-06-13：修复豆包 TTS 握手失败。旧 APK 使用 Android UA 和 mp3 参数会让豆包 WSS 返回 HTTP 200 而不是 101；已按本地跑通的 `doubao-tts.zip` 客户端对齐为 Mac Chrome UA、aac 输出和同款参数，失败仍回退千问。
- 2026-06-13：本次修改前本地备份目录：`.backup-20260613-074857-doubao-websocket-fix`、`.backup-20260613-075039-doubao-version-handoff`、`.backup-20260613-080253-tts-ui-voice-split`、`.backup-20260613-083057-doubao-voice-alias`、`.backup-20260613-085421-doubao-persistent-fingerprint`。
- 2026-06-11：修正图片自拍 prompt 拼接，移除发给图片上游的内部“提示词整理器”身份头、`用户原话：自拍` 字段和单条固定场景硬约束；自拍场景改为多地点候选策略，避免固定室内/客厅背景。
- 2026-06-11：修正语音输入态坐标重建。会话已记录为语音态但缺少 `按住说话` 坐标时，先多轮 OCR 扫描并缓存坐标；只有确认当前是文字态才允许切换，避免在已处于语音态时误点左下角切换按钮。
- 2026-06-11：修正输入态缓存与现场不一致导致反向切换的问题。发送文字/语音前先用 OCR 判断当前底部输入态，若现场已是目标态则更新该群缓存并跳过切换。
- 2026-06-11：增强输入态识别为三态判断：OCR `按住说话`、底部输入栏像素/颜色特征、输入法窗口可见状态共同判断语音态、文字态和输入法弹出态。
- 2026-06-11：新增“语音开关批量同步输入态”开关，默认关闭。关闭时普通聊天/前置/后置语音开关不会批量覆盖白名单群输入态缓存，继续使用每群自动识别。
- 2026-06-11：语音按压点改为发送前实时 OCR 识别当前屏幕的 `按住` 或 `说话`，不再读取或保存会话缓存坐标；兼容微信 OCR 把 `按住 说话` 拆开的情况。
- 2026-06-12：新增短视频/图集解析完整链路。`MessageRouter` 识别 parse-video 支持平台分享链接，`InlineVideoParser` 将 parse-video 的平台解析逻辑内置到 APK，不依赖 Docker/3001/外部解析服务；`VideoParseFlow` 下载 `video_url`、`images[].url`、`images[].live_photo_url` 或封面，复用 `ImageFlow.shareExistingMedia` 发回群。
- 2026-06-12：修复 B 站解析 `cid` 溢出。B 站新视频 `cid` 可能超过 32 位，Java `optInt()` 会把 `39010699439` 溢出成错误值，导致播放接口返回“啥都木有”；现改为字符串读取并写入 `video.bili.ids` 日志。
- 2026-06-12：修复群名/人名带表情导致 OCR 和通知匹配不一致。新增 `NameNormalizer`，比较前只保留文字和数字，过滤 `[表情]`、`(表情)`、真实 emoji、零宽连接符和其他符号；白名单、续聊发起人、喷子/恋人目标、会话标题、群发搜索、图片/视频分享目标均复用同一规则。
- 2026-06-12：新增批量自拍串行队列。`几张/多张自拍` 默认 3 张，`2-7张自拍` 按数量生成，`七情/7情/喜怒哀乐悲恐惊自拍` 拆成 7 个情绪子任务；一次请求只发一次前置和一次后置，中间图片按 `BotService` 单队列串行生成并分享。
- 2026-06-12：新增豆包网页 TTS 实验 provider。面板可在千问 TTS / 豆包 TTS 间下拉切换；豆包配置使用 `sessionid`、`sid_guard`、`uid_tt` 三个输入框和独立发音人下拉框，按网页 WSS 协议生成 AAC，失败回退千问。
- 2026-06-12：新增体育赛事和本地工具分流。体育查询默认走 ESPN scoreboard，覆盖世界杯、足球联赛和 NBA/WNBA/NFL/NHL/MLB；简单计算、单位换算、北京时间查询在 APK 内处理。带“分析/预测/谁赢”等语义时，将实时赛事结果送给上游生成分析。
- 2026-06-12：新增 MiMo TTS provider 和试听按钮。MiMo 按 `manymuch/mimo_tts` 的 `chat.completions + audio.data(base64 WAV)` 方式接入，面板可填 endpoint、API Key、预置声音、自然语言控制、音频标签控制；`试听 TTS` 会直接生成并播放测试音频。
- 2026-06-13：修复虚拟币兜底错误。已知币种仍优先用 Yahoo 映射；未知代币改走 CoinGecko search + simple price；只有泛问“虚拟币/币圈”才返回 BTC/ETH 概览，查不到具体代币时明确提示未识别。
- 2026-06-13：补齐个股名称搜索。A 股、港股和美股名称先走腾讯 smartbox 解析市场与代码；A/HK 行情走腾讯 `qt.gtimg.cn`，US 搜索结果转 Yahoo symbol 查询，避免“个股名无法查询”时退成市场概览。
- 2026-06-13：修复豆包 TTS 握手失败。旧 APK 使用 Android UA 和 `mp3` 参数会让豆包 WSS 返回 HTTP 200 而不是 101；已按本地跑通的 `doubao-tts.zip` 客户端对齐为 Mac Chrome UA、`aac` 输出和同款参数，失败仍回退千问。
- 2026-06-13：豆包完整 Cookie UI 改为只支持文件导入，不再显示完整 Cookie 多行输入框，避免几百行内容占屏和复制丢字段；保存配置不会覆盖已导入的完整 Cookie。
- 2026-06-13：本次修改前本地备份目录：`.backup-20260612-234119-tts-sports-mimo-preview`、`.backup-20260613-001717-crypto-dynamic-lookup`、`.backup-20260613-005435-realtime-compact-money`、`.backup-20260613-010139-readme-install-handoff`；本次本地验证执行 `:app:assembleDebug`，结果 `BUILD SUCCESSFUL`；已安装到 `192.168.2.89`，版本 `versionCode=88` / `versionName=0.1.87-tools-tts`。
- 2026-06-11：新增 Release 下载页发布、无 root 低亮防熄屏开关、菜单/操作手册指令、屏幕最暗/最亮指令、续聊控制人白名单、自拍气质和北京时间实景约束。
- 本次修改前本地备份目录：`.backup-20260611-171112`、`.backup-20260611-172627-followup`、`.backup-20260611-173501-controller-whitelist`、`.backup-20260611-182856-image-prompt`、`.backup-20260611-183913-voice-mode-rebuild`、`.backup-20260611-185336-version-handoff`、`.backup-20260611-191702-input-mode-current-state`、`.backup-20260611-193915-inputmode-visual-ime`、`.backup-20260611-203307-inputmode-sync-switch`、`.backup-20260611-211851-realtime-voice-press-ocr`、`.backup-20260612-075746-video-parse-flow`、`.backup-20260612-084957-video-inline-parser`、`.backup-20260612-102704-bilibili-playurl-fix`、`.backup-20260612-203115-emoji-name-normalize`、`.backup-20260612-213125-selfie-batch-queue`。备份目录仅供本机回滚，不提交到仓库。

## 维护注意

- 不要把 `*.bak-*`、调试截图、日志、APK 产物、临时 dump、交接草稿提交进仓库。
- 修改微信操作链路前，优先在真机小 demo 或单步截图验证，不要直接重写主链路。
- 微信 UI 控件很多不可读，判断会话页、通知栏、输入态、发送按钮时优先用 OCR/找色/找图，避免写死坐标。
- 群名和人名比较必须走 `NameNormalizer`，不要在各链路里重新写清洗规则；通知里的括号表情和微信界面的真实 emoji 要归一成同一个名字。
- 语音输入态按白名单群独立缓存；默认不要用语音开关批量覆盖缓存。只有明确打开“语音开关批量同步输入态”时，才按普通聊天/前置/后置语音开关批量写入白名单群输入态。语音按压点必须发送前实时识别，不能复用旧坐标。
- 短视频解析必须保持 APK 内置，不依赖 Docker、3001 或局域网解析服务。视频和图集分享复用图片分享链路，改分享逻辑前必须同时回归图片和视频。
- 图片、表情、分析、群发、TTS、普通聊天共用 `BotService` 单队列，避免多个并发链路抢微信前台。
- 豆包 TTS 不做短信登录、不自动抓网页 token；优先读取“导入 Cookie 文件”保存的完整 Cookie Header，三段 Cookie 只作兜底。若日志出现 Cookie 失效或风控，重新从已登录浏览器导出 Cookie 文件后导入。
- MiMo TTS 不走网页登录态，使用小米 API 平台 `api-key`；返回结构按 `choices[0].message.audio.data` 解析 base64 WAV，若接口变更先看 `tts.mimo.*` 日志。
