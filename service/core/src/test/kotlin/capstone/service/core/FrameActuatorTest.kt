package capstone.service.core

import capstone.gate.ActionRequest
import capstone.gate.Bounds
import capstone.gate.ExecResult
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.NodeRef
import capstone.gate.RecordingDecisionSink
import capstone.gate.exec.GatedExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameActuatorTest {

    private class Rig(root: FakeNode = searchScreen()) {
        val screen = root
        val device = FakeDevice()
        val holder = FrameHolder { TreeCapture().capture(listOf(FakeWindow(1, screen)), 0) }
        val actuator = FrameActuator(holder, device)

        fun ref(key: String): NodeRef {
            val s = holder.capture()
            return NodeRef.of(s.window(1)!!, key)
        }
    }

    @Test
    fun `캡처한 노드 그대로면 performAction을 부른다`() {
        val r = Rig()
        val ref = r.ref("0/2")
        assertTrue(r.actuator.click(ref))
        assertEquals(listOf<NodeOp>(NodeOp.Click), (r.screen.kids[2] as FakeNode).performed)
        assertTrue(r.actuator.setText(r.ref("0/1"), "부산"))
        assertEquals(NodeOp.SetText("부산"), (r.screen.kids[1] as FakeNode).performed.single())
    }

    @Test
    fun `캡처 전이면 아무것도 누르지 않는다`() {
        val r = Rig()
        assertFalse(r.actuator.click(NodeRef(1, "0/2", "x")))
    }

    @Test
    fun `낡은 treeHash면 실행하지 않는다`() {
        val r = Rig()
        val ref = r.ref("0/2")
        (r.screen.kids[0] as FakeNode).text = "다른 화면"
        r.holder.capture()
        assertFalse(r.actuator.click(ref))
        assertTrue((r.screen.kids[2] as FakeNode).performed.isEmpty())
    }

    @Test
    fun `refresh 뒤 라벨이 바뀌었으면 실행하지 않는다 - 행동 재바인딩`() {
        val r = Rig()
        val ref = r.ref("0/2")
        val button = r.screen.kids[2] as FakeNode
        button.contentDescription = "예매하기" // 캡처와 실행 사이에 같은 자리 버튼이 바뀜
        assertFalse(r.actuator.click(ref))
        assertTrue(button.performed.isEmpty())
    }

    @Test
    fun `노드가 사라졌으면 false`() {
        val r = Rig()
        val ref = r.ref("0/2")
        (r.screen.kids[2] as FakeNode).alive = false
        assertFalse(r.actuator.click(ref))
    }

    @Test
    fun `중심 탭은 refresh한 노드 bounds 중심으로만 한다`() {
        val r = Rig()
        assertTrue(r.actuator.tapCenter(r.ref("0/2")))
        assertEquals(listOf(150 to 450), r.device.taps)
    }

    @Test
    fun `bounds가 비면 탭하지 않는다`() {
        val root = searchScreen().apply { (kids[2] as FakeNode).bounds = Bounds.EMPTY }
        val r = Rig(root)
        assertFalse(r.actuator.tapCenter(r.ref("0/2")))
        assertTrue(r.device.taps.isEmpty())
    }

    @Test
    fun `GatedExecutor에 끼우면 확정 버튼은 실기기 노드까지 가지 않는다`() {
        val confirm = FakeNode(className = "android.widget.Button", text = "예매하기", clickable = true, bounds = Bounds(0, 600, 300, 700))
        val root = searchScreen().apply { kids += confirm }
        val r = Rig(root)
        val gate = Gate(GatePolicy(setOf(GatePolicy.MOCK_APP), ownPackage = "capstone.app"), RecordingDecisionSink())
        val exec = GatedExecutor(gate, r.actuator, r.holder)

        val before = r.holder.capture()
        val w = before.window(1)!!
        val res = exec.execute(before, ActionRequest.Click(NodeRef.of(w, "0/3"), GatePolicy.MOCK_APP, null))
        assertIs<ExecResult.Blocked>(res)
        assertTrue(confirm.performed.isEmpty())
        assertTrue(r.device.taps.isEmpty())

        val ok = exec.execute(before, ActionRequest.Click(NodeRef.of(w, "0/2"), GatePolicy.MOCK_APP, null))
        assertEquals(ExecResult.Done, ok)
        assertEquals(1, (root.kids[2] as FakeNode).performed.size)
    }

    @Test
    fun `클릭 직전 버튼 안에 확정 라벨 자식이 생기면 누르지 않는다`() {
        val r = Rig()
        val ref = r.ref("0/2")
        val button = r.screen.kids[2] as FakeNode
        button.kids += FakeNode(text = "예매하기", bounds = Bounds(100, 420, 200, 480))
        assertFalse(r.actuator.click(ref))
        assertTrue(button.performed.isEmpty())
    }

    @Test
    fun `중심 탭 직전 확정 버튼이 덮으면 탭하지 않는다 - 형제와 새 창 (I-86)`() {
        val r = Rig()
        val ref = r.ref("0/2")
        r.screen.kids += FakeNode(text = "예매하기", clickable = true, bounds = Bounds(100, 420, 200, 480))
        assertFalse(r.actuator.tapCenter(ref))

        val windows = mutableListOf(FakeWindow(1, searchScreen()))
        val holder = FrameHolder { TreeCapture().capture(windows.toList(), 0) }
        val device = FakeDevice()
        val actuator = FrameActuator(holder, device)
        val ref2 = NodeRef.of(holder.capture().window(1)!!, "0/2")
        windows.add(0, FakeWindow(2, FakeNode(text = "결제하기", clickable = true, bounds = Bounds(0, 400, 300, 500)), active = false, focused = false))
        assertFalse(actuator.tapCenter(ref2))
        assertTrue(device.taps.isEmpty() && r.device.taps.isEmpty())
    }

    @Test
    fun `IME 확인 직전 화면 어딘가에 확정 버튼이 생기면 보내지 않는다`() {
        val r = Rig()
        val ref = r.ref("0/1")
        r.screen.kids += FakeNode(className = "android.widget.Button", text = "예매하기", clickable = true, bounds = Bounds(0, 600, 300, 700))
        assertFalse(r.actuator.imeEnter(ref))
        assertTrue((r.screen.kids[1] as FakeNode).performed.isEmpty())
        // 화면이 그대로면 보낸다.
        assertTrue(r.actuator.imeEnter(r.ref("0/1")))
    }

    @Test
    fun `스크롤은 목록 아래가 늘어나도 같은 노드로 본다`() {
        val list = FakeNode(className = "android.widget.ListView", scrollable = true, bounds = Bounds(0, 600, 1080, 2000))
        val root = searchScreen().apply { kids += list }
        val r = Rig(root)
        val ref = r.ref("0/3")
        list.kids += FakeNode(text = "KTX 103", bounds = Bounds(0, 600, 1080, 700))
        assertTrue(r.actuator.scroll(ref, forward = true))
    }

    @Test
    fun `back은 기기 동작으로 간다`() {
        val r = Rig()
        assertTrue(r.actuator.back())
        assertEquals(1, r.device.backs)
    }
}
