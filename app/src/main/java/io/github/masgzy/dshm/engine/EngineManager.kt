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
    /**
     * 引擎生命周期全局锁：current/webUrl 是 companion 级共享状态，而
     * AppViewModel 与 EngineService（看门狗）各自持有不同的 EngineManager 实例 ——
     * 旧实现的 @Synchronized 锁实例，两把不同的锁管同一个进程，看门狗 5s 轮询
     * 能在 restartEngine 的 SIGTERM 窗口里插进来再拉一个 proot，双引擎抢端口
     * 互相踩踏。所有生命周期方法一律串行在本锁上。
     */
    private val LOCK = Any()

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

  /** 容器内 pid 文件路径（guest 侧）；宿主侧对应 rootfs/tmp/ 同名文件。 */
  private val GUEST_PID_FILE = "/tmp/dshm-engine.pid"

  /** 容器 + dsh 均就绪才允许启动。 */
  val engineReady: Boolean
    get() = Paths.rootfsUsable(rootfsDir) && Paths.dshInstalled(rootfsDir)

  /** proot 运行组件（nativeLibraryDir 四件套）是否可用。 */
  val isRunnerAvailable: Boolean
    get() = prootRuntime.runnerAvailable()

  /** 本 App 持有的引擎进程是否存活（不看端口：外部进程占用端口与此无关）。 */
  val processAlive: Boolean
    get() = current?.isAlive == true

  /** 是否有存活的引擎进程（端口应答或进程句柄存活视为运行中）。 */
  val isRunning: Boolean
    get() = (current?.isAlive == true) || EngineProbe.check()

  /** dsh web 端口是否真的在应答（比 isRunning 更严格：进程活着 ≠ 服务就绪）。 */
  val isServing: Boolean
    get() = EngineProbe.check()

  /**
   * 启动引擎。幂等：本 App 的引擎进程存活则直接返回。
   *
   * 端口被占时的三级处理（真机反馈：Termux 手动 `dsh web` 占住 3080 时，旧实现
   * 因端口探测假阳性直接「跳过启动」，然后永远等一个等不到的 token，主页空转
   * 4 分钟+——proot 与 Android 共享网络栈，loopback 端口对全机可见）：
   *  1. 日志尾部 token 验证有效 → 附着到仍在运行的引擎（App 重启收编孤儿，不重启）；
   *  2. 本 App 特征的孤儿 proot（argv 含 rootfs+dsh+web）→ 清理后正常启动；
   *  3. 外部进程占用（Termux/其他应用）→ 明确报错收场，绝不按端口反查误杀。
   *
   * @return 是否成功拉起或附着（进程启动 ≠ 服务就绪，就绪以 webUrl 为准）。
   */
  fun startEngine(port: Int = Paths.DSH_PORT): Boolean {
    synchronized(LOCK) {
    if (current?.isAlive == true) {
      AppLog.log("engine", "引擎已在运行，跳过启动")
      return true
    }
    if (!engineReady) {
      AppLog.log("engine", "引擎未就绪（rootfs/dsh 缺失）")
      return false
    }
    if (!prootRuntime.runnerAvailable()) {
      val missing = prootRuntime.missingComponents()
      AppLog.log(
        "engine",
        "运行器缺失（runtime=${prootRuntime.runtimeKind.id}" +
          if (missing.isEmpty()) ")" else "，缺 ${missing.joinToString()}",
      )
      return false
    }
    if (EngineProbe.check(port)) {
      // 端口有应答但没有本 App 存活句柄：孤儿（上次 App 会话遗留）或外部进程
      val candidate = webUrlFromLog(engineLog)
      if (candidate != null &&
        EngineProbe.checkUrlDetailed(candidate) == EngineProbe.UrlCheck.OK
      ) {
        // 孤儿引擎且 token 仍有效：收编复用（外部进程的 token 不会出现在本 App
        // 日志里，验证通过即证明是自己人）
        webUrl.value = candidate
        AppLog.log("engine", "附着到仍在运行的引擎（日志尾部 token 验证有效）")
        return true
      }
      // 清理本 App 特征的孤儿引擎；外部进程不匹配特征、不会被误杀
      val killed = killOrphanEngineProcesses()
      if (killed > 0) {
        AppLog.log("engine", "已清理 $killed 个孤儿引擎进程，等待端口释放…")
        val deadline = System.currentTimeMillis() + 5_000
        while (EngineProbe.check(port) && System.currentTimeMillis() < deadline) {
          Thread.sleep(300)
        }
      }
      if (EngineProbe.check(port)) {
        AppLog.log(
          "engine",
          "端口 $port 被外部进程占用（非本 App 引擎，可能是 Termux 等手动运行的 dsh web）——" +
            "启动中止。请停止该进程，或在设置中更换端口后重试",
        )
        return false
      }
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
      // boot-cache 预载（设计移植自 dsh-Go）：proot 下 stat/读文件逐次陷入
      // ptrace，对安装后只读的 dsh/Node 目录树做进程内缓存，热路径（启动解析
      // 模块树）提速；stdout 出现 ?token= 或 120s 后自动撤除，不影响运行期
      // I/O。文件由 bootstrap 落位到容器 /root/.dshm/；宿主侧检查存在才注入，
      // 老安装/缺失时自动走原路径（NODE_OPTIONS 引用不存在的模块会让 node 拒启）。
      "NODE_OPTIONS=" + buildString {
        append("--max-old-space-size=512")
        if (java.io.File(rootfsDir, "root/.dshm/boot-cache.mjs").isFile) {
          append(" --import=file:///root/.dshm/boot-cache.mjs")
        }
      },
      // Node 22+ V8 编译缓存：二次启动免重复解析 dsh 依赖树（proot 下 stat/open
      // 翻译开销大，冷启动 10-30s；缓存命中后明显提速）。bootstrap 已预热首份缓存。
      "NODE_COMPILE_CACHE=/root/.dshm/compile-cache",
      "NO_COLOR=1", // dsh 输出禁用 ANSI 颜色，保证 URL 正则稳定匹配
    )
    val argv = prootRuntime.buildArgs(
      rootfsDir,
      binds,
      envPairs,
      // pid 文件技巧（dsh-Go 同款）：echo $$ 写下 sh 的 pid，exec 后同一 pid 变成
      // guest node —— 停止时对它精确 SIGTERM（dsh dispose 插件后 0 退出），
      // proot 的 --kill-on-exit / proroot 的父子收尸随后收场。直接 TERM proot 是
      // 无效的：proot 忽略 SIGTERM/SIGINT/SIGHUP，旧实现因此每次都白等 5s 超时。
      listOf(
        "/bin/sh", "-c",
        "echo \$\$ > $GUEST_PID_FILE 2>/dev/null; exec dsh web --no-open --port $port",
      ),
    )
    return try {
      AppLog.log("engine", "启动 dsh web (port=$port, runtime=${prootRuntime.runtimeKind.id})")
      // 完整启动命令落日志：用户反馈「proot 启动命令和结果啥的也没有」，
      // 出问题时可对照命令逐段排查（不含敏感信息，token 由 dsh web 运行时生成）
      AppLog.log("engine", "容器命令: " + argv.joinToString(" "))
      engineLog.parentFile?.mkdirs()
      webUrl.value = null
      // 上次的 pid 文件必须先删：残留旧 pid 会误导停止逻辑（PID 复用防护见 readGuestEnginePid）
      runCatching { pidFile().delete() }
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
  }

  /**
   * 重启引擎（停止 + 再启动）：端口仍应答但 token 失效（401）时唯一可行的自愈
   * 手段 —— dsh web 的带 token 地址只打印在启动 stdout，旧进程不会重新打印，
   * 干等永远拿不到新凭据。由 AppViewModel 在凭据等待超时后调用。
   */
  fun restartEngine(port: Int = Paths.DSH_PORT): Boolean {
    synchronized(LOCK) {
      AppLog.log("engine", "重启引擎（获取新 token）")
      stopEngine()
      return startEngine(port)
    }
  }

  /**
   * proroot 启动失败的会话级自动回退：本次进程内改用 proot 重启一轮。
   * 触发场景：proroot 的 ldso 补丁注册表不认识 guest 的 glibc 版本（实测
   * Ubuntu 26.04 / glibc 2.43 → "patches skipped" 后 SIGSEGV exit 139），
   * 以及其他 proroot 组件缺陷。不回写用户持久化选择（重进 App 恢复原选），
   * 已回退过则不再重复（避免 proot 也失败时原地转圈）。
   */
  fun fallbackToProotAndRestart(port: Int = Paths.DSH_PORT): Boolean {
    synchronized(LOCK) {
      if (prootRuntime.sessionOverride == null) {
        prootRuntime.sessionOverride = ProotRuntime.RuntimeKind.PROOT
        AppLog.log("engine", "proroot 启动失败，本次会话自动回退 proot 重试（重进 App 后恢复所选运行时）")
      }
      stopEngine()
      return startEngine(port)
    }
  }

  /** 当前生效运行时（含会话级回退覆盖）；供启动流程判断是否需要回退。 */
  val runtimeKind: ProotRuntime.RuntimeKind get() = prootRuntime.runtimeKind

  /**
   * 停止引擎（pid 文件精确杀，典型耗时 ~1s，最坏 ~3s）。
   *
   * 时序（设计对齐 dsh-Go 的停机协议，替代旧实现「TERM proot 白等 5s」）：
   *  1. 读 pid 文件定位 guest node（cmdline+uid 双重验证防 PID 复用误杀），SIGTERM ——
   *     dsh 会 dispose 全部插件后以 0 退出；proot 的 --kill-on-exit / proroot 的
   *     父子收尸随后把启动器一并带走；
   *  2. 1s 内未收场 → SIGKILL guest node（android.os.Process.killProcess）；
   *  3. 启动器仍活着 → destroyForcibly()（SIGKILL），再给 0.5s；
   *  4. 端口仍应答（APP 重启附着等句柄丢失场景）→ /proc 特征扫描孤儿清理。
   */
  fun stopEngine() {
    synchronized(LOCK) {
    val proc = current
    val guestPid = readGuestEnginePid()
    if (guestPid != null) {
      runCatching { android.system.Os.kill(guestPid, android.system.OsConstants.SIGTERM) }
        .onFailure { AppLog.log("engine", "SIGTERM guest($guestPid) 失败: ${it.message}") }
    }
    if (proc != null) {
      try {
        if (!proc.waitFor(1, TimeUnit.SECONDS)) {
          if (guestPid != null) {
            AppLog.log("engine", "guest 1s 未退出，SIGKILL 兜底")
            runCatching { android.os.Process.killProcess(guestPid) }
          }
          if (!proc.waitFor(1, TimeUnit.SECONDS)) {
            AppLog.log("engine", "启动器 2s 未退出，SIGKILL 强制结束")
            proc.destroyForcibly()
            proc.waitFor(500, TimeUnit.MILLISECONDS)
          }
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
    runCatching { pidFile().delete() }
    current = null
    webUrl.value = null
    }
  }

  /** 宿主侧看到的 guest pid 文件（容器 /tmp 即 rootfs/tmp，无单独绑定）。 */
  private fun pidFile(): File = File(rootfsDir, "tmp/dshm-engine.pid")

  /**
   * 从 pid 文件读 guest node 的宿主 pid，并做防误杀验证：
   *  - /proc/<pid>/cmdline 必须同时含 "dsh" 与 "web"（exec 后的 node 命令行）；
   *  - /proc/<pid>/status 的 Uid 必须等于本应用 uid（hidepid/复用防护）。
   * 任一验证不过按「无 pid」处理（走孤儿扫描兜底）。
   */
  private fun readGuestEnginePid(): Int? {
    val f = pidFile()
    if (!f.isFile) return null
    val pid = runCatching { f.readText().trim().toIntOrNull() }.getOrNull() ?: return null
    if (pid <= 1 || pid == android.os.Process.myPid()) return null
    val cmdline = runCatching {
      File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
        .split('\u0000').filter { it.isNotBlank() }
    }.getOrNull() ?: return null
    if (!cmdline.any { it.contains("dsh") } || !cmdline.any { it.contains("web") }) return null
    val uidOk = runCatching {
      File("/proc/$pid/status").bufferedReader().useLines { lines ->
        lines.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.get(1)?.toInt()
      }
    }.getOrNull() == android.os.Process.myUid()
    if (!uidOk) return null
    return pid
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
    // 给 SIGTERM 最多 1.5 秒生效窗口，仍存活的（proot/tracee）直接 SIGKILL
    // （旧值 3s：停止链路压缩后，这里不再需要更长等待）
    val deadline = System.currentTimeMillis() + 1_500
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
