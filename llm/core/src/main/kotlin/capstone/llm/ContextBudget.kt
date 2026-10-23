package capstone.llm

/**
 * 엔진 KV 한도(D77, D65 정정). LiteRT-LM의 `maxNumTokens`는 입력+출력 합계다(D65, v0.18.0 C++ 원문 확인).
 *
 * 전 기기 2048 하나(A34 포함). D65의 4096 후보는 'UI 트리 포함' 전제였는데, 우리 llm 입력은
 * 시스템 지시 + 스키마 + 발화뿐이고 UI 트리는 넣지 않는다(D3·D18·D32, I-38). 요청마다 새 Conversation이라 단계 간 누적도 없다.
 *
 * 올리는 조건은 [shouldTryRaise] — W03 실측으로만 판정하고, 그때도 바로 바꾸지 않고 [RAISE_CANDIDATE]로 PSS부터 다시 잰다.
 */
object ContextBudget {
    const val MAX_NUM_TOKENS: Int = 2048

    /** W03 상향 조건이 걸렸을 때 PSS를 다시 잴 후보값(D77). 코드 기본값이 아니다. */
    const val RAISE_CANDIDATE: Int = 3072

    /** 템플릿 적용 뒤 최종 렌더링 입력 토큰이 이 값을 넘으면 상향 후보(D77, 입력 예산 약 1,664 = 2048 − 384에서 여유를 둔 값). */
    const val RAISE_INPUT_THRESHOLD: Int = 1400

    /** 입력에 쓸 수 있는 몫. 출력 상한(워치독 384, D65)을 뺀 나머지. */
    fun inputBudget(maxOutputTokens: Int = WatchdogLimits().maxTokens): Int = MAX_NUM_TOKENS - maxOutputTokens

    /**
     * W03 판정(D77). 입력 토큰은 1·2단계 각각, 최장 예시 + 최장 발화(장황한 발화·STT 반복 오인식 포함)의 **최대값**을 넣는다.
     * 평균을 넣으면 안 된다(검증 보강). Prefill 에러나 kMaxNumTokensReached가 1회라도 있으면 그것만으로 상향 후보다.
     *
     * 넘쳤을 때 few-shot을 줄이는 것을 3072보다 먼저 검토한다(팀 합의) — 그 판단은 이 함수 밖이다.
     */
    fun shouldTryRaise(maxRenderedInputTokens: Int, prefillErrors: Int = 0, maxTokensReached: Int = 0): Boolean =
        maxRenderedInputTokens > RAISE_INPUT_THRESHOLD || prefillErrors > 0 || maxTokensReached > 0
}
