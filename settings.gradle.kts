// ---------------------------------------------------------------------------
// 仓库：国内镜像在本地用，CI 上不用
//
// 2026-11-03 的 CI 事故：runner 上所有插件都「找不到」——
// 报错是 `Plugin [id: 'com.google.devtools.ksp', version: '2.0.20-1.0.25']
// was not found in any of the following sources`，然后列出六个仓库。
// 但那个版本在 Maven Central 和 aliyun 上都存在（本地能构建、浏览器能下载）。
//
// 抓了注解才看清：runner 上 aliyun **返回 502**，Maven Central 返回 200。
// 镜像排在前面，它一挂，Gradle 就把「这个仓库取不到」记成了
// 「这个坐标到处都没有」，报错指向版本号，完全看不出是镜像的问题。
//
// 所以：本地（有国内网络）走镜像图快，CI 走官方仓库图对。
// 判断放在 repositories 里面而不是文件顶部——
// `pluginManagement {}` 必须是脚本里第一个语句，前面不能站别的东西。
// ---------------------------------------------------------------------------

pluginManagement {
    repositories {
        if (System.getenv("CI").isNullOrEmpty()) {
            // 国内镜像优先，避免同步依赖过慢
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI").isNullOrEmpty()) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "SpeakInspire"
include(":app")
