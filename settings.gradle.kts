// 说明：国内网络直连 google()/mavenCentral() 实测会卡死在下拉依赖上，
// 因此镜像仓库放在前面，官方源作为兜底。海外环境可直接删掉 maven.aliyun.com 三行。
//
// 注意：pluginManagement 块在 Kotlin DSL 中会被单独编译，看不到脚本顶层变量，
// 所以这里不引用任何外部 val。

pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "PhoneAct"

include(":app")
include(":xposed-stubs")
