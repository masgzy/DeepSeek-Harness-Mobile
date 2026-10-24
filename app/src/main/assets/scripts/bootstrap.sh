#!/bin/sh
# =====================================================================
# DeepSeek-Harness-Mobile 容器内一键引导脚本
# 在 proot 容器内以 root 身份执行，支持三类容器系统：
#
#   Debian/Ubuntu（GLIBC，/bin/bash 执行）：
#     1. 解压 Node.js 官方 tarball 到 /usr/local
#     2. npm 全局安装 @deepseek-ai/dsh
#   Alpine（musl，/bin/sh 执行）：
#     0. 切 apk 镜像源 → apk add bash nodejs npm → 重入本脚本（bash）
#   自定义镜像（GLIBC 假设，/bin/bash 执行）：
#     同 Debian/Ubuntu；musl 自定义镜像会在 node 执行检查处报错
#
#   3. 写入安装完成标记 /root/.dshm/installed（记录 distro/node/dsh 版本）
#
# 由宿主 APK 通过 proot bind 注入：
#   /dshm/node.tar.gz  -> Node.js 官方 linux-arm64 tar.gz（宿主已校验 SHA-256；
#                         Alpine 路径不注入该文件）
#   /dshm/bootstrap.sh -> 本脚本
#
# 环境变量（由宿主注入）：
#   DSH_VERSION      要安装的 @deepseek-ai/dsh 版本（空 = npm latest）
#   DSH_NPM_REGISTRY npm registry（默认官方源，可传国内镜像）
#   DSHM_DISTRO_ID   容器系统 ID（写入标记，如 debian13 / alpine3 / custom）
#   DSHM_FAMILY      glibc | alpine
#   NODE_SHA256      Node tarball 期望 SHA-256（GLIBC 路径，校验完整性）
#   ALPINE_MIRROR    Alpine apk 镜像源（alpine 路径；宿主测速择优或默认 TUNA）
#   DSHM_APT_MIRROR  apt 镜像站基址（scheme://host，宿主测速择优；空 = 不切换）
# =====================================================================
set -eu

DSH_VERSION="${DSH_VERSION:-}"
DSH_NPM_REGISTRY="${DSH_NPM_REGISTRY:-https://registry.npmjs.org}"
DSHM_DISTRO_ID="${DSHM_DISTRO_ID:-unknown}"
DSHM_FAMILY="${DSHM_FAMILY:-glibc}"
NODE_ARCHIVE="/dshm/node.tar.gz"
MARKER_DIR="/root/.dshm"
MARKER="$MARKER_DIR/installed"

log() { echo "[dshm-bootstrap] $*"; }
fail() { echo "[dshm-bootstrap][FAIL] $*" >&2; exit 1; }

log "=== DeepSeek Harness Mobile 容器引导开始 ==="
log "distro=$DSHM_DISTRO_ID family=$DSHM_FAMILY | dsh: ${DSH_VERSION:-latest} | registry: $DSH_NPM_REGISTRY"

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
    log "[alpine/2] apk add --no-cache bash nodejs npm coreutils（需网络）..."
    apk add --no-cache bash nodejs npm coreutils \
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

  log "[1/4] 解压 Node.js 到 /usr/local ..."
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

# ---- 2. npm 全局安装 @deepseek-ai/dsh + pnpm（单次 resolve 提速） ----
log "[2/4] npm 配置 registry: $DSH_NPM_REGISTRY"
npm config set registry "$DSH_NPM_REGISTRY" --global

# dsh 与 pnpm（插件管理依赖）合并为一次 npm install：省去第二次依赖解析/元数据
# 拉取（npmmirror 上两次安装大约可省 30-50% 耗时）
log "[3/4] npm install -g @deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION} + pnpm ..."
# --no-audit --no-fund 加速；--loglevel=info 全量输出 fetch/进度行 ——
# 用户要求能看到 npm 完整日志（旧版 error 级在安装期间静默数分钟，
# 无法判断是否卡住）。deprecation 警告属 dsh 传递依赖，无害。
npm install -g --no-audit --no-fund --loglevel=info \
  "@deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION}" pnpm

DSH_BIN="$(command -v dsh || true)"
[ -n "$DSH_BIN" ] || fail "dsh 未找到（npm 全局安装失败？）"
log "  dsh 位于: $DSH_BIN"
dsh --version || true
command -v pnpm >/dev/null 2>&1 || fail "pnpm 安装失败"
pnpm --version || true

# ---- 3.5 V8 编译缓存预热（dsh 启动提速） ----
# Node 22+ 支持 NODE_COMPILE_CACHE：首跑把解析产物落盘，之后每次启动免重复解析。
# proot 下 stat/open 翻译开销大，dsh web 冷启动 10-30s；预热后引擎启动明显加快。
# 引擎进程（EngineManager）启动时已注入同名环境变量，缓存目录保持一致。
log "[3.5/4] 预热 Node 编译缓存（加速 dsh 启动）..."
COMPILE_CACHE="$MARKER_DIR/compile-cache"
mkdir -p "$COMPILE_CACHE"
NODE_COMPILE_CACHE="$COMPILE_CACHE" dsh --version >/dev/null 2>&1 \
  || log "  预热跳过（不影响功能）"

# ---- 4. 写入就绪标记 ----
log "[4/4] 写入安装标记 ..."
mkdir -p "$MARKER_DIR"
cat > "$MARKER" <<EOF
distro=$DSHM_DISTRO_ID
node=$NODE_VER
dsh=${DSH_VERSION:-latest}
registry=$DSH_NPM_REGISTRY
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
# pnpm 就绪标记（App 内插件管理据此跳过补装检查）
printf 'pnpm=%s\ninstalled_at=%s\n' "$(pnpm --version 2>/dev/null || echo unknown)" \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$MARKER_DIR/pnpm-installed"

log "=== 引导完成：dsh web --no-open 即可启动 ==="
exit 0
