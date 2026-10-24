package capstone.service.core

import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.snapshot.PiiFold

/**
 * 덤프 저장 전 기기 안에서 하는 1차 마스킹(I-42). 패턴으로 잡히는 것만 지운다:
 * 이름·주소처럼 패턴이 없는 개인정보는 못 잡으므로, 커밋 전 사람 검토는 그대로 필요하다.
 *
 * - data-sensitive·입력칸 노드: 문자열 필드 전체를 [MASK]로(입력값은 사용자 정보일 수 있다)
 * - 그 밖의 노드: 휴대폰·주민번호·카드번호·이메일·8자리 이상 숫자열을 [MASK]로
 * - 모든 노드: 병원·약국 이름과 진료과 이름을 [MASK]로(D70 ①, 건강정보는 민감정보로 보수 취급)
 * - [healthPackages]에 든 앱(병원·약국 앱) 창: 문자열 필드 전체를 [MASK]로. 의사명·예약 일시·증상은
 *   패턴으로 못 잡으므로 그 앱 화면은 글자를 하나도 남기지 않고 구조(class·viewId·bounds·상태)만 남긴다(D70 ①, I-66).
 * 역 이름·시각·요금·호차/좌석 번호처럼 분류기·gate가 써야 하는 짧은 값은 남긴다.
 *
 * 패턴은 [PiiFold]로 접은 글자에 건다: 전각 숫자·유니코드 대시·특수 공백·보이지 않는 문자로 바뀐 같은 번호도 잡는다(I-84).
 * 보이지 않는 문자는 결과에서도 빠진다.
 */
object DumpMasker {
    const val MASK: String = "[MASK]"

    /** 진료과 이름(긴 것부터: '정형외과'가 '외과'보다 먼저 잡히게). 의료법 표시 과목 기준 + 흔한 표기. */
    private val DEPARTMENTS: List<String> = listOf(
        "정신건강의학과", "소아청소년과", "가정의학과", "재활의학과", "비뇨의학과", "영상의학과", "마취통증의학과",
        "응급의학과", "진단검사의학과", "핵의학과", "직업환경의학과", "방사선종양학과", "병리과", "예방의학과",
        "이비인후과", "산부인과", "정형외과", "신경외과", "성형외과", "흉부외과", "신경과", "피부과", "안과",
        "내과", "외과", "소아과", "한방내과", "치과",
    ).sortedByDescending { it.length }

    private val patterns = listOf(
        Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+"""),
        // 앱이 뒷자리를 가린 형태('900101-1******')도 생년월일·성별이 남으므로 지운다.
        Regex("""\d{6}\s*-\s*[1-8](?:\d{6}|[*●•xX]{6})"""),
        Regex("""\d{4}[\s-]\d{4}[\s-]\d{4}[\s-]\d{3,4}"""),
        Regex("""\d{4}[\s-]\d{6}[\s-]\d{5}"""), // 15자리 카드(4-6-5)
        Regex("""(?:\+?82[\s.-]?0?|0)1[016789][\s.-]?\d{3,4}[\s.-]?\d{4}"""), // 국가번호(+82) 표기 포함
        Regex("""\d{8,}"""),
        // D70: 기관 이름('OO내과의원'처럼 진료과가 붙은 것 포함)을 진료과 단독보다 먼저 지운다.
        Regex("""[가-힣A-Za-z0-9]+(?:한의원|병원|의원|치과|약국|클리닉|보건소)"""),
        // 진료과로 끝나는 낱말 전체('튼튼내과'). 뒤에 한글이 붙으면 다른 낱말일 수 있어('안내과정') 낱말 끝에서만 잡는다.
        Regex(DEPARTMENTS.joinToString("|", "[가-힣A-Za-z0-9]*(?:", ")(?![가-힣])")),
    )

    fun maskText(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        return patterns.fold(PiiFold.stripInvisible(s)) { acc, re -> replaceFolded(acc, re) }
    }

    private fun replaceFolded(s: String, re: Regex): String {
        val matches = re.findAll(PiiFold.fold(s)).toList()
        if (matches.isEmpty()) return s
        val out = StringBuilder(s)
        for (m in matches.asReversed()) out.replace(m.range.first, m.range.last + 1, MASK)
        return out.toString()
    }

    fun mask(snapshot: Snapshot, healthPackages: Set<String> = emptySet()): Snapshot =
        snapshot.copy(
            windows = snapshot.windows.map { w ->
                val health = w.packageName != null && w.packageName in healthPackages
                w.copy(root = w.root?.let { maskNode(it, health) })
            },
        )

    private fun maskNode(n: UiNode, health: Boolean): UiNode {
        if (health) return maskAll(n)
        val whole = n.dataSensitive || n.editable
        val f: (String?) -> String? = if (whole) { s -> if (s.isNullOrEmpty()) s else MASK } else ::maskText
        return n.copy(
            text = f(n.text),
            // 입력칸 hint는 앱이 정한 안내문이라 패턴만 지운다(서명에 쓰일 수 있음).
            hint = maskText(n.hint),
            contentDescription = f(n.contentDescription),
            stateDescription = f(n.stateDescription),
            paneTitle = maskText(n.paneTitle),
            children = n.children.map { maskNode(it, false) },
        )
    }

    private fun maskAll(n: UiNode): UiNode {
        val f: (String?) -> String? = { s -> if (s.isNullOrEmpty()) s else MASK }
        return n.copy(
            text = f(n.text),
            hint = f(n.hint),
            contentDescription = f(n.contentDescription),
            stateDescription = f(n.stateDescription),
            paneTitle = f(n.paneTitle),
            children = n.children.map(::maskAll),
        )
    }
}
