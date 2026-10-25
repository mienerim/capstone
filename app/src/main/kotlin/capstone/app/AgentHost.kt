package capstone.app

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import capstone.agent.Flow
import capstone.agent.RunState
import capstone.agent.ScenarioRunner
import capstone.agent.Slots
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.JsonlDecisionSink
import capstone.gate.exec.GatedExecutor
import capstone.scenarios.MockKorail
import capstone.service.DumperService
import java.io.File

/**
 * 실행 경로 조립(D36): 접근성 서비스의 raw 실행기 → GatedExecutor(유일한 실행 통로) → 상태머신.
 * 상태머신은 작업 스레드에서 돌리고, 상태가 바뀔 때마다 [onState]를 메인 스레드로 부른다.
 * 실행할 때마다 gate를 새로 만든다(잠금·급감 기준선이 실행 사이에 섞이지 않게, I-36과 같은 이유).
 *
 * 미빌드(클라우드 Google Maven 차단). 10-26 팀 PC에서 컴파일.
 */
class AgentHost(
    private val filesDir: File,
    private val onState: (ScenarioRunner, RunState) -> Unit,
) {
    private val worker = HandlerThread("agent").also { it.start() }
    private val bg = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private var runner: ScenarioRunner? = null
    private var proofLog: JsonlDecisionSink? = null

    /** 접근성 서비스가 켜져 있어야 실행할 수 있다. */
    val ready: Boolean get() = DumperService.instance != null

    fun start(flow: Flow, slots: Slots): ScenarioRunner {
        val svc = checkNotNull(DumperService.instance) { "접근성 서비스 꺼짐" }
        runCatching { proofLog?.close() } // 쓰기 실패로 끊긴 로그도 닫기에서 앱을 죽이지 않는다
        // 증명 로그(M7): gate의 모든 판정을 기기 안 파일에. run-as로 꺼낸다.
        // 싱크가 판정마다 flush한다(실행 전에 디스크에, 쓰기 실패면 gate가 막음, I-98).
        val log = JsonlDecisionSink.appendingTo(File(filesDir, PROOF_LOG)).also { proofLog = it }
        val gate = Gate(GatePolicy(allowedPackages = flow.packages, ownPackage = OWN_PACKAGE), log)
        val classifier = MockKorail.classifier()
        val exec = GatedExecutor(gate, svc.actuator, svc.snapshots)
        return ScenarioRunner(flow, slots, classifier, exec, { svc.snapshots.capture() }).also { runner = it }
    }

    /** 러너가 진행할 수 있는 상태(RUN·HANDOFF)면 작업 스레드에서 이어 돌린다. */
    fun kick() {
        bg.removeCallbacks(loop)
        bg.post(loop)
    }

    private val loop = object : Runnable {
        override fun run() {
            val r = runner ?: return
            val st = r.tick()
            // 러너도 함께 넘긴다: '그만하기'·새 실행 뒤에 도착한 지난 러너의 상태는 세션이 버린다(I-85).
            main.post { onState(r, st) }
            when (st) {
                is RunState.Running -> bg.post(this)
                // HANDOFF 중에는 사람이 조작하므로 천천히 관측만 한다(복귀 감지, D14).
                is RunState.Handoff -> bg.postDelayed(this, HANDOFF_POLL_MS)
                else -> Unit
            }
        }
    }

    fun pause() {
        runner?.pauseForUser()
    }

    fun close() {
        runner?.pauseForUser()
        runner = null
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        runCatching { proofLog?.close() } // 쓰기 실패로 끊긴 로그도 닫기에서 앱을 죽이지 않는다
    }

    companion object {
        const val OWN_PACKAGE = "capstone.app"
        const val PROOF_LOG = "gate.jsonl"
        private const val HANDOFF_POLL_MS = 1_000L
    }
}
