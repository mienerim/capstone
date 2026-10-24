package capstone.gate

import java.text.Normalizer

/**
 * 라벨 정규화(D36 4.3): NFKC → 글자·숫자 외 전부 제거(공백·구두점·제로폭·FE0F·이모지) → 소문자.
 * NFKC가 정준 결합까지 하므로 NFD로 풀린 한글 자모도 완성형으로 돌아온다. 매칭 전용이고 로그는 원문을 쓴다.
 * 한글 채움 문자(U+115F·U+1160·U+3164·U+FFA0)는 보이지 않지만 유니코드상 글자(Lo)라 따로 지운다
 * ('예매ㅤ하기' 우회, LabelMutationFuzzTest·I-78). 지운 뒤 NFC를 한 번 더 한다: NFD 자모 사이에
 * 제로폭 문자가 끼면 첫 NFKC에서 결합되지 않기 때문이다.
 */
object LabelNormalizer {
    private val HANGUL_FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetterOrDigit(cp) && cp !in HANGUL_FILLERS) sb.appendCodePoint(Character.toLowerCase(cp))
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }
}

enum class MatchStrength { NONE, PARTIAL, HARD }

/**
 * 확정·결제 라벨 판정. HARD = 하드 정지, PARTIAL = 신호만(차단 안 함, D36 '부분 일치는 정지 추가만').
 * 사전은 실기기 확인 전 후보(상세 ② 4.3)이며 10-27 덤프로 보정한다.
 */
object ConfirmMatcher {
    /** '동사+확정' 패턴의 동사. 접미사가 붙어야 하드(예: '예매'는 탭 이름일 수 있음). */
    private val VERBS = listOf("예매", "예약", "접수", "결제", "구매", "주문", "송금", "구독", "가입", "발권", "신청", "결재")
    private val VERB_SUFFIXES = listOf("하기", "확정", "완료", "진행", "계속", "할게요", "합니다")

    /** 라벨이 이것으로 끝나면 접미사 없이도 하드. '간편결제'·'카카오페이'·'59800원결제' 등. */
    private val BARE_TAILS = listOf(
        "결제", "구매", "주문", "송금", "구독", "페이", "pay", "좌석선택완료", "멤버십가입", "무료체험", "무료체험시작",
        "buy", "buynow", "purchase", "checkout", "ordernow", "placeorder", "booknow", "subscribe",
    )

    /** 포함만 하면 PARTIAL 신호. */
    private val PARTIAL_ROOTS = listOf(
        "예매", "예약", "접수", "결제", "구매", "주문", "송금", "구독", "멤버십", "이용권", "무료체험", "자동결제",
        "페이", "pay", "확정", "발권", "카드",
    )

    /**
     * 결제 화면 판정 문구(어느 노드든 포함하면). 이 화면에서는 모든 조작을 멈춘다.
     * '결제금액'은 좌석 화면(S3)에도 나올 수 있어 넣지 않았다(오정지 M6).
     */
    private val PAYMENT_SCREEN_MARKERS = listOf("카드번호", "결제비밀번호", "결제수단선택", "할부개월", "카드사선택", "카드유효기간")

    /** 일반 긍정 버튼. 단독으로는 안전하지만 주변 문맥이 결제·확정이면 위임으로 본다(T2). */
    private val GENERIC_AFFIRMATIVES = setOf(
        "확인", "예", "네", "계속", "계속하기", "동의", "동의하기", "진행", "진행하기", "다음", "ok", "yes", "continue", "next", "confirm",
    )
    private val CONTEXT_ROOTS = listOf("결제", "구매", "송금", "주문", "예매", "확정", "pay")

    /** viewId 토큰(언더스코어·camelCase 분리). 난독화되면 아무것도 안 걸린다(낮음~중 위험). */
    private val ID_TOKENS = setOf("pay", "payment", "purchase", "buy", "checkout", "reserve", "reservation", "book", "booking", "order", "confirm", "ticketing")

    fun matchLabel(raw: String?): MatchStrength {
        val n = LabelNormalizer.normalize(raw)
        if (n.isEmpty()) return MatchStrength.NONE
        if (BARE_TAILS.any { n.endsWith(it) }) return MatchStrength.HARD
        if (VERBS.any { v -> VERB_SUFFIXES.any { s -> n.endsWith(v + s) } }) return MatchStrength.HARD
        if (PARTIAL_ROOTS.any { n.contains(it) }) return MatchStrength.PARTIAL
        return MatchStrength.NONE
    }

    fun idTokens(viewId: String?): List<String> {
        if (viewId.isNullOrBlank()) return emptyList()
        val local = viewId.substringAfter(":id/")
        return local.split('_', '-', '.', '/')
            .flatMap { it.split(Regex("(?<=[a-z0-9])(?=[A-Z])")) }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
    }

    fun matchId(viewId: String?): Boolean = idTokens(viewId).any { it in ID_TOKENS }

    fun isGenericAffirmative(raw: String?): Boolean = LabelNormalizer.normalize(raw) in GENERIC_AFFIRMATIVES

    fun isConfirmContext(raw: String?): Boolean {
        val n = LabelNormalizer.normalize(raw)
        return n.isNotEmpty() && CONTEXT_ROOTS.any { n.contains(it) }
    }

    fun isPaymentScreenMarker(raw: String?): Boolean {
        val n = LabelNormalizer.normalize(raw)
        return n.isNotEmpty() && PAYMENT_SCREEN_MARKERS.any { n.contains(it) }
    }

    /** 노드 하나의 하드 신호. text/hint → TEXT, desc/state → DESC, viewId → ID 순. */
    fun hardReason(node: UiNode): BlockReason? = when {
        matchLabel(node.text) == MatchStrength.HARD || matchLabel(node.hint) == MatchStrength.HARD ->
            BlockReason.CONFIRM_NODE_TEXT
        matchLabel(node.contentDescription) == MatchStrength.HARD ||
            matchLabel(node.stateDescription) == MatchStrength.HARD -> BlockReason.CONFIRM_NODE_DESC
        matchId(node.viewId) -> BlockReason.CONFIRM_NODE_ID
        else -> null
    }

    fun hasPartial(node: UiNode): Boolean =
        listOf(node.text, node.hint, node.contentDescription, node.stateDescription)
            .any { matchLabel(it) == MatchStrength.PARTIAL }

    fun labelsOf(node: UiNode): List<String> =
        listOfNotNull(node.text, node.hint, node.contentDescription, node.stateDescription)

    fun isPaymentScreen(window: UiWindow): Boolean =
        window.nodes.values.any { n -> labelsOf(n).any { isPaymentScreenMarker(it) } }
}
