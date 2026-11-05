// 仓库顺序策略（大陆网络适配）：
//  - CI（GitHub Actions，CI=true）：官方源优先，与上游构建行为完全一致；
//  - 本地构建（无 CI 变量）：阿里云镜像优先 —— Maven Central 对部分网络
//    环境限流（HTTP 429），且 Gradle 在 429 后会把整个仓库标记为 disabled
//    中断本次解析，国内镜像优先可根治本地构建随机失败（本项目定位即
//    「全面适配大陆网络」，本地开发者走镜像更快更稳）。
// 注：pluginManagement 块是独立编译作用域，无法引用外部 top-level 变量，
//     CI 判断以 System.getenv 内联表达。
pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    if (System.getenv("CI") == null) {
      maven("https://maven.aliyun.com/repository/gradle-plugin")
      maven("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
    gradlePluginPortal()
    if (System.getenv("CI") == null) {
      maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    if (System.getenv("CI") == null) {
      maven("https://maven.aliyun.com/repository/public")
      maven("https://maven.aliyun.com/repository/google")
    }
    mavenCentral()
    if (System.getenv("CI") == null) {
      maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
  }
}

rootProject.name = "DeepSeek-Harness-Mobile"
include(":app")
