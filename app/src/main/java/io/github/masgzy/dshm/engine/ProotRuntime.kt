package io.github.masgzy.dshm.engine

import android.content.Context
import android.os.Build
import java.io.File

/**
 * proot 运行时管理：proot 本体与 loader 以 jniLibs 形式随 APK 分发
 * （`libdsh_proot.so` / `libdsh_proot_loader.so`，由 CI 的 fetch-proot.sh
 * 从 Termux 官方 deb 提取），依赖库 `libtalloc.so` / `libandroid-shmem.so` 同理。
 *
 * ## 为什么必须从 nativeLibraryDir 执行（而不是解压到 filesDir）
 *
 * Android 10 起，targetSdk >= 29 的应用被 SELinux W^X 策略禁止 exec 应用
 * 数据目录（filesDir/noBackupFilesDir/cacheDir 等，统一标签 app_data_file）
 * 中的 ELF —— 表现为 `execve` 返回 EACCES，即 ProcessBuilder 的
 * `error=13, Permission denied`。v1.0.0-alpha.1 的 assets 方案在真机上
 * 必然触发该错误。
 *
 * `nativeLibraryDir`（APK lib/<abi>/ 的解压产物）由系统安装器落地并受
 * 包签名信任，不受上述限制 —— 这是 dphmoblie/deepseek-harness-android
 * （targetSdk 35）在真机上验证过的方案。注意配套
 * `packaging.jniLibs.useLegacyPackaging = true`（build.gradle.kts），否则
 * 新版 AGP 默认不把 .so 解压到磁盘，nativeLibraryDir 下将没有实体文件。
 *
 * ## 访客 ELF 如何绕开 W^X
 *
 * 容器内的访客二进制（bash/node）位于 rootfs（仍是 app_data_file），但
 * proot 会把 tracee 的 execve 改写为对 `PROOT_LOADER` 的 execve：loader
 * 是一个静态 ELF（无动态依赖，随 APK 签名信任），由它以 mmap 方式装载
 * 真正的访客程序。因此宿主侧唯一的 execve 入口都落在 nativeLibraryDir。
 *
 * ## 环境变量（与参考实现/termux-chroot 对齐）
 *
 *  - `PROOT_LOADER`：指向 jniLibs 中的 loader；不设时 proot 会把内嵌
 *    loader 解到 PROOT_TMP_DIR 再 exec —— 那是应用数据目录，必被 W^X 拒绝。
 *  - `LD_LIBRARY_PATH`：指向 nativeLibraryDir，供本体解析补丁后的
 *    `libtalloc.so` 与 `libandroid-shmem.so`。
 *  - `PROOT_TMP_DIR`/`TMPDIR`：proot 自身临时目录（cacheDir 下）。
 *
 * 兼容性注：部分 ROM（如荣耀）上 nativeLibraryDir 文件的
 * `File.canExecute()` 恒为 false，故就绪判定只看「存在且可读」，不依赖 x 位。
 */
class ProotRuntime(private val context: Context) {

  private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)

  /** proot 本体（jniLibs 解压产物）。 */
  val prootBin: File get() = File(nativeLibDir, "libdsh_proot.so")

  /** 静态 loader（访客 execve 的真实入口）。 */
  val prootLoader: File get() = File(nativeLibDir, "libdsh_proot_loader.so")

  private val tmpDir: File get() = File(context.cacheDir, Paths.PROOT_TMP_DIR)

  /**
   * 运行器就绪判定：设备首选 ABI 必须是 arm64-v8a（模拟器经 libhoudini 转
   * 译时 SUPPORTED_ABIS 列表也会包含 arm64-v8a，真 execve 会 SIGSEGV，
   * 故只认首选 ABI），且两个可执行文件实体存在、可读。
   * 刻意不检查 canExecute()：部分 ROM 恒返回 false（见类注释）。
   */
  fun runnerAvailable(): Boolean {
    if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
    return prootBin.isFile && prootBin.canRead() && prootBin.length() > 0L &&
      prootLoader.isFile && prootLoader.canRead() && prootLoader.length() > 0L
  }

  /** 容器内 DNS：阿里 DoH 常规 DNS 优先（国内可达），Google 兜底。 */
  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile || f.length() == 0L) {
      f.parentFile?.mkdirs()
      f.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")
    }
    return f
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
    // Android 10+ 需要绑 /apex 与 /linkerconfig，容器内 glibc 动态链接器
    // 才能找到系统组件（termux-chroot 同款；对纯静态/自带 loader 的访客无副作用）
    if (File("/apex").isDirectory) {
      add("-b")
      add("/apex:/apex")
    }
    if (File("/linkerconfig/ld.config.txt").isFile) {
      add("-b")
      add("/linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt")
    }
    add("-b")
    add(resolvConf().absolutePath + ":/etc/resolv.conf")
    for (b in binds) {
      add("-b")
      add(b)
    }
    add("-w")
    add("/root")
    add("/usr/bin/env")
    add("-i")
    add("HOME=/root")
    add("USER=root")
    add("LOGNAME=root")
    add("LANG=C.UTF-8")
    add("TERM=xterm-256color")
    add("SHELL=/bin/bash")
    // pnpm 全局 bin（$PNPM_HOME/bin）纳入 PATH 兜底：bootstrap 正常会把 dsh 软链到
    // /usr/local/bin，但自定义镜像/老安装标记下可能缺失，多一个搜索点无害
    add("PATH=/root/.local/share/pnpm/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    for (e in envPairs) add(e)
    addAll(command)
  }

  /** proot 进程自身的宿主侧环境（loader 定位 + 本体依赖解析 + 临时目录）。 */
  fun hostEnv(): Map<String, String> {
    tmpDir.mkdirs()
    return mapOf(
      "PROOT_LOADER" to prootLoader.absolutePath,
      "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
      "PROOT_TMP_DIR" to tmpDir.absolutePath,
      "TMPDIR" to tmpDir.absolutePath,
      "HOME" to context.filesDir.absolutePath,
      "LANG" to "C.UTF-8",
      // proot 在 Android 上运行所需的经典环境标记（保持 bionic 视图一致）
      "ANDROID_DATA" to "/data",
      "ANDROID_ROOT" to "/system",
    )
  }
}
