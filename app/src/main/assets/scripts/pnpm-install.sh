#!/bin/sh
# =====================================================================
# pnpm 独立二进制安装器（get.pnpm.io 官方脚本的大陆网络适配精简版）
#
# 与官方 install.sh 的差异（大陆直连痛点逐项处理）：
#  1. 全程只访问 $PNPM_REGISTRY（默认 npmmirror），不碰 registry.npmjs.org；
#     v11 也不再走 GitHub Releases —— 平台二进制包全部由 npm registry 分发；
#  2. dist-tags 走 /-/package/pnpm/dist-tags 小接口（几百字节），
#     替代官方默认 latest 时下载的完整 packument（十几 MB 未压缩 JSON）；
#  3. 只下载平台二进制一个 tarball —— 官方 v12 会把 @pnpm/exe 与 pnpm
#     两个内容相同的包各下一份，大陆网络下是双倍大文件；
#  4. 元数据请求带 --compressed（有 curl 时），不浪费带宽；
#  5. 保留 dist.integrity（SHA-512）校验，去掉 openssl 依赖（od 转 hex）。
#
# 由宿主 APK 注入容器执行（busybox wget 兼容，无需 curl）：
#   PNPM_REGISTRY      npm registry（宿主测速/设置结果）
#   PNPM_VERSION_LINE  版本线：12（默认）/ 11 / 精确版本（12.15.0）
#   PNPM_HOME          安装目录（默认 /root/.local/share/pnpm）
# 产出：$PNPM_HOME/pnpm + /usr/local/bin/pnpm 符号链接
# =====================================================================
set -eu

PNPM_REGISTRY="${PNPM_REGISTRY:-https://registry.npmmirror.com}"
PNPM_VERSION_LINE="${PNPM_VERSION_LINE:-12}"
PNPM_HOME="${PNPM_HOME:-/root/.local/share/pnpm}"
# 清理末尾斜杠，避免拼出双斜杠 URL
PNPM_REGISTRY="${PNPM_REGISTRY%/}"

log() { echo "[pnpm-install] $*"; }
fail() { echo "[pnpm-install][FAIL] $*" >&2; exit 1; }

# ---- 0. 前置检查 ----
ARCH="$(uname -m)"
[ "$ARCH" = "aarch64" ] || fail "仅支持 arm64（当前 $ARCH）"
TMP="$(mktemp -d /tmp/pnpm-install.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

# fetch <url> <outfile>：curl 优先（--compressed），回退 busybox wget
fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --compressed -o "$2" "$1"
  else
    wget -qO "$2" "$1"
  fi
}

# fetch_stdout <url>：小 JSON 到 stdout
fetch_stdout() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --compressed "$1"
  else
    wget -qO- "$1"
  fi
}

# b64_to_hex <base64>：SHA-512 摘要 base64 -> hex（busybox 可用：od）
b64_to_hex() {
  printf '%s' "$1" | base64 -d 2>/dev/null | od -An -tx1 | tr -d ' \n'
}

# ---- 1. 解析精确版本（major 走 dist-tags 小接口，不下载 packument） ----
VER=""
case "$PNPM_VERSION_LINE" in
  latest)
    log "解析 dist-tags（latest）..."
    TAGS="$(fetch_stdout "$PNPM_REGISTRY/-/package/pnpm/dist-tags")" || fail "dist-tags 拉取失败（registry: $PNPM_REGISTRY）"
    VER="$(printf '%s' "$TAGS" | tr ',{}' '\n' | tr -d '" ' | sed -n 's/^latest://p' | head -n1)"
    ;;
  [1-9]|1[0-9])
    log "解析 dist-tags（latest-$PNPM_VERSION_LINE）..."
    TAGS="$(fetch_stdout "$PNPM_REGISTRY/-/package/pnpm/dist-tags")" || fail "dist-tags 拉取失败（registry: $PNPM_REGISTRY）"
    VER="$(printf '%s' "$TAGS" | tr ',{}' '\n' | tr -d '" ' | sed -n "s/^latest-$PNPM_VERSION_LINE://p" | head -n1)"
    [ -n "$VER" ] || VER="$(printf '%s' "$TAGS" | tr ',{}' '\n' | tr -d '" ' | sed -n 's/^latest://p' | head -n1)"
    ;;
  *)
    # 精确版本直接使用（含 next-12 等 tag 的兜底：交给 registry 版本接口校验）
    VER="$PNPM_VERSION_LINE"
    ;;
esac
[ -n "$VER" ] || fail "无法解析 pnpm 版本（输入: $PNPM_VERSION_LINE）"
log "目标版本 pnpm@$VER"

# ---- 2. 平台二进制包选择（musl/glibc × v12+/v11-） ----
MAJOR="$(printf '%s' "$VER" | cut -d. -f1)"
case "$MAJOR" in
  ''|*[!0-9]*) fail "版本号异常，无法解析主版本: $VER" ;;
esac
IS_MUSL=0
if [ -e /lib/ld-musl-aarch64.so.1 ]; then IS_MUSL=1; fi
if [ "$MAJOR" -ge 12 ]; then
  if [ "$IS_MUSL" = "1" ]; then
    PLATFORM_PKG="@pnpm/exe.linux-arm64-musl"
  else
    PLATFORM_PKG="@pnpm/exe.linux-arm64"
  fi
else
  if [ "$IS_MUSL" = "1" ]; then
    PLATFORM_PKG="@pnpm/linuxstatic-arm64"   # v11- 的 musl 只有静态二进制
  else
    PLATFORM_PKG="@pnpm/linux-arm64"
  fi
fi
ENCODED_PKG="$(printf '%s' "$PLATFORM_PKG" | sed 's#/#%2f#')"
log "平台包: $PLATFORM_PKG（musl=$IS_MUSL major=$MAJOR）"

# ---- 3. 平台包元数据（integrity + tarball 文件名） ----
META_FILE="$TMP/meta.json"
fetch "$PNPM_REGISTRY/$ENCODED_PKG/$VER" "$META_FILE" \
  || fail "平台包元数据拉取失败: $PLATFORM_PKG@$VER"
INTEGRITY_B64="$(tr ',{}' '\n' < "$META_FILE" | grep '"integrity"' | head -n1 | sed 's/.*"integrity":"\([^"]*\)".*/\1/')"
TARBALL_NAME="$(tr ',{}' '\n' < "$META_FILE" | grep '"tarball"' | head -n1 | sed 's/.*\///')"
[ -n "$INTEGRITY_B64" ] || fail "元数据缺少 dist.integrity"
[ -n "$TARBALL_NAME" ] || fail "元数据缺少 dist.tarball"
EXPECT_HEX="$(b64_to_hex "${INTEGRITY_B64#sha512-}")"
[ "${#EXPECT_HEX}" = "128" ] || fail "integrity 解码异常"

# ---- 4. 下载 tarball（单文件，走镜像） ----
TGZ="$TMP/pnpm.tgz"
log "下载 tarball: $TARBALL_NAME ..."
fetch "$PNPM_REGISTRY/$PLATFORM_PKG/-/$TARBALL_NAME" "$TGZ" \
  || fail "tarball 下载失败: $PLATFORM_PKG"

# ---- 5. SHA-512 校验 ----
ACTUAL_HEX="$(sha512sum "$TGZ" | cut -d' ' -f1)"
if [ "$ACTUAL_HEX" != "$EXPECT_HEX" ]; then
  fail "SHA-512 校验失败（actual=$ACTUAL_HEX expect=$EXPECT_HEX）"
fi
log "SHA-512 校验通过"

# ---- 6. 解包安装 ----
mkdir -p "$TMP/unpack"
tar -xzf "$TGZ" -C "$TMP/unpack"
BIN="$(find "$TMP/unpack" -type f -name pnpm | head -n1)"
[ -n "$BIN" ] || fail "tarball 内未找到 pnpm 可执行文件"
mkdir -p "$PNPM_HOME"
cp "$BIN" "$PNPM_HOME/pnpm"
chmod 0755 "$PNPM_HOME/pnpm"
"$PNPM_HOME/pnpm" --version >/dev/null 2>&1 \
  || fail "安装后 pnpm --version 执行失败（架构/ABI 不符？）"
log "pnpm $("$PNPM_HOME/pnpm" --version) 就绪: $PNPM_HOME/pnpm"

# ---- 7. 统一符号链接（保证引擎/插件管理 PATH 可达） ----
mkdir -p /usr/local/bin
ln -sf "$PNPM_HOME/pnpm" /usr/local/bin/pnpm
log "已链接 /usr/local/bin/pnpm -> $PNPM_HOME/pnpm"
