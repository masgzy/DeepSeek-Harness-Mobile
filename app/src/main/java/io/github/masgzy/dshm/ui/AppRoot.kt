package io.github.masgzy.dshm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.masgzy.dshm.ui.components.Phases
import io.github.masgzy.dshm.ui.components.PhaseStyle
import io.github.masgzy.dshm.ui.components.StatusBadge
import io.github.masgzy.dshm.ui.components.phaseForStep
import io.github.masgzy.dshm.ui.screens.EnvironmentScreen
import io.github.masgzy.dshm.ui.screens.HomeScreen
import io.github.masgzy.dshm.ui.screens.LogsScreen
import io.github.masgzy.dshm.ui.screens.SettingsScreen
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/**
 * 应用外壳：整版对齐 dphmoblie/deepseek-harness-android 的 app-shell——
 * 顶栏（品牌 + PhaseBadge）+ 主内容 + 底部三项导航（首页 / 环境 / 设置）。
 *
 * 未安装时只显示环境屏（安装引导与运行时管理一体），与参考项目
 * 「Onboarding → 打开对话」的两段式心智一致。
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
      Column(
        Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.background)
          .statusBarsPadding()
          .padding(horizontal = 20.dp, vertical = 12.dp),
      ) {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              "DeepSeek Harness",
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              "Mobile",
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              color = LocalDshmPalette.current.muted,
            )
          }
          StatusBadge(phase)
        }
      }
    },
    bottomBar = {
      NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
      ) {
        NavItem(Icons.Rounded.Home, "首页", "home", current, goTo)
        NavItem(Icons.Rounded.Memory, "环境", "environment", current, goTo)
        NavItem(Icons.Outlined.Settings, "设置", "settings", current, goTo)
      }
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

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
  icon: ImageVector,
  label: String,
  route: String,
  current: String,
  goTo: (String) -> Unit,
) {
  val p = LocalDshmPalette.current
  NavigationBarItem(
    selected = current == route,
    onClick = { goTo(route) },
    icon = { Icon(icon, null) },
    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
    colors = NavigationBarItemDefaults.colors(
      selectedIconColor = p.blue,
      selectedTextColor = p.blue,
      indicatorColor = p.blueSoft,
      unselectedIconColor = p.muted,
      unselectedTextColor = p.muted,
    ),
  )
}
