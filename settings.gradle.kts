// GitHub Actions 等可直接访问官方仓库的环境设置 USE_ALIYUN_MIRROR=false；
// 默认走阿里云镜像（本机开发环境 dl.google.com 握手失败）。
pluginManagement {
    repositories {
        if (System.getenv("USE_ALIYUN_MIRROR") != "false") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("USE_ALIYUN_MIRROR") != "false") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            google()
            mavenCentral()
        }
        maven("https://jitpack.io")
    }
}

rootProject.name = "JumpMaster"
include(":app")
