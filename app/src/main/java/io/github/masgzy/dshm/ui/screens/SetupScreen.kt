package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.ui.AppViewModel

/**
 * 安装向导：两条路线
 *  1. 官方 rootfs + 在线安装（默认，体积小、始终最新）
 *  2. 预装运行时（CI 构建的 bundle，安装快、离线可用）
 */
@Composable
fun SetupScreen(viewModel: AppViewModel) {
  val state by viewModel.setup.collectAsState()
  val progress = state.progress
  val error = state.error
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(24.dp))
    Text(
      "DeepSeek Harness\nMobile",
      fontSize = 28.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      "免 root · 完整 Linux 用户态 · proot 容器",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("运行环境", style = MaterialTheme.typography.titleMedium)
        Text(
          "Ubuntu ${Paths.UBUNTU_RELEASE} LTS · Node.js ${Paths.NODE_VERSION} · dsh ${Paths.DSH_VERSION}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (!state.done) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("安装方式", style = MaterialTheme.typography.titleMedium)
          Button(
            onClick = { viewModel.installOfficial() },
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
          ) { Text("官方 rootfs + 在线安装（推荐）") }
          Text(
            "下载 Ubuntu 官方基础镜像（约 30MB），随后在容器内安装 Node.js 与 dsh（约 1-3 分钟，需网络）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          HorizontalDivider()
          OutlinedButton(
            onClick = { viewModel.installBundled() },
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
          ) { Text("使用预装运行时（更快）") }
          Text(
            "下载本仓库 CI 预构建的完整运行时 bundle（含 Node + dsh，约 200MB），SHA-256 校验后一键启用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (state.running || state.done || state.error != null) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = if (state.error != null) {
            MaterialTheme.colorScheme.errorContainer
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
        ),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.running) {
              CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
              Spacer(Modifier.width(12.dp))
            }
            Text(
              when {
                state.error != null -> "安装失败"
                state.done -> "安装完成 ✓"
                else -> state.step
              },
              style = MaterialTheme.typography.titleSmall,
            )
          }
          if (state.running && progress != null) {
            LinearProgressIndicator(
              progress = { progress },
              modifier = Modifier.fillMaxWidth(),
            )
          } else if (state.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
          if (error != null) {
            Text(
              error,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          if (state.logLines.isNotEmpty()) {
            Column(
              Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            ) {
              state.logLines.takeLast(12).forEach { line ->
                Text(
                  line,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
          if (error != null && !state.running) {
            Button(onClick = { viewModel.installOfficial() }) { Text("重试") }
          }
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}
