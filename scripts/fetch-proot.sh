#!/usr/bin/env bash
# =====================================================================
# fetch-proot.sh —— 获取 Termux proot 运行三件套并注入 APK assets
#
# 从 Termux 官方软件源下载当前版本（或环境变量指定版本）的：
#   proot / libtalloc / libandroid-shmem（aarch64）
# 解包后放入 app/src/main/assets/proot/arm64-v8a/，供 APK 运行时提取。
#
# 用法：
#   ./scripts/fetch-proot.sh
#   PROOT_VER=5.1.107.99 TALLOC_VER=2.5.0 SHMEM_VER=0.7 ./scripts/fetch-proot.sh
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/proot/arm64-v8a"
BASE="${TERMUX_POOL_BASE:-https://packages.termux.dev/apt/termux-main/pool/main}"
ABI="aarch64"

PROOT_VER="${PROOT_VER:-5.1.107.99}"
TALLOC_VER="${TALLOC_VER:-2.5.0}"
SHMEM_VER="${SHMEM_VER:-0.7}"

log() { echo "[fetch-proot] $*"; }
fail() { echo "[fetch-proot][FAIL] $*" >&2; exit 1; }

command -v curl >/dev/null || fail "需要 curl"
command -v ar >/dev/null || fail "需要 binutils (ar)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$DEST" "$WORK/x"

fetch_deb() {
  local name="$1" url="$2"
  log "下载 $name: $url"
  curl -sfL --retry 3 --connect-timeout 20 -o "$WORK/$name.deb" "$url" \
    || fail "下载失败: $url"
}

# ---- 下载三个 deb 包 ----
fetch_deb proot "$BASE/p/proot/proot_${PROOT_VER}_${ABI}.deb"
fetch_deb talloc "$BASE/libt/libtalloc/libtalloc_${TALLOC_VER}_${ABI}.deb"
fetch_deb shmem "$BASE/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER}_${ABI}.deb"

# ---- 解包（兼容 data.tar.xz / data.tar.zst） ----
log "解包 deb ..."
for d in proot talloc shmem; do
  (cd "$WORK" && ar x "$d.deb")
  DATA="$(cd "$WORK" && ls data.tar.* 2>/dev/null | head -1 || true)"
  [ -n "$DATA" ] || fail "deb 内无 data.tar"
  tar -xf "$WORK/$DATA" -C "$WORK/x"
  rm -f "$WORK/$DATA"
done

PREFIX="$WORK/x/data/data/com.termux/files"
PROOT_BIN="$PREFIX/usr/bin/proot"
TALLOC_LIB="$PREFIX/usr/lib/libtalloc.so.2"
SHMEM_LIB="$PREFIX/usr/lib/libandroid-shmem.so"

for f in "$PROOT_BIN" "$TALLOC_LIB" "$SHMEM_LIB"; do
  [ -f "$f" ] || fail "缺少文件: $f"
  head -c 4 "$f" | od -An -tx1 | grep -q "7f 45 4c 46" || fail "非 ELF: $f"
done

# ---- 注入 assets ----
log "写入 $DEST"
install -m 0755 "$PROOT_BIN" "$DEST/proot"
install -m 0644 "$TALLOC_LIB" "$DEST/libtalloc.so.2"
install -m 0644 "$SHMEM_LIB" "$DEST/libandroid-shmem.so"

log "完成。文件清单与 SHA-256："
( cd "$DEST" && ls -la && sha256sum proot libtalloc.so.2 libandroid-shmem.so )
