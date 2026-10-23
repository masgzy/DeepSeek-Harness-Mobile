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
}
