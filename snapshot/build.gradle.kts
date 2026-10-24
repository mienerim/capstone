plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 저장 트리 JSONL v0 코덱(D34). 덤퍼(service)가 쓰고 재생기(tools)가 읽으므로 둘 사이 공용 모듈로 뺐다(I-40).
dependencies {
    api(project(":gate:core"))
    // 트리 API(JsonElement)만 쓴다. 직렬화 플러그인 없이 필드 매핑을 손으로 적어 포맷을 코드에서 바로 읽히게 한다.
    implementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    systemProperty("capstone.fixtures", rootProject.layout.projectDirectory.dir("tools/fixtures").asFile.absolutePath)
}
