package io.github.masgzy.dshm.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.masgzy.dshm.engine.EngineManager
import io.github.masgzy.dshm.engine.EngineService
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.engine.RootfsManager
import io.github.masgzy.dshm.engine.RuntimeInstaller
import io.github.masgzy.dshm.engine.UpdateManager
import io.github.masgzy.dshm.util.AppLog
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

  private val _setup = MutableStateFlow(SetupState())
  val setup: StateFlow<SetupState> = _setup

  private val _engineRunning = MutableStateFlow(false)
  val engineRunning: StateFlow<Boolean> = _engineRunning

  // ---- 设置 ----
  var port: Int
    get() = prefs.getInt("port", Paths.DSH_PORT).coerceIn(1024, 65535)
    set(value) = prefs.edit().putInt("port", value).apply()

  var npmRegistry: String
    get() = prefs.getString("npm_registry", Paths.NPM_REGISTRY_CN) ?: Paths.NPM_REGISTRY_CN
    set(value) = prefs.edit().putString("npm_registry", value).apply()

  // ---- 引擎控制 ----
  fun startEngine() {
    viewModelScope.launch(Dispatchers.IO) {
      val ok = engineManager.startEngine(port)
      if (ok) EngineService.start(getApplication())
      var waited = 0
      // 轮询等待 Web UI 就绪（最多 30s）
      while (ok && waited < 30_000 && !engineManager.isRunning) {
        Thread.sleep(1_000)
        waited += 1_000
      }
      withContext(Dispatchers.Main) { _engineRunning.value = engineManager.isRunning }
      AppLog.log("vm", "startEngine ok=$ok ready=${engineManager.isRunning}")
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

  // ---- 安装向导：官方 rootfs + 在线 bootstrap ----
  fun installOfficial() {
    if (_setup.value.running) return
    viewModelScope.launch(Dispatchers.IO) {
      _setup.value = SetupState(running = true, step = "准备安装")
      try {
        rootfsManager.install { step, done, total ->
          updateProgress(step, done, total)
        }
        runtimeInstaller.install(
          npmRegistry = npmRegistry,
          onProgress = { step, done, total -> updateProgress(step, done, total) },
          onLog = { line -> appendLog(line) },
        )
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
