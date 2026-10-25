package io.github.masgzy.dshm.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import io.github.masgzy.dshm.terminal.DshmTerminalClient
import io.github.masgzy.dshm.terminal.TerminalHolder
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette

/**
 * 容器终端（轻量）：proot 容器内 /bin/bash 的交互终端。
 *
 * 终端内核取自 Termux terminal-emulator/terminal-view（Apache-2.0，仅 20 个
 * 渲染/PTY 源文件 + 15KB libtermux.so，无任何 termux 运行时/包管理）；
 * 快捷键条复刻 Termux extra-keys 三行布局（键位与样式对齐用户真机截图，
 * 见 [EXTRA_KEYS_ROWS]）：
 *  - 字面量键直接写入 pty；
 *  - ESC/TAB/ENTER/HOME/END/PGUP/PGDN/方向键发送对应转义序列；
 *  - CTRL/ALT/FN 为粘滞修饰键：点亮后作用于下一次按键（Ctrl+C / Alt+. /
 *    Fn+数字=F1-F12），IME 与硬键盘路径由 TerminalView 轮询同款状态。
 */
@Composable
fun TerminalScreen(viewModel: io.github.masgzy.dshm.ui.AppViewModel, onBack: () -> Unit) {
  val p = LocalDshmPalette.current
  val context = LocalContext.current
  // 进程级共享 client：会话回调（onTextChanged→重绘）必须命中本屏 view，
  // 详见 TerminalHolder.sharedClient 注释
  val client = remember { TerminalHolder.sharedClient }
  var sessionState by remember { mutableStateOf(TerminalHolder.current()) }
  var attachedId by remember { mutableStateOf(0) }
  // 修饰键点亮态（UI 反馈；真实状态在 client 的 AtomicBoolean）
  var ctrlOn by remember { mutableStateOf(false) }
  var altOn by remember { mutableStateOf(false) }
  var fnOn by remember { mutableStateOf(false) }
  var bootError by remember { mutableStateOf<String?>(null) }

  fun refreshSession() {
    val s = TerminalHolder.obtain(context, client)
    sessionState = s
    bootError = if (s == null) {
      if (!io.github.masgzy.dshm.engine.Paths.rootfsUsable(
          io.github.masgzy.dshm.engine.Paths.rootfs(context.filesDir),
        )
      ) {
        "容器未就绪：请先完成安装（首页「打开对话」引导）。"
      } else {
        "终端运行组件不可用（该 APK 需包含 arm64-v8a 运行库）。"
      }
    } else {
      null
    }
  }

  // 进入时开 会话；会话退出时更新状态
  LaunchedEffect(Unit) { refreshSession() }
  // 会话变化 → 挂到当前 view（attachedId 防重入；update 块仅在重组时触发，
  // 这里补充一条响应式路径，保证会话创建/替换后立即挂载）
  LaunchedEffect(sessionState) {
    val v = client.view ?: return@LaunchedEffect
    val s = sessionState ?: return@LaunchedEffect
    if (System.identityHashCode(s) != attachedId) {
      v.attachSession(s)
      attachedId = System.identityHashCode(s)
    }
  }
  DisposableEffect(Unit) {
    client.onSessionFinished = { sessionState = TerminalHolder.current() }
    onDispose {
      client.onSessionFinished = null
      client.view = null
    }
  }

  fun sendToSession(data: ByteArray) {
    val s = sessionState ?: return
    if (s.isRunning) s.write(data, 0, data.size)
  }

  Box(
    Modifier
      .fillMaxSize()
      .background(p.terminalBg),
  ) {
    Column(Modifier.fillMaxSize()) {
      // ---- 顶栏：返回 + 标题 + 新会话（单行，不放状态文案，终端尽量占满） ----
      Row(
        Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            "返回",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
        Text(
          "容器终端",
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {
          TerminalHolder.close()
          refreshSession()
        }) {
          Icon(Icons.Outlined.Refresh, "新会话", tint = MaterialTheme.colorScheme.onSurface)
        }
      }

      // ---- 终端视图 ----
      Box(
        Modifier
          .weight(1f)
          .fillMaxWidth(),
      ) {
        if (bootError == null) {
          AndroidView(
            factory = { ctx ->
              TerminalView(ctx, null).apply {
                setTerminalViewClient(client)
                layoutParams = FrameLayout.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setTextSize(
                  android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    13f,
                    ctx.resources.displayMetrics,
                  ).toInt(),
                )
                client.view = this
                sessionState?.let {
                  attachSession(it)
                  attachedId = System.identityHashCode(it)
                }
                isFocusable = true
                isFocusableInTouchMode = true
              }
            },
            update = { view ->
              val s = sessionState
              if (s != null && System.identityHashCode(s) != attachedId) {
                view.attachSession(s)
                attachedId = System.identityHashCode(s)
              }
            },
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Column(
            Modifier
              .fillMaxSize()
              .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              bootError ?: "",
              fontSize = 13.sp,
              lineHeight = 19.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // ---- 快捷键条（Termux extra-keys 同款：纯黑底、无边框纯文字键） ----
      ExtraKeysBar(
        ctrlOn = ctrlOn,
        altOn = altOn,
        fnOn = fnOn,
        onToggleModifier = { m ->
          when (m) {
            "CTRL" -> { ctrlOn = !ctrlOn; client.ctrlActive.set(ctrlOn) }
            "ALT" -> { altOn = !altOn; client.altActive.set(altOn) }
            "FN" -> { fnOn = !fnOn; client.fnActive.set(fnOn) }
          }
        },
        onKey = { key ->
          sendKey(key, client, ::sendToSession)
          if (key.kind != ExtraKeyKind.MODIFIER) {
            // 消费后同步 UI 态
            ctrlOn = client.ctrlActive.get()
            altOn = client.altActive.get()
            fnOn = client.fnActive.get()
          }
        },
      )
    }
  }
}

/** 发送一个快捷键：按粘滞修饰键（CTRL/ALT/FN）变换后写入会话。 */
private fun sendKey(
  key: ExtraKey,
  client: DshmTerminalClient,
  send: (ByteArray) -> Unit,
) {
  when (key.kind) {
    ExtraKeyKind.MODIFIER -> return // 修饰键由 UI 层切换，这里不发数据
    ExtraKeyKind.SPECIAL -> {
      send(key.seq!!.toByteArray(Charsets.UTF_8))
      return
    }
    ExtraKeyKind.TEXT -> {
      val (ctrl, alt, fn) = client.takeModifier()
      var text = key.text ?: ""
      // Fn + 数字/等号 → F1-F12（Termux 同语义）
      if (fn && text.length == 1) {
        fnKeySequence(text)?.let { send(it.toByteArray(Charsets.UTF_8)); return }
      }
      // Ctrl + 单字符 → 控制字符（Ctrl+C = 0x03 等）
      if (ctrl && text.length == 1) {
        val c = text[0].lowercaseChar().code
        if (c in 0x40..0x5F || c in 0x61..0x7A) {
          text = (c and 0x1F).toChar().toString()
        }
      }
      if (alt && text.isNotEmpty()) send("\u001B".toByteArray(Charsets.UTF_8))
      send(text.toByteArray(Charsets.UTF_8))
    }
  }
}

/** Fn 修饰下的字符 → 功能键转义序列（1-9,0 = F1-F10，'-' = F11，'=' = F12）。 */
private fun fnKeySequence(c: String): String? = when (c) {
  "1" -> "\u001BOP"
  "2" -> "\u001BOQ"
  "3" -> "\u001BOR"
  "4" -> "\u001BOS"
  "5" -> "\u001B[15~"
  "6" -> "\u001B[17~"
  "7" -> "\u001B[18~"
  "8" -> "\u001B[19~"
  "9" -> "\u001B[20~"
  "0" -> "\u001B[21~"
  "-" -> "\u001B[23~"
  "=" -> "\u001B[24~"
  else -> null
}

// ---- 快捷键定义 ----

enum class ExtraKeyKind { TEXT, SPECIAL, MODIFIER }

/** 快捷键：label 展示；kind 决定行为；text 字面量 / seq 转义序列。 */
data class ExtraKey(
  val label: String = "",
  val kind: ExtraKeyKind,
  val text: String? = null,
  val seq: String? = null,
  /** 修饰键名（CTRL/ALT/FN）。 */
  val modifier: String? = null,
)

/**
 * Termux extra-keys 三行布局（键位与展示符号对齐用户真机截图，见 upload/IMG_20261025_080032.jpg）：
 *
 * 第一行：ESC < > \ = ^ $ () {} [] ↵
 * 第二行：⇄ & ; / ~ % * HOME ↑ END PGUP
 * 第三行：CTRL FN ALT | - + " ← ↓ → PGDN
 */
val EXTRA_KEYS_ROWS: List<List<ExtraKey>> = listOf(
  listOf("ESC", "<", ">", "BACKSLASH", "\\=", "^", "$", "()", "{}", "[]", "ENTER"),
  listOf("TAB", "&", ";", "/", "~", "%", "*", "HOME", "UP", "END", "PGUP"),
  listOf("CTRL", "FN", "ALT", "|", "-", "+", "QUOTE", "LEFT", "DOWN", "RIGHT", "PGDN"),
).map { row -> row.map { parseExtraKey(it) } }

/** 解析 ZeroTermux extra-keys 词法：特殊键名 / 转义字面量（\x）/ 修饰键 / 纯字面量。 */
private fun parseExtraKey(token: String): ExtraKey = when (token) {
  "CTRL", "ALT", "FN" -> ExtraKey(token, ExtraKeyKind.MODIFIER, modifier = token)
  "ESC" -> ExtraKey("ESC", ExtraKeyKind.SPECIAL, seq = "\u001B")
  "TAB" -> ExtraKey("\u21C4", ExtraKeyKind.SPECIAL, seq = "\t")
  "ENTER" -> ExtraKey("\u21B5", ExtraKeyKind.SPECIAL, seq = "\r")
  "UP" -> ExtraKey("\u2191", ExtraKeyKind.SPECIAL, seq = "\u001B[A")
  "DOWN" -> ExtraKey("\u2193", ExtraKeyKind.SPECIAL, seq = "\u001B[B")
  "LEFT" -> ExtraKey("\u2190", ExtraKeyKind.SPECIAL, seq = "\u001B[D")
  "RIGHT" -> ExtraKey("\u2192", ExtraKeyKind.SPECIAL, seq = "\u001B[C")
  "HOME" -> ExtraKey("HOME", ExtraKeyKind.SPECIAL, seq = "\u001B[H")
  "END" -> ExtraKey("END", ExtraKeyKind.SPECIAL, seq = "\u001B[F")
  "PGUP" -> ExtraKey("PGUP", ExtraKeyKind.SPECIAL, seq = "\u001B[5~")
  "PGDN" -> ExtraKey("PGDN", ExtraKeyKind.SPECIAL, seq = "\u001B[6~")
  "INS" -> ExtraKey("INS", ExtraKeyKind.SPECIAL, seq = "\u001B[2~")
  "DEL" -> ExtraKey("DEL", ExtraKeyKind.SPECIAL, seq = "\u001B[3~")
  "BACKSLASH" -> ExtraKey("\\", ExtraKeyKind.TEXT, text = "\\")
  "QUOTE" -> ExtraKey("\"", ExtraKeyKind.TEXT, text = "\"")
  else ->
    if (token.length >= 2 && token[0] == '\\') {
      // "\=" 等转义字面量：剥掉前导反斜杠
      ExtraKey(token.substring(1), ExtraKeyKind.TEXT, text = token.substring(1))
    } else {
      ExtraKey(token, ExtraKeyKind.TEXT, text = token)
    }
}

/**
 * 快捷键条：Termux extra-keys 同款观感——纯黑底（与终端区无缝连体）、
 * 无边框无圆角的纯文字键、白字居中、长标签自动两行（HOME/PGUP 同款换行）、
 * 按下带默认涟漪；粘滞修饰键点亮时蓝字提示。
 */
@Composable
private fun ExtraKeysBar(
  ctrlOn: Boolean,
  altOn: Boolean,
  fnOn: Boolean,
  onToggleModifier: (String) -> Unit,
  onKey: (ExtraKey) -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .background(ExtraKeysBarColor)
      .navigationBarsPadding(),
  ) {
    EXTRA_KEYS_ROWS.forEach { row ->
      Row(Modifier.fillMaxWidth().height(46.dp)) {
        row.forEach { key ->
          val active = when (key.modifier) {
            "CTRL" -> ctrlOn
            "ALT" -> altOn
            "FN" -> fnOn
            else -> false
          }
          Box(
            Modifier
              .weight(1f)
              .fillMaxHeight()
              .clickable { onKey(key) },
            contentAlignment = Alignment.Center,
          ) {
            Text(
              key.label,
              fontSize = 15.sp,
              lineHeight = 16.sp,
              fontWeight = FontWeight.Normal,
              color = if (active) ActiveModifierColor else ExtraKeyTextColor,
              maxLines = 2,
              softWrap = true,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
          }
        }
      }
    }
  }
}

// ---- 终端快捷键条配色（与 Termux 一致：纯黑底白字，与终端区无缝连体） ----
private val ExtraKeysBarColor = androidx.compose.ui.graphics.Color(0xFF000000)
private val ExtraKeyTextColor = androidx.compose.ui.graphics.Color(0xFFF2F3F5)
private val ActiveModifierColor = androidx.compose.ui.graphics.Color(0xFF86ABFF)
