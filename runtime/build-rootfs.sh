#!/usr/bin/env bash
# =====================================================================
# build-rootfs.sh —— 构建预装运行时 bundle（Ubuntu 26.04 + Node 24 + dsh）
#
# 产物：
#   $OUT/dsh-runtime-ubuntu26.04-arm64-<版本>.tar.xz   预装 rootfs
#   $OUT/manifest.json                                  清单（含 SHA-256）
#
# 适用环境：x86_64 GitHub Actions runner（借助 qemu-user-static + proot
# 交叉执行 arm64 二进制）或原生 arm64 主机。
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

MIRROR_BASE="${MIRROR_BASE:-https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release}"
NODE_DIST="${NODE_DIST:-https://nodejs.org/dist}"
RUNTIME_TAG="${RUNTIME_TAG:-$(date -u +%Y%m%d)}"

log() { echo "[build-rootfs] $*"; }
fail() { echo "[build-rootfs][FAIL] $*" >&2; exit 1; }

ARCH="$(uname -m)"
IS_CROSS="$([ "$ARCH" = "x86_64" ] && echo 1 || echo 0)"

mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---- 0. 依赖检查（x86 交叉时需要 qemu-user-static + proot） ----
if [ "$IS_CROSS" = "1" ]; then
  command -v proot >/dev/null || { sudo apt-get update -y && sudo apt-get install -y proot; }
  QEMU="/usr/bin/qemu-aarch64-static"
  [ -f "$QEMU" ] || { sudo apt-get install -y qemu-user-static; }
  [ -f "$QEMU" ] || fail "qemu-aarch64-static 不可用"
fi

# ---- 1. 下载 ubuntu-base rootfs（官方 + TUNA 双镜像） ----
log "下载 Ubuntu ${UBUNTU_RELEASE_VERSION} base rootfs ..."
curl -sfL --retry 3 -o "$WORK/base.tar.gz" \
  "$MIRROR_BASE/$UBUNTU_BASE_NAME" \
  || curl -sfL --retry 3 -o "$WORK/base.tar.gz" \
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/26.04/release/$UBUNTU_BASE_NAME" \
  || fail "rootfs 下载失败"
echo "$UBUNTU_BASE_SHA256  $WORK/base.tar.gz" | sha256sum -c - || fail "rootfs SHA-256 校验失败"

# ---- 2. 下载 Node.js tarball（官方 + npmmirror 双镜像） ----
log "下载 Node.js $NODE_VERSION ..."
NODE_ARCHIVE="$WORK/node.tar.gz"
curl -sfL --retry 3 -o "$NODE_ARCHIVE" "$NODE_DIST/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz" \
  || curl -sfL --retry 3 -o "$NODE_ARCHIVE" "https://npmmirror.com/mirrors/node/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz" \
  || fail "Node 下载失败"
echo "$NODE_SHA256  $NODE_ARCHIVE" | sha256sum -c - || fail "Node SHA-256 校验失败"

# ---- 3. 解压 rootfs 并注入安装素材 ----
ROOTFS="$WORK/rootfs"
mkdir -p "$ROOTFS"
tar -xzf "$WORK/base.tar.gz" -C "$ROOTFS"
mkdir -p "$ROOTFS/dshm" "$ROOTFS/root"
cp "$NODE_ARCHIVE" "$ROOTFS/dshm/node.tar.gz"
cp "$ROOT/app/src/main/assets/scripts/bootstrap.sh" "$ROOTFS/dshm/bootstrap.sh"
chmod +x "$ROOTFS/dshm/bootstrap.sh"
printf 'nameserver 223.5.5.5\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"

# ---- 4. 容器内执行 bootstrap（x86 用 qemu 模拟 arm64） ----
PROOT_ARGS=(proot -0 -r "$ROOTFS" -b /dev -b /proc -b /sys -w /root)
if [ "$IS_CROSS" = "1" ]; then
  # qemu 静态二进制须在容器内可见
  cp "$QEMU" "$ROOTFS/usr/bin/qemu-aarch64-static"
  PROOT_ARGS+=(-q /usr/bin/qemu-aarch64-static)
fi

log "容器内执行 bootstrap（安装 Node + dsh，qemu 下需数分钟）..."
# 注意：runner 上 apt 安装的是 proot-me 5.x，既不支持 Termux 分支的
# --kill-on-exit，也不支持 `--` 参数分隔符；构建期一次性执行无需
# 清理选项，命令直接跟在选项之后。
env -i HOME=/root \
  PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
  TERM=xterm-256color DEBIAN_FRONTEND=noninteractive \
  DSH_VERSION="$DSH_VERSION" DSH_NPM_REGISTRY="$NPM_REGISTRY" \
  "${PROOT_ARGS[@]}" /bin/bash /dshm/bootstrap.sh

[ -f "$ROOTFS/root/.dshm/installed" ] || fail "安装标记缺失，bootstrap 未完成"

# ---- 5. 清理注入物并打包 ----
rm -rf "$ROOTFS/dshm"
if [ "$IS_CROSS" = "1" ]; then rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"; fi
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"

BUNDLE="$OUT/dsh-runtime-ubuntu26.04-arm64-$RUNTIME_TAG.tar.xz"
log "打包 $BUNDLE ..."
tar -cJf "$BUNDLE" -C "$ROOTFS" .
BUNDLE_SHA="$(sha256sum "$BUNDLE" | awk '{print $1}')"
BUNDLE_BYTES="$(stat -c %s "$BUNDLE")"

# ---- 6. 生成 manifest.json ----
NODE_VER_NUM="${NODE_VERSION#v}"
PROOT_TAG="termux-${PROOT_VER:-5.1.107.99}"
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
