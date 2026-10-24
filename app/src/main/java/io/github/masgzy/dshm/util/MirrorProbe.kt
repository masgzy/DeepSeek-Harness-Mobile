package io.github.masgzy.dshm.util

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * apt / apk 多镜像源延迟测速：对候选镜像站并行发起「首字节」HTTP 探测，
 * 按延迟排序，帮助用户把容器内包管理器切到最快的镜像源。
 *
 * 探测目标（均为各镜像站真实存在的小文件）：
 *  - apt (Debian)：{base}/debian/dists/{suite}/Release（首字节）
 *  - apt (Ubuntu)：{base}/ubuntu/dists/{suite}/Release（首字节）
 *  - apk (Alpine)：{base}/alpine/v{branch}/main/APKINDEX.tar.gz（首字节）
 *
 * 测速在宿主侧进行（镜像站是公网 URL，无需容器）；结果用于：
 *  1. 安装时作为 DSHM_APT_MIRROR / ALPINE_MIRROR 传给 bootstrap；
 *  2. 已安装容器经 mirror-apply.sh 即时改写 /etc/apt、/etc/apk 源。
 */
object MirrorProbe {

  data class Mirror(
    /** 展示名（如 "清华 TUNA"）。 */
    val name: String,
    /** 测速用完整 URL。 */
    val probeUrl: String,
    /** 写入容器用的基址：apt = scheme://host；apk = scheme://host/alpine。 */
    val applyBase: String,
  )

  data class Result(
    val mirror: Mirror,
    /** 首字节延迟毫秒；null = 失败（[error] 给出原因）。 */
    val latencyMs: Long?,
    val error: String? = null,
  )

  private const val CONNECT_TIMEOUT = 5_000
  private const val READ_TIMEOUT = 5_000
  /** 每个镜像探测次数（取最优，抗单次抖动）。 */
  private const val TRIES = 2

  // ---- 候选镜像站（顺序仅影响展示，测速结果决定选用） ----

  private const val TUNA = "https://mirrors.tuna.tsinghua.edu.cn"
  private const val USTC = "https://mirrors.ustc.edu.cn"
  private const val ALIYUN = "https://mirrors.aliyun.com"
  private const val TENCENT = "https://mirrors.cloud.tencent.com"
  private const val HUAWEI = "https://mirrors.huaweicloud.com"
  private const val N163 = "https://mirrors.163.com"

  private val APT_HOSTS = listOf(
    "清华 TUNA" to TUNA,
    "中科大 USTC" to USTC,
    "阿里云" to ALIYUN,
    "腾讯云" to TENCENT,
    "华为云" to HUAWEI,
    "网易 163" to N163,
    "官方源" to "http://archive.ubuntu.com",
  )

  /** apt (Ubuntu) 候选：suite 如 noble / resolute。 */
  fun ubuntuMirrors(suite: String): List<Mirror> = APT_HOSTS.map { (name, host) ->
    val base = if (name == "官方源") "http://archive.ubuntu.com" else host
    Mirror(name, "$base/ubuntu/dists/$suite/Release", base)
  }

  /** apt (Debian) 候选：suite 如 trixie / bookworm。 */
  fun debianMirrors(suite: String): List<Mirror> = listOf(
    "清华 TUNA" to TUNA,
    "中科大 USTC" to USTC,
    "阿里云" to ALIYUN,
    "腾讯云" to TENCENT,
    "华为云" to HUAWEI,
    "网易 163" to N163,
    "官方源" to "http://deb.debian.org",
  ).map { (name, host) ->
    val base = if (name == "官方源") "http://deb.debian.org" else host
    Mirror(name, "$base/debian/dists/$suite/Release", base)
  }

  /** apk (Alpine) 候选：branch 如 3.22。 */
  fun alpineMirrors(branch: String): List<Mirror> = listOf(
    "清华 TUNA" to TUNA,
    "中科大 USTC" to USTC,
    "阿里云" to ALIYUN,
    "腾讯云" to TENCENT,
    "华为云" to HUAWEI,
    "官方源" to "https://dl-cdn.alpinelinux.org",
  ).map { (name, host) ->
    val base = if (name == "官方源") "https://dl-cdn.alpinelinux.org" else host
    Mirror(name, "$base/alpine/v$branch/main/APKINDEX.tar.gz", "$base/alpine")
  }

  /**
   * 并行测速一组镜像，按延迟升序返回（失败的排最后）。
   * @param onEach 单个镜像出结果即回调（进度展示）。
   */
  fun probe(
    mirrors: List<Mirror>,
    onEach: (Result) -> Unit = {},
  ): List<Result> {
    val pool = Executors.newFixedThreadPool(minOf(6, mirrors.size))
    val futures = mirrors.map { m ->
      m to pool.submit(Callable {
        val r = Result(m, measure(m.probeUrl))
        onEach(r)
        r
      })
    }
    val results = futures.map { (m, f) ->
      runCatching { f.get() }.getOrElse { t -> Result(m, null, t.message) }
    }
    pool.shutdownNow()
    pool.awaitTermination(15, TimeUnit.SECONDS)
    return results.sortedWith(compareBy { it.latencyMs ?: Long.MAX_VALUE })
  }

  /** 首字节延迟：连接 + 发请求 + 读到第 1 字节为止；取 [TRIES] 次最优。 */
  private fun measure(url: String): Long? {
    var best: Long? = null
    repeat(TRIES) {
      val ms = measureOnce(url)
      if (ms != null && (best == null || ms < best)) best = ms
    }
    return best ?: throw IOException("连续 $TRIES 次探测失败")
  }

  private fun measureOnce(url: String): Long? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT
    conn.readTimeout = READ_TIMEOUT
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("User-Agent", Downloader.UA)
    try {
      val t0 = System.currentTimeMillis()
      if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
      conn.inputStream.use { it.read() } // 首字节
      System.currentTimeMillis() - t0
    } finally {
      conn.disconnect()
    }
  } catch (t: Throwable) {
    AppLog.log("mirror-probe", "探测失败: $url", t)
    null
  }
}
