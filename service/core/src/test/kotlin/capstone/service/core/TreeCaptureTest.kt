package capstone.service.core

import capstone.gate.NodeRef
import capstone.gate.WindowType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeCaptureTest {

    @Test
    fun `창 목록을 스냅샷으로 옮기고 키는 인덱스 경로다`() {
        val root = searchScreen()
        val ime = FakeNode(packageName = "com.samsung.android.honeyboard")
        val frame = TreeCapture().capture(
            listOf(FakeWindow(7, root), FakeWindow(9, ime, type = WindowType.INPUT_METHOD, active = false, focused = false)),
            timestampMs = 1234,
        )
        val s = frame.snapshot
        assertEquals(1234, s.timestampMs)
        assertEquals(listOf(7, 9), s.windows.map { it.id })
        assertEquals("capstone.mock", s.foregroundAppWindow?.packageName)
        val w = s.window(7)!!
        assertEquals(listOf("0", "0/0", "0/1", "0/2", "0/2/0"), w.nodes.keys.toList())
        assertEquals("조회하기", w.nodes["0/2/0"]?.text)
        assertTrue(w.nodes.getValue("0/1").editable)
        assertNull(s.screen, "분류는 agent-core 몫")
    }

    @Test
    fun `비밀번호 칸의 text는 스냅샷에 들어가지 않는다`() {
        val root = searchScreen().apply { kids += FakeNode(text = "secret12", password = true, editable = true) }
        val w = TreeCapture().capture(listOf(FakeWindow(1, root)), 0).snapshot.window(1)!!
        assertNull(w.nodes.getValue("0/3").text)
    }

    @Test
    fun `노드 한도를 넘으면 트리를 자르지 않고 루트를 비운다`() {
        val root = FakeNode(kids = MutableList(20) { FakeNode(text = "행 $it") })
        val frame = TreeCapture(maxNodesPerWindow = 10).capture(listOf(FakeWindow(1, root)), 0)
        assertNull(frame.snapshot.window(1)!!.root, "잘린 트리 대신 빈 트리 → gate가 fail-closed")
        assertEquals(setOf(1), frame.droppedWindows)
    }

    @Test
    fun `순회 중 자식이 사라지면 그 창은 빈 트리`() {
        val root = searchScreen().apply { kids[1] = null }
        val frame = TreeCapture().capture(listOf(FakeWindow(1, root)), 0)
        assertNull(frame.snapshot.window(1)!!.root)
    }

    @Test
    fun `깊이 한도를 넘어도 빈 트리`() {
        var n = FakeNode(text = "leaf")
        repeat(10) { n = FakeNode(kids = mutableListOf(n)) }
        val frame = TreeCapture(maxDepth = 5).capture(listOf(FakeWindow(1, n)), 0)
        assertNull(frame.snapshot.window(1)!!.root)
    }

    @Test
    fun `루트를 못 얻은 창은 패키지 없이 빈 루트`() {
        val frame = TreeCapture().capture(listOf(FakeWindow(1, null)), 0)
        val w = frame.snapshot.window(1)!!
        assertNull(w.root)
        assertNull(w.packageName)
        assertTrue(frame.droppedWindows.isEmpty())
    }

    @Test
    fun `resolve는 treeHash가 같을 때만 손잡이를 준다`() {
        val frame = TreeCapture().capture(listOf(FakeWindow(1, searchScreen())), 0)
        val w = frame.snapshot.window(1)!!
        assertNotNull(frame.resolve(NodeRef.of(w, "0/2")))
        assertNull(frame.resolve(NodeRef(1, "0/2", "deadbeef")))
        assertNull(frame.resolve(NodeRef(2, "0/2", w.treeHash)))
        assertNull(frame.resolve(NodeRef(1, "0/9", w.treeHash)))
    }
}
