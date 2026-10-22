plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 재생기·하니스(Wilson)·덤프 도구(D34).
dependencies {
    implementation(project(":gate:core"))
    implementation(project(":agent-core"))
}
