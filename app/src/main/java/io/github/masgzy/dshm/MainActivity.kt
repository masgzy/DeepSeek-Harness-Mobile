package io.github.masgzy.dshm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.masgzy.dshm.ui.AppRoot
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.theme.DshmTheme

class MainActivity : ComponentActivity() {

  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Android 11+ 才启用 edge-to-edge：API<30 拿不到 IME insets 且
    // adjustResize 在 decorFits=false 下失效，终端快捷键条会被键盘盖住；
    // 老系统保持默认 decorFits，让 windowSoftInputMode=adjustResize 正常工作
    if (Build.VERSION.SDK_INT >= 30) enableEdgeToEdge()
    // Android 13+ 通知权限（前台服务保活通知需要）
    if (Build.VERSION.SDK_INT >= 33) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    setContent {
      DshmTheme {
        val vm: AppViewModel = viewModel()
        AppRoot(vm)
      }
    }
  }
}
