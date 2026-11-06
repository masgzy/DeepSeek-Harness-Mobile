# 更新日志（Changelog）

本项目的所有显著变更都将记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)；
预发布版本以 `alpha` / `beta` / `rc` 后缀标识。

## [1.0.0-alpha.8] - 2026-11-06

### 修复

- **Alpine 3.23 安装失败（P0，apk-tools v3 × proot 不兼容）**：Alpine 3.23 起
  apk 升级到 v3（libapk 3.0.8），其原子写库用 O_TMPFILE + linkat("/proc/self/fd/N")
  提交，proot 路径翻译下必败 EACCES —— 报 "System state may be inconsistent:
  failed to write database: Permission denied"，所有包已解完却在最后一步写库
  失败（3.23.7 真机实测，沙箱内 shim 模拟 proot 缺陷后逐字复现）；apk v3 用
  memfd+execve("/proc/self/fd") 执行 trigger 同类被拒（termux/proot-distro#595、
  alpinelinux/alpine-make-rootfs#28）。修复：新增 `scripts/apk-proot-compat.c`
  （~3.5KB musl LD_PRELOAD shim）——拦 access("/proc/self/fd")→ENOENT 令 apk3
  走「命名临时文件 + renameat」降级路径、拦 memfd_create→ENOSYS 令 trigger 走
  文件路径执行；bootstrap 检测 apk-tools 3 后注入（3.22/apk2 不受影响），并以
  `/usr/local/bin/apk` wrapper 持久化（编译工具链 apk、镜像验证 apk update、
  终端手动 apk 全部自动带 shim）。
- **proroot 引擎 SIGSEGV 自动回退（glibc 2.43）**：proroot 的 ldso 补丁注册表
  不认识 glibc 2.43（Ubuntu 26.04）→ "patches skipped" 后 SIGSEGV exit 139
  （真机实测）。修复：proroot 启动失败（快速退出/凭据未就绪）时本次会话自动
  回退 proot 重启一轮并日志说明，不回写用户持久化选择（重进 App 恢复原选）；
  设置页 proroot 说明明示该不兼容。
- **UI 内容被上下两栏遮挡**：miuix Scaffold 的内容从 (0,0) 全屏铺放、顶/底栏
  悬浮其上，靠 contentPadding 让调用方避让 —— 迁移时忽略了该参数，内容头部/
  尾部被两栏压住（真机反馈「上下的两个栏导致显示不全」）。修复：内容区统一
  应用 contentPadding（terminal 全屏时两栏隐藏、padding 退化为 0，不受影响）。

## [1.0.0-alpha.7] - 2026-11-06

### 新增

- **proroot 第二容器运行时**（设计对齐 DSHA）：proot 之外新增 coderredlab/proroot
  v1.2.8（LD_PRELOAD 进程内路径翻译、零 ptrace）——引擎启动与文件 I/O 明显更快；
  五件套 + DSHA 双兼容 shim（sysconf 代理修 Android 13+ sigstksz 崩溃、入口适配器）
  全部 SHA-256 字节锁定，由 `scripts/fetch-proroot.sh` 构建期自动注入（失败不阻断）；
  设置 → 运行环境可切换，缺件如实提示并回退 proot；proroot 无 kill-on-exit，
  停止走 pid 文件精确杀（见下）。
- **miuix 全量界面迁移**：外壳全部界面（首页/环境/插件/设置/日志/模型/终端顶栏）
  从手绘 styles.css 复刻迁移到 miuix（top.yukonga.miuix.kmp v0.9.4，HyperOS 设计
  语言）——Button/Card/TabRow/TextField/OverlayDialog/NavigationBar/Surface 全部
  miuix 组件，品牌蓝与语义扩展色保留；dsh web（HarnessActivity）与终端渲染区
  不在迁移范围。
- **插件支持 GitHub 仓库安装**：安装框支持 npm 包名之外的
  `git+https://github.com/user/repo`（可 #tag/#commit）；bootstrap 两分支自动
  安装 git（Alpine apk / GLIBC apt，best-effort）。
- **插件操作日志一键复制**：「操作输出」卡片标题行新增「复制」。

### 优化

- **引擎停止提速（~10s → ~1s，最坏 ~3s）**：启动命令改为
  `sh -c 'echo $$ > /tmp/dshm-engine.pid; exec dsh web …'`（dsh-Go 同款 pid 文件），
  停止时先对 guest node 精确 SIGTERM（proot 忽略 SIGTERM/SIGINT/SIGHUP，旧实现
  TERM 启动器只能白等 5s 超时），1s 未退 SIGKILL guest，再 1s 未退才 SIGKILL
  启动器；孤儿扫描等待窗口 3s → 1.5s；PID 复用防护（cmdline + uid 双验证）。
- **rootfs 下载提速**：分段并行阈值 4MB → 1MB —— alpine-minirootfs（~3.9MB）
  旧值下永远走单流慢速路径，是「下载十来秒」的主因；现 4MB 文件也能 4 段并行。

### 变更

- **版本升级（2026-11 核查）**：dsh 0.2.1-rc.2 → **0.2.2-rc.1**（npm latest）；
  Node v24.22.0 → **v24.22.2**（v24 LTS Krypton 线）；Alpine rootfs 3.22.7 →
  **3.23.7**（v3.22 仓库 nodejs 停在 22.23.4，v3.23 起为 24.22.x，与 Node 24
  线对齐；新 SHA-256 经 aliyun/TUNA 双源实测一致）。

### 修复

- **未安装回落环境屏（alpha.7 候选构建回归，P0）**：miuix 迁移时 AppRoot 丢失
  「未安装 → 环境屏」首装引导分支，未安装用户被直接放进主页（对话门屏），
  找不到任何安装入口 —— 恢复安装态分流：未安装时内容区渲染环境屏（壳顶栏
  保留品牌与阶段徽章，底部导航自动隐藏），装完 installedUi→true 自动流转
  正式界面；引导期隐藏顶栏设置键（settings 路由内容在未安装时不渲染），
  并去掉环境屏自带 statusBarsPadding（壳顶栏已处理 inset，避免双份顶部留白）。
- **终端 CTRL 键显示不全**：11 键行窄屏下每键 ~30dp，15sp 的 "CTRL" 被默认
  裁切成 "CTR" —— 改为文本自适应缩小（TextAutoSize，9-15sp），四字母完整可见。
- **插件页报错位置**：操作结果提示从页面顶部移到「安装插件」框下方（与触发
  操作的输入框相邻，不再来回找）。
- **首页状态诚实化**：环境已安装但引擎未运行时，顶栏徽章显示中性「环境就绪」
  （静态灰点），不再显示绿色呼吸「已就绪」；门屏说明改为「打开对话时才会
  启动本机服务（当前未运行）」——修复关闭应用再进入时误以为服务已在后台
  运行的问题（首页/环境屏两处）。

### 新增

- **boot-cache 启动加速预载**（设计移植自 dsh-Go）：引擎启动时经
  `NODE_OPTIONS=--import` 预载 fs 缓存模块，对安装后只读的 dsh/Node 目录树
  缓存 stat/lstat/exists/readFile（proot 下每次路径查找陷入 ptrace，模块
  解析期是热路径）；stdout 出现 `?token=` 或 120s 后自动撤除，不影响运行期
  I/O。文件由 bootstrap 落位到容器 `/root/.dshm/boot-cache.mjs`，老安装
  自动走原路径。
- **本地构建自动注入 proot**：`app/build.gradle.kts` 新增 `fetchProot` 任务
  挂到全部 `pre*Build`——jniLibs 缺失时自动执行 `scripts/fetch-proot.sh`，
  治「本地打的 debug APK 不带 proot、装上容器起不来」；CI 显式注入步骤
  不受影响，`PROOT_SKIP_FETCH=1` 可跳过。
- **竞品设计参考文档** `docs/design-reference.md`：DSHA / dsh-Go 的启动
  契约、停止语义、boot-cache、补丁注册表拆解与本项目决策对照表。
- **RuntimeTrial 先试后切**（设计移植自 DSHA）：三档隔离试运行验证 ——
  ① rootfs 安装：解包产物在隔离 staging 里真实启动一次（proot + busybox
  sh + os-release），验证通过才改名提交，失败自动丢弃并保留原容器
  （拦住「SHA-256 过了但访客链路残废」的坏 rootfs）；② 预装 bundle
  升级：替换前在 staging 里验证 node/dsh 可执行；③ 镜像源写入：
  容器内真实拉一次索引（apk update / apt-get update），验证未通过自动
  回滚到写入前备份（mirror-apply.sh 新增 .dshm-bak 备份与
  DSHM_MIRROR_REVERT 回滚模式）——真机实况多个国内镜像 v3.22 路径
  404/403，首字节测速拦不住。
- **日志彩色化 + 等级可调**：日志页四级配色（错误红 / 警告琥珀 / 成功绿 /
  信息灰）+ 「级别」过滤（全部 / 警告 / 错误，三页通用）+ 设置页
  「日志等级」（详细 / 标准 / 精简）控制录制阈值；应用日志结构化定级
  （D/I/W/E），引擎原始 stdout 按关键词定级，ANSI 色码渲染前剥离；
  轮询期高频「未找到 token」等噪音降为 DEBUG。
- **本地构建镜像兑底**：settings.gradle.kts 仓库顺序分叉 —— CI 保持官方
  源优先；本地构建（无 CI 变量）阿里云镜像优先，根治 Maven Central
  限流（HTTP 429）导致 Gradle 标记仓库 disabled、构建随机失败。

### 计划中

- SAF 文件桥：免权限导入导出设备文件
- Digest 锁定的运行时增量更新与自动回滚
- API Key Android Keystore 加密存储
- 可选 Shizuku 集成（设备 shell 能力扩展）

## [1.0.0-alpha.6] - 2026-10-31

自 alpha.5 以来共 16 个提交：pnpm 安装链路打通与加固、轻量容器终端、
引擎运行时可靠性（凭据自愈 / 失败收场）与多项体验修复。可从 alpha.5 覆盖安装。

### 新增

- **dsh 改 pnpm 优先安装**：容器内引导支持 npm / pnpm / 离线包三种安装方式
  （pnpm 优先，大陆网络下更稳）；Alpine 补 libstdc++ 根治 koffi 段错误；
  安装完成后自动镜像测速 + 预启动引擎，装完即达「对话已就绪」；
- **应用内模型配置与插件管理**（对齐 dphmoblie 能力）：模型参数与插件
  安装/启停可在 App 内管理；
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
  切换容器内 apt 源（Debian/Ubuntu）；
- **轻量容器终端**：应用内直接进入容器 shell，支持 Termux 风格 extra-keys
  快捷键条（CTRL/ALT/FN 点按粘滞、长按锁定、按住即生效），终端整屏纯黑、
  输出实时刷新、点击空白处唤起输入法、快捷键条随软键盘上浮；
- **直链优先安装 + 并行下载 + 镜像测速**：rootfs 下载优先直链源、多镜像
  测速择优、并行分块，下载进度与失败原因详报；
- **引擎凭据自愈**：web 凭据失效或引擎无响应时自动重启引擎换取新 token
  （根治「正在获取访问凭据」无限死等）；启动 60s 无凭据自动重启引擎再等 45s；
  等待弹窗 8s 轮询无果自动恢复；
- **状态诚实化**：首页仅在 web 凭据就绪时显示「对话已就绪」，运行中无凭据
  显示「引擎运行中·等待凭据」，未运行显示「对话未运行」，不再虚报状态；
- **发版流水线**：Release Notes 内嵌提交级完整更新日志（上一版本 → 本版本
  全部提交）与非空 SHA-256 校验块；GitHub Actions 全线升级 Node 24 运行时
  （checkout@v7 / setup-java@v6 / setup-gradle@v6 / upload-artifact@v7 /
  download-artifact@v8 / action-gh-release@v3），消除 Node 20 deprecation 告警。

### 修复

- **koffi musl 槽位补丁**：安装 koffi 时按容器 libc（musl/glibc）改写预编译
  binding 槽位，根治真机 Segmentation fault，并做三层回退加固（第四轮）；
- **pnpm 安装链路**：pnpm 12 registry 不生效改走 `~/.npmrc` + `--registry`
  旗标（根治 koffi 预编译产物拉取失败）；`pnpm add -g` 前预置
  `$PNPM_HOME/bin` 入 PATH（修复 ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH）；
  dsh 改 hoisted 平铺布局 + koffi 加载验证由 bootstrap 执行（第三轮真机根因）；
  沙箱 v12/v11 双链路全流程实测修复两处缺陷；
- **引擎启动即退按失败收场**：dsh-watch 记录进程退出码与存活时长，启动 90s
  内退出即转存 engine.log 尾部 12 行并在门屏明确报错，不再无限等待；
- **终端输出实时刷新**：终端会话与引擎会话共享 client，命令输出即时上屏
  （此前命令已执行但需退出重开终端才能看到输出）；
- **停止引擎 ANR 修复**与旧 token 验证修复；
- **直链包误回退与 bin/sh 缺失两大根因**修复；
- **fetch-proot 增加 TUNA 镜像回退**：termux 官方池异常导致 CI 注入资产
  步骤失败的兜底；
- **DockerRegistryClient 4 处编译错误**修复。

## [1.0.0-alpha.5] - 2026-10-24

启动图标更换与 UI 基础组件库对齐参考项目。可从 alpha.4 覆盖安装。

### 变更

- **启动图标改为用户指定女仆立绘**：自适应图标前景/背景、圆形与传统图标
  等整套 mipmap 资产更新；
- **UI 组件库逐项复刻 dphmoblie styles.css**：按钮 / 卡片 / 输入框等基础
  组件形态与参考项目保持一致。

### 修复

- **CI 触发器截断**：push 触发 branches 配置损坏（`ain]`）导致推 main
  不再触发 CI，已恢复 `[ main ]`。

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

## [1.0.0-alpha.1] - 2026-10-23

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

[Unreleased]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.8...HEAD
[1.0.0-alpha.8]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.7...v1.0.0-alpha.8
[1.0.0-alpha.7]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.6...v1.0.0-alpha.7
[1.0.0-alpha.6]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.5...v1.0.0-alpha.6
[1.0.0-alpha.5]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.4...v1.0.0-alpha.5
[1.0.0-alpha.4]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.3...v1.0.0-alpha.4
[1.0.0-alpha.3]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.2...v1.0.0-alpha.3
[1.0.0-alpha.2]: https://github.com/masgzy/DeepSeek-Harness-Mobile/compare/v1.0.0-alpha.1...v1.0.0-alpha.2
[1.0.0-alpha.1]: https://github.com/masgzy/DeepSeek-Harness-Mobile/releases/tag/v1.0.0-alpha.1
