package io.github.masgzy.dshm.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.engine.ProotRuntime
import io.github.masgzy.dshm.util.AppLog

/**
 * 容器终端会话持有器（进程级单例）。
 *
 * 会话 = proot 容器内 `/bin/bash -l`（与 dsh web 同一 rootfs/绑定，独立实例）。
 * 会话跨屏幕旋转与前后台切换保持（Termux 同策略）；显式 [close]（exit / 终止按钮 /
 * 卸载运行环境）或应用进程死亡时回收。
 */
object TerminalHolder {

  @Volatile
  private var session: TerminalSession? = null

  /** 当前会话（可能已退出）；退出会话在下次进入时替换为新会话。 */
  fun current(): TerminalSession? = session

  /**
   * 取活跃会话：无会话或上一会话已退出时新开一个。
   * @return null = 前置条件不满足（rootfs 未就绪 / 运行器缺失）。
   */
  @Synchronized
  fun obtain(context: Context, client: TerminalSessionClient): TerminalSession? {
    session?.let { if (it.isRunning) return it }
    val filesDir = context.filesDir
    val rootfsDir = Paths.rootfs(filesDir)
    if (!Paths.rootfsUsable(rootfsDir)) return null
    val prootRuntime = ProotRuntime(context)
    if (!prootRuntime.runnerAvailable()) return null

    val workspace = Paths.workspace(filesDir).apply { mkdirs() }
    val argv = prootRuntime.buildArgs(
      rootfsDir,
      binds = listOf(workspace.absolutePath + ":/root/projects"),
      envPairs = listOf(
        "DSH_PICK_DISABLE_UPDATE_CHECK=1",
        // 终端里保留颜色；仅 dsh web 的 stdout 正则解析需要 NO_COLOR
        "TERM=xterm-256color",
      ),
      command = listOf("/bin/bash", "-l"),
    )
    val env = prootRuntime.hostEnv().map { (k, v) -> "$k=$v" }.toTypedArray()
    AppLog.log("terminal", "新建容器终端会话")
    val s = TerminalSession(
      prootRuntime.prootBin.absolutePath,
      filesDir.absolutePath,
      argv.toTypedArray(),
      env,
      200, // transcript 回滚行数
      client,
    )
    session = s
    return s
  }

  /** 关闭会话（容器内子进程随 --kill-on-exit 一并回收）。 */
  @Synchronized
  fun close() {
    session?.finishIfRunning()
    session = null
    AppLog.log("terminal", "终端会话已关闭")
  }
}
