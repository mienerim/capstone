package capstone.service.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1차 마스킹 변형 퍼즈(I-84). 같은 개인정보를 화면이 다르게 그릴 수 있는 방식(구분자·전각 숫자·
 * 국가번호·보이지 않는 문자·앞뒤 한글)으로 바꿔 넣고, 마스킹 뒤 원래 숫자열이 남지 않는지 본다.
 * 반대로 분류기·gate가 써야 하는 짧은 값(시각·요금·호차·날짜)은 그대로 남아야 한다.
 */
class DumpMaskerFuzzTest {
    private val seps = listOf("-", " ", ".", "", "–", "‑", "−", "　", " ")
    private val invisibles = listOf("​", "‌", "‍", "⁠", "﻿", "­")

    private fun Random.digits(n: Int) = (1..n).joinToString("") { nextInt(10).toString() }

    /** 숫자를 전각으로(일부 또는 전부). */
    private fun Random.widen(s: String, p: Double) =
        s.map { c -> if (c.isDigit() && nextDouble() < p) (c - '0' + '０'.code).toChar() else c }.joinToString("")

    /** 글자 사이에 보이지 않는 문자를 끼운다. */
    private fun Random.sprinkle(s: String, p: Double) =
        s.map { c -> if (nextDouble() < p) "$c${invisibles.random(this)}" else "$c" }.joinToString("")

    private data class Case(val shown: String, val core: String)

    private fun Random.phone(): Case {
        val mid = digits(if (nextBoolean()) 4 else 3)
        val last = digits(4)
        val carrier = listOf("10", "11", "16", "17", "18", "19").random(this)
        val sep = seps.random(this)
        val head = when (nextInt(4)) {
            0 -> "+82${seps.random(this)}$carrier"
            1 -> "82${seps.random(this)}$carrier"
            2 -> "+82${seps.random(this)}0$carrier"
            else -> "0$carrier"
        }
        return Case("$head$sep$mid$sep$last", mid + last)
    }

    private fun Random.rrn(): Case {
        val front = "%02d%02d%02d".format(nextInt(100), nextInt(1, 13), nextInt(1, 29))
        val back = "${nextInt(1, 9)}${digits(6)}"
        return Case("$front${listOf("-", " - ", "–", "−", "‑").random(this)}$back", front + back)
    }

    private fun Random.card(): Case {
        val sep = listOf("-", " ", "–", "‑", "−", " ").random(this)
        val groups = if (nextBoolean()) listOf(4, 4, 4, 4) else listOf(4, 6, 5)
        val parts = groups.map { digits(it) }
        return Case(parts.joinToString(sep), parts.joinToString(""))
    }

    private fun Random.email(): Case {
        val local = "u" + digits(5) + listOf("", ".kim", "_lee", "+tag").random(this)
        return Case("$local@${listOf("naver.com", "gmail.com", "daum.net", "korea.ac.kr").random(this)}", local)
    }

    private fun digitsOf(s: String) = s.filter { it.isDigit() }.map { c -> if (c in '０'..'９') c - '０' + '0'.code else c.code }
        .map { it.toChar() }.joinToString("")

    @Test
    fun `변형한 개인정보도 숫자가 남지 않는다`() {
        val misses = mutableListOf<String>()
        for (seed in 1..4000) {
            val r = Random(seed)
            val case = when (seed % 4) {
                0 -> r.phone()
                1 -> r.rrn()
                2 -> r.card()
                else -> r.email()
            }
            var shown = r.widen(case.shown, if (r.nextInt(3) == 0) 1.0 else 0.0)
            if (r.nextInt(4) == 0) shown = r.sprinkle(shown, 0.3)
            val prefix = listOf("", "연락처 ", "연락처:", "휴대폰", "카드번호 ", "회원 ").random(r)
            val suffix = listOf("", " 님", "로 발송", "(본인)").random(r)
            val out = DumpMasker.maskText(prefix + shown + suffix)!!
            val leaked = if (seed % 4 == 3) case.core in out else case.core in digitsOf(out)
            if (leaked) misses += "seed=$seed"
        }
        assertTrue(misses.isEmpty(), "${misses.size}건 새어 나감: ${misses.take(10)}")
    }

    @Test
    fun `앱이 이미 가린 주민번호도 생년월일·성별 자리를 지운다`() {
        for (s in listOf("900101-1******", "900101 - 2●●●●●●", "900101-1xxxxxx")) {
            assertEquals("[MASK]", DumpMasker.maskText(s), s)
        }
    }

    @Test
    fun `코레일 화면의 짧은 값은 변형해도 남긴다`() {
        for (seed in 1..2000) {
            val r = Random(seed)
            val s = when (seed % 6) {
                0 -> "%02d:%02d".format(r.nextInt(24), r.nextInt(60))
                1 -> "%,d원".format(r.nextInt(1, 200) * 100)
                2 -> "${r.nextInt(1, 19)}호차 ${r.nextInt(1, 30)}${"ABCD".random(r)}"
                3 -> "KTX ${r.nextInt(1, 999)}"
                4 -> "2026.%02d.%02d(토)".format(r.nextInt(1, 13), r.nextInt(1, 29))
                else -> "${r.nextInt(1, 13)}월 ${r.nextInt(1, 29)}일 ${r.nextInt(1, 10)}명"
            }
            assertEquals(s, DumpMasker.maskText(s), "seed=$seed")
        }
        // 고객센터 번호처럼 8자리 대표번호는 개인정보가 아니라 남긴다.
        assertEquals("고객센터 1544-7788", DumpMasker.maskText("고객센터 1544-7788"))
    }
}
