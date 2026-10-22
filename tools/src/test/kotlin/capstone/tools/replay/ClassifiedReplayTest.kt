package capstone.tools.replay

import capstone.agent.Screens
import capstone.gate.ActionRequest
import capstone.gate.BlockReason
import capstone.gate.ExecResult
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.exec.GatedExecutor
import capstone.scenarios.MockKorail
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 오라클 라벨 대신 실제 분류기(MockKorail 서명)를 끼운 재생. M2(분류 정확도)의 합성 데이터 버전. */
class ClassifiedReplayTest {
    private val classifier = MockKorail.classifier()

    @Test
    fun `목업 픽스처 5장 분류가 정답 라벨과 모두 일치`() {
        val recs = SnapshotJsonl.read(Fixtures.mockJsonl)
        val wrong = recs.filter { classifier.classify(it.snapshot).screen != it.meta.screenLabel }
            .map { "${it.snapId}: ${classifier.classify(it.snapshot)}" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `분류기 결과로 gate 서명 검사를 거쳐 좌석 화면까지 가고 예매하기는 막힌다`() {
        val sc = Scenario.read(Fixtures.happy)
        val device = ReplayDevice(sc, SnapshotPool.forScenario(Fixtures.happy, sc), oracleScreens = false)
        val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
        // 실행 직후 관측도 분류해야 checkAfter가 화면 서명을 본다.
        val exec = GatedExecutor(gate, device) { classifier.label(device.capture()) }
        val rig = Rig()

        fun click(label: String, expected: String): ExecResult {
            val s = classifier.label(device.capture())
            assertEquals(expected, s.screen)
            return exec.execute(s, ActionRequest.Click(rig.ref(s, label), expectedPackage = MockKorail.PKG, expectedScreen = expected))
        }
        assertEquals(ExecResult.Done, click("닫기", Screens.POPUP_KNOWN))
        assertEquals(ExecResult.Done, click("조회하기", Screens.SEARCH_FORM))
        assertEquals(ExecResult.Done, click("KTX 105", Screens.RESULT_LIST))
        val blocked = assertIs<ExecResult.Blocked>(click("예매하기", Screens.DETAIL))
        assertTrue(blocked.decision.reason.isConfirm)
        assertTrue(device.report().passed, device.report().toString())
    }

    @Test
    fun `빈 트리 결함 관측은 분류기도 UNKNOWN, gate는 빈 트리로 차단`() {
        val sc = Scenario.read(Fixtures.faults)
        val device = ReplayDevice(sc, SnapshotPool.forScenario(Fixtures.faults, sc))
        val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
        var lastAfter: String? = null
        val exec = GatedExecutor(gate, device) { classifier.label(device.capture()).also { lastAfter = it.screen } }
        val rig = Rig()
        var s = classifier.label(device.capture())
        exec.execute(s, ActionRequest.Click(rig.ref(s, "닫기"), expectedScreen = s.screen))
        s = classifier.label(device.capture())
        val r = assertIs<ExecResult.Blocked>(exec.execute(s, ActionRequest.Click(rig.ref(s, "조회하기"), expectedScreen = s.screen)))
        assertEquals(BlockReason.FAIL_CLOSED_EMPTY_TREE, r.decision.reason)
        assertEquals(Screens.UNKNOWN, lastAfter)
    }
}
