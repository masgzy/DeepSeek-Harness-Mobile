#!/usr/bin/env bash
# =====================================================================
# DeepSeek-Harness-Mobile 容器内一键引导脚本
# 在 proot 容器（Ubuntu 26.04 base arm64）内以 root 身份执行：
#   1. 解压 Node.js LTS 官方 tarball 到 /usr/local
#   2. npm 全局安装 @deepseek-ai/dsh
#   3. 写入安装完成标记 /root/.dshm/installed
#
# 由宿主 APK 通过 proot bind 注入：
#   /dshm/node.tar.gz  -> Node.js 官方 linux-arm64 tar.gz（宿主已校验 SHA-256）
#   /dshm/bootstrap.sh -> 本脚本
#
# 环境变量（由宿主注入）：
#   DSH_VERSION      要安装的 @deepseek-ai/dsh 版本（如 0.2.1-rc.2）
#   DSH_NPM_REGISTRY npm registry（默认官方源，可传国内镜像）
# =====================================================================
set -euo pipefail

DSH_VERSION="${DSH_VERSION:-}"
DSH_NPM_REGISTRY="${DSH_NPM_REGISTRY:-https://registry.npmjs.org}"
NODE_ARCHIVE="/dshm/node.tar.gz"
MARKER_DIR="/root/.dshm"
MARKER="$MARKER_DIR/installed"

log() { echo "[dshm-bootstrap] $*"; }
fail() { echo "[dshm-bootstrap][FAIL] $*" >&2; exit 1; }

trap 'fail "安装在第 $LINENO 行失败，详情见上方输出"' ERR

log "=== DeepSeek Harness Mobile 容器引导开始 ==="
log "Node tarball: $NODE_ARCHIVE | dsh: ${DSH_VERSION:-latest} | registry: $DSH_NPM_REGISTRY"

if [ -f "$MARKER" ]; then
  log "检测到安装标记，跳过（如需重装请先在 App 内卸载容器）"
  exit 0
fi

[ -f "$NODE_ARCHIVE" ] || fail "Node tarball 不存在: $NODE_ARCHIVE"

# ---- 1. 解压 Node.js 到 /usr/local（tarball 顶层目录被剥掉） ----
log "[1/4] 解压 Node.js 到 /usr/local ..."
mkdir -p /usr/local
tar -xzf "$NODE_ARCHIVE" --strip-components=1 -C /usr/local

# W^X 兼容（华为/EMUI）：node ELF 不可写才能被 exec
chmod 0555 /usr/local/bin/node 2>/dev/null || true
chmod +x /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack 2>/dev/null || true

hash -r
NODE_VER="$(/usr/local/bin/node --version)" || fail "node 无法执行（ELF 架构/权限问题？）"
log "  node $NODE_VER 就绪"
export PATH="/usr/local/bin:$PATH"

# ---- 2. npm 全局安装 @deepseek-ai/dsh ----
log "[2/4] npm 配置 registry: $DSH_NPM_REGISTRY"
npm config set registry "$DSH_NPM_REGISTRY" --global

log "[3/4] npm install -g @deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION} ..."
# --no-audit --no-fund 加速；--loglevel=error 只保留错误与结果，
# 屏蔽 npm 的 deprecation 噪音（如 node-domexception@1.0.0 deprecated，
# 该警告来自 dsh 的传递依赖，无害且不影响功能）
# dsh 尚在 developer preview，固定版本保证可复现
npm install -g --no-audit --no-fund --loglevel=error \
  "@deepseek-ai/dsh${DSH_VERSION:+@$DSH_VERSION}"

DSH_BIN="$(command -v dsh || true)"
[ -n "$DSH_BIN" ] || fail "dsh 未找到（npm 全局安装失败？）"
log "  dsh 位于: $DSH_BIN"
dsh --version || true

# ---- 3. 重建 apt 源（可选，供用户后续在容器 shell 里使用） ----
# 保持镜像默认源不动；如需加速可自行替换 /etc/apt/sources.list.d/ubuntu.sources

# ---- 4. 写入就绪标记 ----
log "[4/4] 写入安装标记 ..."
mkdir -p "$MARKER_DIR"
cat > "$MARKER" <<EOF
node=$NODE_VER
dsh=${DSH_VERSION:-latest}
registry=$DSH_NPM_REGISTRY
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

log "=== 引导完成：dsh web --no-open 即可启动 ==="
exit 0
