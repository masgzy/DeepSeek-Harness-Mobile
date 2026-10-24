package io.github.masgzy.dshm.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.masgzy.dshm.engine.Distro
import io.github.masgzy.dshm.engine.DistroCatalog
import io.github.masgzy.dshm.engine.DistroFamily
import io.github.masgzy.dshm.engine.DshCatalog
import io.github.masgzy.dshm.engine.EngineManager
import io.github.masgzy.dshm.engine.EngineService
import io.github.masgzy.dshm.engine.ModelConfigManager
import io.github.masgzy.dshm.engine.NodeCatalog
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.engine.PluginManager
import io.github.masgzy.dshm.engine.RootfsManager
import io.github.masgzy.dshm.engine.RuntimeInstaller
import io.github.masgzy.dshm.engine.UpdateManager
import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.MirrorProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全局安装进度状态。 */
data class SetupState(
  val running: Boolean = false,
  val step: String = "",
  val progress: Float? = null, // null = 不确定进度
  val logLines: List<String> = emptyList(),
  val error: String? = null,
  val done: Boolean = false,
)

/** 镜像源测速状态（apt / apk 两组结果，随测速逐个填充）。 */
data class MirrorProbeState(
  val running: Boolean = false,
  val apt: List<MirrorProbe.Result> = emptyList(),
  val apk: List<MirrorProbe.Result> = emptyList(),
  val done: Boolean = false,
  /** 已把择优结果写入已安装容器（rootfs 就绪时自动执行）。 */
  val applied: Boolean = false,
  val error: String? = null,
)

/**
 * 应用级状态中枢：安装向导 + 引擎控制 + 设置读写。
 * 全部 I/O 在 Dispatchers.IO；UI 状态经 StateFlow 发布。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

  private val prefs: SharedPreferences =
    application.getSharedPreferences("dshm", Context.MODE_PRIVATE)

  val rootfsManager = RootfsManager(application.filesDir)
  val engineManager = EngineManager(application)
  private val runtimeInstaller = RuntimeInstaller(application)
  private val updateManager = UpdateManager(application.filesDir)

  /** 应用内模型配置（凭据/自定义供应商/默认模型）与容器同步。 */
  val modelConfig = ModelConfigManager(application)

  /** 插件管理（dsh plugin / pnpm）。 */
  val pluginManager = PluginManager(application)

  private val _setup = MutableStateFlow(SetupState())
  val setup: StateFlow<SetupState> = _setup

  private val _engineRunning = MutableStateFlow(false)
  val engineRunning: StateFlow<Boolean> = _engineRunning

  /** dsh web 带 token 的访问地址（EngineManager 从引擎 stdout 实时解析）。 */
  val webUrl: StateFlow<String?> get() = EngineManager.webUrl

  // ---- 设置 ----
  var port: Int
    get() = prefs.getInt("port", Paths.DSH_PORT).coerceIn(1024, 65535)
    set(value) = prefs.edit().putInt("port", value).apply()

  var npmRegistry: String
    get() = prefs.getString("npm_registry", Paths.NPM_REGISTRY_CN) ?: Paths.NPM_REGISTRY_CN
    set(value) = prefs.edit().putString("npm_registry", value).apply()

  // ---- 镜像源测速择优（apt / apk） ----
  /** apt 镜像站基址（scheme://host；空 = 未测速，安装时按 npm 源推断）。 */
  var aptMirror: String
    get() = prefs.getString("apt_mirror", "") ?: ""
    set(value) = prefs.edit().putString("apt_mirror", value).apply()

  /** apk 镜像基址（scheme://host/alpine；空 = 未测速，用默认 TUNA）。 */
  var apkMirror: String
    get() = prefs.getString("apk_mirror", "") ?: ""
    set(value) = prefs.edit().putString("apk_mirror", value).apply()

  private val _mirrorProbe = MutableStateFlow(MirrorProbeState())
  val mirrorProbe: StateFlow<MirrorProbeState> = _mirrorProbe

  /** 并行测速 apt/apk 镜像源，自动择优持久化；容器已安装时即时写入。 */
  fun probeMirrors() {
    if (_mirrorProbe.value.running) return
    val distro = this.distro
    viewModelScope.launch(Dispatchers.IO) {
      _mirrorProbe.value = MirrorProbeState(running = true)
      try {
        var aptResults: List<MirrorProbe.Result> = emptyList()
        var apkResults: List<MirrorProbe.Result> = emptyList()
        // apt 候选按所选发行版套件探测（自定义镜像无已知套件，跳过）
        val suite = when (distro.id) {
          "ubuntu26" -> "resolute"
          "ubuntu24" -> "noble"
          "debian13" -> "trixie"
          "debian12" -> "bookworm"
          else -> ""
        }
        if (suite.isNotEmpty()) {
          val mirrors =
            if (distro.id.startsWith("ubuntu")) MirrorProbe.ubuntuMirrors(suite)
            else MirrorProbe.debianMirrors(suite)
          aptResults = MirrorProbe.probe(mirrors) { r ->
            val cur = _mirrorProbe.value
            _mirrorProbe.value = cur.copy(apt = mergeProbe(cur.apt, r))
          }
        }
        if (distro.family == DistroFamily.ALPINE) {
          val branch = distro.cdimage?.release ?: "3.22"
          apkResults = MirrorProbe.probe(MirrorProbe.alpineMirrors(branch)) { r ->
            val cur = _mirrorProbe.value
            _mirrorProbe.value = cur.copy(apk = mergeProbe(cur.apk, r))
          }
        }
        // 择优持久化（首个可用 = 延迟最低）
        aptResults.firstOrNull { it.latencyMs != null }?.let { aptMirror = it.mirror.applyBase }
        apkResults.firstOrNull { it.latencyMs != null }?.let { apkMirror = it.mirror.applyBase }
        AppLog.log("mirror", "镜像测速完成 apt=${aptMirror.ifBlank { "-" }} apk=${apkMirror.ifBlank { "-" }}")
        _mirrorProbe.value = MirrorProbeState(
          apt = aptResults.sortedWith(compareBy { it.latencyMs ?: Long.MAX_VALUE }),
          apk = apkResults.sortedWith(compareBy { it.latencyMs ?: Long.MAX_VALUE }),
          done = true,
        )
        // 容器已装：即时把择优源写入（免重装）
        if (rootfsManager.isRootfsReady && (aptMirror.isNotBlank() || apkMirror.isNotBlank())) {
          appendLog("[mirror] 择优完成：apt=${aptMirror.ifBlank { "保持不变" }} apk=${apkMirror.ifBlank { "保持不变" }}，写入容器…")
          runtimeInstaller.applyMirrors(aptMirror, apkMirror) { appendLog(it) }
          _mirrorProbe.value = _mirrorProbe.value.copy(applied = true)
        }
      } catch (t: Throwable) {
        AppLog.log("mirror", "镜像测速失败", t)
        _mirrorProbe.value = _mirrorProbe.value.copy(running = false, error = describe(t))
      }
    }
  }

  /** 测速结果按镜像去重合入（同一镜像取更快的一次）。 */
  private fun mergeProbe(
    existing: List<MirrorProbe.Result>,
    r: MirrorProbe.Result,
  ): List<MirrorProbe.Result> {
    val others = existing.filter { it.mirror.applyBase != r.mirror.applyBase }
    return (others + r).sortedWith(compareBy { it.latencyMs ?: Long.MAX_VALUE })
  }

  // ---- 容器系统与运行时版本选择 ----
  /** 容器系统 ID（DistroCatalog）或 DistroCatalog.CUSTOM_ID。 */
  var distroId: String
    get() = prefs.getString("distro_id", DistroCatalog.DEFAULT.id) ?: DistroCatalog.DEFAULT.id
    set(value) = prefs.edit().putString("distro_id", value).apply()

  /** 自定义镜像引用（distroId == custom 时生效）。 */
  var customImageRef: String
    get() = prefs.getString("distro_custom_ref", "") ?: ""
    set(value) = prefs.edit().putString("distro_custom_ref", value.trim()).apply()

  /** Node.js 版本（"v24.22.0"；Alpine 族忽略）。 */
  var nodeVersion: String
    get() = prefs.getString("node_version", Paths.NODE_VERSION) ?: Paths.NODE_VERSION
    set(value) = prefs.edit().putString("node_version", value).apply()

  /** dsh 版本；空串 = npm latest。 */
  var dshVersion: String
    get() = prefs.getString("dsh_version", Paths.DSH_VERSION) ?: Paths.DSH_VERSION
    set(value) = prefs.edit().putString("dsh_version", value).apply()

  /** 当前生效的容器系统（含自定义引用解析）。 */
  val distro: Distro get() = DistroCatalog.resolve(distroId, customImageRef)

  /** Node.js 拉取失败/离线时的本地候选（常用 LTS）。 */
  val nodeFallbackVersions: List<String> = listOf(
    "v24.22.0", "v24.22.1", "v22.21.1", "v22.14.0", "v20.19.0",
  )

  /** dsh 拉取失败/离线时的本地候选。 */
  val dshFallbackVersions: List<String> = listOf(
    Paths.DSH_VERSION, "0.2.1-rc.3", "0.2.1-rc.1", "0.2.1-alpha.5",
  )

  /** 远端 Node.js 版本目录（IO 线程；失败抛出，调用方自行降级）。 */
  suspend fun fetchNodeVersions(): List<NodeCatalog.Entry> =
    withContext(Dispatchers.IO) { NodeCatalog.list() }

  /** 远端 dsh 版本目录（registry 跟随设置页 npm 源）。 */
  suspend fun fetchDshVersions(): List<DshCatalog.Entry> =
    withContext(Dispatchers.IO) { DshCatalog.list(npmRegistry) }

  // ---- 引擎控制 ----
  fun startEngine(onDone: ((ok: Boolean) -> Unit)? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      val ok = engineManager.startEngine(port)
      if (ok) EngineService.start(getApplication())
      var waited = 0
      // 轮询等待 Web UI 就绪（最多 60s）：以「带 token 的 webUrl 解析完成」为就绪标准，
      // 只探测端口会把 401 拒答误判为可用，WebView 随即撞上认证错误页。
      // 等待期间同步从日志尾部补解析：覆盖 APP 重启后附着到仍在运行引擎的场景
      // （此时 stdout 管道读者已不在，webUrl 流不会自发更新，不能干等 60s）。
      while (ok && waited < 60_000 && EngineManager.webUrl.value == null) {
        Thread.sleep(1_000)
        waited += 1_000
        if (waited % 3_000 == 0) resolveWebUrlFromLog()
      }
      // 端口就绪但 URL 始终没打出来（异常场景）：退回端口探测结果，别让按钮悬空。
      if (ok && EngineManager.webUrl.value == null && engineManager.isRunning) {
        AppLog.log("vm", "端口已应答但未捕获 token URL，尝试从日志尾部解析")
        resolveWebUrlFromLog()
      }
      withContext(Dispatchers.Main) { _engineRunning.value = engineManager.isRunning }
      AppLog.log("vm", "startEngine ok=$ok ready=${engineManager.isRunning} url=${EngineManager.webUrl.value != null}")
      onDone?.let { withContext(Dispatchers.Main) { it(ok) } }
    }
  }

  /**
   * 从引擎日志尾部解析带 token 的 webUrl（engine.log 为追加式，尾部即最新会话）。
   * 引擎重启换新 token 后，新地址打印在日志更靠后的位置，解析结果自然更新。
   */
  fun resolveWebUrlFromLog() {
    EngineManager.webUrlFromLog(engineManager.engineLog)?.let { EngineManager.webUrl.value = it }
  }

  /** 重置运行环境：停服务与引擎、删除 rootfs 与缓存、复位安装状态。 */
  fun uninstallRuntime() {
    viewModelScope.launch(Dispatchers.IO) {
      EngineService.stop(getApplication())
      engineManager.stopEngine()
      io.github.masgzy.dshm.terminal.TerminalHolder.close()
      rootfsManager.uninstall()
      EngineManager.webUrl.value = null
      _setup.value = SetupState()
      withContext(Dispatchers.Main) { _engineRunning.value = false }
      AppLog.log("vm", "运行环境已重置")
    }
  }

  fun stopEngine() {
    viewModelScope.launch(Dispatchers.IO) {
      EngineService.stop(getApplication())
      engineManager.stopEngine()
      withContext(Dispatchers.Main) { _engineRunning.value = false }
    }
  }

  fun refreshEngineState() {
    // 探测含网络 I/O，必须离开主线程
    viewModelScope.launch(Dispatchers.IO) {
      val running = engineManager.isRunning
      withContext(Dispatchers.Main) { _engineRunning.value = running }
    }
  }

  /** 把异常链展开成可读的错误文案（UI 直接展示，不再只给最后一层 message）。 */
  private fun describe(t: Throwable): String = buildString {
    append(t.message ?: t.javaClass.simpleName)
    var c = t.cause
    var depth = 0
    while (c != null && depth < 3) {
      append("；原因: ").append(c.message ?: c.javaClass.simpleName)
      c = c.cause
      depth++
    }
  }

  // ---- 安装向导：官方 rootfs + 在线 bootstrap（发行版/版本可选） ----
  fun installOfficial() {
    if (_setup.value.running) return
    val distro = this.distro
    val nodeVersion = this.nodeVersion
    val dshVersion = this.dshVersion
    viewModelScope.launch(Dispatchers.IO) {
      _setup.value = SetupState(running = true, step = "准备安装")
      try {
        rootfsManager.install(
          distro,
          onProgress = { step, done, total -> updateProgress(step, done, total) },
          onLog = { line -> appendLog(line) },
        )
        runtimeInstaller.install(
          distro = distro,
          npmRegistry = npmRegistry,
          nodeVersion = nodeVersion,
          dshVersion = dshVersion,
          aptMirror = aptMirror,
          alpineMirror = apkMirror,
          onProgress = { step, done, total -> updateProgress(step, done, total) },
          onLog = { line -> appendLog(line) },
        )
        // 首装完成即同步模型配置（用户可能在安装前就填好了 Key）
        modelConfig.sync()
        _setup.value = _setup.value.copy(running = false, done = true, step = "安装完成")
      } catch (t: Throwable) {
        AppLog.log("setup", "安装失败", t)
        _setup.value = _setup.value.copy(running = false, error = describe(t))
      }
    }
  }

  // ---- 安装向导：预装运行时（Release bundle） ----
  fun installBundled() {
    if (_setup.value.running) return
    viewModelScope.launch(Dispatchers.IO) {
      _setup.value = SetupState(running = true, step = "获取运行时清单")
      try {
        val manifest = updateManager.fetchManifest()
        appendLog("清单: v${manifest.version} node=${manifest.node} dsh=${manifest.dsh}")
        val ok = updateManager.applyRuntimeBundle(manifest) { step, done, total ->
          updateProgress(step, done, total)
        }
        if (!ok) throw RuntimeException("预装运行时启用失败，详见日志")
        // 预装路径同样补同步模型配置
        modelConfig.sync()
        _setup.value = _setup.value.copy(running = false, done = true, step = "安装完成")
      } catch (t: Throwable) {
        AppLog.log("setup", "预装运行时安装失败", t)
        _setup.value = _setup.value.copy(running = false, error = describe(t))
      }
    }
  }

  private fun updateProgress(step: String, done: Long, total: Long) {
    val frac = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
    _setup.value = _setup.value.copy(step = step, progress = frac)
  }

  private fun appendLog(line: String) {
    val lines = (_setup.value.logLines + line).takeLast(200)
    _setup.value = _setup.value.copy(logLines = lines)
  }
}
