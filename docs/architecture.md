# 架构设计（Architecture）

> 本文档面向贡献者，说明 DeepSeek Harness Mobile 的整体架构、进程链、
> 目录布局、安全模型与关键技术决策。

## 1. 总体架构

```
Android 系统
└── DeepSeek Harness Mobile（io.github.masgzy.dshm）
    ├── MainActivity（Jetpack Compose + Material 3）
    │   ├── 安装向导（SetupScreen）
    │   ├── 运行页（HomeScreen = WebView → http://127.0.0.1:3080）
    │   ├── 日志页（LogsScreen = engine.log / app.log）
    │   └── 设置页（SettingsScreen）
    ├── EngineService（前台服务，dataSync）
    │   └── 5s 看门狗：EngineProbe 探测失败 → 重启引擎
    └── 引擎子进程（全局单例 Process 持有）
        └── proot（Termux 构建，bionic 动态链接 libtalloc/libandroid-shmem）
            └── /usr/bin/env -i（glibc 纯净环境）
                └── node（/usr/local/bin/node，Node.js 24 LTS）
                    └── dsh web --no-open --port 3080
                        └── @deepseek-ai/dsh（Cordis 插件化 Agent 引擎）
```

## 2. 关键技术决策

### 2.1 为什么是 proot 而不是别的容器方案

| 方案 | root | 与 dsh 兼容性 | 结论 |
| --- | --- | --- | --- |
| proot（用户态 rootfs 虚拟化，ptrace 实现） | 不需要 | glibc 完整用户态，npm 原生模块可编译 | ✅ 采用 |
| chroot | 需要 | — | ❌ 不可行 |
| Termux 前置 | 不需要 | 可行但要求用户先装 Termux | ❌ 体验割裂 |
| KVM/QEMU 虚拟机 | 部分机型需要 | 重量级 | ❌ 不适合移动端 |

proot 通过 ptrace 拦截系统调用实现路径翻译与 uid 伪装（`-0`），
无需任何特权即可让 Ubuntu 用户态完整运行。dsh 与 AI 执行的 bash 命令
全部落在真实 glibc 环境，兼容性与桌面 Linux 一致。

### 2.2 targetSdk 锁定 34 的原因

Android 15 起（targetSdk ≥ 35），系统 SELinux 策略禁止应用执行
`app_data_file` 域的 ELF——即应用私有目录里的 proot / node 二进制。
**只要 targetSdk ≤ 34，该限制不生效**。因此：

- 当前锁定 `targetSdk = 34`；
- 未来升级 targetSdk 35+ 前，需要迁移 exec 方案（例如把可执行文件
  交给 `ProcessBuilder` 之外的方式、借助 `app_process`、或将 ELF 装入
  jniLibs 经 `nativeLibraryDir` 执行——后者要求处理 16KB 页对齐与
  SONAME 等打包细节，是路线图中「targetSdk 35+ 迁移方案」课题）。

### 2.3 proot 资产注入方式：assets（当前） vs jniLibs（备选）

- **当前方案（assets）**：CI 期 `scripts/fetch-proot.sh` 从 Termux 官方源
  下载 `proot / libtalloc / libandroid-shmem` 三个 aarch64 二进制，
  放入 `app/src/main/assets/proot/arm64-v8a/`；运行时解压到
  `filesDir/proot/`，**立即去除写位**（华为/EMUI W^X 策略：可执行文件
  必须不可写），`LD_LIBRARY_PATH` 指向该目录。
  优点：无 SONAME / 16KB 对齐约束，版本升级只需改脚本变量。
- **备选方案（jniLibs）**：参照 dphmoblie 把 ELF 改名为 `lib*.so` 放进
  `jniLibs/arm64-v8a/`，由系统安装器解压到只读的 `nativeLibraryDir`。
  优点：天然满足 W^X；代价：需要处理 SONAME 重定向与 AGP 16KB 对齐校验。

### 2.4 双轨运行时供应

| | 官方通道（轻量） | 预装通道（快速） |
| --- | --- | --- |
| 下载量 | rootfs ≈ 30MB + 容器内 npm ≈ 50MB | bundle ≈ 200MB（一次到位） |
| 联网要求 | 安装时需联网（npm） | 仅需 GitHub 可达 |
| 版本新鲜度 | 始终最新 | 跟随 CI 构建周期（每周） |
| 校验 | rootfs/Node tarball SHA-256 + npm registry | manifest.json 内 SHA-256 锁定 |
| 适合 | 首次使用、网络良好 | 弱网、批量分发、复现环境 |

两条通道最终产物一致：`filesDir/rootfs` 内有 `root/.dshm/installed`
标记（由 `bootstrap.sh` 或预装 bundle 自带）。

### 2.5 引擎进程治理

- **全局单例**：`EngineManager.companion.current` 持有引擎 Process。
  UI（AppViewModel）与 EngineService 是不同实例，必须共享同一进程句柄；
- **存活判定**：`process.isAlive || EngineProbe.check()`（端口应答即存活，
  包括 HTTP 4xx/5xx——进程活着但应用层异常时看门狗不会盲目重启）；
- **看门狗**：EngineService 内 5s `scheduleWithFixedDelay`，任务体全程
  try/catch（防止调度线程被单次异常永久杀死）；服务 START_STICKY，
  进程被系统回收后重启服务即自愈引擎；
- **进程清理**：proot `--kill-on-exit` 保证宿主进程销毁时容器内
  node/dsh 全部退出，不产生孤儿进程。

## 3. 目录布局（宿主侧，应用私有）

```
filesDir/
├── rootfs/                  # Ubuntu 26.04.1 rootfs（容器根）
│   ├── usr/local/bin/node   # Node.js 24 LTS
│   ├── usr/local/lib/node_modules/@deepseek-ai/dsh/
│   ├── root/.dshm/installed # 安装完成标记
│   ├── root/projects -> (bind) 宿主 workspace
│   └── root/.dsh/           # dsh 配置与会话数据（DSH_HOME）
├── proot/                   # proot + libtalloc.so.2 + libandroid-shmem.so（去写位）
├── cache/                   # rootfs.tar.gz / node.tar.gz 下载缓存（SHA-256 校验后复用）
├── workspace/               # 用户工作区（容器内 /root/projects）
├── dshm/bootstrap.sh        # 容器引导脚本（从 assets 注入）
├── etc/resolv.conf          # 容器 DNS（bind 进容器）
└── logs/
    ├── engine.log           # 引擎 stdout/stderr
    └── app.log              # 应用日志（AppLog）
```

用户可见路径：`Android/data/io.github.masgzy.dshm/files/workspace`
（任何文件管理器均可访问，无需存储权限——应用专属目录豁免 scoped storage）。

## 4. 安全模型

| 层面 | 措施 |
| --- | --- |
| 供应链 | 全部下载物 SHA-256 流式校验（常量时间比较）；双镜像回退但校验标准不放松 |
| 解包 | tar 条目路径穿越防护（canonical path 必须落在目标目录内）；符号链接仅允许指向包内相对路径目标 |
| 执行 | proot/node 落盘后去写位（W^X）；`env -i` 纯净容器环境，不泄漏宿主变量 |
| 网络 | networkSecurityConfig 仅放行 127.0.0.1/localhost 明文（dsh Web UI 本机访问），其余全部强制 HTTPS |
| 数据 | rootfs/引擎数据全部在应用私有目录；卸载即清除；工作区在应用专属外部目录，无需存储权限 |
| 假 root | proot `-0` 仅为容器内提供 root 视图（路径/uid 翻译），不涉及任何真实提权 |

## 5. 版本与发版

- **版本号**：语义化版本 + 预发布后缀（`1.0.0-alpha.1`）；
- **versionCode 公式**（单调递增）：
  `M*10_000_000 + m*100_000 + p*1_000 + 后缀偏移（alpha.N→+N，beta.N→+100+N，rc.N→+200+N，正式→+900）`；
- **发版**：推送 `v*` tag → `release.yml` 构建 APK（arm64-v8a）与预装运行时
  bundle，生成含 SHA-256 校验表的规范 Release Notes；
- **运行时镜像**：`rootfs.yml` 每周构建发布至 `runtime-nightly` 预发布，
  manifest.json 供 App 预装通道消费。

## 6. 参考与致谢

- [dphmoblie/deepseek-harness-android](https://github.com/dphmoblie/deepseek-harness-android)：
  运行时清单/自检/合规声明的成熟范本；
- [deepcode-lab/deepseek-harness-mobile](https://github.com/deepseek-harness-mobile)：
  proot assets 方案、EngineService 看门狗与 CI 签名流程参考；
- [Termux proot-distro](https://github.com/termux/termux-packages)：
  proot 启动参数（`--kill-on-exit -0 -r -b` 绑定集）来源。
