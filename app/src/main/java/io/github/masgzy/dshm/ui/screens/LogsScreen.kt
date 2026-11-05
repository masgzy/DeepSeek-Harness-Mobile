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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.CapsuleSelector
import io.github.masgzy.dshm.ui.components.SecondaryButton
import io.github.masgzy.dshm.ui.components.Segmented
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import io.github.masgzy.dshm.util.AppLog
import kotlinx.coroutines.delay

/**
 * 日志页：终端深色面板 + 三类日志切换（引擎 / 应用 / 崩溃）+
 * 等级过滤（全部 / 警告 / 错误）+ 彩色渲染：
 *  - 错误红 / 警告琥珀 / 成功绿 / 调试灰，一眼分清日志性质（真机反馈：
 *    「日志全是一个颜色，出问题找不到重点」）；
 *  - 应用日志用写入时的结构化等级；引擎日志（原始 stdout）按关键词定级；
 *  - ANSI 转义序列在渲染前剥离，避免色码乱码。
 * 形态对齐 dphmoblie/deepseek-harness-android 的日志面板
 * （HarnessLogPanel / DiagnosticLogPanel）：等宽小字、深底、钉在底部、一键复制。
 */

/** 渲染行：剥离 ANSI 后的文本 + 展示色调。 */
private data class UiLine(val text: String, val tone: AppLog.Tone)

/** 各色调在深色终端底上的固定色（终端面板两种主题下都是深底）。 */
private fun toneColor(tone: AppLog.Tone): Color = when (tone) {
  AppLog.Tone.DEBUG -> Color(0xFF8B98A7)
  AppLog.Tone.INFO -> Color(0xFFC6CED8)
  AppLog.Tone.OK -> Color(0xFF8FD694)
  AppLog.Tone.WARN -> Color(0xFFE9BD6B)
  AppLog.Tone.ERROR -> Color(0xFFFF8585)
}

@Composable
fun LogsScreen(viewModel: AppViewModel) {
  val p = LocalDshmPalette.current
  var tab by remember { mutableIntStateOf(0) }
  // 级别过滤阈值：0=全部 1=警告+ 2=仅错误（过滤在渲染层，不改任何文件）
  var filterIdx by remember { mutableIntStateOf(0) }
  var lines by remember { mutableStateOf<List<UiLine>>(emptyList()) }
  var copied by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val scroll = rememberScrollState()

  // 按标签加载日志（IO 轮询刷新保持追尾；含分级与 ANSI 剥离）
  LaunchedEffect(tab) {
    while (true) {
      lines = when (tab) {
        0 -> AppLog.tail(viewModel.engineManager.engineLog, 400).map {
          UiLine(AppLog.stripAnsi(it), AppLog.toneOf(it))
        }
        1 -> AppLog.recentEntries(400).map {
          UiLine(AppLog.stripAnsi(it.line), AppLog.toneOf(it.line, it.level))
        }
        else -> AppLog.tail(AppLog.crashLogFile, 400).map {
          UiLine(AppLog.stripAnsi(it), AppLog.toneOf(it))
        }
      }
      delay(3_000)
    }
  }

  val filterMin = when (filterIdx) {
    1 -> AppLog.Level.WARN
    2 -> AppLog.Level.ERROR
    else -> AppLog.Level.DEBUG
  }
  // 「级别」按等级语义过滤（OK 折算为信息级）；引擎/应用/崩溃三页通用
  val visible = remember(lines, filterMin) {
    lines.filter { AppLog.levelOfTone(it.tone).ordinal >= filterMin.ordinal }
  }
  // 新日志钉在底部
  LaunchedEffect(visible.size) {
    if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // 切换器（.segmented：surface-muted 底 + 白色选中块 + 小阴影）
    Segmented(
      options = listOf("引擎", "应用", "崩溃"),
      selected = tab,
      onSelect = { tab = it },
    )

    // 级别过滤（彩色化配套：只看警告/错误时不再被信息流淹没）
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      Text(
        "级别",
        fontSize = 12.sp,
        color = p.muted,
      )
      CapsuleSelector(
        options = listOf("全部", "警告", "错误"),
        selected = filterIdx,
        onSelect = { filterIdx = it },
      )
      Spacer(Modifier.weight(1f))
      // 当前过滤命中的行数（用户能确认过滤器在工作）
      Text(
        "${visible.size}/${lines.size}",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = p.muted,
      )
    }

    // 终端面板
    Column(
      Modifier
        .fillMaxWidth()
        .weight(1f)
        .background(p.terminalBg, RoundedCornerShape(DshmRadius.card.dp))
        .border(1.dp, p.terminalLine, RoundedCornerShape(DshmRadius.card.dp))
        .padding(12.dp),
    ) {
      if (visible.isEmpty()) {
        Text(
          when {
            lines.isEmpty() && tab == 0 -> "暂无引擎日志。启动引擎后，dsh web 的输出（含带 token 的访问地址）会实时写入这里。"
            lines.isEmpty() && tab == 1 -> "暂无应用日志。"
            lines.isEmpty() -> "暂无崩溃记录（这是好消息）。"
            else -> "当前级别下没有日志（共 ${lines.size} 行被过滤）。切换「级别」查看更多。"
          },
          fontSize = 12.sp,
          lineHeight = 18.sp,
          fontFamily = FontFamily.Monospace,
          color = Color(0xFF8B98A7),
        )
      } else {
        Column(
          Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        ) {
          visible.forEach { line ->
            Text(
              line.text,
              fontSize = 10.5.sp,
              lineHeight = 15.sp,
              fontFamily = FontFamily.Monospace,
              color = toneColor(line.tone),
            )
          }
        }
      }
    }

    // 图例（彩色语义一目了然）
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      LegendDot("错误", toneColor(AppLog.Tone.ERROR))
      LegendDot("警告", toneColor(AppLog.Tone.WARN))
      LegendDot("成功", toneColor(AppLog.Tone.OK))
      LegendDot("信息", toneColor(AppLog.Tone.INFO))
    }

    // 操作行
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      SecondaryButton(
        if (copied) "已复制" else "复制全部",
        {
          clipboard.setText(AnnotatedString(visible.joinToString("\n") { it.text }))
          copied = true
        },
        Modifier.weight(1f),
        enabled = visible.isNotEmpty(),
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

/** 图例点：色块 + 标签。 */
@Composable
private fun LegendDot(label: String, color: Color) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    androidx.compose.foundation.layout.Box(
      Modifier
        .size(8.dp)
        .background(color, androidx.compose.foundation.shape.CircleShape),
    )
    Text(label, fontSize = 10.5.sp, color = LocalDshmPalette.current.muted)
  }
}
