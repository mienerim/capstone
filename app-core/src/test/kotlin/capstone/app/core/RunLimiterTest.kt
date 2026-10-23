package capstone.app.core

import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunLimiterTest {
    private fun kst(d: Int, h: Int, m: Int = 0): Long =
        ZonedDateTime.of(2026, 10, d, h, m, 0, 0, DailyRunLimiter.KST).toInstant().toEpochMilli()

    @Test
    fun `하루 5건까지, 6번째는 기록하지 않고 거부`() {
        val l = DailyRunLimiter(MemoryStore())
        repeat(5) { assertTrue(l.tryStart(kst(27, 9, it))) }
        assertEquals(0, l.remaining(kst(27, 10)))
        assertFalse(l.tryStart(kst(27, 10)))
        assertEquals(0, l.remaining(kst(27, 10)))
    }

    @Test
    fun `한국 시간 자정이 지나면 다시 센다`() {
        val store = MemoryStore()
        val l = DailyRunLimiter(store)
        repeat(5) { l.tryStart(kst(27, 23, it)) }
        assertFalse(l.tryStart(kst(27, 23, 59)))
        // 10-28 00:30 KST = 10-27 15:30 UTC: UTC 날짜로 세면 같은 날이 되어 거부된다.
        assertTrue(l.tryStart(kst(28, 0, 30)))
        assertEquals(4, l.remaining(kst(28, 1)))
        assertEquals("1", store.get("runs.live")!!.split(',').size.toString()) // 지난 날짜는 지운다
    }
}
