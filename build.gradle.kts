// DeepSeek-Harness-Mobile 根构建脚本
// 版本锁定（2026-10 验证过的稳定组合，与 AGP 9.3.1 + Gradle 9.7.0 + JDK 17 匹配）：
//  - AGP 9.3.1
//  - Kotlin Android 插件 2.4.10
//  - Kotlin Compose 编译器插件 2.4.10（随 Kotlin 版本同行）
plugins {
  id("com.android.application") version "9.3.1" apply false
  id("org.jetbrains.kotlin.android") version "2.4.10" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
