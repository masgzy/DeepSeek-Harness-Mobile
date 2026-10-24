package io.github.masgzy.dshm.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.masgzy.dshm.MainActivity
import io.github.masgzy.dshm.R
import io.github.masgzy.dshm.util.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 引擎前台服务：前台通知 + 每 5 秒看门狗。
 * 进程被杀后 START_STICKY 拉起服务 → 看门狗重启引擎。
 * （targetSdk 34 下 dataSync 无 6 小时超时限制；未来升级 targetSdk 35
 * 需要切换 foregroundServiceType，详见 docs/architecture.md 路线图。）
 */
class EngineService : Service() {

  private lateinit var engineManager: EngineManager
  private var watchdog: ScheduledExecutorService? = null
  private var ensureRunner: java.util.concurrent.ExecutorService? = null

  override fun onCreate() {
    super.onCreate()
    engineManager = EngineManager(this)
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AppLog.log("service", "onStartCommand")
    ensureEngine()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    watchdog?.shutdownNow()
    watchdog = null
    ensureRunner?.shutdownNow()
    ensureRunner = null
    // 主线程绝不阻塞等待进程树退出：proot 收 SIGTERM 后清理容器内子进程可达 10s+，
    // 同步 stopEngine 会 ANR（卡死十来秒后闪退）。挪到后台线程执行。
    val em = engineManager
    Thread({
      runCatching { em.stopEngine() }
    }, "engine-stop").start()
    super.onDestroy()
  }

  private fun ensureEngine() {
    if (ensureRunner == null) ensureRunner = Executors.newSingleThreadExecutor()
    ensureRunner?.execute {
      try {
        if (!engineManager.isRunning && engineManager.engineReady) {
          AppLog.log("service", "引擎未运行，启动")
          engineManager.startEngine()
        }
        armWatchdog()
      } catch (t: Throwable) {
        AppLog.log("service", "ensureEngine 失败", t)
      }
    }
  }

  /** 单飞看门狗：5s 轮询，引擎死亡即重启。任务体全程兜底，永不抛出。 */
  private fun armWatchdog() {
    if (watchdog != null) return
    watchdog = Executors.newSingleThreadScheduledExecutor().also { exec ->
      exec.scheduleWithFixedDelay({
        try {
          if (!engineManager.isRunning && engineManager.engineReady) {
            AppLog.log("service", "看门狗：引擎掉线，重启")
            AppLog.includeFile(engineManager.engineLog, "engine")
            engineManager.startEngine()
          }
        } catch (t: Throwable) {
          AppLog.log("service", "看门狗轮询异常", t)
        }
      }, 5, 5, TimeUnit.SECONDS)
    }
  }

  private fun buildNotification(): android.app.Notification {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.engine_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.engine_channel_desc) },
      )
    }
    val contentIntent = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(getString(R.string.engine_notification_title))
      .setContentText(getString(R.string.engine_notification_text, Paths.DSH_PORT))
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "dsh_engine"
    private const val NOTIFICATION_ID = 1001

    /** 统一入口：带通知权限检查的启动（Android 13+ 需运行时授权）。 */
    fun start(context: Context) {
      if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        AppLog.log("service", "未授予通知权限，跳过前台服务启动（引擎仍可前台使用）")
        return
      }
      ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, EngineService::class.java))
    }
  }
}
