package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.engine.PluginManager
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.DshmDialog
import io.github.masgzy.dshm.ui.components.DshmInputField
import io.github.masgzy.dshm.ui.components.InlineAlert
import io.github.masgzy.dshm.ui.components.PrimaryButton
import io.github.masgzy.dshm.ui.components.SectionCard
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件管理页：基于容器内 `dsh plugin --profile web`（pnpm）管理 dsh 树外插件。
 *
 * 列表读取 profile 的 package.json（宿主侧直读，无需引擎）；安装/移除/更新在
 * proot 内执行 pnpm（要求引擎停止）。操作输出流式展示，便于失败排查。
 */
@Composable
fun PluginsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
  val p = LocalDshmPalette.current
  val scope = rememberCoroutineScope()
  var catalog by remember { mutableStateOf(viewModel.pluginManager.catalog()) }
  var opLog by remember { mutableStateOf(listOf<String>()) }
  var busy by remember { mutableStateOf(false) }
  var notice by remember { mutableStateOf<String?>(null) }
  var noticeDanger by remember { mutableStateOf(false) }
  var installDraft by remember { mutableStateOf("") }
  var confirmRemove by remember { mutableStateOf<String?>(null) }

  fun refresh() {
    catalog = viewModel.pluginManager.catalog()
  }

  /** 执行一次插件操作：停引擎闸门 → pnpm → 刷新列表。 */
  fun runOp(title: String, args: List<String>, okMsg: String) {
    if (busy) return
    scope.launch(Dispatchers.IO) {
      busy = true
      notice = null
      opLog = listOf("—— $title ——")
      try {
        if (viewModel.engineRunning.value) {
          throw RuntimeException("请先在「首页」停止引擎，再进行插件操作")
        }
        val exit = viewModel.pluginManager.runPluginOp(args) { line ->
          opLog = (opLog + line).takeLast(300)
        }
        withContext(Dispatchers.Main) {
          if (exit == 0) {
            notice = okMsg
            noticeDanger = false
          } else {
            notice = "操作失败（exit=$exit），详见下方输出"
            noticeDanger = true
          }
          refresh()
        }
      } catch (t: Throwable) {
        withContext(Dispatchers.Main) {
          notice = t.message ?: "操作失败"
          noticeDanger = true
        }
      } finally {
        withContext(Dispatchers.Main) { busy = false }
      }
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 顶栏（子页返回）
    Row(
      Modifier
        .fillMaxWidth()
        .padding(top = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
      }
      Text(
        "插件管理",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    if (notice != null) {
      InlineAlert(title = if (noticeDanger) "操作失败" else "完成", message = notice!!, danger = noticeDanger)
    }

    // —— 已安装插件 ——
    SectionCard("已安装插件") {
      if (!catalog.profileExists) {
        Text(
          "尚未初始化 dsh profile（首次启动引擎后自动创建）。请先在「首页」启动一次引擎。",
          fontSize = 12.sp,
          lineHeight = 17.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Outlined.Extension, null,
            tint = if (catalog.pnpmReady) p.blue else p.muted,
            modifier = Modifier.size(16.dp),
          )
          Text(
            if (catalog.pnpmReady) "pnpm 就绪 · profile/web" else "pnpm 未安装（首次操作时自动补装）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (catalog.plugins.isEmpty()) {
          Text(
            "暂无插件。使用下方「安装插件」添加 npm 包（dsh 插件生态以 @deepseek-ai/dsh-* 与社区包形式发布）。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        catalog.plugins.forEach { plugin ->
          Row(
            Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                plugin.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                plugin.version,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // 内置组合包只读展示；树外插件可移除
            Text(
              if (plugin.builtin) "运行时内置" else "外部插件",
              fontSize = 11.sp,
              fontWeight = FontWeight(650),
              color = if (plugin.builtin) p.muted else p.blue,
            )
            if (!plugin.builtin) {
              IconButton(onClick = { confirmRemove = plugin.name }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, "移除", tint = p.muted, modifier = Modifier.size(16.dp))
              }
            }
          }
        }
        Spacer(Modifier.size(2.dp))
        Text(
          "刷新",
          fontSize = 12.sp,
          fontWeight = FontWeight(650),
          color = p.blue,
          modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .clickableNoIndication { refresh() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }

    // —— 安装插件 ——
    SectionCard("安装插件") {
      DshmInputField(
        value = installDraft,
        onValueChange = { installDraft = it },
        label = "npm 包名（可带版本）",
        hint = "如 @deepseek-ai/dsh-sandbox 或 @scope/plugin@1.2.3",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
      )
      PrimaryButton(
        "安装",
        onClick = {
          val pkg = installDraft.trim()
          runOp("安装 $pkg", listOf("add", pkg), "插件 $pkg 安装成功，重启引擎后生效")
          installDraft = ""
        },
        enabled = !busy && installDraft.trim().isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "安装会通过容器内 pnpm 拉取 npm 包并写入 profile；如切换过 npm 源，请在「设置」里选择合适的源后重试。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // —— 更新 ——
    SectionCard("更新插件") {
      val externals = catalog.plugins.filter { !it.builtin }
      if (externals.isEmpty()) {
        Text(
          "没有可更新的外部插件。",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        externals.forEach { plugin ->
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              plugin.name,
              fontSize = 13.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )
            Text(
              "更新",
              fontSize = 12.sp,
              fontWeight = FontWeight(650),
              color = p.blue,
              modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                .clickableNoIndication(enabled = !busy) {
                  runOp("更新 ${plugin.name}", listOf("update", plugin.name), "插件 ${plugin.name} 已更新")
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
      }
    }

    // —— 操作输出 ——
    if (opLog.isNotEmpty()) {
      SectionCard("操作输出") {
        Column(
          Modifier
            .fillMaxWidth()
            .background(p.surfaceMuted, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .border(1.dp, p.line, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(10.dp),
        ) {
          opLog.takeLast(80).forEach { line ->
            Text(
              line,
              fontSize = 11.sp,
              lineHeight = 15.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }

  // 移除确认
  confirmRemove?.let { pkg ->
    DshmDialog(
      onDismiss = { confirmRemove = null },
      title = "移除插件？",
      message = "将从 profile 中移除 $pkg（其依赖与数据保留在 node_modules 内，重新安装即可恢复）。",
      icon = Icons.Outlined.Delete,
      danger = true,
      confirmLabel = "移除",
      onConfirm = {
        confirmRemove = null
        runOp("移除 $pkg", listOf("remove", pkg), "插件 $pkg 已移除，重启引擎后生效")
      },
    )
  }
}

/** 无涟漪点击（对齐项目内交互风格；enabled=false 时不可点）。 */
private fun Modifier.clickableNoIndication(enabled: Boolean = true, onClick: () -> Unit): Modifier =
  if (enabled) {
    this.clickable(
      interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
      indication = null,
      onClick = onClick,
    )
  } else {
    this
  }
