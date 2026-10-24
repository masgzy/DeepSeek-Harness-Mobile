# 更新日志（Changelog）

本项目的所有显著变更都将记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)；
预发布版本以 `alpha` / `beta` / `rc` 后缀标识。

## [Unreleased]

### 新增

- **容器系统可选**：环境屏「容器系统」卡 + 设置页「版本管理」支持切换
  五个内置发行版 —— Debian 13 (trixie) / Debian 12 (bookworm) / Ubuntu 26.04（默认，
  cdimage 直链优先）/ Ubuntu 24.04 (noble) / Alpine 3.22 (musl)；
- **自定义 Docker 镜像**：内置五个选项之外可填任意公开镜像引用
  （`repo:tag`、`ghcr.io/…` 等），按 Docker Registry v2 协议拉取 ——
  参照 masgzy/proot-distro-i18n 实现移植：引用解析（Docker Hub 短名补
  `library/`）→ WWW-Authenticate 挑战换匿名 token（按 repo 签发）→
  OCI index / manifest-list 解 arm64 子清单 → 逐层 blob 下载（跨源重定向
  丢弃 Authorization）→ SHA-256 按 digest 校验 → whiteout 感知解包；
- **Docker Hub 镜像源回退**：daocloud → 1panel → 1ms → registry-1.docker.io
  顺序自动回退（对齐 proot-distro-i18n mirrors 表，2025-2026 可用性整理）；
- **Node.js 版本自定义**：远端 dist 目录（nodejs.org / npmmirror 双源）选任意
  官方版本，SHA-256 从 SHASUMS256.txt 动态解析（默认版本保持锁定哈希），
  支持手动输入 + 离线候选降级；
- **dsh 版本自定义**（对齐 dphmoblie HarnessVersionsSection 思路）：npm
  packument 全目录（corgi 文档），dist-tags 徽章（latest/next/beta/alpha），
  SemVer 排序；可选「跟随 npm latest」或锁定任意历史版本；
- **Alpine (musl) 容器支持**：bootstrap 改为 POSIX sh 兼容；Alpine 路径在
  容器内切 apk 镜像源后 `apk add bash nodejs npm`（官方 Node glibc tarball
  不兼容 musl），随后以 bash 重入统一安装流程；npm 源选国内时顺带 best-effort
  切换容器内 apt 源（Debian/Ubuntu）。

### 计划中

- SAF 文件桥：免权限导入导出设备文件
- Digest 锁定的运行时增量更新与自动回滚
- API Key Android Keystore 加密存储
- 可选 Shizuku 集成（设备 shell 能力扩展）

## [1.0.0-alpha.4] - 2026-10-24

应用图标与外壳视觉逐像素对齐 dphmoblie/deepseek-harness-android；
dsh 对话改为独立全屏页（参考项目 HarnessActivity 同款进入模式）；
修复首次安装时 proot 工作区绑定警告。可从 alpha.2/alpha.3 覆盖安装。

### 修复

- **proot `can't sanitize binding "…/files/workspace"`**：根因是
  `RuntimeInstaller`（首次安装 bootstrap 阶段）把工作区目录加入 `-b` 绑定前
  没有创建该目录 —— proot 对绑定源做 realpath 解析失败即丢弃绑定，
  容器内 `/root/projects` 在装完引擎后不可用。现于 proot 启动前 `mkdirs()`，
  引擎启动路径（`EngineManager`）原本就有，两处口径一致；
- **APP 重启后附着运行中引擎需干等 60 秒**：webUrl 状态在进程重启后清零，
  轮询循环不读日志。现等待期间每 3 秒从引擎日志尾部补解析，门屏秒进对话页；
- **启动引导日志噪音**：bootstrap 的 npm 安装追加 `--loglevel=error`，
  屏蔽 dsh 传递依赖的 deprecation 警告（如 node-domexception@1.0.0，无害），
  安装日志只保留错误与结果。

### 变更

- **应用图标更换**：整套 mipmap 资产（自适应图标前景/背景/圆形/传统图标，
  五档密度）与吉祥物图（app-mark）来自 dphmoblie/deepseek-harness-android
  （MIT），桌面观感与参考项目一致；
- **dsh 对话改为独立全屏页**：新增 `HarnessActivity`（对齐参考项目
  HarnessActivity 覆盖外壳的模式）—— 门屏「打开对话」→ 启动引擎并等待
  token 就绪 → 全屏承载 dsh Web UI，顶栏/底部导航不再挤压页面，返回键回外壳；
  引擎重启换 token 的 401 自愈逻辑随迁；
- **品牌 Header 重做**：brand-symbol 实底圆角图标块（对角渐变）+ `deepseek`
  + `HARNESS` 徽章，右侧阶段徽章 + 「应用设置」图标键（会话页），形态与
  参考项目 mobile-header 一致；
- **底部导航重做**：去除 M3 药丸指示器，改为参考项目 bottom-navigation 的
  扁平三键（图标 20 + 标签 11），上缘分隔线，选中蓝 / 未选中灰；
- **门屏 launch-panel 对齐**：52dp 实底图标块（--icon-solid-bg）内嵌 38dp
  吉祥物、操作区上缘分隔线、引擎运行中提供「停止」次按钮（文案三态对齐
  conversation-gate）；
- **环境屏 runtime-overview**：蓝软渐变卡片底 + 48dp 实底 runtime-logo
  内嵌 34dp 吉祥物；
- **窗口级主题收尾**：`windowBackground` 浅色 #f3f5f8 / 深色 #0f1317
  （values-night），Compose 首帧前不再白闪；HarnessActivity 使用终端深色
  #0b0f13 专用主题。

## [1.0.0-alpha.3] - 2026-10-24

修复 dsh web 认证失败（Web UI 无法进入），界面整体对齐
dphmoblie/deepseek-harness-android。可从 alpha.2 覆盖安装。

### 修复

- **dsh web 认证失败（401）**：dsh web 每次启动生成随机 token（43 位
  base64url）且只打印在 stdout（`dsh web: http://127.0.0.1:3080/?token=…`），
  不带 token 访问一律得到
  `dsh web authentication required; reopen the URL printed by dsh web.`
  alpha.2 的 WebView 加载的是不带 token 的裸地址，必然被拒。现改为
  引擎进程以管道逐行读取 stdout，实时落盘 engine.log 并解析带 token 的
  完整地址；WebView 只加载该地址，token 未就绪前显示「正在获取访问凭据」；
- **连接时序**：修复「先 connect refused 后认证失败」——token 解析完成
  （而非端口首次应答）才进入 WebView；引擎打印地址与端口监听间的短暂窗口
  由 WebView 延迟自动重试兜底；
- **token 轮换自愈**：引擎被看门狗重启后 token 更换，WebView 收到 401 时
  自动从引擎日志尾部重新解析最新地址并重载（APP 重启后重新附着到
  仍在运行的引擎同样走日志尾部解析）。

### 变更

- **界面整体重做**（逐项对齐 dphmoblie/deepseek-harness-android 外壳）：
  - 主题：深浅双套固定色板照搬参考项目 styles.css（深色底 `#0f1317`/
    卡片 `#171d24`/海洋蓝 `#86abff` 等），不再使用 Material You 动态取色；
  - 外壳：顶栏（品牌 + 全局阶段徽章）+ 底部三项导航（首页 / 环境 / 设置）；
  - 阶段徽章 PhaseBadge：软色底 + 描边 + 呼吸圆点，五色系与参考项目一致；
  - 首页对话门屏（conversation-gate）：eyebrow 标题、launch-panel 启动卡片、
    主按钮/告警条形态逐项对齐；
  - 环境屏（environment-screen）：安装引导与运行时管理一体，下载 → 校验 →
    安装 → 就绪四步指示器、双安装路线、环境详情列表；
  - 设置页：分组卡片 + 图标 + 说明 + 右箭头形态；日志页：终端深色面板 +
    引擎/应用/崩溃三类切换 + 一键复制/清屏。

### 修复（补充）

- 环境屏周期探测改为 IO 线程轮询（3 秒），看门狗拉起/回收引擎后界面自动跟进。

## [1.0.0-alpha.2] - 2026-10-24

修复 alpha.1 在真机上的关键启动缺陷（proot 无法执行）与若干体验问题。
**需要先卸载 alpha.1 再安装本版**（签名密钥切换为固定社区密钥，此后版本可覆盖安装）。

### 修复

- **proot 无法执行（error=13）**：Android 10 起 targetSdk ≥ 29 的应用被
  SELinux W^X 策略禁止 exec 应用数据目录 ELF，alpha.1 的「assets 解压到
  filesDir 再执行」方案在真机必然失败。参照
  dphmoblie/deepseek-harness-android 的成熟实现，改为 **jniLibs 方案**：
  proot 本体与静态 loader 以 `libdsh_proot.so` /
  `libdsh_proot_loader.so` 随 APK 分发，经 `nativeLibraryDir` 执行；
  访客 execve 经 `PROOT_LOADER` 以 mmap 装载，应用数据目录内不再发生
  execve（targetSdk 34 行为与限制详见 docs/architecture.md §2.2）；
- **CI 期二进制供给**：`fetch-proot.sh` 重写为产出 jniLibs 四件套
  （本体 / loader / libtalloc 重命名 / libandroid-shmem），并对 proot
  本体做 DT_NEEDED 同长度补丁（`libtalloc.so.2` → `libtalloc.so`）；
  Termux proot 升级至 5.1.107.100（旧版本已从源池清除）；
- **预装运行时通道健壮性**：清单拉取增加体积上限与字段级校验
  （缺失 SHA-256 时明确拒绝）；错误文案携带完整原因链；
- **引擎状态探测**：修复 HomeScreen 周期探测在主线程执行网络 I/O 被
  静默吞掉的问题（引擎被看门狗拉起后界面此前永不自动恢复）；
- **CI 分支过滤**：修复 push 触发条件（alpha.1 的 CI 实际未在 main 推送时运行）。

### 新增

- 全局崩溃处理器：未捕获异常完整堆栈写入 `logs/crash.log`，
  设置页检测到崩溃记录时提示用户反馈；日志页支持一键复制；
- 安装向导对齐参考项目：阶段状态徽章（未安装/下载中/正在校验/正在安装/
  已就绪/需要处理）、失败后「重试在线安装 / 改用预装运行时」双通道；
- **固定社区签名密钥**：仓库内置 `release.keystore`（alpha 阶段公开），
  预发布版本间可覆盖安装升级；配置 `RELEASE_KEYSTORE_B64` Secret 后自动切换。

### 变更

- UI 阶段文案与状态色对齐 dphmoblie/deepseek-harness-android（phase 色系）；
- 引擎宿主环境对齐参考实现：`PROOT_LOADER` / `LD_LIBRARY_PATH` /
  `PROOT_TMP_DIR` / `TMPDIR`，容器内 `env -i` 增加 USER/LOGNAME/LANG/SHELL；
- 容器绑定补充 `/apex` 与 `/linkerconfig/ld.config.txt`（Android 10+
  系统组件路径，termux-chroot 同款）。

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
