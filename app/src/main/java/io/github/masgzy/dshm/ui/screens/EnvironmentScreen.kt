package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.engine.DistroCatalog
import io.github.masgzy.dshm.engine.DistroFamily
import io.github.masgzy.dshm.engine.Paths
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.AppMarkImage
import io.github.masgzy.dshm.ui.components.DangerQuietButton
import io.github.masgzy.dshm.ui.components.DetailRow
import io.github.masgzy.dshm.ui.components.DshmDialog
import io.github.masgzy.dshm.ui.components.DshVersionPickerDialog
import io.github.masgzy.dshm.ui.components.DistroPickerDialog
import io.github.masgzy.dshm.ui.components.InlineAlert
import io.github.masgzy.dshm.ui.components.InstallSteps
import io.github.masgzy.dshm.ui.components.NodeVersionPickerDialog
import io.github.masgzy.dshm.ui.components.Phases
import io.github.masgzy.dshm.ui.components.PrimaryButton
import io.github.masgzy.dshm.ui.components.ProgressBlock
import io.github.masgzy.dshm.ui.components.ScreenHeading
import io.github.masgzy.dshm.ui.components.SectionCard
import io.github.masgzy.dshm.ui.components.SecondaryButton
import io.github.masgzy.dshm.ui.components.StatusBadge
import io.github.masgzy.dshm.ui.components.phaseForStep
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import kotlinx.coroutines.delay

/**
 * 环境屏：运行时安装引导与日常管理一体
 * （对齐 dphmoblie/deepseek-harness-android 的 environment-screen）。
 *
 * 双安装路线（与 Release 分发双轨对应）：
 *  1. 所选容器系统 rootfs + 在线 bootstrap（默认，体积小、始终最新；
 *     五个内置发行版或任意自定义 Docker 镜像）；
 *  2. 预装运行时（CI 构建 bundle，仅 Ubuntu 26.04，安装快、离线可用）。
 */
@Composable
fun EnvironmentScreen(viewModel: AppViewModel, isOnboarding: Boolean = false) {
  val setup by viewModel.setup.collectAsState()
  val running by viewModel.engineRunning.collectAsState()
  val rootfsReady = viewModel.rootfsManager.isRootfsReady
  val engineReady = viewModel.engineManager.engineReady
  val installed = rootfsReady && (engineReady || setup.done)
  val inProgress = setup.running
  var confirmReset by remember { mutableStateOf(false) }
  var showDistroPicker by remember { mutableStateOf(false) }
  var showNodePicker by remember { mutableStateOf(false) }
  var showDshPicker by remember { mutableStateOf(false) }
  // distro/版本选择变化时重组（非 State 属性，需手动触发）
  var selectionEpoch by remember { mutableStateOf(0) }
  val distro = remember(selectionEpoch) { viewModel.distro }

  // 引擎运行状态周期刷新（IO 线程）：看门狗拉起/回收引擎后界面自动跟进
  LaunchedEffect(Unit) {
    while (true) {
      viewModel.refreshEngineState()
      delay(3_000)
    }
  }

  // 安装四步（下载 → 校验 → 安装 → 就绪）
  val currentStep = when {
    setup.done || installed -> 4
    setup.step.contains("校验") -> 1
    setup.step.contains("解压") || setup.step.contains("安装") || setup.step.contains("启用") -> 2
    setup.running -> 0
    else -> -1
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .statusBarsPadding()
      .padding(horizontal = 20.dp)
      .padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Spacer(Modifier.height(if (isOnboarding) 12.dp else 0.dp))
    ScreenHeading("应用管理", distro.label)

    // runtime-overview 卡片：蓝软渐变底 + 1px 淡蓝外环（.runtime-overview）
    val overviewShape = RoundedCornerShape(DshmRadius.card.dp)
    Box(
      Modifier
        .fillMaxWidth()
        .border(1.dp, LocalDshmPalette.current.blue.copy(alpha = 0.07f), overviewShape)
        .padding(1.dp),
    ) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(
          androidx.compose.ui.graphics.Brush.linearGradient(
            0f to androidx.compose.ui.graphics.lerp(
              LocalDshmPalette.current.surface,
              LocalDshmPalette.current.blueSoft,
              0.48f,
            ),
            1f to MaterialTheme.colorScheme.surface,
          ),
          overviewShape,
        )
        .border(1.dp, LocalDshmPalette.current.line, overviewShape)
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // runtime-logo：48dp 实底块（--icon-solid-bg）内嵌 34dp 吉祥物
        Box(
          Modifier
            .size(48.dp)
            .background(
              LocalDshmPalette.current.iconSolidBg,
              RoundedCornerShape(DshmRadius.inner.dp),
            ),
          contentAlignment = Alignment.Center,
        ) {
          AppMarkImage(34)
        }
        Column(Modifier.weight(1f)) {
          Text(
            distro.label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            when {
              setup.running -> setup.step.ifBlank { "正在准备" }
              installed -> "运行时就绪 · ${installedNodeLabel(viewModel)} · ${installedDshLabel(viewModel)}"
              setup.error != null -> "安装未完成"
              else -> "等待安装 · ${distro.detail}"
            },
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        StatusBadge(
          when {
            setup.running -> phaseForStep(setup.step)
            setup.error != null -> Phases.ERROR
            running -> Phases.RUNNING
            installed -> Phases.READY
            else -> Phases.NOT_INSTALLED
          },
        )
      }

      if (inProgress) {
        ProgressBlock(
          phase = setup.step.ifBlank { "处理中" },
          progress = setup.progress,
          detail = if (setup.step.contains("校验")) "正在比对 SHA-256 校验值，确保运行时完整可信" else null,
        )
        InstallSteps(
          steps = listOf("下载", "校验", "安装", "就绪"),
          currentStep = currentStep.coerceAtMost(3),
          allDone = false,
        )
      }

      // 操作按钮区：随状态切换（参考项目 runtime-actions）
      when {
        // 未安装：双路线安装（预装 bundle 仅 Ubuntu 26.04 可用）
        !installed && !inProgress -> {
          PrimaryButton(
            if (distro.id == "ubuntu26") "下载并安装" else "安装 ${distro.label}",
            { selectionEpoch++; viewModel.installOfficial() },
            Modifier.fillMaxWidth(),
            icon = Icons.Outlined.CloudDownload,
          )
          Text(
            when (distro.id) {
              "alpine3" ->
                "从 Docker registry 拉取 ${distro.imageRef}（约 5MB），随后在容器内通过 apk 安装 Node.js 与 dsh" +
                  "（官方 Node tarball 不兼容 musl；需网络，支持国内镜像加速）。"
              "ubuntu26" ->
                "获取 Ubuntu 官方基础镜像（约 30MB），随后在容器内安装 Node.js 与 dsh" +
                  "（约 1-3 分钟，需网络，支持国内镜像加速；直链不可用时自动改走 Docker registry）。"
              DistroCatalog.CUSTOM_ID ->
                "从 Docker registry 拉取 ${distro.imageRef}，随后在容器内安装 Node.js 与 dsh" +
                  "（需为 glibc 系镜像且带 bash；musl 自定义镜像不受支持）。"
              else ->
                "从 Docker registry 拉取 ${distro.imageRef}（逐层 SHA-256 校验），随后在容器内安装 Node.js 与 dsh" +
                  "（约 1-3 分钟，需网络，支持国内镜像加速）。"
            },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (distro.id == "ubuntu26") {
            SecondaryButton(
              "使用预装运行时（更快）",
              { viewModel.installBundled() },
              Modifier.fillMaxWidth(),
              icon = Icons.Outlined.Archive,
            )
            Text(
              "下载本仓库 CI 预构建的完整运行时 bundle（含 Node + dsh，约 200MB），" +
                "SHA-256 校验后一键启用，无需容器内联网安装（仅 Ubuntu 26.04 提供）。",
              fontSize = 12.sp,
              lineHeight = 17.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        inProgress -> {
          Text(
            "安装时会校验运行时完整性；从网络下载的，中断后可以重试继续。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        installed -> {
          Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (!running) {
              PrimaryButton(
                "启动引擎",
                { viewModel.startEngine() },
                Modifier.weight(1f),
                icon = Icons.Outlined.PlayArrow,
              )
            } else {
              SecondaryButton(
                "停止引擎",
                { viewModel.stopEngine() },
                Modifier.weight(1f),
                icon = Icons.Outlined.PowerSettingsNew,
              )
            }
            DangerQuietButton(
              "重置环境",
              { confirmReset = true },
              Modifier.weight(1f),
              icon = Icons.Outlined.RestoreFromTrash,
            )
          }
          Text(
            "重置将删除容器 rootfs 与全部缓存（/root/projects 工作区文件保留），" +
              "下次使用需重新安装。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    }

    // 错误告警条（inline-alert danger）+ 重试
    if (setup.error != null) {
      InlineAlert(
        title = "安装未完成",
        message = setup.error ?: "",
        danger = true,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        PrimaryButton("重试在线安装", { selectionEpoch++; viewModel.installOfficial() }, Modifier.weight(1f))
        if (distro.id == "ubuntu26") {
          SecondaryButton("改用预装运行时", { viewModel.installBundled() }, Modifier.weight(1f))
        }
      }
    }

    // 安装日志（终端深色底，等宽小字）
    if (setup.logLines.isNotEmpty()) {
      Column(
        Modifier
          .fillMaxWidth()
          .background(LocalDshmPalette.current.terminalBg, RoundedCornerShape(DshmRadius.control.dp))
          .border(1.dp, LocalDshmPalette.current.terminalLine, RoundedCornerShape(DshmRadius.control.dp))
          .padding(12.dp),
      ) {
        Text(
          "安装日志（最近 ${setup.logLines.size.coerceAtMost(30)} 条）",
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = LocalDshmPalette.current.terminalLine,
        )
        Spacer(Modifier.height(8.dp))
        setup.logLines.takeLast(30).forEach { line ->
          Text(
            line,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = androidx.compose.ui.graphics.Color(0xFFC6CED8),
          )
        }
      }
    }

    // 容器系统选择（未安装时可自由切换；已安装后改设置需重置环境）
    if (!installed && !inProgress) {
      SectionCard("容器系统") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          RuntimePickersRow(
            title = "发行版",
            value = distro.display,
          ) { showDistroPicker = true }
          RuntimePickersRow(
            title = "Node.js 版本",
            value = if (distro.family == DistroFamily.ALPINE) "随 apk（容器内安装）" else viewModel.nodeVersion,
          ) { showNodePicker = true }
          RuntimePickersRow(
            title = "dsh 版本",
            value = if (viewModel.dshVersion.isBlank()) "跟随 npm latest" else viewModel.dshVersion,
          ) { showDshPicker = true }
        }
      }
    }

    // 环境详情（detail-section）
    SectionCard("环境详情") {
      DetailRow(Icons.Outlined.Memory, "架构", "arm64-v8a")
      DetailRow(Icons.Outlined.Archive, "系统版本", distro.display)
      DetailRow(Icons.Outlined.CloudDownload, "Node.js", if (installed) installedNodeLabel(viewModel) else viewModel.nodeVersion)
      DetailRow(Icons.Outlined.Memory, "Harness", if (installed) installedDshLabel(viewModel) else dshSelectionLabel(viewModel))
      DetailRow(Icons.Outlined.PowerSettingsNew, "网络访问", "仅本应用内（127.0.0.1）")
      DetailRow(
        Icons.Outlined.RestoreFromTrash,
        "运行组件",
        if (viewModel.engineManager.isRunnerAvailable) "可用" else "不可用",
      )
    }

    if (isOnboarding) {
      Text(
        "安装完成后即可在容器内运行 dsh Web UI；\n装一次就够，之后可离线使用。",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  // 重置确认（底部弹卡）
  if (confirmReset) {
    DshmDialog(
      onDismiss = { confirmReset = false },
      title = "重置运行环境？",
      message = "将删除容器、Node.js 与 dsh 全部数据" +
        "（/root/projects 工作区文件保留）。下次使用需重新安装。",
      icon = Icons.Outlined.RestoreFromTrash,
      danger = true,
      confirmLabel = "重置",
      onConfirm = {
        confirmReset = false
        viewModel.uninstallRuntime()
      },
    )
  }

  // 容器系统 / 版本选择器
  if (showDistroPicker) {
    DistroPickerDialog(viewModel) {
      showDistroPicker = false
      selectionEpoch++
    }
  }
  if (showNodePicker) {
    NodeVersionPickerDialog(viewModel) {
      showNodePicker = false
      selectionEpoch++
    }
  }
  if (showDshPicker) {
    DshVersionPickerDialog(viewModel) {
      showDshPicker = false
      selectionEpoch++
    }
  }
}

/** 已安装容器的 Node 实际版本（读标记；读不到时回退当前选择）。 */
internal fun installedNodeLabel(viewModel: AppViewModel): String {
  val marker = java.io.File(
    viewModel.rootfsManager.rootfsDir,
    "root/.dshm/installed",
  )
  if (marker.isFile) {
    marker.readLines().firstOrNull { it.startsWith("node=") }?.let {
      return "Node.js ${it.removePrefix("node=").trim()}"
    }
  }
  return "Node.js ${viewModel.nodeVersion}"
}

/** 已安装容器的 dsh 实际版本（读标记；读不到时回退当前选择）。 */
internal fun installedDshLabel(viewModel: AppViewModel): String {
  val marker = java.io.File(
    viewModel.rootfsManager.rootfsDir,
    "root/.dshm/installed",
  )
  if (marker.isFile) {
    marker.readLines().firstOrNull { it.startsWith("dsh=") }?.let {
      val v = it.removePrefix("dsh=").trim()
      return "dsh ${if (v == "latest") "latest" else v}"
    }
  }
  return "dsh ${dshSelectionLabel(viewModel)}"
}

/** 当前 dsh 选择文案。 */
internal fun dshSelectionLabel(viewModel: AppViewModel): String =
  if (viewModel.dshVersion.isBlank()) "latest" else viewModel.dshVersion

/** 容器系统/版本选择行（环境屏与设置页共用形态）。 */
@Composable
internal fun RuntimePickersRow(
  title: String,
  value: String,
  onClick: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable { onClick() }
      .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        value,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalDshmPalette.current.blue,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 200.dp),
      )
      Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        null,
        tint = LocalDshmPalette.current.muted,
        modifier = Modifier.size(16.dp),
      )
    }
  }
}
