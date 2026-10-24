package io.github.masgzy.dshm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/**
 * 外壳通用组件库：命名与形态对齐 dphmoblie/deepseek-harness-android 的
 * styles.css（eyebrow / button / inline-alert / progress / install-steps /
 * detail-list / settings-section）。
 */

/** eyebrow：蓝色小型大写标签（参考项目 .eyebrow）。 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
  Text(
    text.uppercase(),
    color = LocalDshmPalette.current.blue,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.4.sp,
    modifier = modifier,
  )
}

/** 屏标题组：eyebrow + 大标题（.screen-heading）。 */
@Composable
fun ScreenHeading(eyebrow: String, title: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Eyebrow(eyebrow)
    Text(
      title,
      fontSize = 26.sp,
      fontWeight = FontWeight.Bold,
      lineHeight = 32.sp,
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
}

/** 主按钮：语义蓝实底（.button-primary）。 */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(DshmRadius.control.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = p.blue,
      contentColor = Color.White,
      disabledContainerColor = p.surfaceMuted,
      disabledContentColor = p.muted,
    ),
    contentPadding = ButtonDefaults.ContentPadding,
    modifier = modifier.height(44.dp),
  ) {
    if (icon != null) {
      Icon(icon, null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
    }
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** 次按钮：表面底 + 强描边（.button-secondary）。 */
@Composable
fun SecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(DshmRadius.control.dp),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      disabledContainerColor = MaterialTheme.colorScheme.surface,
      disabledContentColor = p.muted,
    ),
    border = androidx.compose.foundation.BorderStroke(1.dp, p.lineStrong),
    contentPadding = ButtonDefaults.ContentPadding,
    modifier = modifier.height(44.dp),
  ) {
    if (icon != null) {
      Icon(icon, null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
    }
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
  }
}

/** 危险静默按钮：红软底 + 红字（.button-danger-quiet）。 */
@Composable
fun DangerQuietButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(DshmRadius.control.dp),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = p.redSoft,
      contentColor = p.red,
      disabledContainerColor = p.redSoft,
      disabledContentColor = p.muted,
    ),
    border = androidx.compose.foundation.BorderStroke(1.dp, p.redLine),
    contentPadding = ButtonDefaults.ContentPadding,
    modifier = modifier.height(44.dp),
  ) {
    if (icon != null) {
      Icon(icon, null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
    }
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
  }
}

private enum class AlertTone { WARNING, DANGER }

/** 内联告警条：琥珀警告 / 红色危险（.inline-alert）。 */
@Composable
fun InlineAlert(
  title: String,
  message: String,
  modifier: Modifier = Modifier,
  danger: Boolean = false,
) {
  val p = LocalDshmPalette.current
  val accent = if (danger) p.red else p.amber
  val bg = if (danger) p.redSoft else p.amberSoft
  val line = if (danger) p.redLine else p.amberLine
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(bg, RoundedCornerShape(DshmRadius.control.dp))
      .border(1.dp, line, RoundedCornerShape(DshmRadius.control.dp))
      .padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      Modifier
        .padding(top = 2.dp)
        .size(8.dp)
        .background(accent, CircleShape),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        title,
        color = accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        message,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
      )
    }
  }
}

/**
 * 进度块（.download-progress）：阶段文案 + 百分比 + 进度条 + 遥测细节行。
 * progress == null 表示不确定进度（indeterminate）。
 */
@Composable
fun ProgressBlock(
  phase: String,
  progress: Float?,
  detail: String? = null,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(phase, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
      Text(
        if (progress != null) "${(progress * 100).toInt()}%" else "处理中",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = p.blue,
      )
    }
    if (progress != null) {
      LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp),
        trackColor = p.surfaceMuted,
        color = p.blue,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
      )
    } else {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp),
        trackColor = p.surfaceMuted,
        color = p.blue,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
      )
    }
    if (detail != null) {
      Text(
        detail,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 安装四步指示器（.install-steps）：完成打勾、当前高亮、未到灰。 */
@Composable
fun InstallSteps(
  steps: List<String>,
  currentStep: Int,
  allDone: Boolean,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    steps.forEachIndexed { index, label ->
      val complete = allDone || index < currentStep
      val active = index == currentStep && !allDone
      val color = when {
        complete -> p.green
        active -> p.blue
        else -> p.muted
      }
      Column(
        Modifier
          .weight(1f)
          .background(
            if (complete) p.greenSoft else if (active) p.blueSoft else p.surfaceMuted,
            RoundedCornerShape(DshmRadius.inner.dp),
          )
          .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        if (complete) {
          Icon(
            Icons.Rounded.CheckCircle,
            null,
            tint = color,
            modifier = Modifier.size(17.dp),
          )
        } else {
          Text(
            "${index + 1}",
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
          )
        }
        Text(
          label,
          color = color,
          fontSize = 11.sp,
          fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
      }
    }
  }
}

/** 环境详情行（.detail-row）：图标 + 标签在左，值在右。 */
@Composable
fun DetailRow(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
  val p = LocalDshmPalette.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 9.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, null, tint = p.muted, modifier = Modifier.size(18.dp))
      Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(
      value,
      fontSize = 13.5.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/** 设置/信息分组卡片（.settings-section）：标题 + 内容。 */
@Composable
fun SectionCard(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(DshmRadius.card.dp))
      .border(1.dp, LocalDshmPalette.current.line, RoundedCornerShape(DshmRadius.card.dp))
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      title,
      fontSize = 15.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    content()
  }
}

/** 浮动提示条（.toast）：深底白字、圆角描边，两种主题下统一深色。 */
@Composable
fun ToastBar(
  message: String,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Surface(
    modifier = modifier,
    color = p.toastBg,
    shape = RoundedCornerShape(DshmRadius.control.dp),
    shadowElevation = 8.dp,
  ) {
    Row(
      Modifier
        .border(1.dp, p.toastLine, RoundedCornerShape(DshmRadius.control.dp))
        .padding(horizontal = 14.dp, vertical = 11.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        message,
        color = p.toastText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}
