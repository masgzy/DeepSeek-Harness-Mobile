package io.github.masgzy.dshm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.masgzy.dshm.ui.components.BrandHeader
import io.github.masgzy.dshm.ui.components.Phases
import io.github.masgzy.dshm.ui.components.PhaseStyle
import io.github.masgzy.dshm.ui.components.StatusBadge
import io.github.masgzy.dshm.ui.components.phaseForStep
import io.github.masgzy.dshm.ui.screens.EnvironmentScreen
import io.github.masgzy.dshm.ui.screens.HomeScreen
import io.github.masgzy.dshm.ui.screens.LogsScreen
import io.github.masgzy.dshm.ui.screens.ModelsScreen
import io.github.masgzy.dshm.ui.screens.PluginsScreen
import io.github.masgzy.dshm.ui.screens.SettingsScreen
import io.github.masgzy.dshm.ui.screens.TerminalScreen
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import io.github.masgzy.dshm.ui.theme.drawDshmBackground
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用外壳（miuix 版）：miuix Scaffold 提供弹窗宿主（MiuixPopupHost，OverlayDialog
 * 依赖它渲染）与顶/底栏插槽 —— 顶栏保留品牌 Header（Surface 换 miuix），底部导航
 * 换 miuix NavigationBar（HyperOS 底栏质感）；内容区仍为底色双径向渐变 + NavHost。
 *
 * 未安装时只显示环境屏（安装引导与运行时管理一体）；装好后环境操作并入设置页。
 * dsh 对话走独立全屏 HarnessActivity；容器终端走独立全屏 TerminalScreen
 * （入口在首页下方按钮，全屏时隐藏底部导航）。
 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
  val p = LocalDshmPalette.current
  val setup by viewModel.setup.collectAsState()
  val engineRunning by viewModel.engineRunning.collectAsState()
  val installed by viewModel.installedUi.collectAsState()

  val nav = rememberNavController()
  val backStack by nav.currentBackStackEntryAsState()
  val current = backStack?.destination?.route ?: "home"
  val phase = currentPhase(setup.running, setup.step, setup.error, engineRunning, viewModel)
  val fullScreen = current == "terminal"

  val goTo: (String) -> Unit = { route ->
    nav.navigate(route) {
      popUpTo("home") { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  Box(
    Modifier
      .fillMaxSize()
      .drawBehind { drawDshmBackground(p) },
  ) {
    Scaffold(
      // 背景透明：双径向渐变由外层 Box 绘制，Scaffold 不再叠一层实底
      containerColor = Color.Transparent,
      // inset 由内部组件自管（顶栏 statusBarsPadding / 底栏 NavigationBar 自带）
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      topBar = {
        if (!installed || !fullScreen) {
          // 首装引导期隐藏设置键：settings 路由内容在未安装时不渲染（环境屏独占
          // 内容区），不隐藏会点进一个只有系统返回键才能离开的死页面
          AppTopBar(
            current = current,
            phase = phase,
            showSettings = installed,
            onSettings = { goTo("settings") },
          )
        }
      },
      bottomBar = {
        if (installed && !fullScreen) {
          BottomNav(current, goTo)
        }
      },
    ) { _ ->
      if (!installed) {
        // 首次引导：环境屏承担安装向导职责（装完 installedUi→true 自动流转正式界面）。
        // 回归修复：miuix 迁移时该分支被遗漏，未安装用户直接落在主页（对话门屏），
        // 找不到任何安装入口 —— 恢复迁移前 AppRoot 的安装态分流，环境屏在壳内
        // 渲染（顶栏保留品牌与阶段徽章，底部导航随 installed=false 自动隐藏）。
        EnvironmentScreen(viewModel, isOnboarding = true)
      } else {
        NavHost(
          navController = nav,
          startDestination = "home",
          modifier = Modifier.fillMaxSize(),
        ) {
          composable("home") {
            HomeScreen(
              viewModel,
              onOpenTerminal = { nav.navigate("terminal") { launchSingleTop = true } },
            )
          }
          composable("plugins") { PluginsScreen(viewModel) }
          composable("settings") {
            SettingsScreen(
              viewModel,
              onOpenLogs = { nav.navigate("logs") { launchSingleTop = true } },
              onOpenModels = { nav.navigate("models") { launchSingleTop = true } },
            )
          }
          composable("logs") { LogsScreen(viewModel) }
          composable("models") {
            ModelsScreen(viewModel, onBack = { nav.popBackStack() })
          }
          composable("terminal") {
            TerminalScreen(viewModel, onBack = { nav.popBackStack() })
          }
        }
      }
    }
  }
}

/** mobile-header：miuix Surface（表面 96% 底）+ brand Header + 阶段徽章 + 设置键。 */
@Composable
private fun AppTopBar(
  current: String,
  phase: PhaseStyle,
  showSettings: Boolean,
  onSettings: () -> Unit,
) {
  val p = LocalDshmPalette.current
  Surface(
    color = p.headerBg,
    shadowElevation = 2.dp,
  ) {
    Column {
      Row(
        Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BrandHeader()
        Row(
          horizontalArrangement = Arrangement.spacedBy(9.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          StatusBadge(phase)
          // 首页右侧仅「应用设置」图标键（终端入口挪到首页下方，避免顶栏拥挤）
          if (current == "home" && showSettings) {
            HeaderIconButton(Icons.Outlined.Settings, "应用设置") { onSettings() }
          }
        }
      }
      Box(
        Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(p.line),
      )
    }
  }
}

/** 顶栏图标键（.header-settings 同形态：44dp 表面底 + 1px 描边）。 */
@Composable
private fun HeaderIconButton(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
) {
  val p = LocalDshmPalette.current
  Box(
    Modifier
      .size(44.dp)
      .clip(RoundedCornerShape(DshmRadius.control.dp))
      .background(MiuixTheme.colorScheme.surface)
      .border(1.dp, p.line, RoundedCornerShape(DshmRadius.control.dp))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onClick() },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      contentDescription = label,
      tint = MiuixTheme.colorScheme.onSurface,
      modifier = Modifier.size(21.dp),
    )
  }
}

/** 全局阶段徽章：安装流程 > 引擎状态，与参考项目 PHASE_META 语义一致。 */
private fun currentPhase(
  setupRunning: Boolean,
  setupStep: String,
  setupError: String?,
  engineRunning: Boolean,
  viewModel: AppViewModel,
): PhaseStyle = when {
  setupRunning -> phaseForStep(setupStep)
  setupError != null -> Phases.ERROR
  engineRunning -> Phases.RUNNING
  // 已安装但引擎未跑：中性静态「环境就绪」（此前显示绿色呼吸「已就绪」，
  // 用户反馈关闭应用再进入会误以为服务已在后台运行 —— 绿点呼吸 + 「已就绪」
  // 是运行中语义，空闲态不得使用）
  viewModel.rootfsManager.isRootfsReady && viewModel.engineManager.engineReady -> Phases.STANDBY
  else -> Phases.NOT_INSTALLED
}

/**
 * 底部导航：miuix NavigationBar（HyperOS 底栏，图标 + 标签、选中主色高亮）。
 * 顶缘 1px 分隔线保留（与顶栏呼应）。
 */
@Composable
private fun BottomNav(current: String, goTo: (String) -> Unit) {
  val p = LocalDshmPalette.current
  Column {
    Box(
      Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(p.line),
    )
    NavigationBar(
      color = p.navBg,
    ) {
      NavigationBarItem(
        selected = current == "home",
        onClick = { goTo("home") },
        icon = Icons.Rounded.ChatBubbleOutline,
        label = "首页",
      )
      NavigationBarItem(
        selected = current == "plugins",
        onClick = { goTo("plugins") },
        icon = Icons.Outlined.Extension,
        label = "插件",
      )
      NavigationBarItem(
        selected = current == "settings",
        onClick = { goTo("settings") },
        icon = Icons.Outlined.Settings,
        label = "设置",
      )
    }
  }
}
