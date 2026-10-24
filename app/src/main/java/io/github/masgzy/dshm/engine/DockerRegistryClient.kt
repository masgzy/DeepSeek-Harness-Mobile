package io.github.masgzy.dshm.engine

import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.TarExtractor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Docker Registry v2 客户端：把任意公开镜像拉成本地 rootfs。
 * 参照 masgzy/proot-distro-i18n 的 helpers/docker 实现移植（Python → Kotlin）：
 *
 *   1. 解析镜像引用（registry/repo:tag@digest，Docker Hub 短名补 library/）；
 *   2. manifest 请求遇 401 时按 WWW-Authenticate 挑战换取匿名 Bearer token
 *      （token 按 repo 签发，换 repo 必须重新取）；
 *   3. OCI index / Docker manifest-list 解出 arm64/linux 子 manifest；
 *   4. 逐层下载 blob（跨域重定向丢弃 Authorization）→ SHA-256 按_digest_ 校验 →
 *      解包进 rootfs 暂存目录（含 .wh.* whiteout 处理）。
 *
 * 镜像源：Docker Hub 引用默认走国内可达镜像（daocloud / 1panel / 1ms），
 * 全部失败再回退 registry-1.docker.io；自定义第三方 registry 只走其自身。
 */
class DockerRegistryClient(
  private val cacheDir: File,
  private val onLog: (String) -> Unit = {},
) {

  // ---- 镜像引用解析 ----

  data class ImageRef(
    val registry: String,
    val repo: String,
    val tag: String,
    val digest: String?,
  ) {
    fun baseUrl(): String = "https://$registry"
    fun manifestPath(): String = "/v2/$repo/manifests/${digest ?: tag}"
    fun blobPath(d: String): String = "/v2/$repo/blobs/$d"
  }

  object RefParser {
    private val DIGEST = Regex("^sha256:[a-f0-9]{64}$")

    /**
     * 解析镜像引用，规则与 docker pull 一致：
     *  - 首段含 "." / ":" 或为 "localhost" → 该段是 registry；
     *  - Docker Hub（docker.io）无 "/" 的短名补 "library/" 前缀；
     *  - 默认 tag "latest"；支持 @sha256:… digest。
     */
    fun parse(raw: String): ImageRef {
      var s = raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
      if (s.isEmpty()) throw IOException("镜像引用为空")
      // 拆 digest
      var digest: String? = null
      val at = s.lastIndexOf('@')
      if (at >= 0) {
        digest = s.substring(at + 1)
        if (!DIGEST.matches(digest)) throw IOException("非法 digest：$digest")
        s = s.substring(0, at)
      }
      // 拆 registry
      var registry = "registry-1.docker.io"
      val firstSlash = s.indexOf('/')
      val firstSeg = if (firstSlash > 0) s.substring(0, firstSlash) else s
      if (firstSlash > 0 && (firstSeg.contains('.') || firstSeg.contains(':') || firstSeg == "localhost")) {
        registry = firstSeg
        s = s.substring(firstSlash + 1)
      }
      if (s.isEmpty()) throw IOException("镜像名缺失: $raw")
      // 拆 tag（最后一个 ":" 且不在段首）
      var tag = "latest"
      val lastColon = s.lastIndexOf(':')
      if (lastColon > s.lastIndexOf('/')) {
        tag = s.substring(lastColon + 1)
        s = s.substring(0, lastColon)
      }
      require(tag.isNotEmpty() && tag.length <= 128 && !tag.contains('/')) { "非法 tag: $tag" }
      require(s.none { it.isWhitespace() }) { "镜像名含空白字符" }
      // Docker Hub 短名补 library/
      var repo = s
      if (registry == "registry-1.docker.io" && !repo.contains('/')) repo = "library/$repo"
      require(repo.none { seg -> seg.isEmpty() }) { "非法镜像名: $s" }
      return ImageRef(registry, repo, tag, digest)
    }

    fun isValid(raw: String): Boolean =
      raw.trim().let { it.isNotBlank() && it.length <= 256 } && runCatching { parse(raw) }.isSuccess
  }

  // ---- 镜像源表（参照 proot-distro-i18n mirrors.py，2025-2026 公开可用性整理） ----

  /** Docker Hub 的registry 镜像源（顺序即回退顺序），官方端点在最后。 */
  private val HUB_BASES = listOf(
    "https://docker.m.daocloud.io",
    "https://docker.1panel.live",
    "https://docker.1ms.run",
    "https://registry-1.docker.io",
  )

  private val MEDIA_TYPES = listOf(
    "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.oci.image.index.v1+json",
  ).joinToString(", ")

  private val ACCEPT_MANIFEST = MEDIA_TYPES
  private val ACCEPT_INDEX = listOf(
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.index.v1+json",
  ).joinToString(", ")
  private val ACCEPT_CHILD = listOf(
    "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
  ).joinToString(", ")

  // ---- 对外主流程 ----

  /**
   * 拉取镜像并解包到 [stagingDir]。
   * @param arch 目标架构（当前 arm64）。
   * @throws IOException 网络/协议/校验失败。
   */
  fun pull(
    refRaw: String,
    stagingDir: File,
    arch: String = "arm64",
    onProgress: (step: String, done: Long, total: Long) -> Unit,
  ) {
    val ref = RefParser.parse(refRaw)
    val bases: List<String> =
      if (ref.registry == "registry-1.docker.io") HUB_BASES else listOf(ref.baseUrl())
    var lastError: Exception? = null
    for (base in bases) {
      try {
        onLog("registry: $base (${ref.repo}:${ref.tag})")
        pullFrom(base, ref, stagingDir, arch, onProgress)
        return
      } catch (e: Exception) {
        AppLog.log("registry", "镜像源失败: $base", e)
        onLog("镜像源失败: $base — ${e.message}")
        lastError = e
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
      }
    }
    throw IOException("镜像 ${refRaw} 拉取失败（已尝试 ${bases.size} 个源）", lastError)
  }

  private fun pullFrom(
    base: String,
    ref: ImageRef,
    stagingDir: File,
    arch: String,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
  ) {
    val session = Session(base, ref)

    // 1) manifest（index → arm64 子 manifest）
    onProgress("获取镜像 manifest", 0, 1)
    val indexBytes = session.getJson(ref.manifestPath(), ACCEPT_MANIFEST)
    val index = JSONObject(String(indexBytes, Charsets.UTF_8))
    val manifest: JSONObject = if (isIndex(index)) {
      val child = pickArm64(index, arch)
      onLog("多架构索引 → arm64 子 manifest ${child.digest.take(20)}…")
      val childBytes = if (child.digest == ref.digest) indexBytes
      else session.getJson("/v2/${ref.repo}/manifests/${child.digest}", ACCEPT_CHILD)
      JSONObject(String(childBytes, Charsets.UTF_8))
    } else {
      index
    }

    // 2) layers
    val layers = manifest.optJSONArray("layers")
      ?: throw IOException("manifest 缺少 layers 字段")
    val totalBytes = (0 until layers.length()).sumOf { layers.getJSONObject(it).optLong("size", 0L) }
    onLog("共 ${layers.length()} 层，合计 ${fmt(totalBytes)}")
    var doneBytes = 0L
    var idx = 0
    while (idx < layers.length()) {
      val layer = layers.getJSONObject(idx)
      val digest = layer.optString("digest").orEmpty()
      val size = layer.optLong("size", 0L)
      val media = layer.optString("mediaType", "")
      if (!digest.startsWith("sha256:")) throw IOException("非法 layer digest: $digest")
      if (media.contains("zstd")) {
        throw IOException("镜像使用 zstd 压缩层（$media），暂不支持；请换用 gzip 层的镜像")
      }
      onProgress("下载镜像层 ${idx + 1}/${layers.length()}", doneBytes, totalBytes)
      val blob = session.fetchBlob(digest, size) { d, _ ->
        onProgress("下载镜像层 ${idx + 1}/${layers.length()}", doneBytes + d, totalBytes)
      }
      // digest 校验
      val actual = HashingUtil.sha256Hex(blob)
      if (actual != digest.removePrefix("sha256:")) {
        throw IOException("layer SHA-256 不匹配: $digest 实际=$actual")
      }
      onProgress("解包镜像层 ${idx + 1}/${layers.length()}", doneBytes, totalBytes)
      blob.inputStream().buffered().use { input ->
        TarExtractor.extractLayer(input, stagingDir)
      }
      doneBytes += size
      blob.delete()
      idx++
    }
    onProgress("镜像就绪", 1, 1)
  }

  private fun isIndex(o: JSONObject): Boolean {
    val mt = o.optString("mediaType", "")
    if (mt.contains("manifest.list") || mt.contains("image.index")) return true
    return o.has("manifests") && !o.has("layers")
  }

  private fun pickArm64(index: JSONObject, arch: String): JSONObject {
    val manifests = index.optJSONArray("manifests") ?: throw IOException("index 缺少 manifests")
    var i = 0
    while (i < manifests.length()) {
      val m = manifests.getJSONObject(i)
      val p = m.optJSONObject("platform") ?: JSONObject()
      if (p.optString("os") == "linux" && p.optString("architecture") == arch &&
        (p.optString("variant").isBlank() || p.optString("variant") == "v8")
      ) return m
      i++
    }
    throw IOException("镜像不含 linux/$arch 架构变体")
  }

  private fun fmt(b: Long): String = when {
    b >= 1 shl 20 -> String.format("%.1fMB", b.toDouble() / (1 shl 20))
    b >= 1 shl 10 -> String.format("%.0fKB", b.toDouble() / (1 shl 10))
    else -> "${b}B"
  }

  // ---- 会话：token 缓存 + blob 下载 ----

  /** 每个 (registry, repo) 会话：匿名 Bearer token 按 repo 签发与缓存。 */
  private inner class Session(private val base: String, private val ref: ImageRef) {
    private var token: String? = null

    fun getJson(path: String, accept: String): ByteArray {
      var resp = request(path, accept)
      if (resp.code == 401) {
        authenticate(resp.headers)
        resp = request(path, accept)
      }
      // 跟随重定向：同源保留 Authorization，跨源丢弃（CDN 拒绝 Bearer token）
      var hops = 0
      while (resp.code in 300..399) {
        val loc = resp.header("Location") ?: throw IOException("重定向缺少 Location")
        if (++hops > 5) throw IOException("重定向次数过多")
        val sameOrigin = runCatching {
          URL(loc).host == URL(base).host
        }.getOrDefault(false)
        resp = requestAbsolute(loc, accept, handleAuth = sameOrigin)
        if (resp.code == 401 && sameOrigin) {
          authenticate(resp.headers)
          resp = requestAbsolute(loc, accept, handleAuth = true)
        }
      }
      if (resp.code !in 200..299) throw IOException("HTTP ${resp.code}: ${ref.repo} $path")
      return resp.body ?: throw IOException("空响应体: $path")
    }

    fun fetchBlob(digest: String, size: Long, onRead: (Long, Long) -> Unit): File {
      cacheDir.mkdirs()
      val dest = File(cacheDir, "layer-${digest.removePrefix("sha256:").take(16)}.bin")
      var resp = request(ref.blobPath(digest), "*/*")
      if (resp.code == 401) {
        authenticate(resp.headers)
        resp = request(ref.blobPath(digest), "*/*")
      }
      // 手动跟随重定向：Hub 的 blob 会 302 到 CDN（签名 URL），
      // 跨域后必须丢弃 Authorization（proot-distro-i18n 同款处理）
      var r = resp
      var hops = 0
      while (r.code in 300..399) {
        val loc = r.header("Location") ?: throw IOException("重定向缺少 Location")
        if (++hops > 5) throw IOException("重定向次数过多")
        r = requestAbsolute(loc, handleAuth = false)
      }
      if (r.code !in 200..299) throw IOException("HTTP ${r.code}: blob $digest")
      val stream: InputStream = r.bodyStream ?: throw IOException("blob 空响应体")
      val declared = r.headerLong("Content-Length").takeIf { it > 0 } ?: size
      stream.use { input ->
        File(dest.parentFile, dest.name + ".part").outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var done = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            onRead(done, declared)
          }
        }
      }
      val part = File(dest.parentFile, dest.name + ".part")
      if (!part.renameTo(dest)) part.copyTo(dest, overwrite = true)
      return dest
    }

    /** 按 WWW-Authenticate 挑战换匿名 token（Bearer realm=…,service=…,scope=…）。 */
    private fun authenticate(headers: Map<String, String>) {
      val challenge = headers.entries.firstOrNull { it.key.equals("WWW-Authenticate", true) }?.value
      if (challenge == null || !challenge.startsWith("Bearer")) {
        throw IOException("registry 需要认证且无法匿名访问（$challenge）")
      }
      val params = challenge.removePrefix("Bearer").trim()
        .split(",")
        .mapNotNull {
          val kv = it.split("=", limit = 2)
          if (kv.size == 2) kv[0].trim() to kv[1].trim().removeSurrounding("\"") else null
        }
        .toMap()
      val realm = params["realm"] ?: throw IOException("认证挑战缺少 realm")
      val url = buildString {
        append(realm)
        append(if (realm.contains('?')) '&' else '?')
        append("service=").append(params["service"] ?: "registry.docker.io")
        append("&scope=repository:").append(ref.repo).append(":pull")
      }
      onLog("换取匿名 token: $realm")
      val resp = requestAbsolute(url, handleAuth = false)
      if (resp.code !in 200..299) throw IOException("token 换取失败 HTTP ${resp.code}")
      val body = resp.body ?: throw IOException("token 响应为空")
      token = JSONObject(String(body, Charsets.UTF_8)).optString("token")
        .ifBlank { JSONObject(String(body, Charsets.UTF_8)).optString("access_token") }
      if (token.isNullOrBlank()) throw IOException("token 响应缺少 token 字段")
    }

    private fun request(path: String, accept: String): Resp =
      requestAbsolute(base + path, accept, handleAuth = true)

    private fun requestAbsolute(
      url: String,
      accept: String = "*/*",
      handleAuth: Boolean,
    ): Resp {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("User-Agent", "DeepSeek-Harness-Mobile/1.0 (Android)")
      conn.setRequestProperty("Accept", accept)
      if (handleAuth) token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
      try {
        val code = conn.responseCode
        val headers = conn.headerFields
          .mapValues { (k, v) -> v.firstOrNull() ?: "" }
          .filterKeys { it != null }
          .mapKeys { it.key as String }
        if (code in 300..399) {
          return Resp(code, headers, null, null)
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return if (url.contains("/blobs/")) {
          // blob 流式处理，不整体读入内存
          Resp(code, headers, null, stream)
        } else {
          val body = stream?.readBytes()
          stream?.close()
          Resp(code, headers, body, null)
        }
      } finally {
        // blob 的流在调用方读取期间必须保持连接有效，不能在这里 disconnect
      }
    }
  }

  private class Resp(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val bodyStream: InputStream?,
  ) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun headerLong(name: String): Long = header(name)?.toLongOrNull() ?: -1L
  }
}

/** 内部哈希工具（避免与 util.Hashing 产生混淆的独立实现，供 digest 校验使用）。 */
private object HashingUtil {
  fun sha256Hex(file: File): String? = try {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    md.digest().joinToString("") { "%02x".format(it) }
  } catch (t: Throwable) {
    AppLog.log("registry", "SHA-256 计算失败: ${file.name}", t)
    null
  }
}
