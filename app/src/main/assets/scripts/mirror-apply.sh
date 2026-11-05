#!/bin/sh
# =====================================================================
# DeepSeek-Harness-Mobile 容器内镜像源应用脚本
# 由宿主 APK 在「镜像测速」后通过 proot 执行，把测速择优的镜像源
# 即时写入已安装容器的 /etc/apt（Debian/Ubuntu）与 /etc/apk（Alpine）。
#
# 由宿主 bind 注入：/dshm/mirror-apply.sh（本脚本）
# 环境变量（由宿主注入，经 proot env 传递）：
#   DSHM_APT_MIRROR    apt 镜像站基址（scheme://host；空 = 不改 apt 源）
#   ALPINE_MIRROR      apk 镜像基址（scheme://host/alpine；空 = 不改 apk 源）
#   DSHM_MIRROR_REVERT 置 1 = 回滚模式：把上次写入前的 .dshm-bak 备份还原
#                      （RuntimeTrial 验证新源拉不到索引时由宿主触发）
#
# RuntimeTrial 配套：改写任何配置文件前先留 .dshm-bak 备份，验证失败可完整
# 还原（换源失败的用户拿到的仍是换源前能用的容器）。
# =====================================================================
set -u

log() { echo "[dshm-mirror] $*"; }

# ---- 回滚模式：还原 .dshm-bak ----
if [ -n "${DSHM_MIRROR_REVERT:-}" ]; then
  restored=0
  for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/debian.sources \
           /etc/apt/sources.list /etc/apk/repositories; do
    if [ -f "$f.dshm-bak" ]; then
      cp -p "$f.dshm-bak" "$f" && rm -f "$f.dshm-bak"
      log "已还原 $f"
      restored=$((restored + 1))
    fi
  done
  if [ "$restored" -eq 0 ]; then log "无备份可还原（此前未改写过源）"; fi
  log "镜像源回滚完成"
  exit 0
fi

# ---- apt（Debian/Ubuntu） ----
if [ -n "${DSHM_APT_MIRROR:-}" ]; then
  M="$DSHM_APT_MIRROR"
  log "apt 源 → $M"
  for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list.d/debian.sources; do
    if [ -f "$f" ]; then
      cp -p "$f" "$f.dshm-bak" 2>/dev/null || true
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
    cp -p /etc/apt/sources.list /etc/apt/sources.list.dshm-bak 2>/dev/null || true
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
  [ -f /etc/apk/repositories ] && cp -p /etc/apk/repositories /etc/apk/repositories.dshm-bak 2>/dev/null || true
  printf '%s\n' "$ALPINE_MIRROR/$ALPINE_BRANCH/main" "$ALPINE_MIRROR/$ALPINE_BRANCH/community" > /etc/apk/repositories
  log "apk 源 → $ALPINE_MIRROR/$ALPINE_BRANCH"
fi

log "镜像源应用完成"
exit 0
