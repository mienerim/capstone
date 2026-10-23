package capstone.scenarios

import capstone.agent.Flow
import capstone.agent.NodeMatcher
import capstone.agent.Screens
import capstone.agent.flow

/**
 * 목업 앱 열차 검색 시나리오: 공지 팝업 → 출발·도착 입력 → 조회 → 열차 묻기 → 열차 선택 → 좌석 화면에서 정지.
 * 슬롯 이름은 D30 v1(origin·destination)을 따르고, 열차는 화면 보기에서 사용자가 고른다(train).
 * 날짜·인원·열차 종류 입력은 목업 4화면(⑦)이 생기면 단계로 추가한다.
 */
object MockKorailFlow {
    const val SLOT_ORIGIN = "origin"
    const val SLOT_DESTINATION = "destination"
    const val SLOT_TRAIN = "train"

    val flow: Flow = flow("mock_korail_search", setOf(MockKorail.PKG)) {
        popup(NodeMatcher(id = "btn_popup_close", clickable = true))
        popup(NodeMatcher(text = "닫기", clickable = true))

        type(Screens.SEARCH_FORM, NodeMatcher(id = "dep_station")) { it.getValue(SLOT_ORIGIN) }
        type(Screens.SEARCH_FORM, NodeMatcher(id = "arr_station")) { it.getValue(SLOT_DESTINATION) }
        // 검색 결과는 오래 걸릴 수 있다(상세 ① 4절 초기값 10~15초).
        click(Screens.SEARCH_FORM, then = Screens.RESULT_LIST, timeoutMs = 15_000, query = true, target = NodeMatcher(text = "조회하기", clickable = true))
        ask(Screens.RESULT_LIST, SLOT_TRAIN, "어느 열차로 할까요?") { _, w ->
            w.nodes.values
                .filter { it.clickable && it.viewId?.endsWith(":id/row_train") == true }
                .filter { row -> row.children.any { it.text?.contains("예약가능") == true } }
                .mapNotNull { row -> row.children.firstOrNull()?.text }
        }
        click(Screens.RESULT_LIST, then = Screens.DETAIL) { NodeMatcher(text = it.getValue(SLOT_TRAIN)) }
        finish(Screens.DETAIL) {
            "${it[SLOT_ORIGIN]}→${it[SLOT_DESTINATION]} ${it[SLOT_TRAIN]} 좌석 배정까지 했습니다. '예매하기'부터는 직접 눌러 주세요."
        }
    }
}
