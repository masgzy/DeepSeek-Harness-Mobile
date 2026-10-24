package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.Downloader
import io.github.masgzy.dshm.util.Hashing
import io.github.masgzy.dshm.util.TarExtractor
import java.io.File
import java.io.IOException

/**
 * rootfs 获取器（发行版感知）：
 *
 *  1. cdimage 直线路径：发行方 base tar.gz（Ubuntu 26.04 ubuntu-base，官方 cdimage +
 *     清华 TUNA 双镜像，SHA-256 锁定）——体积最小、无需 registry 协议；
 *  2. registry 路径：任意 Docker 引用经 [DockerRegistryClient] 拉取
 *     （manifest → arm64 子清单 → 逐层下载/校验/解包），国内镜像源自动回退。
 *
 * 统一产出：rootfs 暂存目录解包完成 → 原子改名启用（老 rootfs 备份后删除）。
 */
class RootfsManager(private val filesDir: File) {

  val rootfsDir: File get() = Paths.rootfs(filesDir)
  private val stagingDir: File get() = File(filesDir, Paths.ROOTFS_DIR + ".staging")
  private val cacheDir: File get() = Paths.cache(filesDir)

  val isRootfsReady: Boolean get() = Paths.rootfsUsable(rootfsDir)

  /**
   * 下载并安装所选发行版的 rootfs。幂等：已就绪时直接返回 true。
   * @param distro 当前选择的容器系统（[DistroCatalog.resolve] 的产物）。
   * @throws IOException 下载/校验/解压失败。
   */
  fun install(
    distro: Distro,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
    onLog: (String) -> Unit,
  ): Boolean {
    if (isRootfsReady) return true
    val staging = stagingDir
    staging.deleteRecursively()
    staging.mkdirs()

    when {
      // ① cdimage 直链优先（Ubuntu 26.04），失败回退 registry
      distro.cdimage != null -> {
        val ok = runCatching { installCdimage(distro, staging, onProgress) }
          .onFailure { AppLog.log("rootfs", "cdimage 路径失败，回退 registry: ${distro.imageRef}", it) }
          .isSuccess
        if (!ok) {
          onLog("cdimage 直链不可用，改从 Docker registry 拉取 ${distro.fallbackImageRef()}")
          pullRegistry(distro.fallbackImageRef(), staging, distro.arch, onProgress, onLog)
        }
      }
      // ② 仅 registry 路径
      else -> pullRegistry(distro.imageRef, staging, distro.arch, onProgress, onLog)
    }

    // 原子启用：老 rootfs 换名备份，成功后删除
    if (rootfsDir.exists()) {
      val bak = File(filesDir, Paths.ROOTFS_DIR + ".bak")
      bak.deleteRecursively()
      rootfsDir.renameTo(bak)
    }
    if (!staging.renameTo(rootfsDir)) {
      throw IOException("rootfs 暂存目录改名失败")
    }
    AppLog.log("rootfs", "rootfs 就绪: ${rootfsDir.absolutePath} (${distro.label})")
    onProgress("rootfs 就绪", 1, 1)
    return true
  }

  private fun Distro.fallbackImageRef(): String = cdimage?.fallbackRef ?: imageRef

  /** cdimage 直链路径：下载 tar.gz → SHA-256 校验 → 解包到 staging。 */
  private fun installCdimage(
    distro: Distro,
    staging: File,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
  ) {
    val cd = requireNotNull(distro.cdimage) { "distro 无 cdimage 引用" }
    val archive = File(cacheDir, cd.fileName)
    val cachedHash = if (archive.isFile) Hashing.sha256Hex(archive) else null
    val needDownload = cachedHash == null || !Hashing.equalsHex(cd.sha256, cachedHash)
    if (needDownload) {
      onProgress("下载 ${distro.label} rootfs", 0, 1)
      Downloader.download(cd.mirrors, archive, cd.sha256) { done, total ->
        onProgress("下载 ${distro.label} rootfs", done, total)
      }
    }
    onProgress("解压 rootfs", 0, 1)
    val ok = TarExtractor.extract(archive, staging) { count -> onProgress("解压 rootfs", count, 0) }
    AppLog.log("rootfs", "cdimage staging 解压 ok=$ok entries=${staging.listFiles()?.size ?: 0}")
    if (!ok) {
      staging.deleteRecursively()
      throw IOException("rootfs 解压失败")
    }
  }

  /** registry 路径：Docker Registry v2 逐层拉取解包。 */
  private fun pullRegistry(
    imageRef: String,
    staging: File,
    arch: String,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
    onLog: (String) -> Unit,
  ) {
    staging.deleteRecursively()
    staging.mkdirs()
    DockerRegistryClient(cacheDir, onLog).pull(imageRef, staging, arch, onProgress)
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
