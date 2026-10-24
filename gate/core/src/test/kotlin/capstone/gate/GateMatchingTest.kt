package capstone.gate

import capstone.gate.BlockReason.*
import capstone.gate.Fx.app
import capstone.gate.Fx.button
import capstone.gate.Fx.click
import capstone.gate.Fx.group
import capstone.gate.Fx.keyOfText
import capstone.gate.Fx.ref
import capstone.gate.Fx.snap
import capstone.gate.Fx.text
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 매칭 케이스 G1~G8 (D36). */
class GateMatchingTest {
    private fun decide(root: UiNode, targetText: String, req: (UiWindow, String) -> ActionRequest = { w, k -> click(w, k) }): Decision {
        val w = app(root)
        return Fx.gate().check(snap(w), req(w, keyOfText(w, targetText)))
    }

    private fun assertBlocked(expected: BlockReason, d: Decision) {
        assertIs<Decision.Block>(d, "차단되어야 함: $d")
        assertEquals(expected, d.reason, d.toString())
    }

    @Test
    fun `G1 예매하기 버튼 직접 클릭은 차단`() {
        assertBlocked(CONFIRM_NODE_TEXT, decide(group(button("예매하기")), "예매하기"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "예 매 하 기", "예매\u200B하기", "🎫 예매하기\uFE0F", "예매하기!",
            "ＰＡＹ", "Ｐａｙ", "59,800원 결제하기", "카카오페이", "삼성 페이", "좌석 선택 완료",
        ],
    )
    fun `G2 띄어쓰기 전각 이모지 제로폭 변형도 차단`(label: String) {
        assertBlocked(CONFIRM_NODE_TEXT, decide(group(button(label)), label))
    }

    @Test
    fun `G2 NFD로 풀린 한글 자모도 재결합해 차단`() {
        val nfd = java.text.Normalizer.normalize("예매하기", java.text.Normalizer.Form.NFD)
        assertTrue(nfd.length > 4)
        assertBlocked(CONFIRM_NODE_TEXT, decide(group(button(nfd)), nfd))
    }

    @Test
    fun `G3 contentDescription만 있는 확정 버튼 차단`() {
        assertBlocked(CONFIRM_NODE_DESC, decide(group(button(desc = "결제하기")), "결제하기"))
    }

    @Test
    fun `G4 viewId 토큰만 있는 확정 버튼 차단`() {
        for (id in listOf("com.korail.talk:id/btn_reserve", "com.korail.talk:id/btnPayment", "com.korail.talk:id/checkout")) {
            val w = app(group(text("안내"), button(id = id)))
            val k = Fx.key(w) { it.viewId == id }
            assertBlocked(CONFIRM_NODE_ID, Fx.gate().check(snap(w), click(w, k)))
        }
    }

    @Test
    fun `G5 클릭 가능한 조상이 확정 라벨 자식을 가진 경우 위임 차단`() {
        // 컨테이너를 누르면 '예매하기'가 눌린다
        val w = app(Fx.seatScreen())
        val container = Fx.key(w) { it.clickable && it.children.any { c -> c.text == "예매하기" } }
        assertBlocked(CONFIRM_ANCESTOR_DELEGATE, Fx.gate().check(snap(w), click(w, container)))
        // 같은 컨테이너 안 아이콘(라벨 없음)을 눌러도 클릭은 컨테이너가 받는다
        val w2 = app(group(group(UiNode(className = "ImageView"), text("예매하기"), clickable = true)))
        val icon = Fx.key(w2) { it.className == "ImageView" }
        assertBlocked(CONFIRM_ANCESTOR_DELEGATE, Fx.gate().check(snap(w2), click(w2, icon)))
    }

    @Test
    fun `G6 좌표 탭이 확정 노드와 겹치면 차단, 멀면 허용`() {
        val confirm = button("예매하기", b = Bounds(540, 2000, 1080, 2200))
        val near = button("인원", b = Bounds(500, 1990, 560, 2010)) // 중심(530,2000) ± 24px → 겹침
        val far = button("이전", b = Bounds(0, 1000, 300, 1200))
        val w = app(group(confirm, near, far))
        val g = Fx.gate()
        assertBlocked(CONFIRM_GESTURE_OVERLAP, g.check(snap(w), ActionRequest.TapNodeCenter(ref(w, keyOfText(w, "인원")))))
        assertIs<Decision.Allow>(g.check(snap(w), ActionRequest.TapNodeCenter(ref(w, keyOfText(w, "이전")))))
    }

    @Test
    fun `G7 허용목록 밖 패키지 우리 앱 패키지는 차단, 시스템 IME 창은 무시`() {
        val other = app(group(button("검색")), pkg = "com.android.chrome")
        assertBlocked(PACKAGE_NOT_ALLOWED, Fx.gate().check(snap(other), click(other, keyOfText(other, "검색"))))
        val own = app(group(button("검색")), pkg = Fx.OWN)
        assertBlocked(PACKAGE_NOT_ALLOWED, Fx.gate().check(snap(own), click(own, keyOfText(own, "검색"))))

        val w = app(group(button("검색")))
        val d = Fx.gate().check(snap(w, Fx.systemBar(), Fx.ime()), click(w, keyOfText(w, "검색")))
        assertIs<Decision.Allow>(d)

        // IME 창 안의 노드를 대상으로 하면 차단
        val ime = Fx.ime()
        assertBlocked(PACKAGE_NOT_ALLOWED, Fx.gate().check(snap(w, ime), click(ime, "0")))
    }

    @Test
    fun `G7 자기 패키지를 허용목록에 넣는 설정은 거부`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GatePolicy(allowedPackages = setOf(Fx.PKG, Fx.OWN), ownPackage = Fx.OWN)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["조회하기", "예약 조회", "결제수단 안내", "접수 내역", "좌석 자동배정", "승차권 예매", "다음", "출발역", "확인"])
    fun `G8 비확정 노드는 허용(오차단 확인)`(label: String) {
        val d = decide(group(text("열차를 조회합니다"), button(label)), label)
        assertIs<Decision.Allow>(d, "오차단: $label → $d")
    }

    @Test
    fun `G8 부분 일치는 차단하지 않고 신호만 남긴다`() {
        val d = decide(group(button("예약 조회")), "예약 조회")
        assertIs<Decision.Allow>(d)
        assertTrue(d.signals.any { it.startsWith("partial:") })
    }
}
