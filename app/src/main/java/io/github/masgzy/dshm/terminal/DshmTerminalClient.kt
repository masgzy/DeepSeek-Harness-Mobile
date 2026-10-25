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
 * 单份实现即可）。CTRL/ALT/FN 粘滞修饰键三态语义（Termux extra-keys 增强）：
 *  - 点按 = 一次性粘滞：点亮，下一次按键消费后自动熄灭（Termux 同款）；
 *  - 长按 = 锁定：跨多次按键持续生效，再次点按解除；
 *  - 按住 = 即生效：手指按住期间另一只手点 IME 键即组合键（物理修饰键手感）。
 * IME/硬键路径经 TerminalView 轮询 readXxxKey 消费；快捷键字面量经 [takeModifier]。
 */
class DshmTerminalClient : TerminalSessionClient, TerminalViewClient {

  // ---- 粘滞修饰键三态（Termux extra-keys 语义增强）----
  // 一次性粘滞：点按点亮，下一次按键消费后自动熄灭（Termux 同款）
  private val ctrlOneShot = AtomicBoolean(false)
  private val altOneShot = AtomicBoolean(false)
  private val fnOneShot = AtomicBoolean(false)
  // 长按锁定：长按后持续生效（跨多次按键），直到再次点按/长按解除
  private val ctrlLocked = AtomicBoolean(false)
  private val altLocked = AtomicBoolean(false)
  private val fnLocked = AtomicBoolean(false)
  // 按住即生效：手指按住期间另一只手点 IME 键即组合键（物理修饰键手感）
  private val ctrlHeld = AtomicBoolean(false)
  private val altHeld = AtomicBoolean(false)
  private val fnHeld = AtomicBoolean(false)

  /** 会话退出回调（UI 层刷新状态）。 */
  var onSessionFinished: ((TerminalSession) -> Unit)? = null

  /** 终端视图引用（onTextChanged 等回调需要驱动重绘，Termux app 同款）。 */
  var view: TerminalView? = null

  /** 读取并清除粘滞修饰键（供快捷键条发送字面量时使用）；锁定/按住态不被清除。 */
  fun takeModifier(): Triple<Boolean, Boolean, Boolean> = Triple(
    readControlKey(),
    readAltKey(),
    readFnKey(),
  )

  // ---- 快捷键条修饰键交互入口（UI 状态同步源） ----

  /** 点按修饰键：任意激活态 → 全部熄灭；否则点亮一次性粘滞。 */
  fun modifierTap(mod: String) {
    val (one, lock) = oneLock(mod)
    if (lock.get() || one.get()) {
      lock.set(false)
      one.set(false)
    } else {
      one.set(true)
    }
  }

  /** 长按修饰键：切换锁定态（跨多次按键保持），同时清掉一次性粘滞避免叠加。 */
  fun modifierLongPress(mod: String) {
    val (one, lock) = oneLock(mod)
    one.set(false)
    lock.set(!lock.get())
  }

  /** 按住/抬起修饰键（按下即生效，物理修饰键手感）。 */
  fun modifierHeld(mod: String, held: Boolean) {
    heldRef(mod).set(held)
  }

  /** @return (active 有效激活, locked 锁定) 供 UI 渲染。 */
  fun modifierState(mod: String): Pair<Boolean, Boolean> {
    val (one, lock) = oneLock(mod)
    val held = heldRef(mod)
    return Pair(lock.get() || held.get() || one.get(), lock.get())
  }

  private fun oneLock(mod: String): Pair<AtomicBoolean, AtomicBoolean> = when (mod) {
    "CTRL" -> Pair(ctrlOneShot, ctrlLocked)
    "ALT" -> Pair(altOneShot, altLocked)
    else -> Pair(fnOneShot, fnLocked)
  }

  private fun heldRef(mod: String): AtomicBoolean = when (mod) {
    "CTRL" -> ctrlHeld
    "ALT" -> altHeld
    else -> fnHeld
  }

  /** TerminalView 轮询语义：锁定/按住 → 恒 true 且不清状态；否则消费一次性粘滞。 */
  private fun readOne(one: AtomicBoolean, lock: AtomicBoolean, held: AtomicBoolean): Boolean =
    if (lock.get() || held.get()) true else one.getAndSet(false)

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
    // Termux 同款：点终端任意处 = 要焦点 + 拉起软键盘。
    // 仅 requestFocus 不会唤出 IME（焦点流转与 IMMS 是两回事），必须显式 showSoftInput；
    // 刚 requestFocus 后 IMMS 可能尚未完成焦点流转，post 一次兜底重试。
    val v = view ?: return
    if (!v.hasFocus()) v.requestFocus()
    val imm =
      v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
        as? android.view.inputmethod.InputMethodManager ?: return
    imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    // 兜底：showSoftInput 幂等，重复请求对已弹出的 IME 无副作用
    v.post { if (v.hasFocus()) imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
  }

  override fun shouldBackButtonBeMappedToEscape(): Boolean = false

  override fun shouldEnforceCharBasedInput(): Boolean = true

  override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

  override fun isTerminalViewSelected(): Boolean = true

  override fun copyModeChanged(copyMode: Boolean) {}

  override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

  override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

  override fun onLongPress(event: MotionEvent): Boolean = false

  override fun readControlKey(): Boolean = readOne(ctrlOneShot, ctrlLocked, ctrlHeld)

  override fun readAltKey(): Boolean = readOne(altOneShot, altLocked, altHeld)

  override fun readShiftKey(): Boolean = false

  override fun readFnKey(): Boolean = readOne(fnOneShot, fnLocked, fnHeld)

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
