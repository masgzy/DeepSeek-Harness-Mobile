package io.github.masgzy.dshm.engine

import android.content.Context
import java.io.File

/**
 * proot 运行时管理：从 APK assets 提取 Termux proot + libtalloc +
 * libandroid-shmem（arm64），并负责构建 proot 启动参数。
 *
 * 采用 deepcode-lab/deepseek-harness-mobile 已验证的 assets 方案：
 *  - 提取到 filesDir 后立即去除写位（W^X）：华为/EMUI 拒绝执行可写文件；
 *  - LD_LIBRARY_PATH 只指向 proot 自身目录（其依赖是 bionic 动态库）；
 *  - 容器内环境用 env -i 重建（纯 glibc 路径，不混入 Android 环境）。
 */
class ProotRuntime(private val context: Context) {

  val prootDir: File get() = File(context.filesDir, Paths.PROOT_DIR)
  val prootBin: File get() = File(prootDir, "proot")
  private val tallocLib: File get() = File(prootDir, "libtalloc.so.2")
  private val shmemLib: File get() = File(prootDir, "libandroid-shmem.so")

  /** 容器内 DNS：阿里 DoH 常规 DNS 优先（国内可达），Google 兜底。 */
  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile || f.length() == 0L) {
      f.parentFile?.mkdirs()
      f.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")
    }
    return f
  }

  private fun extractAsset(name: String, target: File, exec: Boolean): Boolean =
    try {
      if (target.isFile && target.length() > 0L) return true
      target.parentFile?.mkdirs()
      context.assets.open("proot/arm64-v8a/$name").use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
      target.setExecutable(exec, true)
      // W^X：可执行文件必须不可写（华为/EMUI 安全策略）
      if (exec) target.setWritable(false, false)
      true
    } catch (t: Throwable) {
      io.github.masgzy.dshm.util.AppLog.log("proot", "资产提取失败: $name", t)
      false
    }

  /** 提取 proot 三件套；三个文件必须齐全，否则返回 false。 */
  fun ensureProot(): Boolean {
    if (prootBin.isFile && prootBin.length() > 0L &&
      tallocLib.isFile && tallocLib.length() > 0L &&
      shmemLib.isFile && shmemLib.length() > 0L
    ) return true
    val okBin = extractAsset("proot", prootBin, exec = true)
    val okTalloc = extractAsset("libtalloc.so.2", tallocLib, exec = false)
    val okShmem = extractAsset("libandroid-shmem.so", shmemLib, exec = false)
    io.github.masgzy.dshm.util.AppLog.log(
      "proot", "ensureProot bin=$okBin talloc=$okTalloc shmem=$okShmem",
    )
    return okBin && okTalloc && okShmem
  }

  /**
   * 构建 proot argv。
   * @param binds 形如 "宿主:容器内" 的绑定列表（"宿主" 单路径时挂到同路径）。
   * @param envPairs env -i 之后的 K=V 环境变量。
   * @param command 容器内要执行的命令（argv 形式）。
   */
  fun buildArgs(
    rootfsDir: File,
    binds: List<String>,
    envPairs: List<String>,
    command: List<String>,
  ): List<String> = buildList {
    add(prootBin.absolutePath)
    add("--kill-on-exit")
    add("-0") // 伪装 root（proot 常规操作，无真实提权）
    add("-r")
    add(rootfsDir.absolutePath)
    add("-b")
    add("/dev")
    add("-b")
    add("/proc")
    add("-b")
    add("/sys")
    add("-b")
    add(resolvConf().absolutePath + ":/etc/resolv.conf")
    for (b in binds) {
      add("-b")
      add(b)
    }
    add("-w")
    add("/root")
    add("--")
    add("/usr/bin/env")
    add("-i")
    add("HOME=/root")
    add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    add("TERM=xterm-256color")
    for (e in envPairs) add(e)
    addAll(command)
  }

  /** proot 进程自身的宿主侧环境（bionic 动态库检索路径）。 */
  fun hostEnv(): Map<String, String> = mapOf("LD_LIBRARY_PATH" to prootDir.absolutePath)
}
