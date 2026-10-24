package capstone.app.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentTest {

    @Test
    fun `처음에는 동의가 필요하고, 동의하면 기기 안에 버전과 시각이 남는다`() {
        val store = MemoryStore()
        val c = ConsentManager(store)
        assertTrue(c.required)
        c.accept(1_000)
        assertFalse(c.required)
        assertEquals(ConsentRecord(Disclosure.VERSION, 1_000), c.record)
        assertFalse(ConsentManager(store).required) // 다시 열어도 유지
    }

    @Test
    fun `문구 버전이 바뀌면 다시 묻는다`() {
        val store = MemoryStore(mutableMapOf("consent.version" to "0", "consent.accepted_at" to "5"))
        assertTrue(ConsentManager(store).required)
    }

    @Test
    fun `철회하면 기록이 지워진다`() {
        val c = ConsentManager(MemoryStore())
        c.accept(1)
        c.withdraw()
        assertNull(c.record)
        assertTrue(c.required)
    }

    @Test
    fun `고지 문구에 필수 요소가 모두 있다`() {
        val all = Disclosure.sections.joinToString(" ") { it.title + " " + it.body }
        // 읽는 데이터 · 사용 방식(정지 지점) · 외부 전송 범위 · 이유 · 끄는 법 · 알림·비tool 알림 안내
        listOf("글자와 버튼", "예매하기", "화면 내용을 밖으로 보내지 않아요", "별개", "대신 처리", "끄는 법", "설정 > 접근성", "알림", "비밀번호").forEach {
            assertTrue(it in all, "고지에 '$it' 없음")
        }
    }

    @Test
    fun `기내모드 실측 전에는 '아무 데도 보내지 않는다'고 단정하지 않는다(D66 ②)`() {
        val all = Disclosure.sections.joinToString(" ") { it.body }
        assertFalse("아무 데도 보내지 않" in all)
    }
}
