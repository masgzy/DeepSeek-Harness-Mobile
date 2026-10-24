package io.github.masgzy.dshm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/**
 * 安装日志块（终端深色底 + 等宽字 + 分级着色 + 复制按钮）。
 *
 * 配色按行内关键词分级：
 *  - 失败 / FAIL / error → 红；
 *  - 警告 / warn / deprecated / 跳过 → 琥珀；
 *  - 完成 / 就绪 / 成功 / ✓ → 绿；
 *  - 阶段标题（=== … ===、[dshm-bootstrap]）→ 蓝；
 *  - 下载 / 解压 / registry / token / 源 → 青；
 *  - 其余 → 亮灰（较旧版提亮，避免「有点暗」）。
 */
@Composable
fun InstallLogBlock(
  lines: List<String>,
  title: String = "安装日志（最近 ${lines.size.coerceAtMost(30)} 条）",
  visibleCount: Int = 30,
) {
  val p = LocalDshmPalette.current
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }

  Column(
    Modifier
      .fillMaxWidth()
      .background(p.terminalBg, RoundedCornerShape(DshmRadius.control.dp))
      .border(1.dp, p.terminalLine, RoundedCornerShape(DshmRadius.control.dp))
      .padding(12.dp),
  ) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF9FB4CC),
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) {
            clipboard.setText(AnnotatedString(lines.takeLast(visibleCount).joinToString("\n")))
            copied = true
          }
          .padding(horizontal = 6.dp, vertical = 3.dp),
      ) {
        Icon(
          if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy,
          contentDescription = "复制日志",
          tint = if (copied) Color(0xFF7BD88F) else Color(0xFF9FB4CC),
          modifier = Modifier.size(13.dp),
        )
        Text(
          if (copied) "已复制" else "复制",
          fontSize = 11.sp,
          color = if (copied) Color(0xFF7BD88F) else Color(0xFF9FB4CC),
        )
      }
    }
    Spacer(Modifier.height(8.dp))
    lines.takeLast(visibleCount).forEach { line ->
      Text(
        line,
        fontSize = 10.sp,
        lineHeight = 14.5.sp,
        fontFamily = FontFamily.Monospace,
        color = logLineColor(line),
      )
    }
    if (copied) {
      // 3 秒后恢复「复制」态
      androidx.compose.runtime.LaunchedEffect(copied) {
        kotlinx.coroutines.delay(3_000)
        copied = false
      }
    }
  }
}

/** 行着色规则（见组件注释）。 */
private fun logLineColor(line: String): Color {
  val l = line.lowercase()
  return when {
    listOf("fail", "error", "失败", "无法", "不可用", "不匹配").any { l.contains(it) } ->
      Color(0xFFFF7A7A)
    listOf("warn", "警告", "deprecated", "跳过", "无法匿名").any { l.contains(it) } ->
      Color(0xFFFFC46B)
    listOf("完成", "就绪", "成功", "校验通过", "✓").any { line.contains(it) } ->
      Color(0xFF7BD88F)
    line.contains("===") || line.contains("[dshm-bootstrap]") || line.contains("[mirror]") ->
      Color(0xFF8AB8FF)
    listOf("registry", "token", "下载", "解压", "lxc-images", "镜像源", "直链", "segment", "分段")
      .any { l.contains(it) } -> Color(0xFF6FD8E8)
    else -> Color(0xFFD8DFE8)
  }
}
