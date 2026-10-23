package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.Downloader
import io.github.masgzy.dshm.util.Hashing
import io.github.masgzy.dshm.util.TarExtractor
import java.io.File

/**
 * 官方 Ubuntu 26.04 rootfs 获取器：
 * 下载 ubuntu-base（官方 cdimage / 清华 TUNA 双镜像）→ SHA-256 校验 →
 * 解压到暂存目录 → 原子改名启用。
 */
class RootfsManager(private val filesDir: File) {

  val rootfsDir: File get() = Paths.rootfs(filesDir)
  private val stagingDir: File get() = File(filesDir, Paths.ROOTFS_DIR + ".staging")
  private val cacheDir: File get() = Paths.cache(filesDir)

  val isRootfsReady: Boolean get() = Paths.rootfsUsable(rootfsDir)

  /**
   * 下载并安装官方 rootfs。幂等：已就绪时直接返回 true。
   * @throws java.io.IOException 下载/校验/解压失败。
   */
  fun install(onProgress: (step: String, done: Long, total: Long) -> Unit): Boolean {
    if (isRootfsReady) return true
    val archive = File(cacheDir, Paths.UBUNTU_BASE_NAME)
    val cachedHash = if (archive.isFile) Hashing.sha256Hex(archive) else null
    val needDownload = cachedHash == null ||
      !Hashing.equalsHex(Paths.UBUNTU_BASE_SHA256, cachedHash)
    if (needDownload) {
      onProgress("下载 Ubuntu ${Paths.UBUNTU_RELEASE} rootfs", 0, 1)
      Downloader.download(
        Paths.UBUNTU_MIRRORS, archive, Paths.UBUNTU_BASE_SHA256,
      ) { done, total -> onProgress("下载 Ubuntu ${Paths.UBUNTU_RELEASE} rootfs", done, total) }
    }

    onProgress("解压 rootfs", 0, 1)
    stagingDir.deleteRecursively()
    stagingDir.mkdirs()
    val ok = TarExtractor.extract(archive, stagingDir) { count -> onProgress("解压 rootfs", count, 0) }
    AppLog.log("rootfs", "staging 解压 ok=$ok entries=${stagingDir.listFiles()?.size ?: 0}")
    if (!ok) {
      stagingDir.deleteRecursively()
      throw java.io.IOException("rootfs 解压失败")
    }

    // 原子启用：老 rootfs 换名备份，成功后删除
    if (rootfsDir.exists()) {
      val bak = File(filesDir, Paths.ROOTFS_DIR + ".bak")
      bak.deleteRecursively()
      rootfsDir.renameTo(bak)
    }
    if (!stagingDir.renameTo(rootfsDir)) {
      throw java.io.IOException("rootfs 暂存目录改名失败")
    }
    AppLog.log("rootfs", "rootfs 就绪: ${rootfsDir.absolutePath}")
    onProgress("rootfs 就绪", 1, 1)
    return true
  }

  /** 彻底移除容器（设置页「卸载容器」）。 */
  fun uninstall() {
    rootfsDir.deleteRecursively()
    stagingDir.deleteRecursively()
    File(filesDir, Paths.ROOTFS_DIR + ".bak").deleteRecursively()
    Paths.cache(filesDir).deleteRecursively()
    AppLog.log("rootfs", "容器已卸载")
  }
}
