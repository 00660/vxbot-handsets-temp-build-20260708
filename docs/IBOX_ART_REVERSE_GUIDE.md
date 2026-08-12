# iBox Art 官方 APK 重新反编译指南

本文用于官方 iBox Art APK 更新后重新建立静态反编译、运行时 Hook 和热加载 Web 资源证据。不要复用旧版本反编译结果解释新版本行为，也不要把历史包名、接口、字段或参数当作当前事实。

2026-08-12 实测官方应用包名为 `com.box.art`、版本为 `3.0.5`、`versionCode` 为 `30005`。这些值只是一份历史记录；每次分析必须重新确认。

## 1. 证据原则

证据冲突时按以下顺序裁决：

1. 当前设备上的实际运行行为。
2. 本轮捕获的网络请求与响应。
3. 当前服务器实际下发的 HTML、JavaScript、CSS 等资源。
4. 本轮从进程内存导出的 Dex、SO 和 ELF。
5. 本轮从设备提取的 APK 静态反编译结果。
6. 旧版本产物、注释和未执行代码。

先证明一条最窄的完整链路，再扩展到其他功能。一次只改变一个条件，并记录操作时间、页面、输入、请求、响应和页面最终状态。不得根据接口名、旧代码或错误文案猜测业务行为。

## 2. 准备目录和变量

先连接设备，然后为本轮分析创建独立目录。尖括号内的值必须替换为本轮实际值，不能原样执行。

```bash
DEVICE=192.168.2.73:5555
PKG=<动态确认结果>
WORK=/workspace/reverse/ibox-art-<版本>-<日期>
JADX=/workspace/ibox-analysis/tools/jadx-1.5.3/bin/jadx

adb connect "$DEVICE"
adb -s "$DEVICE" get-state
mkdir -p "$WORK"/{original,metadata,static/apktool,static/raw,static/jadx,runtime/logs,runtime/dex-so,web,notes}
```

推荐目录含义：

```text
original/          从设备提取的原始 base/split APK
metadata/          包信息、路径、版本、签名和哈希
static/apktool/    Manifest、资源和 smali
static/raw/        每个 APK 的原始 ZIP 内容
static/jadx/       Jadx 反编译结果
runtime/logs/      本轮 HTTP 和密码学 Hook 日志
runtime/dex-so/    本轮内存 Dex、解密 SO、运行时 ELF
web/               当前服务实际下发的 H5 资源
notes/             操作时间线、接口结论和待验证项
```

## 3. 动态确认目标包名

1. 在设备上打开官方 iBox Art，并让它保持前台。
2. 查看当前前台 Activity，不要直接沿用上次的包名：

```bash
adb -s "$DEVICE" shell dumpsys activity activities | rg 'topResumedActivity|mResumedActivity'
adb -s "$DEVICE" shell dumpsys window windows | rg 'mCurrentFocus|mFocusedApp'
```

3. 从输出中取 `/` 前面的候选包名，写入 `PKG`，再同时核对包列表和安装路径：

```bash
PKG=<当前前台应用的候选包名>
adb -s "$DEVICE" shell pm list packages | rg -F "$PKG"
adb -s "$DEVICE" shell pm path "$PKG"
```

只有当前台 Activity、已安装包和 `pm path` 三者一致时，才继续提取。若 ADB 未连接，只能记录连接失败；不能推断设备、应用或页面状态。

## 4. 提取并封存原始 APK

先记录设备侧元数据和所有 APK 路径：

```bash
adb -s "$DEVICE" shell dumpsys package "$PKG" > "$WORK/metadata/package-dumpsys.txt"
adb -s "$DEVICE" shell pm path "$PKG" | sed 's/^package://' | tr -d '\r' > "$WORK/metadata/apk-paths.txt"
```

逐个提取 `base.apk` 和所有 split APK。不要只保存 base APK，否则可能遗漏 ABI、语言、密度资源或动态功能模块。

```bash
while IFS= read -r remote_apk; do
  adb -s "$DEVICE" pull "$remote_apk" "$WORK/original/$(basename "$remote_apk")"
done < "$WORK/metadata/apk-paths.txt"
```

确认 base APK 存在，再记录 APK 元数据、签名证书和全部文件哈希：

```bash
test -f "$WORK/original/base.apk"
aapt dump badging "$WORK/original/base.apk" > "$WORK/metadata/aapt-badging.txt"

for apk in "$WORK"/original/*.apk; do
  printf '\n===== %s =====\n' "$(basename "$apk")"
  apksigner verify --verbose --print-certs "$apk"
done > "$WORK/metadata/apksigner.txt" 2>&1

sha256sum "$WORK"/original/*.apk > "$WORK/metadata/SHA256SUMS"
```

检查 `aapt-badging.txt` 中的 `package: name`、`versionCode` 和 `versionName`，确认它与本轮目标一致。`original/` 在本轮结束前保持只读，不在其中解包或修改文件。

## 5. 静态反编译

### 5.1 Apktool：Manifest、资源和 smali

每个 APK 单独反编译，避免 split 中的 Manifest、资源或 smali 被遗漏或互相覆盖：

```bash
for apk in "$WORK"/original/*.apk; do
  name=$(basename "$apk" .apk)
  apktool d -f "$apk" -o "$WORK/static/apktool/$name"
done
```

重点检查：

- `AndroidManifest.xml` 中的 Activity、Service、Provider、权限、深链和网络安全配置。
- `res/xml/`、`res/raw/`、`assets/` 中的配置、证书、脚本和内嵌资源。
- `smali*/` 中的网络客户端、WebView、JavaScript Bridge、加密、签名和原生方法入口。

### 5.2 原样展开所有 APK

每个 split APK 单独展开，避免同名文件互相覆盖：

```bash
for apk in "$WORK"/original/*.apk; do
  name=$(basename "$apk" .apk)
  mkdir -p "$WORK/static/raw/$name"
  unzip -q "$apk" -d "$WORK/static/raw/$name"
done
```

### 5.3 Jadx：Dex 反编译

让 Jadx 同时读取 base 和 split APK：

```bash
"$JADX" -d "$WORK/static/jadx" "$WORK"/original/*.apk
```

Jadx 的 Java 输出只是反编译视图，不是源码事实。出现反编译错误、方法缺失或控制流异常时，直接回到 Apktool 的 smali、原始 Dex 字符串和运行时行为：

```bash
find "$WORK/static/apktool" -type f -path '*/smali*/*' | sed -n '1,40p'
find "$WORK/static/raw" -type f -name '*.dex' -print
rg -a -n 'https?://|wss?://|WebView|addJavascriptInterface|evaluateJavascript' "$WORK/static/raw" "$WORK/static/apktool"
```

不要因为 Jadx 没有生成可读 Java 就判定某项逻辑不存在。

## 6. 识别 Flutter 与 AOT 代码

先检查 Flutter 的典型产物：

```bash
find "$WORK/static/raw" -type f \( -name 'libapp.so' -o -name 'libflutter.so' \) -print
find "$WORK/static/raw" -type d -name 'flutter_assets' -print
```

若存在 `libapp.so`、`libflutter.so` 或 `flutter_assets`，按 Flutter 应用处理。先保存基础 ELF 和字符串证据：

```bash
find "$WORK/static/raw" -type f -name 'libapp.so' -exec file {} \; > "$WORK/metadata/libapp-file.txt"
find "$WORK/static/raw" -type f -name 'libapp.so' -exec sha256sum {} \; > "$WORK/metadata/libapp-sha256.txt"
find "$WORK/static/raw" -type f -name 'libapp.so' -exec strings -a {} \; > "$WORK/metadata/libapp-strings.txt"
```

Flutter 的主要 Dart 业务代码通常位于 AOT 快照中，Jadx 无法还原。需要进一步分析时：

1. 先从 APK 元数据、`libflutter.so` 和运行时信息确定 Flutter/Dart 版本及 ABI。
2. 使用与该 Flutter/Dart 版本兼容的 Blutter 环境处理本轮 `libapp.so`。
3. 保存 Blutter 版本、命令、输入哈希、完整日志和输出目录。
4. 如果版本不匹配、快照格式不支持或解析报错，把结果标为“未验证”，转向运行时 Hook 和字符串交叉验证。

旧版 Blutter 输出不能作为新版 APK 的证据，也不能用旧符号表给新版地址命名。

## 7. CodexDeviceTools 运行时分析

CodexDeviceTools 当前包名为 `com.codex.devicetools`，入口 Activity 为 `.DeviceToolsActivity`。打开工具后必须在界面中动态选择本轮 `PKG` 对应应用；不要修改工具源码硬编码目标，也不要复用上一个应用的选择结果。

```bash
adb -s "$DEVICE" shell am start -n com.codex.devicetools/.DeviceToolsActivity
```

在工具面板中完成以下动作：

1. 选择刚刚交叉确认的官方应用。
2. 开启 HTTP、Java Crypto、Conscrypt、BoringSSL 四类实时记录。
3. 回到官方应用，仅执行本轮要验证的一条功能链路。
4. 在关键代码加载并执行后导出内存 Dex、解密 SO 和运行时 ELF。

目标应用的实时日志目录为：

```text
/sdcard/Android/data/<目标包名>/files/dandelion-hot-dumps/
```

当前已核实的日志文件为：

```text
http-network.log
java-crypto.log
conscrypt-crypto.log
boringssl-crypto.log
```

将本轮日志拉到独立目录：

```bash
adb -s "$DEVICE" pull "/sdcard/Android/data/$PKG/files/dandelion-hot-dumps" "$WORK/runtime/logs"
```

CodexDeviceTools 的内存采集临时根目录为 `/data/temp/pine-art-dumps/<目标包名>`，工具导出结果名称包含 `<目标包名>-dex-so-dedup.zip`。优先通过工具界面的导出功能取得归档；导出后将归档拉到 `runtime/dex-so/`，记录哈希并解压到独立子目录：

```bash
adb -s "$DEVICE" shell find /sdcard -type f -name "${PKG}-dex-so-dedup.zip" 2>/dev/null
adb -s "$DEVICE" pull <设备返回的实际导出路径> "$WORK/runtime/dex-so/"
sha256sum "$WORK"/runtime/dex-so/*.zip > "$WORK/runtime/dex-so/SHA256SUMS"
mkdir -p "$WORK/runtime/dex-so/latest"
unzip -q "$WORK/runtime/dex-so/${PKG}-dex-so-dedup.zip" -d "$WORK/runtime/dex-so/latest"
```

尖括号路径必须替换成设备实际返回值。不得编造广播参数、导出路径或 Hook 已启用状态；是否启用应以工具面板和本轮日志新增内容为证据。

## 8. 热加载 WebView/H5 资源

抽奖等页面可能是 WebView 在运行时加载的浏览器资源。此时 APK 内的旧 `assets` 不能代表当前页面，应从本轮 `http-network.log` 定位真实入口 HTML、JavaScript、CSS、接口和重定向链。

```bash
rg -n 'https?://|text/html|javascript|application/json|websocket|wss://' "$WORK/runtime/logs"
```

对每条目标链路记录：

- 首个页面 URL、重定向顺序和最终 URL。
- 请求方法、查询参数、必要 Header 和响应状态。
- HTML 引用的带版本号或哈希的 JS/CSS 地址。
- `fetch`、XHR、WebSocket 的调用顺序与请求字段。
- Cookie、`localStorage`、`sessionStorage` 的依赖关系。
- WebView 的 JavaScript Bridge 名称、方法、入参与返回值。

从本轮日志或已授权登录态保存真实响应体到 `web/`，同时记录来源 URL、捕获时间和 SHA-256。分析压缩或混淆 JS 时，保留原文件，格式化文件另存；不要直接覆盖原始响应。

敏感 Cookie、token、交易密码、账号信息、设备指纹、解密密钥和完整敏感响应不得提交到 Git。笔记中只保留完成链路验证所需的脱敏字段。

## 9. 功能链路记录模板

每项功能建立一个 `notes/<功能名>.md`，至少记录以下信息：

```text
APK SHA-256：
包名 / versionName / versionCode：
设备与时间：
起始页面和账号状态：
用户操作：
请求顺序：
关键请求字段：
关键响应字段和状态码：
平台最终状态：
对应静态入口：
对应运行时 Dex/SO/JS：
已证实结论：
仍待验证：
```

“接口返回成功”不等于完整功能成功。应继续核对平台状态、后续查询结果和 UI 最终状态；反之，客户端显示报错但平台状态已改变时，也必须分别记录“请求结果”和“最终平台状态”。

## 10. 每轮验收与清理

本轮反编译可交付前至少确认：

- 包名由当前前台 Activity、包列表和 `pm path` 交叉确认。
- base/split APK 均已提取，版本、签名和 SHA-256 已保存。
- Apktool、原始 APK 展开和 Jadx 已完成，失败项有原始日志。
- Flutter 应用已检查 AOT 产物，没有拿旧 Blutter 输出代替本轮证据。
- 运行时日志来自正确目标包，且捕获时间覆盖实际操作。
- WebView/H5 使用当前服务下发资源，不以旧 APK assets 代替。
- 每个结论都能回指本轮 APK、日志、响应或运行时导出文件。

每轮结束只保留：原始 APK、元数据与哈希、最新有效反编译结果、关键运行时导出、必要的脱敏日志和结论。删除重复 dump、旧截图、失败且无诊断价值的输出、重复 ZIP 和过期临时文件。确认新版本证据完整前，不删除上一份已验证的可回滚证据。

禁止把 `original/`、`runtime/`、`web/` 中可能含有凭据或版权资源的内容直接提交到当前工程仓库；项目仓库只提交脱敏结论和本指南。
