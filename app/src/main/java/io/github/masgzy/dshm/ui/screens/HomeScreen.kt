package io.github.masgzy.dshm.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.InlineAlert
import io.github.masgzy.dshm.ui.components.Phases
import io.github.masgzy.dshm.ui.components.PrimaryButton
import io.github.masgzy.dshm.ui.components.ScreenHeading
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import kotlinx.coroutines.delay

/**
 * 首页：对话门屏（对齐 dphmoblie/deepseek-harness-android 的 conversation-gate）。
 *
 * dsh web 每次启动生成随机 token 且只打印在 stdout（`dsh web: …/?token=…`），
 * WebView 必须加载带 token 的完整 URL；未带 token 一律 401
 * "dsh web authentication required; reopen the URL printed by dsh web."。
 * 因此：门屏「打开对话」→ 等待 webUrl 解析 → WebView 加载带 token 地址；
 * 401 / 加载失败时自动重试（引擎重启会换新 token，webUrl 流驱动重载）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeScreen(viewModel: AppViewModel) {
  val running by viewModel.engineRunning.collectAsState()
  val webUrl by viewModel.webUrl.collectAsState()
  val stopping = remember { mutableStateOf(false) }
  val launching = remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { viewModel.refreshEngineState() }

  if (running) {
    // 运行中：dsh Web UI 全屏 WebView；token 未就绪前显示凭据获取中
    val url = webUrl
    if (url != null) {
      HarnessWebView(url, viewModel, Modifier.fillMaxSize())
    } else {
      Column(
        Modifier
          .fillMaxSize()
          .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator(color = LocalDshmPalette.current.blue)
        Spacer(Modifier.height(16.dp))
        Text("正在获取访问凭据…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
          "等待 dsh web 打印本次会话的带 token 地址",
          fontSize = 12.sp,
          color = LocalDshmPalette.current.muted,
        )
        LaunchedEffect(Unit) {
          // 端口已在应答但 URL 一直没解析到：尝试从引擎日志尾部补一次
          while (viewModel.webUrl.value == null) {
            viewModel.resolveWebUrlFromLog()
            if (viewModel.webUrl.value != null) break
            delay(2_000)
          }
        }
      }
    }
  } else {
    GateScreen(viewModel, launching.value, stopping.value,
      onLaunch = {
        launching.value = true
        viewModel.startEngine {
          launching.value = false
        }
      },
      onStop = {
        stopping.value = true
        viewModel.stopEngine()
        stopping.value = false
      },
    )
  }
}

/**
 * 门屏：eyebrow + 动态标题 + launch-panel（图标块 + 状态句 + 说明 + 按钮组）。
 * 文案逐项对应参考项目 conversation-gate 的三态（就绪 / 正在进入 / 正在停止）。
 */
@Composable
private fun GateScreen(
  viewModel: AppViewModel,
  launching: Boolean,
  stopping: Boolean,
  onLaunch: () -> Unit,
  onStop: () -> Unit,
) {
  val p = LocalDshmPalette.current
  val title = when {
    launching -> "正在进入对话"
    stopping -> "正在停止运行环境"
    else -> "对话已就绪"
  }
  val headline = when {
    launching -> "正在打开 Harness 对话"
    stopping -> "正在停止本机服务"
    else -> "点击「打开对话」开始"
  }
  val detail = when {
    launching -> "正在启动本机 proot 容器与 dsh web，首次启动约需 10-30 秒…"
    stopping -> "停止完成后即可重新打开对话。"
    else -> "打开对话时才会启动本机服务；容器与运行环境已就绪。"
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    ScreenHeading("Harness 对话", title)

    // launch-panel：主启动卡片
    Column(
      Modifier
        .fillMaxWidth()
        .background(
          androidx.compose.ui.graphics.Brush.linearGradient(
            0f to p.blueSoft.copy(alpha = 0.65f),
            1f to MaterialTheme.colorScheme.surface,
          ),
          RoundedCornerShape(DshmRadius.card.dp),
        )
        .border(1.dp, p.line, RoundedCornerShape(DshmRadius.card.dp))
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
      ) {
        // launch-icon：52dp 图标块
        Box(
          Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(DshmRadius.inner.dp))
            .background(p.ink.copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.06f)),
          contentAlignment = Alignment.Center,
        ) {
          if (launching) {
            CircularProgressIndicator(
              modifier = Modifier.size(26.dp),
              strokeWidth = 2.5.dp,
              color = LocalDshmPalette.current.blue,
            )
          } else {
            Icon(
              Icons.Rounded.ChatBubbleOutline,
              null,
              tint = LocalDshmPalette.current.blue,
              modifier = Modifier.size(28.dp),
            )
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            headline,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            detail,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // launch-actions：主按钮（未运行时不摆按不动的停止键，对齐参考项目）
      PrimaryButton(
        if (launching) "正在打开…" else "打开对话",
        onLaunch,
        Modifier.fillMaxWidth(),
        enabled = !launching && !stopping,
        icon = if (launching) null else Icons.Filled.PlayArrow,
      )
    }

    // 保活提示（inline-alert warning）
    InlineAlert(
      title = "请勿从最近任务划掉应用",
      message = "启动对话后将开启前台服务保活；长时间任务期间请保持应用在后台运行，" +
        "进程被系统回收时看门狗会自动重启引擎。",
    )

    Spacer(Modifier.height(8.dp))
  }

  // 周期探测（IO 线程）：引擎在后台被看门狗拉起后，门屏自动进入对话
  LaunchedEffect(Unit) {
    while (true) {
      delay(3_000)
      viewModel.refreshEngineState()
    }
  }
}

/**
 * dsh Web UI WebView：加载带 token 的完整地址。
 * - token 失效（引擎被看门狗重启换新 token）→ 401：从引擎日志重新解析并重载；
 * - 引擎刚打印 URL 但端口尚未监听 → 连接失败：延迟自动重试。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HarnessWebView(initialUrl: String, viewModel: AppViewModel, modifier: Modifier = Modifier) {
  val webUrl by viewModel.webUrl.collectAsState()
  var webView by remember { mutableStateOf<WebView?>(null) }
  // 已加载到 WebView 里的地址：防止 AndroidView.update 在每次重组时重复 loadUrl
  var lastLoaded by remember { mutableStateOf<String?>(null) }

  AndroidView(
    factory = { ctx ->
      WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
          private var retries = 0

          override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
          ) {
            if (request?.isForMainFrame != true) return
            // 引擎打印 URL 与端口监听之间有短暂窗口，延迟重试
            if (retries < 6) {
              retries++
              view?.postDelayed({ view.loadUrl(view.url ?: initialUrl) }, 2_500)
            }
          }

          override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?,
          ) {
            if (request?.isForMainFrame != true) return
            if (errorResponse?.statusCode == 401) {
              // token 已失效（引擎重启换新 token）：延迟后从日志尾部重新解析，
              // 引擎重启完成后新地址会打印在日志更靠后的位置
              view?.postDelayed({ viewModel.resolveWebUrlFromLog() }, 5_000)
            }
          }
        }
        lastLoaded = initialUrl
        loadUrl(initialUrl)
      }.also { webView = it }
    },
    update = { view ->
      // webUrl 变化（重启换 token / 从日志重新解析）→ 重载一次
      val target = webUrl
      if (target != null && lastLoaded != target) {
        lastLoaded = target
        view.loadUrl(target)
      }
    },
    modifier = modifier,
  )

  BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
}
