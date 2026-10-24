package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import java.io.File

/**
 * dsh 引擎生命周期管理：proot 内启动 `dsh web`，输出落到 engine.log。
 * 进程仅由本类与 EngineService（看门狗）管理。
 */
class EngineManager(private val context: Context) {

  private val prootRuntime = ProotRuntime(context)

  val rootfsDir: File get() = Paths.rootfs(context.filesDir)
  val engineLog: File get() = Paths.engineLog(context.filesDir)

  /** 全局进程持有者：UI 与 EngineService 是不同实例，共享同一个引擎进程。 */
  companion object {
    @Volatile
    private var current: Process? = null
  }

  /** 容器 + dsh 均就绪才允许启动。 */
  val engineReady: Boolean
    get() = Paths.rootfsUsable(rootfsDir) && Paths.dshInstalled(rootfsDir)

  /** 是否有存活的引擎进程（端口应答视为运行中）。 */
  val isRunning: Boolean
    get() = (current?.isAlive == true) || EngineProbe.check()

  /**
   * 启动引擎。幂等：已在运行则直接返回。
   * @return 是否成功拉起（进程启动 ≠ 服务就绪，就绪以 EngineProbe 为准）。
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
    val workspace = Paths.workspace(context.filesDir).apply { mkdirs() }
    val binds = listOf(
      workspace.absolutePath + ":/root/projects",
    )
    val envPairs = listOf(
      "DSH_HOME=/root/.dsh",
      "DSH_PICK_DISABLE_UPDATE_CHECK=1",
      "NODE_OPTIONS=--max-old-space-size=512",
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
      val builder = ProcessBuilder(argv)
      builder.environment().putAll(prootRuntime.hostEnv())
      builder.redirectErrorStream(true)
      builder.redirectOutput(ProcessBuilder.Redirect.appendTo(engineLog))
      current = builder.start()
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
    }
  }
}
