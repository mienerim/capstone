plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "capstone.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "capstone.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// 조립 모듈: service + gate + agent-core + llm + app-scenarios를 묶는다(D36).
dependencies {
    implementation(project(":service"))
    implementation(project(":gate:exec"))
    implementation(project(":agent-core"))
    implementation(project(":app-scenarios"))
    implementation(project(":llm:litertlm"))
    implementation(libs.androidx.core.ktx)
}
