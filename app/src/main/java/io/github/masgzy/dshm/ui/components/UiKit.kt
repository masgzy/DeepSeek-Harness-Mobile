package io.github.masgzy.dshm.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.masgzy.dshm.R
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/**
 * 外壳通用组件库：逐项复刻 dphmoblie/deepseek-harness-android 的 styles.css——
 * brand / eyebrow / screen-heading / button 系列 / inline-alert / progress /
 * install-steps / segmented / capsule / detail-row / settings-section /
 * management-row / dialog（底部弹卡）/ field。
 * 数值（圆角三档 12/16/999 + 内层 8、按钮 44 高、字重 650、进度条 8px、
 * 步骤圆 27px、行高 68、弹卡 23 内边距）与 CSS 一致。
 */

private fun controlShape() = RoundedCornerShape(DshmRadius.control.dp)
private fun cardShape() = RoundedCornerShape(DshmRadius.card.dp)
private fun innerShape() = RoundedCornerShape(DshmRadius.inner.dp)
private fun chipShape() = RoundedCornerShape(DshmRadius.chip.dp)

/** 应用吉祥物图（dphmoblie 同款 app-mark）：按 [sizeDp] 等比渲染。 */
@Composable
fun AppMarkImage(sizeDp: Int, modifier: Modifier = Modifier) {
  Image(
    painter = painterResource(R.drawable.app_mark),
    contentDescription = null,
    modifier = modifier.size(sizeDp.dp),
  )
}

/**
 * 品牌 Header（.brand）：渐变圆角图标块 + `deepseek` + `HARNESS` 徽章。
 * .brand-symbol：32/8px，linear-gradient(145deg, brand-bg, blue 45%) + 蓝晕阴影。
 */
@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
  val p = LocalDshmPalette.current
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .shadow(4.dp, RoundedCornerShape(DshmRadius.inner.dp), spotColor = p.blue)
        .clip(RoundedCornerShape(DshmRadius.inner.dp))
        .background(
          Brush.linearGradient(
            0f to p.brandBg,
            1f to lerpColor(p.brandBg, p.blue, 0.45f),
          ),
        ),
      contentAlignment = Alignment.Center,
    ) {
      AppMarkImage(26)
    }
    Spacer(Modifier.width(8.dp))
    Text(
      "deepseek",
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      color = p.ink,
    )
    Spacer(Modifier.width(7.dp))
    // brand-badge：小号白字品牌底角标
    Text(
      "HARNESS",
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
      lineHeight = 9.sp,
      color = p.onSolid,
      modifier = Modifier
        .background(p.brandBg, RoundedCornerShape(4.dp))
        .padding(horizontal = 5.dp, vertical = 3.dp),
    )
  }
}

/** sRGB 线性插值（styles.css color-mix 的近似实现，用于渐变端点）。 */
private fun lerpColor(a: Color, b: Color, fraction: Float): Color = Color(
  red = a.red + (b.red - a.red) * fraction,
  green = a.green + (b.green - a.green) * fraction,
  blue = a.blue + (b.blue - a.blue) * fraction,
  alpha = a.alpha + (b.alpha - a.alpha) * fraction,
)

/** eyebrow：蓝色大写小标签（.eyebrow，11px / 730）。 */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
  Text(
    text.uppercase(),
    color = LocalDshmPalette.current.blue,
    fontSize = 11.sp,
    fontWeight = FontWeight(730),
    letterSpacing = 0.5.sp,
    modifier = modifier,
  )
}

/** 屏标题组：eyebrow + 大标题（.screen-heading，h1 27px / 720 / -0.025em）。 */
@Composable
fun ScreenHeading(eyebrow: String, title: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Eyebrow(eyebrow)
    Text(
      title,
      fontSize = 27.sp,
      fontWeight = FontWeight(720),
      lineHeight = 32.sp,
      letterSpacing = (-0.68).sp,
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
}

/**
 * 按钮基座（.button：44 高 / 12 圆角 / 14px·650，按下颜色加深，禁用整体 52% 透明）。
 * 参考项目按钮无 M3 阴影体系，这里用扁平底色 + 按压反馈复刻。
 */
@Composable
private fun BaseButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
  container: Color,
  content: Color,
  border: Color? = null,
  pressedContainer: Color? = null,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val shape = controlShape()
  val bg = when {
    !enabled -> container
    pressed && pressedContainer != null -> pressedContainer
    else -> container
  }
  Row(
    modifier = modifier
      .alpha(if (enabled) 1f else 0.52f)
      .height(44.dp)
      .clip(shape)
      .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
      .background(bg, shape)
      .clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
      )
      .padding(horizontal = 15.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, null, modifier = Modifier.size(18.dp), tint = content)
    }
    Text(
      text,
      fontSize = 14.sp,
      fontWeight = FontWeight(650),
      color = content,
      maxLines = 1,
    )
  }
}

/** 主按钮（.button-primary：accent 底 + on-accent 字，按压加深）。 */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  BaseButton(
    text, onClick, modifier, enabled, icon,
    container = p.blue,
    content = p.onAccent,
    pressedContainer = p.blueHover,
  )
}

/** 次按钮（.button-secondary：表面底 + blue-line 描边 + accent 蓝字）。 */
@Composable
fun SecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  BaseButton(
    text, onClick, modifier, enabled, icon,
    container = MaterialTheme.colorScheme.surface,
    content = p.blue,
    border = p.blueLine,
    pressedContainer = p.blueSoft,
  )
}

/** 危险实心（.button-danger：红底白字）。 */
@Composable
fun DangerButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  BaseButton(
    text, onClick, modifier, enabled, icon,
    container = p.red,
    content = Color.White,
    pressedContainer = p.red.copy(alpha = 0.85f),
  )
}

/** 危险静默（.button-danger-quiet：红软底 + danger-line 描边 + 红字）。 */
@Composable
fun DangerQuietButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
) {
  val p = LocalDshmPalette.current
  BaseButton(
    text, onClick, modifier, enabled, icon,
    container = p.redSoft,
    content = p.red,
    border = p.redLine,
    pressedContainer = p.redSoft.copy(alpha = 0.7f),
  )
}

/** 内联告警条（.inline-alert）：琥珀警告 / 红色危险，语义描边。 */
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
      .background(bg, controlShape())
      .border(1.dp, line, controlShape())
      .padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      Modifier
        .padding(top = 5.dp)
        .size(8.dp)
        .background(accent, CircleShape),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        title,
        color = accent,
        fontSize = 13.sp,
        fontWeight = FontWeight(650),
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
 * 进度块（.download-progress）：阶段文案 + accent 百分比 + 8px 胶囊进度条 + 明细行。
 * progress == null 表示不确定进度（45% 宽滑块 translateX(-100%→225%) 1.25s 往复）。
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
      Text(phase, fontSize = 13.sp, fontWeight = FontWeight(650), color = MaterialTheme.colorScheme.onSurface)
      Text(
        if (progress != null) "${(progress * 100).toInt()}%" else "处理中",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = p.blue,
        style = TextStyle(fontFeatureSettings = "tnum"),
      )
    }
    BoxWithConstraints(
      Modifier
        .fillMaxWidth()
        .height(8.dp)
        .clip(chipShape())
        .background(p.track),
    ) {
      if (progress != null) {
        Box(
          Modifier
            .fillMaxWidth(progress.coerceIn(0.02f, 1f))
            .fillMaxHeight()
            .clip(chipShape())
            .background(p.blue),
        )
      } else {
        val transition = rememberInfiniteTransition(label = "progress-indeterminate")
        val x by transition.animateFloat(
          initialValue = -1f,
          targetValue = 2.25f,
          animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart),
          label = "progress-indeterminate-x",
        )
        val barWidth = maxWidth * 0.45f
        Box(
          Modifier
            .offset(x = barWidth * x)
            .width(barWidth)
            .fillMaxHeight()
            .clip(chipShape())
            .background(p.blue),
        )
      }
    }
    if (detail != null) {
      Text(
        detail,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * 安装步骤指示器（.install-steps）：27px 步骤圆 + 1px 连接线（完成段绿色轨道）、
 * active=蓝实心白字、complete=绿软底打勾、标签 11px。
 */
@Composable
fun InstallSteps(
  steps: List<String>,
  currentStep: Int,
  allDone: Boolean,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    steps.forEachIndexed { index, label ->
      val complete = allDone || index < currentStep
      val active = index == currentStep && !allDone
      if (index > 0) {
        // 连接线：上一节点完成 → 绿色轨道，否则普通线
        Box(
          Modifier
            .padding(top = 13.dp)
            .weight(1f)
            .height(1.dp)
            .background(if (complete || allDone) p.greenTrack else p.line),
        )
      }
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Box(
          Modifier
            .size(27.dp)
            .clip(CircleShape)
            .background(
              when {
                complete -> p.greenSoft
                active -> p.blue
                else -> p.track
              },
            )
            .border(
              1.dp,
              when {
                complete -> p.greenLine
                active -> p.blue
                else -> p.line
              },
              CircleShape,
            ),
          contentAlignment = Alignment.Center,
        ) {
          if (complete) {
            Icon(Icons.Rounded.Check, null, tint = p.green, modifier = Modifier.size(15.dp))
          } else {
            Text(
              "${index + 1}",
              color = if (active) p.onAccent else p.muted,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
        Text(
          label,
          color = when {
            complete -> p.green
            active -> p.blue
            else -> p.muted
          },
          fontSize = 11.sp,
          fontWeight = if (active) FontWeight(650) else FontWeight.Medium,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/** 环境详情行（.detail-row：min-height 52，左标签右值）。 */
@Composable
fun DetailRow(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
  val p = LocalDshmPalette.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 52.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, null, tint = p.muted, modifier = Modifier.size(17.dp))
      Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
      value,
      fontSize = 13.5.sp,
      fontWeight = FontWeight(650),
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.End,
    )
  }
}

/**
 * 设置/信息分组卡片（.settings-section）：16 圆角卡 + 外圈 1px 淡蓝环
 * （box-shadow 双描边：var(--shadow), 0 0 0 1px blue 7%）。
 */
@Composable
fun SectionCard(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  val p = LocalDshmPalette.current
  Box(
    Modifier
      .fillMaxWidth()
      .border(1.dp, p.blue.copy(alpha = 0.07f), cardShape())
      .padding(1.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface, cardShape())
        .border(1.dp, p.line, cardShape())
        .padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      content()
    }
  }
}

/**
 * iOS 风格分段控件（.segmented）：surface-muted 底 12 圆角容器内 3px padding，
 * 选中块 = 白面 8 圆角 + 小阴影 + ink 字，未选中 muted。
 */
@Composable
fun Segmented(
  options: List<String>,
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Row(
    modifier
      .fillMaxWidth()
      .background(p.surfaceMuted, controlShape())
      .border(1.dp, p.line, controlShape())
      .padding(3.dp),
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    options.forEachIndexed { index, label ->
      val sel = index == selected
      Row(
        modifier = Modifier
          .weight(1f)
          .height(36.dp)
          .then(if (sel) Modifier.shadow(2.dp, innerShape()) else Modifier)
          .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent, innerShape())
          .clip(innerShape())
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { onSelect(index) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          label,
          fontSize = 13.sp,
          fontWeight = FontWeight(650),
          color = if (sel) MaterialTheme.colorScheme.onSurface else p.muted,
        )
      }
    }
  }
}

/** 分类胶囊选择器（.settings-categories：999 胶囊，选中 accent 软底 + accent 描边）。 */
@Composable
fun CapsuleSelector(
  options: List<String>,
  selected: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEachIndexed { index, label ->
      val sel = index == selected
      val shape = chipShape()
      Row(
        modifier = Modifier
          .defaultMinSize(minHeight = 40.dp)
          .clip(shape)
          .background(if (sel) p.blueSoft else p.surfaceMuted, shape)
          .border(1.dp, if (sel) p.blue else Color.Transparent, shape)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { onSelect(index) }
          .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          label,
          fontSize = 13.sp,
          fontWeight = FontWeight(650),
          color = if (sel) p.blue else p.muted,
        )
      }
    }
  }
}

/**
 * 表单输入（.field）：12px 650 field-label 标签 + sunken 底输入框，
 * focus 时描边变 accent、底变 surface。
 */
@Composable
fun DshmInputField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  hint: String? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
) {
  val p = LocalDshmPalette.current
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight(650), color = p.fieldLabel)
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      singleLine = true,
      textStyle = TextStyle(
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
      ),
      keyboardOptions = keyboardOptions,
      visualTransformation = visualTransformation,
      interactionSource = interaction,
      modifier = Modifier
        .fillMaxWidth()
        .clip(controlShape())
        .background(if (focused) MaterialTheme.colorScheme.surface else p.surfaceMuted, controlShape())
        .border(1.dp, if (focused) p.blue else p.lineStrong, controlShape())
        .padding(horizontal = 12.dp, vertical = 13.dp),
      cursorBrush = SolidColor(p.blue),
      decorationBox = { inner ->
        if (value.isEmpty() && hint != null) {
          Text(hint, fontSize = 15.sp, color = p.muted)
        }
        inner()
      },
    )
  }
}

/**
 * 底部弹卡（.dialog 手机形态）：贴底 16 圆角卡、图标块 44/8px、标题 19/700、
 * 正文 13 muted、动作行右对齐 + 上分隔线；点外部关闭。
 */
@Composable
fun DshmDialog(
  onDismiss: () -> Unit,
  title: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
  message: String? = null,
  icon: ImageVector? = null,
  danger: Boolean = false,
  confirmEnabled: Boolean = true,
  dismissLabel: String? = "取消",
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
  val p = LocalDshmPalette.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onDismiss,
        ),
      contentAlignment = Alignment.BottomCenter,
    ) {
      Column(
        modifier
          .padding(14.dp)
          .navigationBarsPadding()
          .fillMaxWidth()
          .shadow(10.dp, cardShape())
          .background(MaterialTheme.colorScheme.surface, cardShape())
          .border(1.dp, p.line, cardShape())
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          )
          .padding(23.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          if (icon != null) {
            Box(
              Modifier
                .size(44.dp)
                .background(if (danger) p.redSoft else p.amberSoft, innerShape()),
              contentAlignment = Alignment.Center,
            ) {
              Icon(icon, null, tint = if (danger) p.red else p.amber, modifier = Modifier.size(22.dp))
            }
          }
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              title,
              fontSize = 19.sp,
              fontWeight = FontWeight.Bold,
              lineHeight = 24.sp,
              color = MaterialTheme.colorScheme.onSurface,
            )
            if (message != null) {
              Text(
                message,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        content()
        Row(
          Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .drawBehind {
              drawLine(
                color = p.lineFaint,
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
              )
            }
            .padding(top = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
          if (dismissLabel != null) {
            BaseButton(
              dismissLabel, onDismiss, Modifier.weight(1f),
              container = MaterialTheme.colorScheme.surface,
              content = p.muted,
              border = p.lineStrong,
            )
          }
          if (danger) {
            DangerButton(confirmLabel, onConfirm, Modifier.weight(1f), enabled = confirmEnabled)
          } else {
            PrimaryButton(confirmLabel, onConfirm, Modifier.weight(1f), enabled = confirmEnabled)
          }
        }
      }
    }
  }
}

/**
 * 设置入口行（.management-row）：42/8px 图标容器（设置类=蓝软底蓝图标、
 * 服务类=深底白图标）+ 标题 14/650 + 说明 12 muted + 右值/箭头，min-height 68。
 */
@Composable
fun SettingRow(
  icon: ImageVector,
  title: String,
  hint: String,
  modifier: Modifier = Modifier,
  value: String? = null,
  onClick: (() -> Unit)? = null,
  iconSolid: Boolean = false,
) {
  val p = LocalDshmPalette.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 68.dp)
      .then(
        if (onClick != null) {
          Modifier.clip(innerShape()).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
          )
        } else {
          Modifier
        },
      )
      .padding(vertical = 13.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .size(42.dp)
        .background(if (iconSolid) p.iconSolidBg else p.blueSoft, innerShape()),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        icon, null,
        tint = if (iconSolid) p.onSolid else p.blue,
        modifier = Modifier.size(21.dp),
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight(650),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        hint,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (value != null) {
      Text(
        value,
        fontSize = 13.5.sp,
        fontWeight = FontWeight(650),
        color = p.blue,
      )
    }
    if (onClick != null) {
      Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        null,
        tint = p.muted,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/** 1px 行分隔线（行间 --line-faint）。 */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(1.dp).background(LocalDshmPalette.current.lineFaint))
}

/** 浮动提示条（.toast）：深底白字、12 圆角描边，两种主题下统一深色。 */
@Composable
fun ToastBar(
  message: String,
  modifier: Modifier = Modifier,
) {
  val p = LocalDshmPalette.current
  Surface(
    modifier = modifier,
    color = p.toastBg,
    shape = controlShape(),
    shadowElevation = 8.dp,
  ) {
    Row(
      Modifier
        .border(1.dp, p.toastLine, controlShape())
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
