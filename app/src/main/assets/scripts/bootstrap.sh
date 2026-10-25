#!/bin/sh
# =====================================================================
# DeepSeek-Harness-Mobile 容器内一键引导脚本
# 在 proot 容器内以 root 身份执行，支持三类容器系统：
#
#   Debian/Ubuntu（GLIBC，/bin/bash 执行）：
#     1. 解压 Node.js 官方 tarball 到 /usr/local
#     2. 安装 pnpm（三种方式可选）→ pnpm 全局安装 @deepseek-ai/dsh
#   Alpine（musl，/bin/sh 执行）：
#     0. 切 apk 镜像源 → apk add bash nodejs npm coreutils libstdc++
#        （libstdc++ 必装：koffi/node-pty 的 musl 预编译二进制依赖它，
#         缺失会导致 optional 预编译包 dlopen 失败 → 回退源码编译 → 无
#         CMake → 安装中止，实测 alpine:3.22 复现）
#   自定义镜像（GLIBC 假设，/bin/bash 执行）：
#     同 Debian/Ubuntu；musl 自定义镜像会在 node 执行检查处报错
#
#   3. 写入安装完成标记 /root/.dshm/installed（记录 distro/node/dsh/pnpm 版本）
#
# 由宿主 APK 通过 proot bind 注入：
#   /dshm/node.tar.gz      -> Node.js 官方 linux-arm64 tar.gz（宿主已校验
#                             SHA-256；Alpine 路径不注入该文件）
#   /dshm/bootstrap.sh     -> 本脚本
#   /dshm/pnpm-install.sh  -> pnpm 一键脚本（安装方式 = script 时使用）
#
# 环境变量（由宿主注入）：
#   DSH_VERSION      要安装的 @deepseek-ai/dsh 版本（空 = npm latest）
#   DSH_NPM_REGISTRY npm registry（默认官方源，可传国内镜像）
#   DSHM_DISTRO_ID   容器系统 ID（写入标记，如 debian13 / alpine3 / custom）
#   DSHM_FAMILY      glibc | alpine
#   NODE_SHA256      Node tarball 期望 SHA-256（GLIBC 路径，校验完整性）
#   ALPINE_MIRROR    Alpine apk 镜像源（alpine 路径；宿主测速择优或默认 TUNA）
#   DSHM_APT_MIRROR  apt 镜像站基址（scheme://host，宿主测速择优；空 = 不切换）
#   PNPM_MAJOR       pnpm 版本线：12（默认）| 11
#   PNPM_METHOD      pnpm 安装方式：npm（默认）| getpnpm | script
# =====================================================================
set -eu

DSH_VERSION="${DSH_VERSION:-}"
DSH_NPM_REGISTRY="${DSH_NPM_REGISTRY:-https://registry.npmjs.org}"
DSHM_DISTRO_ID="${DSHM_DISTRO_ID:-unknown}"
DSHM_FAMILY="${DSHM_FAMILY:-glibc}"
PNPM_MAJOR="${PNPM_MAJOR:-12}"
PNPM_METHOD="${PNPM_METHOD:-npm}"
NODE_ARCHIVE="/dshm/node.tar.gz"
MARKER_DIR="/root/.dshm"
MARKER="$MARKER_DIR/installed"
PNPM_HOME="/root/.local/share/pnpm"

log() { echo "[dshm-bootstrap] $*"; }
fail() { echo "[dshm-bootstrap][FAIL] $*" >&2; exit 1; }

# Alpine 分支会 exec bash "$0" 重入，重入时只提示续跑，避免横幅重复刷屏
if [ "${DSHM_ALPINE_READY:-}" = "1" ]; then
  log "=== 引导继续（Alpine 基础环境已就绪）==="
else
  log "=== DeepSeek Harness Mobile 容器引导开始 ==="
  log "distro=$DSHM_DISTRO_ID family=$DSHM_FAMILY | dsh: ${DSH_VERSION:-latest} | registry: $DSH_NPM_REGISTRY | pnpm: v$PNPM_MAJOR via $PNPM_METHOD"
fi

if [ -f "$MARKER" ]; then
  log "检测到安装标记，跳过（如需重装请先在 App 内卸载容器）"
  exit 0
fi

# ---- 0. Alpine（musl）分支：apk 装 bash/nodejs/npm，再以 bash 重入 ----
if [ "$DSHM_FAMILY" = "alpine" ]; then
  if [ "${DSHM_ALPINE_READY:-}" != "1" ]; then
    log "[alpine/1] 切换 apk 镜像源 ..."
    ALPINE_MIRROR="${ALPINE_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/alpine}"
    # /etc/alpine-release 形如 "3.22.1"；仓库路径需要 v3.22 前缀
    ALPINE_BRANCH="v$(cat /etc/alpine-release 2>/dev/null | cut -d. -f1,2 || echo 3.22)"
    mkdir -p /etc/apk
    printf '%s\n' "$ALPINE_MIRROR/$ALPINE_BRANCH/main" "$ALPINE_MIRROR/$ALPINE_BRANCH/community" > /etc/apk/repositories
    log "  源: $ALPINE_MIRROR/$ALPINE_BRANCH"
    # libstdc++ 是 koffi/node-pty musl 预编译二进制的硬依赖，缺失时
    # optionalDependencies 的 .node 加载失败 → 回退源码编译 → 无 CMake 中止
    log "[alpine/2] apk add --no-cache bash nodejs npm coreutils libstdc++（需网络）..."
    apk add --no-cache bash nodejs npm coreutils libstdc++ \
      || fail "apk 安装失败（检查网络 / 镜像源：$ALPINE_MIRROR）"
    command -v node >/dev/null 2>&1 || fail "apk nodejs 安装后 node 仍不可用"
    log "  node $(node --version) / npm $(npm --version) 就绪"
    # 以 bash 重入（后续步骤统一 bash 语义）
    DSHM_ALPINE_READY=1
    export DSHM_ALPINE_READY
    exec bash "$0"
  fi
fi

# ---- 0.5 apt 镜像切换（可选，best-effort；失败不阻断安装） ----
# DSHM_APT_MIRROR 为镜像站基址（scheme://host，由宿主测速择优），
# 对 archive.ubuntu.com / security.ubuntu.com / deb.debian.org / security.debian.org
# 全量替换到该基址下的同路径（/ubuntu、/debian、/debian-security）。
if [ -n "${DSHM_APT_MIRROR:-}" ] && command -v sed >/dev/null 2>&1; then
  log "[apt] 切换 apt 镜像源 → $DSHM_APT_MIRROR（best-effort）"
  M="$DSHM_APT_MIRROR"
  for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/debian.sources; do
    if [ -f "$f" ]; then
      sed -i "s#http://archive.ubuntu.com/ubuntu#$M/ubuntu#g; \
              s#https://archive.ubuntu.com/ubuntu#$M/ubuntu#g; \
              s#http://security.ubuntu.com/ubuntu#$M/ubuntu#g; \
              s#https://security.ubuntu.com/ubuntu#$M/ubuntu#g; \
              s#http://deb.debian.org/debian#$M/debian#g; \
              s#https://deb.debian.org/debian#$M/debian#g; \
              s#http://security.debian.org/debian-security#$M/debian-security#g; \
              s#https://security.debian.org/debian-security#$M/debian-security#g" "$f" 2>/dev/null || true
    fi
  done
  [ -f /etc/apt/sources.list ] && sed -i "s#http://archive.ubuntu.com#$M#g; \
              s#https://archive.ubuntu.com#$M#g; \
              s#http://deb.debian.org#$M#g; \
              s#https://deb.debian.org#$M#g; \
              s#http://security.ubuntu.com#$M#g; \
              s#http://security.debian.org#$M#g" /etc/apt/sources.list 2>/dev/null || true
fi

# ---- GLIBC 路径：解压 Node.js tarball 到 /usr/local ----
if [ "$DSHM_FAMILY" != "alpine" ]; then
  [ -f "$NODE_ARCHIVE" ] || fail "Node tarball 不存在: $NODE_ARCHIVE（自定义 musl 镜像不受支持，请选 Alpine 或 glibc 系镜像）"

  log "[1/5] 解压 Node.js 到 /usr/local ..."
  mkdir -p /usr/local
  tar -xzf "$NODE_ARCHIVE" --strip-components=1 -C /usr/local

  # W^X 兼容（华为/EMUI）：node ELF 不可写才能被 exec
  chmod 0555 /usr/local/bin/node 2>/dev/null || true
  chmod +x /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack 2>/dev/null || true

  hash -r
  NODE_VER="$(/usr/local/bin/node --version)" || fail "node 无法执行（ELF 架构/权限问题？musl 镜像请选 Alpine 容器系统）"
  log "  node $NODE_VER 就绪"
  export PATH="/usr/local/bin:$PATH"
else
  NODE_VER="$(node --version)"
fi

# ---- 2. 安装 pnpm（pnpm 12 为 Rust 重写的原生二进制，安装/运行均显著快于 npm） ----
npm config set registry "$DSH_NPM_REGISTRY" --global
# pnpm 12 不透传 npm_config_registry 环境变量（真机实测走了 npmjs.org，
# 龟速导致 koffi 平台预编译包下载失败被静默跳过 → cnoke 回退源码编译 →
# 无 CMake 安装中止）。沙箱实测 pnpm 12.15.0 只认用户级 ~/.npmrc 与
# --registry 旗标：npmrc 覆盖 pnpm 全部子命令，旗标保证本次调用确定生效。
NPMRC="${HOME:-/root}/.npmrc"
touch "$NPMRC"
if grep -q '^registry=' "$NPMRC" 2>/dev/null; then
  sed -i "s#^registry=.*#registry=$DSH_NPM_REGISTRY#" "$NPMRC"
else
  printf 'registry=%s\n' "$DSH_NPM_REGISTRY" >> "$NPMRC"
fi

install_pnpm_npm() {
  # npmmirror 平台二进制包齐全（@pnpm/exe.linux-arm64[-musl] 已实测同步）；
  # v12 的 npm 包本身即 Rust 二进制（install.js 从平台 optional dep 复制）
  npm install -g --no-audit --no-fund --loglevel=notice "pnpm@$PNPM_MAJOR"
}

install_pnpm_getpnpm() {
  # pnpm 官方 get-pnpm：自动平台/musl 检测，装独立二进制到 PNPM_HOME；
  # registry 经 npm_config_registry 透传（官方支持）
  npm_config_registry="$DSH_NPM_REGISTRY" npx --yes get-pnpm@latest "$PNPM_MAJOR"
}

install_pnpm_script() {
  # 大陆适配版一键脚本（本仓库 assets，全走镜像 + 单 tarball + SHA-512）
  PNPM_REGISTRY="$DSH_NPM_REGISTRY" PNPM_VERSION_LINE="$PNPM_MAJOR" PNPM_HOME="$PNPM_HOME" \
    sh /dshm/pnpm-install.sh
}

log "[2/5] 安装 pnpm v$PNPM_MAJOR（方式: $PNPM_METHOD）..."
# pnpm 全局 bin 目录：v10+/v12 为 $PNPM_HOME/bin，旧版为 $PNPM_HOME 本身。
# 必须在 pnpm add -g 之前把两者加入 PATH，否则非交互 shell 下 pnpm 12 会报
# ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH 直接中止（alpine3 真机实测复现）。
# 提前导出也使 get-pnpm / 一键脚本安装路径确定一致。
export PNPM_HOME
for d in "$PNPM_HOME" "$PNPM_HOME/bin"; do
  case ":$PATH:" in
    *":$d:"*) ;;
    *) export PATH="$d:$PATH" ;;
  esac
done
hash -r
case "$PNPM_METHOD" in
  getpnpm) install_pnpm_getpnpm || install_pnpm_npm ;;
  script)  install_pnpm_script  || install_pnpm_npm ;;
  *)       install_pnpm_npm ;;
esac
# 兜底定位：npm 装在 /usr/local/bin；get-pnpm/script 装在 $PNPM_HOME
if ! command -v pnpm >/dev/null 2>&1 && [ -x "$PNPM_HOME/pnpm" ]; then
  export PATH="$PNPM_HOME:$PATH"
fi
command -v pnpm >/dev/null 2>&1 || fail "pnpm 安装失败（npm / get-pnpm / 脚本三种方式均未成功）"
PNPM_VER="$(pnpm --version)"
log "  pnpm $PNPM_VER 就绪: $(command -v pnpm)"
# 统一入口符号链接：引擎启动与插件管理均按 PATH 找 pnpm
PNPM_REAL="$(readlink -f "$(command -v pnpm)")"
mkdir -p /usr/local/bin
ln -sf "$PNPM_REAL" /usr/local/bin/pnpm

# ---- 3. pnpm 全局安装 @deepseek-ai/dsh（失败自动回退 npm） ----
log "[3/5] pnpm install -g @deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION} ..."
# --node-linker=hoisted：npm 式平铺布局。pnpm 默认隔离布局（.pnpm + 符号链接）
# 在 proot 下导致 koffi 预编译二进制加载失败（alpine3 真机三轮复现，而 npm
# 平铺布局可用）；hoisted 与 npm 回退路径的目录结构完全一致，规避该问题。
# --allow-build：pnpm 10+ 默认禁止依赖的生命周期脚本。注意 koffi 不放行：
#   其 install 脚本（cnoke --prebuild）只是"预编译可加载性"校验器，预编译
#   可用时为空操作；加载失败时回退源码编译（容器无 CMake）→ 整个安装硬
#   失败且真实错误被吞。改由安装完成后 bootstrap 亲自验证并输出真实错误。
#   其余放行：node-pty 预编译打包在 tarball 内（prebuild.js 落位）；
#   dsh-subprocess-local 恢复 node-pty spawn-helper 可执行位；dsh 主包脚本。
ALLOW_FLAGS=""
if pnpm add --help 2>/dev/null | grep -q -- "--allow-build"; then
  ALLOW_FLAGS="--allow-build=node-pty --allow-build=@deepseek-ai/dsh --allow-build=@deepseek-ai/dsh-subprocess-local"
fi
DSH_SPEC="@deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION}"
if npm_config_registry="$DSH_NPM_REGISTRY" pnpm add -g $ALLOW_FLAGS --node-linker=hoisted --registry="$DSH_NPM_REGISTRY" "$DSH_SPEC"; then
  log "  pnpm 安装 dsh 完成"
  # koffi 预编译加载验证（与 cnoke checkPrebuild 同法 require 包目录）：
  # koffi 是 dsh 运行时 FFI 依赖，平台包内含 glibc/musl 双二进制，musl 变体
  # 仅需 libstdc++（apk 已补）。失败时把真实错误打进日志（cnoke 会吞掉它）
  KOFFI_DIR="$(find "$PNPM_HOME/global" -type d -name koffi -path '*node_modules/koffi' 2>/dev/null | head -1 || true)"
  if [ -n "$KOFFI_DIR" ]; then
    if node -e "require(process.argv[1])" "$KOFFI_DIR" >/dev/null 2>&1; then
      log "  koffi 预编译加载验证通过"
    else
      log "  [警告] koffi 预编译加载失败，真实错误："
      node -e "require(process.argv[1])" "$KOFFI_DIR" 2>&1 | head -6 | sed 's/^/[dshm-bootstrap]     /' || true
      log "  回退 npm 安装（保底路径）..."
      rm -f "$PNPM_HOME/bin/dsh" "$PNPM_HOME/dsh" 2>/dev/null || true
      hash -r
      npm install -g --no-audit --no-fund --loglevel=notice "$DSH_SPEC"
    fi
  fi
else
  log "  pnpm 安装失败，回退 npm 安装（保底路径）..."
  # 清掉可能残留的 pnpm 半成品 dsh shim，避免 PATH 中 $PNPM_HOME/bin
  # 优先级高于 /usr/local/bin 时 shadow 掉 npm 装的可用 dsh
  rm -f "$PNPM_HOME/bin/dsh" "$PNPM_HOME/dsh" 2>/dev/null || true
  hash -r
  npm install -g --no-audit --no-fund --loglevel=notice "$DSH_SPEC"
fi
hash -r

DSH_BIN="$(command -v dsh || true)"
[ -n "$DSH_BIN" ] || fail "dsh 未找到（pnpm/npm 全局安装均失败？）"
log "  dsh 位于: $DSH_BIN"
# pnpm 装的 dsh 在 $PNPM_HOME；统一符号链接到 /usr/local/bin 供引擎 PATH 使用
DSH_REAL="$(readlink -f "$DSH_BIN")"
ln -sf "$DSH_REAL" /usr/local/bin/dsh
dsh --version || true

# ---- 3.5 V8 编译缓存预热（dsh 启动提速） ----
# Node 22+ 支持 NODE_COMPILE_CACHE：首跑把解析产物落盘，之后每次启动免重复解析。
# proot 下 stat/open 翻译开销大，dsh web 冷启动 10-30s；预热后引擎启动明显加快。
# 引擎进程（EngineManager）启动时已注入同名环境变量，缓存目录保持一致。
log "[4/5] 预热 Node 编译缓存（加速 dsh 启动）..."
COMPILE_CACHE="$MARKER_DIR/compile-cache"
mkdir -p "$COMPILE_CACHE"
NODE_COMPILE_CACHE="$COMPILE_CACHE" dsh --version >/dev/null 2>&1 \
  || log "  预热跳过（不影响功能）"

# ---- 5. 写入就绪标记 ----
log "[5/5] 写入安装标记 ..."
mkdir -p "$MARKER_DIR"
cat > "$MARKER" <<EOF
distro=$DSHM_DISTRO_ID
node=$NODE_VER
pnpm=$PNPM_VER
dsh=${DSH_VERSION:-latest}
registry=$DSH_NPM_REGISTRY
pnpm_method=$PNPM_METHOD
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
# pnpm 就绪标记（App 内插件管理据此跳过补装检查）
printf 'pnpm=%s\ninstalled_at=%s\n' "$(pnpm --version 2>/dev/null || echo unknown)" \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$MARKER_DIR/pnpm-installed"

log "=== 引导完成：dsh web --no-open 即可启动 ==="
exit 0
