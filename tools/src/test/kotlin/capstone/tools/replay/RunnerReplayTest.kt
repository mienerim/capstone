package capstone.tools.replay

import capstone.agent.RunState
import capstone.agent.RunnerConfig
import capstone.agent.ScenarioRunner
import capstone.agent.VirtualClock
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.exec.GatedExecutor
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 상태머신(④-b)을 저장 트리 재생기 위에서 실제 gate·분류기와 함께 돌린다. 앱의 조립과 같은 모양이다. */
class RunnerReplayTest {
    private val classifier = MockKorail.classifier()

    private class Run(val device: ReplayDevice, val runner: ScenarioRunner)

    private fun start(file: File, slots: Map<String, String>): Run {
        val sc = Scenario.read(file)
        val device = ReplayDevice(sc, SnapshotPool.forScenario(file, sc), oracleScreens = false)
        val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
        val exec = GatedExecutor(gate, device) { classifier.label(device.capture()) }
        val runner = ScenarioRunner(MockKorailFlow.flow, slots, classifier, exec, device::capture, VirtualClock(), RunnerConfig())
        return Run(device, runner)
    }

    private val slots = mapOf(MockKorailFlow.SLOT_ORIGIN to "서울", MockKorailFlow.SLOT_DESTINATION to "부산")

    @Test
    fun `목업 시나리오가 좌석 화면에서 멈추고 예매하기는 장치에 닿지 않는다`() {
        val r = start(Fixtures.happy, slots)
        val ask = assertIs<RunState.AskUser>(r.runner.run())
        // KTX 103은 매진(클릭 불가)이라 보기에서 빠진다.
        assertEquals(listOf("KTX 101", "KTX 105", "무궁화호 1201", "KTX 107", "ITX-새마을 1001"), ask.options)
        r.runner.answer("KTX 105")
        assertIs<RunState.Done>(r.runner.run())

        val report = r.device.report()
        assertTrue(report.passed, report.toString())
        assertEquals("mock_s3_seat", r.device.currentSnapId)
        assertEquals(listOf("click", "set_text", "click", "click"), r.device.log.map { it.action })
    }

    @Test
    fun `결함 시나리오(클릭 실패·빈 트리)도 복구해 좌석 화면까지 간다`() {
        val r = start(Fixtures.faults, slots + (MockKorailFlow.SLOT_TRAIN to "KTX 105"))
        assertIs<RunState.Done>(r.runner.run())
        val report = r.device.report()
        assertTrue(report.passed, report.toString())
        val notes = r.device.log.flatMap { it.notes }
        assertTrue("fault:click_fails" in notes && "fault:empty_after_action" in notes, notes.toString())
        // 클릭 실패는 gate-exec 폴백(중심 탭 1회)이 흡수한다.
        assertTrue(r.device.log.any { it.action == "tap_center" && it.ok })
    }
}
