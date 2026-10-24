package io.github.masgzy.dshm

import android.app.Application
import io.github.masgzy.dshm.util.AppLog
import java.io.File

class DshmApplication : Application() {

  override fun onCreate() {
    super.onCreate()
    AppLog.init(filesDir)
    installCrashLog()
    AppLog.log("app", "DshmApplication onCreate")
  }

  /**
   * 全局崩溃记录：未捕获异常先同步写入 app.log（含完整堆栈）再交还系统。
   *
   * 背景：v1.0.0-alpha.1 有用户反馈「预装运行时通道点击后闪退」，但本地静态
   * 排查无法在所有设备形态上穷尽路径；与其猜，不如保证**任何**崩溃都留下
   * 完整现场 —— 用户在「日志 → 应用」页即可看到崩溃堆栈并反馈。
   * 崩溃文件独立于 app.log（crash.log），避免环形缓冲与日志噪音淹没现场。
   */
  private fun installCrashLog() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching {
        val dir = File(filesDir, "logs").apply { mkdirs() }
        val text = buildString {
          append("\n===== CRASH ")
          append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date()))
          append(" thread=").append(thread.name).append(" =====\n")
          append(throwable.javaClass.name).append(": ").append(throwable.message).append('\n')
          append(throwable.stackTrace.joinToString("\n") { "    at $it" })
          var cause = throwable.cause
          var depth = 0
          while (cause != null && depth < 8) {
            append("\nCaused by: ").append(cause.javaClass.name).append(": ").append(cause.message).append('\n')
            append(cause.stackTrace.joinToString("\n") { "    at $it" })
            cause = cause.cause
            depth++
          }
          append('\n')
        }
        File(dir, "crash.log").appendText(text)
        AppLog.log("crash", "未捕获异常: ${throwable.javaClass.simpleName}: ${throwable.message}")
      }
      previous?.uncaughtException(thread, throwable)
    }
  }
}
