package capstone.gate

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 중심 탭(D2 폴백) 좌표 겹침 퍼즈. 화면은 그대로이고(경합 없음, 21회차와 다름) 함정만 바꾼다.
 *
 * 불변식(오라클): 탭 여유 사각형과 겹치는 노드가 어느 창에든 있고, 그 노드가 확정 라벨이거나
 * 클릭 가능하면서 서브트리에 확정 라벨을 가지면(G5 위임) gate check는 ALLOW하지 않는다.
 * 창 순서(z-order)는 보지 않는다 — 아래 창이라도 겹치면 위험으로 친다(I-87, fail-closed).
 *
 * 함정 종류:
 *  0 없음 / 1 같은 창 확정 형제가 겹침 / 2 같은 창 클릭 가능 컨테이너가 겹치고 확정 자식은 멀리
 *  3 같은 앱 다른 창(포커스 없는 팝업) 확정 노드가 겹침 / 4 다른 창 클릭 가능 컨테이너 + 먼 확정 자식
 *  5 시스템 창 확정 글자가 겹침 / 6 다른 창 확정 노드가 멀리 있음(겹치지 않음 → 허용되어야 함)
 */
class TapHitTestFuzzTest {
    private val slop = Fx.policy.touchSlopPx
    private val confirmLabels = listOf("예매하기", "결제하기", "구매 확정", "카카오페이", "Pay", "접수하기", "좌석선택완료")
    private val safeLabels = listOf("조회하기", "출발역", "도착역", "KTX 101", "09:00", "일반실", "어른 1명", "이전", null)

    private data class Case(val snap: Snapshot, val targetWindow: UiWindow, val targetKey: String, val trap: Int)

    private fun rect(r: Random, maxW: Int = 400, maxH: Int = 200): Bounds {
        val x = r.nextInt(0, 900)
        val y = r.nextInt(0, 2000)
        return Bounds(x, y, x + r.nextInt(40, maxW), y + r.nextInt(40, maxH))
    }

    /** 탭 여유 사각형과 겹치지 않는 자리. 대상이 화면 위쪽이면 아래, 아니면 위. */
    private fun farFrom(tap: Bounds): Bounds =
        if (tap.top > 1100) Bounds(10, 10, 200, 80) else Bounds(10, 2100, 200, 2180)

    private fun covering(tap: Bounds, r: Random): Bounds =
        Bounds(tap.left - r.nextInt(0, 200), tap.top - r.nextInt(0, 150), tap.right + r.nextInt(0, 200), tap.bottom + r.nextInt(0, 150))

    private fun filler(r: Random, tap: Bounds): UiNode {
        var b = rect(r)
        // 채움 노드는 탭과 겹치지 않게 둔다(겹치면 대상 대신 눌릴 수 있어 오라클이 흐려짐).
        if (b.intersects(tap)) b = farFrom(tap)
        return UiNode(className = "android.widget.TextView", text = safeLabels.random(r), bounds = b, clickable = r.nextInt(4) == 0)
    }

    private fun build(seed: Int): Case {
        val r = Random(seed)
        val trap = r.nextInt(0, 7)
        val tb = rect(r)
        val tap = Bounds.point(tb.centerX, tb.centerY).inflate(slop)
        val target = UiNode(className = "android.widget.TextView", text = "조회하기", bounds = tb, clickable = r.nextBoolean())
        val confirmNode = { b: Bounds -> UiNode(className = "android.widget.Button", text = confirmLabels.random(r), bounds = b, clickable = r.nextBoolean()) }
        val trapContainer = {
            val outer = covering(tap, r)
            // 확정 자식은 컨테이너 안이지만 탭과는 겹치지 않는 구석(컨테이너가 작으면 바깥 먼 곳 — 그래도 서브트리).
            val corner = Bounds(outer.left, outer.bottom - 30, outer.left + 30, outer.bottom)
            val child = confirmNode(if (corner.intersects(tap)) farFrom(tap) else corner)
            UiNode(className = "android.widget.FrameLayout", bounds = outer, clickable = true, children = listOf(child))
        }

        val siblings = MutableList(r.nextInt(0, 5)) { filler(r, tap) }
        when (trap) {
            1 -> siblings += confirmNode(covering(tap, r))
            2 -> siblings += trapContainer()
        }
        siblings.shuffle(r)
        val insertAt = r.nextInt(0, siblings.size + 1)
        val children = siblings.toMutableList().apply { add(insertAt, target) }
        val root = UiNode(className = "android.widget.LinearLayout", bounds = Bounds(0, 0, 1080, 2340), children = children)
        val main = UiWindow(1, WindowType.APPLICATION, Fx.PKG, active = true, focused = true, root = root)
        val targetKey = "0/$insertAt"

        val extra = when (trap) {
            3 -> UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = confirmNode(covering(tap, r)))
            4 -> UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = trapContainer())
            5 -> UiWindow(3, WindowType.SYSTEM, "com.android.systemui", root = UiNode(text = "카카오페이 결제", bounds = covering(tap, r)))
            6 -> UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = confirmNode(farFrom(tap)))
            else -> null
        }
        // 덮는 창은 보통 목록 앞(위쪽 z)에 오지만 순서는 섞는다 — 오라클이 순서를 보지 않으므로.
        val windows = listOfNotNull(extra, main, Fx.systemBar()).shuffled(r)
        return Case(Fx.snap(*windows.toTypedArray()), main, targetKey, trap)
    }

    private fun hazardous(snap: Snapshot, tap: Bounds): Boolean = snap.windows.any { w ->
        w.nodes.any { (k, n) ->
            n.bounds.intersects(tap) && (
                ConfirmMatcher.hardReason(n) != null ||
                    (n.clickable && w.descendantsOf(k).any { d -> w.nodes[d]?.let(ConfirmMatcher::hardReason) != null })
                )
        }
    }

    @Test
    fun `중심 탭 좌표를 덮는 확정 경로는 어느 창이든 ALLOW 0건`() {
        val violations = mutableListOf<String>()
        val hazardByTrap = IntArray(7)
        var safeCases = 0
        var safeAllowed = 0
        for (seed in 110_000 until 113_000) {
            val c = build(seed)
            val node = c.targetWindow.nodes.getValue(c.targetKey)
            val tap = Bounds.point(node.bounds.centerX, node.bounds.centerY).inflate(slop)
            val d = Gate(Fx.policy).check(c.snap, ActionRequest.TapNodeCenter(NodeRef.of(c.targetWindow, c.targetKey)))
            if (hazardous(c.snap, tap)) {
                hazardByTrap[c.trap]++
                if (d is Decision.Allow) violations += "seed=$seed trap=${c.trap}"
            } else {
                safeCases++
                if (d is Decision.Allow) safeAllowed++
            }
        }
        if (violations.isNotEmpty()) {
            val byTrap = violations.groupingBy { it.substringAfter("trap=") }.eachCount().toSortedMap()
            fail("${violations.size}건 위반(함정별 $byTrap), 처음 10건: ${violations.take(10)}")
        }
        for (t in 1..5) assertTrue(hazardByTrap[t] > 100, "함정 $t 위험 사례가 너무 적음: ${hazardByTrap[t]}")
        // 과잉 차단 확인: 겹치는 위험이 없으면 전부 실행돼야 한다(채움 노드는 안전 라벨뿐).
        assertTrue(safeCases > 500, "안전 사례가 너무 적음: $safeCases")
        assertEquals(safeCases, safeAllowed, "위험 없는 탭이 막힘")
    }

    @Test
    fun `CLICK 실패 폴백 구조 - 클릭 가능한 행이 탭을 덮고 확정 버튼을 품으면 탭 차단`() {
        // 행 전체가 클릭 가능하고 안에 '상세'(클릭 실패)와 '예매하기'가 있다. 중심 탭은 '상세'가 안 받으면 행이 받는다.
        val detail = UiNode(className = "android.widget.TextView", text = "상세", bounds = Bounds(20, 500, 200, 560), clickable = true)
        val book = Fx.button("예매하기", b = Bounds(800, 500, 1000, 560))
        val row = Fx.group(detail, book, clickable = true, b = Bounds(0, 480, 1080, 580))
        val w = Fx.app(Fx.group(row, b = Bounds(0, 0, 1080, 2340)))
        val snap = Fx.snap(w)
        val k = Fx.keyOfText(w, "상세")
        assertTrue(Gate(Fx.policy).check(snap, ActionRequest.Click(Fx.ref(w, k))) is Decision.Allow, "클릭은 '상세'만 받으므로 허용")
        val d = Gate(Fx.policy).check(snap, ActionRequest.TapNodeCenter(Fx.ref(w, k)))
        assertTrue(d is Decision.Block && d.reason == BlockReason.CONFIRM_GESTURE_OVERLAP, "탭은 차단: $d")
    }

    @Test
    fun `다른 창 확정 버튼이 탭 지점을 덮으면 차단, 멀리 있으면 허용`() {
        val target = Fx.button("조회하기", b = Bounds(100, 1000, 500, 1100))
        val w = Fx.app(Fx.group(target, b = Bounds(0, 0, 1080, 2340)))
        val k = Fx.keyOfText(w, "조회하기")
        val over = UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = Fx.button("결제하기", b = Bounds(0, 900, 1080, 1200)))
        val far = UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = Fx.button("결제하기", b = Bounds(0, 2000, 1080, 2200)))
        val blocked = Gate(Fx.policy).check(Fx.snap(over, w), ActionRequest.TapNodeCenter(Fx.ref(w, k)))
        assertTrue(blocked is Decision.Block && blocked.reason == BlockReason.CONFIRM_GESTURE_OVERLAP, "덮는 창: $blocked")
        assertTrue(Gate(Fx.policy).check(Fx.snap(far, w), ActionRequest.TapNodeCenter(Fx.ref(w, k))) is Decision.Allow)
    }
}
