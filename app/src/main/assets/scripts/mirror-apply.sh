#!/bin/sh
# =====================================================================
# DeepSeek-Harness-Mobile 容器内镜像源应用脚本
# 由宿主 APK 在「镜像测速」后通过 proot 执行，把测速择优的镜像源
# 即时写入已安装容器的 /etc/apt（Debian/Ubuntu）与 /etc/apk（Alpine）。
#
# 由宿主 bind 注入：/dshm/mirror-apply.sh（本脚本）
# 环境变量（由宿主注入，经 proot env 传递）：
#   DSHM_APT_MIRROR  apt 镜像站基址（scheme://host；空 = 不改 apt 源）
#   ALPINE_MIRROR    apk 镜像基址（scheme://host/alpine；空 = 不改 apk 源）
# =====================================================================
set -u

log() { echo "[dshm-mirror] $*"; }

# ---- apt（Debian/Ubuntu） ----
if [ -n "${DSHM_APT_MIRROR:-}" ]; then
  M="$DSHM_APT_MIRROR"
  log "apt 源 → $M"
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
      log "  已更新 $f"
    fi
  done
  if [ -f /etc/apt/sources.list ]; then
    sed -i "s#http://archive.ubuntu.com#$M#g; \
            s#https://archive.ubuntu.com#$M#g; \
            s#http://deb.debian.org#$M#g; \
            s#https://deb.debian.org#$M#g; \
            s#http://security.ubuntu.com#$M#g; \
            s#http://security.debian.org#$M#g" /etc/apt/sources.list 2>/dev/null || true
    log "  已更新 /etc/apt/sources.list"
  fi
fi

# ---- apk（Alpine） ----
if [ -n "${ALPINE_MIRROR:-}" ] && [ -d /etc/apk ]; then
  ALPINE_BRANCH="v$(cut -d. -f1,2 /etc/alpine-release 2>/dev/null || echo 3.22)"
  mkdir -p /etc/apk
  printf '%s\n' "$ALPINE_MIRROR/$ALPINE_BRANCH/main" "$ALPINE_MIRROR/$ALPINE_BRANCH/community" > /etc/apk/repositories
  log "apk 源 → $ALPINE_MIRROR/$ALPINE_BRANCH"
fi

log "镜像源应用完成"
exit 0
