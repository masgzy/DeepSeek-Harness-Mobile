package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import java.io.File
import java.util.concurrent.TimeUnit
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

  /** 是否有存活的引擎进程（端口应答或进程句柄存活视为运行中）。 */
  val isRunning: Boolean
    get() = (current?.isAlive == true) || EngineProbe.check()

  /** dsh web 端口是否真的在应答（比 isRunning 更严格：进程活着 ≠ 服务就绪）。 */
  val isServing: Boolean
    get() = EngineProbe.check()

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
      // Node 22+ V8 编译缓存：二次启动免重复解析 dsh 依赖树（proot 下 stat/open
      // 翻译开销大，冷启动 10-30s；缓存命中后明显提速）。bootstrap 已预热首份缓存。
      "NODE_COMPILE_CACHE=/root/.dshm/compile-cache",
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
      // 完整 proot 启动命令落日志：用户反馈「proot 启动命令和结果啥的也没有」，
      // 出问题时可对照命令逐段排查（不含敏感信息，token 由 dsh web 运行时生成）
      AppLog.log("engine", "proot 命令: " + argv.joinToString(" "))
      engineLog.parentFile?.mkdirs()
      webUrl.value = null
      val builder = ProcessBuilder(argv)
      builder.environment().putAll(prootRuntime.hostEnv())
      builder.redirectErrorStream(true)
      val process = builder.start()
      current = process
      val startedAt = System.currentTimeMillis()
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
        } catch (t: Throwable) {
          AppLog.log("engine", "引擎日志读取线程异常", t)
        }
      }
      // 退出码观察者：进程退出（自然停止/崩溃/启动即退）一律记录 exit 码；
      // 启动后 90s 内退出视为异常，转存 engine.log 尾部帮助定位（反馈：
      // 「引擎到底启动了吗全靠猜」——现在退出码 + 尾部输出直接可见）
      thread(isDaemon = true, name = "dsh-watch") {
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        if (current === process) current = null
        val elapsedS = (System.currentTimeMillis() - startedAt) / 1000
        AppLog.log("engine", "引擎进程已退出（exit=$code，存活 ${elapsedS}s）")
        if (elapsedS < 90) {
          AppLog.log("engine", "引擎快速退出，最近输出：")
          AppLog.tail(engineLog, 12).forEach { AppLog.log("engine", "│ $it") }
        }
      }
      true
    } catch (t: Throwable) {
      AppLog.log("engine", "引擎启动失败", t)
      false
    }
  }

  /**
   * 重启引擎（停止 + 再启动）：端口仍应答但 token 失效（401）时唯一可行的自愈
   * 手段 —— dsh web 的带 token 地址只打印在启动 stdout，旧进程不会重新打印，
   * 干等永远拿不到新凭据。由 AppViewModel 在凭据等待超时后调用。
   */
  @Synchronized
  fun restartEngine(port: Int = Paths.DSH_PORT): Boolean {
    AppLog.log("engine", "重启引擎（获取新 token）")
    stopEngine()
    return startEngine(port)
  }

  /**
   * 停止引擎：SIGTERM 给 proot 5s 优雅退出（--kill-on-exit 连带清理容器内子进程），
   * 超时则 SIGKILL 强杀（再给 2s）。旧实现 waitFor() 无限等待，proot 清理慢时
   * 调用线程（含 EngineService.onDestroy 的主线程路径）被拖住十秒以上 → ANR 闪退。
   *
   * 另处理孤儿引擎：APP 重启后附着存活引擎时 current == null（句柄已丢失），
   * 此时停止必须扫宿主 /proc 找到 proot/dsh 进程树强杀，否则 dsh web 继续应答、
   * 「点击停止不会停止」。杀完以端口探测复核。
   */
  @Synchronized
  fun stopEngine() {
    val proc = current
    if (proc != null) {
      try {
        proc.destroy()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
          AppLog.log("engine", "SIGTERM 5s 未退出，SIGKILL 强制结束")
          proc.destroyForcibly()
          proc.waitFor(2, TimeUnit.SECONDS)
        }
        AppLog.log("engine", "引擎已停止")
      } catch (t: Throwable) {
        AppLog.log("engine", "停止引擎异常", t)
        runCatching { proc.destroyForcibly() }
      }
    }
    // 孤儿清理：current 为空（APP 重启附着）或进程已死但端口仍在应答时执行
    if (proc == null || EngineProbe.check()) {
      val killed = killOrphanEngineProcesses()
      if (killed > 0) {
        AppLog.log("engine", "孤儿引擎清理：终止 $killed 个残留进程（当前句柄 ${if (proc == null) "缺失" else "已退出"}）")
      }
    }
    current = null
    webUrl.value = null
  }

  /**
   * 扫描宿主 /proc 终止残留的引擎进程树（proot 主进程 + 容器内 dsh web tracee）。
   * 匹配特征：argv 含本 rootfs 绝对路径（-r 参数）且包含 dsh/web 两个独立参数；
   * 终端/安装脚本的 proot 进程不含 dsh web 组合，不会被误杀。
   * @return 实际发信号的进程数。
   */
  private fun killOrphanEngineProcesses(): Int {
    val rootfsPath = rootfsDir.absolutePath
    var killed = 0
    val dir = java.io.File("/proc")
    val candidates = dir.listFiles { f -> f.name.toIntOrNull() != null } ?: return 0
    for (entry in candidates) {
      val pid = entry.name.toIntOrNull() ?: continue
      if (pid == android.os.Process.myPid()) continue
      val args = runCatching {
        entry.resolve("cmdline").readBytes().toString(Charsets.UTF_8)
          .split('\u0000').filter { it.isNotBlank() }
      }.getOrNull() ?: continue
      val isEngine = args.contains(rootfsPath) && args.contains("dsh") && args.contains("web")
      if (!isEngine) continue
      // 先 SIGTERM 给 proot 机会触发 --kill-on-exit 清理 tracee，再补 SIGKILL
      runCatching { ProcessBuilder("kill", "-TERM", pid.toString()).start().waitFor() }
      killed++
    }
    if (killed == 0) return 0
    // 给 SIGTERM 最多 3 秒生效窗口，仍存活的（proot/tracee）直接 SIGKILL
    val deadline = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < deadline) {
      val alive = survivingEnginePids()
      if (alive.isEmpty()) break
      Thread.sleep(300)
    }
    for (pid in survivingEnginePids()) {
      runCatching { ProcessBuilder("kill", "-KILL", pid.toString()).start().waitFor() }
    }
    return killed
  }

  /** 当前仍存活（cmdline 可读且特征匹配）的引擎进程 PID 集合。 */
  private fun survivingEnginePids(): Set<Int> {
    val rootfsPath = rootfsDir.absolutePath
    val out = mutableSetOf<Int>()
    val candidates = java.io.File("/proc").listFiles { f -> f.name.toIntOrNull() != null } ?: return out
    for (entry in candidates) {
      val pid = entry.name.toIntOrNull() ?: continue
      if (pid == android.os.Process.myPid()) continue
      val args = runCatching {
        entry.resolve("cmdline").readBytes().toString(Charsets.UTF_8)
          .split('\u0000').filter { it.isNotBlank() }
      }.getOrNull() ?: continue
      if (args.contains(rootfsPath) && args.contains("dsh") && args.contains("web")) out += pid
    }
    return out
  }
}
