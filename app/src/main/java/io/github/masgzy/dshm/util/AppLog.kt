package io.github.masgzy.dshm.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量应用日志：内存环形缓冲 + 磁盘文件双写，带四级等级（DEBUG/INFO/WARN/ERROR）。
 * 「日志」页同时展示引擎日志（engine.log，原始行 + 关键词分级）与应用日志
 * （app.log，写入时已带结构化等级）。
 *
 * 等级语义：
 *  - [Level.DEBUG]：诊断细节（轮询跳过、逐镜像探测过程），默认不录制；
 *  - [Level.INFO]：常规进展（默认级）；
 *  - [Level.WARN]：可自动恢复的异常（回退、重试、降级）；
 *  - [Level.ERROR]：失败 / 需要用户介入。
 *
 * [minLevel] 录制阈值（设置页「日志等级」）：低于阈值的行不进内存缓冲也不落盘，
 * 用户选「精简」时日志页只剩警告与错误，不再刷屏。引擎日志文件是进程 stdout 的
 * 原样镜像、无法在写入端分级 —— 展示层用 [classify] 按关键词分级后同样受
 * 日志页级别过滤器约束。
 */
object AppLog {

  enum class Level { DEBUG, INFO, WARN, ERROR }

  /** 带等级的日志条目（内存缓冲 / 日志页渲染的最小单元）。 */
  data class Entry(val line: String, val level: Level)

  private const val MAX_BUFFER = 512
  private val buffer = ArrayDeque<Entry>(MAX_BUFFER)
  private val lock = Any()

  /** 录制阈值：低于此级的日志丢弃（设置页可调；默认 INFO）。 */
  @Volatile
  var minLevel: Level = Level.INFO

  val logFile: File by lazy {
    File(contextDir(), "logs/app.log").apply { parentFile?.mkdirs() }
  }

  /** 崩溃现场文件（由全局异常处理器写入；存在即代表进程发生过未捕获崩溃）。 */
  val crashLogFile: File
    get() = File(contextDir(), "logs/crash.log")

  // 在依赖注入就位前用懒加载兜底：由 App 在 onCreate 里注入 filesDir。
  @Volatile
  private var baseDir: File? = null

  fun init(filesDir: File) {
    baseDir = filesDir
  }

  private fun contextDir(): File = baseDir ?: File("/data/local/tmp").apply { mkdirs() }

  /**
   * 兼容入口：error 参数非空时按 ERROR 记，否则 INFO。
   * 新代码建议用 [d]/[i]/[w]/[e] 显式标级。
   */
  fun log(tag: String, message: String, error: Throwable? = null) {
    write(tag, message, if (error != null) Level.ERROR else Level.INFO, error)
  }

  fun d(tag: String, message: String, error: Throwable? = null) = write(tag, message, Level.DEBUG, error)
  fun i(tag: String, message: String, error: Throwable? = null) = write(tag, message, Level.INFO, error)
  fun w(tag: String, message: String, error: Throwable? = null) = write(tag, message, Level.WARN, error)
  fun e(tag: String, message: String, error: Throwable? = null) = write(tag, message, Level.ERROR, error)

  private fun write(tag: String, message: String, level: Level, error: Throwable?) {
    val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    val line = buildString {
      append(ts).append(' ').append(tag).append(": ").append(message)
      if (error != null) {
        append(" <- ").append(error.javaClass.simpleName)
        error.message?.let { append('(').append(it).append(')') }
      }
    }
    if (level.ordinal >= minLevel.ordinal) {
      synchronized(lock) {
        if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
        buffer.addLast(Entry(line, level))
      }
      runCatching {
        logFile.parentFile?.mkdirs()
        logFile.appendText(line + "\n")
      }
    }
    android.util.Log.println(
      when (level) {
        Level.DEBUG -> android.util.Log.DEBUG
        Level.INFO -> android.util.Log.INFO
        Level.WARN -> android.util.Log.WARN
        Level.ERROR -> android.util.Log.ERROR
      },
      tag, message + (error?.let { ": ${it.message}" } ?: ""),
    )
  }

  /** 取最近 [lines] 条内存日志（带等级，供日志页彩色渲染）。 */
  fun recentEntries(lines: Int = 128, min: Level = Level.DEBUG): List<Entry> =
    synchronized(lock) { buffer.filter { it.level.ordinal >= min.ordinal }.takeLast(lines) }

  /** 取最近 [lines] 条内存日志的纯文本（兼容旧调用）。 */
  fun recent(lines: Int = 128): List<String> = recentEntries(lines).map { it.line }

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

  // ---- 展示层分级：无结构化等级的原始行（引擎 stdout / 历史文件）按关键词定级 ----

  private val ERR_RE = Regex(
    "(?i)失败|错误|fatal|exception|traceback|segfault|segmentation|refused|denied|" +
      "cannot|can't|unable|\\berror\\b|\\b40[134]\\b|exit=[1-9]|退出码=[1-9]",
  )
  private val WARN_RE = Regex(
    "(?i)warn|警告|重试|回退|fallback|跳过|超时|timeout|stale|慢启动|deprecat|验证未通过|未通过",
  )
  private val OK_RE = Regex(
    "(?i)完成|成功|就绪|已解析|已启动|已停止|已清理|已还原|验证通过|token 已就绪|" +
      "退出码=0|exit=0|\\bready\\b|listening|✓|✔",
  )

  /**
   * 展示层色调：比 [Level] 多一档 OK（成功绿），让「安装完成 / 验证通过」
   * 在日志页一眼可辨。结构化等级优先（写入时已定级），原始行按关键词定级。
   */
  enum class Tone { DEBUG, INFO, OK, WARN, ERROR }

  /**
   * 行色调（展示层）：只影响日志页的过滤与配色，不改变任何文件内容。
   * [structured] 非 null 时为应用日志结构化行；引擎日志等原始行传 null。
   */
  fun toneOf(line: String, structured: Level? = null): Tone {
    when (structured) {
      Level.ERROR -> return Tone.ERROR
      Level.WARN -> return Tone.WARN
      Level.DEBUG -> return Tone.DEBUG
      else -> {}
    }
    return when {
      ERR_RE.containsMatchIn(line) -> Tone.ERROR
      WARN_RE.containsMatchIn(line) -> Tone.WARN
      OK_RE.containsMatchIn(line) -> Tone.OK
      structured == Level.DEBUG -> Tone.DEBUG
      else -> Tone.INFO
    }
  }

  /** 色调折算回等级（日志页级别过滤阈值比较用；INFO 与 OK 同级）。 */
  fun levelOfTone(tone: Tone): Level = when (tone) {
    Tone.DEBUG -> Level.DEBUG
    Tone.INFO, Tone.OK -> Level.INFO
    Tone.WARN -> Level.WARN
    Tone.ERROR -> Level.ERROR
  }

  private val ANSI_RE = Regex("\u001B\\[[0-9;]*[A-Za-z]")

  /** 剥离 ANSI 转义序列（引擎输出残留的色码会让日志页出现乱码方块）。 */
  fun stripAnsi(line: String): String = ANSI_RE.replace(line, "")
}
