package capstone.scenarios

import capstone.agent.NodeMatcher
import capstone.agent.ScreenClassifier
import capstone.agent.ScreenSignature
import capstone.agent.Screens
import capstone.gate.GatePolicy

/**
 * 목업 앱(mock-app) 화면 서명. 텍스트 라벨 위주로 쓰고 viewId는 보조(코레일+는 난독화 가능성, 상세 ② 1절).
 * 코레일+ 실제 서명은 10-27 덤프 뒤 KorailPlus.kt로 따로 만든다.
 */
object MockKorail {
    const val PKG: String = GatePolicy.MOCK_APP

    private val popupMarker = NodeMatcher(id = "popup")

    val signatures: List<ScreenSignature> = listOf(
        ScreenSignature(
            Screens.POPUP_KNOWN, setOf(PKG),
            mustHave = listOf(popupMarker, NodeMatcher(text = "공지사항")),
            weighted = listOf(NodeMatcher(text = "닫기", clickable = true) to 1.0),
        ),
        ScreenSignature(
            Screens.SEARCH_FORM, setOf(PKG),
            // 입력칸은 값이 들어가면 hint 라벨이 text로 가려지므로 id로 잡는다.
            mustHave = listOf(NodeMatcher(text = "조회하기", clickable = true), NodeMatcher(id = "dep_station", editable = true)),
            mustNotHave = listOf(popupMarker),
            weighted = listOf(NodeMatcher(id = "arr_station", editable = true) to 1.0),
        ),
        ScreenSignature(
            Screens.RESULT_LIST, setOf(PKG),
            mustHave = listOf(NodeMatcher(id = "list"), NodeMatcher(id = "row_train")),
            weighted = listOf(NodeMatcher(textContains = "→") to 0.5),
        ),
        ScreenSignature(
            Screens.DETAIL, setOf(PKG),
            mustHave = listOf(NodeMatcher(text = "좌석 자동배정 결과")),
            weighted = listOf(NodeMatcher(text = "예매하기") to 1.0, NodeMatcher(id = "seat") to 1.0),
        ),
        ScreenSignature(
            Screens.PAYMENT_GATE, setOf(PKG),
            // '결제 예정 금액' 같은 안내문(M6 유사 문구)과 겹치지 않게 '결제금액'으로 좁힌다.
            mustHave = listOf(NodeMatcher(textContains = "결제금액")),
            weighted = listOf(NodeMatcher(text = "카드번호") to 1.0, NodeMatcher(text = "결제하기") to 1.0),
        ),
    )

    fun classifier(): ScreenClassifier = ScreenClassifier(signatures)
}
