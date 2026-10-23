package io.github.masgzy.dshm.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.masgzy.dshm.ui.screens.HomeScreen
import io.github.masgzy.dshm.ui.screens.LogsScreen
import io.github.masgzy.dshm.ui.screens.SettingsScreen

/** 应用根导航：未安装 → 安装向导；已安装 → 底部三 Tab。 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
  val setupDone = viewModel.setup.collectAsState().value.done
  val installed = viewModel.rootfsManager.isRootfsReady &&
    (viewModel.engineManager.engineReady || setupDone)

  if (!installed) {
    io.github.masgzy.dshm.ui.screens.SetupScreen(viewModel)
    return
  }

  val nav = rememberNavController()
  val context = LocalContext.current
  val backStack by nav.currentBackStackEntryAsState()
  val current = backStack?.destination?.route ?: "home"

  Scaffold(
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = current == "home",
          onClick = {
            nav.navigate("home") {
              popUpTo("home") { saveState = true }
              launchSingleTop = true
              restoreState = true
            }
          },
          icon = { Icon(Icons.Filled.PlayArrow, null) },
          label = { Text("运行") },
        )
        NavigationBarItem(
          selected = current == "logs",
          onClick = {
            nav.navigate("logs") {
              popUpTo("home") { saveState = true }
              launchSingleTop = true
              restoreState = true
            }
          },
          icon = { Icon(Icons.Filled.Terminal, null) },
          label = { Text("日志") },
        )
        NavigationBarItem(
          selected = current == "settings",
          onClick = {
            nav.navigate("settings") {
              popUpTo("home") { saveState = true }
              launchSingleTop = true
              restoreState = true
            }
          },
          icon = { Icon(Icons.Filled.Info, null) },
          label = { Text("设置") },
        )
      }
    },
  ) { padding ->
    NavHost(
      navController = nav,
      startDestination = "home",
      modifier = Modifier.padding(padding),
    ) {
      composable("home") { HomeScreen(viewModel) }
      composable("logs") { LogsScreen(context.filesDir) }
      composable("settings") { SettingsScreen(viewModel) }
    }
  }
}
