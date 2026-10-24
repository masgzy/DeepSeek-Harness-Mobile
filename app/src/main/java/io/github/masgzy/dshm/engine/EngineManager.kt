package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import java.io.File
import kotlin.concurrent.thread

/**
 * dsh 引擎生命周期管理：proot 内启动 `dsh web`，输出实时落入 engine.log。
 * 进程仅由本类与 EngineService（看门狗）管理。
 *
 * dsh web 每次启动都会生成随机 token（43 位 base64url），只打印在 stdout：
 *   dsh web: http://127.0.0.1:3080/?token=xxxxx
 * Web UI 必须以该完整 URL 访问，否则得到 401
 * "dsh web authentication required; reopen the URL printed by dsh web."
 * 因此本类以管道逐行读取 stdout，一边落盘一边解析 webUrl 供 WebView 使用。
 */
class EngineManager(private val context: Context) {

  private val prootRuntime = ProotRuntime(context)
  private val modelConfig = ModelConfigManager(context)

  val rootfsDir: File get() = Paths.rootfs(context.filesDir)
  val engineLog: File get() = Paths.engineLog(context.filesDir)

  /** 全局进程与 webUrl 持有者：UI 与 EngineService 是不同实例，共享同一个引擎进程。 */
  companion object {
    @Volatile
    private var current: Process? = null

    /** 当前 dsh web 的带 token 访问地址（解析自引擎 stdout）；null = 尚未解析到。 */
    val webUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** dsh web 打印的访问 URL：`dsh web: http://127.0.0.1:3080/?token=<43 位 base64url>`。 */
    private val WEB_URL_REGEX =
      Regex("""dsh web:\s*(https?://[^\s]+)""")

    /** 兜底：任意行中出现带 token 的本机 URL（宽容匹配，防未来输出格式微调）。 */
    private val TOKEN_URL_REGEX =
      Regex("""https?://(?:127\.0\.0\.1|localhost):\d+/\?token=[A-Za-z0-9_\-]{8,}""")

    /**
     * 从 engine.log 尾部解析最近一次的 webUrl。
     * 用于 APP 重启后重新附着到仍在运行的引擎（看门狗拉起、进程未被杀），
     * 此时管道读取者已不存在，只能依赖日志文件。
     * 只读最后 64KB：追加式日志里越靠后越新，dsh web 启动即打印 URL。
     */
    fun webUrlFromLog(logFile: File): String? {
      return try {
        if (!logFile.isFile) return null
        val size = logFile.length()
        val start = if (size > 65_536) size - 65_536 else 0L
        java.io.RandomAccessFile(logFile, "r").use { raf ->
        raf.seek(start)
        val bytes = ByteArray((size - start).toInt())
        raf.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        (WEB_URL_REGEX.findAll(text).lastOrNull()?.groupValues?.get(1))
          ?: TOKEN_URL_REGEX.findAll(text).lastOrNull()?.value
      }
      } catch (t: Throwable) {
        AppLog.log("engine", "从日志解析 webUrl 失败", t)
        null
      }
    }
  }

  /** 容器 + dsh 均就绪才允许启动。 */
  val engineReady: Boolean
    get() = Paths.rootfsUsable(rootfsDir) && Paths.dshInstalled(rootfsDir)

  /** proot 运行组件（nativeLibraryDir 四件套）是否可用。 */
  val isRunnerAvailable: Boolean
    get() = prootRuntime.runnerAvailable()

  /** 是否有存活的引擎进程（端口应答视为运行中）。 */
  val isRunning: Boolean
    get() = (current?.isAlive == true) || EngineProbe.check()

  /**
   * 启动引擎。幂等：已在运行则直接返回。
   * @return 是否成功拉起（进程启动 ≠ 服务就绪，就绪以 EngineProbe 与 webUrl 为准）。
   */
  @Synchronized
  fun startEngine(port: Int = Paths.DSH_PORT): Boolean {
    if (isRunning) {
      AppLog.log("engine", "引擎已在运行，跳过启动")
      return true
    }
    if (!engineReady) {
      AppLog.log("engine", "引擎未就绪（rootfs/dsh 缺失）")
      return false
    }
    if (!prootRuntime.runnerAvailable()) {
      AppLog.log("engine", "proot 运行器缺失（nativeLibraryDir 无 libdsh_proot.so/libdsh_proot_loader.so）")
      return false
    }
    // 启动前把应用内模型配置并写进容器（凭据文档 + home 级 patch）：
    // 覆盖看门狗重启路径 —— 用户改完 Key 不重启 APP 也能生效。
    // 失败不阻断启动（容器内可能还是旧凭据，Web UI 模型页仍可用）。
    if (Paths.dshInstalled(rootfsDir)) modelConfig.sync()
    val workspace = Paths.workspace(context.filesDir).apply { mkdirs() }
    val binds = listOf(
      workspace.absolutePath + ":/root/projects",
    )
    val envPairs = listOf(
      "DSH_HOME=/root/.dsh",
      "DSH_PICK_DISABLE_UPDATE_CHECK=1",
      "NODE_OPTIONS=--max-old-space-size=512",
      "NO_COLOR=1", // dsh 输出禁用 ANSI 颜色，保证 URL 正则稳定匹配
    )
    val argv = prootRuntime.buildArgs(
      rootfsDir,
      binds,
      envPairs,
      listOf("dsh", "web", "--no-open", "--port", port.toString()),
    )
    return try {
      AppLog.log("engine", "启动 dsh web (port=$port)")
      engineLog.parentFile?.mkdirs()
      webUrl.value = null
      val builder = ProcessBuilder(argv)
      builder.environment().putAll(prootRuntime.hostEnv())
      builder.redirectErrorStream(true)
      val process = builder.start()
      current = process
      // 管道逐行读取：落盘 engine.log + 解析带 token 的 webUrl。
      // dsh web 启动后 1-3 秒内即打印 URL；进程退出后 readLine 返回 null，线程自然结束。
      thread(isDaemon = true, name = "dsh-log") {
        try {
          process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
              runCatching { engineLog.appendText(line + "\n") }
              val url = WEB_URL_REGEX.find(line)?.groupValues?.get(1)
                ?: TOKEN_URL_REGEX.find(line)?.value
              if (url != null && webUrl.value != url) {
                webUrl.value = url
                AppLog.log("engine", "已解析 dsh web 访问地址（token 已就绪）")
              }
            }
          }
          AppLog.log("engine", "引擎输出流已关闭（进程退出）")
        } catch (t: Throwable) {
          AppLog.log("engine", "引擎日志读取线程异常", t)
        }
      }
      true
    } catch (t: Throwable) {
      AppLog.log("engine", "引擎启动失败", t)
      false
    }
  }

  /** 停止引擎（--kill-on-exit 会连带清理容器内子进程）。 */
  @Synchronized
  fun stopEngine() {
    try {
      current?.destroy()
      current?.waitFor()
      AppLog.log("engine", "引擎已停止")
    } catch (t: Throwable) {
      AppLog.log("engine", "停止引擎异常", t)
      current?.destroyForcibly()
    } finally {
      current = null
      webUrl.value = null
    }
  }
}
