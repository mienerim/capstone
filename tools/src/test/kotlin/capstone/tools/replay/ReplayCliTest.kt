package capstone.tools.replay

import capstone.tools.checkScenario
import capstone.tools.validate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayCliTest {
    @Test
    fun `픽스처와 시나리오는 CLI 검사를 통과한다`() {
        val out = StringBuilder()
        assertTrue(validate(Fixtures.mockJsonl, out), out.toString())
        assertTrue(out.contains("mock_s3_seat label=DETAIL fg=capstone.mock"), out.toString())
        assertTrue(checkScenario(Fixtures.happy, out), out.toString())
        assertTrue(checkScenario(Fixtures.faults, out), out.toString())
    }

    @Test
    fun `깨진 덤프는 실패로 보고`(@TempDir dir: File) {
        val f = File(dir, "bad.jsonl").apply { writeText("""{"v":0,"snap_id":"x","windows":[{"id":1,"root":0,"nodes":[{"id":0,"children":[3]}]}]}""") }
        val out = StringBuilder()
        assertFalse(validate(f, out))
        assertTrue(out.contains("형식 오류"))
        assertFalse(validate(File(dir, "none.jsonl"), out))
    }
}
