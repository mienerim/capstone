package capstone.agent

import capstone.gate.LabelNormalizer
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow

/** 상세 ① 4절 화면 상태 enum. 시나리오는 이 이름을 [ScreenSignature.id]로 쓴다(앱별 확장 가능). */
object Screens {
    const val HOME = "HOME"
    const val SEARCH_FORM = "SEARCH_FORM"
    const val RESULT_LIST = "RESULT_LIST"
    const val DETAIL = "DETAIL"
    const val LOGIN = "LOGIN"
    const val PAYMENT_GATE = "PAYMENT_GATE"
    const val SENSITIVE_INPUT = "SENSITIVE_INPUT"
    const val POPUP_KNOWN = "POPUP_KNOWN"
    const val POPUP_UNKNOWN = "POPUP_UNKNOWN"
    const val ERROR = "ERROR"
    const val FOREIGN_APP = "FOREIGN_APP"
    const val UNKNOWN = Snapshot.SCREEN_UNKNOWN
}

/**
 * 노드 조건. 지정한 조건이 모두 맞아야 한다(AND). 텍스트 비교는 gate와 같은 [LabelNormalizer]로 한다:
 * 띄어쓰기·전각 차이로 분류가 흔들리지 않게(덤프가 바뀌어도 서명이 덜 깨지게).
 */
data class NodeMatcher(
    val text: String? = null,
    val textContains: String? = null,
    val desc: String? = null,
    /** viewId 전체 또는 `:id/` 뒤 이름. */
    val id: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
) {
    private val nText = text?.let(LabelNormalizer::normalize)
    private val nContains = textContains?.let(LabelNormalizer::normalize)
    private val nDesc = desc?.let(LabelNormalizer::normalize)

    init {
        require(listOf(text, textContains, desc, id, className).any { it != null }) { "빈 NodeMatcher" }
    }

    fun matches(n: UiNode): Boolean {
        if (!n.visible) return false
        val t = LabelNormalizer.normalize(n.text)
        val d = LabelNormalizer.normalize(n.contentDescription)
        return (nText == null || t == nText || (n.text.isNullOrEmpty() && LabelNormalizer.normalize(n.hint) == nText)) &&
            (nContains == null || t.contains(nContains) || d.contains(nContains)) &&
            (nDesc == null || d == nDesc) &&
            (id == null || n.viewId == id || n.viewId?.substringAfter(":id/", "") == id) &&
            (className == null || n.className == className || n.className?.substringAfterLast('.') == className) &&
            (clickable == null || n.clickable == clickable) &&
            (editable == null || n.editable == editable)
    }

    fun presentIn(w: UiWindow): Boolean = w.nodes.values.any(::matches)
}

/** 화면 서명: 패키지 일치 → must_have 전부 / must_not_have 없음 → 가중 점수(상세 ① 4절). */
data class ScreenSignature(
    val id: String,
    val packages: Set<String>,
    val mustHave: List<NodeMatcher>,
    val mustNotHave: List<NodeMatcher> = emptyList(),
    val weighted: List<Pair<NodeMatcher, Double>> = emptyList(),
) {
    init {
        require(mustHave.isNotEmpty()) { "$id: must_have가 비면 아무 화면에나 맞는다" }
        require(id != Screens.UNKNOWN) { "UNKNOWN은 서명으로 정의할 수 없다" }
    }
}

data class Classification(
    val screen: String,
    /** 후보별 점수(must_have 통과한 것만). 측정 시트 A·디버깅용. */
    val scores: Map<String, Double>,
    val reason: String,
)

/**
 * 규칙 기반 화면 분류기. 결과는 [Snapshot.screen]에 넣어 gate의 서명 검사(I-4)에 쓰인다.
 * 애매하면 UNKNOWN을 낸다: UNKNOWN이면 gate가 모든 액션을 막으므로(fail-closed) 틀린 화면으로 진행하는 것보다 안전하다.
 */
class ScreenClassifier(
    private val signatures: List<ScreenSignature>,
    /** 1·2위 점수 차가 이 값 이하면 UNKNOWN. 가설값, 실기기 덤프로 보정(M2). */
    private val margin: Double = 0.5,
) {
    private val knownPackages = signatures.flatMap { it.packages }.toSet()

    init {
        val dup = signatures.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "서명 id 중복: $dup" }
    }

    fun classify(snapshot: Snapshot): Classification {
        val fg = snapshot.foregroundAppWindow ?: return Classification(Screens.UNKNOWN, emptyMap(), "no_foreground_window")
        if (fg.packageName !in knownPackages) return Classification(Screens.FOREIGN_APP, emptyMap(), "pkg:${fg.packageName}")
        if (fg.root == null) return Classification(Screens.UNKNOWN, emptyMap(), "empty_tree")

        val scores = LinkedHashMap<String, Double>()
        for (sig in signatures) {
            if (fg.packageName !in sig.packages) continue
            if (!sig.mustHave.all { it.presentIn(fg) }) continue
            if (sig.mustNotHave.any { it.presentIn(fg) }) continue
            scores[sig.id] = sig.mustHave.size + sig.weighted.sumOf { (m, w) -> if (m.presentIn(fg)) w else 0.0 }
        }
        val ranked = scores.entries.sortedByDescending { it.value }
        return when {
            ranked.isEmpty() -> Classification(Screens.UNKNOWN, scores, "no_signature_matched")
            ranked.size >= 2 && ranked[0].value - ranked[1].value <= margin ->
                Classification(Screens.UNKNOWN, scores, "ambiguous:${ranked[0].key}~${ranked[1].key}")
            else -> Classification(ranked[0].key, scores, "matched")
        }
    }

    /** 분류 결과를 넣은 스냅샷. gate에 넘기기 전에 이걸 쓴다. */
    fun label(snapshot: Snapshot): Snapshot = snapshot.copy(screen = classify(snapshot).screen)
}
