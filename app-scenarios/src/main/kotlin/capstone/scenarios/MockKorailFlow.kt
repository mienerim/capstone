package capstone.scenarios

import capstone.agent.Flow
import capstone.agent.NodeMatcher
import capstone.agent.Screens
import capstone.agent.flow
import capstone.gate.LabelNormalizer
import capstone.gate.UiWindow

/**
 * 목업 앱 열차 검색 시나리오: 공지 팝업 → 출발·도착 입력 → 조회 → 열차 묻기 → 열차 선택 → 좌석 화면에서 정지.
 * 슬롯 이름은 D30 v1(origin·destination)을 따르고, 열차는 화면 보기에서 사용자가 고른다(train).
 * 날짜·인원·열차 종류 입력은 목업 4화면(⑦)이 생기면 단계로 추가한다.
 * 열차 보기는 KTX·일반열차만 낸다(D4·D17, 10-26 안건 8 'SRT 걸러내기' 기본값). 코레일+ 조회 결과에
 * SRT가 섞이는지는 녹화로 확인한다.
 */
object MockKorailFlow {
    const val SLOT_ORIGIN = "origin"
    const val SLOT_DESTINATION = "destination"
    const val SLOT_TRAIN = "train"

    /** 이 흐름이 화면에 넣는 사용자 슬롯. 날짜·시간·열차 종류·인원은 아직 단계가 없어 복창이 '직접 확인'으로 알린다(I-94). */
    val APPLIED_SLOTS: Set<String> = setOf(SLOT_ORIGIN, SLOT_DESTINATION)

    /** SRT는 다른 운영사·결제 흐름이라 범위 밖. 라벨 앞머리로 판단한다(정규화 후 'srt'로 시작). */
    fun isServedTrain(label: String): Boolean = !LabelNormalizer.normalize(label).startsWith("srt")

    /** 결과 화면에서 고를 수 있는 열차 라벨: 클릭 가능 + '예약가능' + SRT 제외. */
    fun trainOptions(w: UiWindow): List<String> = w.nodes.values
        .filter { it.clickable && it.viewId?.endsWith(":id/row_train") == true }
        .filter { row -> row.children.any { it.text?.contains("예약가능") == true } }
        .mapNotNull { row -> row.children.firstOrNull()?.text }
        .filter(::isServedTrain)

    val flow: Flow = flow("mock_korail_search", setOf(MockKorail.PKG)) {
        popup(NodeMatcher(id = "btn_popup_close", clickable = true))
        popup(NodeMatcher(text = "닫기", clickable = true))

        type(Screens.SEARCH_FORM, NodeMatcher(id = "dep_station")) { it.getValue(SLOT_ORIGIN) }
        type(Screens.SEARCH_FORM, NodeMatcher(id = "arr_station")) { it.getValue(SLOT_DESTINATION) }
        // 검색 결과는 오래 걸릴 수 있다(상세 ① 4절 초기값 10~15초).
        click(Screens.SEARCH_FORM, then = Screens.RESULT_LIST, timeoutMs = 15_000, query = true, target = NodeMatcher(text = "조회하기", clickable = true))
        ask(Screens.RESULT_LIST, SLOT_TRAIN, "어느 열차로 할까요?") { _, w -> trainOptions(w) }
        click(Screens.RESULT_LIST, then = Screens.DETAIL) { NodeMatcher(text = it.getValue(SLOT_TRAIN)) }
        finish(Screens.DETAIL) {
            "${it[SLOT_ORIGIN]}→${it[SLOT_DESTINATION]} ${it[SLOT_TRAIN]} 좌석 배정까지 했습니다. '예매하기'부터는 직접 눌러 주세요."
        }
    }
}
