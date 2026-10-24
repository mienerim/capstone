package capstone.agent

import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenClassifierTest {
    private val pkg = "com.korail.talk"

    private fun text(t: String, visible: Boolean = true) = UiNode(className = "android.widget.TextView", text = t, visible = visible)
    private fun button(t: String) = UiNode(className = "android.widget.Button", text = t, clickable = true)
    private fun edit(hint: String, t: String? = null) = UiNode(className = "android.widget.EditText", hint = hint, text = t, editable = true, clickable = true)
    private fun screen(vararg c: UiNode, p: String = pkg) = Snapshot(
        timestampMs = 0,
        windows = listOf(
            UiWindow(1, WindowType.APPLICATION, p, active = true, focused = true, root = UiNode(className = "FrameLayout", children = c.toList())),
            UiWindow(2, WindowType.SYSTEM, "com.android.systemui", root = text("12:00")),
        ),
    )

    private val search = ScreenSignature(
        Screens.SEARCH_FORM, setOf(pkg),
        mustHave = listOf(NodeMatcher(text = "조회하기", clickable = true), NodeMatcher(text = "출발역", editable = true)),
        mustNotHave = listOf(NodeMatcher(text = "공지사항")),
    )
    private val result = ScreenSignature(
        Screens.RESULT_LIST, setOf(pkg),
        mustHave = listOf(NodeMatcher(textContains = "KTX")),
        weighted = listOf(NodeMatcher(text = "선택") to 1.0),
    )
    private val popup = ScreenSignature(Screens.POPUP_KNOWN, setOf(pkg), mustHave = listOf(NodeMatcher(text = "공지사항")))
    private val classifier = ScreenClassifier(listOf(search, result, popup))

    @Test
    fun `must_have가 모두 있으면 그 화면`() {
        val c = classifier.classify(screen(edit("출발역"), edit("도착역"), button("조회하기")))
        assertEquals(Screens.SEARCH_FORM, c.screen)
        assertEquals("matched", c.reason)
    }

    @Test
    fun `입력값이 들어간 EditText는 hint가 아니라 text로 본다`() {
        // 사용자가 '서울'을 넣으면 text=서울. 출발역 라벨이 hint로만 남아 있으면 must_have가 깨져야 정상이다.
        assertEquals(Screens.UNKNOWN, classifier.classify(screen(edit("출발역", t = "서울"), button("조회하기"))).screen)
    }

    @Test
    fun `must_not_have가 보이면 제외`() {
        val c = classifier.classify(screen(edit("출발역"), button("조회하기"), text("공지사항")))
        assertEquals(Screens.POPUP_KNOWN, c.screen)
        assertTrue(Screens.SEARCH_FORM !in c.scores)
    }

    @Test
    fun `1·2위 점수 차가 작으면 UNKNOWN`() {
        // SEARCH_FORM(2) 과 RESULT_LIST(1 + 선택 1 = 2) 동점.
        val c = classifier.classify(screen(edit("출발역"), button("조회하기"), text("KTX 101"), button("선택")))
        assertEquals(Screens.UNKNOWN, c.screen)
        assertTrue(c.reason.startsWith("ambiguous"))
    }

    @Test
    fun `아무 서명도 안 맞으면 UNKNOWN`() {
        assertEquals(Screens.UNKNOWN, classifier.classify(screen(text("로그인"))).screen)
    }

    @Test
    fun `대상 패키지가 아니면 FOREIGN_APP, 빈 트리·창 없음은 UNKNOWN`() {
        assertEquals(Screens.FOREIGN_APP, classifier.classify(screen(text("KTX"), p = "com.kakao.talk")).screen)
        val empty = screen().let { s -> s.copy(windows = s.windows.map { if (it.id == 1) it.copy(root = null) else it }) }
        assertEquals(Screens.UNKNOWN, classifier.classify(empty).screen)
        assertEquals(Screens.UNKNOWN, classifier.classify(Snapshot(0, emptyList())).screen)
    }

    @Test
    fun `띄어쓰기·전각 차이는 같은 라벨로 본다, 안 보이는 노드는 무시`() {
        assertEquals(Screens.SEARCH_FORM, classifier.classify(screen(edit("출 발 역"), button("조회　하기"))).screen)
        assertEquals(Screens.UNKNOWN, classifier.classify(screen(text("KTX 1", visible = false))).screen)
    }

    @Test
    fun `label은 스냅샷 screen을 채운다`() {
        val s = classifier.label(screen(text("KTX 101")))
        assertEquals(Screens.RESULT_LIST, s.screen)
    }

    @Test
    fun `잘못된 서명 정의는 거부`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenSignature("X", setOf(pkg), mustHave = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ScreenSignature(Screens.UNKNOWN, setOf(pkg), listOf(NodeMatcher(text = "a"))) }
        assertThrows(IllegalArgumentException::class.java) { ScreenClassifier(listOf(popup, popup)) }
        assertThrows(IllegalArgumentException::class.java) { NodeMatcher(clickable = true) }
    }
}
