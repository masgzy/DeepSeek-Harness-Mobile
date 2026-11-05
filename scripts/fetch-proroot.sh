#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# fetch-proroot.sh — 注入 proroot 运行时（proot 之外的第二个容器运行时）到 jniLibs
#
# proroot（coderredlab/proroot）是 LD_PRELOAD + 进程内路径翻译的容器运行时，
# 零 ptrace 开销，引擎启动/文件 I/O 明显快于 proot。二进制为专有许可、
# 无公开源码可重编，与 proot 四件套一样以 jniLibs 形式随 APK 分发
# （运行时按需选用，缺件时 ProotRuntime.runnerAvailable() 会如实报告不可用）。
#
# 来源：DSH-APP/DSHA 公开仓库随包分发的同一批字节（abi.lock.json 锁定版本
# proroot v1.2.8 + 两个自研兼容 shim），逐件 SHA-256 校验后才落位 ——
# 上游仓库 main 分支若更新，哈希不符会直接失败，不会引入未知字节。
#
# 兼容 shim（DSHA 自研，Apache-2.0）：
#  - libdsha-proroot-compat.so  glibc sysconf(75/249/250) 代理，
#    修复 Android 13+「Fatal glibc error: sigstksz」断言崩溃
#  - libdsha-proroot-linker.so  无依赖入口适配器，把 shim 以 --preload
#    接进上游加载器并保持 runtime 直接预加载
#
# 与 fetch-proot.sh 的差异：proroot 是可选实验运行时 —— 下载失败只告警
# 不阻断构建（proot 仍可用），下次构建自动重试。
# 跳过手段：PROOT_SKIP_FETCH=1（与 proot 共用开关）。
# ---------------------------------------------------------------------------
set -uo pipefail

SRC_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$SRC_DIR"

# name|sha256
ASSETS=(
  "libproroot.so|a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01"
  "libproroot-runtime.so|8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34"
  "libproroot-linker.so|51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee"
  "libproroot-stub-loader.so|06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0"
  "libproroot-bridge.so|1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f"
  "libdsha-proroot-compat.so|c693728a15bfef86eb52731517da2d06e67474459eeb883e31a4fa75dff0592d"
  "libdsha-proroot-linker.so|26428de01fb2638cb0293469013cabfac8919f6df772bc7d1b6b0506d8b501d7"
)

# 镜像优先级：jsdelivr（对 raw.githubusercontent 的 CDN，CI 与本地都稳）→ GitHub raw
REF="DSH-APP/DSHA@main"
RELPATH="app/src/main/jniLibs/arm64-v8a"
MIRRORS=(
  "https://cdn.jsdelivr.net/gh/${REF}/${RELPATH}"
  "https://raw.githubusercontent.com/DSH-APP/DSHA/main/${RELPATH}"
)

fetch_one() {
  local name="$1" dest="$2" url
  for url in "${MIRRORS[@]}"; do
    echo "[fetch-proroot] GET $url/$name"
    if curl -fsSL --connect-timeout 15 --max-time 120 --retry 2 \
        -o "$dest.part" "$url/$name"; then
      mv "$dest.part" "$dest"
      return 0
    fi
    rm -f "$dest.part"
  done
  return 1
}

verify_one() {
  local file="$1" want="$2" got
  got=$(sha256sum "$file" | awk '{print $1}')
  if [ "$got" != "$want" ]; then
    echo "[fetch-proroot] SHA-256 不符: $file" >&2
    echo "  期望 $want" >&2
    echo "  实得 $got" >&2
    return 1
  fi
  return 0
}

fail_count=0
for entry in "${ASSETS[@]}"; do
  name="${entry%%|*}"
  want="${entry##*|}"
  dest="$SRC_DIR/$name"

  # 幂等：已存在且校验通过即跳过
  if [ -f "$dest" ]; then
    if verify_one "$dest" "$want"; then
      echo "[fetch-proroot] 已就位: $name"
      continue
    fi
    echo "[fetch-proroot] 现有文件校验失败，重新拉取: $name"
    rm -f "$dest"
  fi

  if ! fetch_one "$name" "$dest"; then
    echo "[fetch-proroot] 警告: $name 下载失败（proroot 为可选运行时，构建继续）" >&2
    rm -f "$dest"
    fail_count=$((fail_count + 1))
    continue
  fi
  if ! verify_one "$dest" "$want"; then
    rm -f "$dest"
    fail_count=$((fail_count + 1))
    continue
  fi
  echo "[fetch-proroot] 注入完成: $name"
done

if [ "$fail_count" -gt 0 ]; then
  echo "[fetch-proroot] 完成（$fail_count 件缺失，proroot 运行时将不可选，proot 不受影响）" >&2
else
  echo "[fetch-proroot] 全部七件就位（proroot v1.2.8 + 双 shim，字节锁定）"
fi
exit 0
