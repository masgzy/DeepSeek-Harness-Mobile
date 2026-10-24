# 第三方组件与许可声明（THIRD_PARTY NOTICES）

> 本文档记录 DeepSeek Harness Mobile 分发与使用到的全部第三方组件，包括其版本、
> 许可证、**原始版权声明（未删改）**、在项目中的用途，以及关键许可证的完整原文。
>
> 本项目自身以 **GNU GPL-3.0** 许可证发布（完整文本见 [LICENSE](./LICENSE)）。
> 本文件不改变任何上游组件的许可条款；各组件版权归其原作者所有。

## 组件总表

| 组件 | 版本 | 许可证 | 来源 | 用途 |
| --- | --- | --- | --- | --- |
| DeepSeek Harness (`@deepseek-ai/dsh`) | 0.2.1-rc.2 | MIT | npm `@deepseek-ai/dsh` | 容器内运行的 AI Agent 引擎 |
| dphmoblie/deepseek-harness-android 图标资产 | 2026-10 main | MIT | GitHub `dphmoblie/deepseek-harness-android` | 应用图标（全套 mipmap）与吉祥物图（app-mark） |
| Node.js | v24.22.0 | MIT（含捆绑组件各自许可） | nodejs.org 官方发行包 | 容器内 JavaScript 运行时 |
| proot | 5.1.107.99（Termux 构建） | GPL-2.0-or-later | Termux 官方软件源 deb 包重打包 | 用户态 rootfs 虚拟化 |
| libtalloc | 2.5.0 | LGPL-3.0-or-later | Termux 官方软件源 deb 包重打包 | proot 的动态内存分配依赖 |
| libandroid-shmem | 0.7 | BSD-3-Clause | Termux 官方软件源 deb 包重打包 | proot 的 Android 共享内存适配 |
| Ubuntu rootfs | 26.04.1 LTS (Resolute Raccoon) | 各包自有许可（以 GPL 系列为主） | Canonical 官方 ubuntu-base 镜像 | 容器 Linux 用户态 |
| AndroidX / Jetpack Compose / Material 3 | compileSdk 36 对应版本 | Apache-2.0 | Google Maven | Android 应用框架与 UI |
| Kotlin 标准库 / Kotlin Android 插件 | 2.4.10 | Apache-2.0 | Maven Central | 开发语言与运行时 |
| Apache Commons Compress | 1.28.0 | Apache-2.0 | Maven Central | rootfs tar 归档解包 |
| XZ for Java (org.tukaani:xz) | 1.10 | Public Domain（公有领域） | Maven Central | .tar.xz 解压 |
| Gradle Wrapper | 9.7.0 | Apache-2.0 | Gradle 官方 | 构建入口 |

---

## 一、MIT 许可证组件

### 1.1 DeepSeek Harness（@deepseek-ai/dsh）

- **版权行（原始声明，未删改）**：`Copyright (c) 2026 DeepSeek`
- **来源**：https://www.npmjs.com/package/@deepseek-ai/dsh 、https://github.com/deepseek-ai/deepseek-harness
- **用途**：容器内核心 Agent 引擎，经 npm 全局安装进 Ubuntu rootfs，以 `dsh web` 启动官方 Web UI。

### 1.2 dphmoblie/deepseek-harness-android 图标资产

- **版权行（原始声明，未删改）**：`Copyright (c) 2026 DeepSeek Harness Mobile contributors`
- **来源**：https://github.com/dphmoblie/deepseek-harness-android （`src/assets/app-mark.png`、`android/app/src/main/res/mipmap-*`、`LICENSE`）
- **用途**：应用桌面图标（自适应图标前景/背景/圆形/传统图标）与外壳内吉祥物图。
- **说明**：本项目外部视觉对齐参考项目；图标资产未作内容修改，仅按 Android 资源规范重命名引入。

### 1.3 Node.js

- **版权行（原始声明，未删改）**：`Copyright OpenJS Foundation and Node.js contributors. All rights reserved.`
- **来源**：https://nodejs.org/dist/v24.22.0/
- **用途**：dsh 的运行时载体，以官方 linux-arm64 tarball 解压至容器 `/usr/local`。
- **说明**：Node.js 官方发行包本身捆绑大量第三方组件（V8、OpenSSL、zlib 等），
  其各自许可与版权声明以 Node.js 发行包内 `LICENSE` 文件为准；
  本项目分发的是未修改的官方 tarball，对应源码可从上述来源获取。

### 1.4 MIT 许可证全文

```text
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 二、BSD-3-Clause 许可证组件

### 2.1 libandroid-shmem

- **版权行（原始声明，未删改，取自 Termux deb 包内 copyright 文件）**：

```text
Copyright (c) 2013, Sergii Pylypenko
Copyright (c) 2017, Fredrik Fornwall
All rights reserved.
```

- **来源**：https://github.com/termux/libandroid-shmem （经 Termux 官方软件源分发）
- **用途**：在 Android 用户态提供 System V 共享内存接口，proot 运行所必需。

### 2.2 BSD-3-Clause 许可证全文

```text
Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice, this
  list of conditions and the following disclaimer in the documentation and/or
  other materials provided with the distribution.

* Neither the name of the {organization} nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

> 注：以上全文逐字取自上游分发文件（含占位符 `{organization}`，为上游原文，未做修改）。

---

## 三、Apache-2.0 许可证组件

### 3.1 组件清单与版权行

| 组件 | 原始版权声明（未删改） |
| --- | --- |
| AndroidX / Jetpack Compose / Material 3 / Material Icons | `Copyright The Android Open Source Project` |
| Kotlin 标准库（kotlin-stdlib） | `Copyright 2000-2026 JetBrains s.r.o.` |
| Apache Commons Compress | `Copyright The Apache Software Foundation` |
| Termux terminal-emulator / terminal-view / libtermux JNI（`com.termux.terminal`、`com.termux.view`、`app/src/main/cpp/termux.c`，容器终端 UI 内核；未修改逻辑，仅调整 R 引用与集成打包） | `Copyright (C) 2018 The Android Open Source Project` 等 Termux AUTHORS（见 termux-app 仓库） |
| Gradle Wrapper（gradle-wrapper） | `Copyright Gradle Inc.` / `Copyright 2010-2026 Gradle contributors` |

### 3.2 Android NOTICE 内容（Apache License 2.0 第 4(d) 条要求的归属声明）

以下为 AndroidX / Jetpack 组件随包分发的 NOTICE 文件内容（原文收录）：

```text
Copyright 2018 The Android Open Source Project

This product includes software developed as part of
The Android Open Source Project (http://www.android.com/).
```

### 3.3 Apache License 2.0 全文

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

> 注：以上为 Apache License 2.0 逐字原文（含附录占位符 `[yyyy]`、
> `[name of copyright owner]`）。各组件的**具体版权行**见第 3.1 节表格
> 与 AndroidX NOTICE 原文（第 3.2 节），均未删改。

---

## 四、GPL / LGPL 组件

### 4.1 proot（GPL-2.0-or-later）

- **版权行（原始声明，未删改）**：
  - `Copyright (C) 2015 STMicroelectronics, licensed under GPL v2 or later.`
  - 版权归 PRoot 原作者（proot-me 项目贡献者）与 Termux 打包者所有
- **来源**：https://github.com/termux/proot （Termux 官方源 deb 重打包）
- **说明**：上游头文件声明「GPL v2 or later」；Termux 包将版权文件链接至 GPL-2.0 文本。
  本项目以 **GPL-2.0-or-later** 声明分发该二进制。
- **完整 GPL-2.0 文本**：[docs/licenses/gpl-2.0.txt](./docs/licenses/gpl-2.0.txt)
- **对应源码（Corresponding Source）提供方式**：本仓库 `scripts/fetch-proot.sh`
  即为生成所分发二进制的完整构建脚本（从 Termux 官方源按固定版本下载并重打包）；
  上游源码与构建配方见 https://github.com/termux/proot 。

### 4.2 libtalloc（LGPL-3.0-or-later）

- **版权行（原始声明）**：`Copyright (C) Andrew Tridgell and the Samba Team`
- **来源**：https://talloc.samba.org/ （Termux 官方源 deb 重打包）
- **完整 LGPL-3.0 文本**：[docs/licenses/lgpl-3.0.txt](./docs/licenses/lgpl-3.0.txt)
  （上游历史版本亦提供 LGPL-2.1-or-later 选项，文本见
  [docs/licenses/lgpl-2.1.txt](./docs/licenses/lgpl-2.1.txt)）
- **说明**：本库以**动态链接**方式被 proot 使用，未做静态合并；故按 LGPL 义务
  提供许可文本与源码获取途径，并允许用户重新链接修改后的库版本。

### 4.3 Ubuntu 26.04 rootfs（各包自有许可，以 GPL 系列为主）

- **说明**：容器 rootfs 来自 Canonical 官方 `ubuntu-base-26.04.1-base-arm64.tar.gz`
  镜像（未修改基础包，仅由安装脚本按上游文档指引注入 Node.js 与 dsh）。
  其中包含的 GNU 组件（coreutils、bash、apt、dpkg 等）及其他软件包，
  其版权与许可以各软件包随附的版权文件为准（容器内 `/usr/share/doc/*/copyright`）。
- **获取与对应源码**：Ubuntu 官方镜像站 https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/
  （清华 TUNA 镜像：https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ ）；
  任何单个软件包的源码可通过 Ubuntu 软件源 `deb-src` 条目或
  https://launchpad.net/ubuntu/resolute/arm64/<包名> 获取。
- **容器内引导脚本**：`app/src/main/assets/scripts/bootstrap.sh` 为本项目自研
  （GPL-3.0，同本项目整体许可），不修改 Ubuntu 软件包本身。

### 4.4 GPL 组件修改记录

> 依据 GPL-3.0 第 5(a) 条及本项目合规要求：**任何对 GPL/LGPL 许可源文件的修改，
> 必须在分发时以显著方式声明修改**。本项目按下表登记：

| 组件 | 是否修改 | 修改内容 | 修改日期 |
| --- | --- | --- | --- |
| proot 5.1.107.99 | **否** | 仅从 Termux 官方 deb 重打包，未改动任何源文件 | —— |
| libtalloc 2.5.0 | **否** | 仅从 Termux 官方 deb 重打包，未改动任何源文件 | —— |
| Ubuntu 26.04.1 rootfs | **否** | 使用官方 base 镜像原样分发；Node.js/dsh 以附加方式注入，未修改 Ubuntu 自身软件包 | —— |

> 未来若引入对上述 GPL/LGPL 源文件的修改，须在本表新增条目，
> 注明**修改的具体文件、内容摘要与修改日期**，并随分发提供对应源码。

---

## 五、公有领域组件

### 5.1 XZ for Java（org.tukaani:xz 1.10）

- **声明**：该库由 Lasse Collin 及贡献者置于**公有领域（Public Domain）**。
- **用途**：应用内解压 .tar.xz 格式的预装运行时 bundle。

---

## 六、合规义务说明

1. **Apache-2.0**：分发时保留许可全文与 NOTICE 归属声明（本文件第三节已收录）。
2. **MIT / BSD-3**：分发时在所有副本或显著部分保留版权声明与许可文本
   （本文件第一、二节已收录）。
3. **GPL-2.0-or-later / GPL-3.0**：按 GPL 义务提供完整对应源码——
   本仓库即包含全部构建脚本与配方；proot/Ubuntu/Node.js/dsh 的上游源码
   均可从本文档标注的官方来源获取。
4. **LGPL-3.0-or-later**：动态链接方式下提供许可文本、源码获取途径，
   并保留用户替换修改版库文件的能力。
5. 商标声明：Android、Ubuntu、Node.js、JetBrains、DeepSeek 等名称与标识
   归各自权利人所有；本项目与其无隶属关系，亦未获得背书。
