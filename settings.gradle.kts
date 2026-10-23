pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Android 모듈은 SDK(+Google Maven)가 있어야 구성된다. 클라우드 개발 환경·CI는 제외한다.
val androidMode = providers.gradleProperty("capstone.android").getOrElse("auto")
val sdkPresent = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
val androidEnabled = when (androidMode) {
    "true" -> true
    "false" -> false
    else -> sdkPresent
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google Maven이 막힌 환경에서 JVM 모듈 의존성 해석이 403으로 깨지지 않도록 조건부.
        if (androidEnabled) google()
        mavenCentral()
    }
}

rootProject.name = "capstone"

// 순수 Kotlin(JVM) 모듈: 어디서나 빌드·테스트된다.
include(
    ":gate:core",
    ":gate:exec",
    ":agent-core",
    ":llm:core",
    ":app-scenarios",
    ":app-core",
    ":snapshot",
    ":service:core",
    ":tools",
)

gradle.extra["capstone.androidEnabled"] = androidEnabled

if (androidEnabled) {
    include(
        ":service:android",
        ":llm:litertlm",
        ":app",
        ":mock-app",
    )
}
