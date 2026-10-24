package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.Downloader
import java.io.File

/**
 * 一键安装器：把 Node.js LTS tarball + @deepseek-ai/dsh 装进 proot 容器。
 *
 * 流程（bootstrap.sh 在容器内执行）：
 *  1. 宿主侧下载 node-v24.22.0-linux-arm64.tar.gz（官方 + npmmirror 双镜像，SHA-256 校验）；
 *  2. tarball 以 bind 方式注入容器 /dshm/node.tar.gz（容器内无需 wget/curl）；
 *  3. proot 起容器执行 /dshm/bootstrap.sh：解压 Node → npm 全局安装 dsh → 写入就绪标记；
 *  4. 宿主侧验证标记文件 root/.dshm/installed 存在。
 */
class RuntimeInstaller(private val context: Context) {

  private val prootRuntime = ProotRuntime(context)

  val isDshInstalled: Boolean get() = Paths.dshInstalled(Paths.rootfs(context.filesDir))

  /**
   * 执行一键安装。阻塞运行，必须在后台线程调用。
   * @param npmRegistry npm 源（默认 npmmirror 国内加速）。
   * @throws Exception 任何一步失败。
   */
  fun install(
    npmRegistry: String,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
    onLog: (String) -> Unit,
  ) {
    val filesDir = context.filesDir
    val rootfsDir = Paths.rootfs(filesDir)
    check(Paths.rootfsUsable(rootfsDir)) { "rootfs 未就绪，请先安装容器" }
    if (isDshInstalled) {
      onProgress("dsh 已安装", 1, 1)
      return
    }
    if (!prootRuntime.runnerAvailable()) {
      throw RuntimeException(
        "proot 运行器缺失（该 APK 构建未包含 arm64-v8a 运行库，请从官方 Release 重新下载）",
      )
    }

    // 1) 下载 Node.js（已有校验通过的缓存则复用）
    val nodeArchive = File(Paths.cache(filesDir), Paths.NODE_TARBALL)
    onProgress("下载 Node.js ${Paths.NODE_VERSION}", 0, 1)
    Downloader.download(
      Paths.NODE_MIRRORS, nodeArchive, Paths.NODE_SHA256,
    ) { done, total -> onProgress("下载 Node.js ${Paths.NODE_VERSION}", done, total) }

    // 2) 注入 bootstrap 脚本
    val scriptDir = File(filesDir, "dshm")
    scriptDir.mkdirs()
    val script = File(scriptDir, "bootstrap.sh")
    context.assets.open("scripts/bootstrap.sh").use { input ->
      script.outputStream().use { out -> input.copyTo(out) }
    }
    script.setExecutable(true, true)

    // 3) proot 内执行 bootstrap
    onProgress("容器内安装 Node.js + dsh", 0, 1)
    val binds = listOf(
      nodeArchive.absolutePath + ":/dshm/node.tar.gz",
      script.absolutePath + ":/dshm/bootstrap.sh",
      Paths.workspace(filesDir).absolutePath + ":/root/projects",
    )
    val envPairs = listOf(
      "DEBIAN_FRONTEND=noninteractive",
      "DSH_VERSION=${Paths.DSH_VERSION}",
      "DSH_NPM_REGISTRY=$npmRegistry",
      "NODE_SHA256=${Paths.NODE_SHA256}",
    )
    val argv = prootRuntime.buildArgs(
      rootfsDir, binds, envPairs, listOf("/bin/bash", "/dshm/bootstrap.sh"),
    )
    val exit = runProot(argv, onLog)
    AppLog.log("bootstrap", "bootstrap 退出码=$exit")
    if (exit != 0 || !Paths.dshInstalled(rootfsDir)) {
      throw RuntimeException("bootstrap 失败（exit=$exit），详情见日志页")
    }
    onProgress("dsh 安装完成", 1, 1)
  }

  /** 运行 proot 子进程并把输出流式回调（供 UI 日志区展示）。 */
  fun runProot(argv: List<String>, onLog: (String) -> Unit): Int {
    AppLog.log("proot", "exec: " + argv.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
    val builder = ProcessBuilder(argv)
    builder.environment().putAll(prootRuntime.hostEnv())
    builder.redirectErrorStream(true)
    val process = builder.start()
    // 读取输出（必须在 waitFor 之前，否则缓冲区写满会死锁）
    val reader = process.inputStream.bufferedReader()
    Thread {
      try {
        while (true) {
          val line = reader.readLine() ?: break
          onLog(line)
        }
      } catch (_: Exception) {
      }
    }.apply { isDaemon = true }.start()
    return process.waitFor()
  }
}
