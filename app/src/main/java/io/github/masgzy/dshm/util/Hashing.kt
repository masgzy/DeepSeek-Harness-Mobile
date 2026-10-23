package io.github.masgzy.dshm.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** 流式 SHA-256 计算（rootfs / Node 压缩包可达上百 MB，必须分块）。 */
object Hashing {

  fun sha256Hex(file: File): String? =
    runCatching { sha256Hex(file.inputStream()) }.getOrNull()

  fun sha256Hex(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    input.use { stream ->
      val buf = ByteArray(DEFAULT_BUFFER_SIZE * 16)
      while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  /** 常量时间比较，避免时序侧信道。 */
  fun equalsHex(expected: String, actual: String): Boolean {
    val a = expected.trim().lowercase()
    val b = actual.trim().lowercase()
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
  }
}
