package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.Downloader
import java.io.File

/**
 * 一键安装器：把 Node.js + @deepseek-ai/dsh 装进 proot 容器（发行版与版本可选）。
 *
 * 流程（bootstrap.sh 在容器内执行）：
 *  1. GLIBC 族：宿主侧下载所选版本 node-vX-linux-arm64.tar.gz（官方 + npmmirror 双镜像，
 *     SHA-256 取自官方 SHASUMS256.txt）；
 *     ALPINE 族：musl 不兼容官方 glibc tarball，改为容器内 apk add nodejs npm；
 *  2. tarball 以 bind 方式注入容器 /dshm/node.tar.gz（容器内无需 wget/curl）；
 *  3. proot 起容器执行 /dshm/bootstrap.sh：装 Node（tarball/apk）→ npm 全局安装
 *     所选版本 dsh → 写入就绪标记；
 *  4. 宿主侧验证标记文件 root/.dshm/installed 存在。
 */
class RuntimeInstaller(private val context: Context) {

  private val prootRuntime = ProotRuntime(context)

  val isDshInstalled: Boolean get() = Paths.dshInstalled(Paths.rootfs(context.filesDir))

  /**
   * 执行一键安装。阻塞运行，必须在后台线程调用。
   * @param distro 当前容器系统。
   * @param npmRegistry npm 源（默认 npmmirror 国内加速）。
   * @param nodeVersion Node.js 版本（"v24.22.0"；Alpine 族忽略，用 apk 版本）。
   * @param dshVersion @deepseek-ai/dsh 版本；空串 = npm latest。
   * @throws Exception 任何一步失败。
   */
  fun install(
    distro: Distro,
    npmRegistry: String,
    nodeVersion: String,
    dshVersion: String,
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

    // 1) Node.js 供应：GLIBC = 宿主下载 tarball；ALPINE = 容器内 apk
    val nodeArchive: File? = when (distro.family) {
      DistroFamily.ALPINE -> {
        onLog("Alpine (musl) 将在容器内通过 apk 安装 Node.js（官方 tarball 不兼容 musl）")
        null
      }
      DistroFamily.GLIBC -> {
        val version = NodeCatalog.normalize(nodeVersion) ?: Paths.NODE_VERSION
        downloadNode(version, npmRegistry, onProgress, onLog)
      }
    }

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
    // 工作区目录必须在启动 proot 前落盘：proot 对 -b 绑定源做 realpath 解析，
    // 源不存在时打印
    //   proot warning: can't sanitize binding "…/files/workspace": No such file or directory
    // 并丢弃该绑定 —— 首次安装后容器内 /root/projects 将不可用。
    val workspace = Paths.workspace(filesDir).apply { mkdirs() }
    val binds = buildList {
      nodeArchive?.let { add(it.absolutePath + ":/dshm/node.tar.gz") }
      add(script.absolutePath + ":/dshm/bootstrap.sh")
      add(workspace.absolutePath + ":/root/projects")
    }
    val isCn = npmRegistry == Paths.NPM_REGISTRY_CN
    val envPairs = buildList {
      add("DEBIAN_FRONTEND=noninteractive")
      add("DSH_VERSION=$dshVersion")
      add("DSH_NPM_REGISTRY=$npmRegistry")
      add("DSHM_DISTRO_ID=${distro.id}")
      add("DSHM_FAMILY=${distro.family.name.lowercase()}")
      if (distro.family == DistroFamily.ALPINE) {
        add("ALPINE_MIRROR=${if (isCn) Paths.ALPINE_MIRROR_CN else Paths.ALPINE_MIRROR_OFFICIAL}")
      } else {
        add("NODE_SHA256=$nodeSha256")
        // 国内源时顺带换 apt 源（best-effort，失败不影响安装）
        if (isCn) add("DSHM_APT_MIRROR=${Paths.APT_MIRROR_CN}")
      }
    }
    // Alpine 首轮用 busybox sh（bash 由 bootstrap 装好后再重入）
    val shell = if (distro.family == DistroFamily.ALPINE) "/bin/sh" else "/bin/bash"
    val argv = prootRuntime.buildArgs(
      rootfsDir, binds, envPairs, listOf(shell, "/dshm/bootstrap.sh"),
    )
    val exit = runProot(argv, onLog)
    AppLog.log("bootstrap", "bootstrap 退出码=$exit")
    if (exit != 0 || !Paths.dshInstalled(rootfsDir)) {
      throw RuntimeException("bootstrap 失败（exit=$exit），详情见日志页")
    }
    onProgress("dsh 安装完成", 1, 1)
  }

  private var nodeSha256: String = Paths.NODE_SHA256

  /** 下载所选版本 Node tarball（已有校验通过的缓存则复用），返回归档文件与 SHA-256。 */
  private fun downloadNode(
    version: String,
    npmRegistry: String,
    onProgress: (step: String, done: Long, total: Long) -> Unit,
    onLog: (String) -> Unit,
  ): File {
    val filesDir = context.filesDir
    val preferCn = npmRegistry == Paths.NPM_REGISTRY_CN
    // SHA-256 从官方 SHASUMS256.txt 解析（默认版本保持既有锁定哈希，省一次请求）
    val sha = if (version == Paths.NODE_VERSION) {
      Paths.NODE_SHA256
    } else {
      onProgress("获取 Node.js SHA-256", 0, 1)
      NodeCatalog.sha256For(version, preferCn).also { onLog("Node $version SHA-256=$it") }
    }
    nodeSha256 = sha
    val archive = File(Paths.cache(filesDir), NodeCatalog.tarballName(version))
    onProgress("下载 Node.js $version", 0, 1)
    Downloader.download(
      NodeCatalog.tarballMirrors(version), archive, sha,
    ) { done, total -> onProgress("下载 Node.js $version", done, total) }
    return archive
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
