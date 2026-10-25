package capstone.llm

/**
 * 엔진 KV 한도(D79, D77 정정). LiteRT-LM의 `maxNumTokens`는 입력+출력 합계다(D65, v0.18.0 C++ 원문 확인).
 *
 * 요청값은 전 기기 [MAX_NUM_TOKENS] 2048 하나(D77). 그러나 정적 .litertlm은 파일에 컴파일된 KV보다 큰 요청을
 * 오류 없이 WARNING "…Using model limit." 후 파일 값으로 낮춘다(v0.18.0 `ClampMaxNumTokens`) → **실효 예산 = min(2048, 파일 KV)**.
 * 그래서 예산 검사는 요청값이 아니라 [effective]로 하고, 1·2단계 각각 '프롬프트 토큰 + 출력 384 ≤ 실효 예산'을 본다([fitsStage]).
 *
 * 우리 llm 입력에는 UI 트리가 없다(D3·D18·D32, I-38). 요청마다 새 Conversation이라 단계 간 누적도 없다.
 */
object ContextBudget {
    /** EngineConfig.maxNumTokens 요청값(D77). 실효 예산은 [effective]. */
    const val MAX_NUM_TOKENS: Int = 2048

    /** W03 상향 조건이 걸렸을 때 PSS를 다시 잴 후보값(D77). 코드 기본값이 아니다. 파일 KV가 이보다 작으면 의미 없다(D79). */
    const val RAISE_CANDIDATE: Int = 3072

    /** 템플릿 적용 뒤 최종 렌더링 입력 토큰이 이 값을 넘으면 상향 후보(D77, 입력 예산 약 1,664 = 2048 − 384에서 여유를 둔 값). */
    const val RAISE_INPUT_THRESHOLD: Int = 1400

    /** 엔진 maxOutputToken·워치독 토큰 상한(D65). 예산 검사에서 출력 몫으로 뺀다. */
    val MAX_OUTPUT_TOKENS: Int get() = WatchdogLimits().maxTokens

    /**
     * 모델 파일의 KV. 엔진 생성 전 `ModelInfo.from(path).maxContextTokens()`(0.18.0 소스 공개 API)로 읽는 것이 D79 1순위인데,
     * AAR 노출이 10-26 확인 전이라 지금은 파일명 표지(`_ekv1280` 등)만 읽는다. 모르면 null — 추정값을 넣지 않는다(D79 'Gemma 3n 추정값 사용 금지').
     */
    fun fileKvFromName(fileName: String): Int? =
        EKV.find(fileName.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** 실효 예산 = min(요청, 파일 KV). 파일 KV를 모르면 요청값 그대로 두되 [Budget.fileKvKnown]이 false라 실험 CSV에 '미확인'으로 남는다. */
    fun effective(fileKv: Int?, requested: Int = MAX_NUM_TOKENS): Budget =
        Budget(requested = requested, fileKv = fileKv, effective = fileKv?.let { minOf(requested, it) } ?: requested)

    /** 입력에 쓸 수 있는 몫. 출력 상한(384, D65)을 뺀 나머지. 파일 KV를 모르면 요청값 2048 기준(1,664). */
    fun inputBudget(maxOutputTokens: Int = MAX_OUTPUT_TOKENS, fileKv: Int? = null): Int =
        effective(fileKv).effective - maxOutputTokens

    /** 단계 사전 검사(D79): 프롬프트 토큰(템플릿 포함) + 출력 몫 ≤ 실효 예산. 거짓이면 그 단계는 실행 0이다. */
    fun fitsStage(promptTokens: Int, budget: Budget, maxOutputTokens: Int = MAX_OUTPUT_TOKENS): Boolean =
        promptTokens >= 0 && promptTokens + maxOutputTokens <= budget.effective

    /**
     * W03 판정(D77). 입력 토큰은 1·2단계 각각, 최장 예시 + 최장 발화(장황한 발화·STT 반복 오인식 포함)의 **최대값**을 넣는다.
     * 평균을 넣으면 안 된다(검증 보강). Prefill 에러나 kMaxNumTokensReached가 1회라도 있으면 그것만으로 상향 후보다.
     *
     * D79: 파일 KV가 [RAISE_CANDIDATE]보다 작다고 확인되면 요청을 올려도 클램프되므로 상향은 답이 아니다(false) —
     * 그때는 예시·지시를 줄인다. 파일 KV를 모르면(null) 조건만 본다(10-26 peek로 확정).
     */
    fun shouldTryRaise(
        maxRenderedInputTokens: Int,
        prefillErrors: Int = 0,
        maxTokensReached: Int = 0,
        fileKv: Int? = null,
    ): Boolean {
        if (fileKv != null && fileKv < RAISE_CANDIDATE) return false
        return maxRenderedInputTokens > RAISE_INPUT_THRESHOLD || prefillErrors > 0 || maxTokensReached > 0
    }

    /** 실험 CSV 필수 열(D79): 요청값·파일 KV(모르면 빈칸)·실효값. */
    data class Budget(val requested: Int, val fileKv: Int?, val effective: Int) {
        val fileKvKnown: Boolean get() = fileKv != null
        fun csv(): String = "$requested,${fileKv ?: ""},$effective"
    }

    // litert-community 파일명 관례: Qwen3-0.6B ..._ekv1280.litertlm(KV 1280 고정으로 읽힘, D79 확신도 중간).
    private val EKV = Regex("""(?:^|[_\-.])ekv(\d{3,6})(?=[_\-.]|$)""")
}

/**
 * 단계 사전 검사에 쓸 프롬프트 토큰 수. 진짜 값은 모델 토크나이저 + 채팅 템플릿으로 센 수다(D79) —
 * 0.18.0 Kotlin API에 토큰 세기가 있는지는 AAR 확인 전이라, 그 전에는 [ConservativeTokenEstimate]를 쓴다.
 * null을 돌려주면 셀 수 없다는 뜻이고, 그 단계는 검사 없이 진행한다(엔진 Prefill 에러 → 워치독 → 되묻기로 fail-closed).
 */
fun interface PromptTokenCounter {
    fun count(request: LlmRequest): Int?
}

/**
 * 실측 전 보수적 어림(I-79·I-80): 비ASCII(한글) 1글자 = 1토큰, ASCII 2글자 = 1토큰(보통 3~4글자 — 추정).
 * 스키마는 프롬프트에 넣지 않고 ResponseFormat으로만 넘기지만, 엔진이 주입하는지 미확인이라(D77) 한 번 센다.
 * 과대 어림이라 실제보다 일찍 막을 수는 있어도 늦게 막지는 않는 쪽이다.
 */
object ConservativeTokenEstimate : PromptTokenCounter {
    /** 채팅 템플릿의 턴 표지(<start_of_turn>user, Qwen의 빈 <think></think> 등) 몫. 넉넉히 잡은 추정값. */
    const val TEMPLATE_OVERHEAD_TOKENS: Int = 50

    fun upper(s: String): Int {
        val ascii = s.count { it.code < 128 }
        return (s.length - ascii) + (ascii + 1) / 2
    }

    override fun count(request: LlmRequest): Int =
        upper(request.systemInstruction) + upper(request.responseSchema.orEmpty()) + upper(request.userText) + TEMPLATE_OVERHEAD_TOKENS
}
