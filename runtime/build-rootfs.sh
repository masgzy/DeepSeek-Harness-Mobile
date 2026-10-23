#!/usr/bin/env bash
# =====================================================================
# build-rootfs.sh —— 构建预装运行时 bundle（Ubuntu 26.04 + Node 24 + dsh）
#
# 产物：
#   $OUT/dsh-runtime-ubuntu26.04-arm64-<版本>.tar.xz   预装 rootfs
#   $OUT/manifest.json                                  清单（含 SHA-256）
#
# 设计说明（v2）：零交叉执行 —— npm 包均为纯 JavaScript，因此在宿主侧：
#   1. 直接解压 arm64 的 ubuntu-base 与 Node tarball（tar 不执行二进制）；
#   2. 用宿主 Node/npm 把 @deepseek-ai/dsh 安装进 rootfs 的 /usr/local；
#   3. 写入与真机 bootstrap.sh 相同的 /root/.dshm/installed 标记。
# 无需 proot / qemu-user-static，规避 qemu 对新内核系统调用
# （如 fchmodat2）支持不全导致的容器内 tar/npm 失败。
#
# 用法： OUT=runtime/out ./runtime/build-rootfs.sh
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/runtime/out}"
DSH_VERSION="${DSH_VERSION:-0.2.1-rc.2}"
NODE_VERSION="${NODE_VERSION:-v24.22.0}"
UBUNTU_BASE_NAME="${UBUNTU_BASE_NAME:-ubuntu-base-26.04.1-base-arm64.tar.gz}"
UBUNTU_BASE_SHA256="${UBUNTU_BASE_SHA256:-5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd}"
NODE_SHA256="${NODE_SHA256:-b7e7991d5407815adfa74a2a9cd4db683552c8ce32ff4a295c22e972849c3f5d}"
UBUNTU_RELEASE_VERSION="${UBUNTU_RELEASE_VERSION:-26.04.1}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
PROOT_VER="${PROOT_VER:-5.1.107.99}"
RUNTIME_TAG="${RUNTIME_TAG:-$(date -u +%Y%m%d)}"

log() { echo "[build-rootfs] $*"; }
fail() { echo "[build-rootfs][FAIL] $*" >&2; exit 1; }

# 宿主需要 Node.js + npm（GitHub runner 自带；本地需自备）
command -v npm >/dev/null || fail "宿主缺少 npm（Node.js 20+）"
NPM_MAJOR="$(npm --version | cut -d. -f1)"
[ "$NPM_MAJOR" -ge 9 ] || fail "npm 版本过低（需 9+，当前 $(npm --version)）"

mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---- 1. 下载 ubuntu-base rootfs（官方 + TUNA 双镜像） ----
log "下载 Ubuntu ${UBUNTU_RELEASE_VERSION} base rootfs ..."
curl -sfL --retry 3 -o "$WORK/base.tar.gz" \
  "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/$UBUNTU_BASE_NAME" \
  || curl -sfL --retry 3 -o "$WORK/base.tar.gz" \
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/26.04/release/$UBUNTU_BASE_NAME" \
  || fail "rootfs 下载失败"
echo "$UBUNTU_BASE_SHA256  $WORK/base.tar.gz" | sha256sum -c - || fail "rootfs SHA-256 校验失败"

# ---- 2. 下载 Node.js tarball（官方 + npmmirror 双镜像） ----
log "下载 Node.js $NODE_VERSION ..."
NODE_ARCHIVE="$WORK/node.tar.gz"
curl -sfL --retry 3 -o "$NODE_ARCHIVE" "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz" \
  || curl -sfL --retry 3 -o "$NODE_ARCHIVE" "https://npmmirror.com/mirrors/node/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz" \
  || fail "Node 下载失败"
echo "$NODE_SHA256  $NODE_ARCHIVE" | sha256sum -c - || fail "Node SHA-256 校验失败"

# ---- 3. 组装 rootfs（全部在宿主侧完成） ----
ROOTFS="$WORK/rootfs"
mkdir -p "$ROOTFS"
tar -xzf "$WORK/base.tar.gz" -C "$ROOTFS"

log "解压 Node.js 到 rootfs/usr/local ..."
mkdir -p "$ROOTFS/usr/local"
tar -xzf "$NODE_ARCHIVE" --strip-components=1 -C "$ROOTFS/usr/local"
# W^X 语义对齐真机 bootstrap：node ELF 不可写
chmod 0555 "$ROOTFS/usr/local/bin/node"

log "宿主侧 npm 安装 @deepseek-ai/dsh@$DSH_VERSION ..."
# --ignore-scripts：不执行任何包生命周期脚本（纯 JS 安装，无原生构建）
npm install --global --prefix "$ROOTFS/usr/local" \
  --registry "$NPM_REGISTRY" --no-audit --no-fund --ignore-scripts \
  "@deepseek-ai/dsh@$DSH_VERSION"
[ -x "$ROOTFS/usr/local/bin/dsh" ] || fail "dsh bin 未生成"
ls -la "$ROOTFS/usr/local/bin/" | head

# ---- 4. 注入标记与配置（与真机 bootstrap.sh 产物一致） ----
mkdir -p "$ROOTFS/root/.dshm"
cat > "$ROOTFS/root/.dshm/installed" <<EOF
node=$NODE_VERSION
dsh=$DSH_VERSION
registry=$NPM_REGISTRY
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
builder=host-side-npm
EOF
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"
# rootfs 元数据（版本追溯）
cat > "$ROOTFS/root/.dshm/runtime.json" <<EOF
{"ubuntu":"$UBUNTU_RELEASE_VERSION","node":"${NODE_VERSION#v}","dsh":"$DSH_VERSION","proot":"termux-$PROOT_VER"}
EOF

# ---- 5. 打包 ----
BUNDLE="$OUT/dsh-runtime-ubuntu26.04-arm64-$RUNTIME_TAG.tar.xz"
log "打包 $BUNDLE ..."
tar -cJf "$BUNDLE" -C "$ROOTFS" .
BUNDLE_SHA="$(sha256sum "$BUNDLE" | awk '{print $1}')"
BUNDLE_BYTES="$(stat -c %s "$BUNDLE")"

# ---- 6. 生成 manifest.json ----
NODE_VER_NUM="${NODE_VERSION#v}"
PROOT_TAG="termux-$PROOT_VER"
python3 - "$OUT/manifest.json" <<PYEOF
import json, sys
manifest = {
    "schema": "dshm.runtime/1",
    "runtime": {
        "ubuntu": "$UBUNTU_RELEASE_VERSION",
        "node": "$NODE_VER_NUM",
        "dsh": "$DSH_VERSION",
        "proot": "$PROOT_TAG",
    },
    "bundle": {
        "version": "$RUNTIME_TAG",
        "file": "$(basename "$BUNDLE")",
        "url": "https://github.com/masgzy/DeepSeek-Harness-Mobile/releases/download/runtime-nightly/$(basename "$BUNDLE")",
        "sha256": "$BUNDLE_SHA",
        "bytes": $BUNDLE_BYTES,
    },
    "created": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
}
open(sys.argv[1], "w").write(json.dumps(manifest, indent=2) + "\n")
PYEOF

log "完成："
ls -la "$OUT"
log "bundle sha256: $BUNDLE_SHA"
