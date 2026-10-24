package io.github.masgzy.dshm.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/** 状态徽章色调（对齐 dphmoblie/deepseek-harness-android 的 phase 色系）。 */
enum class StatusTone { NEUTRAL, BLUE, AMBER, GREEN, RED }

/** 一个运行阶段的展示元数据：文案 + 色调（对应参考项目 PHASE_META）。 */
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

/** 按安装步骤文案推导阶段徽章（环境页进度展示用）。 */
fun phaseForStep(step: String): PhaseStyle = when {
  step.contains("下载") -> Phases.DOWNLOADING
  step.contains("校验") -> Phases.VERIFYING
  step.contains("解压") || step.contains("安装") || step.contains("启用") -> Phases.EXTRACTING
  step.contains("完成") || step.contains("就绪") -> Phases.READY
  step.contains("失败") -> Phases.ERROR
  step.isBlank() -> Phases.NOT_INSTALLED
  else -> Phases.PREPARING
}

/**
 * 运行阶段徽章：胶囊 chip + 7dp 圆点。
 * 配色逐项对应参考项目 .phase-badge / .phase-{tone}：
 * soft 底 + 语义色描边 + 语义色文字；蓝/绿（进行中态）圆点呼吸动画。
 */
@Composable
fun StatusBadge(style: PhaseStyle, modifier: Modifier = Modifier) {
  val p = LocalDshmPalette.current
  val neutralText = MaterialTheme.colorScheme.onSurfaceVariant
  val neutralBg = MaterialTheme.colorScheme.surface

  val (text, dot, bg, line) = when (style.tone) {
    StatusTone.NEUTRAL -> Tuple4(neutralText, p.muted, neutralBg, p.line)
    StatusTone.BLUE -> Tuple4(p.blue, p.blue, p.blueSoft, p.blueLine)
    StatusTone.AMBER -> Tuple4(p.amber, p.amber, p.amberSoft, p.amberLine)
    StatusTone.GREEN -> Tuple4(p.green, p.green, p.greenSoft, p.greenLine)
    StatusTone.RED -> Tuple4(p.red, p.red, p.redSoft, p.redLine)
  }

  // 进行中（蓝=读取/下载，绿=运行中）圆点 1.7s 呼吸；红/琥珀/中性静止。
  val pulsing = style.tone == StatusTone.BLUE || style.tone == StatusTone.GREEN
  val dotAlpha: Float = if (pulsing) {
    val transition = rememberInfiniteTransition(label = "phase-dot")
    val a by transition.animateFloat(
      initialValue = 0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
      label = "phase-dot-alpha",
    )
    a
  } else {
    1f
  }

  Row(
    modifier = modifier
      .background(bg, RoundedCornerShape(999.dp))
      .border(1.dp, line, RoundedCornerShape(999.dp))
      .padding(horizontal = 10.dp, vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    androidx.compose.foundation.layout.Box(
      modifier = Modifier
        .size(7.dp)
        .alpha(dotAlpha)
        .background(dot, CircleShape),
    )
    Text(
      style.label,
      color = if (style.tone == StatusTone.NEUTRAL) neutralText else text,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}

private data class Tuple4(val a: Color, val b: Color, val c: Color, val d: Color)
