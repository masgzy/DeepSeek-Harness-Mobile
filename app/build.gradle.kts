import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "io.github.masgzy.dshm"
  compileSdk = 36

  defaultConfig {
    applicationId = "io.github.masgzy.dshm"
    minSdk = 26          // Android 8.0+
    // 关键：targetSdk 保持 34。Android 15 起（targetSdk 35+）系统禁止
    // 执行应用数据目录中的 ELF —— proot/node 将无法启动。
    // 在升级 targetSdk 前必须先迁移 exec 方案（详见 docs/architecture.md）。
    targetSdk = 34
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    versionName = (project.findProperty("versionName") as String?) ?: "1.0.0-alpha.1"

    // 单一运行时：只支持 arm64-v8a（2019 年后的主流设备全覆盖）
    ndk { abiFilters += "arm64-v8a" }
  }

  androidResources {
    // rootfs/Node 压缩包已是高压缩格式，进 assets 时禁止 AAPT 二次压缩
    noCompress += listOf("gz", "xz", "tgz")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = true
    checkReleaseBuilds = false
  }

  packaging {
    // 容器脚本以 LF 落盘，避免资源压缩管线改动换行符
    resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
  implementation(composeBom)

  implementation("androidx.core:core-ktx:1.19.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
  implementation("androidx.activity:activity-compose:1.13.0")

  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended:1.7.8")
  implementation("androidx.navigation:navigation-compose:2.10.2")

  // rootfs 归档解包：tar 解析 + gzip/xz 解压
  implementation("org.apache.commons:commons-compress:1.28.0")
  implementation("org.tukaani:xz:1.10")

  debugImplementation("androidx.compose.ui:ui-tooling")
}
