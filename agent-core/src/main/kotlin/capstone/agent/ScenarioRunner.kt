package capstone.agent

import capstone.gate.ActionExecutor
import capstone.gate.ActionRequest
import capstone.gate.BlockReason
import capstone.gate.ConfirmMatcher
import capstone.gate.ExecResult
import capstone.gate.LabelNormalizer
import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiWindow

/** 시간 추상화. 재생기·테스트는 가상 시계를 써서 타임아웃을 즉시 지난다. */
interface AgentClock {
    fun nowMs(): Long
    fun sleep(ms: Long)

    object System : AgentClock {
        override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}

class VirtualClock(var now: Long = 0) : AgentClock {
    override fun nowMs(): Long = now
    override fun sleep(ms: Long) {
        now += ms
    }
}

/** 현재 화면 읽기(service 또는 재생기). agent-core는 gate-exec·service를 모르므로 따로 둔다. */
fun interface ScreenSource {
    fun capture(): Snapshot
}

/** HANDOFF 트리거(상세 ① 4절 ①a·①b·②·③). 사람에게 넘기고 조작을 멈춘다. */
enum class HandoffTrigger {
    /** ①a 빈 트리가 로딩 보류 상한을 넘김. */
    LOADING,
    /** ①b 화면에 data-sensitive 노드. */
    DATA_SENSITIVE,
    /** ② takeScreenshotOfWindow 오류 6(FLAG_SECURE). */
    SECURE_WINDOW,
    /** ③ 보안 키패드·비밀번호 입력(SENSITIVE_INPUT·LOGIN 화면). 비밀번호는 저장도 입력도 하지 않는다. */
    SENSITIVE_INPUT,
}

/** 실행 상태(상세 ① 4절). Done·Stopped는 끝 상태다. */
sealed interface RunState {
    data class Running(val step: Int) : RunState
    data class Handoff(val trigger: HandoffTrigger, val step: Int, val sinceMs: Long) : RunState
    data class AskUser(val step: Int, val question: String, val options: List<String>) : RunState
    /** D39: 사용자가 고정 알림으로 우리 Activity를 열었다. 이 상태에서는 액션 0. */
    data class PausedForUser(val step: Int, val lastScreen: String?) : RunState
    /** 확정 직전 화면에서 멈춤. 요약 카드 + "'예매하기'부터는 직접 눌러 주세요". */
    data class Done(val summary: String) : RunState
    /** 정지·안내(복구 사다리 끝, gate 잠금 등). */
    data class Stopped(val reason: String, val step: Int) : RunState

    val terminal: Boolean get() = this is Done || this is Stopped
}

data class RunEvent(val tMs: Long, val step: Int, val kind: String, val detail: String = "")

/** 수치는 모두 가설(상세 ① 4절 '초기값 일반 3~5초·검색 결과 10~15초'). 실측 p95×2로 보정한다. */
data class RunnerConfig(
    val pollMs: Long = 300,
    /** UNKNOWN·빈 트리를 로딩으로 보고 기다리는 상한(I-11의 호출자 정책). 넘으면 HANDOFF ①a. */
    val loadingHoldMs: Long = 5_000,
    val stepTimeoutMs: Long = 5_000,
    val handoffTimeoutMs: Long = 5 * 60_000,
    /** 처음 1회 + 재시도 1회(복구 사다리 1단). */
    val maxAttempts: Int = 2,
    val maxPopupDismiss: Int = 2,
    /** D17: 1건(한 번의 실행)당 조회 클릭 상한. 재시도로 다시 누른 것도 센다. */
    val maxQueries: Int = 3,
    /** D17: 조회 클릭 사이 최소 간격. 모자라면 기다렸다 누른다(D8 사람 같은 지연). */
    val minQueryGapMs: Long = 3_000,
)

/**
 * 상태머신 런타임(D3). 사전 정의 [Flow]만 실행하고, 모든 액션은 [ActionExecutor](gate)에만 제출한다(D36).
 *
 * 매 액션: pre-check(현재 화면 = 단계의 on) → 실행 → post-check(기대 화면, 타임아웃).
 * 실패하면 복구 사다리: 재시도 1회 → 알려진 팝업 사전 → back 1회 → 정지·안내.
 *
 * [tick]은 한 단계만 진행한다. 앱은 서비스 스레드에서 [run]이나 [tick]을 돌리고,
 * 다른 스레드에서 [pauseForUser]를 부를 수 있다: 다음 제출 전에 반드시 멈춘다.
 */
class ScenarioRunner(
    private val flow: Flow,
    slots: Slots,
    private val classifier: ScreenClassifier,
    private val executor: ActionExecutor,
    private val source: ScreenSource,
    private val clock: AgentClock = AgentClock.System,
    private val config: RunnerConfig = RunnerConfig(),
) {
    private val slots = slots.toMutableMap()
    private val _events = mutableListOf<RunEvent>()
    private var lastScreen: String? = null
    private var resumeCheck: String? = null
    private var currentStep = 0
    private var queries = 0
    private var lastQueryAt: Long? = null

    @Volatile private var pauseRequested = false

    var state: RunState = RunState.Running(0)
        private set

    val events: List<RunEvent> get() = _events.toList()
    val slotValues: Slots get() = slots.toMap()

    fun run(maxTicks: Int = 1_000): RunState {
        var n = 0
        while (state is RunState.Running && n++ < maxTicks) tick()
        return state
    }

    fun tick(): RunState {
        val s = state
        if (pauseRequested && !s.terminal && s !is RunState.PausedForUser) {
            pause()
            return state
        }
        try {
            when (s) {
                is RunState.Running -> runStep(s.step)
                is RunState.Handoff -> handoffTick(s)
                else -> Unit
            }
        } catch (_: PauseSignal) {
            pause()
        }
        return state
    }

    /** 스레드 안전. 진행 중인 대기는 다음 폴링에서, 진행 중인 단계는 다음 제출 전에 멈춘다. */
    fun pauseForUser() {
        pauseRequested = true
    }

    /** 사용자가 우리 Activity에서 '계속'을 고른 뒤. 다음 tick에서 직전 화면 서명을 다시 확인한다. */
    fun resumeFromPause() {
        val s = state as? RunState.PausedForUser ?: error("PAUSED_FOR_USER가 아님: $state")
        pauseRequested = false
        resumeCheck = s.lastScreen ?: Screens.UNKNOWN
        state = RunState.Running(s.step)
        event(s.step, "resume")
    }

    fun answer(option: String) {
        val s = state as? RunState.AskUser ?: error("ASK_USER가 아님: $state")
        require(option in s.options) { "보기에 없는 답: $option" }
        val step = flow.steps[s.step] as Step.Ask
        slots[step.slot] = option
        event(s.step, "answer", step.slot)
        state = RunState.Running(s.step)
    }

    // ---- 단계 실행 ----

    private fun runStep(i: Int) {
        currentStep = i
        resumeCheck?.let { prev ->
            resumeCheck = null
            val snap = settle() ?: return
            if (snap.screen != prev) {
                // D39: 직전 화면 서명 불일치 → 재탐색. 못 찾으면 정지.
                val j = flow.relocate(snap.screen) ?: return stop("resume_mismatch:$prev->${snap.screen}")
                event(i, "relocate", "$prev->${snap.screen} step=$j")
                state = RunState.Running(j)
                return
            }
        }

        val step = flow.steps[i]
        var attempts = 0
        var popups = 0
        var backed = false
        var acted = false
        while (true) {
            val snap = settle() ?: return
            if (isDone(step, snap, acted)) return complete(i, step)
            if (snap.screen == Screens.POPUP_KNOWN && step.on != Screens.POPUP_KNOWN && popups < config.maxPopupDismiss) {
                popups++
                if (!dismissPopup(snap)) return
                continue
            }
            if ((step.on == null || snap.screen == step.on) && attempts < config.maxAttempts) {
                if (attempts++ > 0) event(i, "retry")
                if (!act(i, step, snap)) return
                acted = true
                continue
            }
            if (!backed && snap.screen != Screens.FOREIGN_APP) {
                backed = true
                event(i, "back", "at=${snap.screen}")
                if (submit(snap, ActionRequest.GlobalBack(fgPackage(snap), snap.screen)) == null) return
                await(setOfNotNull(step.on), config.stepTimeoutMs)
                continue
            }
            return stop("ladder_exhausted:on=${step.on},at=${snap.screen}")
        }
    }

    private fun isDone(step: Step, snap: Snapshot, acted: Boolean): Boolean = when (step) {
        is Step.Click -> acted && snap.screen == step.then
        is Step.Back -> acted && snap.screen == step.then
        is Step.WaitFor -> snap.screen == step.screen
        is Step.Finish -> snap.screen == step.on
        is Step.Ask -> step.slot in slots
        is Step.Type -> snap.screen == step.on && typedValue(snap, step.target(slots))
            ?.let { it == norm(step.text(slots)) } == true
    }

    /** @return false면 상태가 바뀌었다(ASK_USER·HANDOFF·정지·일시정지) → 단계 루프를 나간다. */
    private fun act(i: Int, step: Step, snap: Snapshot): Boolean {
        when (step) {
            is Step.Click -> {
                val ref = resolveClickable(snap, step.target(slots), i) ?: return true
                if (looksLikeConfirm(snap, ref)) {
                    // 시나리오 정의 오류. gate도 막지만 제출 자체를 하지 않는다(이중 방어).
                    stop("flow_targets_confirm")
                    return false
                }
                if (step.query && !queryBudget()) return false
                submit(snap, ActionRequest.Click(ref, fgPackage(snap), snap.screen)) ?: return false
                if (step.query) {
                    queries++
                    lastQueryAt = clock.nowMs()
                }
                await(setOf(step.then), step.timeoutMs ?: config.stepTimeoutMs)
            }
            is Step.Type -> {
                val ref = resolveEditable(snap, step.target(slots), i) ?: return true
                submit(snap, ActionRequest.SetText(ref, step.text(slots), fgPackage(snap), snap.screen)) ?: return false
            }
            is Step.Back -> {
                submit(snap, ActionRequest.GlobalBack(fgPackage(snap), snap.screen)) ?: return false
                await(setOf(step.then), step.timeoutMs ?: config.stepTimeoutMs)
            }
            is Step.WaitFor -> await(setOf(step.screen), step.timeoutMs)
            is Step.Ask -> {
                val w = snap.foregroundAppWindow ?: return true
                val options = step.options(slots, w)
                if (options.isEmpty()) {
                    stop("ask_no_options:${step.slot}")
                    return false
                }
                state = RunState.AskUser(i, step.question, options)
                event(i, "ask", step.slot)
                return false
            }
            is Step.Finish -> Unit
        }
        return true
    }

    /** D17 조회 한도. 간격이 모자라면 기다린다(일시정지 요청은 그 사이에도 받는다). @return false면 정지했다. */
    private fun queryBudget(): Boolean {
        if (queries >= config.maxQueries) {
            stop("limit:queries")
            return false
        }
        val last = lastQueryAt ?: return true
        while (clock.nowMs() - last < config.minQueryGapMs) {
            checkPause()
            clock.sleep(minOf(config.pollMs, config.minQueryGapMs - (clock.nowMs() - last)))
        }
        return true
    }

    private fun complete(i: Int, step: Step) {
        event(i, "step_done")
        state = if (step is Step.Finish) {
            RunState.Done(step.summary(slots)).also { event(i, "done") }
        } else {
            RunState.Running(i + 1)
        }
    }

    private fun dismissPopup(snap: Snapshot): Boolean {
        val rule = flow.popups.filter { it.screen == snap.screen }
            .firstNotNullOfOrNull { r -> resolveClickable(snap, r.dismiss, currentStep, quiet = true) }
        if (rule == null) {
            event(currentStep, "popup_no_rule")
            return true
        }
        event(currentStep, "popup_dismiss")
        submit(snap, ActionRequest.Click(rule, fgPackage(snap), snap.screen)) ?: return false
        await(emptySet(), config.stepTimeoutMs, untilLeaves = snap.screen)
        return true
    }

    // ---- 관측 ----

    private fun observe(): Snapshot = classifier.label(source.capture()).also { lastScreen = it.screen }

    /**
     * 로딩 보류: UNKNOWN·FOREIGN_APP·재시도 가능한 스크린샷 오류는 [RunnerConfig.loadingHoldMs]까지 기다린다.
     * HANDOFF 트리거가 보이면 바로 넘긴다. @return null이면 HANDOFF로 갔다.
     */
    private fun settle(): Snapshot? {
        val start = clock.nowMs()
        var snap = observe()
        while (true) {
            handoffTrigger(snap)?.let { enterHandoff(it); return null }
            if (!isTransient(snap) || clock.nowMs() - start >= config.loadingHoldMs) break
            sleep()
            snap = observe()
        }
        if (snap.screen == Screens.UNKNOWN && isEmptyTree(snap)) {
            enterHandoff(HandoffTrigger.LOADING)
            return null
        }
        return snap
    }

    /** 화면이 [targets] 중 하나가 되거나(또는 [untilLeaves]를 벗어나거나) 팝업·HANDOFF 신호가 보일 때까지. */
    private fun await(targets: Set<String>, timeoutMs: Long, untilLeaves: String? = null) {
        val start = clock.nowMs()
        while (true) {
            val s = observe()
            if (s.screen in targets || (untilLeaves != null && s.screen != untilLeaves && !isTransient(s))) return
            if (s.screen == Screens.POPUP_KNOWN || handoffTrigger(s) != null) return
            if (clock.nowMs() - start >= timeoutMs) return
            sleep()
        }
    }

    private fun handoffTrigger(s: Snapshot): HandoffTrigger? = when {
        s.screenshotError == SCREENSHOT_ERROR_SECURE_WINDOW -> HandoffTrigger.SECURE_WINDOW
        s.screen == Screens.SENSITIVE_INPUT || s.screen == Screens.LOGIN -> HandoffTrigger.SENSITIVE_INPUT
        s.foregroundAppWindow?.nodes?.values?.any { it.visible && it.dataSensitive } == true -> HandoffTrigger.DATA_SENSITIVE
        else -> null
    }

    private fun isTransient(s: Snapshot): Boolean =
        s.screen == Screens.UNKNOWN || s.screen == Screens.FOREIGN_APP || s.screenshotError != null

    private fun isEmptyTree(s: Snapshot): Boolean {
        val w = s.foregroundAppWindow ?: return true
        return w.root == null || w.nodes.values.none { it.hasLabel || it.interactive }
    }

    // ---- HANDOFF ----

    private fun enterHandoff(trigger: HandoffTrigger) {
        state = RunState.Handoff(trigger, currentStep, clock.nowMs())
        event(currentStep, "handoff", trigger.name)
    }

    /** D14: 다음 화면이 분류되면 복귀(주 신호), 5분이 지나도 안 돌아오면 정지·안내(안전망). */
    private fun handoffTick(h: RunState.Handoff) {
        currentStep = h.step
        val s = observe()
        if (handoffTrigger(s) == null && !isTransient(s)) {
            val j = flow.relocate(s.screen)
            if (j != null) {
                event(h.step, "handoff_return", "${s.screen} step=$j")
                state = RunState.Running(j)
                return
            }
        }
        if (clock.nowMs() - h.sinceMs >= config.handoffTimeoutMs) stop("handoff_timeout:${h.trigger}")
    }

    // ---- 제출(gate만) ----

    /** @return null이면 상태가 정지로 바뀌었다. 확정 계열·패키지 차단은 gate가 잠그므로 재시도하지 않는다(I-12). */
    private fun submit(snap: Snapshot, req: ActionRequest): ExecResult? {
        checkPause()
        val r = executor.execute(snap, req)
        event(currentStep, "exec", "${req.kind}@${snap.screen} -> ${describe(r)}")
        if (r is ExecResult.Blocked) {
            val reason = r.decision.reason
            if (reason.isConfirm || reason == BlockReason.PACKAGE_NOT_ALLOWED || reason == BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH) {
                stop("gate:$reason")
                return null
            }
        }
        return r
    }

    private fun describe(r: ExecResult): String = when (r) {
        ExecResult.Done -> "done"
        is ExecResult.Blocked -> "blocked:${r.decision.reason}"
        is ExecResult.Failed -> "failed:${r.message}"
    }

    private fun pause() {
        pauseRequested = false
        state = RunState.PausedForUser(currentStep, lastScreen)
        event(currentStep, "paused", lastScreen.orEmpty())
    }

    private fun checkPause() {
        if (pauseRequested) throw PauseSignal
    }

    private fun sleep() {
        checkPause()
        clock.sleep(config.pollMs)
    }

    private fun stop(reason: String) {
        state = RunState.Stopped(reason, currentStep)
        event(currentStep, "stopped", reason)
    }

    private fun event(step: Int, kind: String, detail: String = "") {
        _events += RunEvent(clock.nowMs(), step, kind, detail)
    }

    // ---- 노드 해석 ----

    private fun fgPackage(s: Snapshot): String? = s.foregroundAppWindow?.packageName

    /** 맞는 노드의 '클릭이 닿는 노드'(자신 또는 가장 가까운 클릭 가능 조상)가 정확히 하나일 때만. */
    private fun resolveClickable(snap: Snapshot, m: NodeMatcher, i: Int, quiet: Boolean = false): NodeRef? {
        val w = snap.foregroundAppWindow ?: return null
        val keys = w.nodes.filterValues(m::matches).keys
            .mapNotNull { k -> w.ancestorsOf(k).firstOrNull { w.nodes.getValue(it).clickable && w.nodes.getValue(it).enabled } }
            .distinct()
        return unique(w, keys, m, i, quiet)
    }

    private fun resolveEditable(snap: Snapshot, m: NodeMatcher, i: Int): NodeRef? {
        val w = snap.foregroundAppWindow ?: return null
        val keys = w.nodes.filter { (_, n) -> n.editable && n.enabled && m.matches(n) }.keys.toList()
        return unique(w, keys, m, i, quiet = false)
    }

    private fun unique(w: UiWindow, keys: List<String>, m: NodeMatcher, i: Int, quiet: Boolean): NodeRef? {
        if (keys.size == 1) return NodeRef.of(w, keys[0])
        if (!quiet) event(i, if (keys.isEmpty()) "target_not_found" else "target_ambiguous", m.toString())
        return null
    }

    private fun typedValue(snap: Snapshot, m: NodeMatcher): String? {
        val w = snap.foregroundAppWindow ?: return null
        return w.nodes.values.singleOrNull { it.editable && m.matches(it) }?.let { norm(it.text) }
    }

    private fun looksLikeConfirm(snap: Snapshot, ref: NodeRef): Boolean {
        val w = snap.window(ref.windowId) ?: return true
        return (listOf(ref.key) + w.descendantsOf(ref.key)).any { ConfirmMatcher.hardReason(w.nodes.getValue(it)) != null }
    }

    private fun norm(s: String?): String = LabelNormalizer.normalize(s)

    /** 일시정지 요청을 대기·제출 지점에서 단계 루프 밖으로 풀어 내는 신호(스택 추적 없음). */
    private object PauseSignal : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        /** AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34). */
        const val SCREENSHOT_ERROR_SECURE_WINDOW: Int = 6
    }
}
