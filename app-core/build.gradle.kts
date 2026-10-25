plugins {
    id("org.jetbrains.kotlin.jvm")
}

// app의 판단 부분(순수 Kotlin): 첫 실행 고지·동의, D17 일일 한도, 버튼/발화 → 슬롯 채우기 → 복창·확인 → 실행 상태 안내.
// Android 화면(app)은 이 모듈의 상태를 그리기만 한다(I-46). gate·service는 보지 않는다: 실행은 주입받은 러너가 한다.
dependencies {
    api(project(":agent-core"))
    api(project(":llm:core"))
}

// 매니페스트 정책 테스트(INTERNET 없음·isAccessibilityTool=false)가 저장소 파일을 읽는다.
tasks.withType<Test>().configureEach {
    systemProperty("capstone.rootDir", rootDir.absolutePath)
    systemProperty("capstone.fixtures", rootDir.resolve("tools/fixtures").absolutePath)
}

// 통합 테스트만 실제 조립(재생기 + gate + 목업 시나리오)을 쓴다. main은 이들을 모른다.
dependencies {
    testImplementation(project(":tools"))
    testImplementation(project(":gate:exec"))
    testImplementation(project(":app-scenarios"))
}
