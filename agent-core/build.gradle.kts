plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 상태머신·화면 분류기·DSL. gate API에만 의존하고 service는 볼 수 없다(D26·D36).
dependencies {
    api(project(":gate:core"))
}

// 테스트만 실제 실행 경로(GatedExecutor)를 끼운다. main 코드는 gate-exec를 볼 수 없다.
dependencies {
    testImplementation(project(":gate:exec"))
}
