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
import capstone.llm.SlotName
import capstone.llm.SlotsV1
import capstone.llm.TargetApp
import capstone.llm.UnknownReason
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

/** 앱 흐름을 실제 조립(재생기 + gate + 목업 시나리오)으로 끝까지 돌린다. */
class AppSessionTest {
    private val now = ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, DailyRunLimiter.KST).toInstant().toEpochMilli()
    private val fixtures = File(System.getProperty("capstone.fixtures"), "mock")

    private var parseResult: ParseResult = ParseResult.AskAgain(1, "unset")
    private val parsed = mutableListOf<String>()

    /** Android의 작업 스레드 파싱을 흉내: 받을 수 있는 화면일 때만 파서를 부른다. */
    private fun AppSession.submitUtterance(text: String) {
        check(acceptsRequest)
        parsed += text
        onParsed(parseResult)
    }
    private val started = mutableListOf<Slots>()
    private var live = false
    private lateinit var device: ReplayDevice

    private fun startReplay(flow: Flow, slots: Slots): ScenarioRunner {
        started += slots
        val file = File(fixtures, "happy.json")
        val sc = ReplayScenario.read(file)
        device = ReplayDevice(sc, SnapshotPool.forScenario(file, sc), oracleScreens = false)
        val classifier = MockKorail.classifier()
        val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
        val exec = GatedExecutor(gate, device) { classifier.label(device.capture()) }
        return ScenarioRunner(flow, slots, classifier, exec, device::capture, VirtualClock(), RunnerConfig())
    }

    private fun session(store: KeyValueStore = MemoryStore()) = AppSession(
        store,
        scenarioFor = { s -> if (s.intent == Intent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = live, applies = MockKorailFlow.APPLIED_SLOTS) else null },
        startRunner = ::startReplay,
        nowMs = { now },
    )

    private fun consented(): AppSession = session().also { it.acceptConsent() }

    @Test
    fun `첫 실행은 고지·동의 화면, 거부하면 그대로 머문다`() {
        val store = MemoryStore()
        val s = session(store)
        assertEquals(AppScreen.Consent(), s.screen)
        s.declineConsent()
        assertEquals(AppScreen.Consent(declined = true), s.screen)
        s.acceptConsent()
        assertIs<AppScreen.Home>(s.screen)
        assertIs<AppScreen.Home>(session(store).screen) // 동의는 기기에 남는다
    }

    @Test
    fun `동의 전에는 요청을 받지 않는다`() {
        val s = session()
        assertTrue(runCatching { s.startWithButtons() }.isFailure)
        assertTrue(runCatching { s.submitUtterance("내일 부산") }.isFailure)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `버튼만으로 출발·도착·날짜를 고르고 확인하면 실행해 좌석 화면에서 멈춘다`() {
        val s = consented()
        s.startWithButtons()
        val origin = assertIs<AppScreen.AskSlot>(s.screen)
        assertEquals(SlotName.ORIGIN, origin.slot)
        assertTrue(origin.options.size <= 8)
        s.choose("서울")
        val dest = assertIs<AppScreen.AskSlot>(s.screen)
        assertFalse("서울" in dest.options) // 출발역은 도착 보기에서 뺀다
        s.choose("부산")
        s.choose("내일")
        val confirm = assertIs<AppScreen.Confirm>(s.screen)
        assertEquals("내일 10월 24일(토) 서울에서 부산 가는 열차를 찾을까요? 날짜는 제가 넣지 않아요. 앱 화면에서 직접 확인해 주세요.", confirm.text)
        assertTrue(started.isEmpty()) // 확인 전에는 실행하지 않는다(D16)

        s.confirm(true)
        assertEquals(mapOf("origin" to "서울", "destination" to "부산", "date" to "2026-10-24"), started.single())
        val r = s.runner!!
        s.onRunState(r.run())
        val ask = assertIs<AppScreen.Running>(s.screen)
        assertIs<RunState.AskUser>(ask.run)
        s.answer("KTX 105")
        s.onRunState(r.run())
        val done = assertIs<AppScreen.Finished>(s.screen)
        assertTrue(done.success && "직접 눌러" in done.text, done.text)
        assertTrue(device.report().passed, device.report().toString())
        assertNull(device.log.firstOrNull { "예매하기" in it.toString() })
    }

    @Test
    fun `발화로 모든 슬롯이 오면 바로 복창, 빠진 것만 버튼으로 묻는다`() {
        val s = consented()
        parseResult = ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TODAY))
        s.submitUtterance("오늘 서울에서 부산")
        assertIs<AppScreen.Confirm>(s.screen)

        s.reset()
        parseResult = ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, destination = "부산", dateRel = DateRel.TODAY))
        s.submitUtterance("오늘 부산")
        assertEquals(SlotName.ORIGIN, assertIs<AppScreen.AskSlot>(s.screen).slot)
    }

    @Test
    fun `알아듣지 못하면 한 번 더, 두 번째는 버튼으로 안내(D16)`() {
        val s = consented()
        parseResult = ParseResult.AskAgain(2, "watchdog:timeout")
        s.submitUtterance("음…")
        assertTrue("한 번 더" in assertIs<AppScreen.Home>(s.screen).message!!)
        s.submitUtterance("음…")
        assertTrue("버튼" in assertIs<AppScreen.Home>(s.screen).message!!)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `범위 밖 요청과 병원 요청은 실행하지 않고 안내`() {
        val s = consented()
        parseResult = ParseResult.OutOfScope(UnknownReason.UNSUPPORTED_APP)
        s.submitUtterance("SRT 예매해 줘")
        assertFalse(assertIs<AppScreen.Finished>(s.screen).success)

        parseResult = ParseResult.Ok(SlotsV1(Intent.HOSPITAL_SEARCH, TargetApp.GOODDOC))
        s.submitUtterance("내과 찾아 줘")
        assertIs<AppScreen.Finished>(s.screen)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `복창에 아니요면 실행하지 않고 처음으로`() {
        val s = consented()
        parseResult = ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TODAY))
        s.submitUtterance("오늘 서울에서 부산")
        s.confirm(false)
        assertIs<AppScreen.Home>(s.screen)
        assertNull(s.runner)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `실서비스는 하루 5건을 넘으면 시작하지 않는다, 목업은 세지 않는다(D17)`() {
        val store = MemoryStore()
        val s = session(store).also { it.acceptConsent() }
        parseResult = ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TODAY))
        fun once() {
            s.reset()
            s.submitUtterance("오늘 서울에서 부산")
            s.confirm(true)
        }
        repeat(6) { once() } // 목업
        assertEquals(6, started.size)
        assertEquals(5, s.remainingToday())

        live = true
        repeat(5) { once() }
        assertEquals(11, started.size)
        once()
        assertEquals(11, started.size)
        assertTrue("하루 5번" in assertIs<AppScreen.Finished>(s.screen).text)
    }

    @Test
    fun `실행 중 멈춤과 계속(D39)`() {
        val s = consented()
        parseResult = ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TODAY))
        s.submitUtterance("오늘 서울에서 부산")
        s.confirm(true)
        s.pause()
        val r = s.runner!!
        s.onRunState(r.tick())
        assertIs<RunState.PausedForUser>(assertIs<AppScreen.Running>(s.screen).run)
        s.resume()
        s.onRunState(r.run())
        assertIs<RunState.AskUser>(assertIs<AppScreen.Running>(s.screen).run)
    }
}
