package capstone.app.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 대기 피드백 v1(D73): 1초 안 확인음·3초 필러 1회·10초 선택지·25초 안전 중단. */
class WaitFeedbackTest {
    @Test
    fun `신호는 시각 순서대로 한 번씩만 나온다`() {
        val w = WaitFeedback()
        w.start(1_000)
        assertEquals(listOf<WaitCue>(WaitCue.Thinking), w.poll(1_000))
        assertTrue(w.poll(3_999).isEmpty())
        assertIs<WaitCue.Filler>(w.poll(4_000).single())
        assertTrue(w.poll(8_000).isEmpty(), "필러는 반복하지 않는다")
        assertEquals(listOf<WaitCue>(WaitCue.AskContinue), w.poll(11_000))
        assertTrue(w.asking)
        assertEquals(listOf<WaitCue>(WaitCue.Abort), w.poll(26_000))
        assertFalse(w.active)
        assertTrue(w.poll(60_000).isEmpty())
    }

    @Test
    fun `틱이 늦게 와도 놓친 신호를 순서대로 모두 낸다`() {
        val w = WaitFeedback()
        w.start(0)
        val cues = w.poll(12_000)
        assertEquals(3, cues.size)
        assertEquals(WaitCue.Thinking, cues[0])
        assertIs<WaitCue.Filler>(cues[1])
        assertEquals(WaitCue.AskContinue, cues[2])
    }

    @Test
    fun `계속 기다려도 25초 안전 중단은 그대로다(I-72)`() {
        val w = WaitFeedback()
        w.start(0)
        w.poll(10_000)
        w.keepWaiting()
        assertFalse(w.asking)
        assertEquals(listOf<WaitCue>(WaitCue.Abort), w.poll(25_000))
    }

    @Test
    fun `멈춘 뒤에는 아무 신호도 내지 않는다`() {
        val w = WaitFeedback()
        w.start(0)
        w.poll(0)
        w.stop()
        assertTrue(w.poll(30_000).isEmpty())
    }

    @Test
    fun `필러는 작업명을 복창하고 대기마다 다른 변형을 쓴다`() {
        val w = WaitFeedback()
        val texts = (0 until WaitFeedback.FILLERS.size + 1).map { i ->
            w.start(i * 100_000L, "KTX 예매")
            (w.poll(i * 100_000L + 3_000).last() as WaitCue.Filler).text
        }
        assertTrue(texts.all { "KTX 예매" in it }, texts.toString())
        texts.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "연속 같은 필러: $a") }
        assertEquals(WaitFeedback.FILLERS.size, texts.distinct().size)
    }

    @Test
    fun `안전 중단 문구는 결제를 하지 않았다고 말하고 다음 길(버튼)을 알려 준다`() {
        assertTrue("결제는 하지 않았" in WaitFeedback.ABORT_TEXT)
        assertTrue("버튼" in WaitFeedback.ABORT_TEXT)
    }

    @Test
    fun `첫 신호는 1초 안이어야 한다(D73 ①)`() {
        assertThrows<IllegalArgumentException> { WaitFeedback.Timing(thinkingMs = 1_500) }
        assertThrows<IllegalArgumentException> { WaitFeedback.Timing(askMs = 30_000) }
    }
}
