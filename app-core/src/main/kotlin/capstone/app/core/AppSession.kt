package capstone.app.core

import capstone.agent.Flow
import capstone.agent.RunState
import capstone.agent.ScenarioRunner
import capstone.agent.Slots
import capstone.llm.DateRel
import capstone.llm.Intent
import capstone.llm.ParseResult
import capstone.llm.SlotName
import capstone.llm.SlotsV1
import capstone.llm.Stations
import capstone.llm.TargetApp
import capstone.llm.UnknownReason
import java.time.Instant
import java.time.LocalDate

/** 실행할 시나리오. [liveService]가 true면 실서비스라 D17 일일 한도를 센다(목업은 세지 않음). */
class Scenario(
    val flow: Flow,
    val liveService: Boolean,
    /** 검증된 슬롯 v1 → 상태머신 슬롯(이름은 D30 wire). */
    val toFlowSlots: (SlotsV1, LocalDate) -> Slots = ::defaultFlowSlots,
)

fun defaultFlowSlots(s: SlotsV1, today: LocalDate): Slots = buildMap {
    s.origin?.let { put(SlotName.ORIGIN.wire, it) }
    s.destination?.let { put(SlotName.DESTINATION.wire, it) }
    s.resolveDate(today)?.let { put(SlotName.DATE.wire, it.toString()) }
    s.timeFrom?.let { put(SlotName.TIME_FROM.wire, it) }
    s.trainType?.let { put(SlotName.TRAIN_TYPE.wire, it.wire) }
}

/** 앱 화면 하나(Activity가 그대로 그린다). 모든 단계는 큰 버튼으로 끝낼 수 있다(D16·D41). */
sealed interface AppScreen {
    data class Consent(val declined: Boolean = false) : AppScreen
    data class Home(val message: String? = null) : AppScreen
    /** 발화 종료 ~ 복창 시작(D73). [asking]이면 '계속 기다릴까요?' 버튼 2개를 그린다. */
    data class Waiting(val text: String, val asking: Boolean = false) : AppScreen
    data class AskSlot(val slot: SlotName, val question: String, val options: List<String>) : AppScreen
    data class Confirm(val text: String) : AppScreen
    /** 실행 중. [run]의 종류에 따라 버튼(AskUser 보기·계속·멈춤)을 그린다. */
    data class Running(val text: String, val run: RunState) : AppScreen
    /** 끝(완료·정지·범위 밖·한도). 버튼은 '처음으로' 하나. */
    data class Finished(val text: String, val success: Boolean) : AppScreen
}

/**
 * 앱 흐름(상세 ① 1절): 고지·동의 → 요청(버튼 또는 발화) → 빈 슬롯은 큰 버튼으로 묻기 → 복창·확인 → 실행 → 요약.
 *
 * - 실행은 사용자가 확인 버튼을 누른 뒤에만 시작한다(D16, Play 방어선 2).
 * - LLM은 앱이 [SlotParser][capstone.llm.SlotParser]로 돌리고(D32, 작업 스레드), 결과만 [onParsed]로 받는다.
 *   발화가 2번 연속 실패하면 버튼으로 묻는다(D16).
 * - 상태머신을 돌리는 스레드는 앱(서비스) 몫이고, 이 클래스는 상태를 바꾸고 화면을 정한다.
 *   Android에서는 메인 스레드에서만 부른다고 가정한다(I-46).
 */
class AppSession(
    store: KeyValueStore,
    private val scenarioFor: (SlotsV1) -> Scenario?,
    private val startRunner: (Flow, Slots) -> ScenarioRunner,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val wait: WaitFeedback = WaitFeedback(),
) {
    private val consent = ConsentManager(store)
    private val limiter = DailyRunLimiter(store)
    private var slots: SlotsV1? = null
    private var parseFailures = 0
    private var ticket = 0

    var runner: ScenarioRunner? = null
        private set

    var screen: AppScreen = if (consent.required) AppScreen.Consent() else AppScreen.Home()
        private set

    // ---- 고지·동의 ----

    fun acceptConsent() {
        check(screen is AppScreen.Consent)
        consent.accept(nowMs())
        screen = AppScreen.Home()
    }

    fun declineConsent() {
        check(screen is AppScreen.Consent)
        screen = AppScreen.Consent(declined = true)
    }

    // ---- 요청 ----

    /** 말 대신 버튼으로 시작: 기차 예매, 출발·도착·날짜를 차례로 묻는다. */
    fun startWithButtons() {
        requireIdle()
        slots = SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS)
        next()
    }

    /** 요청을 받을 수 있는 화면인가(동의 전·실행 중에는 발화를 파서에 넘기지 않는다). */
    val acceptsRequest: Boolean get() = screen is AppScreen.Home || screen is AppScreen.Finished

    /**
     * 발화가 끝나 파싱을 시작한다(D73 ①). 돌려준 번호를 [onParsed]에 그대로 넘긴다 —
     * 안전 중단·'그만' 뒤에 늦게 온 결과는 번호가 달라 버린다.
     */
    fun beginParsing(): Int {
        requireIdle()
        wait.start(nowMs())
        screen = AppScreen.Waiting(WaitFeedback.THINKING_TEXT)
        return ++ticket
    }

    /**
     * 대기 중 주기적으로(약 0.5초) 부른다. 새로 낼 신호를 돌려준다 — 앱이 확인음·필러 음성으로 바꾼다.
     * 25초가 되면 화면을 처음으로 돌리고 결과를 버린다(D73 ④).
     */
    fun onWaitTick(): List<WaitCue> {
        if (screen !is AppScreen.Waiting) return emptyList()
        val cues = wait.poll(nowMs())
        cues.forEach { c ->
            screen = when (c) {
                WaitCue.Thinking -> AppScreen.Waiting(WaitFeedback.THINKING_TEXT, wait.asking)
                is WaitCue.Filler -> AppScreen.Waiting(c.text, wait.asking)
                WaitCue.AskContinue -> AppScreen.Waiting(WaitFeedback.ASK_TEXT, asking = true)
                WaitCue.Abort -> {
                    ticket++
                    AppScreen.Home(WaitFeedback.ABORT_TEXT)
                }
            }
        }
        return cues
    }

    /** '계속 기다릴게요'(true) / '그만할게요'(false). */
    fun answerWait(keep: Boolean) {
        val w = screen as? AppScreen.Waiting ?: return
        if (keep) {
            wait.keepWaiting()
            screen = w.copy(asking = false)
        } else {
            wait.stop()
            ticket++
            screen = AppScreen.Home()
        }
    }

    /**
     * 발화 파싱 결과(SlotParser.parse). [parseTicket]은 [beginParsing]이 준 번호이고,
     * 지금 기다리는 번호가 아니면 버린다(false). 번호 없이 부르면 대기 없이 바로 받는다(요청 화면에서만).
     */
    fun onParsed(r: ParseResult, parseTicket: Int? = null): Boolean {
        if (parseTicket != null) {
            if (screen !is AppScreen.Waiting || parseTicket != ticket) return false
            wait.stop()
            screen = AppScreen.Home()
        }
        requireIdle()
        when (r) {
            is ParseResult.Ok -> {
                parseFailures = 0
                slots = r.slots
                next()
            }
            is ParseResult.OutOfScope -> {
                parseFailures = 0
                screen = AppScreen.Finished(outOfScope(r.reason), success = false)
            }
            is ParseResult.AskAgain -> {
                if (++parseFailures >= 2) {
                    parseFailures = 0
                    screen = AppScreen.Home("잘 못 알아들었어요. 아래 '버튼으로 찾기'를 눌러 주세요.")
                } else {
                    screen = AppScreen.Home("잘 못 알아들었어요. 한 번 더 말씀해 주세요.")
                }
            }
        }
        return true
    }

    /** [AppScreen.AskSlot]의 보기 하나를 골랐다. */
    fun choose(option: String) {
        val ask = screen as? AppScreen.AskSlot ?: error("AskSlot이 아님: $screen")
        require(option in ask.options) { "보기에 없는 값: $option" }
        val s = slots ?: error("슬롯 없음")
        slots = when (ask.slot) {
            SlotName.ORIGIN -> s.copy(origin = option)
            SlotName.DESTINATION -> s.copy(destination = option)
            SlotName.DATE -> s.copy(date = null, dateRel = DATE_OPTIONS.getValue(option))
            else -> error("버튼으로 묻지 않는 슬롯: ${ask.slot}")
        }
        next()
    }

    /** 복창 화면의 '네'/'아니요'. */
    fun confirm(yes: Boolean) {
        check(screen is AppScreen.Confirm)
        val s = slots ?: error("슬롯 없음")
        if (!yes) {
            reset("다시 말씀해 주세요.")
            return
        }
        val scenario = scenarioFor(s) ?: run {
            screen = AppScreen.Finished("아직 이 요청은 도와드릴 수 없어요. 기차 예매만 됩니다.", success = false)
            return
        }
        if (scenario.liveService && !limiter.tryStart(nowMs())) {
            screen = AppScreen.Finished("오늘은 더 진행할 수 없어요(하루 5번까지). 내일 다시 해 주세요.", success = false)
            return
        }
        val r = startRunner(scenario.flow, scenario.toFlowSlots(s, today()))
        runner = r
        onRunState(r.state)
    }

    // ---- 실행 중 ----

    /** 러너 상태가 바뀔 때마다 앱이 부른다. */
    fun onRunState(st: RunState) {
        screen = when (st) {
            is RunState.Done -> AppScreen.Finished(st.summary, success = true)
            is RunState.Stopped -> AppScreen.Finished(Readback.stopped(st.reason), success = false)
            else -> AppScreen.Running(Readback.runState(st), st)
        }
    }

    fun answer(option: String) {
        val r = runner ?: error("실행 중 아님")
        r.answer(option)
        onRunState(r.state)
    }

    /** 고정 알림·'멈춰' 버튼(D39). 진행 중 단계는 다음 제출 전에 멈춘다. */
    fun pause() {
        runner?.pauseForUser()
    }

    fun resume() {
        val r = runner ?: error("실행 중 아님")
        r.resumeFromPause()
        onRunState(r.state)
    }

    /** '처음으로'. 실행 중이면 먼저 멈춘다(러너를 버리면 더 제출하지 않는다). */
    fun reset(message: String? = null) {
        if (screen is AppScreen.Waiting) {
            wait.stop()
            ticket++
        }
        runner?.pauseForUser()
        runner = null
        slots = null
        screen = if (consent.required) AppScreen.Consent() else AppScreen.Home(message)
    }

    fun remainingToday(): Int = limiter.remaining(nowMs())

    // ---- 내부 ----

    private fun next() {
        val s = slots ?: return
        if (s.intent != Intent.TRAIN_BOOK) {
            screen = AppScreen.Finished("병원 예약은 아직 준비 중이에요. 기차 예매만 됩니다.", success = false)
            return
        }
        val missing = s.requiredMissing().firstOrNull()
        screen = if (missing != null) {
            AppScreen.AskSlot(missing, Readback.question(missing), options(missing, s))
        } else {
            AppScreen.Confirm(Readback.confirm(s, today()))
        }
    }

    private fun options(slot: SlotName, s: SlotsV1): List<String> = when (slot) {
        SlotName.ORIGIN -> MAJOR_STATIONS.filter { it != s.destination }
        SlotName.DESTINATION -> MAJOR_STATIONS.filter { it != s.origin }
        SlotName.DATE -> DATE_OPTIONS.keys.toList()
        else -> emptyList()
    }

    private fun requireIdle() {
        check(acceptsRequest) { "요청을 받을 수 없는 화면: $screen" }
        runner = null
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(DailyRunLimiter.KST).toLocalDate()

    private fun outOfScope(r: UnknownReason): String = when (r) {
        UnknownReason.UNSUPPORTED_APP -> "SRT 등 다른 앱은 도와드릴 수 없어요. 코레일+ 기차 예매만 됩니다."
        UnknownReason.OUT_OF_SCOPE -> "결제·취소 같은 일은 도와드릴 수 없어요. 기차 찾기만 됩니다."
        UnknownReason.UNCLEAR -> "무슨 말씀인지 잘 모르겠어요. 예: \"내일 부산 가는 KTX 예매해 줘\""
    }

    companion object {
        /** 큰 버튼으로 보여 줄 주요 역(8개, 화면 한 장). 목록 전체([Stations.KORAIL])는 말로 고른다. */
        val MAJOR_STATIONS: List<String> = listOf("서울", "용산", "수원", "대전", "동대구", "부산", "광주송정", "강릉")
            .also { list -> require(list.all { it in Stations }) }

        val DATE_OPTIONS: Map<String, DateRel> = linkedMapOf("오늘" to DateRel.TODAY, "내일" to DateRel.TOMORROW, "모레" to DateRel.DAY_AFTER_TOMORROW)
    }
}
