package capstone.agent

import capstone.gate.Snapshot
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScenarioRunnerTest {

    private val pickTrain = flow("pick", setOf(PKG)) {
        click(Screens.RESULT_LIST, then = Screens.DETAIL, target = NodeMatcher(text = "KTX 105"))
        finish(Screens.DETAIL) { "ok" }
    }

    @Test
    fun `시나리오 중간 화면에서 시작하면 조건 없이 이어 가지 않고 첫 단계 화면으로 되돌린다`() {
        val h = Harness(start = Screens.RESULT_LIST)
        h.runToAsk()
        assertEquals("back", h.app.raw.first())
        assertTrue(h.app.raw.containsAll(listOf("set_text:dep_station", "click:조회하기")))
    }

    private fun Harness.runToAsk(): RunState.AskUser = assertIs<RunState.AskUser>(runner.run())

    private fun Harness.assertNoConfirmReached() {
        assertFalse(app.raw.any { "예매하기" in it || "확인" in it }, app.raw.toString())
    }

    @Test
    fun `팝업 닫기, 입력, 조회, 열차 묻기, 선택 뒤 좌석 화면에서 멈춘다`() {
        val h = Harness()
        val ask = h.runToAsk()
        assertEquals(listOf("KTX 105", "KTX 107"), ask.options)
        h.runner.answer("KTX 105")
        val done = assertIs<RunState.Done>(h.runner.run())
        assertTrue("KTX 105" in done.summary && "직접 눌러" in done.summary)
        assertEquals(
            listOf("click:닫기", "set_text:dep_station", "set_text:arr_station", "click:조회하기", "click:KTX 105"),
            h.app.raw,
        )
        assertEquals(Screens.DETAIL, h.app.current)
        assertTrue("popup_dismiss" in h.kinds)
        h.assertNoConfirmReached()
    }

    @Test
    fun `이미 들어 있는 입력값과 슬롯은 다시 하지 않는다`() {
        val h = Harness(slots = mapOf("origin" to "서울", "destination" to "부산", "train" to "KTX 107"))
        h.runner.run() // 첫 회차는 팝업부터
        assertIs<RunState.Done>(h.runner.state)
        assertFalse("ask" in h.kinds)
        assertEquals(1, h.app.raw.count { it == "set_text:dep_station" })
    }

    @Test
    fun `조회 뒤 로딩(빈 트리)은 기다렸다 진행하고 HANDOFF로 가지 않는다`() {
        val h = Harness()
        h.app.on(Screens.SEARCH_FORM, "조회하기", Screens.RESULT_LIST, delayMs = 4_000)
        h.runToAsk()
        assertFalse("handoff" in h.kinds)
    }

    @Test
    fun `로딩이 보류 상한을 넘으면 HANDOFF 1a, 화면이 뜨면 해당 단계로 복귀`() {
        val h = Harness(config = RunnerConfig(stepTimeoutMs = 2_000, loadingHoldMs = 3_000))
        h.app.on(Screens.SEARCH_FORM, "조회하기", Screens.RESULT_LIST, delayMs = 60_000)
        val ho = assertIs<RunState.Handoff>(h.runner.run())
        assertEquals(HandoffTrigger.LOADING, ho.trigger)
        val rawBefore = h.app.raw.size
        assertIs<RunState.Handoff>(h.runner.tick()) // 아직 로딩: 액션 없음
        assertEquals(rawBefore, h.app.raw.size)

        h.clock.now += 60_000
        assertIs<RunState.Running>(h.runner.tick())
        assertTrue("handoff_return" in h.kinds)
        assertIs<RunState.AskUser>(h.runner.run())
    }

    @Test
    fun `반응 없는 클릭은 재시도 1회로 넘어간다`() {
        val h = Harness(start = Screens.RESULT_LIST, flow = pickTrain)
        h.app.ignoreClicks = 1
        assertIs<RunState.Done>(h.runner.run())
        assertEquals(listOf("click:KTX 105", "click:KTX 105"), h.app.raw)
        assertTrue("retry" in h.kinds)
    }

    @Test
    fun `재시도도 실패하면 back 1회 뒤 정지·안내`() {
        val h = Harness(start = Screens.RESULT_LIST, flow = pickTrain)
        h.app.ignoreClicks = 99
        val st = assertIs<RunState.Stopped>(h.runner.run())
        assertTrue(st.reason.startsWith("ladder_exhausted"), st.reason)
        assertEquals(listOf("click:KTX 105", "click:KTX 105", "back"), h.app.raw)
    }

    @Test
    fun `분류 안 되는 화면은 로딩 보류 뒤 back도 gate가 막아 정지`() {
        val h = Harness(start = FakeApp.ODD)
        val st = assertIs<RunState.Stopped>(h.runner.run())
        assertTrue(st.reason.startsWith("ladder_exhausted"))
        assertTrue(h.app.raw.isEmpty(), "UNKNOWN 화면에서는 어떤 raw 호출도 나가지 않아야 한다: ${h.app.raw}")
        assertTrue(h.runner.events.any { it.kind == "exec" && "FAIL_CLOSED_SCREEN_SIGNATURE" in it.detail })
    }

    @Test
    fun `스크린샷 오류 6이면 HANDOFF 2, 5분 안에 안 돌아오면 정지`() {
        val h = Harness()
        h.app.overlay = { it.copy(screenshotError = 6) }
        val ho = assertIs<RunState.Handoff>(h.runner.run())
        assertEquals(HandoffTrigger.SECURE_WINDOW, ho.trigger)
        h.clock.now += 5 * 60_000
        val st = assertIs<RunState.Stopped>(h.runner.tick())
        assertEquals("handoff_timeout:SECURE_WINDOW", st.reason)
        assertTrue(h.app.raw.isEmpty())
    }

    @Test
    fun `data-sensitive 노드가 보이면 HANDOFF 1b`() {
        val h = Harness()
        h.app.overlay = { s ->
            s.copy(windows = s.windows.map { w -> if (w.id == 1 && w.root != null) w.copy(root = w.root!!.copy(dataSensitive = true)) else w })
        }
        assertEquals(HandoffTrigger.DATA_SENSITIVE, assertIs<RunState.Handoff>(h.runner.run()).trigger)
    }

    @Test
    fun `로그인 화면은 HANDOFF 3, 사용자가 로그인해 검색 화면이 뜨면 복귀`() {
        val h = Harness(start = Screens.LOGIN)
        assertEquals(HandoffTrigger.SENSITIVE_INPUT, assertIs<RunState.Handoff>(h.runner.run()).trigger)
        assertTrue(h.app.raw.isEmpty())
        h.app.goTo(Screens.SEARCH_FORM)
        assertIs<RunState.Running>(h.runner.tick())
        assertIs<RunState.AskUser>(h.runner.run())
    }

    @Test
    fun `일시정지 요청은 다음 제출 전에 멈추고, 같은 화면으로 돌아오면 이어서 진행`() {
        val h = Harness()
        // 첫 raw 호출(팝업 닫기) 도중 사용자가 고정 알림을 눌렀다고 본다.
        h.app.onRaw = { h.runner.pauseForUser(); h.app.onRaw = null }
        val p = assertIs<RunState.PausedForUser>(h.runner.run())
        assertEquals(listOf("click:닫기"), h.app.raw)
        assertEquals(Screens.SEARCH_FORM, p.lastScreen)

        // PAUSED_FOR_USER 중에는 tick이 아무것도 하지 않는다.
        repeat(3) { h.runner.tick() }
        assertEquals(1, h.app.raw.size)

        h.runner.resumeFromPause()
        assertIs<RunState.AskUser>(h.runner.run())
        assertFalse("relocate" in h.kinds)
    }

    @Test
    fun `ASK_USER 중 일시정지, 복귀 화면이 다르면 재탐색`() {
        val h = Harness()
        h.runToAsk()
        h.runner.pauseForUser()
        assertIs<RunState.PausedForUser>(h.runner.tick())
        h.app.goTo(Screens.SEARCH_FORM) // 사용자가 그 사이 뒤로 갔다
        h.runner.resumeFromPause()
        h.runner.tick()
        assertTrue("relocate" in h.kinds)
        assertIs<RunState.AskUser>(h.runner.run())
    }

    @Test
    fun `복귀했는데 다른 앱이면 정지`() {
        val h = Harness()
        h.runToAsk()
        h.runner.pauseForUser()
        h.runner.tick()
        h.app.goTo(FakeApp.FOREIGN)
        h.runner.resumeFromPause()
        val st = assertIs<RunState.Stopped>(h.runner.run())
        assertTrue(st.reason.startsWith("resume_mismatch"), st.reason)
    }

    @Test
    fun `시나리오가 예매하기를 누르려 하면 제출하지 않고 정지`() {
        val bad = flow("bad", setOf(PKG)) {
            click(Screens.DETAIL, then = "PAYMENT", target = NodeMatcher(text = "예매하기"))
            finish(Screens.DETAIL) { "" }
        }
        val h = Harness(start = Screens.DETAIL, flow = bad)
        assertEquals("flow_targets_confirm", assertIs<RunState.Stopped>(h.runner.run()).reason)
        assertTrue(h.app.raw.isEmpty())
    }

    @Test
    fun `결제 문맥 안 확인 버튼은 gate가 막고 상태머신은 재시도 없이 정지`() {
        val bad = flow("bad_ctx", setOf(PKG)) {
            click(Screens.DETAIL, then = "PAYMENT", target = NodeMatcher(text = "확인"))
            finish(Screens.DETAIL) { "" }
        }
        val h = Harness(start = Screens.DETAIL, flow = bad)
        val st = assertIs<RunState.Stopped>(h.runner.run())
        assertEquals("gate:CONFIRM_ANCESTOR_DELEGATE", st.reason)
        assertTrue(h.app.raw.isEmpty())
        assertEquals(1, h.runner.events.count { it.kind == "exec" })
    }

    @Test
    fun `보기가 없으면 묻지 않고 정지`() {
        val f = flow("no_opts", setOf(PKG)) {
            ask(Screens.RESULT_LIST, "train", "어느 열차?") { _, _ -> emptyList() }
            finish(Screens.RESULT_LIST) { "" }
        }
        val h = Harness(start = Screens.RESULT_LIST, flow = f)
        assertEquals("ask_no_options:train", assertIs<RunState.Stopped>(h.runner.run()).reason)
    }

    @Test
    fun `보기에 없는 답은 거부`() {
        val h = Harness()
        h.runToAsk()
        assertThrows(IllegalArgumentException::class.java) { h.runner.answer("KTX 101") }
    }

    @Test
    fun `wait_for와 expect`() {
        val f = flow("wait", setOf(PKG)) {
            waitFor(Screens.RESULT_LIST, timeoutMs = 10_000)
            expect(Screens.RESULT_LIST)
            finish(Screens.RESULT_LIST) { "ok" }
        }
        val h = Harness(start = Screens.SEARCH_FORM, flow = f)
        h.app.on(Screens.SEARCH_FORM, "x", Screens.RESULT_LIST)
        h.app.goTo(Screens.SEARCH_FORM)
        // 3초 뒤 결과 화면이 뜬다.
        val src = h.app
        var polls = 0
        src.overlay = { s -> if (++polls == 10) { src.goTo(Screens.RESULT_LIST); src.capture() } else s }
        assertEquals("ok", assertIs<RunState.Done>(h.runner.run()).summary)
        assertTrue(h.app.raw.isEmpty())
    }

    @Test
    fun `Finish 없는 시나리오는 만들 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) {
            flow("x", setOf(PKG)) { expect(Screens.DETAIL) }
        }
    }

    @Test
    fun `가상 시계에서 끝 상태가 아닌 채로 run이 끝나지 않는다`() {
        val h = Harness()
        val s = h.runner.run()
        assertTrue(s !is RunState.Running, s.toString())
        assertEquals(Snapshot.SCREEN_UNKNOWN, Screens.UNKNOWN)
    }
}
