package io.github.masgzy.dshm.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量应用日志：内存环形缓冲 + 磁盘文件双写。
 * 「日志」页同时展示引擎日志（engine.log）与应用日志（app.log）。
 */
object AppLog {
  private const val MAX_BUFFER = 512
  private val buffer = ArrayDeque<String>(MAX_BUFFER)
  private val lock = Any()

  val logFile: File by lazy {
    File(contextDir(), "logs/app.log").apply { parentFile?.mkdirs() }
  }

  // 在依赖注入就位前用懒加载兜底：由 App 在 onCreate 里注入 filesDir。
  @Volatile
  private var baseDir: File? = null

  fun init(filesDir: File) {
    baseDir = filesDir
  }

  private fun contextDir(): File = baseDir ?: File("/data/local/tmp").apply { mkdirs() }

  fun log(tag: String, message: String, error: Throwable? = null) {
    val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    val line = buildString {
      append(ts).append(' ').append(tag).append(": ").append(message)
      if (error != null) {
        append(" <- ").append(error.javaClass.simpleName)
        error.message?.let { append('(').append(it).append(')') }
      }
    }
    synchronized(lock) {
      if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
      buffer.addLast(line)
    }
    android.util.Log.d(tag, message, error)
    runCatching {
      logFile.parentFile?.mkdirs()
      logFile.appendText(line + "\n")
    }
  }

  /** 取最近 [lines] 条内存日志（用于 UI 快速展示）。 */
  fun recent(lines: Int = 128): List<String> = synchronized(lock) { buffer.toList().takeLast(lines) }

  /** 读取文件末尾 [lines] 行（用于引擎日志展示）。 */
  fun tail(file: File, lines: Int = 500): List<String> {
    if (!file.isFile) return emptyList()
    return runCatching {
      val deque = ArrayDeque<String>(lines)
      file.useLines { seq ->
        for (l in seq) {
          if (deque.size >= lines) deque.removeFirst()
          deque.addLast(l)
        }
      }
      deque.toList()
    }.getOrDefault(emptyList())
  }

  /** 把整个文件内容转存进应用日志（看门狗重启时抢救崩溃前输出）。 */
  fun includeFile(file: File, tag: String) {
    if (!file.isFile) return
    runCatching { tail(file, 80).forEach { log(tag, it) } }
  }
}
