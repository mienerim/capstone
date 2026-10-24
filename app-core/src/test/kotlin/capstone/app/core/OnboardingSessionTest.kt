package capstone.app.core

import capstone.agent.Flow
import capstone.agent.RunState
import capstone.agent.RunnerConfig
import capstone.agent.ScenarioRunner
import capstone.agent.Slots
import capstone.agent.VirtualClock
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.exec.GatedExecutor
import capstone.llm.DateRel
import capstone.llm.Intent
import capstone.llm.ParseResult
import capstone.llm.SlotsV1
import capstone.llm.TargetApp
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import capstone.tools.replay.ReplayDevice
import capstone.tools.replay.SnapshotPool
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import capstone.tools.replay.Scenario as ReplayScenario

/**
 * 온보딩 ↔ AppSession 통합(MainActivity가 하는 조립을 그대로): 같은 저장소를 쓰고, 실행 직전 [Onboarding.startBlocker]를 본다.
 * 대기 피드백(D73)은 가상 시계로 돌린다.
 */
class OnboardingSessionTest {
    private var now = ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, DailyRunLimiter.KST).toInstant().toEpochMilli()
    private val fixtures = File(System.getProperty("capstone.fixtures"), "mock")
    private val store = MemoryStore()
    private val device = object : DeviceChecks {
        var notifications = true
        var accessibility = true
        var ap: Boolean? = null
        override fun notificationsGranted() = notifications
        override fun accessibilityEnabled() = accessibility
        override fun advancedProtection() = ap
    }
    private val started = mutableListOf<Slots>()
    private lateinit var replay: ReplayDevice

    private fun startReplay(flow: Flow, slots: Slots): ScenarioRunner {
        started += slots
        val file = File(fixtures, "happy.json")
        val sc = ReplayScenario.read(file)
        replay = ReplayDevice(sc, SnapshotPool.forScenario(file, sc), oracleScreens = false)
        val classifier = MockKorail.classifier()
        val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
        val exec = GatedExecutor(gate, replay) { classifier.label(replay.capture()) }
        return ScenarioRunner(flow, slots, classifier, exec, replay::capture, VirtualClock(), RunnerConfig())
    }

    private fun onboarding() = Onboarding(store, device, nowMs = { now })
    private fun session() = AppSession(
        store,
        scenarioFor = { s -> if (s.intent == Intent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = false) else null },
        startRunner = ::startReplay,
        nowMs = { now },
    )

    /** MainActivity.startRun과 같은 순서: 막힘이 있으면 실행하지 않고 처음 화면에 안내. */
    private fun startRun(o: Onboarding, s: AppSession) {
        o.startBlocker()?.let { s.reset(it); return }
        s.confirm(true)
    }

    private val seoulBusan = SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TODAY)

    private fun finishOnboarding(): Onboarding = onboarding().apply {
        next(); accept(); chooseHelp(HelpMode.ALONE)
        assertEquals(OnboardingScreen.Ready, screen)
        finish()
    }

    @Test
    fun `온보딩 동의가 AppSession에도 그대로 쓰이고, 연습 1회는 목업이라 일일 한도를 쓰지 않는다`() {
        val o = finishOnboarding()
        val s = session()
        assertEquals(AppScreen.Home(), s.screen, "온보딩 ②에서 한 동의를 다시 묻지 않는다")
        val before = s.remainingToday()

        s.startWithButtons() // ⑦ '연습해 보기'
        s.choose("서울"); s.choose("부산"); s.choose("오늘")
        assertIs<AppScreen.Confirm>(s.screen)
        startRun(o, s)
        val r = s.runner!!
        s.onRunState(r.run())
        s.answer("KTX 105")
        s.onRunState(r.run())
        assertTrue(assertIs<AppScreen.Finished>(s.screen).success)
        assertTrue(replay.report().passed, replay.report().toString())
        assertEquals(before, s.remainingToday())
    }

    @Test
    fun `실행 직전에 알림·접근성이 꺼졌거나 AP가 켜졌으면 러너를 만들지 않는다`() {
        val o = finishOnboarding()
        val s = session()
        fun attempt(): AppScreen {
            s.reset()
            s.onParsed(ParseResult.Ok(seoulBusan))
            startRun(o, s)
            return s.screen
        }
        device.notifications = false
        assertEquals(AppScreen.Home(Onboarding.NOTIFICATIONS_OFF_TEXT), attempt())
        device.notifications = true
        device.accessibility = false
        assertEquals(AppScreen.Home("먼저 접근성에서 이 앱을 켜 주세요."), attempt())
        device.accessibility = true
        device.ap = true
        assertEquals(AppScreen.Home(Onboarding.PROTECTED_TEXT), attempt())
        assertTrue(started.isEmpty())
        assertNull(s.runner)

        device.ap = null
        attempt()
        assertEquals(1, started.size)
    }

    @Test
    fun `고지 문구 버전이 바뀌면 온보딩과 AppSession이 모두 동의를 다시 요구하고, 온보딩에서 동의하면 둘 다 풀린다`() {
        finishOnboarding()
        store.put("consent.version", (Disclosure.VERSION - 1).toString())
        val o = onboarding()
        assertFalse(o.isDone)
        assertEquals(AppScreen.Consent(), session().screen)
        o.accept()
        o.finish()
        assertTrue(o.isDone)
        assertEquals(AppScreen.Home(), session().screen)
    }

    @Test
    fun `발화 대기 - 3초 안에 결과가 오면 필러 없이 바로 복창`() {
        finishOnboarding()
        val s = session()
        val t = s.beginParsing()
        assertEquals(listOf<WaitCue>(WaitCue.Thinking), s.onWaitTick())
        assertEquals(AppScreen.Waiting(WaitFeedback.THINKING_TEXT), s.screen)
        assertFalse(s.acceptsRequest, "기다리는 중에는 새 요청을 받지 않는다")
        now += 2_500
        assertTrue(s.onWaitTick().isEmpty())
        assertTrue(s.onParsed(ParseResult.Ok(seoulBusan), t))
        assertIs<AppScreen.Confirm>(s.screen)
        now += 30_000
        assertTrue(s.onWaitTick().isEmpty(), "끝난 대기는 신호를 내지 않는다")
    }

    @Test
    fun `발화 대기 - 필러, 계속 기다리기, 25초 안전 중단 뒤 늦은 결과는 버린다`() {
        finishOnboarding()
        val s = session()
        val t = s.beginParsing()
        s.onWaitTick()
        now += 3_000
        val filler = assertIs<WaitCue.Filler>(s.onWaitTick().single())
        assertEquals(AppScreen.Waiting(filler.text), s.screen)
        assertTrue(WaitFeedback.DEFAULT_TASK in filler.text)
        now += 7_000
        s.onWaitTick()
        assertEquals(AppScreen.Waiting(WaitFeedback.ASK_TEXT, asking = true), s.screen)
        s.answerWait(keep = true)
        assertEquals(AppScreen.Waiting(WaitFeedback.ASK_TEXT, asking = false), s.screen)
        now += 15_000
        assertEquals(listOf<WaitCue>(WaitCue.Abort), s.onWaitTick())
        assertEquals(AppScreen.Home(WaitFeedback.ABORT_TEXT), s.screen)

        assertFalse(s.onParsed(ParseResult.Ok(seoulBusan), t), "중단 뒤 결과")
        assertEquals(AppScreen.Home(WaitFeedback.ABORT_TEXT), s.screen)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `발화 대기 - 그만하면 처음으로, 다음 요청의 결과만 받는다`() {
        finishOnboarding()
        val s = session()
        val old = s.beginParsing()
        now += 10_000
        s.onWaitTick()
        s.answerWait(keep = false)
        assertEquals(AppScreen.Home(), s.screen)

        val fresh = s.beginParsing()
        assertFalse(s.onParsed(ParseResult.Ok(seoulBusan), old), "앞 요청의 늦은 결과")
        assertIs<AppScreen.Waiting>(s.screen)
        assertTrue(s.onParsed(ParseResult.AskAgain(1, "x"), fresh))
        assertTrue("한 번 더" in assertIs<AppScreen.Home>(s.screen).message!!)
    }
}
