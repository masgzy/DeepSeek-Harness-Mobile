package io.github.masgzy.dshm.engine

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 容器运行时管理：proot 与 proroot 双运行时并存、可切换（设计对齐 DSHA 的 ContainerRuntime）。
 *
 *  - **proot**（Termux，默认稳定）：ptrace 系统调用翻译，`--kill-on-exit` 可靠；
 *  - **proroot**（coderredlab/proroot，实验）：进程内路径翻译（LD_PRELOAD + 二进制补丁），
 *    零 ptrace 开销 —— 引擎启动/文件 I/O 明显更快，但**没有 kill-on-exit**：
 *    杀掉启动器不会传播信号给容器内进程，停止必须按 pid 文件精确杀 guest node
 *    （见 EngineManager.stopEngine）。
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
 * 包签名信任，不受上述限制。注意配套
 * `packaging.jniLibs.useLegacyPackaging = true`（build.gradle.kts），否则
 * 新版 AGP 默认不把 .so 解压到磁盘，nativeLibraryDir 下将没有实体文件。
 *
 * ## 访客 ELF 如何绕开 W^X（proot）
 *
 * 容器内的访客二进制（bash/node）位于 rootfs（仍是 app_data_file），但
 * proot 会把 tracee 的 execve 改写为对 `PROOT_LOADER` 的 execve：loader
 * 是一个静态 ELF（无动态依赖，随 APK 签名信任），由它以 mmap 方式装载
 * 真正的访客程序。因此宿主侧唯一的 execve 入口都落在 nativeLibraryDir。
 *
 * ## proroot 五件套 + 双 shim（来源 DSH-APP/DSHA，abi 锁定见 fetch-proroot.sh）
 *
 *  - `libproroot.so` 启动器；`libproroot-runtime.so` guest 路径 hook 运行库；
 *    `libproroot-linker.so` 上游加载器；`libproroot-stub-loader.so` 静态 stub loader；
 *    `libproroot-bridge.so` 上游 bridge —— 五件必须同目录（启动器按 /proc/self/exe 找同伴）；
 *  - `libdsha-proroot-compat.so`：glibc sysconf(75/249/250) 代理 —— 修复 Android 13+
 *    「Fatal glibc error: sigstksz」断言崩溃（从 /proc/self/auxv 读 AT_MINSIGSTKSZ）；
 *  - `libdsha-proroot-linker.so`：无依赖入口适配器，把 compat shim 以
 *    `--preload` 接进上游加载器，同时保持 runtime 的直接预加载。
 *
 * 运行时选择持久化在 prefs（`container_runtime`），EngineManager / RuntimeInstaller /
 * RuntimeTrial / TerminalHolder 全部经由本类的 buildArgs/hostEnv，改一处全局生效。
 */
class ProotRuntime(private val context: Context) {

  enum class RuntimeKind(val id: String, val label: String, val desc: String) {
    PROOT("proot", "proot", "Termux · ptrace 翻译 · 稳定"),
    PROROOT("proroot", "proroot", "零 ptrace · 启动更快 · 实验性"),
  }

  private val prefs = context.getSharedPreferences("dshm", Context.MODE_PRIVATE)

  /** 用户选择的容器运行时（默认 proot；值非法时回退 proot）。 */
  val runtimeKind: RuntimeKind
    get() = when (prefs.getString(PREF_RUNTIME, RuntimeKind.PROOT.id)) {
      RuntimeKind.PROROOT.id -> RuntimeKind.PROROOT
      else -> RuntimeKind.PROOT
    }

  /** proroot 加载器模式：true = --static-loader + stub loader（DSHA 同款开关）。 */
  val prorootStaticLoader: Boolean
    get() = prefs.getBoolean(PREF_STATIC_LOADER, false)

  fun setRuntime(kind: RuntimeKind) {
    prefs.edit().putString(PREF_RUNTIME, kind.id).apply()
  }

  fun setProrootStaticLoader(enabled: Boolean) {
    prefs.edit().putBoolean(PREF_STATIC_LOADER, enabled).apply()
  }

  companion object {
    const val PREF_RUNTIME = "container_runtime"
    const val PREF_STATIC_LOADER = "proroot_static_loader"
  }

  private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)

  // ---- proot 组件（libdsh_ 前缀，fetch-proot.sh 注入） ----

  /** proot 本体（jniLibs 解压产物）。 */
  val prootBin: File get() = File(nativeLibDir, "libdsh_proot.so")

  /** 静态 loader（访客 execve 的真实入口）。 */
  val prootLoader: File get() = File(nativeLibDir, "libdsh_proot_loader.so")

  // ---- proroot 组件（五件套 + 双 shim，fetch-proroot.sh 注入） ----

  /** proroot 五件 + 两个兼容 shim：启动器要求同目录（按 /proc/self/exe dirname 找同伴）。 */
  val PROROOT_LIBS = listOf(
    "libproroot.so",
    "libproroot-runtime.so",
    "libproroot-linker.so",
    "libproroot-stub-loader.so",
    "libproroot-bridge.so",
    "libdsha-proroot-compat.so",
    "libdsha-proroot-linker.so",
  )

  private val prorootBin: File get() = File(nativeLibDir, "libproroot.so")

  private val tmpDir: File get() = File(context.cacheDir, Paths.PROOT_TMP_DIR)

  /** proroot 的 /dev/shm 映射源：Android 无 /dev/shm，用私有 cache 目录顶替。 */
  private val shmDir: File get() = File(context.cacheDir, "shm").apply { mkdirs() }

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
   * 运行器就绪判定：设备首选 ABI 必须是 arm64-v8a（模拟器经 libhoudini 转
   * 译时 SUPPORTED_ABIS 列表也会包含 arm64-v8a，真 execve 会 SIGSEGV，
   * 故只认首选 ABI），且所选运行时的全部可执行文件实体存在、可读。
   * 刻意不检查 canExecute()：部分 ROM 恒返回 false。
   */
  fun runnerAvailable(): Boolean {
    if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
    return when (runtimeKind) {
      RuntimeKind.PROOT ->
        prootBin.isFile && prootBin.canRead() && prootBin.length() > 0L &&
          prootLoader.isFile && prootLoader.canRead() && prootLoader.length() > 0L
      RuntimeKind.PROROOT -> prorootFilesComplete()
    }
  }

  /** proroot 七件是否全部就位；缺失清单仅在日志可见（不可用时 startEngine 会报）。 */
  private fun prorootFilesComplete(): Boolean =
    PROROOT_LIBS.all { name ->
      val f = File(nativeLibDir, name)
      f.isFile && f.canRead() && f.length() > 0L
    }

  /** 当前运行时缺失的组件名（就绪时为空列表）。 */
  fun missingComponents(): List<String> {
    val need = when (runtimeKind) {
      RuntimeKind.PROOT -> listOf(prootBin, prootLoader)
      RuntimeKind.PROROOT -> PROROOT_LIBS.map { File(nativeLibDir, it) }
    }
    return need.filter { !it.isFile || it.length() == 0L }.map { it.name }
  }

  /**
   * 构建所选运行时的 argv。
   * @param binds 形如 "宿主:容器内" 的绑定列表（"宿主" 单路径时挂到同路径）。
   * @param envPairs env -i 之后的 K=V 环境变量。
   * @param command 容器内要执行的命令（argv 形式）。
   */
  fun buildArgs(
    rootfsDir: File,
    binds: List<String>,
    envPairs: List<String>,
    command: List<String>,
  ): List<String> = when (runtimeKind) {
    RuntimeKind.PROOT -> buildProotArgs(rootfsDir, binds, envPairs, command)
    RuntimeKind.PROROOT -> buildProrootArgs(rootfsDir, binds, envPairs, command)
  }

  /** proot argv（默认稳定路径，保持既有行为）。 */
  private fun buildProotArgs(
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
    addGuestEnv(envPairs, command)
  }

  /**
   * proroot argv（DSHA ContainerRuntime.Proroot.baseArgv 同款模板）：
   * `libproroot.so [--static-loader|--no-static-loader] -r <rootfs> -0 -w /root
   *  [binds…] -b <shm>:/dev/shm --link2symlink`。
   * 与 proot 的差异：
   *  - 无 --kill-on-exit（proroot 无此选项；停止靠 pid 文件精确杀 guest）；
   *  - bind 必须显式 host:guest 成对形式；
   *  - --link2symlink 固定启用（proroot 路径翻译下硬链接场景与 proot 不同，
   *    DSHA 同款处理，避免 dpkg/pnpm 的链接语义差异）。
   */
  private fun buildProrootArgs(
    rootfsDir: File,
    binds: List<String>,
    envPairs: List<String>,
    command: List<String>,
  ): List<String> = buildList {
    add(prorootBin.absolutePath)
    add(if (prorootStaticLoader) "--static-loader" else "--no-static-loader")
    add("-r")
    add(rootfsDir.absolutePath)
    add("-0")
    add("-w")
    add("/root")
    // 公共 bind（DSHA ContainerRuntime.binds() 同款：/dev/urandom→/dev/random、
    // /proc/self/fd→/dev/fd；不绑 /linkerconfig —— 无权限、每次启动刷告警、纯负收益）
    val commonBinds = mutableListOf<String>()
    commonBinds += "/dev:/dev"
    commonBinds += "/dev/urandom:/dev/random"
    commonBinds += "/proc:/proc"
    commonBinds += "/sys:/sys"
    if (File("/system").isDirectory) commonBinds += "/system:/system"
    if (File("/apex").isDirectory) commonBinds += "/apex:/apex"
    commonBinds += "/proc/self/fd:/dev/fd"
    commonBinds += resolvConf().absolutePath + ":/etc/resolv.conf"
    for (b in commonBinds) {
      add("-b")
      add(b)
    }
    for (b in binds) {
      add("-b")
      // 调用方可能传单路径（挂同路径），proroot 要求显式成对
      add(if (b.contains(':')) b else "$b:$b")
    }
    add("-b")
    add(shmDir.absolutePath + ":/dev/shm")
    add("--link2symlink")
    addGuestEnv(envPairs, command)
  }

  /** 两种运行时共用的 guest 环境段：`/usr/bin/env -i …` + 命令。 */
  private fun MutableList<String>.addGuestEnv(envPairs: List<String>, command: List<String>) {
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

  /** 所选运行时进程自身的宿主侧环境（loader 定位 + 依赖解析 + 临时目录）。 */
  fun hostEnv(): Map<String, String> = when (runtimeKind) {
    RuntimeKind.PROOT -> {
      tmpDir.mkdirs()
      mapOf(
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
    RuntimeKind.PROROOT -> {
      tmpDir.mkdirs()
      shmDir.mkdirs()
      buildMap {
        put("PROROOT_TMP_DIR", tmpDir.absolutePath)
        // 原始 runtime 保持直接预加载，其路径 hook 才在查找范围内（DSHA 同款）
        put("PROROOT_LIB_PATH", File(nativeLibDir, "libproroot-runtime.so").absolutePath)
        // 入口适配器（DSHA 自研 shim）：负责把 sysconf 代理以 --preload 接进上游加载器
        put("PROROOT_LINKER_PATH", File(nativeLibDir, "libdsha-proroot-linker.so").absolutePath)
        if (prorootStaticLoader) {
          put("PROROOT_STUB_LOADER", File(nativeLibDir, "libproroot-stub-loader.so").absolutePath)
        }
        put("HOME", context.filesDir.absolutePath)
        put("LANG", "C.UTF-8")
      }
    }
  }
}
