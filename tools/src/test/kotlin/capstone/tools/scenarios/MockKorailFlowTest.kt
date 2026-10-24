package capstone.tools.scenarios

import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MockKorailFlowTest {
    private fun row(name: String, status: String, clickable: Boolean = true) = UiNode(
        className = "LinearLayout", clickable = clickable, viewId = "${MockKorail.PKG}:id/row_train",
        children = listOf(UiNode(className = "TextView", text = name), UiNode(className = "TextView", text = "일반실 $status")),
    )

    private fun window(vararg rows: UiNode) = UiWindow(
        1, WindowType.APPLICATION, MockKorail.PKG, active = true, focused = true,
        root = UiNode(className = "RecyclerView", viewId = "${MockKorail.PKG}:id/list", children = rows.toList()),
    )

    @Test
    fun `열차 보기는 예약가능한 KTX·일반열차만, SRT는 빠진다`() {
        val w = window(
            row("KTX 101", "예약가능"),
            row("SRT 301", "예약가능"),
            row("KTX 103", "매진", clickable = false),
            row("무궁화호 1201", "예약가능"),
            row("ＳＲＴ 303", "예약가능"),
        )
        assertEquals(listOf("KTX 101", "무궁화호 1201"), MockKorailFlow.trainOptions(w))
    }

    @Test
    fun `SRT 판정은 라벨 앞머리 정규화로`() {
        assertFalse(MockKorailFlow.isServedTrain("SRT 301"))
        assertFalse(MockKorailFlow.isServedTrain(" s r t 301"))
        assertTrue(MockKorailFlow.isServedTrain("KTX-산천 105"))
        assertTrue(MockKorailFlow.isServedTrain("ITX-새마을 1001"))
    }
}
