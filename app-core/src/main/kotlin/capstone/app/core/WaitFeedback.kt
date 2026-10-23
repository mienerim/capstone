package capstone.app.core

/** 대기 중 한 번씩 내는 신호(D73). 앱이 소리·음성·글자로 바꾼다 — 효과음·아이콘만 쓰지 않고 음성+문구를 함께. */
sealed interface WaitCue {
    /** ① 발화 종료 직후: 확인음 + 큰 글씨 '생각 중'. */
    data object Thinking : WaitCue

    /** ② 3초 무응답: 작업명 복창형 필러 1회(반복 금지). */
    data class Filler(val text: String) : WaitCue

    /** ③ 10초 이상: '계속 기다릴까요?' 선택지 + 진행 표시. */
    data object AskContinue : WaitCue

    /** ④ 약 25초 무응답: 안전 중단(결제 미진행). 늦게 온 파싱 결과는 버린다. */
    data object Abort : WaitCue
}

/**
 * 발화 종료 ~ 복창 시작 사이의 대기 피드백 v1(D73). 시각은 [start]에 넘긴 발화 종료 시각부터 잰다.
 * 단계값 1/3/10/25초는 문헌 기반 가설 — W12 실측(팀원 3명, D61: 고령자 파일럿 없음)으로 보정한다.
 *
 * - [poll]은 아직 내지 않은 신호 중 시각이 된 것을 순서대로 한 번씩만 돌려준다(앱은 0.5초쯤마다 부르면 된다).
 * - '계속 기다릴게요'([keepWaiting])는 질문만 닫는다. 25초 안전 중단은 그대로 둔다(가장 단순한 해석, I-72).
 * - 필러 문구는 대기마다 다음 변형으로 돌린다(같은 문구 연속 금지). 작업명은 코드가 정한다(LLM 출력 아님).
 */
class WaitFeedback(
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val thinkingMs: Long = 0,
        val fillerMs: Long = 3_000,
        val askMs: Long = 10_000,
        val abortMs: Long = 25_000,
    ) {
        init {
            require(thinkingMs in 0..1_000) { "D73 ①: 1초 안" }
            require(thinkingMs < fillerMs && fillerMs < askMs && askMs < abortMs)
        }
    }

    private var startedAt: Long? = null
    private var emitted = 0
    private var fillerIndex = -1
    private var task = DEFAULT_TASK

    /** 지금 '계속 기다릴까요?'를 보여 줘야 하는가. */
    var asking: Boolean = false
        private set

    val active: Boolean get() = startedAt != null

    fun start(nowMs: Long, taskName: String = DEFAULT_TASK) {
        startedAt = nowMs
        emitted = 0
        asking = false
        task = taskName
        fillerIndex = (fillerIndex + 1) % FILLERS.size
    }

    /** 결과가 왔거나(복창 시작) 사용자가 그만뒀다. */
    fun stop() {
        startedAt = null
        asking = false
    }

    fun keepWaiting() {
        asking = false
    }

    fun poll(nowMs: Long): List<WaitCue> {
        val t0 = startedAt ?: return emptyList()
        val elapsed = nowMs - t0
        val out = mutableListOf<WaitCue>()
        val steps = listOf(timing.thinkingMs, timing.fillerMs, timing.askMs, timing.abortMs)
        while (emitted < steps.size && elapsed >= steps[emitted]) {
            out += when (emitted) {
                0 -> WaitCue.Thinking
                1 -> WaitCue.Filler(FILLERS[fillerIndex](task))
                2 -> WaitCue.AskContinue.also { asking = true }
                else -> WaitCue.Abort
            }
            emitted++
        }
        if (WaitCue.Abort in out) stop()
        return out
    }

    companion object {
        /** MVP는 기차 예매만이라 파싱 전에도 작업명을 안다. */
        const val DEFAULT_TASK = "기차표 예매"

        const val THINKING_TEXT = "생각 중이에요…"
        const val ASK_TEXT = "조금 더 걸려요. 계속 기다릴까요?"
        const val KEEP = "계속 기다릴게요"
        const val CANCEL = "그만할게요"
        const val ABORT_TEXT = "시간이 너무 오래 걸려서 멈췄어요. 결제는 하지 않았어요. 다시 말씀하시거나 '버튼으로 찾기'를 눌러 주세요."

        /** 작업명 복창형 필러(D73 ②, 변형 3개). */
        val FILLERS: List<(String) -> String> = listOf(
            { t -> "$t 내용을 정리하는 중이에요." },
            { t -> "$t 준비하고 있어요. 조금만 기다려 주세요." },
            { t -> "곧 $t 내용을 말씀드릴게요." },
        )
    }
}
