package capstone.service.core

import capstone.gate.Snapshot
import capstone.gate.UiNode

/**
 * 덤프 저장 전 기기 안에서 하는 1차 마스킹(I-42). 패턴으로 잡히는 것만 지운다:
 * 이름·주소처럼 패턴이 없는 개인정보는 못 잡으므로, 커밋 전 사람 검토는 그대로 필요하다.
 *
 * - data-sensitive·입력칸 노드: 문자열 필드 전체를 [MASK]로(입력값은 사용자 정보일 수 있다)
 * - 그 밖의 노드: 휴대폰·주민번호·카드번호·이메일·8자리 이상 숫자열을 [MASK]로
 * 역 이름·시각·요금·호차/좌석 번호처럼 분류기·gate가 써야 하는 짧은 값은 남긴다.
 */
object DumpMasker {
    const val MASK: String = "[MASK]"

    private val patterns = listOf(
        Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""),
        Regex("""\d{6}\s*-\s*[1-8]\d{6}"""),
        Regex("""\d{4}[\s-]\d{4}[\s-]\d{4}[\s-]\d{3,4}"""),
        Regex("""01[016789][\s.-]?\d{3,4}[\s.-]?\d{4}"""),
        Regex("""\d{8,}"""),
    )

    fun maskText(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        return patterns.fold(s) { acc, re -> re.replace(acc, MASK) }
    }

    fun mask(snapshot: Snapshot): Snapshot =
        snapshot.copy(windows = snapshot.windows.map { w -> w.copy(root = w.root?.let(::maskNode)) })

    private fun maskNode(n: UiNode): UiNode {
        val whole = n.dataSensitive || n.editable
        val f: (String?) -> String? = if (whole) { s -> if (s.isNullOrEmpty()) s else MASK } else ::maskText
        return n.copy(
            text = f(n.text),
            // 입력칸 hint는 앱이 정한 안내문이라 패턴만 지운다(서명에 쓰일 수 있음).
            hint = maskText(n.hint),
            contentDescription = f(n.contentDescription),
            stateDescription = f(n.stateDescription),
            paneTitle = maskText(n.paneTitle),
            children = n.children.map(::maskNode),
        )
    }
}
