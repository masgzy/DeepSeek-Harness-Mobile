package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * 应用内插件管理：基于容器内 `dsh plugin --profile web <pnpm args>`
 * （转发给 profile 目录中的 pnpm，管理 $DSH_HOME/profiles/web 下的树外插件）。
 *
 * ## 插件落在哪
 *
 * - profile 目录：容器内 `/root/.dsh/profiles/web`，宿主侧 `<rootfs>/root/.dsh/profiles/web`；
 * - `package.json` 的 `dependencies` 就是树外插件清单（pnpm add/remove 会维护它）；
 * - `dsh.profile.bundles`（package.json 内嵌）是运行时内置组合包（base/web-app），
 *   随 dsh 安装解析，不归插件管理增删，只读展示。
 *
 * ## 为什么列表不走 dsh plugin
 *
 * `dsh plugin` 仅转发 pnpm 参数；列表信息（名称/版本/是否内置）宿主侧直接读
 * profile 的 package.json 与 node_modules 即可，零进程开销、引擎运行中也可看。
 * 变更类操作（add/remove/update）必须走 proot 里的 pnpm，且要求引擎停止
 * （安装会重写 node_modules，运行中的 dsh 持有旧文件句柄，热更换可能崩溃）。
 *
 * ## pnpm 依赖
 *
 * `dsh plugin` 需要 PATH 上有 pnpm。bootstrap 已在新装容器时预装；存量容器
 * 首次进入插件管理时经 [ensurePnpm] 按需补装（走应用设置的 npm 源）。
 */
class PluginManager(private val context: Context) {

  private val prootRuntime = ProotRuntime(context)

  companion object {
    /** profile 内 package.json（插件清单）。 */
    fun profilePackageFile(rootfsDir: File): File =
      File(rootfsDir, "root/.dsh/profiles/web/package.json")

    /** pnpm 补装标记（容器内 /root/.dshm/pnpm-installed）。 */
    fun pnpmMarker(rootfsDir: File): File =
      File(rootfsDir, "root/.dshm/pnpm-installed")
  }

  // ---- 数据模型 ----

  /** 已安装插件条目。 */
  data class InstalledPlugin(
    val name: String,
    /** node_modules 中实际版本；读取失败时回退 package.json 的 range。 */
    val version: String,
    /** 运行时内置组合包（dsh.profile.bundles），不可增删。 */
    val builtin: Boolean,
    /** 声明在 package.json dependencies 中。 */
    val declared: Boolean,
  )

  /** 插件目录快照。 */
  data class Catalog(
    val plugins: List<InstalledPlugin>,
    val pnpmReady: Boolean,
    val profileExists: Boolean,
  )

  // ---- 列表（纯宿主侧读文件） ----

  fun catalog(rootfsDir: File = Paths.rootfs(context.filesDir)): Catalog {
    val pkg = profilePackageFile(rootfsDir)
    if (!pkg.isFile) return Catalog(emptyList(), pnpmReady(rootfsDir), profileExists = false)
    val deps = LinkedHashMap<String, String>()
    val bundles = LinkedHashSet<String>()
    try {
      val obj = JSONObject(pkg.readText())
      val d = obj.optJSONObject("dependencies")
      d?.let { for (k in it.keys()) deps[k] = it.optString(k) }
      obj.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")?.let { arr ->
        for (i in 0 until arr.length()) bundles.add(arr.optString(i))
      }
    } catch (t: Throwable) {
      AppLog.log("plugin", "profile package.json 解析失败", t)
    }
    val nodeModules = pkg.parentFile?.let { File(it, "node_modules") }
    val plugins = mutableListOf<InstalledPlugin>()
    // 内置组合包：来自 dsh 安装目录，node_modules 里通常没有；版本从全局 dsh 依赖不可得，展示 profile 声明
    bundles.forEach { name ->
      plugins.add(InstalledPlugin(name, versionOf(nodeModules, name) ?: "（随运行时）", builtin = true, declared = deps.containsKey(name)))
    }
    // 树外插件：dependencies 逐个读实际版本
    deps.forEach { (name, range) ->
      if (name in bundles) return@forEach
      plugins.add(InstalledPlugin(name, versionOf(nodeModules, name) ?: range, builtin = false, declared = true))
    }
    return Catalog(plugins, pnpmReady(rootfsDir), profileExists = true)
  }

  private fun versionOf(nodeModules: File?, name: String): String? {
    if (nodeModules == null) return null
    val pkgFile = if (name.startsWith("@")) {
      val slash = name.indexOf('/')
      File(nodeModules, "${name.substring(0, slash)}/${name.substring(slash + 1)}/package.json")
    } else {
      File(nodeModules, "$name/package.json")
    }
    if (!pkgFile.isFile) return null
    return try { JSONObject(pkgFile.readText()).optString("version").takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }
  }

  /** pnpm 是否就绪（bootstrap 新装容器自带；存量容器看标记或容器内探测）。 */
  fun pnpmReady(rootfsDir: File = Paths.rootfs(context.filesDir)): Boolean {
    if (pnpmMarker(rootfsDir).isFile) return true
    // 标记缺失但 pnpm 可能存在（用户手工装过）：二进制存在即认可
    return File(rootfsDir, "usr/local/bin/pnpm").isFile
  }

  // ---- 变更操作（proot 内执行，须停引擎） ----

  /**
   * 确保容器内有 pnpm；没有则在容器内 `npm install -g pnpm`（用应用设置的 npm 源）。
   * 阻塞执行，须后台线程调用。成功后写标记。
   */
  fun ensurePnpm(onLog: (String) -> Unit) {
    val rootfsDir = Paths.rootfs(context.filesDir)
    if (pnpmReady(rootfsDir)) return
    check(Paths.rootfsUsable(rootfsDir)) { "容器未就绪" }
    check(prootRuntime.runnerAvailable()) { "proot 运行器缺失" }
    onLog("未检测到 pnpm，正在安装（npm install -g pnpm）...")
    val exit = RuntimeInstaller(context).runProot(
      prootRuntime.buildArgs(
        rootfsDir,
        emptyList(),
        listOf("DSH_NPM_REGISTRY=${npmRegistry()}"),
        listOf("/bin/bash", "-lc", "npm install -g --no-audit --no-fund --loglevel=error pnpm && command -v pnpm"),
      ),
      onLog,
    )
    if (exit != 0 || !pnpmReady(rootfsDir)) {
      throw RuntimeException("pnpm 安装失败（exit=$exit），可在「环境」页查看日志后重试")
    }
    // 写标记（容器内 /root/.dshm 可能不存在 —— 首次 bootstrap 前提已保证存在）
    val markerDir = pnpmMarker(rootfsDir).parentFile
    markerDir?.mkdirs()
    pnpmMarker(rootfsDir).writeText("pnpm\ninstalled_at=${System.currentTimeMillis()}\n")
    onLog("pnpm 就绪")
  }

  /**
   * 执行插件变更：`dsh plugin --profile web <args>`。
   * 阻塞运行，须后台线程调用；输出流式回调。引擎必须处于停止状态。
   *
   * @param args 例如 ["add", "@scope/pkg"] / ["remove", "@scope/pkg"] / ["update", "@scope/pkg"]
   */
  fun runPluginOp(args: List<String>, onLog: (String) -> Unit): Int {
    val rootfsDir = Paths.rootfs(context.filesDir)
    check(Paths.rootfsUsable(rootfsDir) && Paths.dshInstalled(rootfsDir)) { "容器或 dsh 未就绪" }
    check(prootRuntime.runnerAvailable()) { "proot 运行器缺失" }
    ensurePnpm(onLog)
    val workspace = Paths.workspace(context.filesDir).apply { mkdirs() }
    val argv = prootRuntime.buildArgs(
      rootfsDir,
      listOf(workspace.absolutePath + ":/root/projects"),
      listOf(
        "DSH_HOME=/root/.dsh",
        "DSH_PICK_DISABLE_UPDATE_CHECK=1",
        "NODE_OPTIONS=--max-old-space-size=512",
        "NO_COLOR=1",
      ),
      listOf("dsh", "plugin", "--profile", "web") + args,
    )
    onLog("\$ dsh plugin --profile web ${args.joinToString(" ")}")
    val exit = RuntimeInstaller(context).runProot(argv, onLog)
    AppLog.log("plugin", "dsh plugin ${args.joinToString(" ")} 退出码=$exit")
    return exit
  }

  private fun npmRegistry(): String =
    context.getSharedPreferences("dshm", Context.MODE_PRIVATE)
      .getString("npm_registry", Paths.NPM_REGISTRY_CN) ?: Paths.NPM_REGISTRY_CN
}
