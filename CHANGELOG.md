# 更新日志（Changelog）

本项目的所有显著变更都将记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)；
预发布版本以 `alpha` / `beta` / `rc` 后缀标识。

## [Unreleased]

### 计划中

- SAF 文件桥：免权限导入导出设备文件
- Digest 锁定的运行时增量更新与自动回滚
- API Key Android Keystore 加密存储
- 可选 Shizuku 集成（设备 shell 能力扩展）

## [1.0.0-alpha.1] - 2026-10-24

首个开发者预览版本（项目框架落地）。

### 新增

- **proot 容器引擎**：基于 Termux proot 5.1.107.99（assets 注入 + W^X 处理），
  在应用私有目录构建 Ubuntu 26.04.1 LTS (Resolute Raccoon) rootfs 容器，
  全程免 root、免 Termux；
- **双轨运行时供应**：
  - 官方通道：ubuntu-base 镜像下载（cdimage 官方 + 清华 TUNA 双镜像回退，
    SHA-256 校验）+ 容器内一键引导安装 Node.js v24.22.0 LTS 与
    @deepseek-ai/dsh 0.2.1-rc.2（npm 国内镜像可切换）；
  - 预装通道：CI 构建的开箱即用 bundle（manifest.json + SHA-256 锁定 +
    校验失败自动回滚）；
- **dsh 引擎管理**：前台服务（dataSync）保活 + 5 秒看门狗自愈 +
  进程/端口双通道存活探测（127.0.0.1:3080）；
- **Material You 原生界面**（Kotlin 2.4.10 + Jetpack Compose + Material 3）：
  - 安装向导（双安装路线选择、实时进度与日志）；
  - 运行页（WebView 承载 dsh 官方 Web UI、引擎启停控制）；
  - 日志页（引擎日志 / 应用日志，自动滚动）；
  - 设置页（端口、npm 源、容器卸载）；
- **CI/CD**：
  - `ci.yml`：shellcheck + bash -n + Android 构建/静态检查门禁；
  - `rootfs.yml`：每周自动构建预装运行时镜像并发布至 runtime-nightly 预发布；
  - `release.yml`：tag 驱动发版（单调 versionCode 公式、签名密钥策略、
    规范化 Release Notes 与 SHA-256 校验表）；
- **合规文件**：GPL-3.0 LICENSE、全中文 THIRD_PARTY_NOTICES.md
  （完整许可文本收录 + 原始版权行保留 + Apache NOTICE 覆盖 + GPL 修改记录表）。

### 安全

- 所有下载物强制 SHA-256 流式校验，校验失败自动回退下一镜像；
- rootfs 解包拒绝路径穿越（`../` 与绝对路径条目）；
- proot 等可执行文件落盘后去除写位（华为/EMUI W^X 策略兼容）；
- 网络安全配置仅放行 `127.0.0.1` 明文回环（dsh Web UI），其余强制 HTTPS。

### 已知限制

- 仅支持 arm64-v8a 设备（Android 8.0+）；
- targetSdk 锁定为 34（Android 15+ 对 targetSdk 35 应用禁止执行应用数据目录
  ELF，升级需先设计 exec 迁移方案）；
- alpha 阶段未配置正式签名密钥时，Release APK 使用一次性密钥（无法覆盖升级）。

[Unreleased]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.1...HEAD
[1.0.0-alpha.1]: https://github.com/masgzy/DeepSeek-Harness-Mobile/releases/tag/v1.0.0-alpha.1
