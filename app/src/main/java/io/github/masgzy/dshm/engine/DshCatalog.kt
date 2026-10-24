package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * @deepseek-ai/dsh npm 全版本目录客户端（参照 dphmoblie HarnessPackageCatalog 的思路）：
 * 拉 npm registry packument → 校验包名 / 版本号形态 → 解析 dist-tags（latest/next/…）
 * → SemVer 降序输出。registry 跟随设置页 npm 源（npmmirror / npmjs 内容一致）。
 */
object DshCatalog {

  const val PACKAGE = "@deepseek-ai/dsh"

  /** 版本号形态（与 dph 同款）：x.y.z[-pre.a.b…]；上限 96 字符。 */
  private val VERSION = Regex(
    "^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})" +
      "(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?$",
  )

  data class Entry(val version: String, val tags: List<String>)

  fun isValidVersion(v: String): Boolean = v.length <= 96 && VERSION.matches(v)

  /** 空串 / "latest" → ""（bootstrap 里空 = npm latest tag）。 */
  fun normalize(raw: String): String {
    val v = raw.trim().removePrefix("v").let { if (it.equals("latest", true)) "" else it }
    require(v.isEmpty() || isValidVersion(v)) { "非法 dsh 版本号: $raw" }
    return v
  }

  /**
   * 拉取 packument 并解析版本列表（降序）。
   * @param registryBase npm registry 根（https://registry.npmmirror.com 或官方）。
   * @throws IOException 网络失败 / 包名不符 / 目录超限。
   */
  fun list(registryBase: String): List<Entry> {
    val url = "$registryBase/@deepseek-ai%2Fdsh"
    val bytes = httpGet(url)
    if (bytes.size > 16 * 1024 * 1024) throw IOException("dsh 目录响应超限")
    val root = try {
      JSONObject(String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
      throw IOException("dsh 目录 JSON 解析失败", e)
    }
    if (root.optString("name") != PACKAGE) throw IOException("目录包名不符: ${root.optString("name")}")
    val versions = root.optJSONObject("versions") ?: throw IOException("dsh 目录缺少 versions")
    if (versions.length() > 1000) throw IOException("dsh 目录版本数量超限")
    val tags = root.optJSONObject("dist-tags") ?: JSONObject()
    val out = ArrayList<Entry>(versions.length())
    val it = versions.keys()
    while (it.hasNext()) {
      val v = it.next()
      if (!isValidVersion(v)) continue
      val manifest = versions.optJSONObject(v) ?: continue
      if (manifest.optString("name") != PACKAGE || manifest.optString("version") != v) continue
      out += Entry(
        v,
        listOf("latest", "next", "beta", "alpha").filter { tags.optString(it) == v },
      )
    }
    out.sortWith { a, b -> compareVersions(b.version, a.version) }
    return out
  }

  /** SemVer 比较（dph 同款）：正式版高于同号预发布；预发布数字按数值比较。 */
  fun compareVersions(a: String, b: String): Int {
    val left = a.split('-', limit = 2)
    val right = b.split('-', limit = 2)
    val lcore = left[0].split('.').map(String::toInt)
    val rcore = right[0].split('.').map(String::toInt)
    for (i in 0..2) if (lcore[i] != rcore[i]) return lcore[i].compareTo(rcore[i])
    if (left.size != right.size) return right.size.compareTo(left.size)
    if (left.size == 1) return 0
    val lp = left[1].split('.')
    val rp = right[1].split('.')
    for (i in 0 until minOf(lp.size, rp.size)) {
      if (lp[i] == rp[i]) continue
      val ln = lp[i].toLongOrNull()
      val rn = rp[i].toLongOrNull()
      return when {
        ln != null && rn != null -> ln.compareTo(rn)
        ln != null -> -1
        rn != null -> 1
        else -> lp[i].compareTo(rp[i])
      }
    }
    return lp.size.compareTo(rp.size)
  }

  private fun httpGet(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "DeepSeek-Harness-Mobile/1.0 (Android)")
    // npm corgi 文档：省去 description/readme 等大字段，保留 versions 与 dist-tags
    conn.setRequestProperty("Accept", "application/vnd.npm.install-v1+json, application/json")
    try {
      conn.connect()
      val code = conn.responseCode
      if (code !in 200..299) throw IOException("HTTP $code: $url")
      return conn.inputStream.use { it.readBytes() }
    } catch (e: IOException) {
      AppLog.log("dsh-catalog", "packument 获取失败: $url", e)
      throw e
    } finally {
      conn.disconnect()
    }
  }
}
