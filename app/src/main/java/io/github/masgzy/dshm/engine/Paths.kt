package io.github.masgzy.dshm.engine

import java.io.File

/**
 * 集中管理容器目录布局与运行时版本常量。
 * 版本升级只改这里 + release.yml 的 tag。
 */
object Paths {

  // ---- 运行时版本（2026-10 验证） ----
  const val UBUNTU_RELEASE = "26.04.1"          // Ubuntu 26.04 LTS (Resolute Raccoon) 首个 point release
  const val UBUNTU_BASE_NAME = "ubuntu-base-26.04.1-base-arm64.tar.gz"
  const val UBUNTU_BASE_SHA256 =
    "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd"

  const val NODE_VERSION = "v24.22.0"           // Node.js 24 LTS (Active LTS 至 2028-04)
  const val NODE_TARBALL = "node-v24.22.0-linux-arm64.tar.gz"
  const val NODE_SHA256 =
    "b7e7991d5407815adfa74a2a9cd4db683552c8ce32ff4a295c22e972849c3f5d"

  const val DSH_VERSION = "0.2.1-rc.2"          // @deepseek-ai/dsh npm latest
  const val DSH_PORT = 3080

  // rootfs 下载镜像：官方 cdimage + 清华 TUNA（国内加速）
  val UBUNTU_MIRRORS = listOf(
    "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/$UBUNTU_BASE_NAME",
    "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/26.04/release/$UBUNTU_BASE_NAME",
  )

  // Node.js 官方 + npmmirror（国内加速）
  val NODE_MIRRORS = listOf(
    "https://nodejs.org/dist/$NODE_VERSION/$NODE_TARBALL",
    "https://npmmirror.com/mirrors/node/$NODE_VERSION/$NODE_TARBALL",
  )

  // npm 默认走国内镜像，可在设置中切回官方源
  const val NPM_REGISTRY_CN = "https://registry.npmmirror.com"
  const val NPM_REGISTRY_OFFICIAL = "https://registry.npmjs.org"

  // ---- 目录布局 ----
  const val ROOTFS_DIR = "rootfs"
  const val PROOT_TMP_DIR = "proot-tmp" // proot 自身临时目录（cacheDir 下）
  const val CACHE_DIR = "cache"
  const val LOGS_DIR = "logs"

  fun rootfs(filesDir: File): File = File(filesDir, ROOTFS_DIR)
  fun cache(filesDir: File): File = File(filesDir, CACHE_DIR)
  fun engineLog(filesDir: File): File = File(filesDir, "$LOGS_DIR/engine.log")
  fun workspace(contextFilesDir: File): File = File(contextFilesDir, "workspace")

  /** rootfs 就绪判定：bash + bootstrap 安装标记同时存在。 */
  fun rootfsUsable(rootfsDir: File): Boolean =
    File(rootfsDir, "usr/bin/bash").isFile

  /** dsh 安装完成标记（由容器内 bootstrap.sh 写入）。 */
  fun dshInstalled(rootfsDir: File): Boolean =
    File(rootfsDir, "root/.dshm/installed").isFile
}
