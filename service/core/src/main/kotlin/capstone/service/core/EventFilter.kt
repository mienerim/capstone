package capstone.service.core

/** 서비스가 구독하는 접근성 이벤트 종류(D63 ② eventTypes 최소화: 이 셋만 받는다). */
enum class A11yEventKind { WINDOW_STATE_CHANGED, WINDOW_CONTENT_CHANGED, WINDOWS_CHANGED, OTHER }

/**
 * 접근성 이벤트 → 다시 캡처할지(D63 ②). accessibility_service_config의 packageNames는 비워 두고 여기서 거른다:
 * packageNames를 지정하면 패키지가 null로 오는 TYPE_WINDOWS_CHANGED가 항상 탈락해 창 전환·팝업을 놓친다.
 *
 * - 대상 앱 이벤트 → 캡처
 * - TYPE_WINDOWS_CHANGED(패키지 null) → 캡처. 창 목록(getWindows)을 다시 읽어 포그라운드로 판단한다
 * - 그 밖(다른 앱·패키지 없는 내용 이벤트) → 즉시 버린다. 이벤트 내용·노드는 읽지 않는다(개인정보)
 *
 * 이벤트를 놓쳐도 안전 쪽이다: 실행 경로는 액션마다 새로 캡처해 gate에 묻고(이벤트에 기대지 않음),
 * 덤프만 한 장 빠진다.
 */
class EventFilter(private val targetPackages: Set<String>) {
    fun shouldCapture(kind: A11yEventKind, packageName: String?): Boolean = when {
        kind == A11yEventKind.OTHER -> false
        packageName != null -> packageName in targetPackages
        else -> kind == A11yEventKind.WINDOWS_CHANGED
    }
}
