package capstone.tools.replay

import capstone.gate.BlockReason
import capstone.gate.ExecResult
import capstone.gate.NodeRef
import capstone.snapshot.ReplayFormatException
import capstone.snapshot.SnapshotJsonl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReplayDeviceTest {

    @Test
    fun `정상 흐름은 좌석 화면까지 가고 예매하기는 gate가 막는다`() {
        val rig = Rig()
        assertEquals(ExecResult.Done, rig.click("닫기"))
        assertEquals(ExecResult.Done, rig.type("도착역", "부산"))
        assertEquals(ExecResult.Done, rig.click("조회하기"))
        assertEquals("mock_s2_results", rig.device.currentSnapId)
        assertEquals(ExecResult.Done, rig.click("KTX 105"))
        assertEquals("mock_s3_seat", rig.device.currentSnapId)

        val blocked = assertIs<ExecResult.Blocked>(rig.click("예매하기"))
        assertTrue(blocked.decision.reason.isConfirm, "확정 계열 사유: ${blocked.decision.reason}")
        assertEquals("mock_s3_seat", rig.device.currentSnapId, "막혔으니 화면이 그대로")

        val report = rig.device.report()
        assertTrue(report.passed, report.toString())
        assertEquals(4, report.steps, "닫기·입력·조회하기·열차 4건뿐, 예매하기는 장치에 닿지 않았다")
        assertTrue(rig.sink.entries.any { it.decision == "BLOCK" && it.reason?.isConfirm == true })
    }

    @Test
    fun `gate를 우회해 장치에 닿은 클릭은 위반으로 잡힌다`() {
        // 재생기의 M7 판정 자체를 검증: 일부러 RawActuator를 직접 불러 본다.
        val rig = Rig()
        rig.click("닫기"); rig.click("조회하기"); rig.click("KTX 105")
        val s = rig.snap()
        assertTrue(rig.device.click(rig.ref(s, "예매하기")))
        val report = rig.device.report()
        assertFalse(report.passed)
        assertEquals(listOf("mock_s4_payment"), report.neverReachHit)
        assertEquals("예매하기", report.violations.single().label)
    }

    @Test
    fun `must_reach에 못 가면 실패`() {
        val rig = Rig()
        rig.click("닫기")
        assertEquals(listOf("mock_s3_seat"), rig.device.report().missingMustReach)
    }

    @Test
    fun `화면이 바뀐 뒤의 낡은 참조는 장치가 거부한다`() {
        val rig = Rig()
        val old = rig.snap()
        val closeRef = rig.ref(old, "닫기")
        assertTrue(rig.device.click(closeRef))
        assertFalse(rig.device.click(closeRef))
        assertTrue("stale_ref" in rig.device.log.last().notes)
        assertFalse(rig.device.click(NodeRef(windowId = 99, key = "0", treeHash = "x")))
    }

    @Test
    fun `set_text는 editable 노드만 바꾸고 treeHash가 달라진다`() {
        val rig = Rig()
        rig.click("닫기")
        val before = rig.snap()
        assertFalse(rig.device.setText(rig.ref(before, "승차권 조회"), "x"), "TextView는 입력 불가")
        assertTrue(rig.device.setText(rig.ref(before, "도착역"), "부산"))
        val after = rig.snap()
        assertNotEquals(before.foregroundAppWindow!!.treeHash, after.foregroundAppWindow!!.treeHash)
        assertTrue(after.foregroundAppWindow!!.nodes.values.any { it.text == "부산" && it.editable })
        assertEquals("mock_s1_search", rig.device.currentSnapId)
        val entry = rig.device.log.last()
        assertTrue("text_len:2" in entry.notes)
        assertFalse(entry.toJson().toString().contains("부산"), "입력 텍스트는 로그에 남기지 않는다")
    }

    @Test
    fun `back 전이와 전이 없는 클릭`() {
        val rig = Rig()
        rig.click("닫기"); rig.click("조회하기")
        assertEquals(ExecResult.Done, rig.executor.execute(rig.snap(), capstone.gate.ActionRequest.GlobalBack()))
        assertEquals("mock_s1_search", rig.device.currentSnapId)
        assertEquals(ExecResult.Done, rig.click("KTX"))
        assertEquals("mock_s1_search", rig.device.currentSnapId)
        assertTrue("no_transition" in rig.device.log.last().notes)
    }

    @Test
    fun `결함 - 액션 직후 빈 트리면 checkAfter가 막고, 클릭 실패는 중심 탭 1회로 넘어간다`() {
        val rig = Rig(Fixtures.faults)
        rig.click("닫기")
        val r = assertIs<ExecResult.Blocked>(rig.click("조회하기"))
        assertEquals(BlockReason.FAIL_CLOSED_EMPTY_TREE, r.decision.reason)
        assertEquals("mock_s2_results", rig.device.currentSnapId, "장치는 전이했고 관측만 비었다")
        assertTrue(rig.snap().foregroundAppWindow!!.root != null, "결함은 1회성")

        assertEquals(ExecResult.Done, rig.click("KTX 105"))
        val last2 = rig.device.log.takeLast(2)
        assertEquals(listOf("click", "tap_center"), last2.map { it.action })
        assertTrue("fault:click_fails" in last2[0].notes)
        assertEquals("mock_s3_seat", last2[1].to)
    }

    @Test
    fun `결함 - 스크린샷 오류 코드가 다음 관측에 붙는다`() {
        val sc = Scenario.read(Fixtures.happy).copy(faults = listOf(Fault.ScreenshotError(code = 6, onSnap = "mock_s1_popup")))
        val rig = Rig(Fixtures.happy, sc)
        val r = assertIs<ExecResult.Blocked>(rig.click("닫기"))
        assertEquals(BlockReason.FAIL_CLOSED_SCREENSHOT_ERROR, r.decision.reason)
        assertNull(rig.snap().screenshotError)
    }

    @Test
    fun `확률 결함은 seed가 같으면 같은 결과`() {
        fun run(seed: Long): List<Boolean> {
            val sc = Scenario.read(Fixtures.happy).copy(seed = seed, faults = listOf(Fault.ClickFails(times = 100, probability = 0.5)))
            val rig = Rig(Fixtures.happy, sc)
            val ref = rig.ref(rig.snap(), "출발역")
            return List(30) { rig.device.click(ref) }
        }
        assertEquals(run(42), run(42))
        assertTrue(run(42).toSet().size == 2, "0.5 확률이면 성공·실패가 섞인다")
    }

    @Test
    fun `시나리오 오류`() {
        fun parse(body: String) = Scenario.parse("""{"v":0,"start":"a",$body}""")
        assertThrows(ReplayFormatException::class.java) { parse(""""transitions":[{"from":"a","on":{"action":"swipe"},"to":"b"}]""") }
        assertThrows(ReplayFormatException::class.java) { parse(""""transitions":[{"from":"a","on":{"action":"click"},"to":"b"}]""") }
        assertThrows(ReplayFormatException::class.java) { parse(""""faults":[{"kind":"meteor"}]""") }
        assertThrows(ReplayFormatException::class.java) { parse(""""faults":[{"kind":"click_fails","p":2}]""") }
        assertThrows(ReplayFormatException::class.java) { Scenario.parse("""{"v":0}""") }
        val sc = parse(""""transitions":[{"from":"a","on":{"action":"back"},"to":"nowhere"}]""")
            .copy(start = "mock_s1_search", transitions = listOf(Transition("mock_s1_search", ReplayAction.BACK, null, "nowhere")))
        assertThrows(ReplayFormatException::class.java) { ReplayDevice(sc, Fixtures.pool()) }
    }

    @Test
    fun `같은 snap_id가 두 번이면 풀 생성 실패`() {
        val r = SnapshotJsonl.decodeLine("""{"v":0,"snap_id":"d","windows":[]}""")
        assertThrows(ReplayFormatException::class.java) { SnapshotPool(listOf(r, r)) }
    }
}
