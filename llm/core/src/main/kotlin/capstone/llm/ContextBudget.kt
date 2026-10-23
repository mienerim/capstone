package capstone.llm

/**
 * 엔진 KV 한도(D65). LiteRT-LM의 `maxNumTokens`는 입력+출력 합계이고 KV 메모리는 이 값에 비례하므로 기기 RAM으로 고른다.
 *
 * - 8GB 이상: 4096(후보, 10-26 첫날 PSS 실측 후 확정 — S24 8GB 공존이 W04에서 깨지면 2048로 내린다)
 * - 그 아래(A34 6GB, W13): 2048
 *
 * 우리 llm 입력은 발화 200자 + 시스템 지시 + 스키마뿐이고 UI 트리는 넣지 않는다(D32, I-38). 그래서 2048도 지금 입력에는 넉넉하다.
 */
object ContextBudget {
    const val LARGE: Int = 4096
    const val SMALL: Int = 2048

    /**
     * 커널이 예약분을 빼고 보고하므로 '8GB' 기기의 `MemInfo.totalMem`은 7.2~7.6GiB쯤 나온다(추정).
     * 6GB 기기(약 5.5GiB)와 가르기 위해 경계를 7GiB에 둔다.
     */
    const val LARGE_MIN_TOTAL_BYTES: Long = 7L * 1024 * 1024 * 1024

    /** @param totalMemBytes ActivityManager.MemoryInfo.totalMem. 0 이하(못 읽음)면 작은 쪽으로. */
    fun maxNumTokens(totalMemBytes: Long): Int = if (totalMemBytes >= LARGE_MIN_TOTAL_BYTES) LARGE else SMALL
}
