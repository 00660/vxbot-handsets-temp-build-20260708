# iBox Native Android

这是独立运行的 iBox 原生 Android 客户端。面板自身没有 WebView、没有 5000 面板地址，也不会转发到容器；主导航、账号、合成、行情、量化、交易、抽奖、首发和设置均使用 Android 原生控件绘制。短信与任务验证码保留官方 GeeTest Android SDK，其内部验证页面由服务商 SDK 提供。

当前版本包含：

- 短信登录流程与官方 Geetest Android SDK 验证
- 账号资产读取、刷新、切换和移除
- 合成活动、立即合成、定时任务和取消
- 行情搜索、阈值监控、停止监控
- 量化策略配置、启停、删除、记录、人机验证和支付入口
- 交易/捡漏任务创建、启停、删除、支付入口和人机验证
- 首发立即/定时任务、取消、钱包支付入口和人机验证
- Bark 配置和测试
- 可选 Android 前台服务，每秒执行本机行情监控、任务调度和状态通知

账号、策略、任务和 Bark 配置保存在 APK 的应用私有存储中。业务请求直接使用 iBox 官方 HTTPS 服务，设备不需要连接容器或本地面板服务。

远端构建使用 GitHub Actions，运行环境为 JDK 21、Gradle 8.9、Android Gradle Plugin 8.7.3、compileSdk 35。构建产物为 `app/build/outputs/apk/debug/app-debug.apk`。
