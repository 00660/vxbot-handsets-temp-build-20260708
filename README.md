# HS 微信机器人 APK

这是机器人 APK 的干净源码仓库，只保留编译和协同开发需要的内容：

- `app/`：Android APK 主工程。
- `src/dev/handsets/daemon/`：APK 内置 hs daemon 源码，供 `HsDaemonManager` 通过 root/shizuku 链路启动。
- `.github/workflows/wechatbot-apk.yml`：GitHub Actions 远端构建 APK，并把最新 APK 发布到 `apk-build-output` 分支。

仓库不保存本地调试截图、日志、APK 产物、历史备份、交接草稿或 handsets 原项目文档。

## 当前版本

- `applicationId`：`com.vxbot.wechatbot`
- `versionCode`：`143`
- `versionName`：`0.1.142-direct-ime-focus-retry`
- 默认上游文字接口：`http://192.168.2.157:8317/v1/chat/completions`
- 默认 Happy Codex 桥接接口：`http://192.168.2.204:8731/v1/codex`
- 默认图片接口：`http://192.168.3.1:3002/v1`

## 已实现功能

- 微信通知监听：从系统通知提取群名、发送人、消息文本，按白名单群处理。
- 名字匹配归一化：白名单、续聊发起人、喷子/恋人目标、OCR 会话名、图片/视频分享目标统一过滤括号表情、真实 emoji 和非文字符号。
- 白名单隔离：上下文、发起人锁、喷子目标、恋人目标均按群隔离。
- 人物画像：白名单群消息按群、成员、日期分组落盘；群里发送 `人物画像`、`昨日总结`、`谁是话痨`、`昨天说了啥` 会把压缩后的成员统计和发言样本交给上游大模型分析，返回话痨排行、性格画像、昨天/今天干了啥说了啥和群聊重点，上游失败时回落本地统计。
- 普通聊天：调用 OpenAI 兼容接口回复，支持文字/语音回复开关。
- Happy Codex：命中 `codex`、`代码`、`报错`、`bug`、`修复` 的技术请求时，优先调用容器内 Happy Codex 桥接接口；桥接服务复用 Happy 终端授权拉起/复用 Codex 会话，失败时回退普通上游。
- 语音回复：TTS 生成后通过微信“按住说话”录音发送，支持千问/豆包/MiMo TTS 下拉切换、发音人和语速配置；豆包支持浏览器 Cookie JSON 文件或完整 Cookie 文件导入，界面不展示 Cookie 原文，三段 Cookie 仅作兜底，MiMo 使用 `api-key` 调用小米 `mimo-v2.5-tts`，失败自动回退千问。
- TTS 试听：功能页提供 `试听 TTS`，按当前 provider、角色、语速或控制参数生成一句测试语并在 APK 内播放。
- 输入态缓存：默认按每个白名单群自动识别文字/语音/输入法弹出态并缓存；语音真正按下前每次实时 OCR 当前屏幕的 `按住` 或 `说话` 区域，不使用缓存按压坐标。
- 图片自拍：支持人物参考图、清凉/泳装风格参考图、前置/后置话术、图片发送；`几张自拍` 默认串行生成 3 张，`2-7张自拍` 按数量排队，`七情自拍/喜怒哀乐悲恐惊自拍` 串行生成 7 张不同情绪自拍。
- 图片分析/表情：支持引用图点开截图后传上游分析或生成表情图。
- 群发消息：从配置面板向白名单群依次发送自定义消息。
- 每日问好/新闻早报：定时向白名单群发送随机问好，并附带微博热搜、百度热榜和 Google News RSS 组成的今日早报；群里发送 `早报`、`晨报`、`今日简报` 也可手动触发。
- 金融/天气/新闻/体育/本地工具/羊毛：内置实时工具分流与回复；金融支持股票代码、A/HK/US 个股名称搜索、指数、汇率、贵金属克价；虚拟币查询优先走 Binance.US/Binance 交易所 ticker，再走 DexScreener 链上/DEX 池，最后才用 CoinGecko，支持合约地址和币安链/BSC 等链偏好，不再让虚拟币问题先落到 Yahoo 或固定 BTC/ETH；体育支持世界杯、足球联赛、NBA/WNBA/NFL/NHL/MLB 等赛程比分；赛事分析类问题会带实时赛程上下文请求上游分析。
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
- 人物画像存储：`app/src/main/java/com/vxbot/wechatbot/PersonaStore.java`
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

安装到目标手机只走脚本推送和手机 root shell 内安装。容器端先推 APK 和手机安装脚本：

```bash
./scripts/push-phone-install-script.sh ./hs-wechatbot-latest.apk
```

随后进入手机 shell，先 `su`，确认 `uid=0` 后再运行手机端脚本：

```bash
adb -s 192.168.2.89:5555 shell
su
id
sh /data/local/tmp/phone-install-vxbot.sh
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
- `persona.store.full` / `persona.store.error`：人物画像按群成员分组落盘时成员数超限或写入失败。
- `persona.analysis.request` / `persona.analysis.fallback`：人物画像上游大模型分析请求或失败后回落本地统计。
- `morning.briefing.fail`：定时新闻早报生成失败，继续发送普通问好。
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

- 2026-07-09：修复小白点输入法面板偶发不弹。原因是悬浮窗从 `FLAG_NOT_FOCUSABLE` 切到可聚焦后立刻调用 `showInputMethodPicker()` 会和系统焦点登记竞争，导致请求偶尔被吞；v143 改为先临时获取焦点，再在 120/420/900ms 分三次请求系统输入法选择面板，并把恢复非焦点悬浮窗延后到 3.5s。版本升到 `versionCode=143` / `versionName=0.1.142-direct-ime-focus-retry`。
- 2026-07-09：修复悬浮小白点直接弹系统输入法面板。v141 直接从 `FLAG_NOT_FOCUSABLE` 悬浮窗调用 `showInputMethodPicker()` 会记录请求但不显示面板；v142 点击“输入法”时临时让小白点窗口获取焦点，调用系统 `InputMethodManager.showInputMethodPicker()` 后自动恢复非焦点悬浮窗，不恢复透明 Activity 路线。版本升到 `versionCode=142` / `versionName=0.1.141-direct-ime-focus`。
- 2026-07-09：重写悬浮小白点输入法切换。删除透明 `ImePickerActivity` 桥接页和延迟重复请求逻辑，小白点“输入法”按钮直接调用 `InputMethodManager.showInputMethodPicker()` 弹出系统输入法选择面板；不再启动透明 Activity。版本升到 `versionCode=141` / `versionName=0.1.140-direct-ime-picker`。
- 2026-07-09：修复 Redmi 9A/dandelion v18 内核动态采样率虚拟麦 APK 注入链路。`VmicInjector` 对 MTK proc 路径不再把 TTS WAV 强制重采样到 48k，而是读取 WAV 原始采样率，写入 `/proc/mtk_virtual_mic_pcm` 前先向 `/proc/mtk_virtual_mic_ctl` 下发 `rate <hz>`；单声道 16-bit WAV 直接拷贝原始 PCM，多声道仅降为 mono 且保持采样率；WAV 解析改为选择最大的有效 `data` chunk，避免异常小 chunk 导致 `pcmBytes=2`。版本升到 `versionCode=140` / `versionName=0.1.139-vmic-source-rate`。
- 2026-07-09：修复悬浮小白点输入法按钮交互。点击“输入法”只打开系统输入法选择器，不再主动收起小白点面板；选择器由系统在用户选择后自动关闭。版本升到 `versionCode=139` / `versionName=0.1.138-ime-picker-keep-panel`。
- 2026-07-09：修复悬浮小白点输入法切换。`ImePickerActivity` 不再在 `onCreate()` 立即调用一次后快速退出，改为在 `onResume()` 和窗口获得焦点后请求 `InputMethodManager.showInputMethodPicker()`，有焦点后停止重复请求，并保留桥接页数秒，避免系统输入法选择器被过早关闭。版本升到 `versionCode=138` / `versionName=0.1.137-ime-picker-focus-once`。
- 2026-07-09：曾尝试把 Redmi 9A/dandelion 虚拟麦 APK 侧统一转成 `48000Hz/s16le/mono` PCM；该路线不适配 v18 动态采样率内核，后续以 `versionCode=140` 的原始采样率直写方案为准。
- 2026-07-09：运行页通用化。虚拟麦录音测试按钮仅在检测到 `/proc/mtk_virtual_mic_*` 或可用 helper 时显示；授权状态改为 root 或 Shizuku 任一授权即显示已授权；悬浮小白点输入法按钮改为通过临时 Activity 调起系统输入法选择器。版本升到 `versionCode=136` / `versionName=0.1.135-runtime-ui-fixes`。
- 2026-07-07：适配 Redmi 9A/dandelion 内核 proc 虚拟麦。`VmicInjector` 优先检测 `/proc/mtk_virtual_mic_ctl`、`/proc/mtk_virtual_mic_pcm`、`/proc/mtk_virtual_mic_status`，把机器人生成的 TTS WAV 在进程内转成 PCM 后通过 root 写入内核虚拟麦，并在按住微信语音期间启停 `enable`；旧 `vmic_play` / `vmic_push` helper 保留为兜底。版本升到 `versionCode=131` / `versionName=0.1.130-redmi9a-proc-vmic`。
- 2026-07-03：适配 1+8P PE13 虚拟麦模块。`VmicInjector` 先复用 mido 的 `vmic_play`，找不到时改走 `/vendor/bin/vmic_push`，并增加 `/data/adb/modules/instantnoodlep_vmic/system/vendor/bin/vmic_push` 兜底；TTS WAV 仍由机器人进程生成后通过 root helper 灌入虚拟麦。版本升到 `versionCode=130` / `versionName=0.1.129-instantnoodlep-vmic`。
- 2026-06-20：修正 Codex 桥接失败路径。容器内 `happy-codex-bridge` 默认直接驱动本地 `codex app-server`，不再因为缺 `/root/.happy/access.key` 阻断机器人 Codex；APK 侧 `CODEX` 路由只走 `happyCodexEndpoint`，桥接失败时记录 `codex.happy.fail` 并终止本次回复，不再回退普通聊天上游；版本升到 `versionCode=113` / `versionName=0.1.112-codex-bridge-no-fallback`。
- 2026-06-20：继续收紧 Codex 待命模式。某群进入 Codex 模式后，该群只处理绑定的授权人消息；其它成员即使 @ 机器人也不会落到普通金融、新闻、人物画像等路由，避免非授权成员绕过模式限制；版本升到 `versionCode=112` / `versionName=0.1.111-codex-mode-sender-lock`。
- 2026-06-20：收紧 Codex 待命模式权限。`进入codex模式`、`打开codex模式` 只能在白名单群内由“续聊控制人白名单”里的成员触发；模式按群持久化，并绑定触发人，进入后只有该授权人在该群的消息直接走 `CODEX`，其它群和其它成员不会进入 Codex；版本升到 `versionCode=111` / `versionName=0.1.110-session-codex-mode`。
- 2026-06-20：新增 Codex 待命模式初版。白名单群发送 `进入codex模式`、`打开codex模式`、`开启codex模式`、`全局codex模式` 后会持久化进入 Codex 模式；该初版在 `versionCode=111` 已收紧为按群和授权人绑定；版本升到 `versionCode=110` / `versionName=0.1.109-global-codex-mode`。
- 2026-06-20：Codex 触发词改为独立拦截。`codex` 不再走普通 `matchesAny` 宽匹配，而是在去掉机器人名后按命令入口单独判断；`@慢一点 codex ...` 会先进入 `CODEX` 路由，不再参与金融/DexScreener 分流；版本升到 `versionCode=109` / `versionName=0.1.108-codex-trigger-intercept`。
- 2026-06-20：修复 Codex 触发被金融分流吞掉的问题。金融关键词包含 `dex`，`codex` 会先命中金融/DexScreener；现将 `codex`、`代码`、`报错`、`bug`、`修复` 的路由优先级前移到金融、新闻、天气等工具分流之前；版本升到 `versionCode=108` / `versionName=0.1.107-codex-route-priority`。
- 2026-06-20：更正 Happy Codex 端点安装包。默认 `happyCodexEndpoint` 固定为 `http://192.168.2.204:8731/v1/codex`，并把旧配置里误写的 `127.0.0.1:8731` / `192.168.2.157:8731` 自动归一到 204；新增 `scripts/push-phone-install-script.sh` 和手机端 `/data/local/tmp/phone-install-vxbot.sh` 安装方式，安装命令只允许在目标手机 shell 内 `su` 后执行；版本升到 `versionCode=107` / `versionName=0.1.106-happy-codex-endpoint-204`。
- 2026-06-20：接入 Happy Codex 桥接。新增 `Happy Codex 桥接接口` 配置，默认 `http://192.168.2.204:8731/v1/codex`；CODEX 路由优先把微信群请求转发到容器内 `happy-codex-bridge`，由桥接服务复用 Happy 授权和 Codex app-server 会话执行，返回结果直接发群，桥不可用时回退普通上游；版本升到 `versionCode=106` / `versionName=0.1.105-happy-codex-bridge`。
- 2026-06-20：增强虚拟币行情和新闻早报。虚拟币查询从金融分支前置，优先 Binance.US/Binance 交易所 ticker，再走 DexScreener 链上/DEX 池，最后才用 CoinGecko；支持合约地址、币安链/BSC 等链偏好，避免虚拟币问题被 Yahoo 固定映射成 BTC/ETH。早安定时广播附带微博热搜、百度热榜和 Google News RSS 早报，群内 `早报`、`晨报`、`今日简报` 可手动触发；版本升到 `versionCode=105` / `versionName=0.1.104-market-news-briefing`。
- 2026-06-20：新增人物画像功能。白名单群消息会按群名、成员名、日期写入本地 `persona_store`；`人物画像`、`昨日总结`、`谁是话痨`、`昨天说了啥` 等指令会把压缩后的成员统计和发言样本交给上游大模型分析，返回话痨排行、性格画像、昨天/今天干了啥说了啥和群聊重点，上游失败时回落本地统计；版本升到 `versionCode=104` / `versionName=0.1.103-persona-profile`。
- 2026-06-14：同步 GitHub 前已通过 `192.168.2.89:22` 真机核对当前运行 APK：`com.vxbot.wechatbot` 进程在线，`versionCode=103` / `versionName=0.1.102-quoted-delay-after-tap`，与本地源码 `app/build.gradle` 对齐；同步前本地差异备份目录：`.backup-20260614-230703-before-github-sync-v103`。
- 2026-06-13：引用图取图动作改为固定时序：点击微信引用灰卡缩略图后立即按 `quotedImageOpenDelayMs` 等待，默认和最小值均为 `800ms`，等待结束后才判断预览页并截图；截图成功后固定等待 `300ms` 再执行 Back 返回会话。日志分别为 `image.reference.quote.after_tap_wait`、`image.reference.preview.capture` 和 `image.reference.preview.after_capture_wait`。
- 2026-06-13：修复引用图请求被后端误取旧图的问题。引用图图生图命中后，上传给图片编辑接口的 multipart 只保留当前引用截图 1 个 `image` 附件，不再同时追加人物参考图、清凉风格图或历史图，避免后端按最后一个/临时文件目录取到旧文件；新增 `image.edit.request` 日志记录 filename、bytes、sha256，方便核对实际上传内容。
- 2026-06-13：修复引用图偶发“已点开但提示没拿到图”的竞态。`quotedImageOpenDelayMs` 只作用在 `tap` 引用缩略图之后，不再放到截图函数内部或点开前面；截图函数只负责确认预览页、截图、失败短重试，避免一边点开一边马上 Back 导致拿旧截图。
- 2026-06-13：修复通知栏偶发甩飞到系统控制面板的问题。打开通知前先收起旧通知/控制面板；OCR 发现只有系统控制面板快捷项、没有通知卡片时，不再继续下滑，而是执行 `collapse -> expand-notifications -> 稳定等待 -> 重新 OCR`，最多重试 3 次。
- 2026-06-13：全屏背景页改为沉浸预览。开启 `全屏背景` 且人物/图片/蒸馏页存在底图时，切换到对应侧标签后右侧内容默认隐藏，左侧标签栏保留；单击右侧背景显示/隐藏右侧内容，长按强制显示右侧内容，上下滑调整右侧内容透明度并保存。豆包三段 `sessionid/sid_guard/uid_tt` 输入框从 UI 删除，只保留 Cookie 文件导入/清除。
- 2026-06-13：按 `ProxyPin_2026-06-13.har` 里的豆包 `alice/user_voice/recommend` 返回结果，把豆包 TTS 音色表扩展为 60 个最新 `style_id`，旧 `taozi/tianmei/shuangkuai/yangguang/chenwen` 配置自动映射到新音色；豆包音色 UI 从 60 项系统 Spinner 改成“当前音色 + 展开/收起 + 每页 8 个”的分页选择，避免下拉框冲出屏幕。
- 2026-06-13：豆包 TTS Cookie 支持浏览器导出的 JSON 数组文件导入。配置页新增“导入 Cookie 文件”按钮，导入后将 JSON 内所有 `name/value` 转成完整 Cookie Header 保存；已用用户提供的浏览器导出 Cookie 转换后跑通原始 `doubao-tts.zip` 客户端，生成 `taozi.aac` 大小 `10639` 字节。
- 2026-06-13：豆包 Cookie 配置按钮改为 `导入` / `清除`，避免按钮文字过长在窄屏上割裂。
- 2026-06-13：人物、图片、蒸馏预览页增加 `全屏背景` 开关；开启后切到对应侧标签且已有底图时，以该底图作为全屏背景显示。上传状态文案已移除，界面只保留预览图和操作按钮。
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
