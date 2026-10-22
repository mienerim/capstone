package capstone.gate.exec

import capstone.gate.ActionRequest
import capstone.gate.BlockReason
import capstone.gate.Bounds
import capstone.gate.ExecResult
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GatedExecutorTest {
    private val policy = GatePolicy(setOf(GatePolicy.KORAIL_PLUS), ownPackage = "capstone.app")

    private class FakeActuator(var clickOk: Boolean = true) : RawActuator {
        val calls = mutableListOf<String>()
        override fun click(ref: NodeRef) = clickOk.also { calls += "click:${ref.key}" }
        override fun setText(ref: NodeRef, text: String) = true.also { calls += "set:${ref.key}" }
        override fun scroll(ref: NodeRef, forward: Boolean) = true.also { calls += "scroll" }
        override fun imeEnter(ref: NodeRef) = true.also { calls += "enter" }
        override fun tapCenter(ref: NodeRef) = true.also { calls += "tap:${ref.key}" }
        override fun back() = true.also { calls += "back" }
    }

    private fun window(vararg c: UiNode) = UiWindow(
        1, WindowType.APPLICATION, GatePolicy.KORAIL_PLUS, active = true, focused = true,
        root = UiNode(className = "Frame", children = c.toList()),
    )

    private val search = UiNode(text = "조회하기", clickable = true, bounds = Bounds(0, 0, 300, 100))
    private val confirm = UiNode(text = "예매하기", clickable = true, bounds = Bounds(600, 2000, 1000, 2200))
    private val w = window(search, confirm)
    private val snap = Snapshot(0, listOf(w), screen = "S2")

    @Test
    fun `확정 버튼은 raw 실행기까지 가지 않는다`() {
        val act = FakeActuator()
        val r = GatedExecutor(Gate(policy), act) { snap }.execute(snap, ActionRequest.Click(NodeRef.of(w, "0/1")))
        assertIs<ExecResult.Blocked>(r)
        assertEquals(BlockReason.CONFIRM_NODE_TEXT, r.decision.reason)
        assertEquals(emptyList(), act.calls)
    }

    @Test
    fun `CLICK 실패 시 같은 노드 중심 탭 1회 폴백`() {
        val act = FakeActuator(clickOk = false)
        val r = GatedExecutor(Gate(policy), act) { snap }.execute(snap, ActionRequest.Click(NodeRef.of(w, "0/0")))
        assertEquals(ExecResult.Done, r)
        assertEquals(listOf("click:0/0", "tap:0/0"), act.calls)
    }

    @Test
    fun `실행 후 다른 앱이 뜨면 Blocked를 돌려주고 잠긴다`() {
        val act = FakeActuator()
        val gate = Gate(policy)
        val other = Snapshot(1, listOf(w.copy(id = 2, packageName = "com.evil")), screen = null)
        val r = GatedExecutor(gate, act) { other }.execute(snap, ActionRequest.Click(NodeRef.of(w, "0/0")))
        assertIs<ExecResult.Blocked>(r)
        assertEquals(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, gate.lockedReason)
    }
}
