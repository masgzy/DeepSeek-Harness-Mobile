package io.github.masgzy.dshm.util

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 多镜像回退 + 多线程分段并行下载器。
 *
 *  1. 镜像回退：依次尝试 [download] 传入的镜像列表，成功（完成 + SHA-256 校验）即返回；
 *  2. 并行分段：服务器支持 Range 且文件足够大时，按 8 段并发下载（aria2 风格，
 *     各段独立重试），直接以 RandomAccessFile 写入目标偏移，免二次拼装；
 *     不支持 Range / 小文件 / 分段失败 → 自动回退单流下载。
 *
 * 用于 rootfs 直链（ubuntu-base / alpine-minirootfs / lxc-images）与
 * Node.js 官方 tarball，保证弱网/墙内环境可安装且尽量跑满带宽。
 */
object Downloader {

  /** UA 需带 Mozilla 前缀：部分镜像站（如 USTC）拒绝非浏览器 UA。 */
  const val UA = "Mozilla/5.0 (Android 14; arm64) DeepSeek-Harness-Mobile/1.0"

  private const val CONNECT_TIMEOUT = 15_000
  private const val READ_TIMEOUT = 60_000
  /** 分段并行阈值与段数：小于 4MB 走单流；最多 8 段。 */
  private const val SEGMENT_MIN_SIZE = 4L * 1024 * 1024
  private const val MAX_SEGMENTS = 8
  /** 单段重试次数（慢速段超时后换 Range 续传重试）。 */
  private const val SEGMENT_RETRIES = 2

  /**
   * 依次尝试 [mirrors]，成功（下载完成 + SHA-256 校验通过）即返回。
   * @param expectedSha256 为 null 时跳过校验。
   * @throws IOException 全部镜像均失败。
   */
  fun download(
    mirrors: List<String>,
    dest: File,
    expectedSha256: String?,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
  ): File {
    require(mirrors.isNotEmpty()) { "镜像列表为空" }
    var lastError: Exception? = null
    for (url in mirrors) {
      try {
        AppLog.log("downloader", "尝试镜像: $url")
        fetch(url, dest, onProgress)
        if (expectedSha256 != null) {
          val actual = Hashing.sha256Hex(dest)
            ?: throw IOException("SHA-256 计算失败: ${dest.name}")
          if (!Hashing.equalsHex(expectedSha256, actual)) {
            throw IOException("SHA-256 不匹配: $url 期望=$expectedSha256 实际=$actual")
          }
          AppLog.log("downloader", "校验通过: ${dest.name}")
        }
        return dest
      } catch (e: Exception) {
        AppLog.log("downloader", "镜像失败: $url", e)
        lastError = e
        dest.delete()
        dest.parentFile?.listFiles { f -> f.name.startsWith("${dest.name}.seg") }?.forEach { it.delete() }
      }
    }
    throw IOException("所有镜像均下载失败（最后一个错误: ${lastError?.message}）", lastError)
  }

  /** 小体积整体读取（lxc-images simplestreams 索引等元数据）。 */
  fun fetchBytes(url: String): ByteArray {
    val conn = open(url)
    try {
      val code = conn.responseCode
      if (code !in 200..299) throw IOException("HTTP $code: $url")
      return conn.inputStream.use { it.readBytes() }
    } finally {
      conn.disconnect()
    }
  }

  // ---- 单镜像获取：并行分段优先，自动回退单流 ----

  private fun fetch(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
    dest.parentFile?.mkdirs()
    clearSegments(dest)
    dest.delete() // 并行分段直写目标文件，先清掉历史残留（长度不符会导致校验失败）
    val plan = probe(url)
    if (plan != null && plan.total >= SEGMENT_MIN_SIZE) {
      try {
        fetchSegmented(url, dest, plan, onProgress)
        return
      } catch (e: Exception) {
        AppLog.log("downloader", "分段并行下载失败，回退单流: ${e.message}")
        clearSegments(dest)
        dest.delete()
      }
    }
    fetchSingle(url, dest, onProgress)
  }

  private class RangePlan(val total: Long, val acceptRanges: Boolean)

  /** 探测文件大小与 Range 支持（Range: 0-0 请求，Content-Range 给出总长）。 */
  private fun probe(url: String): RangePlan? = try {
    val conn = open(url)
    try {
      conn.requestMethod = "GET"
      conn.setRequestProperty("Range", "bytes=0-0")
      if (conn.responseCode == 206) {
        val cr = conn.getHeaderField("Content-Range") ?: ""
        val total = cr.substringAfter('/', "").toLongOrNull()
        if (total != null && total > 0) RangePlan(total, true) else null
      } else {
        val len = conn.contentLengthLong
        if (len > 0) RangePlan(len, false) else null
      }
    } finally {
      conn.disconnect()
    }
  } catch (e: Exception) {
    AppLog.log("downloader", "Range 探测失败（将单流下载）: ${e.message}")
    null
  }

  /** 多线程分段下载：每段独立连接 + 重试，RandomAccessFile 直写目标偏移。 */
  private fun fetchSegmented(
    url: String,
    dest: File,
    plan: RangePlan,
    onProgress: (Long, Long) -> Unit,
  ) {
    val total = plan.total
    val segments = minOf(MAX_SEGMENTS, (total / (1L * 1024 * 1024)).toInt().coerceAtLeast(1))
    val segSize = (total + segments - 1) / segments
    AppLog.log("downloader", "并行分段下载: $segments 段 × ~${segSize / 1024}KB (total=$total)")

    val done = AtomicLong(0L)
    var lastReport = 0L
    val progressLock = Any()
    fun report() {
      val now = System.currentTimeMillis()
      synchronized(progressLock) {
        if (now - lastReport > 120) {
          lastReport = now
          onProgress(done.get(), total)
        }
      }
    }

    val pool: ExecutorService = Executors.newFixedThreadPool(segments)
    val futures = mutableListOf<Future<Boolean>>()
    try {
      for (i in 0 until segments) {
        val start = i * segSize
        val end = minOf(start + segSize, total) - 1
        if (start > end) break
        futures.add(pool.submit(Callable {
          var attempt = 0
          while (true) {
            val segDone = AtomicLong(0L)
            try {
              fetchSegment(url, dest, start, end) { n ->
                val d = segDone.addAndGet(n.toLong())
                done.set(start + d)
                report()
              }
              return@Callable true
            } catch (e: Exception) {
              attempt++
              AppLog.log("downloader", "分段 $i 第 $attempt 次失败: ${e.message}")
              if (attempt > SEGMENT_RETRIES) return@Callable false
              // 重试：整段重写（并发分段无共享进度，文件长度不能作为续传依据）
            }
          }
          false
        }))
      }
      var ok = true
      for (f in futures) if (!f.get()) ok = false
      if (!ok) throw IOException("部分分段下载失败")
      onProgress(total, total)
      // 校验总长度（防截断）
      if (dest.length() != total) {
        throw IOException("分段下载后文件大小不符: ${dest.length()} != $total")
      }
      AppLog.log("downloader", "分段并行下载完成: ${dest.name}")
    } finally {
      pool.shutdownNow()
    }
  }

  /** 下载单个分段：Range 请求 → 从 start 偏移直写；失败重试时整段重写。 */
  private fun fetchSegment(
    url: String,
    dest: File,
    start: Long,
    end: Long,
    onRead: (Int) -> Unit,
  ) {
    val conn = open(url)
    try {
      conn.setRequestProperty("Range", "bytes=$start-$end")
      val code = conn.responseCode
      if (code != 206) throw IOException("分段请求未返回 206（HTTP $code）")
      java.io.RandomAccessFile(dest, "rw").use { raf ->
        raf.seek(start)
        conn.inputStream.use { input ->
          val buf = ByteArray(128 * 1024)
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            raf.write(buf, 0, n)
            onRead(n)
          }
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  /** 单流下载（小文件 / 不支持 Range 的回退路径）。 */
  private fun fetchSingle(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, dest.name + ".part")
    tmp.delete()
    val conn = open(url)
    try {
      conn.instanceFollowRedirects = true
      val code = conn.responseCode
      if (code !in 200..299) throw IOException("HTTP $code: $url")
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        tmp.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var done = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            onProgress(done, if (total > 0) total else -1L)
          }
        }
      }
      if (!tmp.renameTo(dest)) {
        // 跨文件系统 rename 失败兜底
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun open(url: String): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT
    conn.readTimeout = READ_TIMEOUT
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", UA)
    conn.setRequestProperty("Accept-Encoding", "identity")
    return conn
  }

  /** 清理残留分段临时文件（.seg0.part 等）。 */
  private fun clearSegments(dest: File) {
    dest.parentFile?.listFiles { f -> f.name.startsWith(dest.name + ".seg") }?.forEach { it.delete() }
  }
}
