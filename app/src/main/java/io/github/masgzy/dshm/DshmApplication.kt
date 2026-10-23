package io.github.masgzy.dshm

import android.app.Application
import io.github.masgzy.dshm.util.AppLog

class DshmApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppLog.init(filesDir)
    AppLog.log("app", "DshmApplication onCreate")
  }
}
