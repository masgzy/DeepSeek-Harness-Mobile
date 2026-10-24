package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.Downloader
import java.io.IOException
import java.net.URL
import org.json.JSONObject

/**
 * LXC 镜像源（images.linuxcontainers.org）simplestreams 解析器。
 *
 * Debian 官方不提供 ubuntu-base 那样的最小 rootfs 直链，这里改用 LXC 镜像源
 * （Canonical 维护，每日构建，proot-distro 同源）：
 *
 *   1. 拉取 simplestreams 索引 images.json（约 1MB，含每个镜像的权威 SHA-256）；
 *   2. 按 "<suite>:arm64:default" 选最新日期版本的 rootfs.tar.xz 条目；
 *   3. 用 [Downloader] 从多镜像（TUNA → USTC → 官方）下载并按索引内 SHA-256 校验。
 *
 * 索引来源镜像与下载镜像同源镜像列表（官方源兜底）：
 *   - 清华 TUNA:   https://mirrors.tuna.tsinghua.edu.cn/lxc-images
 *   - 中科大 USTC: https://mirrors.ustc.edu.cn/lxc-images
 *   - 官方:        https://images.linuxcontainers.org
 *
 * 注意：镜像按日构建，SHA-256 每天变化，因此不做硬编码锁定 —— 以索引为准
 * （索引与文件同源镜像分发，完整性等同官方签名链）。
 */
object LxcImages {

  /** LXC 镜像引用（Debian 等无官方 base tarball 的发行版）。 */
  data class Ref(
    /** 发行套件（trixie / bookworm …）。 */
    val suite: String,
    /** 展示名（"Debian trixie (lxc-images)"）。 */
    val describe: String,
  )

  /** 解析结果：多镜像直链 + 索引内锁定的 SHA-256 + 体积。 */
  data class Resolved(
    val urls: List<String>,
    val sha256: String,
    val size: Long,
    val buildId: String,
  )

  /** 镜像站基址（顺序即优先级：国内加速优先，官方兜底）。 */
  private val MIRRORS = listOf(
    "https://mirrors.tuna.tsinghua.edu.cn/lxc-images",
    "https://mirrors.ustc.edu.cn/lxc-images",
    "https://images.linuxcontainers.org",
  )

  private const val INDEX_PATH = "/streams/v1/images.json"

  /**
   * 解析指定套件最新 arm64 default 镜像的 rootfs.tar.xz。
   * 任一镜像站的索引可读即成功；全部失败抛 IOException。
   */
  fun resolve(ref: Ref): Resolved {
    var lastError: Exception? = null
    for (base in MIRRORS) {
      try {
        return resolveFrom(base, ref)
      } catch (e: Exception) {
        AppLog.log("lxc-images", "索引读取失败: $base", e)
        lastError = e
      }
    }
    throw IOException("lxc-images 索引获取失败（${ref.suite}）：${lastError?.message}", lastError)
  }

  private fun resolveFrom(base: String, ref: Ref): Resolved {
    val indexUrl = "$base$INDEX_PATH"
    AppLog.log("lxc-images", "读取 simplestreams 索引: $indexUrl")
    val bytes = Downloader.fetchBytes(indexUrl)
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    val products = root.getJSONObject("products")
    // product 键形如 "debian:trixie:arm64:default"
    val productKey = "debian:${ref.suite}:arm64:default"
    if (!products.has(productKey)) {
      throw IOException("索引缺少 $productKey（镜像站数据不完整？）")
    }
    val product = products.getJSONObject(productKey)
    val versions = product.getJSONObject("versions")
    if (versions.length() == 0) throw IOException("$productKey 无可用版本")
    // 版本键为构建日期 "20261022_05:24"，字典序即时间序
    var latestKey: String? = null
    val vit = versions.keys()
    while (vit.hasNext()) {
      val k = vit.next()
      if (latestKey == null || k > latestKey) latestKey = k
    }
    val latest = versions.getJSONObject(requireNotNull(latestKey))
    val items = latest.getJSONObject("items")
    val names = items.keys()
    while (names.hasNext()) {
      val name = names.next()
      if (name != "root.tar.xz") continue
      val item = items.getJSONObject(name)
      val path = item.getString("path") // "images/debian/trixie/arm64/default/<build>/rootfs.tar.xz"
      val sha256 = item.getString("sha256")
      val size = item.optLong("size", 0L)
      if (!sha256.matches(Regex("^[a-f0-9]{64}$"))) {
        throw IOException("索引内 SHA-256 非法: $sha256")
      }
      return Resolved(
        urls = MIRRORS.map { "$it/$path" },
        sha256 = sha256,
        size = size,
        buildId = requireNotNull(latestKey),
      )
    }
    throw IOException("$productKey 最新版本缺少 rootfs.tar.xz 条目")
  }

  /** 是否为合法套件名（防注入：只允许小写字母数字）。 */
  fun validSuite(s: String): Boolean = s.matches(Regex("^[a-z0-9]+$"))
}
