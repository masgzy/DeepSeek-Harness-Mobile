package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

/**
 * Node.js 官方发行目录（dist index.json）客户端。
 *
 * - 全量列表：https://nodejs.org/dist/index.json（官方）与
 *   https://npmmirror.com/mirrors/node/index.json（国内），内容一致；
 * - tarball 下载镜像：nodejs.org/dist + npmmirror（弱网自动回退）；
 * - SHA-256：安装时从所选源的 SHASUMS256.txt 取 linux-arm64 行校验，
 *   免去按版本硬编码哈希表。
 */
object NodeCatalog {

  const val CURRENT_DEFAULT = "v24.22.0" // 与 Paths.NODE_VERSION 一致的默认值

  private val INDEX_MIRRORS = listOf(
    "https://nodejs.org/dist/index.json",
    "https://npmmirror.com/mirrors/node/index.json",
  )

  private val VERSION = Regex("^v?(\\d{1,2})\\.(\\d{1,2})\\.(\\d{1,3})$")

  data class Entry(val version: String, val lts: Boolean, val date: String)

  /** 规范化用户输入 → "vX.Y.Z"；非法返回 null。 */
  fun normalize(raw: String): String? {
    val m = VERSION.matchEntire(raw.trim()) ?: return null
    return "v${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
  }

  /** tarball 文件名（linux-arm64 glibc 构建）。 */
  fun tarballName(version: String): String = "node-$version-linux-arm64.tar.gz"

  /** tarball 下载镜像列表（官方 + npmmirror）。 */
  fun tarballMirrors(version: String): List<String> = listOf(
    "https://nodejs.org/dist/$version/${tarballName(version)}",
    "https://npmmirror.com/mirrors/node/$version/${tarballName(version)}",
  )

  /**
   * 拉取 SHASUMS256.txt 并取 linux-arm64 tarball 行的哈希。
   * @param preferCn true 时先试 npmmirror。
   * @throws IOException 两个源都失败或找不到对应行。
   */
  fun sha256For(version: String, preferCn: Boolean): String {
    val bases = if (preferCn) {
      listOf("https://npmmirror.com/mirrors/node", "https://nodejs.org/dist")
    } else {
      listOf("https://nodejs.org/dist", "https://npmmirror.com/mirrors/node")
    }
    var lastError: Exception? = null
    for (base in bases) {
      try {
        val text = httpGet("$base/$version/SHASUMS256.txt").toString(Charsets.UTF_8)
        val line = text.lineSequence().firstOrNull { it.trimEnd().endsWith(tarballName(version)) }
          ?: throw IOException("SHASUMS256.txt 无 ${tarballName(version)} 行")
        val hash = line.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: ""
        if (!Regex("^[a-f0-9]{64}$").matches(hash)) throw IOException("非法 SHA-256 行: $line")
        return hash
      } catch (e: Exception) {
        AppLog.log("node-catalog", "SHASUMS256 获取失败: $base", e)
        lastError = e
      }
    }
    throw IOException("SHASUMS256.txt 获取失败（已尝试 ${bases.size} 个源）", lastError)
  }

  /**
   * 全量版本列表（新→旧）。仅保留 x.y.z 完整版本，过滤 nightly/rc 等非常规 tag。
   * @throws IOException 两个镜像都失败。
   */
  fun list(): List<Entry> {
    var lastError: Exception? = null
    for (url in INDEX_MIRRORS) {
      try {
        val arr = JSONArray(String(httpGet(url), Charsets.UTF_8))
        val out = ArrayList<Entry>(arr.length())
        var i = 0
        while (i < arr.length()) {
          val e = arr.getJSONObject(i)
          val v = e.optString("version", "")
          if (VERSION.matchEntire(v) != null) {
            val ltsRaw = e.opt("lts")
            out += Entry(v, ltsRaw is String && ltsRaw.isNotBlank(), e.optString("date", ""))
          }
          i++
        }
        return out
      } catch (e: Exception) {
        AppLog.log("node-catalog", "index.json 获取失败: $url", e)
        lastError = e
      }
    }
    throw IOException("Node.js 版本目录获取失败（官方与 npmmirror 均不可达）", lastError)
  }

  private fun httpGet(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "DeepSeek-Harness-Mobile/1.0 (Android)")
    try {
      conn.connect()
      val code = conn.responseCode
      if (code !in 200..299) throw IOException("HTTP $code: $url")
      return conn.inputStream.use { it.readBytes() }
    } finally {
      conn.disconnect()
    }
  }
}
