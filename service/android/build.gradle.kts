plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "capstone.service"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// AccessibilityService와 raw performAction/dispatchGesture. 판단 없는 로직은 service/core(JVM 테스트)에 있고,
// 여기서는 AccessibilityNodeInfo를 A11yNode로 감싸기만 한다. 밖으로는 NodeRef만(D36).
dependencies {
    api(project(":service:core"))
    implementation(libs.mlkit.text.korean)
}
