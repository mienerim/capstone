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
// 화면 흐름의 판단(동의·한도·복창)은 app-core(JVM 테스트)에 있고, 여기는 그리기와 조립만 한다(I-46).
dependencies {
    implementation(project(":app-core"))
    implementation(project(":service:android"))
    implementation(project(":gate:exec"))
    implementation(project(":agent-core"))
    implementation(project(":app-scenarios"))
    implementation(project(":llm:litertlm"))
    implementation(libs.androidx.core.ktx)
    // 실험(10-30): llama.cpp 소스가 있는 PC에서만(settings 참고).
    rootProject.findProject(":llm:llamacpp")?.let { implementation(it) }
}
