package io.github.masgzy.dshm.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 状态徽章色调（对齐 dphmoblie/deepseek-harness-android 的 phase 色系）。 */
enum class StatusTone { NEUTRAL, BLUE, AMBER, GREEN, RED }

/** 一个运行阶段的展示元数据：文案 + 色调。 */
data class PhaseStyle(val label: String, val tone: StatusTone)

/** 运行阶段集合：命名与参考项目的 PHASE_META 一一对应。 */
object Phases {
  val NOT_INSTALLED = PhaseStyle("未安装", StatusTone.NEUTRAL)
  val PREPARING = PhaseStyle("读取内置环境", StatusTone.BLUE)
  val DOWNLOADING = PhaseStyle("下载中", StatusTone.BLUE)
  val VERIFYING = PhaseStyle("正在校验", StatusTone.AMBER)
  val EXTRACTING = PhaseStyle("正在安装", StatusTone.AMBER)
  val READY = PhaseStyle("已就绪", StatusTone.GREEN)
  val RUNNING = PhaseStyle("运行中", StatusTone.GREEN)
  val STOPPING = PhaseStyle("正在停止", StatusTone.AMBER)
  val ERROR = PhaseStyle("需要处理", StatusTone.RED)
}

/** 按安装步骤文案推导阶段徽章（Setup 页进度展示用）。 */
fun phaseForStep(step: String): PhaseStyle = when {
  step.contains("下载") -> Phases.DOWNLOADING
  step.contains("校验") -> Phases.VERIFYING
  step.contains("解压") || step.contains("安装") || step.contains("启用") -> Phases.EXTRACTING
  step.contains("完成") || step.contains("就绪") -> Phases.READY
  step.contains("失败") -> Phases.ERROR
  step.isBlank() -> Phases.NOT_INSTALLED
  else -> Phases.PREPARING
}

/** 圆角小徽章：中性/蓝/琥珀/绿/红 五档，深浅色各自可读。 */
@Composable
fun StatusBadge(style: PhaseStyle, modifier: Modifier = Modifier) {
  val dark = isSystemInDarkTheme()
  val (container, content) = when (style.tone) {
    StatusTone.NEUTRAL ->
      MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.BLUE ->
      MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    StatusTone.AMBER ->
      if (dark) Color(0xFF4D3A10) to Color(0xFFFFDF9E) else Color(0xFFFDE9C8) to Color(0xFF5C4300)
    StatusTone.GREEN ->
      if (dark) Color(0xFF1E3A25) to Color(0xFFA8E6B3) else Color(0xFFD7F0DC) to Color(0xFF114A1F)
    StatusTone.RED ->
      if (dark) Color(0xFF4A1A20) to Color(0xFFFFB4AD) else Color(0xFFFCD9DC) to Color(0xFF6E1220)
  }
  Surface(
    modifier = modifier,
    color = container,
    contentColor = content,
    shape = RoundedCornerShape(999.dp),
  ) {
    Text(
      style.label,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}
