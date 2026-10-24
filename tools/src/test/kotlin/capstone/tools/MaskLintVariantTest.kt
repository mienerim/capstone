package capstone.tools

import capstone.tools.MaskLint.Kind
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** 기기 마스킹이 놓친 변형을 커밋 전 린트가 마지막으로 잡는지(I-84, DumpMaskerFuzzTest와 같은 변형). */
class MaskLintVariantTest {
    private fun kinds(line: String) = MaskLint.scanLine(line).map { it.first }

    private fun Random.digits(n: Int) = (1..n).joinToString("") { nextInt(10).toString() }

    private fun Random.widen(s: String) =
        s.map { c -> if (c.isDigit()) (c - '0' + '０'.code).toChar() else c }.joinToString("")

    @Test
    fun `전각·국가번호·유니코드 대시·보이지 않는 문자로 바꾼 휴대폰 번호도 잡는다`() {
        val seps = listOf("-", " ", ".", "", "–", "‑", "−", "　", " ", "​")
        val misses = (1..1500).filter { seed ->
            val r = Random(seed)
            val sep = seps.random(r)
            val head = listOf("+82${seps.random(r)}10", "82-10", "+82 010", "010", "019").random(r)
            var line = "$head$sep${r.digits(4)}$sep${r.digits(4)}"
            if (r.nextBoolean()) line = r.widen(line)
            Kind.PHONE !in kinds("""{"text":"연락처 $line"}""")
        }
        assertTrue(misses.isEmpty(), "${misses.size}건 놓침: ${misses.take(10)}")
    }

    @Test
    fun `15자리 카드와 뒷자리를 가린 주민번호도 잡는다`() {
        assertEquals(listOf(Kind.CARD), kinds("카드 3782-822463-10005"))
        assertEquals(listOf(Kind.CARD), kinds("카드 １２３４－５６７８－９０１２－３４５６"))
        assertEquals(listOf(Kind.RRN), kinds("550101-1******"))
        assertEquals(listOf(Kind.RRN), kinds("550101‑​1234567"))
    }

    @Test
    fun `열차 화면 값은 접은 뒤에도 통과`() {
        listOf(
            "２０２６．１０．２６ KTX 105",
            "08 : 00 서울–부산",
            "고객센터 1544-7788",
            "5호차 12A ５９,８００원",
        ).forEach { assertTrue(kinds(it).isEmpty(), "$it → ${kinds(it)}") }
    }
}
