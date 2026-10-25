package capstone.app.core

import capstone.agent.HandoffTrigger
import capstone.agent.RunState
import capstone.llm.DateRel
import capstone.llm.Intent
import capstone.llm.SlotsV1
import capstone.llm.TargetApp
import capstone.llm.TrainType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadbackTest {
    private val today = LocalDate.of(2026, 10, 23) // 금요일

    @Test
    fun `복창은 상대 날짜를 실제 날짜로 풀어 말한다`() {
        val s = SlotsV1(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "서울", destination = "부산", dateRel = DateRel.TOMORROW, trainType = TrainType.KTX)
        assertEquals("내일 10월 24일(토) 서울에서 부산 가는 KTX를 찾을까요?", Readback.confirm(s, today))
    }

    @Test
    fun `시간과 인원도 읽어 준다`() {
        val s = SlotsV1(
            Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, origin = "용산", destination = "광주송정",
            date = LocalDate.of(2026, 10, 30), timeFrom = "14:30", adult = 1, senior = 2,
        )
        assertEquals("10월 30일(금) 오후 2시 30분 이후 용산에서 광주송정 가는 열차를 찾을까요? (어른 1명, 경로 2명)", Readback.confirm(s, today))
    }

    @Test
    fun `시각 표기`() {
        assertEquals("오전 12시", Readback.timeText("00:00"))
        assertEquals("오후 12시 5분", Readback.timeText("12:05"))
        assertEquals("오전 9시", Readback.timeText("09:00"))
    }

    @Test
    fun `정지 사유 코드는 화면에 그대로 나오지 않는다`() {
        listOf("gate:CONFIRM_NODE_TEXT", "flow_targets_confirm", "limit:queries", "handoff_timeout:LOADING", "ladder_exhausted:on=X,at=Y", "resume_mismatch:a->b")
            .forEach { code ->
                val msg = Readback.stopped(code)
                assertFalse(":" in msg || "_" in msg, "$code → $msg")
            }
        assertTrue("직접" in Readback.stopped("gate:CONFIRM_NODE_TEXT"))
    }

    @Test
    fun `HANDOFF 안내는 모두 사람에게 할 일을 말한다`() {
        HandoffTrigger.entries.forEach { t ->
            assertTrue("직접" in Readback.runState(RunState.Handoff(t, 0, 0)), t.name)
        }
    }
}
