# 竞品设计参考（DSHA / dsh-Go）

> 面向贡献者的设计对照文档。梳理两个成熟 dsh 移动壳项目的工程模式，
> 说明本项目已吸收、暂不吸收的部分及理由，避免后续迭代重新踩坑。

## 1. 参照对象

| | DSH-APP/DSHA | ccch1mneyyy/dsh-Go |
| --- | --- | --- |
| 定位 | dsh 安卓壳（最成熟） | dsh 安卓壳（功能最全） |
| 技术栈 | Java 原生启动器 + WebView | Flutter 原生 UI + WebView 桥 |
| 容器 rootfs | Ubuntu 24.04 (glibc) | Ubuntu 24.04 (glibc) |
| 包体 | ~278MB | ~300MB 级 |
| 许可 | MIT | AGPL-3.0 |
| 启停模型 | 原生进程管理 + 启动行解析 | proot + boot-cache 预载加速 |
| 补丁管理 | `runtime-patches.json` + `applyPatchChain()` | `patches/pins.json` + `patch-dsh` |

两家都用 **Ubuntu 24.04 glibc rootfs**（dsh 是 glibc Linux 程序；musl 下
原生模块编译链脆弱）。本项目保留 Alpine + 自定义镜像设计（用户决策），
musl 的债用引导期补丁与 npm 回退梯子偿还（见 `bootstrap.sh` 的 koffi
三梯：槽位补丁 → npm --ignore-scripts 回退 → 源码编译），glibc 顽固问题
走容器系统的 Debian/Ubuntu 选项做逃生舱。

## 2. DSHA：启动契约（分层不可破坏）

DSHA 的启动链路是严格分层、单向依赖的：

```
ConfigStore（配置读写）
  → StartupPipeline（先确认旧 guest 退出）
    → RuntimeLauncher（统一 argv + binds + env，固定启动命令）
      → 受管 dsh 包装 → Node → dsh Web
        → 解析本轮官方启动行 → WebOutputSession / WebProcessManager
```

关键不变式（本项目已对照落实或标记差异）：

1. **端口策略**：DSHA 用 `--port 0` 让 OS 分配，从本轮启动行解析实际端口，
   根治 EADDRINUSE 秒退。本项目差异：保留**固定默认端口 3080 + 设置页可改**，
   端口占用走三级处理（附着有效 token 的孤儿引擎 → 清理本 App 特征孤儿 →
   外部占用明确报错，见 `EngineManager.startEngine`）。理由：固定端口让
   用户在 Termux 手动排障时地址可预期；`--port 0` 的解析收益已被三级处理
   覆盖，而「端口被谁占着」的排障成本反而更高。
2. **只认本轮完整输出的鉴权行**：`dsh web: http://127.0.0.1:<port>/?token=…`
   强格式解析 + 活性验证（`EngineProbe.checkUrlDetailed` 把 401/不可达拆开），
   绝不把日志尾部旧 token 直接喂给 WebView。DSHA 的教训：401 是「引擎活着
   token 死了」，恢复手段是重启引擎，干等永远 401。
3. **慢启动不强杀**：proot 冷初始化实测 42~65 秒、温启动 12 秒+。DSHA 明确
   「等待鉴权不设强杀时限、超时永不触发重试」。本项目对齐：凭据等待只在
   引擎日志停滞（mtime > 20s）且进程死亡时提前收场，慢启动只延长等待。
4. **停止语义**：DSHA 靠「启动时写 pid 文件 + 哨兵文件 + 出生身份核验」，
   **绝不按端口反查**（`/proc/net/tcp` 非 root 是静默空表，按端口反查会
   误杀无辜进程）。本项目等价实现：宿主侧 Process 句柄 + /proc argv 特征
   扫描（argv 同时含本 rootfs 路径 + `dsh` + `web` 三特征才算引擎进程），
   终端/安装脚本的 proot 不会被误杀——杀容器启动器等于连环境带 App 一起带走。
5. **RuntimeTrial（未吸收，待办）**：换镜像/升 dsh 前先在隔离目录真实启动
   一次，验证通过再切换提交，失败自动回切。对自定义镜像场景价值极高
   （用户填错镜像引用时，不至于把能跑的旧环境搞坏）。

## 3. dsh-Go：构建产物工程化 + 启动加速

1. **boot-cache.mjs（已移植）**：dsh shim 经 `NODE_OPTIONS=--import` 预载
   一个 fs 补丁模块，对只读目录树（dsh 安装树、Node 运行时树）缓存 lstat/
   package.json 读取——proot 下每次路径查找陷入 ptrace，模块解析期是热路径。
   自动撤除条件：stdout 出现 `?token=` 或满 120 秒。本项目移植版见
   `assets/scripts/boot-cache.mjs`（`createRequire` 拿 CJS builtin 对象，
   ESM 命名导出/默认导出/CJS 三条导入路径全部吃到补丁，node 24 实测；
   `disabled` 旗标兜底 graceful-fs 持有的旧引用）。
2. **产物版本化**：`build-runtime.sh` 产出带指纹的版本串
   `${RUNTIME_REVISION}-dsh${DSH}-node${NODE}-ubuntu${UBU}-${inputs_sha}` +
   `manifest.json` + `build-report.json`——任何一份运行时产物都能回答
   「谁、何时、用什么输入构建」。本项目暂无预装 bundle 产物线（更新管理器
   已预留 `UpdateManager.applyRuntimeBundle` 入口），补齐时对齐此格式。
3. **补丁注册表**：`patches/pins.json` 按 dsh 版本钉住补丁清单 + `patch-dsh`
   统一执行器，可摘可续。本项目现状：koffi musl 槽位补丁写死在 bootstrap.sh
   （版本无关，按平台包结构探测）；补丁数量增长后再 JSON 化。
4. **插件桥**：dsh-Go 的 bridge 是 cordis 补丁注入的 dsh 插件（原生插件协议
   v1/v1.2，结构化通道），Flutter UI 因此能拿到结构化事件而非 DOM 注入。
   本项目走 WebView 直开 dsh web，dsh 官方 UI 即最终 UI；如未来做原生聊天
   外壳，优先评估 dsh 插件协议而非截屏 DOM。

## 4. 决策对照总表

| 能力 | DSHA | dsh-Go | 本项目 | 状态 |
| --- | --- | --- | --- | --- |
| proot 打包 | jniLibs/nativeLibraryDir | 同左 | 同左（W^X 根治） | ✅ 同源 |
| 端口策略 | `--port 0` + 启动行解析 | 固定端口 | 固定 3080 + 三级占用处理 | 差异（有意） |
| 鉴权行解析 | 本轮完整输出强校验 | 同左 | 强格式 + 活性验证 + 401 自愈 | ✅ 已吸收 |
| 慢启动 | 永不强杀 | — | 日志停滞 + 进程死亡才收场 | ✅ 已吸收 |
| 停止语义 | pid+哨兵+身份核验 | — | 句柄 + /proc argv 三特征 | ✅ 等价 |
| 启动加速 | — | boot-cache.mjs | boot-cache.mjs（已移植） | ✅ 已移植 |
| 补丁管理 | runtime-patches.json | pins.json | bootstrap 内联（梯子式） | ⏳ 待 JSON 化 |
| 换镜像试运行 | RuntimeTrial | — | — | ⏳ 待办 |
| 容器 rootfs | Ubuntu 24.04 | Ubuntu 24.04 | Alpine 默认 + 四系可选 + 自定义镜像 | 差异（核心设计） |
| 安装方式 | 内置固定线 | 内置固定线 | pnpm 优先 + npm 回退 + 大陆镜像全线 | 差异（核心设计） |

## 5. 后续候选（按价值排序）

1. **RuntimeTrial**：换容器系统/升 dsh 前在隔离目录试启动，验证再转正。
2. **补丁注册表 JSON 化**：koffi 补丁之外新增补丁时先建注册表，避免
   bootstrap.sh 无限膨胀。
3. **运行时产物 manifest 化**：为未来预装 bundle/增量更新铺路。
4. **AGENTS.md 契约文档**：把启动契约、停止不变式、已知 trap 固化成
   贡献者必读（本仓库已有 architecture.md，可扩一节 invariants）。
