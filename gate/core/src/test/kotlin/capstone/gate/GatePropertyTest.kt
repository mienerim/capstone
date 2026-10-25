package capstone.gate

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 속성 불변식(D36): 확정 라벨이 클릭 경로(대상·클릭 받는 조상·그 서브트리)에 있으면 ALLOW는 0건.
 * 시드는 고정하고 실패 시 메시지에 남긴다(fuzz seed 기록).
 */
class GatePropertyTest {
    private val confirmLabels = listOf("예매하기", "예 매 하 기", "결제하기", "Pay", "구매 확정", "카카오페이", "접수하기")
    private val safeLabels = listOf("조회", "다음", "이전", "출발역", "도착역", "KTX", "좌석 자동배정", "예약 조회", "승차권 예매", null)

    private fun randomTree(r: Random, depth: Int = 0): UiNode {
        val childCount = if (depth >= 4) 0 else r.nextInt(0, 4)
        val label = if (r.nextInt(10) == 0) confirmLabels.random(r) else safeLabels.random(r)
        val x = r.nextInt(0, 1000)
        val y = r.nextInt(0, 2200)
        return UiNode(
            className = if (childCount > 0) "LinearLayout" else "TextView",
            text = label,
            clickable = r.nextInt(3) == 0,
            bounds = Bounds(x, y, x + r.nextInt(20, 300), y + r.nextInt(20, 200)),
            children = List(childCount) { randomTree(r, depth + 1) },
        )
    }

    private fun clickPathHasConfirm(w: UiWindow, key: String): Boolean {
        val effective = w.ancestorsOf(key).firstOrNull { w.nodes[it]?.clickable == true } ?: key
        val path = w.ancestorsOf(key).takeWhile { it != effective } + effective + w.descendantsOf(effective) + key
        return path.any { k -> w.nodes[k]?.let { ConfirmMatcher.hardReason(it) } != null }
    }

    @Test
    fun `무작위 트리 2000개에서 확정 경로 클릭 ALLOW 0건`() {
        var confirmCases = 0
        for (seed in 1..2000) {
            val r = Random(seed)
            val w = UiWindow(1, WindowType.APPLICATION, Fx.PKG, active = true, focused = true, root = randomTree(r))
            val key = w.nodes.keys.random(r)
            val snap = Snapshot(0, listOf(w), screen = "S")
            val gate = Gate(Fx.policy)
            for (req in listOf(
                ActionRequest.Click(NodeRef.of(w, key)),
                ActionRequest.TapNodeCenter(NodeRef.of(w, key)),
            )) {
                val d = gate.check(snap, req)
                if (clickPathHasConfirm(w, key)) {
                    confirmCases++
                    if (d is Decision.Allow) fail("seed=$seed key=$key req=${req.kind}: 확정 경로인데 ALLOW")
                }
            }
        }
        assertTrue(confirmCases > 100, "확정 경로 사례가 너무 적음: $confirmCases")
    }
}
