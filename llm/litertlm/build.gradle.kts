plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "capstone.llm.litertlm"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// LiteRT-LM 어댑터(D38·D40: 0.18.0 고정).
dependencies {
    api(project(":llm:core"))
    implementation(libs.litertlm.android)
    // sendMessageAsync가 Flow를 돌려준다(가정, 10-26 AAR로 확인).
    implementation(libs.kotlinx.coroutines.core)
}
