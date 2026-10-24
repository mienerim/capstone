package capstone.service.core

import capstone.gate.ActionRequest
import capstone.gate.Bounds
import capstone.gate.ConfirmMatcher
import capstone.gate.ExecResult
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.NodeRef
import capstone.gate.RecordingDecisionSink
import capstone.gate.WindowType
import capstone.gate.exec.GatedExecutor
import capstone.gate.exec.RawActuator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gate 판정과 실제 실행 사이에 화면이 바뀌는 경합(TOCTOU) 퍼즈(I-86).
 *
 * gate는 `before` 스냅샷으로 허용하고, [FrameActuator]는 그 뒤 실기기 노드를 다시 읽어 누른다.
 * 그 틈에 확정 버튼이 생기는 방식 7가지를 섞는다: 대상 라벨 교체·대상 안에 확정 자식·대상 위를 덮는
 * 형제·대상 위를 덮는 새 창·대상 이동·무관한 글자 변경·대상 소멸. 불변식은 하나다:
 * **실행 순간의 실기기 화면에서 확정 노드가 클릭(performAction)이나 탭을 받지 않는다.**
 * 클릭은 노드 자신과 그 서브트리(자식 라벨이 곧 버튼 라벨인 흔한 구조, G5)를, 탭은 탭 좌표를
 * 덮는 모든 창의 노드를 본다.
 */
class FrameActuatorRaceFuzzTest {

    private enum class Race { NONE, RELABEL_TARGET, CHILD_CONFIRM, SIBLING_OVER, OVERLAY_WINDOW, MOVE_TARGET, UNRELATED_TEXT, KILL_TARGET }

    private enum class Mode { CLICK, CLICK_FAILS_THEN_TAP, TAP }

    private val safeLabels = listOf("조회하기", "다음", "이전", "출발역", "도착역", "서울", "부산", "대전", "KTX 101", "좌석 선택", "인원 1명", "날짜 변경")
    private val confirmLabels = listOf("예매하기", "결제하기", "예매 확정", "구매하기")

    private class World(val windows: MutableList<FakeWindow>, val root: FakeNode, val rows: List<FakeNode>)

    /** 루트 → 행 버튼 n개. 절반은 라벨이 자식 TextView에, 나머지는 버튼 자신에 있다. */
    private fun Random.world(): World {
        val rows = (0 until nextInt(3, 9)).map { i ->
            val b = Bounds(40, 200 + i * 220, 1040, 400 + i * 220)
            val label = safeLabels.random(this)
            if (nextBoolean()) {
                FakeNode(
                    className = "android.widget.LinearLayout", clickable = true, bounds = b,
                    kids = mutableListOf(FakeNode(text = label, bounds = Bounds(b.left + 20, b.top + 20, b.right - 20, b.bottom - 20))),
                )
            } else {
                FakeNode(className = "android.widget.Button", text = label, clickable = true, bounds = b)
            }
        }
        val root = FakeNode(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400), kids = rows.toMutableList())
        return World(mutableListOf(FakeWindow(1, root)), root, rows)
    }

    private fun Random.race(w: World, target: FakeNode, race: Race) {
        val tb = target.bounds
        val over = Bounds(tb.centerX - 60, tb.centerY - 30, tb.centerX + 60, tb.centerY + 30)
        val confirm = confirmLabels.random(this)
        when (race) {
            Race.NONE -> Unit
            Race.RELABEL_TARGET -> if (target.kids.isEmpty()) target.text = confirm else target.contentDescription = confirm
            Race.CHILD_CONFIRM -> target.kids += FakeNode(text = confirm, bounds = over)
            Race.SIBLING_OVER -> w.root.kids += FakeNode(className = "android.widget.Button", text = confirm, clickable = true, bounds = over)
            Race.OVERLAY_WINDOW -> {
                val dialog = FakeNode(
                    className = "android.widget.FrameLayout", bounds = Bounds(0, tb.top - 100, 1080, tb.bottom + 100),
                    kids = mutableListOf(FakeNode(className = "android.widget.Button", text = confirm, clickable = true, bounds = over)),
                )
                // 같은 앱의 대화상자 창이 맨 위(목록 앞)에 뜬다.
                w.windows.add(0, FakeWindow(2, dialog, type = if (nextBoolean()) WindowType.APPLICATION else WindowType.SYSTEM, active = false, focused = false))
            }
            Race.MOVE_TARGET -> target.bounds = Bounds(tb.left, tb.top + 300, tb.right, tb.bottom + 300)
            Race.UNRELATED_TEXT -> (w.rows - target).randomOrNull(this)?.let { it.text = "${it.text ?: ""}!" } ?: Unit
            Race.KILL_TARGET -> target.alive = false
        }
    }

    /** 첫 raw 호출 직전에 경합을 일으킨다(gate check는 이미 끝난 시점). */
    private class Racing(private val inner: RawActuator, private val onFirst: () -> Unit) : RawActuator {
        private var fired = false
        private fun <T> once(body: () -> T): T {
            if (!fired) {
                fired = true
                onFirst()
            }
            return body()
        }
        override fun click(ref: NodeRef) = once { inner.click(ref) }
        override fun setText(ref: NodeRef, text: String) = once { inner.setText(ref, text) }
        override fun scroll(ref: NodeRef, forward: Boolean) = once { inner.scroll(ref, forward) }
        override fun imeEnter(ref: NodeRef) = once { inner.imeEnter(ref) }
        override fun tapCenter(ref: NodeRef) = once { inner.tapCenter(ref) }
        override fun back() = once { inner.back() }
    }

    private fun FakeNode.all(): List<FakeNode> = listOf(this) + kids.filterNotNull().flatMap { it.all() }
    private fun FakeNode.isConfirm() = alive && ConfirmMatcher.hardReason(TreeCapture.shallow(this)) != null

    /** 위반 설명 목록(비면 통과). */
    private fun violations(w: World, device: FakeDevice): List<String> {
        val out = mutableListOf<String>()
        val live = w.windows.mapNotNull { it.root() as FakeNode? }.flatMap { it.all() }
        for (n in live) {
            if (n.performed.any { it == NodeOp.Click || it == NodeOp.ImeEnter } && n.all().any { it.isConfirm() }) {
                out += "click_on_confirm:${n.text ?: n.contentDescription}"
            }
        }
        for ((x, y) in device.taps) {
            live.firstOrNull { it.isConfirm() && it.visibleToUser && it.bounds.intersects(Bounds.point(x, y)) }
                ?.let { out += "tap_on_confirm:$x,$y:${it.text}" }
        }
        return out
    }

    private data class Outcome(val race: Race, val mode: Mode, val result: ExecResult, val violations: List<String>)

    private fun runOne(seed: Int): Outcome {
        val rnd = Random(seed)
        val w = rnd.world()
        val device = FakeDevice()
        val holder = FrameHolder { TreeCapture().capture(w.windows.toList(), 0) }
        val race = Race.entries.random(rnd)
        val mode = Mode.entries.random(rnd)
        val targetIndex = rnd.nextInt(w.rows.size)
        val target = w.rows[targetIndex]
        if (mode == Mode.CLICK_FAILS_THEN_TAP) target.performResult = false

        val actuator = Racing(FrameActuator(holder, device)) { rnd.race(w, target, race) }
        val gate = Gate(GatePolicy(setOf(GatePolicy.MOCK_APP), ownPackage = "capstone.app"), RecordingDecisionSink())
        val exec = GatedExecutor(gate, actuator, holder)

        val before = holder.capture()
        val ref = NodeRef.of(before.window(1)!!, "0/$targetIndex")
        val req = if (mode == Mode.TAP) {
            ActionRequest.TapNodeCenter(ref, GatePolicy.MOCK_APP, null)
        } else {
            ActionRequest.Click(ref, GatePolicy.MOCK_APP, null)
        }
        val result = exec.execute(before, req)
        return Outcome(race, mode, result, violations(w, device))
    }

    @Test
    fun `gate 판정 뒤 화면이 바뀌어도 확정 노드는 클릭도 탭도 받지 않는다`() {
        val outcomes = (90000 until 93000).map(::runOne)
        val bad = outcomes.filter { it.violations.isNotEmpty() }
        val summary = bad.groupingBy { "${it.race}/${it.mode}" }.eachCount()
        assertTrue(bad.isEmpty(), "위반 ${bad.size}건 $summary, 예: ${bad.take(3).map { it.violations }}")
        // 모든 경합 × 모드 조합이 실제로 나왔는지(퍼즈가 한쪽으로 쏠리지 않았는지).
        assertEquals(Race.entries.size * Mode.entries.size, outcomes.map { it.race to it.mode }.distinct().size)
    }

    @Test
    fun `화면이 그대로면 세 경로 모두 실행된다 - 과잉 차단 없음`() {
        val clean = (90000 until 93000).map(::runOne).filter { it.race == Race.NONE }
        assertTrue(clean.size > 100)
        val notDone = clean.filter { it.result != ExecResult.Done }
        assertTrue(notDone.isEmpty(), "그대로인 화면에서 실행 안 됨 ${notDone.size}건: ${notDone.take(3).map { "${it.mode}:${it.result}" }}")
    }
}
