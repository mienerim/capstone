package capstone.llm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 가짜 러너의 한 번 응답. */
sealed interface Script {
    /** 문자열을 [chunk]자씩 잘라 낸다. */
    data class Text(val text: String, val chunk: Int = 4) : Script
    /** 공백을 끝없이 낸다(#3510 문법 막힘 재현). */
    data object Spaces : Script
    /** 토큰을 끝없이 낸다(공백 아님). */
    data object Endless : Script
    /** 아무것도 내지 않고 cancel까지 멈춘다. */
    data object Hang : Script
    /** 생성 중 예외. */
    data object Crash : Script
}

/** 호출마다 다음 스크립트를 쓴다. 받은 요청을 기록해 프롬프트·스키마 전달을 확인한다. */
class FakeRunner(vararg scripts: Script) : LlmRunner {
    private val queue = ArrayDeque(scripts.toList())
    val requests = mutableListOf<LlmRequest>()
    @Volatile private var cancelled = CountDownLatch(1)
    @Volatile var cancelCount = 0

    override fun generate(request: LlmRequest, sink: (String) -> Boolean) {
        synchronized(this) { requests += request }
        cancelled = CountDownLatch(1)
        when (val s = synchronized(this) { queue.removeFirstOrNull() } ?: error("스크립트 소진")) {
            is Script.Text -> s.text.chunked(s.chunk).forEach { if (!sink(it)) return }
            Script.Spaces -> while (sink(" ")) Unit
            Script.Endless -> while (sink("가")) Unit
            Script.Hang -> cancelled.await(5, TimeUnit.SECONDS)
            Script.Crash -> throw IllegalStateException("엔진 오류")
        }
    }

    override fun cancel() {
        cancelCount++
        cancelled.countDown()
    }
}

fun text(s: String) = Script.Text(s)

/** 정상 1단계·2단계 출력 예. */
object Outputs {
    const val TRAIN = """{"intent":"train_book","app":"korail_plus","reason":null}"""
    const val SEOUL_BUSAN =
        """{"origin":"서울","destination":"부산","date":null,"date_rel":"tomorrow","time_from":"09:00","train_type":"ktx","adult":1,"senior":null,"missing":["senior"]}"""
}
