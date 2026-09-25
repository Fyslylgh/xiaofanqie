pluginManagement {
    repositories {
        // 国内镜像优先，失败自动回落到官方源
        maven("https://maven.aliyun.com/repository/gradle-plugin") { name = "AliyunGradlePlugin" }
        maven("https://maven.aliyun.com/repository/google") { name = "AliyunGoogle" }
        maven("https://maven.aliyun.com/repository/public") { name = "AliyunPublic" }
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
        maven("https://maven.aliyun.com/repository/google") { name = "AliyunGoogle" }
        maven("https://maven.aliyun.com/repository/public") { name = "AliyunPublic" }
        google()
        mavenCentral()
    }
}

rootProject.name = "Pomodoro"
include(":app")
