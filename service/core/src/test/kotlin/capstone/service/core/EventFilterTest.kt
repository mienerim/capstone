package capstone.service.core

import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.WindowType
import capstone.gate.ActionRequest
import capstone.gate.BlockReason
import capstone.gate.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D63 ②: 이벤트 필터와 허용목록 밖 창 비순회. */
class EventFilterTest {
    private val filter = EventFilter(setOf("capstone.mock"))

    @Test
    fun `대상 앱 이벤트만 캡처하고 다른 앱 이벤트는 버린다`() {
        assertTrue(filter.shouldCapture(A11yEventKind.WINDOW_STATE_CHANGED, "capstone.mock"))
        assertTrue(filter.shouldCapture(A11yEventKind.WINDOW_CONTENT_CHANGED, "capstone.mock"))
        assertFalse(filter.shouldCapture(A11yEventKind.WINDOW_CONTENT_CHANGED, "com.kakao.talk"))
        assertFalse(filter.shouldCapture(A11yEventKind.WINDOW_STATE_CHANGED, "com.android.systemui"))
    }

    @Test
    fun `패키지 없는 창 목록 변경은 다시 보고, 패키지 없는 내용 변경은 버린다`() {
        assertTrue(filter.shouldCapture(A11yEventKind.WINDOWS_CHANGED, null))
        assertFalse(filter.shouldCapture(A11yEventKind.WINDOW_CONTENT_CHANGED, null))
        assertFalse(filter.shouldCapture(A11yEventKind.OTHER, "capstone.mock"))
    }

    private fun foreign(): FakeNode =
        // 순회하면 null 자식 때문에 '순회 중 변경'으로 루트가 비워지고 droppedWindows에 들어간다.
        FakeNode(packageName = "com.kakao.talk", text = "대화 내용", kids = mutableListOf(FakeNode(packageName = "com.kakao.talk"), null))

    @Test
    fun `허용목록 밖 창은 패키지만 남기고 노드를 순회하지 않는다`() {
        val capture = TreeCapture(targetPackages = setOf("capstone.mock"))
        val frame = capture.capture(
            listOf(
                FakeWindow(1, searchScreen(), active = false, focused = false),
                FakeWindow(2, foreign()),
                FakeWindow(3, FakeNode(packageName = "com.samsung.android.honeyboard"), type = WindowType.INPUT_METHOD, active = false, focused = false),
            ),
            0,
        )
        val s = frame.snapshot
        assertEquals("com.kakao.talk", s.window(2)!!.packageName)
        assertNull(s.window(2)!!.root)
        assertNull(s.window(3)!!.root)
        assertTrue(frame.droppedWindows.isEmpty(), "순회하지 않았으므로 '순회 중 변경'도 없다")
        assertEquals(5, s.window(1)!!.nodeCount, "대상 앱 창은 그대로 순회")
    }

    @Test
    fun `다른 앱이 포그라운드면 gate가 패키지로 막고 덤프도 쓰지 않는다`() {
        val s = TreeCapture(targetPackages = setOf("capstone.mock"))
            .capture(listOf(FakeWindow(1, searchScreen(), active = false, focused = false), FakeWindow(2, foreign())), 0).snapshot
        val gate = Gate(GatePolicy(allowedPackages = setOf("capstone.mock"), ownPackage = "capstone.app"))
        val d = assertIs<Decision.Block>(gate.check(s, ActionRequest.GlobalBack()))
        assertEquals(BlockReason.PACKAGE_NOT_ALLOWED, d.reason)

        val out = StringBuilder()
        val rec = DumpRecorder(out, setOf("capstone.mock"), DeviceInfo(36, null) { null }, "s1")
        assertEquals(DumpRecorder.Result.Skipped("package_not_listed"), rec.record(s))
        assertTrue(out.isEmpty())
    }
}
