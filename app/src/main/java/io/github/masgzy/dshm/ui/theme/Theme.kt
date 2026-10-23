package io.github.masgzy.dshm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** DeepSeek 品牌蓝作为静态回退种子色（Material You 动态取色不可用时）。 */
val DeepSeekBlue = Color(0xFF4D6BFE)

private val LightColors = lightColorScheme(
  primary = DeepSeekBlue,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFDEE0FF),
  onPrimaryContainer = Color(0xFF000F5C),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFFBAC3FF),
  onPrimary = Color(0xFF08218D),
  primaryContainer = Color(0xFF293CA9),
  onPrimaryContainer = Color(0xFFDEE0FF),
)

/**
 * Material 3 主题：Android 12+ 默认应用用户壁纸动态取色（Material You），
 * 低版本回退到 DeepSeek 品牌蓝。
 */
@Composable
fun DshmTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Material You：S+ 动态取色（用户可从系统关闭，此处尊重系统设置）
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColors
    else -> LightColors
  }
  MaterialTheme(colorScheme = colorScheme, content = content)
}
