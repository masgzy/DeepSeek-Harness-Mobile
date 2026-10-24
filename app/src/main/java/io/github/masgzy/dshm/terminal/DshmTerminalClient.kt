package io.github.masgzy.dshm.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端客户端：粘合 TerminalSession/TerminalView 与本应用。
 *
 * 同时实现 TerminalSessionClient 与 TerminalViewClient（两接口的 logXxx 同签名，
 * 单份实现即可）。CTRL/ALT/FN 粘滞修饰键：快捷键条按下后置位，下一次按键
 * （IME/硬键经 TerminalView 轮询 readXxxKey，快捷键字面量经 [takeModifier]）
 * 消费并自动复位（Termux ExtraKeysView 同语义）。
 */
class DshmTerminalClient : TerminalSessionClient, TerminalViewClient {

  val ctrlActive = AtomicBoolean(false)
  val altActive = AtomicBoolean(false)
  val fnActive = AtomicBoolean(false)

  /** 会话退出回调（UI 层刷新状态）。 */
  var onSessionFinished: ((TerminalSession) -> Unit)? = null

  /** 终端视图引用（onTextChanged 等回调需要驱动重绘，Termux app 同款）。 */
  var view: TerminalView? = null

  /** 读取并清除粘滞修饰键（供快捷键条发送字面量时使用）。 */
  fun takeModifier(): Triple<Boolean, Boolean, Boolean> = Triple(
    ctrlActive.getAndSet(false),
    altActive.getAndSet(false),
    fnActive.getAndSet(false),
  )

  // ---- TerminalSessionClient / TerminalViewClient（公共 logXxx 单份实现） ----

  override fun onTextChanged(changedSession: TerminalSession) {
    view?.onScreenUpdated()
  }

  override fun onTitleChanged(changedSession: TerminalSession) {}

  override fun onSessionFinished(finishedSession: TerminalSession) {
    log("终端会话已退出（exit=${finishedSession.exitStatus}）")
    onSessionFinished?.invoke(finishedSession)
    view?.onScreenUpdated()
  }

  override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}

  override fun onPasteTextFromClipboard(session: TerminalSession) {}

  override fun onBell(session: TerminalSession) {}

  override fun onColorsChanged(session: TerminalSession) {
    view?.onScreenUpdated()
  }

  override fun onTerminalCursorStateChange(state: Boolean) {}

  override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

  override fun logError(tag: String, message: String) = log("[$tag] $message")
  override fun logWarn(tag: String, message: String) = log("[$tag] $message")
  override fun logInfo(tag: String, message: String) {}
  override fun logDebug(tag: String, message: String) {}
  override fun logVerbose(tag: String, message: String) {}
  override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
    log("$message: ${e.message}")
  override fun logStackTrace(tag: String, e: Exception) = log("${e.message}")

  // ---- TerminalViewClient ----

  override fun onScale(scale: Float): Float = scale

  override fun onSingleTapUp(e: MotionEvent) {
    // 软键盘唤起由 TerminalView 内部 requestFocus 处理
  }

  override fun shouldBackButtonBeMappedToEscape(): Boolean = false

  override fun shouldEnforceCharBasedInput(): Boolean = true

  override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

  override fun isTerminalViewSelected(): Boolean = true

  override fun copyModeChanged(copyMode: Boolean) {}

  override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

  override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

  override fun onLongPress(event: MotionEvent): Boolean = false

  override fun readControlKey(): Boolean = ctrlActive.getAndSet(false)

  override fun readAltKey(): Boolean = altActive.getAndSet(false)

  override fun readShiftKey(): Boolean = false

  override fun readFnKey(): Boolean = fnActive.getAndSet(false)

  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
    false // 交给 TerminalView 默认处理

  override fun onEmulatorSet() {}

  private fun log(message: String) {
    try {
      io.github.masgzy.dshm.util.AppLog.log("terminal", message)
    } catch (_: Throwable) {
    }
  }
}
