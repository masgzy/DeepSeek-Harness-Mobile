package io.github.masgzy.dshm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 外壳主题：全面迁移到 miuix（HyperOS 设计语言，top.yukonga.miuix.kmp）。
 *
 *  - miuix `lightColorScheme()/darkColorScheme()` 覆盖品牌色：主色沿用原海洋蓝
 *    （浅 0xFF315FCF / 深 0xFF86ABFF），表面/背景沿用原 DshmPalette 的底色体系，
 *    保证迁移前后观感连续；
 *  - [DshmPalette] 保留：语义扩展色（成功绿/警告琥珀/危险红软底、终端面板深色、
 *    毛玻璃顶栏、toast）是 miuix 色板之外的领域色，供 UiKit 定制组件与终端区使用；
 *  - dsh web（HarnessActivity）与终端渲染区不经过本主题（WebView / 纯黑终端例外区）。
 */

/** 语义色全集：miuix colorScheme 装不下的扩展令牌都放这里。 */
data class DshmPalette(
  // 表面与文字
  val bg: Color,
  val surface: Color,
  val surfaceMuted: Color,
  val ink: Color,
  val muted: Color,
  // 品牌头与实底图标块（.brand-symbol / .launch-icon / .runtime-logo）
  val brandBg: Color,
  val iconSolidBg: Color,
  val onSolid: Color,
  // 线条
  val line: Color,
  val lineStrong: Color,
  val lineFaint: Color,
  val track: Color,
  // 主色（海洋蓝）
  val blue: Color,
  val blueHover: Color,
  val blueSoft: Color,
  val blueLine: Color,
  val onAccent: Color,
  // 语义色
  val green: Color,
  val greenSoft: Color,
  val greenLine: Color,
  val greenTrack: Color,
  val amber: Color,
  val amberSoft: Color,
  val amberLine: Color,
  val red: Color,
  val redSoft: Color,
  val redLine: Color,
  // 终端/日志面板：两种主题下都是深色
  val terminalBg: Color,
  val terminalLine: Color,
  val terminalText: Color,
  // 表单标签（.field > span --field-label）
  val fieldLabel: Color,
  // 毛玻璃表面（顶栏 96% / 底部导航 92% 不透明）
  val headerBg: Color,
  val navBg: Color,
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
  brandBg = Color(0xFF17202B),
  iconSolidBg = Color(0xFF202831),
  onSolid = Color(0xFFFFFFFF),
  line = Color(0xFFDCE2E9),
  lineStrong = Color(0xFFC8D0DA),
  lineFaint = Color(0xFFE3E6E9),
  track = Color(0xFFE3E8EE),
  blue = Color(0xFF315FCF),
  blueHover = Color(0xFF274FAA),
  blueSoft = Color(0xFFE8EFFF),
  blueLine = Color(0xFFCBD9FB),
  onAccent = Color(0xFFFFFFFF),
  green = Color(0xFF177A50),
  greenSoft = Color(0xFFE6F5EE),
  greenLine = Color(0xFFC6E5D7),
  greenTrack = Color(0xFFA8D7C3),
  amber = Color(0xFF9A6500),
  amberSoft = Color(0xFFFFF2D5),
  amberLine = Color(0xFFEFD8A2),
  red = Color(0xFFB33535),
  redSoft = Color(0xFFFDEAEA),
  redLine = Color(0xFFEFC4C4),
  terminalBg = Color(0xFF101419),
  terminalLine = Color(0xFF2D343D),
  terminalText = Color(0xFFC6CED8),
  fieldLabel = Color(0xFF475362),
  headerBg = Color(0xF5FFFFFF),
  navBg = Color(0xEBFFFFFF),
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
  brandBg = Color(0xFF2A3440),
  iconSolidBg = Color(0xFF2B3641),
  onSolid = Color(0xFFFFFFFF),
  line = Color(0xFF2B333D),
  lineStrong = Color(0xFF3D4854),
  lineFaint = Color(0xFF262D35),
  track = Color(0xFF242C35),
  blue = Color(0xFF86ABFF),
  blueHover = Color(0xFFA3C0FF),
  blueSoft = Color(0xFF1D2B4A),
  blueLine = Color(0xFF35507F),
  onAccent = Color(0xFF0F1317),
  green = Color(0xFF5CC796),
  greenSoft = Color(0xFF143326),
  greenLine = Color(0xFF27553D),
  greenTrack = Color(0xFF2C6047),
  amber = Color(0xFFE9BD6B),
  amberSoft = Color(0xFF362A12),
  amberLine = Color(0xFF57431C),
  red = Color(0xFFFF9292),
  redSoft = Color(0xFF3A1F1F),
  redLine = Color(0xFF6C3434),
  terminalBg = Color(0xFF0B0F13),
  terminalLine = Color(0xFF303A45),
  terminalText = Color(0xFFCCD5DF),
  fieldLabel = Color(0xFFB9C5D3),
  headerBg = Color(0xF50F1317),
  navBg = Color(0xEB0F1317),
  toastBg = Color(0xFF232B34),
  toastLine = Color(0xFF3D4854),
  toastText = Color(0xFFFFFFFF),
)

val LocalDshmPalette = staticCompositionLocalOf { LightPalette }

/** miuix 浅色方案：品牌蓝 + 原底色体系（其余取 miuix 默认 HyperOS 值）。 */
private fun dshmLightScheme(p: DshmPalette) = lightColorScheme(
  primary = p.blue,
  onPrimary = Color.White,
  primaryVariant = p.blueHover,
  onPrimaryVariant = p.blueSoft,
  background = p.bg,
  onBackground = p.ink,
  surface = p.surface,
  onSurface = p.ink,
  surfaceVariant = p.surfaceMuted,
  onSurfaceSecondary = p.ink.copy(alpha = 0.8f),
  onSurfaceVariantSummary = p.muted,
  secondary = p.ink,
  onSecondary = Color.White,
  error = p.red,
  onError = Color.White,
  errorContainer = p.redSoft,
  onErrorContainer = p.red,
)

/** miuix 深色方案：同构映射。 */
private fun dshmDarkScheme(p: DshmPalette) = darkColorScheme(
  primary = p.blue,
  onPrimary = Color(0xFF0F1317),
  primaryVariant = p.blueHover,
  onPrimaryVariant = p.blueSoft,
  background = p.bg,
  onBackground = p.ink,
  surface = p.surface,
  onSurface = p.ink,
  surfaceVariant = p.surfaceMuted,
  onSurfaceSecondary = p.ink.copy(alpha = 0.8f),
  onSurfaceVariantSummary = p.muted,
  secondary = p.ink,
  onSecondary = Color(0xFF0F1317),
  error = p.red,
  onError = Color(0xFF0F1317),
  errorContainer = p.redSoft,
  onErrorContainer = p.red,
)

/**
 * 页面背景绘制：底色上叠两个 accent 蓝径向渐变（对齐参考项目 body 背景）——
 * 左上 `circle at 7% -12%: blue 10% → transparent 34%`，
 * 右上 `circle at 100% 12%: blue 7% → transparent 30%`。
 * 在外壳根部 Modifier.drawBehind 中调用。
 */
fun DrawScope.drawDshmBackground(p: DshmPalette) {
  drawRect(p.bg)
  val c1 = Offset(size.width * 0.07f, -size.height * 0.12f)
  drawRect(
    brush = Brush.radialGradient(
      colors = listOf(p.blue.copy(alpha = 0.10f), Color.Transparent),
      center = c1,
      radius = size.width * 0.85f,
    ),
  )
  val c2 = Offset(size.width, size.height * 0.12f)
  drawRect(
    brush = Brush.radialGradient(
      colors = listOf(p.blue.copy(alpha = 0.07f), Color.Transparent),
      center = c2,
      radius = size.width * 0.70f,
    ),
  )
}

/** 圆角令牌（styles.css 尺度令牌）：控件 12 / 卡片 16 / 胶囊 999 / 内层 8。 */
object DshmRadius {
  const val control = 12
  const val card = 16
  const val chip = 999
  const val inner = 8
}

/**
 * 外壳主题：MiuixTheme 接线（深浅双色板固定取值，强调色海洋蓝）。
 * 与参考项目一致：状态栏/导航栏融入背景（theme-color == --bg）。
 */
@Composable
fun DshmTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val palette = if (darkTheme) DarkPalette else LightPalette
  CompositionLocalProvider(LocalDshmPalette provides palette) {
    MiuixTheme(
      colors = if (darkTheme) dshmDarkScheme(palette) else dshmLightScheme(palette),
      content = content,
    )
  }
}

/** 便捷取色：UiKit 定制组件里用 `MiuixTheme.colorScheme.xxx` 直接读。 */
object DshmMiuix {
  val colors: top.yukonga.miuix.kmp.theme.Colors
    @Composable get() = MiuixTheme.colorScheme
}
