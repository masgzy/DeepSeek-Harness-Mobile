package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.Downloader
import io.github.masgzy.dshm.util.TarExtractor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 预装运行时更新管理器（双轨供应之「预装」通道）：
 * 从本项目 Release 下载 CI 构建的预装 bundle（Ubuntu 26.04 + Node + dsh），
 * manifest.json 驱动 + SHA-256 锁定 + 暂存目录校验后原子替换，失败自动回滚。
 */
class UpdateManager(private val filesDir: File) {

  private val rootfsManager = RootfsManager(filesDir)

  data class Manifest(
    val version: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val ubuntu: String,
    val node: String,
    val dsh: String,
  )

  /** 拉取最新运行时清单（默认指向本仓库 runtime-nightly 预发布）。 */
  fun fetchManifest(manifestUrl: String = DEFAULT_MANIFEST_URL): Manifest {
    val text = httpGet(manifestUrl)
    val json = JSONObject(text)
    val bundle = json.getJSONObject("bundle")
    val runtime = json.getJSONObject("runtime")
    return Manifest(
      version = bundle.getString("version"),
      url = bundle.getString("url"),
      sha256 = bundle.getString("sha256"),
      bytes = bundle.optLong("bytes", -1L),
      ubuntu = runtime.optString("ubuntu", ""),
      node = runtime.optString("node", ""),
      dsh = runtime.optString("dsh", ""),
    )
  }

  /**
   * 下载并安装预装运行时。阻塞运行，后台线程调用。
   * @return true 成功。
   */
  fun applyRuntimeBundle(
    manifest: Manifest,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
  ): Boolean {
    val archive = File(Paths.cache(filesDir), "dsh-runtime-${manifest.version}.tar.xz")
    onProgress("下载预装运行时 ${manifest.version}", 0, 1)
    Downloader.download(listOf(manifest.url), archive, manifest.sha256) { done, total ->
      onProgress("下载预装运行时 ${manifest.version}", done, total)
    }

    onProgress("校验并解压运行时", 0, 1)
    val staging = File(filesDir, Paths.ROOTFS_DIR + ".staging")
    staging.deleteRecursively()
    staging.mkdirs()
    if (!TarExtractor.extract(archive, staging)) {
      staging.deleteRecursively()
      AppLog.log("update", "预装运行时解压失败")
      return false
    }
    // 校验：预装 bundle 必须自带 dsh 安装标记
    if (!File(staging, "root/.dshm/installed").isFile) {
      staging.deleteRecursively()
      AppLog.log("update", "预装运行时缺少 dsh 标记，拒绝启用")
      return false
    }

    val bak = File(filesDir, Paths.ROOTFS_DIR + ".bak")
    bak.deleteRecursively()
    if (rootfsManager.rootfsDir.exists()) rootfsManager.rootfsDir.renameTo(bak)
    if (!staging.renameTo(rootfsManager.rootfsDir)) {
      // 回滚
      bak.renameTo(rootfsManager.rootfsDir)
      AppLog.log("update", "启用新运行时失败，已回滚")
      return false
    }
    bak.deleteRecursively()
    AppLog.log("update", "预装运行时已启用: ${manifest.version}")
    onProgress("预装运行时 ${manifest.version} 就绪", 1, 1)
    return true
  }

  private fun httpGet(url: String): String {
    var conn: HttpURLConnection? = null
    return try {
      conn = URL(url).openConnection() as HttpURLConnection
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000
      conn.instanceFollowRedirects = true
      val code = conn.responseCode
      if (code !in 200..299) throw RuntimeException("HTTP $code: $url")
      conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn?.disconnect()
    }
  }

  companion object {
    /** runtime.yml 会把 manifest.json 发布到 runtime-nightly 预发布的资产里。 */
    const val DEFAULT_MANIFEST_URL =
      "https://github.com/masgzy/DeepSeek-Harness-Mobile/releases/download/runtime-nightly/manifest.json"
  }
}
