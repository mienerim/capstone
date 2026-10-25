package capstone.app.core

import capstone.agent.Flow
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
import capstone.llm.Stations
import capstone.llm.SlotsV1
import capstone.llm.TargetApp
import capstone.llm.TrainType
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import capstone.tools.replay.Scenario as ReplayScenario

/**
 * 복창 ↔ 실행 퍼즈(9단계 테스트 보강). D16은 '복창하고 네를 받은 뒤 실행'인데, 사용자가 '네'를 누른 문장과
 * 러너가 실제로 받는 값이 같은지는 지금까지 정해진 시각·정해진 슬롯으로만 봤다. 여기서는
 *  - 검증기를 통과할 수 있는 임의의 슬롯 v1(역·절대/상대 날짜·시각·열차 종류·인원)을 발화 또는 버튼으로 채우고,
 *  - 복창 화면이 뜬 뒤 '네'를 누르기까지 시간을 흘린다(KST 자정 근처에 몰아서),
 * 러너가 시작되는 순간에
 *  ① 러너에 넘긴 날짜가 '네'를 누른 문장의 날짜(M월 D일)와 같고, 문장의 '오늘·내일·모레'가 시작 시각 기준으로도 맞으며,
 *     지난 날짜로 시작하지 않는다,
 *  ② 사용자가 말했지만 시나리오가 화면에 넣지 않는 조건(날짜·시간·열차 종류·인원)이 있으면, '네'를 누른 문장이
 *     그 조건을 '직접 확인'하라고 이름으로 알려 준다(말한 조건이 조용히 버려지지 않는다),
 *  ③ 출발·도착은 문장과 러너 값이 같다
 * 를 본다. 오라클은 Readback을 부르지 않고 문장 글자와 시계만으로 판정한다.
 */
class ReadbackFuzzTest {
    private val fixtures = File(System.getProperty("capstone.fixtures"), "mock")
    private val happy = File(fixtures, "happy.json")

    /** 조건 이름(문장에 나와야 하는 낱말)과 그 조건이 슬롯에 있는지. */
    private val conditions: List<Pair<String, (SlotsV1) -> Boolean>> = listOf(
        "날짜" to { s -> s.date != null || s.dateRel != null },
        "시간" to { s -> s.timeFrom != null },
        "열차 종류" to { s -> s.trainType != null },
        "인원" to { s -> (s.adult ?: 0) > 0 || (s.senior ?: 0) > 0 },
    )

    private fun runner(flow: Flow, slots: Slots): ScenarioRunner = runnerOn(flow, slots).first

    private fun runnerOn(flow: Flow, slots: Slots): Pair<ScenarioRunner, ReplayDevice> {
        val sc = ReplayScenario.read(happy)
        val device = ReplayDevice(sc, SnapshotPool.forScenario(happy, sc), oracleScreens = false)
        val classifier = MockKorail.classifier()
        val gate = Gate(GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app"))
        val exec = GatedExecutor(gate, device) { classifier.label(device.capture()) }
        return ScenarioRunner(flow, slots, classifier, exec, device::capture, VirtualClock(), RunnerConfig()) to device
    }

    private fun randomSlots(rnd: Random, today: LocalDate): SlotsV1 {
        fun station() = Stations.KORAIL[rnd.nextInt(Stations.KORAIL.size)]
        val origin = station().takeIf { rnd.nextInt(5) != 0 }
        val dest = generateSequence { station() }.first { it != origin }.takeIf { rnd.nextInt(5) != 0 }
        val (date, rel) = when (rnd.nextInt(4)) {
            0 -> null to null
            1 -> today.plusDays(rnd.nextLong(0, 30)) to null
            else -> null to DateRel.entries[rnd.nextInt(DateRel.entries.size)]
        }
        return SlotsV1(
            Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS,
            origin = origin, destination = dest, date = date, dateRel = rel,
            timeFrom = if (rnd.nextBoolean()) "%02d:%02d".format(rnd.nextInt(24), rnd.nextInt(60)) else null,
            trainType = if (rnd.nextBoolean()) TrainType.entries[rnd.nextInt(TrainType.entries.size)] else null,
            adult = if (rnd.nextInt(3) == 0) rnd.nextInt(0, 10) else null,
            senior = if (rnd.nextInt(3) == 0) rnd.nextInt(0, 10) else null,
        )
    }

    private fun kstDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(DailyRunLimiter.KST).toLocalDate()

    private val dateRe = Regex("""(\d{1,2})월 (\d{1,2})일""")
    private val relWords = mapOf("오늘" to 0L, "내일" to 1L, "모레" to 2L)

    /** 한 시드의 위반 목록. */
    private fun violations(seed: Long): List<String> {
        val rnd = Random(seed)
        // 2026-10-26 ~ 12-31 사이 아무 날, 시각은 절반을 23:00~23:59 KST에 몬다.
        val day = LocalDate.of(2026, 10, 26).plusDays(rnd.nextLong(0, 67))
        val sec = if (rnd.nextBoolean()) 23 * 3600 + rnd.nextInt(3600) else rnd.nextInt(86_400)
        var now = ZonedDateTime.of(day.atStartOfDay(), DailyRunLimiter.KST).toInstant().toEpochMilli() + sec * 1000L

        val started = mutableListOf<Slots>()
        val session = AppSession(
            MemoryStore(),
            scenarioFor = { s -> if (s.intent == Intent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = false, applies = MockKorailFlow.APPLIED_SLOTS) else null },
            startRunner = { f, sl -> started += sl; runner(f, sl) },
            nowMs = { now },
        )
        session.acceptConsent()
        val slots = randomSlots(rnd, kstDate(now))
        session.onParsed(ParseResult.Ok(slots))
        // 빠진 필수 슬롯은 버튼으로 채운다(보기 중 아무거나). 고른 값은 세션 안에 남으므로 문장으로만 판정한다.
        var guard = 0
        while (session.screen is AppScreen.AskSlot && guard++ < 5) {
            val ask = session.screen as AppScreen.AskSlot
            session.choose(ask.options[rnd.nextInt(ask.options.size)])
        }
        val out = mutableListOf<String>()
        var pressed = 0
        while (pressed++ < 4) {
            // 날짜가 지나 날짜를 다시 묻는 경우(I-94)는 버튼으로 다시 고른다.
            (session.screen as? AppScreen.AskSlot)?.let { session.choose(it.options[rnd.nextInt(it.options.size)]) }
            if (session.screen !is AppScreen.Confirm) break
            val shown = (session.screen as AppScreen.Confirm).text
            // 복창을 듣고 망설이는 시간: 0초 ~ 3시간(자정을 넘길 수 있다).
            now += when (rnd.nextInt(3)) {
                0 -> rnd.nextLong(0, 5_000)
                1 -> rnd.nextLong(0, 10 * 60_000)
                else -> rnd.nextLong(0, 3 * 3_600_000)
            }
            val before = started.size
            session.confirm(true)
            if (started.size == before) continue
            val run = started.last()
            val todayAtStart = kstDate(now)

            // ①
            val m = dateRe.find(shown)
            val runDate = run["date"]?.let(LocalDate::parse)
            if (m == null) {
                if (runDate != null) out += "seed=$seed 문장에 날짜가 없는데 러너 날짜 $runDate: $shown"
            } else {
                val (mm, dd) = m.destructured
                if (runDate == null || runDate.monthValue != mm.toInt() || runDate.dayOfMonth != dd.toInt()) {
                    out += "seed=$seed 문장 날짜 ${m.value} ≠ 러너 날짜 $runDate: $shown"
                }
                relWords.entries.firstOrNull { (w, _) -> shown.startsWith("$w ") }?.let { (w, d) ->
                    if (runDate != null && runDate != todayAtStart.plusDays(d)) {
                        out += "seed=$seed '$w'라고 복창했는데 시작 시 오늘 $todayAtStart, 러너 $runDate"
                    }
                }
                if (runDate != null && runDate < todayAtStart) out += "seed=$seed 지난 날짜 ${runDate}로 시작(오늘 $todayAtStart)"
            }
            // ②: 목업 흐름이 화면에 넣는 것은 출발·도착뿐이다(아래 고정 테스트가 재생기 액션으로 확인).
            for ((word, has) in conditions) {
                val provided = has(slots) || (word == "날짜" && m != null)
                if (provided && !(word in shown && "직접 확인" in shown)) {
                    out += "seed=$seed 말한 조건 '$word'를 넣지 않는데 문장이 알리지 않음: $shown"
                }
            }
            // ③
            if (run["origin"] == null || "${run["origin"]}에서" !in shown) out += "seed=$seed 출발 불일치 ${run["origin"]}: $shown"
            if (run["destination"] == null || "${run["destination"]} 가는" !in shown) out += "seed=$seed 도착 불일치 ${run["destination"]}: $shown"
        }
        if (started.isEmpty()) out += "seed=$seed 네를 ${pressed}번 눌러도 시작 안 함: ${session.screen}"
        return out
    }

    @Test
    fun `복창한 문장과 러너가 받는 값이 자정을 넘겨도 같고, 넣지 않는 조건은 알린다`() {
        val all = (300_000L until 302_000L).flatMap(::violations)
        val byKind = all.groupingBy { v ->
            when {
                "넣지 않는데" in v -> "조용히 버린 조건"
                "복창했는데" in v || "≠" in v || "지난 날짜" in v || "날짜가 없는데" in v -> "날짜 어긋남"
                else -> "기타"
            }
        }.eachCount()
        assertTrue(all.isEmpty(), "위반 ${all.size}건 $byKind\n" + all.take(10).joinToString("\n"))
    }

    /** 목업 흐름을 끝까지 돌려 기기에 나간 액션(종류·대상·라벨)을 모은다. 열차 보기는 픽스처 전이에 있는 KTX 105를 고른다. */
    private fun actions(slots: Slots): List<String> {
        val (r, device) = runnerOn(MockKorailFlow.flow, slots)
        var st = r.run()
        while (st is capstone.agent.RunState.AskUser) {
            r.answer(st.options.firstOrNull { it == "KTX 105" } ?: st.options.first())
            st = r.run()
        }
        assertIs<capstone.agent.RunState.Done>(st, st.toString())
        return device.log.map { "${it.action}|${it.key}|${it.label}|${it.notes}" }
    }

    @Test
    fun `목업 흐름의 APPLIED_SLOTS는 실제로 화면에 영향을 주는 슬롯과 같다`() {
        val base = mapOf("origin" to "서울", "destination" to "부산")
        val withExtras = base + mapOf("date" to "2026-12-24", "time_from" to "14:30", "train_type" to "ktx", "adult" to "2", "senior" to "1")
        // 날짜·시간·종류·인원을 줘도 기기에 나가는 액션이 같다 = 넣지 않는다 → 복창이 알려야 한다.
        assertEquals(actions(base), actions(withExtras))
        // 대조군: 넣는 슬롯(출발)을 바꾸면 나가는 액션이 달라진다 — 위 비교가 글자를 못 보는 비교가 아님을 확인.
        assertNotEquals(actions(base), actions(base + ("origin" to "용산")))
        assertEquals(setOf("origin", "destination"), MockKorailFlow.APPLIED_SLOTS)
    }

    private fun sessionAt(clock: () -> Long, started: MutableList<Slots>) = AppSession(
        MemoryStore(),
        scenarioFor = { s -> if (s.intent == Intent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = false, applies = MockKorailFlow.APPLIED_SLOTS) else null },
        startRunner = { f, sl -> started += sl; runner(f, sl) },
        nowMs = clock,
    ).also { it.acceptConsent() }

    @Test
    fun `자정을 넘겨 네를 누르면 바뀐 날짜로 다시 묻고, 두 번째 네에 그 날짜로 시작한다`() {
        var now = ZonedDateTime.of(2026, 10, 26, 23, 58, 0, 0, DailyRunLimiter.KST).toInstant().toEpochMilli()
        val started = mutableListOf<Slots>()
        val s = sessionAt({ now }, started)
        s.onParsed(ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TOMORROW)))
        assertTrue(assertIs<AppScreen.Confirm>(s.screen).text.startsWith("내일 10월 27일"))
        now += 5 * 60_000 // 00:03
        s.confirm(true)
        assertTrue(started.isEmpty())
        assertTrue(assertIs<AppScreen.Confirm>(s.screen).text.startsWith("내일 10월 28일"))
        s.confirm(true)
        assertEquals("2026-10-28", started.single()["date"])
    }

    @Test
    fun `복창 뒤 날짜가 지나 버리면 날짜를 다시 묻는다`() {
        var now = ZonedDateTime.of(2026, 10, 26, 23, 59, 0, 0, DailyRunLimiter.KST).toInstant().toEpochMilli()
        val started = mutableListOf<Slots>()
        val s = sessionAt({ now }, started)
        s.onParsed(ParseResult.Ok(SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", date = LocalDate.of(2026, 10, 26))))
        now += 2 * 60_000
        s.confirm(true)
        assertTrue(started.isEmpty())
        assertEquals(capstone.llm.SlotName.DATE, assertIs<AppScreen.AskSlot>(s.screen).slot)
    }

    @Test
    fun `넣지 않는 조건은 이름을 대고 알리고, 넣는 조건만 있으면 덧붙이지 않는다`() {
        val today = LocalDate.of(2026, 10, 26)
        val all = SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TOMORROW, timeFrom = "09:00", trainType = TrainType.KTX, senior = 2)
        assertEquals(
            "내일 10월 27일(화) 오전 9시 이후 서울에서 부산 가는 KTX를 찾을까요? (경로 2명) 날짜·시간·열차 종류·인원은 제가 넣지 않아요. 앱 화면에서 직접 확인해 주세요.",
            Readback.confirm(all, today, MockKorailFlow.APPLIED_SLOTS),
        )
        val onlyStations = SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", trainType = TrainType.GENERAL)
        assertEquals(
            "서울에서 부산 가는 일반열차를 찾을까요? 열차 종류는 제가 넣지 않아요. 앱 화면에서 직접 확인해 주세요.",
            Readback.confirm(onlyStations, today, MockKorailFlow.APPLIED_SLOTS),
        )
        assertEquals("서울에서 부산 가는 열차를 찾을까요?", Readback.confirm(onlyStations.copy(trainType = null), today, MockKorailFlow.APPLIED_SLOTS))
        assertTrue("직접 확인" !in Readback.confirm(all, today)) // 다 넣는 흐름이면 덧붙이지 않는다
    }
}
