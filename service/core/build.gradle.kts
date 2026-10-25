plugins {
    id("org.jetbrains.kotlin.jvm")
}

// service의 판단 없는 부분 중 Android API에 묶이지 않는 것: 트리 캡처·NodeRef 해석·raw 실행·덤프 마스킹·기록.
// Android 어댑터(service/android)는 AccessibilityNodeInfo를 A11yNode로 감싸기만 한다(I-41).
dependencies {
    api(project(":gate:exec"))
    api(project(":snapshot"))
}
