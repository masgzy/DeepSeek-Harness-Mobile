package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行时试运行（RuntimeTrial，设计移植自 DSHA 的 RuntimeTrial 思想）：
 *
 * 任何「将要替换运行环境」的操作 —— 换 rootfs 镜像源重装、应用预装 bundle 升级、
 * 切换容器内镜像源 —— 在真正提交（改名替换 rootfs / 保留新配置）之前，先在
 * 隔离目录里用 proot 真实启动一次候选环境做验证：
 *
 *  - [verifyBootable]：候选 rootfs 能否完成一次最小访客启动（busybox sh echo +
 *    os-release）。拦住「解包完整但动态链路残废」的坏 rootfs（历史事故：
 *    SHA-256 通过、bin/sh 存在，但 bootstrap 在里面跑不起来，用户只能整装重试）。
 *  - [verifyDshStack]：候选 rootfs 里 node / dsh 真的可执行（预装 bundle 的
 *    musl/glibc 兼容性、软链完整性都只有真跑才知道）。
 *  - [verifyApkMirror] / [verifyAptMirror]：镜像源写入容器后，包管理器能否真的
 *    拉到索引（镜像测速只测了首字节延迟，404/403 的索引只有 update 一次才知道）。
 *
 * 验证失败时调用方保持现状不提交（老 rootfs 原地不动 / 镜像源回滚），用户拿到的
 * 永远是「验证过能用」的环境。试运行输出全部流式落安装日志，失败不静默。
 *
 * 超时语义（对齐 DSHA「慢启动不强杀」的边界）：试运行跑的是秒级命令（echo /
 * --version / apk update），不是 dsh web 冷启动 —— 卡死 120-240s 说明环境坏了，
 * 杀掉并判失败是正确行为，与引擎启动的「等待鉴权不设强杀时限」不冲突。
 */
class RuntimeTrial(private val context: Context) {

  private val prootRuntime = ProotRuntime(context)

  /** 单次试运行结果。 */
  data class Result(
    val ok: Boolean,
    /** 一句话结论（落日志 / 抛异常文案用）。 */
    val summary: String,
    val exitCode: Int? = null,
    /** 失败时的输出尾部（帮助定位）。 */
    val tail: List<String> = emptyList(),
  )

  /**
   * 最小启动验证：候选 rootfs 里执行 `/bin/sh -c 'echo …'` 并读取 os-release。
   * 通过标准：exit=0 且输出含 OK 标记（proot/loader/musl|glibc 链路全通才算）。
   */
  fun verifyBootable(rootfs: File, onLog: (String) -> Unit): Result {
    onLog("[trial] 试运行验证：最小启动（proot + busybox sh）…")
    val cmd = buildList {
      add("/bin/sh")
      add("-c")
      add("echo DSHM_TRIAL_OK; head -n1 /etc/os-release 2>/dev/null || true")
    }
    return runTrial(rootfs, cmd, expectedMarker = "DSHM_TRIAL_OK", timeoutS = 120, onLog = onLog)
  }

  /**
   * dsh 运行栈验证：候选 rootfs（应为已装好 Node + dsh 的预装 bundle）内执行
   * `dsh --version` 与 `node --version`。任一失败即不通过。
   */
  fun verifyDshStack(rootfs: File, onLog: (String) -> Unit): Result {
    onLog("[trial] 试运行验证：node / dsh 可执行性…")
    val cmd = buildList {
      add("/bin/sh")
      add("-c")
      add("node --version && dsh --version")
    }
    return runTrial(rootfs, cmd, expectedMarker = "v", timeoutS = 180, onLog = onLog)
  }

  /** apk 镜像验证：容器内 `apk update` 能否拉到索引（Alpine）。 */
  fun verifyApkMirror(rootfs: File, onLog: (String) -> Unit): Result {
    onLog("[trial] 试运行验证：apk update 拉取索引…")
    val cmd = listOf("/bin/sh", "-c", "apk update 2>&1 | tail -n 3")
    return runTrial(rootfs, cmd, expectedMarker = null, timeoutS = 240, onLog = onLog)
  }

  /** apt 镜像验证：容器内 `apt-get update` 能否拉到索引（Debian/Ubuntu）。 */
  fun verifyAptMirror(rootfs: File, onLog: (String) -> Unit): Result {
    onLog("[trial] 试运行验证：apt-get update 拉取索引…")
    val cmd = listOf(
      "/bin/sh", "-c",
      "apt-get update -o Acquire::Retries=1 2>&1 | tail -n 3",
    )
    return runTrial(rootfs, cmd, expectedMarker = null, timeoutS = 240, onLog = onLog)
  }

  /**
   * 试运行核心：对 [rootfs] 起 proot 执行 [cmd]，输出流式回调 [onLog] 并截尾留存。
   * 不绑定 workspace / 安装脚本（试运行必须无副作用、只读验证）；
   * resolv.conf 照常绑定（apk/apt 验证需要 DNS）。
   *
   * @param expectedMarker 非 null 时要求输出包含该标记（exit=0 之外的第二道确认，
   *   防某些访客 shell 异常退出但 exit 仍为 0 的 ROM 差异）。
   */
  private fun runTrial(
    rootfs: File,
    cmd: List<String>,
    expectedMarker: String?,
    timeoutS: Long,
    onLog: (String) -> Unit,
  ): Result {
    if (!prootRuntime.runnerAvailable()) {
      return Result(false, "proot 运行器缺失，无法试运行验证")
    }
    // 试运行不做任何镜像注入：验证的就是候选环境「自身」的配置
    val envPairs = listOf("DEBIAN_FRONTEND=noninteractive")
    val argv = prootRuntime.buildArgs(
      rootfs,
      binds = emptyList(),
      envPairs = envPairs,
      command = cmd,
    )
    AppLog.log("trial", "exec: " + argv.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
    var process: Process? = null
    return try {
      val builder = ProcessBuilder(argv)
      builder.environment().putAll(prootRuntime.hostEnv())
      builder.redirectErrorStream(true)
      val proc = builder.start()
      process = proc
      val tail = ArrayDeque<String>(16)
      val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
      Thread {
        try {
          while (true) {
            val line = reader.readLine() ?: break
            onLog("[trial] $line")
            synchronized(tail) {
              if (tail.size >= 12) tail.removeFirst()
              tail.addLast(line)
            }
          }
        } catch (_: Exception) {
        }
      }.apply { isDaemon = true }.start()

      val finished = proc.waitFor(timeoutS, TimeUnit.SECONDS)
      if (!finished) {
        runCatching { proc.destroy() }
        if (!proc.waitFor(5, TimeUnit.SECONDS)) runCatching { proc.destroyForcibly() }
        return Result(
          ok = false,
          summary = "试运行超时（${timeoutS}s 未退出，环境疑似损坏）",
          exitCode = null,
          tail = synchronized(tail) { tail.toList() },
        )
      }
      val code = proc.exitValue()
      val out = synchronized(tail) { tail.toList() }
      val markerOk = expectedMarker == null || out.any { it.contains(expectedMarker) }
      when {
        code == 0 && markerOk -> Result(true, "试运行通过", exitCode = 0)
        code == 0 -> Result(
          false, "试运行退出码 0 但未出现预期标记（访客环境异常）", exitCode = 0, tail = out,
        )
        else -> Result(false, "试运行退出码=$code", exitCode = code, tail = out)
      }
    } catch (t: Throwable) {
      AppLog.log("trial", "试运行执行异常", t)
      Result(false, "试运行执行异常：${t.message ?: t.javaClass.simpleName}")
    } finally {
      // 兜底清理：任何路径都不允许试运行进程泄漏
      process?.let { p -> runCatching { if (p.isAlive) p.destroyForcibly() } }
    }
  }
}
