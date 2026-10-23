package io.github.masgzy.dshm.util

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 多镜像顺序回退下载器：任一镜像失败自动切换下一个。
 * 用于 ubuntu-base rootfs（cdimage 官方 + 清华 TUNA）与 Node.js 官方 tarball
 * （nodejs.org + npmmirror），保证弱网/墙内环境可安装。
 */
object Downloader {

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
      }
    }
    throw IOException("所有镜像均下载失败（最后一个错误: ${lastError?.message}）", lastError)
  }

  private fun fetch(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, dest.name + ".part")
    tmp.delete()
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "DeepSeek-Harness-Mobile/1.0 (Android)")
    try {
      conn.connect()
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
}
