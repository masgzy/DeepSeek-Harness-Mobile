package io.github.masgzy.dshm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.masgzy.dshm.ui.screens.SettingsScreen
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/**
 * 应用外壳：整版对齐 dphmoblie/deepseek-harness-android 的 mobile-header +
 * bottom-navigation —— 品牌 Header（brand-symbol + deepseek + HARNESS 徽章 +
 * 全局阶段徽章）+ 主内容 + 底部三项导航（首页 / 环境 / 设置）。
 *
 * 未安装时只显示环境屏（安装引导与运行时管理一体）。
 * dsh 对话走独立全屏 HarnessActivity（门屏「打开对话」进入），与参考项目
 * HarnessActivity 覆盖外壳的模式一致。
 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
  val setup by viewModel.setup.collectAsState()
  val engineRunning by viewModel.engineRunning.collectAsState()
  val installed = viewModel.rootfsManager.isRootfsReady &&
    (viewModel.engineManager.engineReady || setup.done)

  if (!installed) {
    // 首次引导：环境屏承担安装向导职责
    EnvironmentScreen(viewModel, isOnboarding = true)
    return
  }

  val nav = rememberNavController()
  val backStack by nav.currentBackStackEntryAsState()
  val current = backStack?.destination?.route ?: "home"
  val phase = currentPhase(setup.running, setup.step, setup.error, engineRunning, viewModel)

  val goTo: (String) -> Unit = { route ->
    nav.navigate(route) {
      popUpTo("home") { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      // mobile-header：表面底 + 下缘分隔线 + 投影（styles.css .mobile-header）
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
      ) {
        Row(
          Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(32.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BrandHeader()
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            StatusBadge(phase)
            // 会话页右侧「应用设置」图标键（.header-settings）：44dp 表面灰底
            if (current == "home") {
              Box(
                Modifier
                  .size(44.dp)
                  .background(
                    LocalDshmPalette.current.surfaceMuted,
                    RoundedCornerShape(DshmRadius.control.dp),
                  )
                  .clickable { goTo("settings") },
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  Icons.Outlined.Settings,
                  contentDescription = "应用设置",
                  tint = LocalDshmPalette.current.ink,
                  modifier = Modifier.size(19.dp),
                )
              }
            }
          }
        }
      }
    },
    bottomBar = {
      BottomNav(current, goTo)
    },
  ) { padding ->
    NavHost(
      navController = nav,
      startDestination = "home",
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      composable("home") { HomeScreen(viewModel) }
      composable("environment") { EnvironmentScreen(viewModel) }
      composable("settings") { SettingsScreen(viewModel, onOpenLogs = { nav.navigate("logs") { launchSingleTop = true } }) }
      composable("logs") { LogsScreen(viewModel) }
    }
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
  viewModel.rootfsManager.isRootfsReady && viewModel.engineManager.engineReady -> Phases.READY
  else -> Phases.NOT_INSTALLED
}

/**
 * 底部导航（.bottom-navigation）：表面底 + 上缘分隔线，每项为「图标 20 + 标签 11」
 * 的扁平竖排按钮——选中蓝、未选中灰，无 M3 药丸指示器，对齐参考项目形态。
 */
@Composable
private fun BottomNav(current: String, goTo: (String) -> Unit) {
  val p = LocalDshmPalette.current
  Surface(color = p.surface) {
    Column {
      Box(
        Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(p.line),
      )
      Row(
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .height(58.dp),
      ) {
        NavItem(Icons.Rounded.ChatBubbleOutline, "首页", "home", current, goTo)
        NavItem(Icons.Rounded.Memory, "环境", "environment", current, goTo)
        NavItem(Icons.Outlined.Settings, "设置", "settings", current, goTo)
      }
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
  icon: ImageVector,
  label: String,
  route: String,
  current: String,
  goTo: (String) -> Unit,
) {
  val p = LocalDshmPalette.current
  val active = current == route
  val tint = if (active) p.blue else p.muted
  Column(
    Modifier
      .weight(1f)
      .fillMaxSize()
      .clickable { goTo(route) },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    Spacer(Modifier.height(3.dp))
    Text(
      label,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      color = tint,
      lineHeight = 13.sp,
    )
  }
}
