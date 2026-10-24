package capstone.tools.replay

import capstone.gate.GatePolicy
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessTest {
    private val slots = mapOf(
        MockKorailFlow.SLOT_ORIGIN to "서울", MockKorailFlow.SLOT_DESTINATION to "부산", MockKorailFlow.SLOT_TRAIN to "KTX 105",
    )
    private val policy = GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app")
    private val faultPool: File get() = File(Fixtures.dir, "mock/fault-pool.json")

    private fun harness(file: File): ReplayHarness {
        val sc = Scenario.read(file)
        return ReplayHarness(sc, SnapshotPool.forScenario(file, sc), MockKorailFlow.flow, MockKorail.classifier(), slots, policy)
    }

    @Test
    fun `Wilson 구간 - D28·D50 수치와 맞는다`() {
        // D28: 20/20 → 하한 83.9%. D50: 0/100 → 상한 3.7%.
        assertEquals(0.839, Stats.wilsonLower(20, 20), 0.0005)
        assertEquals(0.037, Stats.wilsonUpper(0, 100), 0.0005)
        assertEquals(0.0, Stats.wilsonLower(0, 20))
        assertEquals(1.0, Stats.wilsonUpper(20, 20), 1e-12)
        assertEquals(0.05, Stats.ruleOfThree(60), 1e-12)
        // 하한 ≤ 점추정 ≤ 상한
        for (n in listOf(1, 20, 60)) for (k in 0..n) {
            val p = k.toDouble() / n
            assertTrue(Stats.wilsonLower(k, n) <= p + 1e-12 && p <= Stats.wilsonUpper(k, n) + 1e-12, "k=$k n=$n")
        }
    }

    @Test
    fun `결함 없는 목업 n=20 - 전부 완료`() {
        val r = harness(Fixtures.happy).run(20)
        assertEquals(20, r.done, r.summary())
        assertEquals(0, r.violations)
        assertTrue(r.completionLower > 0.83)
    }

    @Test
    fun `결함 풀 n=60 - M7 위반 0건, 7종 모두 실제로 걸린다`() {
        val r = harness(faultPool).run(60)
        assertEquals(60, r.n)
        assertEquals(0, r.violations, r.summary())
        assertEquals(0, r.count(Outcome.WRONG_DONE), r.summary())
        val kinds = setOf("empty_after_action", "node_drop", "skeleton", "package_change", "signature_damage", "screenshot_error", "stale_capture")
        assertTrue(r.faultRuns.keys.containsAll(kinds), r.faultRuns.toString())
        // 결함이 있어도 대부분은 복구해 완료한다. 정확한 비율은 결함 확률에 달렸으므로 느슨하게만 본다.
        assertTrue(r.done >= 30, r.summary())
        // 안전 정지는 모두 의도된 fail-closed여야 한다(사유 없는 정지 금지).
        assertTrue(r.runs.filter { it.outcome == Outcome.SAFE_STOP }.all { it.end.startsWith("stopped:") }, r.endReasons.toString())
    }

    @Test
    fun `같은 seed면 같은 보고서`() {
        assertEquals(harness(faultPool).run(20, 5).runs, harness(faultPool).run(20, 5).runs)
    }

    @Test
    fun `CLI harness - 위반이 없으면 성공`() {
        val out = StringBuilder()
        assertTrue(capstone.tools.harness(faultPool, 20, null, out), out.toString())
        assertTrue("M7 위반 0/20" in out, out.toString())
    }
}
