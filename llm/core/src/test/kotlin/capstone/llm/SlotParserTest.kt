package capstone.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 2단계 생성·워치독·재생성 1회·되묻기(D11·D30). 실제 모델 대신 [FakeRunner]. */
class SlotParserTest {
    private fun parser(runner: FakeRunner, limits: WatchdogLimits = WatchdogLimits(timeoutMs = 1_000)) =
        SlotParser(GuardedGenerator(runner, limits))

    @Test
    fun `정상 - 2단계 생성으로 슬롯을 얻는다`() {
        val runner = FakeRunner(text(Outputs.TRAIN), text(Outputs.SEOUL_BUSAN))
        val r = assertIs<ParseResult.Ok>(parser(runner).parse("내일 아침 아홉 시 서울에서 부산 가는 KTX 한 장"))
        assertEquals("부산", r.slots.destination)
        // 각 단계에 그 단계의 스키마와 토큰 상한이 간다.
        assertEquals(SlotSchemas.stage1(), runner.requests[0].responseSchema)
        assertEquals(SlotSchemas.stage2(Intent.TRAIN_BOOK), runner.requests[1].responseSchema)
        assertEquals(SlotParser.STAGE1_TOKENS, runner.requests[0].maxOutputTokens)
        assertEquals(listOf("valid", "valid"), parser(FakeRunner(text(Outputs.TRAIN), text(Outputs.SEOUL_BUSAN))).let { p ->
            p.parse("서울 부산"); p.attempts.map { it.outcome }
        })
    }

    @Test
    fun `SRT 예매는 범위 밖으로 끝나고 2단계를 돌리지 않는다`() {
        val runner = FakeRunner(text("""{"intent":"unknown","app":null,"reason":"unsupported_app"}"""))
        assertEquals(ParseResult.OutOfScope(UnknownReason.UNSUPPORTED_APP), parser(runner).parse("SRT 예매해 줘"))
        assertEquals(1, runner.requests.size)
    }

    @Test
    fun `스키마 위반은 재생성 1회로 회복한다`() {
        val runner = FakeRunner(text("```json\n${Outputs.TRAIN}\n```"), text(Outputs.TRAIN), text(Outputs.SEOUL_BUSAN))
        val p = parser(runner)
        assertIs<ParseResult.Ok>(p.parse("서울에서 부산"))
        assertEquals(listOf("not_a_single_object", "valid", "valid"), p.attempts.map { it.outcome })
    }

    @Test
    fun `두 번 실패하면 되묻는다`() {
        val runner = FakeRunner(text("모르겠어요"), text("{\"intent\":"))
        val r = assertIs<ParseResult.AskAgain>(parser(runner).parse("음..."))
        assertEquals(1, r.stage)
        assertEquals(2, runner.requests.size, "재생성은 1회만")
    }

    @Test
    fun `워치독 - 공백 무한 생성(#3510)을 끊고 재생성한다`() {
        val runner = FakeRunner(Script.Spaces, text(Outputs.TRAIN), text(Outputs.SEOUL_BUSAN))
        val p = parser(runner)
        assertIs<ParseResult.Ok>(p.parse("서울 부산"))
        assertEquals("watchdog:whitespace_loop", p.attempts.first().outcome)
        assertEquals(WatchdogLimits().maxWhitespaceRun, p.attempts.first().tokens)
    }

    @Test
    fun `워치독 - 토큰 상한·시간 초과·러너 예외는 모두 되묻기`() {
        val endless = FakeRunner(Script.Endless, Script.Endless)
        val p1 = parser(endless)
        assertIs<ParseResult.AskAgain>(p1.parse("서울 부산"))
        assertEquals(listOf("watchdog:token_limit", "watchdog:token_limit"), p1.attempts.map { it.outcome })
        // 요청 토큰 상한은 단계 상한(64)이고, 워치독은 그와 별개로 256에서 끊는다.
        assertEquals(WatchdogLimits().maxTokens + 1, p1.attempts.first().tokens)

        val hang = FakeRunner(Script.Hang, Script.Hang)
        val p2 = parser(hang, WatchdogLimits(timeoutMs = 100))
        assertIs<ParseResult.AskAgain>(p2.parse("서울 부산"))
        assertEquals(listOf("watchdog:timeout", "watchdog:timeout"), p2.attempts.map { it.outcome })
        assertEquals(2, hang.cancelCount)

        val crash = FakeRunner(Script.Crash, text(Outputs.TRAIN), text(Outputs.SEOUL_BUSAN))
        val p3 = parser(crash)
        assertIs<ParseResult.Ok>(p3.parse("서울 부산"))
        assertEquals("watchdog:runner_error", p3.attempts.first().outcome)
    }

    @Test
    fun `2단계 실패는 2단계 되묻기`() {
        val runner = FakeRunner(text(Outputs.TRAIN), text("{}"), text("{}"))
        val r = assertIs<ParseResult.AskAgain>(parser(runner).parse("서울 부산"))
        assertEquals(2, r.stage)
        assertEquals("missing_key", r.why)
    }

    @Test
    fun `발화 정리 - 제어 문자 제거·길이 제한, 빈 발화는 모델을 부르지 않는다`() {
        assertEquals("서울 부산", SlotPrompts.sanitize("  서울\u0000\n\t 부산 "))
        assertEquals(SlotPrompts.MAX_UTTERANCE_CHARS, SlotPrompts.sanitize("가".repeat(500)).length)
        val runner = FakeRunner()
        assertIs<ParseResult.AskAgain>(parser(runner).parse(" \n "))
        assertTrue(runner.requests.isEmpty())
    }

    @Test
    fun `시도 기록에 발화·출력 원문이 남지 않는다`() {
        val runner = FakeRunner(text("""{"intent":"train_book","app":null,"reason":null,"note":"010-1234-5678"}"""), text(Outputs.TRAIN), text(Outputs.SEOUL_BUSAN))
        val p = parser(runner)
        p.parse("제 번호는 010-1234-5678")
        val logged = p.attempts.joinToString()
        assertNotNull(logged)
        assertTrue("010" !in logged, logged)
    }
}
