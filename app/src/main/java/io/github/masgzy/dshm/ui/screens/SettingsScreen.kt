package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.masgzy.dshm.BuildConfig
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.util.AppLog

/**
 * 设置页：端口、npm 源、容器管理、关于与许可证。
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
  var portText by remember { mutableStateOf(viewModel.port.toString()) }
  var confirmUninstall by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("设置", style = MaterialTheme.typography.headlineSmall)

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Web UI 端口", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
              keyboardType = KeyboardType.Number,
            ),
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Button(onClick = {
            portText.toIntOrNull()?.let { viewModel.port = it }
          }) { Text("保存") }
        }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("npm 源（安装/更新 dsh 时使用）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = viewModel.npmRegistry == Paths.NPM_REGISTRY_CN,
            onClick = { viewModel.npmRegistry = Paths.NPM_REGISTRY_CN },
            label = { Text("国内镜像") },
          )
          FilterChip(
            selected = viewModel.npmRegistry == Paths.NPM_REGISTRY_OFFICIAL,
            onClick = { viewModel.npmRegistry = Paths.NPM_REGISTRY_OFFICIAL },
            label = { Text("官方源") },
          )
        }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("容器管理", style = MaterialTheme.typography.titleSmall)
        Text(
          "卸载将删除容器 rootfs 与全部缓存（工作区文件保留）。下次使用需重新安装。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { confirmUninstall = true }) { Text("卸载容器") }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("关于", style = MaterialTheme.typography.titleSmall)
        Text("DeepSeek Harness Mobile ${BuildConfig.VERSION_NAME}")
        Text(
          "Ubuntu ${Paths.UBUNTU_RELEASE} · Node.js ${Paths.NODE_VERSION} · dsh ${Paths.DSH_VERSION}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "本项目基于 GPL-3.0 开源；第三方组件声明见仓库 THIRD_PARTY_NOTICES.md。" +
            "DeepSeek Harness 为 DeepSeek 官方开源项目（developer preview）。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (AppLog.crashLogFile.isFile) {
          Text(
            "检测到崩溃记录（crash.log），反馈问题时请附上「日志 → 应用」页内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }

  if (confirmUninstall) {
    AlertDialog(
      onDismissRequest = { confirmUninstall = false },
      title = { Text("卸载容器？") },
      text = { Text("将删除 Ubuntu 容器、Node.js 与 dsh 全部数据（/root/projects 工作区文件保留）。") },
      confirmButton = {
        TextButton(onClick = {
          confirmUninstall = false
          Thread {
            viewModel.rootfsManager.uninstall()
          }.start()
        }) { Text("卸载") }
      },
      dismissButton = {
        TextButton(onClick = { confirmUninstall = false }) { Text("取消") }
      },
    )
  }
}
