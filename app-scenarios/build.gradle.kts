plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 대상 앱별 시나리오·셀렉터(코레일+ 우선, D4).
dependencies {
    api(project(":agent-core"))
}
