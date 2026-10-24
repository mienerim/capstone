package capstone.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * D17 일일 한도: 실서비스(코레일+) 실행은 하루 5건. 목업 실행은 세지 않는다.
 * '건' = 확인 버튼으로 시작한 실행 1회(1건 1흐름). 하루는 한국 시간 자정 기준.
 * 팀 합계 한도는 기기 하나에서 셀 수 없으므로 기기별로 세고, 합계는 사람이 측정 시트로 맞춘다(I-47).
 * 기기 시계를 뒤로 돌려도 한도가 다시 생기지 않도록, '지금'은 기록된 가장 늦은 시작 시각보다 앞서지 않는다(I-85).
 */
class DailyRunLimiter(
    private val store: KeyValueStore,
    private val maxPerDay: Int = 5,
    private val zone: ZoneId = KST,
) {
    fun remaining(nowMs: Long): Int = maxPerDay - today(nowMs).second.size

    /** 한도 안이면 기록하고 true. 한도를 넘으면 기록하지 않고 false. */
    fun tryStart(nowMs: Long): Boolean {
        val (now, runs) = today(nowMs)
        if (runs.size >= maxPerDay) return false
        store.put(KEY, (runs + now).joinToString(","))
        return true
    }

    /** (시계가 뒤로 간 만큼 당긴 '지금', 그날의 시작 기록). 지난 날짜 기록은 다음 tryStart가 지운다. */
    private fun today(nowMs: Long): Pair<Long, List<Long>> {
        val all = store.get(KEY).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val now = maxOf(nowMs, all.maxOrNull() ?: nowMs)
        val day = dayOf(now)
        return now to all.filter { dayOf(it) == day }
    }

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val KEY = "runs.live"
    }
}
