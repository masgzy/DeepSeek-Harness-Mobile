package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.util.AppLog
import java.io.File

/**
 * 日志页：引擎日志（engine.log）+ 应用日志（app.log）双 Tab 逻辑，
 * 框架阶段以分段切换展示，自动滚动到底部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(baseDir: File) {
  var tab by remember { mutableStateOf(0) }
  var lines by remember { mutableStateOf<List<String>>(emptyList()) }

  fun reload() {
    lines = if (tab == 0) {
      AppLog.tail(io.github.masgzy.dshm.engine.Paths.engineLog(baseDir), 400)
    } else {
      AppLog.recent(400)
    }
  }

  LaunchedEffect(tab) { reload() }
  LaunchedEffect(Unit) {
    while (true) {
      kotlinx.coroutines.delay(2_000)
      reload()
    }
  }

  Scaffold(
    topBar = {
      val context = androidx.compose.ui.platform.LocalContext.current
      TopAppBar(
        title = { Text("日志") },
        actions = {
          Row(Modifier.padding(end = 8.dp)) {
            OutlinedButton(
              onClick = {
                if (lines.isNotEmpty()) {
                  // 平台剪贴板 API：不依赖 Compose 剪贴板封装的版本演进
                  val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                  cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("dshm-log", lines.joinToString("\n")),
                  )
                }
              },
              enabled = lines.isNotEmpty(),
            ) { Text("复制") }
            OutlinedButton(onClick = { tab = 0 }) {
              Text(
                "引擎",
                color = if (tab == 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            OutlinedButton(onClick = { tab = 1 }) {
              Text(
                "应用",
                color = if (tab == 1) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
      )
    },
  ) { padding ->
    if (lines.isEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          if (tab == 0) "暂无引擎日志，先启动引擎吧" else "暂无应用日志",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      val listState = rememberLazyListState()
      LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
      }
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        items(lines) { line ->
          Text(
            line,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 1.dp),
          )
        }
      }
    }
  }
}
