<div align="center">

# DeepSeek Harness Mobile

**免 root 在 Android 上原生运行 [DeepSeek Harness (dsh)](https://github.com/deepseek-ai/deepseek-harness) 的容器化方案**

*proot 容器 · 完整 Linux 用户态 · Material You 原生壳*

[![最新版本](https://img.shields.io/github/v/release/masgzy/DeepSeek-Harness-Mobile?include_prereleases&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&logo=github)](https://github.com/masgzy/DeepSeek-Harness-Mobile/releases)
[![总下载量](https://img.shields.io/github/downloads/masgzy/DeepSeek-Harness-Mobile/total?label=%E6%80%BB%E4%B8%8B%E8%BD%BD%E9%87%8F&logo=github)](https://github.com/masgzy/DeepSeek-Harness-Mobile/releases)
[![Star](https://img.shields.io/github/stars/masgzy/DeepSeek-Harness-Mobile?logo=github&label=Stars)](https://github.com/masgzy/DeepSeek-Harness-Mobile/stargazers)
[![最近提交](https://img.shields.io/github/last-commit/masgzy/DeepSeek-Harness-Mobile/main?label=%E6%9C%80%E8%BF%91%E6%8F%90%E4%BA%A4&logo=github&display_name=commit)](https://github.com/masgzy/DeepSeek-Harness-Mobile/commits/main)
[![许可证](https://img.shields.io/github/license/masgzy/DeepSeek-Harness-Mobile?label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](./LICENSE)

[![CI](https://img.shields.io/github/actions/workflow/status/masgzy/DeepSeek-Harness-Mobile/ci.yml?branch=main&label=CI&logo=githubactions)](https://github.com/masgzy/DeepSeek-Harness-Mobile/actions/workflows/ci.yml)
[![运行时镜像](https://img.shields.io/github/actions/workflow/status/masgzy/DeepSeek-Harness-Mobile/rootfs.yml?label=%E8%BF%90%E8%A1%8C%E6%97%B6%E9%95%9C%E5%83%8F&logo=githubactions)](https://github.com/masgzy/DeepSeek-Harness-Mobile/actions/workflows/rootfs.yml)
[![发布流水线](https://img.shields.io/github/actions/workflow/status/masgzy/DeepSeek-Harness-Mobile/release.yml?label=%E5%8F%91%E5%B8%83%E6%B5%81%E6%B0%B4%E7%BA%BF&logo=githubactions)](https://github.com/masgzy/DeepSeek-Harness-Mobile/actions/workflows/release.yml)
[![Android 版本 \| ABI](https://img.shields.io/badge/Android-8.0%2B%20%7C%20arm64--v8a-3DDC84?logo=android&logoColor=white)](./docs/architecture.md)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](./app/build.gradle.kts)

[![UI](https://img.shields.io/badge/UI-Material%20You%20(M3)-6750A4?logo=materialdesign&logoColor=white)](./app/src/main/java/io/github/masgzy/dshm/ui/theme/Theme.kt)
[![发行版](https://img.shields.io/badge/Ubuntu-26.04%20LTS-E95420?logo=ubuntu&logoColor=white)](./docs/architecture.md)
[![Node.js](https://img.shields.io/badge/Node.js-24%20LTS-339933?logo=nodedotjs&logoColor=white)](https://nodejs.org)
[![dsh](https://img.shields.io/badge/dsh-0.2.1--rc.2-4D6BFE)](https://www.npmjs.com/package/@deepseek-ai/dsh)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](./CONTRIBUTING.md)

</div>

---

## 📖 简介

**DeepSeek Harness Mobile** 是 DeepSeek Harness（dsh，DeepSeek 官方开源 Agent Harness）的 **Android 原生客户端**。项目通过 **proot 用户态虚拟化** 在应用私有目录中构建完整的 Ubuntu 26.04 LTS 容器，容器内运行 Node.js 24 LTS 与 dsh 引擎，原生界面通过 WebView 承载 dsh 官方 Web UI —— **全程无需 root、无需 Termux**，装上就用。

> ⚠️ dsh 目前处于 developer preview 阶段，接口可能存在破坏性变更；本项目为社区驱动，与 DeepSeek 官方无关。

### ✨ 特性

- **免 root / 免 Termux**：单 APK，全部逻辑跑在应用私有目录内，卸载即完全清除
- **完整 Linux 用户态**：Ubuntu 26.04 LTS (Resolute Raccoon) rootfs，AI 生成的 bash 命令真实可执行
- **双轨运行时供应**：
  - 🪶 **轻量通道**：下载官方 ubuntu-base 镜像（约 30MB），容器内一键安装 Node.js + dsh
  - ⚡ **预装通道**：CI 构建的开箱即用 bundle（SHA-256 锁定），下载即可用，适合弱网环境
- **供应链安全**：所有下载物强制 SHA-256 校验，镜像自动回退（官方源 ↔ 国内镜像）
- **前台保活**：前台服务 + 5 秒看门狗，长任务运行不怕后台回收
- **Material You**：Jetpack Compose + M3 动态取色，跟随系统壁纸换肤
- **工作区外置**：`Android/data/io.github.masgzy.dshm/files/workspace` 映射为容器内 `/root/projects`，文件管理器可直接访问

### 🧭 架构一图流

```
┌───────────────────────────────────────────────────┐
│  Android App（Kotlin + Jetpack Compose + M3）      │
│                                                    │
│  ┌─────────────┐  ┌─────────────────────────────┐ │
│  │ EngineService│  │  WebView                    │ │
│  │ 前台保活+看门狗│  │  http://127.0.0.1:3080     │ │
│  └──────┬──────┘  └──────────────▲──────────────┘ │
│         │                        │                │
│  ┌──────▼────────────────────────┴──────────────┐ │
│  │  proot（Termux 构建，用户态 rootfs 虚拟化）     │ │
│  │  ┌─────────────────────────────────────────┐ │ │
│  │  │  Ubuntu 26.04 LTS rootfs（应用私有目录）   │ │ │
│  │  │   ├─ Node.js 24 LTS（官方 tarball）       │ │ │
│  │  │   ├─ @deepseek-ai/dsh（npm 全局安装）      │ │ │
│  │  │   └─ /root/projects ←→ 宿主工作区 bind    │ │ │
│  │  └─────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
```

更多细节（进程链、目录布局、安全模型、更新机制、targetSdk 34 的 exec 限制说明）见 **[docs/architecture.md](./docs/architecture.md)**。

## 📲 安装

### 系统要求

| 项目 | 要求 |
| --- | --- |
| Android 版本 | 8.0（API 26）及以上 |
| CPU 架构 | arm64-v8a（2019 年后的主流机型） |
| 内存 | 建议 4GB+ |
| 存储 | 500MB+ 可用空间 |
| 网络 | 安装时需联网（预装通道约 200MB，轻量通道约 30MB + 容器内约 50MB） |

### 步骤

1. 从 [Releases](https://github.com/masgzy/DeepSeek-Harness-Mobile/releases) 下载最新 APK 并安装（允许「安装未知应用」）；
2. 首次启动进入安装向导，选择安装方式：
   - **官方 rootfs + 在线安装（推荐）**：体积小、组件始终最新；
   - **预装运行时**：下载 CI 预构建 bundle，最快最稳；
3. 安装完成后点击「启动引擎」，稍候 10-30 秒即可在 App 内使用 dsh Web UI；
4. 在 Web UI 设置中填入你的 DeepSeek API Key 即可开始对话。

## 🛠 从源码构建

```bash
# 1. 环境要求：JDK 17 + Android SDK (API 37)
# 2. 注入 proot 运行时资产（从 Termux 官方源获取）
./scripts/fetch-proot.sh

# 3. 构建
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（需 release.keystore）

# 4. （可选）本地构建预装运行时镜像
OUT=runtime/out ./runtime/build-rootfs.sh
```

## ⚙️ 配置说明

| 配置项 | 位置 | 默认值 |
| --- | --- | --- |
| Web UI 端口 | App → 设置 | `3080` |
| npm 源 | App → 设置 | 国内镜像（npmmirror） |
| rootfs 下载源 | 自动回退 | cdimage 官方 → 清华 TUNA |
| Node.js 下载源 | 自动回退 | nodejs.org → npmmirror |
| 工作区（宿主） | `Android/data/io.github.masgzy.dshm/files/workspace` | 容器内 `/root/projects` |

## 🗺 路线图

- [x] 框架搭建：proot 容器 + Node + dsh 一键安装
- [x] 双轨运行时供应（在线 bootstrap / CI 预装 bundle）
- [x] 前台保活 + 看门狗自愈
- [ ] SAF 文件桥：免权限导入导出设备文件（参照 dphmoblie）
- [ ] Digest 锁定的运行时增量更新 + 自动回滚
- [ ] API Key Android Keystore 加密存储
- [ ] 可选 Shizuku 集成（设备 shell 能力扩展）
- [ ] x86_64 模拟器支持
- [ ] targetSdk 35+ 迁移方案（exec 限制绕行）

## 🤝 参与贡献

欢迎 Issue 与 PR！提交前请确保：

1. Kotlin 代码遵循项目现有风格（2 空格缩进）；
2. Shell 脚本通过 `shellcheck`；
3. 新增依赖请同步更新 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。

## 📄 许可证

本项目基于 **GPL-3.0** 许可证开源，详见 [LICENSE](./LICENSE)。

分发中包含的第三方组件（DeepSeek Harness、Node.js、proot、Ubuntu rootfs、AndroidX 等）版权归各自作者所有，完整声明见 **[THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)**。

## 🙏 致谢

| 项目 | 说明 |
| --- | --- |
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | dsh 本体（MIT） |
| [dphmoblie/deepseek-harness-android](https://github.com/dphmoblie/deepseek-harness-android) | 最重要参考：jniLibs/proot 执行方案、PROOT_LOADER 机制、UI 与合规设计 |
| [deepcode-lab/deepseek-harness-mobile](https://github.com/deepcode-lab/deepseek-harness-mobile) | 引擎保活与 CI 签名参考 |
| [duke5am/deepseek-harness-on-android](https://github.com/duke5am/deepseek-harness-on-android) | Termux + proot-distro 安装路径验证 |
| [thness/dsh-mobile](https://github.com/thness/dsh-mobile) | 内嵌 Node.js 方案参考 |
| [Termux](https://github.com/termux/termux-packages) | proot / libtalloc / libandroid-shmem 构建 |
