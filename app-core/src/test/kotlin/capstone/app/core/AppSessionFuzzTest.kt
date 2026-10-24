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
import capstone.llm.UnknownReason
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import capstone.tools.replay.ReplayDevice
import capstone.tools.replay.SnapshotPool
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import capstone.tools.replay.Scenario as ReplayScenario

/**
 * 앱 흐름 퍼즈(9단계 테스트 보강). AppSessionTest는 정해진 순서만 본다. 여기서는 MainActivity가 그리는 버튼만
 * 아무 순서로 누르고, 그 사이에 Android에서 실제로 끼어드는 비동기 일을 섞는다:
 *  - AgentHost 작업 스레드의 tick과 main.post로 늦게 도착하는 러너 상태(이미 버린 러너의 것 포함),
 *  - 늦게 오는 파싱 결과, 대기 틱, 고정 알림 '멈춰',
 *  - 시간 경과와 기기 시계를 뒤로 돌리기(사용자가 날짜를 바꾼 경우).
 * 어떤 조합에서도
 *  - 화면에 그려진 버튼을 누를 때 예외가 없고,
 *  - 실행 화면(Running)이면 러너가 있으며(없으면 '멈춰'만 남아 빠져나갈 수 없다),
 *  - 버린 러너의 상태가 화면을 바꾸지 않고,
 *  - 러너는 복창 화면의 '네'로만 시작하며(D16),
 *  - 실서비스 시작은 시계를 뒤로 돌려도 하루 5건을 넘지 않고(D17),
 *  - M7 위반·'예매'/'결제' 클릭이 0건인지 본다.
 * 시드는 고정이라 실패하면 seed와 마지막 동작 기록으로 재현된다.
 */
class AppSessionFuzzTest {
    private val fixtures = File(System.getProperty("capstone.fixtures"), "mock")
    private val scenarioFiles = listOf("happy.json", "fault-pool.json", "srt.json").map { File(fixtures, it) }
    private val start = ZonedDateTime.of(2026, 10, 27, 9, 0, 0, 0, DailyRunLimiter.KST).toInstant().toEpochMilli()

    private class Run(val runner: ScenarioRunner, val device: ReplayDevice) {
        var looping = false
    }

    private inner class World(val seed: Long) {
        val rnd = Random(seed)
        var realMs = start
        /** 기기 시계 = 실제 시각 + offset. 앞으로 돌리기는 '하루가 지난 것'과 구분할 수 없어 뒤로만 돌린다(I-85). */
        var offsetMs = 0L
        val store = MemoryStore()
        val live = rnd.nextInt(3) != 0
        val runs = mutableListOf<Run>()
        /** AgentHost.runner: 새 실행이 시작될 때만 바뀐다(reset은 세션 쪽 러너만 버린다). */
        var host: Run? = null
        /** main.post 큐: 작업 스레드 tick 결과가 메인 스레드에 늦게 도착한다. */
        val mainQueue = ArrayDeque<Pair<ScenarioRunner, RunState>>()
        val pendingParses = mutableListOf<Pair<Int, ParseResult>>()
        val liveStartsDeviceMs = mutableListOf<Long>()
        val trace = ArrayDeque<String>()
        var confirming = false

        val session = AppSession(
            store,
            scenarioFor = { s -> if (s.intent == Intent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = live) else null },
            startRunner = ::startRunner,
            nowMs = { realMs + offsetMs },
        )

        private fun startRunner(flow: Flow, slots: Slots): ScenarioRunner {
            check(confirming) { "복창 '네' 밖에서 러너 시작: ${session.screen}" }
            if (live) liveStartsDeviceMs += realMs + offsetMs
            val file = scenarioFiles.random(rnd)
            val sc = ReplayScenario.read(file)
            val device = ReplayDevice(sc.copy(seed = rnd.nextLong()), SnapshotPool.forScenario(file, sc), oracleScreens = false)
            val classifier = MockKorail.classifier()
            val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
            val exec = GatedExecutor(gate, device) { classifier.label(device.capture()) }
            val r = ScenarioRunner(flow, slots, classifier, exec, device::capture, VirtualClock(), RunnerConfig())
            return r.also { runs += Run(it, device).also { run -> host = run } }
        }

        fun log(s: String) {
            trace.addLast(s)
            if (trace.size > 40) trace.removeFirst()
        }

        private fun kick() {
            host?.looping = true
        }

        private fun randomParse(): ParseResult = when (rnd.nextInt(6)) {
            0 -> ParseResult.AskAgain(1, "fuzz")
            1 -> ParseResult.OutOfScope(UnknownReason.entries.random(rnd))
            2 -> ParseResult.Ok(SlotsV1(Intent.HOSPITAL_SEARCH, TargetApp.GOODDOC))
            else -> ParseResult.Ok(
                SlotsV1(
                    Intent.TRAIN_BOOK,
                    TargetApp.KORAIL_PLUS,
                    origin = if (rnd.nextBoolean()) "서울" else null,
                    destination = if (rnd.nextBoolean()) listOf("부산", "대전", "서울").random(rnd) else null,
                    dateRel = if (rnd.nextBoolean()) DateRel.entries.random(rnd) else null,
                ),
            )
        }

        /** MainActivity.render()가 지금 화면에 그리는 버튼 하나를 누른다. */
        fun pressButton() {
            when (val s = session.screen) {
                is AppScreen.Consent -> if (rnd.nextInt(4) == 0) tap("decline") { session.declineConsent() } else tap("accept") { session.acceptConsent() }
                is AppScreen.Home -> if (rnd.nextBoolean()) {
                    tap("buttons") { session.startWithButtons() }
                } else {
                    tap("speak") { pendingParses += session.beginParsing() to randomParse() }
                }
                is AppScreen.Waiting -> if (s.asking) {
                    val keep = rnd.nextBoolean()
                    tap("wait:$keep") { session.answerWait(keep) }
                }
                is AppScreen.AskSlot -> {
                    val o = s.options.random(rnd)
                    tap("choose:$o") { session.choose(o) }
                }
                is AppScreen.Confirm -> if (rnd.nextInt(5) == 0) {
                    tap("confirm:no") { session.confirm(false) }
                } else {
                    tap("confirm:yes") {
                        confirming = true
                        try {
                            session.confirm(true)
                        } finally {
                            confirming = false
                        }
                        if (session.runner != null) kick()
                    }
                }
                is AppScreen.Running -> when (val st = s.run) {
                    is RunState.AskUser -> {
                        val o = st.options.random(rnd)
                        tap("answer:$o") { session.answer(o); kick() }
                    }
                    is RunState.PausedForUser -> if (rnd.nextBoolean()) {
                        tap("resume") { session.resume(); kick() }
                    } else {
                        tap("stop") { session.reset() }
                    }
                    else -> tap("pause") { session.pause() }
                }
                is AppScreen.Finished -> tap("home") { session.reset() }
            }
        }

        private fun tap(name: String, f: () -> Unit) {
            log("tap:$name @${session.screen::class.simpleName}")
            f()
        }

        /** AgentHost.loop 한 바퀴: 진행 가능한 상태면 tick하고 결과를 메인 큐에 올린다. */
        fun hostTick() {
            val h = host ?: return
            if (!h.looping) return
            val st = h.runner.tick()
            log("host:tick → ${st::class.simpleName}")
            mainQueue.addLast(h.runner to st)
            h.looping = st is RunState.Running || st is RunState.Handoff
        }

        fun deliverMain() {
            val (r, st) = mainQueue.removeFirstOrNull() ?: return
            val before = session.screen
            val current = r === session.runner
            log("main:deliver ${st::class.simpleName} current=$current")
            session.onRunState(st, r)
            if (!current) assertEquals(before, session.screen, "seed=$seed: 버린 러너의 상태가 화면을 바꿈\n${trace.joinToString("\n")}")
        }

        fun deliverParse() {
            if (pendingParses.isEmpty()) return
            val (ticket, r) = pendingParses.removeAt(rnd.nextInt(pendingParses.size))
            log("parse:deliver #$ticket ${r::class.simpleName}")
            session.onParsed(r, ticket)
        }

        fun waitTick() {
            realMs += rnd.nextLong(300, 4_000)
            session.onWaitTick()
        }

        fun notificationPause() {
            log("notify:pause")
            session.pause()
        }

        fun timePasses() {
            realMs += rnd.nextLong(60_000, 8 * 3_600_000L)
            log("time → ${Instant.ofEpochMilli(realMs + offsetMs).atZone(DailyRunLimiter.KST)}")
        }

        fun clockBack() {
            offsetMs = if (offsetMs < 0 && rnd.nextBoolean()) 0 else offsetMs - rnd.nextLong(3_600_000L, 3 * 86_400_000L)
            log("clock offset=${offsetMs / 3_600_000}h")
        }

        fun checkInvariants() {
            val s = session.screen
            if (s is AppScreen.Running) {
                assertTrue(session.runner != null, "seed=$seed: 러너 없는 실행 화면(빠져나갈 버튼 없음)\n${trace.joinToString("\n")}")
            }
            // D17: 기기 시계가 뒤로 가도 하루 5건. '하루'는 지금까지 본 가장 늦은 기기 시각 기준(I-85).
            var maxSeen = Long.MIN_VALUE
            val perDay = mutableMapOf<LocalDate, Int>()
            for (t in liveStartsDeviceMs) {
                maxSeen = maxOf(maxSeen, t)
                val d = Instant.ofEpochMilli(maxSeen).atZone(DailyRunLimiter.KST).toLocalDate()
                perDay[d] = (perDay[d] ?: 0) + 1
            }
            assertTrue(perDay.values.all { it <= 5 }, "seed=$seed: 하루 5건 초과 $perDay\n${trace.joinToString("\n")}")
        }
    }

    private fun runOnce(seed: Long, steps: Int = 400): World {
        val w = World(seed)
        try {
            repeat(steps) {
                when (w.rnd.nextInt(100)) {
                    in 0..34 -> w.pressButton()
                    in 35..59 -> w.hostTick()
                    in 60..74 -> w.deliverMain()
                    in 75..81 -> w.deliverParse()
                    in 82..87 -> w.waitTick()
                    in 88..91 -> w.notificationPause()
                    in 92..96 -> w.timePasses()
                    else -> w.clockBack()
                }
                w.checkInvariants()
            }
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            fail("seed=$seed: 예외 ${e::class.simpleName}: ${e.message}\n${w.trace.joinToString("\n")}", e)
        }
        for (run in w.runs) {
            val report = run.device.report()
            assertTrue(report.violations.isEmpty() && report.neverReachHit.isEmpty(), "seed=$seed: M7 위반 $report")
            val clicked = run.device.log.filter { it.action == "click" || it.action == "tap_center" }.mapNotNull { it.label }
            assertTrue(clicked.none { it.contains("예매") || it.contains("결제") }, "seed=$seed: $clicked")
        }
        return w
    }

    @Test
    fun `앱 흐름 퍼즈 300회 - 예외·멈춘 화면·버린 러너 반영·한도 초과·M7 위반 0건`() {
        val worlds = (0L until 300L).map { runOnce(70_000 + it) }
        // 퍼즈가 실제로 여러 갈래를 지났는지.
        val finished = worlds.sumOf { w -> w.runs.count { it.runner.state is RunState.Done } }
        val starts = worlds.sumOf { it.runs.size }
        assertTrue(finished > 0 && starts > 100, "starts=$starts done=$finished")
        assertTrue(worlds.any { w -> w.liveStartsDeviceMs.size >= 5 }, "한도까지 가 본 시드가 없음")
    }

    @Test
    fun `시계를 뒤로 돌려도 하루 5건이 다시 생기지 않는다(I-85)`() {
        val store = MemoryStore()
        val l = DailyRunLimiter(store)
        val day = start
        repeat(5) { assertTrue(l.tryStart(day + it * 60_000L)) }
        assertFalse(l.tryStart(day + 3_600_000L))
        // 날짜를 하루 전으로 → 기록이 '미래'라 버려지면 5건이 새로 생긴다.
        assertFalse(l.tryStart(day - 86_400_000L))
        assertEquals(0, l.remaining(day - 86_400_000L))
        // 다시 맞춰도 그대로.
        assertFalse(l.tryStart(day + 7_200_000L))
        // 실제로 다음 날이 되면 다시 5건.
        assertTrue(l.tryStart(day + 86_400_000L))
        assertEquals(4, l.remaining(day + 86_400_000L))
    }

    @Test
    fun `버린 러너의 늦은 상태는 화면을 바꾸지 않는다`() {
        val w = World(1)
        w.session.acceptConsent()
        w.session.onParsed(ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TODAY)))
        w.confirming = true
        w.session.confirm(true)
        w.confirming = false
        val old = w.session.runner!!
        val st = old.tick() // 작업 스레드 tick, main.post 대기 중
        w.session.pause()
        w.session.reset() // '그만하기'
        assertFalse(w.session.onRunState(st, old))
        assertTrue(w.session.screen is AppScreen.Home, w.session.screen.toString())
    }
}
