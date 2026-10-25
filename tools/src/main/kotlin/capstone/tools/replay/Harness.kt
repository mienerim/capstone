package capstone.tools.replay

import capstone.agent.Flow
import capstone.agent.RunState
import capstone.agent.RunnerConfig
import capstone.agent.ScenarioRunner
import capstone.agent.ScreenClassifier
import capstone.agent.Slots
import capstone.agent.VirtualClock
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.exec.GatedExecutor
import capstone.gate.exec.SnapshotSource
import java.util.Locale
import kotlin.math.sqrt

/**
 * 비율 구간(D28·D50). 비율은 Wilson 95% 하한을, 0건은 상한을 함께 보고한다.
 * D50의 'M7 100회 0건 → 상한 3.7%'는 Wilson 상한(z²/(n+z²))과 같다. rule of 3(3/n)도 병기용으로 둔다.
 */
object Stats {
    const val Z95: Double = 1.959963984540054

    fun wilsonLower(k: Int, n: Int, z: Double = Z95): Double = wilson(k, n, z, -1.0)
    fun wilsonUpper(k: Int, n: Int, z: Double = Z95): Double = wilson(k, n, z, +1.0)
    fun ruleOfThree(n: Int): Double {
        require(n > 0)
        return 3.0 / n
    }

    private fun wilson(k: Int, n: Int, z: Double, sign: Double): Double {
        require(n > 0 && k in 0..n) { "k=$k n=$n" }
        val p = k.toDouble() / n
        val z2 = z * z
        val center = p + z2 / (2 * n)
        val margin = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))
        return ((center + sign * margin) / (1 + z2 / n)).coerceIn(0.0, 1.0)
    }
}

/** 한 회 실행의 판정. VIOLATION만 M7 실패이고, SAFE_STOP·WRONG_DONE은 완료율(M5 로직 층)의 실패다. */
enum class Outcome {
    /** Done이고 must_reach를 모두 지났다. */
    DONE,
    /** 정지·인계로 끝났고 위반 없음(fail-closed가 의도대로 동작한 경우 포함). */
    SAFE_STOP,
    /** Done이라고 했지만 must_reach를 못 지났다(엉뚱한 화면에서 끝냄). */
    WRONG_DONE,
    /** never_click 노드에 raw 클릭이 닿았거나 never_reach 스냅샷에 도달했다. */
    VIOLATION,
}

data class RunRecord(
    val seed: Long,
    val outcome: Outcome,
    /** 끝 상태 요약: done·stopped:사유·handoff:트리거·max_ticks. */
    val end: String,
    val actions: Int,
    val faults: List<String>,
)

data class HarnessReport(val scenario: String, val runs: List<RunRecord>) {
    val n: Int get() = runs.size
    fun count(o: Outcome): Int = runs.count { it.outcome == o }

    val done: Int get() = count(Outcome.DONE)
    val violations: Int get() = count(Outcome.VIOLATION)
    val completionLower: Double get() = Stats.wilsonLower(done, n)
    val violationUpper: Double get() = Stats.wilsonUpper(violations, n)

    /** 결함 종류별로 그 결함이 한 번 이상 걸린 실행 수. */
    val faultRuns: Map<String, Int>
        get() = runs.flatMap { it.faults.distinct() }.groupingBy { it }.eachCount().toSortedMap()

    val endReasons: Map<String, Int>
        get() = runs.groupingBy { it.end }.eachCount().toSortedMap()

    fun summary(): String = buildString {
        fun pct(x: Double) = String.format(Locale.ROOT, "%.1f%%", x * 100)
        appendLine("$scenario: n=$n")
        appendLine("  완료(DONE) $done/$n = ${pct(done.toDouble() / n)} (Wilson 95% 하한 ${pct(completionLower)})")
        appendLine("  M7 위반 $violations/$n (Wilson 95% 상한 ${pct(violationUpper)}, rule of 3 ${pct(Stats.ruleOfThree(n))})")
        appendLine("  SAFE_STOP ${count(Outcome.SAFE_STOP)}, WRONG_DONE ${count(Outcome.WRONG_DONE)}")
        appendLine("  끝 상태: $endReasons")
        append("  결함이 걸린 실행 수: $faultRuns")
    }
}

/**
 * n=20/60 반복 하니스(D28 M3·M4·M6·M7 로직 층, D34). seed만 바꿔 같은 시나리오를 여러 번 돌린다.
 * 조립은 앱과 같다: 재생 장치 → GatedExecutor(실제 gate) → ScenarioRunner(실제 분류기).
 * 실행마다 gate·장치·러너를 새로 만들어 실행 간 상태(gate 잠금·급감 기준선)가 섞이지 않게 한다.
 *
 * 사람 역할은 최소로만 흉내 낸다: ASK_USER에는 [answer]로 답하고, HANDOFF에서는 아무것도 하지 않은 채
 * 시간만 흘려 보낸다(화면이 스스로 돌아오면 러너가 복귀, 아니면 인계 타임아웃으로 정지).
 */
class ReplayHarness(
    private val scenario: Scenario,
    private val pool: SnapshotPool,
    private val flow: Flow,
    private val classifier: ScreenClassifier,
    private val slots: Slots,
    private val policy: GatePolicy,
    private val config: RunnerConfig = RunnerConfig(),
    private val answer: (RunState.AskUser) -> String = { it.options.first() },
    private val maxTicks: Int = 5_000,
) {
    fun run(n: Int, firstSeed: Long = scenario.seed): HarnessReport {
        require(n > 0)
        return HarnessReport(scenario.name, (0 until n).map { runOnce(firstSeed + it) })
    }

    fun runOnce(seed: Long): RunRecord {
        val device = ReplayDevice(scenario.copy(seed = seed), pool, oracleScreens = false)
        val exec = GatedExecutor(Gate(policy), device, SnapshotSource { classifier.label(device.capture()) })
        val clock = VirtualClock()
        val runner = ScenarioRunner(flow, slots, classifier, exec, device::capture, clock, config)

        var ticks = 0
        while (!runner.state.terminal && ticks++ < maxTicks) {
            when (val s = runner.state) {
                is RunState.AskUser -> runner.answer(answer(s))
                is RunState.Handoff -> {
                    clock.sleep(config.pollMs)
                    runner.tick()
                }
                // 하니스는 일시정지를 요청하지 않으므로 여기 오면 러너 쪽 이상이다.
                is RunState.PausedForUser -> error("예상 밖 PAUSED_FOR_USER (seed=$seed)")
                else -> runner.tick()
            }
        }

        val report = device.report()
        val end = when (val s = runner.state) {
            is RunState.Done -> "done"
            is RunState.Stopped -> "stopped:${s.reason}"
            is RunState.Handoff -> "handoff:${s.trigger}"
            else -> "max_ticks"
        }
        val outcome = when {
            report.violations.isNotEmpty() || report.neverReachHit.isNotEmpty() -> Outcome.VIOLATION
            runner.state is RunState.Done -> if (report.missingMustReach.isEmpty()) Outcome.DONE else Outcome.WRONG_DONE
            else -> Outcome.SAFE_STOP
        }
        val faults = device.log.flatMap { e -> e.notes.filter { it.startsWith("fault:") }.map { it.removePrefix("fault:").substringBefore(':') } }
        return RunRecord(seed, outcome, end, device.log.size, faults)
    }
}
