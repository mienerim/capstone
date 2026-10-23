package capstone.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextBudgetTest {
    @Test
    fun `토큰 한도 - 전 기기 2048 하나, 입력 몫은 2048 - 384(D77)`() {
        assertEquals(2048, ContextBudget.MAX_NUM_TOKENS)
        assertEquals(1664, ContextBudget.inputBudget())
        assertTrue(ContextBudget.RAISE_INPUT_THRESHOLD < ContextBudget.inputBudget())
    }

    @Test
    fun `상향 판정 - 최대 입력 1,400 초과 또는 Prefill 에러·한도 도달 1회면 3072 후보(D77)`() {
        assertFalse(ContextBudget.shouldTryRaise(1_400))
        assertTrue(ContextBudget.shouldTryRaise(1_401))
        assertTrue(ContextBudget.shouldTryRaise(600, prefillErrors = 1))
        assertTrue(ContextBudget.shouldTryRaise(600, maxTokensReached = 1))
        assertFalse(ContextBudget.shouldTryRaise(900, prefillErrors = 0, maxTokensReached = 0))
    }

    /**
     * 실측(W03) 전 울타리. 토큰 수를 보수적으로 어림한다: 비ASCII(한글) 1글자 = 1토큰, ASCII 2글자 = 1토큰(보통 3~4글자 — 추정).
     * 스키마는 우리가 프롬프트에 넣지 않고 ResponseFormat으로만 넘기지만, 엔진이 프롬프트에 주입하는지 미확인이라(D77) 한 번 센다.
     * 프롬프트·스키마를 늘리다 이 테스트가 깨지면 2048 단일 전제가 흔들리는 것이니 W03 전에 팀에 알린다(I-79).
     */
    @Test
    fun `토큰 어림 울타리 - 단계별 시스템 지시 + 스키마 + 최장 발화가 1,400 안(D77)`() {
        val longest = "가".repeat(SlotPrompts.MAX_UTTERANCE_CHARS)
        val stages = listOf("1단계" to (SlotPrompts.stage1System to SlotSchemas.stage1())) +
            listOf(Intent.TRAIN_BOOK, Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE)
                .map { "2단계 ${it.wire}" to (SlotPrompts.stage2System(it) to SlotSchemas.stage2(it)) }
        for ((name, pair) in stages) {
            val (system, schema) = pair
            val tokens = upper(system) + upper(schema) + upper(longest) + TEMPLATE_OVERHEAD_TOKENS
            assertTrue(tokens <= ContextBudget.RAISE_INPUT_THRESHOLD, "$name 입력 어림 $tokens > ${ContextBudget.RAISE_INPUT_THRESHOLD}")
        }
    }

    private fun upper(s: String): Int {
        val ascii = s.count { it.code < 128 }
        return (s.length - ascii) + (ascii + 1) / 2
    }

    private companion object {
        /** Gemma 채팅 템플릿의 턴 표지(<start_of_turn>user 등) 몫. 넉넉히 잡은 추정값. */
        const val TEMPLATE_OVERHEAD_TOKENS = 50
    }
}
