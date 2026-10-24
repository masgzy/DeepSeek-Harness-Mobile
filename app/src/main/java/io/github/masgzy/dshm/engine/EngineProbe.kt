package io.github.masgzy.dshm.engine

import java.net.HttpURLConnection
import java.net.URL

/** 引擎就绪探测：dsh Web UI 是否在 127.0.0.1:3080 应答。 */
object EngineProbe {

  /**
   * @return true 只要端口有任何 HTTP 应答（含 4xx/5xx，说明进程活着）。
   */
  fun check(port: Int = Paths.DSH_PORT): Boolean {
    var conn: HttpURLConnection? = null
    return try {
      conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
      conn.connectTimeout = 1_500
      conn.readTimeout = 1_500
      conn.requestMethod = "GET"
      conn.instanceFollowRedirects = false
      conn.responseCode > 0
    } catch (_: Exception) {
      false
    } finally {
      conn?.disconnect()
    }
  }

  /**
   * 带凭据 URL 活性验证：200-399 = token 有效；401/403 = 凭据过期（引擎重启换了
   * 新 token，日志尾部是旧地址）；连接失败 = 引擎已死。用于「从日志尾部解析 URL」
   * 场景防旧 token 误入 WebView（否则表现为点进入对话即 refused）。
   * 必须在非主线程调用（网络 I/O）。
   */
  fun checkUrl(url: String): Boolean {
    var conn: HttpURLConnection? = null
    return try {
      conn = URL(url).openConnection() as HttpURLConnection
      conn.connectTimeout = 2_000
      conn.readTimeout = 3_000
      conn.requestMethod = "GET"
      conn.instanceFollowRedirects = false
      conn.responseCode in 200..399
    } catch (_: Exception) {
      false
    } finally {
      conn?.disconnect()
    }
  }
}
