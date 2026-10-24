package io.github.masgzy.dshm.util

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Docker Hub registry 镜像源测速（择优排序）。
 *
 * 用户需求：「不能配置多个 dockerhub 镜像源择优吗」——在拉取 Docker Hub 镜像前，
 * 对候选镜像源并行做「GET /v2/ 首字节」探测（401/403/302 均算可达 —— 那是匿名
 * 访问的正常响应），按延迟升序作为 [io.github.masgzy.dshm.engine.DockerRegistryClient]
 * 的回退顺序；官方端点 registry-1.docker.io 永远垫底（大陆直连常黑洞，境外也
 * 不必前插 —— 镜像源本身就是 Hub 的全量代理）。
 *
 * 结果缓存（cacheDir/registry-probe.json，TTL 6 小时）：拉取大镜像期间不重复
 * 测速；全部失败或无缓存时用静态默认顺序。
 *
 * 与 [MirrorProbe]（apt/apk 镜像测速）的差异：registry 的 /v2/ 匿名响应码是
 * 401/403 而非 2xx，不能用 MirrorProbe.measure（把非 2xx 当失败）。
 */
object RegistryProbe {

  data class Candidate(val name: String, val base: String)

  /** 候选镜像源（2026-10 公开可用性整理，顺序仅影响展示；测速决定选用）。 */
  val CANDIDATES: List<Candidate> = listOf(
    Candidate("DaoCloud", "https://docker.m.daocloud.io"),
    Candidate("1ms.run", "https://docker.1ms.run"),
    Candidate("1panel", "https://docker.1panel.live"),
    Candidate("aityp", "https://docker.aityp.com"),
    Candidate("rat.dev", "https://hub.rat.dev"),
    Candidate("轩辕镜像", "https://docker.xuanyuan.me"),
  )

  /** 官方端点：永远垫底，不参与排序。 */
  const val OFFICIAL_BASE = "https://registry-1.docker.io"

  private val DEFAULT_ORDER: List<String> = CANDIDATES.map { it.base } + OFFICIAL_BASE

  private const val CACHE_FILE = "registry-probe.json"
  private const val TTL_MS = 6 * 60 * 60 * 1000L
  private const val CONNECT_TIMEOUT = 4_000
  private const val READ_TIMEOUT = 4_000

  /**
   * 返回测速择优后的镜像源基址（官方端点固定垫底）。
   * 缓存命中（TTL 内）直接返回上次结果；测速全败回退静态默认顺序。
   * @param force 跳过缓存强制重测（供设置页「重新测速」使用）。
   */
  fun orderedBases(
    cacheDir: File,
    force: Boolean = false,
    onLog: (String) -> Unit = {},
  ): List<String> {
    val cache = File(cacheDir, CACHE_FILE)
    if (!force) {
      val cached = readCache(cache)
      if (cached != null) {
        onLog("registry 测速缓存命中: ${cached.second.joinToString(" → ") { short(it) }}")
        return cached.second
      }
    }
    onLog("registry 镜像源测速中（${CANDIDATES.size} 个候选，并行首字节）…")
    val results = probeAll(CANDIDATES)
    onLog("registry 测速: " + results.joinToString(" / ") { (c, ms) ->
      "${c.name} " + (ms?.let { "${it}ms" } ?: "超时")
    })
    val ok = results.filter { it.second != null }.sortedBy { it.second!! }
    if (ok.isEmpty()) {
      onLog("registry 测速全部失败，使用默认顺序")
      return DEFAULT_ORDER
    }
    val order = ok.map { it.first.base } + OFFICIAL_BASE
    writeCache(cache, results)
    return order
  }

  /** 供设置页展示：并行测速并返回结果列表（延迟升序，失败在尾部）。 */
  fun probeForDisplay(onEach: (Candidate, Long?) -> Unit = { _, _ -> }): List<Pair<Candidate, Long?>> {
    val results = probeAll(CANDIDATES, onEach)
    return results.sortedWith(compareBy { it.second ?: Long.MAX_VALUE })
  }

  // ---- 探测实现 ----

  private fun probeAll(
    candidates: List<Candidate>,
    onEach: (Candidate, Long?) -> Unit = { _, _ -> },
  ): List<Pair<Candidate, Long?>> {
    val pool = Executors.newFixedThreadPool(minOf(6, candidates.size))
    val futures = candidates.map { c ->
      c to pool.submit(Callable { measure(c.base) })
    }
    val results = futures.map { (c, f) ->
      val ms = runCatching { f.get() }.getOrElse { null }
      onEach(c, ms)
      c to ms
    }
    pool.shutdownNow()
    pool.awaitTermination(15, TimeUnit.SECONDS)
    return results
  }

  /**
   * GET {base}/v2/ 首字节延迟。匿名访问 registry 的正常响应是 401/403
   * （提示带 token 再来），因此「拿到任意响应码 + 首字节」即算可达。
   */
  private fun measure(base: String): Long? {
    var best: Long? = null
    repeat(2) {
      val ms = measureOnce("$base/v2/")
      if (ms != null && (best == null || ms < best)) best = ms
    }
    return best
  }

  private fun measureOnce(url: String): Long? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT
    conn.readTimeout = READ_TIMEOUT
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("User-Agent", Downloader.UA)
    try {
      val t0 = System.currentTimeMillis()
      val code = conn.responseCode
      if (code <= 0) return null
      (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.read() }
      System.currentTimeMillis() - t0
    } finally {
      conn.disconnect()
    }
  } catch (t: Throwable) {
    AppLog.log("registry-probe", "探测失败: $url", t)
    null
  }

  // ---- 缓存（JSON：ts + [{name,base,ms}...]） ----

  private fun writeCache(cache: File, results: List<Pair<Candidate, Long?>>) {
    runCatching {
      cache.parentFile?.mkdirs()
      val arr = JSONArray()
      results.forEach { (c, ms) ->
        arr.put(JSONObject().put("name", c.name).put("base", c.base).put("ms", ms ?: -1L))
      }
      cache.writeText(JSONObject().put("ts", System.currentTimeMillis()).put("results", arr).toString())
    }
  }

  /** 返回 (ageMinutes, 按延迟序的 base 列表)；过期/损坏返回 null。 */
  private fun readCache(cache: File): Pair<Long, List<String>>? = runCatching {
    if (!cache.isFile) return null
    val o = JSONObject(cache.readText())
    val ts = o.optLong("ts", 0L)
    if (ts <= 0 || System.currentTimeMillis() - ts > TTL_MS) return null
    val arr = o.optJSONArray("results") ?: return null
    data class E(val base: String, val ms: Long)
    val entries = ArrayList<E>(arr.length())
    var i = 0
    while (i < arr.length()) {
      val e = arr.getJSONObject(i)
      val ms = e.optLong("ms", -1L)
      if (ms > 0) entries.add(E(e.optString("base"), ms))
      i++
    }
    if (entries.isEmpty()) return null
    (System.currentTimeMillis() - ts) / 60_000L to entries.sortedBy { it.ms }.map { it.base }
  }.getOrNull()

  private fun short(base: String): String = base.removePrefix("https://").removePrefix("http://")
}
