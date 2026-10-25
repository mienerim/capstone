package capstone.service.core

/**
 * 받은 접근성 이벤트 수를 종류 × 출처(대상 앱 / 패키지 null / 다른 앱)로만 센다(D63 ④(b) 실측용).
 * packageNames를 비웠을 때와 지정했을 때 TYPE_WINDOWS_CHANGED(null)가 오는지 logcat 한 줄로 비교한다.
 *
 * 다른 앱 패키지 이름은 남기지 않는다: 어떤 앱을 쓰는지가 곧 개인정보다(D58). 스레드 하나(메인)에서만 부른다.
 */
class EventTally(private val targetPackages: Set<String>) {
    enum class Source { TARGET, NULL_PACKAGE, OTHER_APP }

    private val counts = LinkedHashMap<Pair<A11yEventKind, Source>, Int>()
    private var captured = 0

    fun count(kind: A11yEventKind, packageName: String?, didCapture: Boolean) {
        val source = when {
            packageName == null -> Source.NULL_PACKAGE
            packageName in targetPackages -> Source.TARGET
            else -> Source.OTHER_APP
        }
        val key = kind to source
        counts[key] = (counts[key] ?: 0) + 1
        if (didCapture) captured++
    }

    fun get(kind: A11yEventKind, source: Source): Int = counts[kind to source] ?: 0

    /** 예: `events WINDOWS_CHANGED/NULL_PACKAGE=3 WINDOW_STATE_CHANGED/TARGET=5 captured=8`. 종류 이름 순. */
    fun summary(): String {
        val parts = counts.entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second.name }))
            .joinToString(" ") { "${it.key.first}/${it.key.second}=${it.value}" }
        return if (parts.isEmpty()) "events none captured=0" else "events $parts captured=$captured"
    }
}
