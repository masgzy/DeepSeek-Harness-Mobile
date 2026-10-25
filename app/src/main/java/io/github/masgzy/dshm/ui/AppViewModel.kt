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
import io.github.masgzy.dshm.engine.EngineProbe
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
import java.util.concurrent.atomic.AtomicBoolean

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

  /**
   * 「已进入正式界面」判定（rootfs 就绪 && (dsh 就绪 || 安装向导完成)）。
   * 显式状态化：AppRoot 直接以此切换环境屏/主界面，不再依赖重组时机的
   * 文件读数 + StateFlow 时序组合——安装完成时 [installOfficial] 主动发布，
   * 轮询路径 [refreshEngineState] 兼职兑底，任何一路到达都能完成流转。
   */
  private val _installedUi = MutableStateFlow(false)
  val installedUi: StateFlow<Boolean> = _installedUi

  /** 重算并发布安装态（文件态 + setup 状态合成；值不变时 StateFlow 自动去重）。 */
  fun refreshInstalled() {
    _installedUi.value =
      rootfsManager.isRootfsReady && (engineManager.engineReady || _setup.value.done)
  }

  init {
    refreshInstalled()
  }

  /**
   * _setup 单写锁：安装期间 onLog/onProgress 可能从 proot 输出读取线程与安装
   * 协程两条线程并发到达，读-改-写不串行会互相覆盖（末尾的 done=true 可能被
   * 迟到的日志行写入覆盖回 done=false，正式界面不再流转）。所有 _setup 写入
   * 一律经由本锁下的 [publishSetup]。
   */
  private val setupLock = Any()

  private fun publishSetup(transform: (SetupState) -> SetupState) {
    synchronized(setupLock) { _setup.value = transform(_setup.value) }
  }

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

  /** pnpm 版本线（"12" 默认 / "11"；pnpm 12 为 Rust 重写，安装/运行均更快）。 */
  var pnpmMajor: String
    get() = prefs.getString("pnpm_major", "12") ?: "12"
    set(value) = prefs.edit().putString("pnpm_major", value).apply()

  /** pnpm 安装方式：npm 直装（默认）/ get-pnpm（官方 npx 自举）/ script（大陆适配一键脚本）。 */
  var pnpmMethod: String
    get() = prefs.getString("pnpm_method", "npm") ?: "npm"
    set(value) = prefs.edit().putString("pnpm_method", value).apply()

  private val _mirrorProbe = MutableStateFlow(MirrorProbeState())
  val mirrorProbe: StateFlow<MirrorProbeState> = _mirrorProbe

  /** 并行测速 apt/apk 镜像源，自动择优持久化；容器已安装时即时写入。 */
  fun probeMirrors() {
    if (_mirrorProbe.value.running) return
    viewModelScope.launch(Dispatchers.IO) {
      _mirrorProbe.value = MirrorProbeState(running = true)
      try {
        val (aptResults, apkResults) = probeMirrorsCore()
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

  /**
   * 镜像测速核心（阻塞，IO 线程）：按当前发行版探测 apt/apk 候选并择优持久化。
   * 已有择优结果的维度跳过（skipMissingOnly 语义：只补缺的那一侧）。
   * 手动测速（设置页）与首装自动测速共用。
   */
  private suspend fun probeMirrorsCore(): Pair<List<MirrorProbe.Result>, List<MirrorProbe.Result>> =
    withContext(Dispatchers.IO) {
      val distro = this@AppViewModel.distro
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
      if (suite.isNotEmpty() && aptMirror.isBlank()) {
        val mirrors =
          if (distro.id.startsWith("ubuntu")) MirrorProbe.ubuntuMirrors(suite)
          else MirrorProbe.debianMirrors(suite)
        aptResults = MirrorProbe.probe(mirrors) { r ->
          val cur = _mirrorProbe.value
          _mirrorProbe.value = cur.copy(apt = mergeProbe(cur.apt, r))
        }
        aptResults.firstOrNull { it.latencyMs != null }?.let { aptMirror = it.mirror.applyBase }
      }
      if (distro.family == DistroFamily.ALPINE && apkMirror.isBlank()) {
        val branch = distro.cdimage?.release ?: "3.22"
        apkResults = MirrorProbe.probe(MirrorProbe.alpineMirrors(branch)) { r ->
          val cur = _mirrorProbe.value
          _mirrorProbe.value = cur.copy(apk = mergeProbe(cur.apk, r))
        }
        apkResults.firstOrNull { it.latencyMs != null }?.let { apkMirror = it.mirror.applyBase }
      }
      AppLog.log("mirror", "镜像测速完成 apt=${aptMirror.ifBlank { "-" }} apk=${apkMirror.ifBlank { "-" }}")
      aptResults to apkResults
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
   *
   * 解析结果必须经 [EngineProbe.checkUrl] 活性验证后才发布：APP 重启后附着到
   * 存活引擎时，日志尾部可能是上一轮会话的过期 token（401 refused 的根源），
   * 直接采用会让 WebView 撞凭据错误页。验证含本机网络 I/O，全程 IO 线程。
   */
  private val urlVerifyInFlight = AtomicBoolean(false)

  fun resolveWebUrlFromLog() {
    // 去重：轮询/启动等待/手动点击可能并发触发，一次只跑一个验证
    if (!urlVerifyInFlight.compareAndSet(false, true)) return
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val candidate = EngineManager.webUrlFromLog(engineManager.engineLog)
        if (candidate == null || candidate == EngineManager.webUrl.value) return@launch
        if (EngineProbe.checkUrl(candidate)) {
          EngineManager.webUrl.value = candidate
          AppLog.log("vm", "日志尾部 URL 验证通过，凭据就绪")
        } else {
          AppLog.log("vm", "日志尾部 URL 凭据已失效（401/不可达），等待引擎打印新地址")
        }
      } finally {
        urlVerifyInFlight.set(false)
      }
    }
  }

  /** 重置运行环境：停服务与引擎、删除 rootfs 与缓存、复位安装状态。 */
  fun uninstallRuntime() {
    viewModelScope.launch(Dispatchers.IO) {
      EngineService.stop(getApplication())
      engineManager.stopEngine()
      io.github.masgzy.dshm.terminal.TerminalHolder.close()
      rootfsManager.uninstall()
      EngineManager.webUrl.value = null
      publishSetup { SetupState() }
      refreshInstalled()
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
    // 探测含网络 I/O，必须离开主线程；发现引擎存活但凭据未就绪时顺带预热解析；
    // 顺带重算安装态（兑底：确保安装完成后正式界面流转必定到达）
    viewModelScope.launch(Dispatchers.IO) {
      val running = engineManager.isRunning
      if (running && EngineManager.webUrl.value == null) resolveWebUrlFromLog()
      refreshInstalled()
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
    val pnpmMajor = this.pnpmMajor
    val pnpmMethod = this.pnpmMethod
    viewModelScope.launch(Dispatchers.IO) {
      publishSetup { SetupState(running = true, step = "准备安装") }
      try {
        // 首装前镜像测速（反馈：测速应该在安装时做）——apt/apk 未测过时自动
        // 执行并择优持久化，择优结果直接作为安装参数（写入 sources/apk 源）
        if (aptMirror.isBlank() || apkMirror.isBlank()) {
          updateProgress("镜像源测速", 0, 1)
          appendLog("[mirror] 首次安装自动测速镜像源（并行首字节，数秒）...")
          probeMirrorsCore()
        }
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
          pnpmMajor = pnpmMajor,
          pnpmMethod = pnpmMethod,
          onProgress = { step, done, total -> updateProgress(step, done, total) },
          onLog = { line -> appendLog(line) },
        )
        // 首装完成即同步模型配置（用户可能在安装前就填好了 Key）
        modelConfig.sync()
        publishSetup { it.copy(running = false, done = true, step = "安装完成") }
        // 装完即发布「已安装」状态 → AppRoot 流转到正式界面；随后后台预热引擎
        refreshInstalled()
        appendLog("[setup] 安装完成，自动启动引擎预热（后台）...")
        val ok = engineManager.startEngine(port)
        if (ok) EngineService.start(getApplication())
        withContext(Dispatchers.Main) { _engineRunning.value = engineManager.isRunning }
        refreshInstalled()
      } catch (t: Throwable) {
        AppLog.log("setup", "安装失败", t)
        publishSetup { it.copy(running = false, error = describe(t)) }
        refreshInstalled()
      }
    }
  }

  // ---- 安装向导：预装运行时（Release bundle） ----
  fun installBundled() {
    if (_setup.value.running) return
    viewModelScope.launch(Dispatchers.IO) {
      publishSetup { SetupState(running = true, step = "获取运行时清单") }
      try {
        val manifest = updateManager.fetchManifest()
        appendLog("清单: v${manifest.version} node=${manifest.node} dsh=${manifest.dsh}")
        val ok = updateManager.applyRuntimeBundle(manifest) { step, done, total ->
          updateProgress(step, done, total)
        }
        if (!ok) throw RuntimeException("预装运行时启用失败，详见日志")
        // 预装路径同样补同步模型配置
        modelConfig.sync()
        publishSetup { it.copy(running = false, done = true, step = "安装完成") }
        refreshInstalled()
      } catch (t: Throwable) {
        AppLog.log("setup", "预装运行时安装失败", t)
        publishSetup { it.copy(running = false, error = describe(t)) }
        refreshInstalled()
      }
    }
  }

  private fun updateProgress(step: String, done: Long, total: Long) {
    val frac = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
    publishSetup { it.copy(step = step, progress = frac) }
  }

  private fun appendLog(line: String) {
    // 上限 500 条（UI 固定高度可滚动展示，避免长安装日志被过早裁剪）
    publishSetup { it.copy(logLines = (it.logLines + line).takeLast(500)) }
  }
}
