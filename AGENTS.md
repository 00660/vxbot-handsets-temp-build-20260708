# vxbot-handsets 项目硬约束

## APK 构建与安装

- 构建 APK 时优先使用项目已有 GitHub Actions 远端构建流程，不要默认在本机跑 Gradle/Android SDK。
- 目标手机固定按用户指定的 `192.168.2.89:5555` 操作；除非用户明确给出新设备地址，不要扫描、连接或操作其它 ADB IP。
- 安装 APK 的硬约束：先用 `scripts/push-phone-install-script.sh <apk>` 从容器端把 APK 和手机端安装脚本推到手机；随后必须先进入交互式手机 shell 环境，在手机 shell 内输入 `su` 获取 root，并用 `id` 确认 `uid=0` 后，才允许执行手机端安装脚本。

```bash
./scripts/push-phone-install-script.sh <apk>
adb -s 192.168.2.89:5555 shell
su
id
sh /data/local/tmp/phone-install-vxbot.sh
```

- 不要把外部一行式 `adb -s 192.168.2.89:5555 shell pm install ...` 当作本项目的安装方式；安装命令必须在已经进入并通过 `su` 获得 root 的手机 shell 内执行。
- 如果 `su` 返回 `Permission denied`、提示符仍是 `$`，或 `id` 仍显示 `uid=2000(shell)`，说明没有拿到 root；此时必须停止安装，不要继续执行 `pm install` 试错。
- 2026-06-21 已验证：本机已通过 Magisk 模块 SSH 给 `192.168.2.89` 配置 root 登录，私钥保存在本地忽略目录 `artifacts/phone_ssh_ed25519`，手机 root 授权文件为 `/data/ssh/root/.ssh/authorized_keys`。如果 ADB shell 内 `su` 被拒绝，优先用该 SSH root 通道修复 Magisk policy，而不是重复尝试裸 `su`。
- 2026-06-21 已验证的 ADB shell root 修复方式：用 SSH root 备份 `/data/adb/magisk.db` 后执行 `/data/adb/magisk/magisk --sqlite "replace into policies (uid,policy,until,logging,notification) values (2000,2,0,1,1)"`。修复后 `adb -s 192.168.2.89:5555 shell 'su -c id'` 应返回 `uid=0(root)`。
- 2026-06-20 已验证：未通过 `su` 获取 root 时，在交互式手机 shell 内执行 `pm install -r -d /data/local/tmp/hs-wechatbot-latest.apk` 会报 `user -1` / `INTERACT_ACROSS_USERS_FULL`；执行 `pm install --user 0 -r -d /data/local/tmp/hs-wechatbot-latest.apk` 会报 `Failure [null]`。这两个命令只能作为失败证据，不能写成成功安装路径。
- 已知事实：`versionCode=105` 完成了远端构建和 APK 发布，但当前保留日志没有 105 安装成功记录；不要把“准备安装”“构建成功”“下载 APK”改写成“已安装成功”。
- 安装前必须先查会话记录、shell history、项目交接、设备端 hs/root/Shizuku 安装通道和已有脚本。查不到成功安装命令时，停止并明确说明“成功安装路径记录缺失”，不要继续试错或编造。
- 不要把普通 `adb install`、外部一行式 `adb shell pm install ...`、`pm uninstall` 当作本设备的可靠安装路径。这台 Android 14 设备会出现 `user -1/-2`、`INTERACT_ACROSS_USERS`、`Failure [null]`、PackageInstaller NPE 等问题。
- 如果一次普通 ADB 安装出现上述多用户/权限/`Failure [null]` 问题，立即停止这条路径，回到已知成功安装方式；不要继续叠加 `--user`、`-r`、`-d`、分段安装、卸载重装等试错。
- 不要为了解决安装问题卸载 `com.vxbot.wechatbot`，除非用户明确要求；卸载会破坏现有配置、授权和运行状态。
- 如果用户明确说明 APK 已经安装完成，不再重复安装，只执行版本校验，例如：

```bash
adb -s 192.168.2.89:5555 shell pm list packages --user 0 --show-versioncode | rg 'com\.vxbot\.wechatbot'
```

## Happy / Codex

- Happy/Codex 授权属于独立链路。除非用户明确要求重新授权，不要执行 `happy auth login --force`、`happy auth logout` 或清理 `/root/.happy` 凭据。
- 机器人接入 Happy Codex 时只修改 `happyCodexEndpoint` 或桥服务配置，不要把普通聊天上游 `chatEndpoint` 当成同一件事处理。
- `happy-codex-bridge` 默认应直接驱动本容器内 `codex app-server`，不要把缺 `/root/.happy/access.key` 当成机器人 Codex 不可用的必要条件；只有显式设置 `HAPPY_CODEX_BRIDGE_MODE=happy-api` 时才走 Happy API 授权文件。
- APK 侧 `CODEX` 路由不能在 Happy/Codex 桥接失败时回退普通聊天上游；失败应记录 `codex.happy.fail` 并终止本次回复，避免把 Codex 请求伪装成普通聊天回答。
- Codex 待命模式不能做任意群、任意人全局触发；只能在 `allowedSessions` 白名单群内，由 `followUpSenderWhitelist` 续聊控制人白名单里的成员发送 `进入codex模式` / `打开codex模式` 开启，并且模式只作用于该群和该授权人。
- Happy/Codex 桥接所在机器按用户明确指定的 `192.168.2.204` 处理；`happyCodexEndpoint` 应为 `http://192.168.2.204:8731/v1/codex`。
- `8731` 是容器内 `happy-codex-bridge` 默认端口；手机不能直接访问容器内 `127.0.0.1` 或 `172.17.0.3`，必须先在 `192.168.2.204` 宿主机做端口映射/反代，再让 89 手机访问 `http://192.168.2.204:8731/health` 验证。
- `192.168.2.157:8317` 是另一台普通聊天上游，不属于 Happy/Codex 桥接所在机器；不要把 `8317` 或 `192.168.2.157:8731` 用作 Happy/Codex 验证地址。
