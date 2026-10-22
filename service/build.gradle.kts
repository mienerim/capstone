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

// AccessibilityService와 raw performAction/dispatchGesture(RawActuator 구현). 밖으로는 NodeRef만(D36).
dependencies {
    implementation(project(":gate:exec"))
}
