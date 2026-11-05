import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "io.github.masgzy.dshm"
  // compileSdk 37：AndroidX 2026-10 版本线（navigation 2.10.x / compose BOM
  // 2026.09.00 等）要求 compileSdk >= 37；targetSdk 保持 34 不受影响
  // （运行时行为仅由 targetSdk 决定）。
  compileSdk = 37

  // NDK：仅用于 libtermux（Termux terminal-emulator 的 pty JNI，214 行 C）。
  // 与 CI（.github/workflows/ci.yml setup-android packages）及本地开发锁定同版本。
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "io.github.masgzy.dshm"
    minSdk = 26          // Android 8.0+
    // targetSdk 34：运行时行为由 targetSdk 决定。Android 10+ 对 targetSdk>=29
    // 的应用施加 W^X 限制（禁止 exec 应用数据目录 ELF），proot 已改由
    // jniLibs/nativeLibraryDir 方案绕开（见 ProotRuntime 类注释），
    // targetSdk 不再约束 exec 能力；升级 35+ 前需评估前台服务类型。
    targetSdk = 34
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 3
    versionName = (project.findProperty("versionName") as String?) ?: "1.0.0-alpha.4"

    // 单一运行时：只支持 arm64-v8a（2019 年后的主流设备全覆盖）
    ndk { abiFilters += "arm64-v8a" }

    externalNativeBuild {
      cmake {
        cppFlags += ""
        arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildFeatures {
    // BuildConfig.VERSION_NAME 供设置页「关于」展示
    buildConfig = true
  }

  androidResources {
    // rootfs/Node 压缩包已是高压缩格式，进 assets 时禁止 AAPT 二次压缩
    noCompress += listOf("gz", "xz", "tgz")
  }

  // CI 签名：release workflow 将 release.keystore 放到项目根目录时，
  // release 构建自动使用该密钥（正式密钥来自 Secrets，alpha 首发
  // 为一次性生成密钥）；本地无密钥时保持不签名。
  if (rootProject.file("release.keystore").exists()) {
    signingConfigs.create("ci") {
      storeFile = rootProject.file("release.keystore")
      storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "dshm-release"
      keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "release"
      keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "dshm-release"
      // v1 签名兜底：部分 OEM 安装器拒绝仅 v2 的 APK
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (rootProject.file("release.keystore").exists()) {
        signingConfig = signingConfigs.getByName("ci")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    // 需在 JDK 21+ 下运行：AGP 新版 lint 内部分析（ECJ）依赖
    // SequencedCollection.removeLast()（JDK 21 API），JDK 17 下会在分析
    // Java 源文件（如 Termux JNI.java）时以 "Unexpected failure during
    // lint analysis" 硬崩。CI 与本地均用 JDK 21 跑 Gradle（编译目标仍 17）。
    abortOnError = true
    checkReleaseBuilds = false
  }

  packaging {
    // 容器脚本以 LF 落盘，避免资源压缩管线改动换行符
    resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    // jniLibs：proot 运行四件套（libdsh_proot/libdsh_proot_loader/libtalloc/
    // libandroid-shmem）必须解压到 nativeLibraryDir 才能被 exec ——
    // 新版 AGP 默认 extractNativeLibs=false（.so 直接从 APK 内映射，
    // 磁盘上无实体文件），必须显式开启 legacy 打包。压缩存储同时天然
    // 满足 16KB 页对齐设备的要求（对齐仅约束未压缩存储的 .so）。
    jniLibs {
      useLegacyPackaging = true
      keepDebugSymbols += listOf("**/libdsh_proot.so", "**/libdsh_proot_loader.so")
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

// ---------------------------------------------------------------------------
// proot 运行四件套自动注入（治「本地打出的 APK 不带 proot」）
//
// app/src/main/jniLibs/ 是 gitignore 的构建期产物，此前只有 CI 工作流显式跑
// scripts/fetch-proot.sh，本地 assembleDebug 极易忘记注入，产出的 APK 缺
// libdsh_proot.so 导致容器必然起不来（且只有装到真机才会发现）。改为
// Gradle 接管：任何 pre*Build 前检查标记文件，缺失即自动执行注入脚本
// （脚本自带 Termux 索引解析 + TUNA 镜像回退，幂等可重复执行）。
// 跳过手段：PROOT_SKIP_FETCH=1 ./gradlew ...（如仅想快速验证编译）。
// ---------------------------------------------------------------------------
val prootMarker = file("src/main/jniLibs/arm64-v8a/libdsh_proot.so")
val fetchProot by tasks.registering(Exec::class) {
  group = "build setup"
  description = "缺失时执行 scripts/fetch-proot.sh 注入 proot 运行四件套到 jniLibs"
  workingDir = rootDir
  commandLine("bash", "scripts/fetch-proot.sh")
  // 输出即标记文件；配合 onlyIf 实现存在即跳过（PROOT_SKIP_FETCH=1 强制跳过）
  outputs.file(prootMarker)
  onlyIf {
    !prootMarker.exists() && System.getenv("PROOT_SKIP_FETCH") != "1"
  }
}
tasks.matching { it.name == "preBuild" || it.name.matches(Regex("pre[A-Za-z]+Build")) }
  .configureEach { dependsOn(fetchProot) }

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
