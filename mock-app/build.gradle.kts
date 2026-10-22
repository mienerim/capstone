plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "capstone.mock"
    compileSdk = 36
    defaultConfig {
        applicationId = "capstone.mock"
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

// 코레일+ 흉내 목업 4화면(S1 검색폼/S2 결과/S3 좌석+예매하기/S4 결제 진입) + 스위치(D34).
dependencies {
    implementation(libs.androidx.core.ktx)
}
