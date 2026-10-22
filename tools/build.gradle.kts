plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// 재생기·하니스(Wilson)·덤프 도구(D34). gate·agent-core·app-scenarios는 tools를 모른다(테스트 방향 의존).
dependencies {
    implementation(project(":gate:core"))
    implementation(project(":gate:exec"))
    implementation(project(":agent-core"))
    implementation(project(":app-scenarios"))
    // 트리 API(JsonElement)만 쓴다. 직렬화 플러그인 없이 필드 매핑을 손으로 적어 포맷을 코드에서 바로 읽히게 한다.
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("capstone.tools.ReplayCliKt")
}

tasks.withType<Test>().configureEach {
    // 픽스처 경로를 테스트에 넘긴다(저장소 루트 기준 tools/fixtures).
    systemProperty("capstone.fixtures", layout.projectDirectory.dir("fixtures").asFile.absolutePath)
}
