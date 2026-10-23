package capstone.gate

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.text.Normalizer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 확정 라벨 변형 퍼즈(D36 4.3·G2 보강). G2는 고정 사례 10개라, 보이지 않는 글자·전각·NFD·이모지를
 * 무작위 위치에 섞은 변형으로 "정규화 뒤 HARD" 불변식을 넓게 확인한다. 시드 고정, 실패 시 시드를 남긴다.
 */
class LabelMutationFuzzTest {
    private val confirmLabels = listOf("예매하기", "결제하기", "구매확정", "접수하기", "카카오페이", "Pay", "Buy now", "좌석선택완료")

    /** 글자 사이에 끼워 넣을 것: 공백·제로폭·방향 제어·이체 선택자·한글 채움 문자·점자 공백·구두점·이모지. */
    private val invisibles = listOf(
        " ", " ", "​", "‌", "‍", "⁠", "﻿", "‎", "‮", "️", "­",
        "ㅤ", "ﾠ", "ᅟ", "ᅠ", "⠀", "·", "-", "_", "🎫", "💳",
    )

    private fun fullwidth(s: String) = s.map { c -> if (c in '!'..'~') (c.code + 0xFEE0).toChar() else c }.joinToString("")

    private fun mutate(label: String, r: Random): String {
        var s = if (r.nextBoolean()) fullwidth(label) else label
        if (r.nextInt(3) == 0) s = s.uppercase()
        val sb = StringBuilder()
        // NFD로 풀린 자모 사이에 끼우면 NFKC 재결합 전에 끊긴다 — 실제 화면 텍스트에서도 가능한 경우라 그대로 둔다.
        val base = if (r.nextInt(4) == 0) Normalizer.normalize(s, Normalizer.Form.NFD) else s
        for (ch in base) {
            sb.append(ch)
            if (r.nextInt(3) == 0) repeat(r.nextInt(1, 3)) { sb.append(invisibles.random(r)) }
        }
        if (r.nextBoolean()) sb.insert(0, invisibles.random(r))
        return sb.toString()
    }

    @Test
    fun `확정 라벨 변형 3000개 모두 HARD`() {
        for (seed in 1..3000) {
            val r = Random(seed)
            val label = confirmLabels.random(r)
            val m = mutate(label, r)
            val got = ConfirmMatcher.matchLabel(m)
            if (got != MatchStrength.HARD) {
                val cps = m.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
                fail("seed=$seed '$label' 변형이 $got: [$cps] → '${LabelNormalizer.normalize(m)}'")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["예매ㅤ하기", "결ﾠ제", "구매ᅟᅠ확정", "예매하기ㅤ"])
    fun `한글 채움 문자는 글자로 분류되지만 매칭에서 지운다`(label: String) {
        assertEquals(MatchStrength.HARD, ConfirmMatcher.matchLabel(label))
    }

    @Test
    fun `NFD 자모 사이에 제로폭 문자가 껴도 재결합해 HARD`() {
        val split = Normalizer.normalize("예매하기", Normalizer.Form.NFD).toCharArray().joinToString("‍")
        assertEquals("예매하기", LabelNormalizer.normalize(split))
        assertEquals(MatchStrength.HARD, ConfirmMatcher.matchLabel(split))
    }

    @Test
    fun `채움 문자만 있는 라벨은 빈 라벨`() {
        assertEquals("", LabelNormalizer.normalize("ㅤﾠᅟᅠ"))
    }
}
