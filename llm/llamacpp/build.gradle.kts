plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// llama.cpp 소스는 저장소 밖에 둔다(settings의 capstone.llamaCppDir, v0.7.0 고정 권장).
val llamaCppDir = (gradle.extra["capstone.llamaCppDir"] as String).replace('\\', '/')

android {
    namespace = "capstone.llm.llamacpp"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 34
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // debug 변형이어도 추론은 Release로 컴파일한다(속도 측정용).
                arguments += listOf("-DLLAMA_SRC=$llamaCppDir", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// 실험(10-30): 엔진 비교(개발 계획 1번). llama.cpp를 JNI로 묶고 adb broadcast로 벤치만 돌린다.
dependencies {
}
