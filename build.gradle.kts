// AGP는 Google Maven에서만 받을 수 있어, Android 모듈이 꺼져 있으면 클래스패스에 올리지 않는다.
// (plugins {} 블록은 조건부가 불가능해서 buildscript로 선언)
buildscript {
    val androidEnabled = gradle.extra["capstone.androidEnabled"] as Boolean
    repositories {
        if (androidEnabled) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.serialization.plugin)
        if (androidEnabled) classpath(libs.android.gradle.plugin)
    }
}

// 순수 Kotlin 모듈 공통 설정: JDK 17 툴체인, JUnit 5.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
        dependencies {
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(kotlin("test-junit5"))
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}
