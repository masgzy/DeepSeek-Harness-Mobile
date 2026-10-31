#!/usr/bin/env bash
# =====================================================================
# fetch-proot.sh —— 获取 Termux proot 运行四件套并注入 APK jniLibs
#
# 为什么是 jniLibs 而不是 assets：Android 10 起 targetSdk >= 29 的应用被
# SELinux W^X 策略禁止 exec 应用数据目录（files/、no_backup/ 等）中的 ELF，
# 上一版「assets 解压到 files/proot/ 再 exec」在真机上必然得到
#   Cannot run program ".../files/proot/proot": error=13, Permission denied
# 而 nativeLibraryDir（APK lib/<abi>/ 解压产物，随包签名信任）不受该限制，
# 这正是 dphmoblie/deepseek-harness-android（targetSdk 35）验证过的方案。
#
# 产出（app/src/main/jniLibs/arm64-v8a/，文件名必须 lib*.so 才会被解压）：
#   libdsh_proot.so         <- Termux usr/bin/proot（proot 本体）
#   libdsh_proot_loader.so  <- Termux usr/libexec/proot/loader（静态 ELF，
#                              经 PROOT_LOADER 注入；访客 execve 会被 proot
#                              改写为对该 loader 的 execve，loader 再以
#                              mmap 方式装载真正的访客 ELF，绕开 W^X）
#   libtalloc.so            <- Termux usr/lib/libtalloc.so.2（重命名）
#   libandroid-shmem.so     <- 原样
# 另：proot 本体的 DT_NEEDED 中的 "libtalloc.so.2" 被同长度补丁为
#   "libtalloc.so"（jniLibs 只接受 lib*.so 命名），配合宿主侧
#   LD_LIBRARY_PATH=<nativeLibraryDir> 完成依赖解析。
#
# 用法：
#   ./scripts/fetch-proot.sh
#   PROOT_VER_FORCE=5.1.107.101 TALLOC_VER_FORCE=2.5.0 SHMEM_VER_FORCE=0.7 ./scripts/fetch-proot.sh
# （不设 *_FORCE 时自动从 Termux 索引解析当前版本；TALLOC_VER/SHMEM_VER 仅作
#   索引解析失败后的内置回退值）
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
BASE="${TERMUX_POOL_BASE:-https://packages.termux.dev/apt/termux-main/pool/main}"
ABI="aarch64"

PROOT_VER="${PROOT_VER:-5.1.107.101}"
TALLOC_VER="${TALLOC_VER:-2.5.0}"
SHMEM_VER="${SHMEM_VER:-0.7}"

log() { echo "[fetch-proot] $*"; }
fail() { echo "[fetch-proot][FAIL] $*" >&2; exit 1; }

command -v curl >/dev/null || fail "需要 curl"
command -v python3 >/dev/null || fail "需要 python3（DT_NEEDED 补丁）"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$DEST" "$WORK/x"

# 下载源：Termux 官方池 + 清华 TUNA 镜像（2026-10 官方池多次抽风——本地与
# GitHub runner 均出现长时间无响应，镜像回退成为必选项）。
# TERMUX_POOL_BASE 仍可显式覆盖（如本地走代理时指定其他镜像）。
SOURCES=("$BASE" "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/pool/main")

fetch_deb() {
  local name="$1" relpath="$2"
  for src in "${SOURCES[@]}"; do
    log "下载 $name: $src/$relpath"
    if curl -sfL --retry 3 --connect-timeout 20 -o "$WORK/$name.deb" "$src/$relpath"; then
      return 0
    fi
    log "源不可用，尝试下一个: $name ($src)"
  done
  fail "下载失败（已尝试 ${#SOURCES[@]} 个源）: $relpath"
}

# ---- 动态解析 deb 路径（Termux 池会清除旧版本 deb，硬编码版本号随时 404） ----
# 从 apt 索引 dists/stable/main/binary-aarch64/Packages 解析三个包的当前
# pool 相对路径；全部源失败时回退内置已知版本（仍可用 *_VER 环境变量覆盖）。
resolve_paths() {
  local src idx
  for src in "${SOURCES[@]}"; do
    idx="${src%/pool/main}/dists/stable/main/binary-aarch64/Packages"
    log "解析包索引: $idx"
    if ! curl -sfL --retry 2 --connect-timeout 20 --max-time 120 -o "$WORK/Packages" "$idx"; then
      log "索引不可用，尝试下一个源"
      continue
    fi
    mapfile -t rels < <(python3 - "$WORK/Packages" <<'PYEOF'
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = ["proot", "libtalloc", "libandroid-shmem"]
found = {}
for blk in text.split("\n\n"):
    pkg = fn = None
    for line in blk.splitlines():
        if line.startswith("Package: "):
            pkg = line[9:].strip()
        elif line.startswith("Filename: "):
            fn = line[10:].strip()
    if pkg in want and pkg not in found:
        found[pkg] = fn
for k in want:
    rel = found.get(k, "")
    # 索引的 Filename 相对仓库根（含 pool/main/ 前缀），而下载源已含 pool/main
    if rel.startswith("pool/main/"):
        rel = rel[len("pool/main/"):]
    print(rel)
PYEOF
)
    if [ -n "${rels[0]:-}" ] && [ -n "${rels[1]:-}" ] && [ -n "${rels[2]:-}" ]; then
      PROOT_REL="${rels[0]}"
      TALLOC_REL="${rels[1]}"
      SHMEM_REL="${rels[2]}"
      return 0
    fi
    log "索引中缺少目标包，尝试下一个源"
  done
  return 1
}

PROOT_REL=""
TALLOC_REL=""
SHMEM_REL=""
if resolve_paths; then
  log "已解析到当前版本: $PROOT_REL | $TALLOC_REL | $SHMEM_REL"
else
  log "动态解析失败，回退内置版本: proot=$PROOT_VER talloc=$TALLOC_VER shmem=$SHMEM_VER"
  PROOT_REL="p/proot/proot_${PROOT_VER}_${ABI}.deb"
  TALLOC_REL="libt/libtalloc/libtalloc_${TALLOC_VER}_${ABI}.deb"
  SHMEM_REL="liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER}_${ABI}.deb"
fi
# 显式环境变量仍可强制指定版本（如锁版本复现）
if [ -n "${PROOT_VER_FORCE:-}" ]; then PROOT_REL="p/proot/proot_${PROOT_VER_FORCE}_${ABI}.deb"; fi
if [ -n "${TALLOC_VER_FORCE:-}" ]; then TALLOC_REL="libt/libtalloc/libtalloc_${TALLOC_VER_FORCE}_${ABI}.deb"; fi
if [ -n "${SHMEM_VER_FORCE:-}" ]; then SHMEM_REL="liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER_FORCE}_${ABI}.deb"; fi

# ---- 下载三个 deb 包 ----
fetch_deb proot "$PROOT_REL"
fetch_deb talloc "$TALLOC_REL"
fetch_deb shmem "$SHMEM_REL"

# ---- 解包（dpkg-deb 优先，回退 ar + tar，兼容 data.tar.xz / data.tar.zst） ----
log "解包 deb ..."
for d in proot talloc shmem; do
  mkdir -p "$WORK/x/$d"
  if command -v dpkg-deb >/dev/null; then
    dpkg-deb -x "$WORK/$d.deb" "$WORK/x/$d"
  else
    command -v ar >/dev/null || fail "需要 dpkg-deb 或 binutils (ar)"
    (cd "$WORK" && ar x "$d.deb")
    DATA="$(cd "$WORK" && ls data.tar.* 2>/dev/null | head -1 || true)"
    [ -n "$DATA" ] || fail "deb 内无 data.tar"
    tar -xf "$WORK/$DATA" -C "$WORK/x/$d"
    rm -f "$WORK/$DATA"
  fi
done

PREFIX="$WORK/x/proot/data/data/com.termux/files"
PROOT_BIN="$PREFIX/usr/bin/proot"
PROOT_LOADER="$PREFIX/usr/libexec/proot/loader"
TALLOC_LIB="$WORK/x/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2"
SHMEM_LIB="$WORK/x/shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so"

for f in "$PROOT_BIN" "$PROOT_LOADER" "$TALLOC_LIB" "$SHMEM_LIB"; do
  [ -f "$f" ] || fail "缺少文件: $f"
  head -c 4 "$f" | od -An -tx1 | grep -q "7f 45 4c 46" || fail "非 ELF: $f"
done

# ---- DT_NEEDED 补丁：libtalloc.so.2 -> libtalloc.so（同长度，NUL 填充） ----
log "补丁 proot DT_NEEDED: libtalloc.so.2 -> libtalloc.so"
python3 - "$PROOT_BIN" <<'PYEOF'
import sys

path = sys.argv[1]
with open(path, "rb") as fh:
    blob = fh.read()

old = b"libtalloc.so.2\x00"
new = b"libtalloc.so\x00\x00\x00"
assert len(old) == len(new), "补丁必须同长度"

count = blob.count(old)
if count == 0:
    print("[fetch-proot] proot 未依赖 libtalloc.so.2（可能已静态链接），跳过补丁")
elif count == 1:
    with open(path, "wb") as fh:
        fh.write(blob.replace(old, new))
    print("[fetch-proot] DT_NEEDED 补丁完成（1 处）")
else:
    sys.exit(f"[fetch-proot][FAIL] libtalloc.so.2 出现 {count} 次，拒绝盲目补丁")
PYEOF

# ---- 注入 jniLibs ----
log "写入 $DEST"
install -m 0644 "$PROOT_BIN" "$DEST/libdsh_proot.so"
install -m 0644 "$PROOT_LOADER" "$DEST/libdsh_proot_loader.so"
install -m 0644 "$TALLOC_LIB" "$DEST/libtalloc.so"
install -m 0644 "$SHMEM_LIB" "$DEST/libandroid-shmem.so"

log "完成。文件清单与 SHA-256："
( cd "$DEST" && ls -la && sha256sum libdsh_proot.so libdsh_proot_loader.so libtalloc.so libandroid-shmem.so )
