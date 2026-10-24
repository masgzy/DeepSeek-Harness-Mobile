package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.SecondaryButton
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import io.github.masgzy.dshm.util.AppLog
import kotlinx.coroutines.delay

/**
 * 日志页：终端深色面板 + 三类日志切换（引擎 / 应用 / 崩溃），
 * 形态对齐 dphmoblie/deepseek-harness-android 的日志面板（HarnessLogPanel / DiagnosticLogPanel）：
 * 等宽小字、深底、钉在底部、一键复制。
 */
@Composable
fun LogsScreen(viewModel: AppViewModel) {
  val p = LocalDshmPalette.current
  var tab by remember { mutableIntStateOf(0) }
  var lines by remember { mutableStateOf<List<String>>(emptyList()) }
  var copied by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val scroll = rememberScrollState()

  // 按标签加载日志（IO 轮询，5s 刷新保持追尾）
  LaunchedEffect(tab) {
    while (true) {
      lines = when (tab) {
        0 -> AppLog.tail(viewModel.engineManager.engineLog, 400)
        1 -> AppLog.recent(400)
        else -> AppLog.tail(AppLog.crashLogFile, 400)
      }
      delay(5_000)
    }
  }
  // 新日志钉在底部
  LaunchedEffect(lines.size) {
    if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // 切换器
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      val tabs = listOf("引擎", "应用", "崩溃")
      tabs.forEachIndexed { index, label ->
        SegmentedButton(
          selected = tab == index,
          onClick = { tab = index },
          shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
          label = { Text(label, fontSize = 13.sp) },
        )
      }
    }

    // 终端面板
    Column(
      Modifier
        .fillMaxWidth()
        .weight(1f)
        .background(p.terminalBg, RoundedCornerShape(DshmRadius.control.dp))
        .border(1.dp, p.terminalLine, RoundedCornerShape(DshmRadius.control.dp))
        .padding(12.dp),
    ) {
      if (lines.isEmpty()) {
        Text(
          when (tab) {
            0 -> "暂无引擎日志。启动引擎后，dsh web 的输出（含带 token 的访问地址）会实时写入这里。"
            1 -> "暂无应用日志。"
            else -> "暂无崩溃记录（这是好消息）。"
          },
          fontSize = 12.sp,
          lineHeight = 18.sp,
          fontFamily = FontFamily.Monospace,
          color = androidx.compose.ui.graphics.Color(0xFF8B98A7),
        )
      } else {
        Column(
          Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        ) {
          lines.forEach { line ->
            Text(
              line,
              fontSize = 10.5.sp,
              lineHeight = 15.sp,
              fontFamily = FontFamily.Monospace,
              color = androidx.compose.ui.graphics.Color(0xFFC6CED8),
            )
          }
        }
      }
    }

    // 操作行
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      SecondaryButton(
        if (copied) "已复制" else "复制全部",
        {
          clipboard.setText(AnnotatedString(lines.joinToString("\n")))
          copied = true
        },
        Modifier.weight(1f),
        enabled = lines.isNotEmpty(),
      )
      SecondaryButton(
        "清屏",
        {
          when (tab) {
            0 -> viewModel.engineManager.engineLog.takeIf { it.isFile }?.writeText("")
            1 -> AppLog.logFile.takeIf { it.isFile }?.writeText("")
            else -> AppLog.crashLogFile.takeIf { it.isFile }?.writeText("")
          }
          lines = emptyList()
        },
        Modifier.weight(1f),
        enabled = lines.isNotEmpty(),
      )
    }
    Spacer(Modifier.height(8.dp))
  }
}
