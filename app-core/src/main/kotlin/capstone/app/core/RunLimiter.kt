package capstone.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * D17 일일 한도: 실서비스(코레일+) 실행은 하루 5건. 목업 실행은 세지 않는다.
 * '건' = 확인 버튼으로 시작한 실행 1회(1건 1흐름). 하루는 한국 시간 자정 기준.
 * 팀 합계 한도는 기기 하나에서 셀 수 없으므로 기기별로 세고, 합계는 사람이 측정 시트로 맞춘다(I-47).
 */
class DailyRunLimiter(
    private val store: KeyValueStore,
    private val maxPerDay: Int = 5,
    private val zone: ZoneId = KST,
) {
    fun remaining(nowMs: Long): Int = maxPerDay - today(nowMs).size

    /** 한도 안이면 기록하고 true. 한도를 넘으면 기록하지 않고 false. */
    fun tryStart(nowMs: Long): Boolean {
        val runs = today(nowMs)
        if (runs.size >= maxPerDay) return false
        store.put(KEY, (runs + nowMs).joinToString(","))
        return true
    }

    private fun today(nowMs: Long): List<Long> {
        val day = dayOf(nowMs)
        return store.get(KEY).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { dayOf(it) == day }
    }

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private const val KEY = "runs.live"
    }
}
