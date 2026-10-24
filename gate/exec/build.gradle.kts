plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 유일한 ActionExecutor 구현(D36). RawActuator는 service가 구현하고 app이 조립한다.
dependencies {
    api(project(":gate:core"))
}
