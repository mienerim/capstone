package capstone.app.core

import capstone.agent.HandoffTrigger
import capstone.agent.RunState
import capstone.llm.DateRel
import capstone.llm.SlotName
import capstone.llm.SlotsV1
import capstone.llm.TrainType
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 사용자에게 보여 주고 읽어 줄 문장(D16 큰 글씨+복창). 모든 문장은 코드가 검증된 슬롯 값으로 만든다:
 * 모델 출력 문자열을 그대로 화면에 내지 않는다(D18 ②, I-37).
 */
object Readback {

    /** 시나리오가 화면에 넣는 조건 전부(D30 wire). [confirm]의 기본값 — 넣지 않는 조건이 없으면 알릴 것도 없다. */
    val ALL_TRAIN_SLOTS: Set<String> = setOf(SlotName.ORIGIN, SlotName.DESTINATION, SlotName.DATE, SlotName.TIME_FROM, SlotName.TRAIN_TYPE, SlotName.ADULT, SlotName.SENIOR)
        .map { it.wire }.toSet()

    /**
     * 복창 확인 문장. 상대 날짜는 오늘 기준으로 풀어서 날짜까지 말한다(어제·내일 헷갈림 방지).
     * 사용자가 말했지만 시나리오가 화면에 넣지 않는 조건([applied] 밖)은 끝에 이름을 대고 직접 확인하라고 덧붙인다:
     * '네'를 누른 문장이 실행할 것보다 많이 약속하지 않게 하기 위해서다(I-94).
     */
    fun confirm(s: SlotsV1, today: LocalDate, applied: Set<String> = ALL_TRAIN_SLOTS): String = buildString {
        s.resolveDate(today)?.let { append(dateText(it, today)).append(' ') }
        s.timeFrom?.let { append(timeText(it)).append(" 이후 ") }
        append("${s.origin}에서 ${s.destination} 가는 ")
        append(
            when (s.trainType) {
                TrainType.KTX -> "KTX"
                TrainType.GENERAL -> "일반열차"
                null -> "열차"
            },
        )
        append("를 찾을까요?")
        people(s)?.let { append(" (").append(it).append(")") }
        notApplied(s, applied).takeIf { it.isNotEmpty() }?.let {
            append(" ").append(it.joinToString("·")).append(josa(it.last())).append(" 제가 넣지 않아요. 앱 화면에서 직접 확인해 주세요.")
        }
    }

    /** 말했지만 넣지 않는 조건의 이름(문장 순서). */
    fun notApplied(s: SlotsV1, applied: Set<String>): List<String> = buildList {
        if ((s.date != null || s.dateRel != null) && SlotName.DATE.wire !in applied) add("날짜")
        if (s.timeFrom != null && SlotName.TIME_FROM.wire !in applied) add("시간")
        if (s.trainType != null && SlotName.TRAIN_TYPE.wire !in applied) add("열차 종류")
        val adult = (s.adult ?: 0) > 0 && SlotName.ADULT.wire !in applied
        val senior = (s.senior ?: 0) > 0 && SlotName.SENIOR.wire !in applied
        if (adult || senior) add("인원")
    }

    private fun josa(word: String): String = if ((word.last() - '가') % 28 == 0) "는" else "은"

    fun dateText(d: LocalDate, today: LocalDate): String {
        val rel = DateRel.entries.firstOrNull { today.plusDays(it.days) == d }?.let { REL_WORD.getValue(it) + " " }.orEmpty()
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return "$rel${d.monthValue}월 ${d.dayOfMonth}일($dow)"
    }

    /** "14:30" → "오후 2시 30분". */
    fun timeText(hhmm: String): String {
        val (h, m) = hhmm.split(':').map(String::toInt)
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$ampm ${h12}시" + if (m > 0) " ${m}분" else ""
    }

    private fun people(s: SlotsV1): String? {
        val parts = listOfNotNull(s.adult?.takeIf { it > 0 }?.let { "어른 ${it}명" }, s.senior?.takeIf { it > 0 }?.let { "경로 ${it}명" })
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun question(slot: SlotName): String = when (slot) {
        SlotName.ORIGIN -> "어디에서 출발하세요?"
        SlotName.DESTINATION -> "어디로 가세요?"
        SlotName.DATE -> "언제 가세요?"
        else -> "다시 말씀해 주세요."
    }

    fun runState(st: RunState): String = when (st) {
        is RunState.Running -> "잠시만요, 코레일+에서 찾고 있어요."
        is RunState.AskUser -> st.question
        is RunState.Handoff -> handoff(st.trigger)
        is RunState.PausedForUser -> "멈췄어요. '계속'을 누르면 이어서 합니다."
        is RunState.Done -> st.summary
        is RunState.Stopped -> stopped(st.reason)
    }

    fun handoff(t: HandoffTrigger): String = when (t) {
        HandoffTrigger.LOADING -> "화면이 아직 안 보여요. 잠시 기다리시거나 직접 진행해 주세요. 화면이 돌아오면 이어서 합니다."
        HandoffTrigger.SENSITIVE_INPUT -> "로그인·비밀번호는 직접 입력해 주세요. 끝나면 이어서 합니다."
        HandoffTrigger.USER_AUTH -> "본인 인증은 직접 해 주세요. 저는 인증 화면을 누르지 않아요. 끝나면 이어서 합니다."
        HandoffTrigger.DATA_SENSITIVE, HandoffTrigger.SECURE_WINDOW ->
            "보호된 화면이라 제가 볼 수 없어요. 이 부분은 직접 진행해 주세요. 끝나면 이어서 합니다."
    }

    /** 러너 정지 사유 코드 → 안내. 코드 원문은 화면에 내지 않고 실행 기록에만 남긴다. */
    fun stopped(reason: String): String = when {
        reason.startsWith("gate:") || reason == "flow_targets_confirm" ->
            "안전을 위해 여기서 멈췄어요. 이 다음은 직접 확인해 주세요."
        reason == "limit:queries" -> "여러 번 조회해도 안 돼서 멈췄어요. 잠시 뒤에 다시 해 주세요."
        reason.startsWith("handoff_timeout") -> "오래 기다려도 화면이 돌아오지 않아 멈췄어요."
        else -> "화면이 예상과 달라 멈췄어요. 코레일+ 화면을 확인해 주세요."
    }

    private val REL_WORD = mapOf(DateRel.TODAY to "오늘", DateRel.TOMORROW to "내일", DateRel.DAY_AFTER_TOMORROW to "모레")
}
