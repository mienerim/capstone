package capstone.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
     * 실측(W03) 전 울타리. 어림은 [ConservativeTokenEstimate](비ASCII 1글자 = 1토큰, ASCII 2글자 = 1토큰, 템플릿 50).
     * 프롬프트·스키마를 늘리다 이 테스트가 깨지면 2048 단일 전제가 흔들리는 것이니 W03 전에 팀에 알린다(I-79).
     */
    @Test
    fun `토큰 어림 울타리 - 단계별 시스템 지시 + 스키마 + 최장 발화가 1,400 안(D77)`() {
        for ((name, req) in longestStageRequests()) {
            val tokens = ConservativeTokenEstimate.count(req)
            assertTrue(tokens <= ContextBudget.RAISE_INPUT_THRESHOLD, "$name 입력 어림 $tokens > ${ContextBudget.RAISE_INPUT_THRESHOLD}")
            assertTrue(ContextBudget.fitsStage(tokens, ContextBudget.effective(fileKv = null)), "$name 2048 예산 초과")
        }
    }

    @Test
    fun `실효 예산 - min(2048, 파일 KV), 모르면 요청값이되 미확인 표시(D79)`() {
        assertEquals(ContextBudget.Budget(2048, 1280, 1280), ContextBudget.effective(1280))
        assertEquals(2048, ContextBudget.effective(4096).effective, "큰 파일 KV는 요청값까지만")
        val unknown = ContextBudget.effective(null)
        assertEquals(2048, unknown.effective)
        assertFalse(unknown.fileKvKnown)
        assertEquals("2048,,2048", unknown.csv())
        assertEquals("2048,1280,1280", ContextBudget.effective(1280).csv())
        assertEquals(1280 - 384, ContextBudget.inputBudget(fileKv = 1280))
    }

    @Test
    fun `파일명 KV 표지 - Qwen3 ekv1280만 읽고 Gemma 3n은 모름(D79 추정값 금지)`() {
        assertEquals(1280, ContextBudget.fileKvFromName("Qwen3-0.6B_multi-prefill-seq_q8_ekv1280.litertlm"))
        assertEquals(4096, ContextBudget.fileKvFromName("model_EKV4096.litertlm"))
        assertNull(ContextBudget.fileKvFromName("gemma-3n-E2B-it-int4.litertlm"))
        assertNull(ContextBudget.fileKvFromName("checkekv1280x.litertlm"), "단어 경계 없으면 표지 아님")
        assertNull(ContextBudget.fileKvFromName("a_ekv0.litertlm"))
    }

    @Test
    fun `단계 사전 검사 - 프롬프트 + 384가 실효 예산을 넘으면 거짓(D79)`() {
        val qwen = ContextBudget.effective(1280)
        assertTrue(ContextBudget.fitsStage(896, qwen))
        assertFalse(ContextBudget.fitsStage(897, qwen))
        assertFalse(ContextBudget.fitsStage(-1, qwen), "음수 토큰 수는 계수기 오류 — 통과시키지 않는다")
    }

    @Test
    fun `파일 KV가 3072 미만이면 상향은 답이 아니다(D79)`() {
        assertFalse(ContextBudget.shouldTryRaise(1_500, fileKv = 1280))
        assertFalse(ContextBudget.shouldTryRaise(600, prefillErrors = 1, fileKv = 2048))
        assertTrue(ContextBudget.shouldTryRaise(1_500, fileKv = 4096))
        assertTrue(ContextBudget.shouldTryRaise(1_500, fileKv = null), "모르면 조건만 본다")
    }

    /**
     * A34 Qwen3-0.6B ekv1280 경로(D79: 실효 1280·입력 약 896)는 지금 프롬프트로는 2단계 기차가 들어가지 않는다 —
     * D79가 말한 '짧은 프롬프트'가 아직 없다는 기록이다(팀 합의 A12: 모델별 두 벌 vs 공통 짧은 판, I-80).
     * 짧은 판을 만들면 이 테스트를 '모두 들어간다'로 뒤집는다.
     */
    @Test
    fun `Qwen 1280 예산 - 1단계는 들어가고 2단계 기차는 넘친다(현 프롬프트, I-80)`() {
        val qwen = ContextBudget.effective(1280)
        val byName = longestStageRequests().toMap()
        assertTrue(ContextBudget.fitsStage(ConservativeTokenEstimate.count(byName.getValue("1단계")), qwen))
        assertFalse(ContextBudget.fitsStage(ConservativeTokenEstimate.count(byName.getValue("2단계 train_book")), qwen))
    }

    private fun longestStageRequests(): List<Pair<String, LlmRequest>> {
        val longest = "가".repeat(SlotPrompts.MAX_UTTERANCE_CHARS)
        return listOf("1단계" to LlmRequest(SlotPrompts.stage1System, longest, SlotSchemas.stage1(), SlotParser.STAGE1_TOKENS)) +
            listOf(Intent.TRAIN_BOOK, Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE).map {
                "2단계 ${it.wire}" to LlmRequest(SlotPrompts.stage2System(it), longest, SlotSchemas.stage2(it), SlotParser.STAGE2_TOKENS)
            }
    }
}
