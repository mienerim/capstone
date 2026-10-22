package capstone.tools.replay

import capstone.gate.BlockReason
import capstone.gate.ExecResult
import capstone.gate.GatePolicy
import capstone.agent.Screens
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** D36 재생기 결함 주입 7종: 관측이 어떻게 바뀌는지(장치), 그리고 실제 gate·러너가 어떻게 반응하는지(하니스). */
class FaultInjectionTest {
    private val happy = Scenario.read(Fixtures.happy)
    private fun rig(vararg faults: Fault) = Rig(Fixtures.happy, happy.copy(faults = faults.toList()))
    private fun normal() = Rig().also { it.click("닫기") }.snap()

    /** gate를 거치지 않은 raw 클릭. GatedExecutor는 checkAfter에서 관측 1회를 쓰므로, 관측 변형만 볼 때는 이것을 쓴다. */
    private fun Rig.rawClick(label: String) = device.click(ref(snap(), label))

    // ---- 장치: 관측 변형 ----

    @Test
    fun `노드 삭제 - 루트는 남고 노드가 줄며 결함 동안 같은 트리를 보인다`() {
        val rig = rig(Fault.NodeDrop(0.7, onSnap = "mock_s1_popup", captures = 2))
        rig.rawClick("닫기")
        val a = rig.snap().foregroundAppWindow!!
        val b = rig.snap().foregroundAppWindow!!
        val c = rig.snap().foregroundAppWindow!!
        val full = normal().foregroundAppWindow!!
        assertNotNull(a.root)
        assertTrue(a.nodeCount < full.nodeCount, "${a.nodeCount} < ${full.nodeCount}")
        assertEquals(a.treeHash, b.treeHash, "같은 결함 동안 같은 잘린 트리")
        assertEquals(full.treeHash, c.treeHash, "captures=2 뒤에는 원래 트리")
    }

    @Test
    fun `스켈레톤 - 구조는 같고 라벨만 빈다`() {
        val rig = rig(Fault.Skeleton(onSnap = "mock_s1_popup"))
        rig.rawClick("닫기")
        val s = rig.snap()
        val w = s.foregroundAppWindow!!
        val full = normal().foregroundAppWindow!!
        assertEquals(full.nodeCount, w.nodeCount)
        assertTrue(w.nodes.values.all { it.text == null && it.contentDescription == null && it.hint == null })
        assertEquals(Screens.UNKNOWN, MockKorail.classifier().classify(s).screen)
    }

    @Test
    fun `패키지 변경 - 실행 직후 관측이 다른 앱이면 gate가 막고 잠근다`() {
        val rig = rig(Fault.PackageChange(onSnap = "mock_s1_popup"))
        val r = assertIs<ExecResult.Blocked>(rig.click("닫기"))
        assertEquals(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, r.decision.reason)
        // 관측은 다시 정상이어도 잠금은 남는다(I-12).
        assertEquals(GatePolicy.MOCK_APP, rig.snap().foregroundAppWindow!!.packageName)
        assertIs<ExecResult.Blocked>(rig.type("출발역", "서울"))
    }

    @Test
    fun `서명 훼손 - id가 난독화되어 분류기가 검색 화면으로 보지 않는다`() {
        val rig = rig(Fault.SignatureDamage(onSnap = "mock_s1_popup"))
        rig.rawClick("닫기")
        val damaged = rig.device.capture()
        assertTrue(damaged.foregroundAppWindow!!.nodes.values.none { it.viewId?.endsWith(":id/dep_station") == true })
        val classifier = MockKorail.classifier()
        assertNotEquals(Screens.SEARCH_FORM, classifier.classify(damaged).screen)
        assertEquals(Screens.SEARCH_FORM, classifier.classify(rig.device.capture()).screen, "1회성")
    }

    @Test
    fun `서명 훼손 - 지정 텍스트도 지운다`() {
        val rig = rig(Fault.SignatureDamage(text = "조회하기", onSnap = "mock_s1_popup"))
        rig.rawClick("닫기")
        assertTrue(rig.device.capture().foregroundAppWindow!!.nodes.values.none { it.text == "조회하기" })
    }

    @Test
    fun `이벤트 순서 - 장치는 넘어갔는데 관측은 액션 전 트리`() {
        val rig = rig(Fault.StaleCapture(onSnap = "mock_s1_popup"))
        val before = rig.snap().foregroundAppWindow!!.treeHash
        // checkAfter가 첫 관측(낡은 트리)을 소비한다. 낡은 트리는 같은 패키지라 막히지 않는다.
        assertEquals(ExecResult.Done, rig.click("닫기"))
        assertEquals("mock_s1_search", rig.device.currentSnapId)
        assertNotEquals(before, rig.snap().foregroundAppWindow!!.treeHash)
    }

    @Test
    fun `이벤트 순서 - 낡은 트리의 노드 참조는 장치에서 실패한다`() {
        val rig = rig(Fault.StaleCapture(onSnap = "mock_s1_popup", captures = 2))
        rig.device.click(rig.ref(rig.snap(), "닫기"))
        val stale = rig.snap()
        assertTrue(!rig.device.click(rig.ref(stale, "닫기")))
        assertTrue("stale_ref" in rig.device.log.last().notes)
    }

    @Test
    fun `새 액션이 오면 남은 일시 결함은 버린다`() {
        val rig = rig(Fault.EmptyAfterAction(onSnap = "mock_s1_popup", captures = 5))
        rig.device.click(rig.ref(rig.snap(), "닫기"))
        assertEquals(null, rig.device.capture().foregroundAppWindow!!.root)
        rig.device.back()
        assertNotNull(rig.device.capture().foregroundAppWindow!!.root)
    }

    @Test
    fun `결함 JSON - 7종과 별칭, 범위 오류`() {
        fun parse(f: String) = Scenario.parse("""{"v":0,"start":"a","faults":[$f]}""").faults.single()
        assertIs<Fault.EmptyAfterAction>(parse("""{"kind":"node_zero","captures":3}""")).also { assertEquals(3, it.captures) }
        assertEquals(0.5, assertIs<Fault.NodeDrop>(parse("""{"kind":"node_drop","fraction":0.5}""")).fraction)
        assertIs<Fault.Skeleton>(parse("""{"kind":"skeleton"}"""))
        assertEquals("x.y", assertIs<Fault.PackageChange>(parse("""{"kind":"package_change","pkg":"x.y"}""")).pkg)
        assertEquals(Fault.DEFAULT_FOREIGN_PKG, assertIs<Fault.PackageChange>(parse("""{"kind":"package_change"}""")).pkg)
        assertEquals("조회하기", assertIs<Fault.SignatureDamage>(parse("""{"kind":"signature_damage","text":"조회하기"}""")).text)
        assertEquals(3, assertIs<Fault.ScreenshotError>(parse("""{"kind":"screenshot_error","code":3}""")).code)
        assertIs<Fault.StaleCapture>(parse("""{"kind":"event_order"}"""))
        assertThrows(ReplayFormatException::class.java) { parse("""{"kind":"node_drop","fraction":1.5}""") }
        assertThrows(ReplayFormatException::class.java) { parse("""{"kind":"skeleton","captures":0}""") }
    }

    // ---- 러너: 결함별 반응 (실제 gate·분류기·상태머신) ----

    private val slots = mapOf(
        MockKorailFlow.SLOT_ORIGIN to "서울", MockKorailFlow.SLOT_DESTINATION to "부산", MockKorailFlow.SLOT_TRAIN to "KTX 105",
    )

    private fun outcome(fault: Fault): RunRecord {
        val sc = happy.copy(faults = listOf(fault))
        val policy = GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app")
        val h = ReplayHarness(sc, SnapshotPool.forScenario(Fixtures.happy, sc), MockKorailFlow.flow, MockKorail.classifier(), slots, policy)
        return h.runOnce(0)
    }

    @Test
    fun `일시 결함 7종 - 위반 없이 복구하거나 안전하게 멈춘다`() {
        // 조회하기 클릭(검색 화면) 직후에 건다: 로딩이 가장 길고 결과 화면 서명이 처음 확인되는 지점.
        val on = "mock_s1_search"
        val faults = listOf(
            Fault.EmptyAfterAction(on, times = 3),
            Fault.NodeDrop(0.7, on, times = 3),
            Fault.Skeleton(on, times = 3, captures = 3),
            Fault.PackageChange(onSnap = on),
            Fault.SignatureDamage(onSnap = on, times = 3),
            Fault.ScreenshotError(3, on, times = 3),
            Fault.ScreenshotError(6, on),
            Fault.StaleCapture(on, times = 3, captures = 2),
        )
        val got = faults.associate { f -> "${f.kind}${(f as? Fault.ScreenshotError)?.code ?: ""}" to outcome(f).let { "${it.outcome}/${it.end}" } }
        val expected = mapOf(
            "empty_after_action" to "DONE/done",
            "node_drop" to "DONE/done",
            "skeleton" to "DONE/done",
            // 실행 직후 예상 밖 패키지 = 잠금(fail-closed). 화면이 돌아와도 이어 가지 않는다.
            "package_change" to "SAFE_STOP/stopped:gate:FAIL_CLOSED_PACKAGE_MISMATCH",
            "signature_damage" to "DONE/done",
            "screenshot_error3" to "DONE/done",
            "screenshot_error6" to "DONE/done",
            "stale_capture" to "DONE/done",
        )
        assertEquals(expected, got)
    }

    @Test
    fun `지속 결함 - 화면이 돌아오지 않으면 인계 뒤 시간 초과로 멈춘다`() {
        val on = "mock_s1_search"
        val forever = 100_000
        val got = listOf(
            Fault.EmptyAfterAction(on, captures = forever),
            Fault.Skeleton(on, captures = forever),
            Fault.SignatureDamage(onSnap = on, captures = forever),
            Fault.ScreenshotError(6, on, captures = forever),
        ).associate { f -> f.kind to outcome(f) }
        for ((kind, r) in got) {
            assertEquals(Outcome.SAFE_STOP, r.outcome, "$kind: ${r.end}")
            assertTrue(r.end.startsWith("stopped:"), "$kind: ${r.end}")
        }
    }
}
