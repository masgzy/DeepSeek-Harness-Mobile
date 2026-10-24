package io.github.masgzy.dshm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 外壳配色：整版照搬 dphmoblie/deepseek-harness-android 的 styles.css 令牌
 * （深浅两套固定色值，不使用 Material You 动态取色，保证与参考项目观感一致）。
 *
 * 浅色 :root        / 深色 :root[data-theme='dark']，逐项对应。
 */

/** 语义色全集：M3 colorScheme 装不下的扩展令牌都放这里。 */
data class DshmPalette(
  // 表面与文字
  val bg: Color,
  val surface: Color,
  val surfaceMuted: Color,
  val ink: Color,
  val muted: Color,
  // 线条
  val line: Color,
  val lineStrong: Color,
  val lineFaint: Color,
  // 主色（海洋蓝）
  val blue: Color,
  val blueSoft: Color,
  val blueLine: Color,
  // 语义色
  val green: Color,
  val greenSoft: Color,
  val greenLine: Color,
  val amber: Color,
  val amberSoft: Color,
  val amberLine: Color,
  val red: Color,
  val redSoft: Color,
  val redLine: Color,
  // 终端/日志面板：两种主题下都是深色
  val terminalBg: Color,
  val terminalLine: Color,
  // 浮动提示条：两种主题下都保持深底白字
  val toastBg: Color,
  val toastLine: Color,
  val toastText: Color,
)

private val LightPalette = DshmPalette(
  bg = Color(0xFFF3F5F8),
  surface = Color(0xFFFFFFFF),
  surfaceMuted = Color(0xFFEDF1F5),
  ink = Color(0xFF17202B),
  muted = Color(0xFF667181),
  line = Color(0xFFDCE2E9),
  lineStrong = Color(0xFFC8D0DA),
  lineFaint = Color(0xFFE3E6E9),
  blue = Color(0xFF315FCF),
  blueSoft = Color(0xFFE8EFFF),
  blueLine = Color(0xFFCBD9FB),
  green = Color(0xFF177A50),
  greenSoft = Color(0xFFE6F5EE),
  greenLine = Color(0xFFC6E5D7),
  amber = Color(0xFF9A6500),
  amberSoft = Color(0xFFFFF2D5),
  amberLine = Color(0xFFEFD8A2),
  red = Color(0xFFB33535),
  redSoft = Color(0xFFFDEAEA),
  redLine = Color(0xFFEFC4C4),
  terminalBg = Color(0xFF101419),
  terminalLine = Color(0xFF2D343D),
  toastBg = Color(0xFF1C232B),
  toastLine = Color(0xFF333C47),
  toastText = Color(0xFFFFFFFF),
)

private val DarkPalette = DshmPalette(
  bg = Color(0xFF0F1317),
  surface = Color(0xFF171D24),
  surfaceMuted = Color(0xFF1F262E),
  ink = Color(0xFFE8EEF5),
  muted = Color(0xFF9DABBB),
  line = Color(0xFF2B333D),
  lineStrong = Color(0xFF3D4854),
  lineFaint = Color(0xFF262D35),
  blue = Color(0xFF86ABFF),
  blueSoft = Color(0xFF1D2B4A),
  blueLine = Color(0xFF35507F),
  green = Color(0xFF5CC796),
  greenSoft = Color(0xFF143326),
  greenLine = Color(0xFF27553D),
  amber = Color(0xFFE9BD6B),
  amberSoft = Color(0xFF362A12),
  amberLine = Color(0xFF57431C),
  red = Color(0xFFFF9292),
  redSoft = Color(0xFF3A1F1F),
  redLine = Color(0xFF6C3434),
  terminalBg = Color(0xFF0B0F13),
  terminalLine = Color(0xFF303A45),
  toastBg = Color(0xFF232B34),
  toastLine = Color(0xFF3D4854),
  toastText = Color(0xFFFFFFFF),
)

val LocalDshmPalette = staticCompositionLocalOf { LightPalette }

/** 圆角令牌（styles.css 尺度令牌）：控件 12 / 卡片 16 / 胶囊 999 / 内层 8。 */
object DshmRadius {
  const val control = 12
  const val card = 16
  const val chip = 999
  const val inner = 8
}

private fun lightScheme(p: DshmPalette) = lightColorScheme(
  primary = p.blue,
  onPrimary = Color.White,
  primaryContainer = p.blueSoft,
  onPrimaryContainer = p.blue,
  secondary = p.ink,
  onSecondary = Color.White,
  background = p.bg,
  onBackground = p.ink,
  surface = p.surface,
  onSurface = p.ink,
  surfaceVariant = p.surfaceMuted,
  onSurfaceVariant = p.muted,
  surfaceContainer = p.surface,
  surfaceContainerHigh = p.surface,
  surfaceContainerHighest = p.surface,
  outline = p.lineStrong,
  outlineVariant = p.line,
  error = p.red,
  onError = Color.White,
  errorContainer = p.redSoft,
  onErrorContainer = p.red,
)

private fun darkScheme(p: DshmPalette) = darkColorScheme(
  primary = p.blue,
  onPrimary = Color(0xFF0F1317),
  primaryContainer = p.blueSoft,
  onPrimaryContainer = p.blue,
  secondary = p.ink,
  onSecondary = Color(0xFF0F1317),
  background = p.bg,
  onBackground = p.ink,
  surface = p.surface,
  onSurface = p.ink,
  surfaceVariant = p.surfaceMuted,
  onSurfaceVariant = p.muted,
  surfaceContainer = p.surface,
  surfaceContainerHigh = p.surface,
  surfaceContainerHighest = p.surface,
  outline = p.lineStrong,
  outlineVariant = p.line,
  error = p.red,
  onError = Color(0xFF0F1317),
  errorContainer = p.redSoft,
  onErrorContainer = p.red,
)

/**
 * 外壳主题：深浅双色板固定取值，强调色默认海洋蓝。
 * 与参考项目一致：状态栏/导航栏融入背景（theme-color == --bg）。
 */
@Composable
fun DshmTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val palette = if (darkTheme) DarkPalette else LightPalette
  CompositionLocalProvider(LocalDshmPalette provides palette) {
    MaterialTheme(
      colorScheme = if (darkTheme) darkScheme(palette) else lightScheme(palette),
      content = content,
    )
  }
}
