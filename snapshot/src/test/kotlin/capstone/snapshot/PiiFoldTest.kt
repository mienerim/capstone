package capstone.snapshot

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PiiFoldTest {
    @Test
    fun `접기는 길이를 바꾸지 않는다`() {
        val s = "０１０–１２３４　５６７８ ＠ 서울 KTX"
        assertEquals("010-1234 5678 @ 서울 KTX", PiiFold.fold(s))
        assertEquals(s.length, PiiFold.fold(s).length)
    }

    @Test
    fun `보이지 않는 문자만 지운다`() {
        assertEquals("01012345678 서울", PiiFold.stripInvisible("010​1234⁠567­8 서﻿울"))
    }
}
