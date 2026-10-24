package io.github.masgzy.dshm.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.Phases
import io.github.masgzy.dshm.ui.components.StatusBadge
import kotlinx.coroutines.delay

/**
 * 主界面：dsh 官方 Web UI（WebView 加载 127.0.0.1:3080）+ 引擎控制。
 *
 * 引擎状态探测全部走 viewModel（Dispatchers.IO）：HttpURLConnection 在主线程
 * 会抛 NetworkOnMainThreadException，v1.0.0-alpha.1 的轮询把它静默吞掉，
 * 导致「引擎被看门狗拉起后界面永不恢复」。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeScreen(viewModel: AppViewModel) {
  val running by viewModel.engineRunning.collectAsState()
  var webView by remember { mutableStateOf<WebView?>(null) }

  LaunchedEffect(Unit) { viewModel.refreshEngineState() }

  if (running) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          StatusBadge(Phases.RUNNING)
          Text(
            "127.0.0.1:${viewModel.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(onClick = { viewModel.stopEngine() }) { Text("停止") }
      }
      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:${viewModel.port}/")
          }.also { webView = it }
        },
        modifier = Modifier.fillMaxSize(),
      )
      BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
    }
  } else {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Card(Modifier.fillMaxWidth()) {
        Column(
          Modifier.padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          StatusBadge(Phases.READY)
          Text("引擎未运行", style = MaterialTheme.typography.titleLarge)
          Text(
            "启动后将在本机 proot 容器内运行 DeepSeek Harness Web UI。" +
              "首次启动约需 10-30 秒。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = { viewModel.startEngine() },
            modifier = Modifier.fillMaxWidth(),
          ) { Text("启动引擎") }
        }
      }
      Spacer(Modifier.size(8.dp))
      Text(
        "提示：启动引擎将开启前台服务保活，任务长时间运行请勿从最近任务划掉应用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    // 周期探测（IO 线程）：引擎在后台被看门狗拉起后，界面自动恢复
    LaunchedEffect(Unit) {
      while (true) {
        delay(3_000)
        viewModel.refreshEngineState()
      }
    }
  }
}
