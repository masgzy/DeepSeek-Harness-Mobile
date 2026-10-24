package io.github.masgzy.dshm

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import io.github.masgzy.dshm.engine.EngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * dsh Web UI 全屏对话页（对齐 dphmoblie/deepseek-harness-android 的 HarnessActivity 模式）：
 * 门屏「打开对话」→ 本 Activity 全屏承载 dsh 官方 Web UI，顶栏/底部导航不占空间，
 * 返回键回外壳。这是「dsh 界面在哪进」的答案：首页 → 打开对话。
 *
 * dsh web 每次启动生成随机 token 且只打印在 stdout（`dsh web: …/?token=…`），
 * WebView 必须加载带 token 的完整 URL，否则 401
 * "dsh web authentication required; reopen the URL printed by dsh web."
 *
 * 自愈逻辑（与外壳共享 EngineManager.webUrl 流）：
 * - 引擎刚打印 URL 但端口尚未监听 → 主帧加载失败：延迟自动重试；
 * - 引擎被看门狗重启换新 token → 401：延迟后从引擎日志尾部重新解析并重载。
 */
class HarnessActivity : ComponentActivity() {

  companion object {
    /** Intent 携带的入口地址 key；缺省时回落到 EngineManager.webUrl（APP 重启后附着场景）。 */
    const val EXTRA_URL = "io.github.masgzy.dshm.extra.WEB_URL"

    /** 主帧加载失败重试上限（端口监听前的短暂窗口，一般 2-3 次内成功）。 */
    private const val MAX_LOAD_RETRIES = 8
  }

  private var webView: WebView? = null
  private var lastLoaded: String? = null
  private var loadRetries = 0

  private val backCallback = object : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      val wv = webView
      if (wv != null && wv.canGoBack()) wv.goBack() else finish()
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    onBackPressedDispatcher.addCallback(this, backCallback)

    val initialUrl = intent.getStringExtra(EXTRA_URL) ?: EngineManager.webUrl.value
    // URL 必须就绪才允许进入本页（门屏已等待 token 解析）；异常缺省时直接回外壳
    if (initialUrl.isNullOrBlank()) {
      finish()
      return
    }

    val wv = WebView(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      // 纯本地服务，禁用不必要的缓存模式由页面自行决定；UA 保持默认避免被 dsh 前端误判
      setBackgroundColor(android.graphics.Color.parseColor("#0B0F13"))
      webViewClient = object : WebViewClient() {
        override fun onReceivedError(
          view: WebView?,
          request: WebResourceRequest?,
          error: WebResourceError?,
        ) {
          if (request?.isForMainFrame != true) return
          // 引擎打印 URL 与端口监听之间存在短暂窗口：延迟重试
          if (loadRetries < MAX_LOAD_RETRIES) {
            loadRetries++
            view?.postDelayed({ retrySameUrl() }, 2_500)
          }
        }

        override fun onReceivedHttpError(
          view: WebView?,
          request: WebResourceRequest?,
          errorResponse: android.webkit.WebResourceResponse?,
        ) {
          if (request?.isForMainFrame != true) return
          if (errorResponse?.statusCode == 401) {
            // token 失效（引擎被看门狗重启换新 token）：延迟后从日志尾部重新解析，
            // 引擎重启完成后新地址会打印在日志更靠后的位置
            view?.postDelayed({ reparseAndReload() }, 5_000)
          }
        }
      }
    }
    webView = wv
    setContentView(wv)
    lastLoaded = initialUrl
    wv.loadUrl(initialUrl)

    // 外壳侧 token 更新（重启换 token / 日志重新解析）→ 本页跟随重载
    lifecycleScope.launch(Dispatchers.Main) {
      EngineManager.webUrl.collect { url ->
        if (url != null && url != lastLoaded) {
          lastLoaded = url
          loadRetries = 0
          wv.loadUrl(url)
        }
      }
    }
  }

  /** 原地址重试（端口尚未监听窗口期）。 */
  private fun retrySameUrl() {
    val wv = webView ?: return
    val url = lastLoaded ?: EngineManager.webUrl.value ?: return
    if (EngineManager.webUrl.value != null && EngineManager.webUrl.value != url) {
      // 已有更新地址，直接用新的
      lastLoaded = EngineManager.webUrl.value
    }
    wv.loadUrl(lastLoaded ?: url)
  }

  /** 401 自愈：从引擎日志尾部重新解析最新带 token 地址。
   *  解析结果先做活性验证（旧 token 401，直接加载只会再撞一次凭据页），
   *  验证通过才重载；未通过则等待 EngineManager.webUrl 流（外壳侧验证后更新）。 */
  private fun reparseAndReload() {
    val logFile = io.github.masgzy.dshm.engine.Paths.engineLog(filesDir)
    val log = EngineManager.webUrlFromLog(logFile) ?: return
    if (log == lastLoaded) return
    Thread {
      val ok = io.github.masgzy.dshm.engine.EngineProbe.checkUrl(log)
      runOnUiThread {
        if (ok && log != lastLoaded) {
          lastLoaded = log
          loadRetries = 0
          webView?.loadUrl(log)
        }
      }
    }.start()
  }

  override fun onDestroy() {
    webView?.apply {
      loadUrl("about:blank")
      (parent as? ViewGroup)?.removeView(this)
      destroy()
    }
    webView = null
    super.onDestroy()
  }
}
