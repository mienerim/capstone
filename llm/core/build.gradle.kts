plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 슬롯 스키마 v1·검증기·LlmRunner 인터페이스(D30). Android 런타임 어댑터는 :llm:litertlm.

dependencies {
    // 트리 API(JsonElement)만 쓴다: 모델 출력을 엄격하게 손으로 검사하기 위해서(모르는 키·타입 불일치 = 거부).
    implementation(libs.kotlinx.serialization.json)
}
