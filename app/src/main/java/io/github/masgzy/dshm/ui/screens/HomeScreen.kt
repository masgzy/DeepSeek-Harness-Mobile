package io.github.masgzy.dshm.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Square
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.HarnessActivity
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.AppMarkImage
import io.github.masgzy.dshm.ui.components.InlineAlert
import io.github.masgzy.dshm.ui.components.PrimaryButton
import io.github.masgzy.dshm.ui.components.ScreenHeading
import io.github.masgzy.dshm.ui.components.SecondaryButton
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import kotlinx.coroutines.delay

/**
 * 首页：对话门屏（对齐 dphmoblie/deepseek-harness-android 的 conversation-gate）。
 *
 * 「打开对话」→ 启动引擎并等待 token 就绪 → 独立全屏 HarnessActivity 承载
 * dsh Web UI（参考项目 openHarness → HarnessActivity 覆盖外壳的同款模式）。
 * 引擎已在运行时主按钮变为「进入对话」，直接凭日志尾部 token 打开对话页；
 * 引擎重启换 token 的 401 自愈逻辑内置于 HarnessActivity。
 */
@Composable
fun HomeScreen(viewModel: AppViewModel) {
  val running by viewModel.engineRunning.collectAsState()
  val webUrl by viewModel.webUrl.collectAsState()
  val context = LocalContext.current
  var launching by remember { mutableStateOf(false) }
  var stopping by remember { mutableStateOf(false) }
  // 已请求进入对话但凭据尚未就绪：URL 一到即跳全屏对话页
  var pendingOpen by remember { mutableStateOf(false) }
  var openFailed by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { viewModel.refreshEngineState() }

  fun openHarness(url: String) {
    context.startActivity(
      Intent(context, HarnessActivity::class.java).putExtra(HarnessActivity.EXTRA_URL, url),
    )
  }

  // 凭据就绪 → 自动打开全屏对话页
  LaunchedEffect(webUrl, pendingOpen) {
    if (pendingOpen && webUrl != null) {
      pendingOpen = false
      launching = false
      openHarness(webUrl!!)
    }
  }

  // 兜底轮询：请求已挂起但 URL 未就绪（如 APP 重启后附着到仍在运行的引擎）→
  // 每 2 秒从引擎日志尾部解析一次（解析结果经活性验证，过期 token 不会被采用）。
  // 90s 仍拿不到有效凭据（引擎已死/反复重启）→ 收场并提示，避免无限等待。
  LaunchedEffect(pendingOpen) {
    if (!pendingOpen) return@LaunchedEffect
    var waited = 0
    while (pendingOpen && webUrl == null && waited < 90_000) {
      viewModel.resolveWebUrlFromLog()
      delay(2_000)
      waited += 2_000
    }
    if (pendingOpen && webUrl == null) {
      pendingOpen = false
      launching = false
      openFailed = true
      viewModel.refreshEngineState()
    }
  }

  // 引擎真正停下（停止是异步的，进程树退出可达数秒）后才解除按钮禁用态
  LaunchedEffect(running) {
    if (!running) stopping = false
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 22.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    ScreenHeading("Harness 对话", title(running, launching, stopping))

    // launch-panel：52dp 图标块 + 文案 + 分隔线 + 按钮区
    // （.launch-panel：135° blue-soft→surface 对角渐变 + 1px 淡蓝外环）
    val panelShape = RoundedCornerShape(DshmRadius.card.dp)
    val panelRing = LocalDshmPalette.current.blue.copy(alpha = 0.07f)
    Box(
      Modifier
        .fillMaxWidth()
        .border(1.dp, panelRing, panelShape)
        .padding(1.dp),
    ) {
      Column(
        Modifier
          .fillMaxWidth()
          .background(
            androidx.compose.ui.graphics.Brush.linearGradient(
              colorStops = arrayOf(
                0f to androidx.compose.ui.graphics.lerp(
                  LocalDshmPalette.current.blueSoft,
                  MaterialTheme.colorScheme.surface,
                  0.35f,
                ),
                0.42f to MaterialTheme.colorScheme.surface,
              ),
            ),
            panelShape,
          )
          .border(1.dp, LocalDshmPalette.current.line, panelShape)
          .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
      ) {
        // launch-icon：52dp 实底块（--icon-solid-bg），内嵌 38dp 吉祥物
        Box(
          Modifier
            .size(52.dp)
            .background(
              LocalDshmPalette.current.iconSolidBg,
              RoundedCornerShape(DshmRadius.inner.dp),
            ),
          contentAlignment = Alignment.Center,
        ) {
          if (launching) {
            CircularProgressIndicator(
              modifier = Modifier.size(26.dp),
              strokeWidth = 2.5.dp,
              color = LocalDshmPalette.current.blue,
            )
          } else {
            AppMarkImage(38)
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
          Text(
            headline(running, launching, stopping),
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 25.sp,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            detail(running, launching, stopping),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = LocalDshmPalette.current.muted,
          )
        }
      }

      // launch-actions：上缘分隔线 + 主/次按钮（.launch-actions）
      Column(
        Modifier
          .fillMaxWidth()
          .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        Box(
          Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalDshmPalette.current.line),
        )
        Spacer(Modifier.height(7.dp))
        PrimaryButton(
          text = when {
            launching -> "正在打开…"
            running -> "进入对话"
            else -> "打开对话"
          },
          onClick = {
            openFailed = false
            pendingOpen = true
            if (running) {
              // 引擎已在运行：直接从日志尾部解析 token（多数情况下瞬时完成）
              viewModel.resolveWebUrlFromLog()
            } else {
              launching = true
              viewModel.startEngine { ok ->
                launching = false
                if (!ok) {
                  pendingOpen = false
                  openFailed = true
                }
              }
            }
          },
          Modifier.fillMaxWidth(),
          enabled = !launching && !stopping,
          icon = if (launching) null else Icons.Filled.PlayArrow,
        )
        if (running) {
          SecondaryButton(
            "停止",
            {
              stopping = true
              pendingOpen = false
              viewModel.stopEngine() // 异步：完成后 running→false，LaunchedEffect 解除 stopping
            },
            Modifier.fillMaxWidth(),
            enabled = !launching && !stopping,
            icon = Icons.Outlined.Square,
          )
        }
      }
      }
    }

    // 凭据等待提示（请求已挂起、URL 未到）
    if (pendingOpen && webUrl == null) {
      InlineAlert(
        title = "正在获取访问凭据",
        message = "正在等待 dsh web 打印本次会话的带 token 地址；" +
          "首次启动约需 10-30 秒，就绪后将自动进入对话。",
      )
    }

    if (openFailed) {
      InlineAlert(
        title = "无法启动对话",
        message = "引擎启动失败或运行组件缺失，请到「设置 → 运行环境」查看运行时状态，安装日志见安装引导页。",
        danger = true,
      )
    }

    // 保活提示（inline-alert warning）
    InlineAlert(
      title = "请勿从最近任务划掉应用",
      message = "启动对话后将开启前台服务保活；长时间任务期间请保持应用在后台运行，" +
        "进程被系统回收时看门狗会自动重启引擎。",
    )

    Spacer(Modifier.height(8.dp))
  }

  // 周期探测（IO 线程）：引擎在后台被看门狗拉起/回收后，门屏按钮自动跟随
  LaunchedEffect(Unit) {
    while (true) {
      delay(3_000)
      viewModel.refreshEngineState()
    }
  }
}

/** 门屏 h1（.screen-heading h1）：三态文案与参考项目 conversation-gate 一致。 */
private fun title(running: Boolean, launching: Boolean, stopping: Boolean) = when {
  launching -> "正在进入对话"
  stopping -> "正在停止运行环境"
  running -> "对话已就绪"
  else -> "对话已就绪"
}

/** launch-copy h2：状态句。 */
private fun headline(running: Boolean, launching: Boolean, stopping: Boolean) = when {
  launching -> "正在打开 Harness 对话"
  stopping -> "正在停止本机服务"
  running -> "点击「进入对话」继续"
  else -> "点击「打开对话」开始"
}

/** launch-copy p：说明句。 */
private fun detail(running: Boolean, launching: Boolean, stopping: Boolean) = when {
  launching -> "正在启动本机 proot 容器与 dsh web，首次启动约需 10-30 秒…"
  stopping -> "停止完成后即可重新打开对话。"
  running -> "本机服务正在运行，打开后即可继续对话。"
  else -> "打开对话时才会启动本机服务；容器与运行环境已就绪。"
}
