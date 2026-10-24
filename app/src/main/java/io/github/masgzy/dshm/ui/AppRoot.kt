package io.github.masgzy.dshm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import io.github.masgzy.dshm.ui.theme.drawDshmBackground

/**
 * 应用外壳：整版复刻 dphmoblie/deepseek-harness-android 的 app-shell——
 * 毛玻璃顶栏（96% 表面 + 下缘 1px 线，brand-symbol + deepseek + HARNESS 徽章 +
 * 全局阶段徽章 + 仅首页设置钮）+ 底色双径向渐变的主内容 + 底部三项导航
 * （首页 / 环境 / 设置，选中 accent 软底 12 圆角胶囊）。
 *
 * 未安装时只显示环境屏（安装引导与运行时管理一体）。
 * dsh 对话走独立全屏 HarnessActivity（门屏「打开对话」进入），与参考项目
 * HarnessActivity 覆盖外壳的模式一致。
 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
  val p = LocalDshmPalette.current
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

  Box(
    Modifier
      .fillMaxSize()
      .drawBehind { drawDshmBackground(p) },
  ) {
    Column(Modifier.fillMaxSize()) {
      // ---- mobile-header：表面 96% 底 + 下缘 1px 分隔线 + 轻投影 ----
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
              // 会话页右侧「应用设置」图标键（.header-settings）：44dp 表面底 + 1px 描边
              if (current == "home") {
                Box(
                  Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(DshmRadius.control.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, p.line, RoundedCornerShape(DshmRadius.control.dp))
                    .clickable(
                      interactionSource = remember { MutableInteractionSource() },
                      indication = null,
                    ) { goTo("settings") },
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "应用设置",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp),
                  )
                }
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

      // ---- app-main：独立内容区（各屏自带滚动） ----
      Box(Modifier.weight(1f)) {
        NavHost(
          navController = nav,
          startDestination = "home",
          modifier = Modifier.fillMaxSize(),
        ) {
          composable("home") { HomeScreen(viewModel) }
          composable("environment") { EnvironmentScreen(viewModel) }
          composable("settings") {
            SettingsScreen(
              viewModel,
              onOpenLogs = { nav.navigate("logs") { launchSingleTop = true } },
              onOpenModels = { nav.navigate("models") { launchSingleTop = true } },
              onOpenPlugins = { nav.navigate("plugins") { launchSingleTop = true } },
            )
          }
          composable("logs") { LogsScreen(viewModel) }
          composable("models") {
            ModelsScreen(viewModel, onBack = { nav.popBackStack() })
          }
          composable("plugins") {
            PluginsScreen(viewModel, onBack = { nav.popBackStack() })
          }
        }
      }

      // ---- bottom-navigation ----
      BottomNav(current, goTo)
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
 * 底部导航（.bottom-navigation）：surface 92% 底 + 顶缘 1px 分隔线；
 * item 54 高 / 12 圆角（图标 20-21 + 标签 11），选中 = accent 字 + accent 软底。
 */
@Composable
private fun BottomNav(current: String, goTo: (String) -> Unit) {
  val p = LocalDshmPalette.current
  Surface(color = p.navBg) {
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
          .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
  val selected = current == route
  Column(
    Modifier
      .weight(1f)
      .height(54.dp)
      .clip(RoundedCornerShape(DshmRadius.control.dp))
      .background(if (selected) p.blueSoft else Color.Transparent)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { goTo(route) },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      icon,
      contentDescription = label,
      tint = if (selected) p.blue else p.muted,
      modifier = Modifier.size(21.dp),
    )
    Spacer(Modifier.height(4.dp))
    Text(
      label,
      fontSize = 11.sp,
      fontWeight = if (selected) FontWeight(650) else FontWeight.Medium,
      color = if (selected) p.blue else p.muted,
      lineHeight = 13.sp,
      maxLines = 1,
    )
  }
}
