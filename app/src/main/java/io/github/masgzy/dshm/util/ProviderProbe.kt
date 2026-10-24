package io.github.masgzy.dshm.util

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 模型供应商连通性测试（宿主侧直连探测）。
 *
 * 对内置/自定义供应商的模型列表端点发一次带凭据的 GET，以「HTTP 状态 + 延迟」
 * 判定 Key 与网络是否可用 —— 与参考项目 ModelConnectivityProbe 的语义一致
 * （200/401/403/404 都说明 TLS 与路由可达，凭据是否有效由状态码区分；
 * 真正的补全能力由 dsh 内部验证）。请求由宿主发出，不经容器，零依赖。
 */
object ProviderProbe {

  data class Result(
    val ok: Boolean,
    val httpStatus: Int?,
    val latencyMs: Long,
    val message: String,
  )

  private const val TIMEOUT_MS = 12_000

  /**
   * @param baseUrl 供应商根地址（内置官方或自定义供应商填写值）
   * @param apiStyle 内置三家协议；自定义供应商按其 api 字段映射
   * @param apiKey 明文 Key；null = 未配置 Key，仅测网络可达（不带鉴权头发请求）
   */
  fun probe(
    baseUrl: String,
    apiStyle: String,
    apiKey: String?,
  ): Result {
    val base = baseUrl.trim().trimEnd('/')
    // 归一化：内置三家（openai/anthropic/google）与自定义三种 api 值
    // （openai-completions/openai-responses/anthropic-messages）统一到同套探测规则
    val anthropic = apiStyle.startsWith("anthropic")
    val google = apiStyle.startsWith("google")
    val urlStr = when {
      anthropic -> if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
      google -> "$base/models"
      else -> if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }
    val started = System.currentTimeMillis()
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "dshm-probe/1.0")
        when {
          anthropic -> {
            setRequestProperty("anthropic-version", "2023-06-01")
            if (!apiKey.isNullOrEmpty()) setRequestProperty("x-api-key", apiKey)
          }
          google -> {
            if (!apiKey.isNullOrEmpty()) setRequestProperty("x-goog-api-key", apiKey)
          }
          else -> {
            if (!apiKey.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
          }
        }
      }
      val status = conn.responseCode
      val latency = System.currentTimeMillis() - started
      val body = try {
        (if (status in 200..299) conn.inputStream else conn.errorStream)
          ?.bufferedReader()?.use { it.readText().take(300) }
      } catch (_: Throwable) {
        null
      }
      val (ok, msg) = when {
        status in 200..299 -> true to "连接正常（$status）"
        status == 401 || status == 403 -> false to "Key 无效或无权限（$status）"
        status == 404 -> false to "端点不存在（404）—— Base URL 可能填错"
        status == 429 -> true to "可达，但已限流（429）"
        else -> false to "HTTP $status" + (body?.let { "：${it.take(120)}" } ?: "")
      }
      Result(ok, status, latency, msg)
    } catch (t: Throwable) {
      val latency = System.currentTimeMillis() - started
      Result(false, null, latency, "无法连接：${t.message ?: t.javaClass.simpleName}")
    } finally {
      conn?.disconnect()
    }
  }

  /** 按 [io.github.masgzy.dshm.engine.ModelConfigManager.BuiltinProvider.id] 的官方地址探测。 */
  fun probeBuiltin(baseUrl: String, apiStyle: String, apiKey: String?): Result =
    probe(baseUrl, apiStyle, apiKey)
}
