package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.BuildConfig
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.DangerQuietButton
import io.github.masgzy.dshm.ui.components.InlineAlert
import io.github.masgzy.dshm.ui.components.SectionCard
import io.github.masgzy.dshm.ui.components.CapsuleSelector
import io.github.masgzy.dshm.ui.components.DshmDialog
import io.github.masgzy.dshm.ui.components.DshmInputField
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import io.github.masgzy.dshm.util.AppLog

/**
 * 设置页：对齐 dphmoblie/deepseek-harness-android 设置首页的分组卡片形态——
 * 图标 + 标题 + 说明（hint）+ 右箭头；数值项行内展开编辑。
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel, onOpenLogs: () -> Unit) {
  val p = LocalDshmPalette.current
  var editingPort by rememberSaveable { mutableStateOf(false) }
  var portDraft by rememberSaveable { mutableStateOf("") }
  var confirmUninstall by remember { mutableStateOf(false) }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // —— 通用 ——
    SectionCard("通用") {
      // 端口
      SettingRow(
        icon = Icons.Outlined.Dns,
        title = "Web UI 端口",
        hint = "dsh web 监听地址端口（默认 ${Paths.DSH_PORT}）",
        value = viewModel.port.toString(),
      ) {
        portDraft = viewModel.port.toString()
        editingPort = true
      }
      // npm 源
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "npm 源（安装 / 更新 dsh 时使用）",
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        CapsuleSelector(
          options = listOf("国内镜像", "官方源"),
          selected = if (viewModel.npmRegistry == Paths.NPM_REGISTRY_CN) 0 else 1,
          onSelect = { index ->
            viewModel.npmRegistry =
              if (index == 0) Paths.NPM_REGISTRY_CN else Paths.NPM_REGISTRY_OFFICIAL
          },
        )
      }
    }

    // —— 日志与排查 ——
    SectionCard("日志与排查") {
      SettingRow(
        icon = Icons.Outlined.Description,
        title = "引擎与应用日志",
        hint = "引擎 stdout、应用运行日志与崩溃记录",
        value = null,
        onClick = onOpenLogs,
      )
      if (AppLog.crashLogFile.isFile) {
        InlineAlert(
          title = "检测到崩溃记录",
          message = "crash.log 已生成，反馈问题时请到「日志与排查」复制崩溃内容一并提交。",
          danger = true,
        )
      }
    }

    // —— 版本管理 ——
    SectionCard("版本管理") {
      DetailLine("应用版本", BuildConfig.VERSION_NAME)
      DetailLine("Ubuntu", "${Paths.UBUNTU_RELEASE} LTS（Resolute Raccoon）")
      DetailLine("Node.js", Paths.NODE_VERSION)
      DetailLine("Harness", "dsh ${Paths.DSH_VERSION}")
    }

    // —— 关于 ——
    SectionCard("关于") {
      SettingRow(
        icon = Icons.Outlined.Info,
        title = "开源与许可",
        hint = "GPL-3.0 开源；DeepSeek Harness 为 DeepSeek 官方开源项目（developer preview）",
        value = null,
      )
      SettingRow(
        icon = Icons.Outlined.Archive,
        title = "第三方组件",
        hint = "proot / libtalloc / libandroid-shmem / Node.js 等声明见仓库 THIRD_PARTY_NOTICES.md",
        value = null,
      )
    }

    // —— 容器管理 ——
    SectionCard("容器管理") {
      DangerQuietButton(
        "重置运行环境",
        { confirmUninstall = true },
        Modifier.fillMaxWidth(),
        icon = Icons.Outlined.RestoreFromTrash,
      )
      Text(
        "卸载将删除容器 rootfs 与全部缓存（工作区文件保留）。下次使用需重新安装。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Spacer(Modifier.height(8.dp))
  }

  // 端口编辑（底部弹卡 + sunken 输入框）
  if (editingPort) {
    DshmDialog(
      onDismiss = { editingPort = false },
      title = "Web UI 端口",
      message = "修改后需重新启动引擎生效（1024-65535）。",
      confirmLabel = "保存",
      confirmEnabled = portDraft.toIntOrNull() in 1024..65535,
      onConfirm = {
        portDraft.toIntOrNull()?.let { viewModel.port = it }
        editingPort = false
      },
    ) {
      DshmInputField(
        value = portDraft,
        onValueChange = { portDraft = it.filter(Char::isDigit).take(5) },
        label = "端口",
        hint = "1024-65535",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )
    }
  }

  // 卸载确认（底部弹卡，危险态）
  if (confirmUninstall) {
    DshmDialog(
      onDismiss = { confirmUninstall = false },
      title = "重置运行环境？",
      message = "将删除 Ubuntu 容器、Node.js 与 dsh 全部数据" +
        "（/root/projects 工作区文件保留）。下次使用需重新安装。",
      icon = Icons.Outlined.RestoreFromTrash,
      danger = true,
      confirmLabel = "重置",
      onConfirm = {
        confirmUninstall = false
        viewModel.uninstallRuntime()
      },
    )
  }
}

/** 设置行：图标 + 标题 + 说明（+ 右侧值/箭头），整行可点。 */
@Composable
private fun SettingRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  hint: String,
  value: String? = null,
  onClick: (() -> Unit)? = null,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      icon,
      null,
      tint = LocalDshmPalette.current.muted,
      modifier = Modifier.size(20.dp),
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        hint,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (value != null) {
      Text(
        value,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalDshmPalette.current.blue,
      )
    }
    if (onClick != null) {
      Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        null,
        tint = LocalDshmPalette.current.muted,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

/** 键值行：版本管理/详情展示。 */
@Composable
private fun DetailLine(label: String, value: String) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
    Text(
      value,
      fontSize = 13.5.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
