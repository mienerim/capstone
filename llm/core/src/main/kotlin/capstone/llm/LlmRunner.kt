package capstone.llm

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 한 번의 생성 요청. 입력은 사용자 발화뿐이다: 화면 텍스트는 llm에 들어오지 않는다(D32, I-38). */
data class LlmRequest(
    val systemInstruction: String,
    val userText: String,
    /** ResponseFormat.json에 넘길 JSON Schema. null이면 제약 없이 생성(폴백 경로, E 페이지 '무제약 생성 → 파서 검증'). */
    val responseSchema: String?,
    val maxOutputTokens: Int,
)

/**
 * 온디바이스 LLM 런타임 포트. 구현은 :llm:litertlm(LiteRT-LM 0.18.0)과 테스트용 가짜 러너.
 *
 * [generate]는 호출 스레드를 막고 토큰 조각을 차례로 [sink]에 넘긴다. sink가 false를 돌려주면 즉시 멈춰야 한다.
 * [cancel]은 다른 스레드에서 불린다(워치독 시간 초과). 엔진이 문법에 막혀 토큰을 안 내도(#3510) 멈출 수 있어야 한다.
 */
interface LlmRunner : AutoCloseable {
    fun generate(request: LlmRequest, sink: (String) -> Boolean)
    fun cancel()
    override fun close() {}
}

/**
 * 워치독 한도(D11 '앱 쪽 생성 워치독(시간·토큰)'). 수치는 W12 팀 임의값: maxOutputToken 256·10초·공백 연속 8개.
 * 토큰 수는 sink 호출 수로 센다(런타임이 조각 단위로 주므로 실제 토큰 수의 근사).
 */
data class WatchdogLimits(
    val maxTokens: Int = 256,
    val timeoutMs: Long = 10_000,
    /** 공백 문자(스페이스·탭·개행·U+2581)가 연달아 이만큼 나오면 #3510 무한 공백으로 보고 멈춘다. */
    val maxWhitespaceRun: Int = 8,
    val maxChars: Int = 2_048,
)

sealed interface Generation {
    data class Completed(val text: String, val tokens: Int, val elapsedMs: Long) : Generation
    data class Aborted(val cause: AbortCause, val tokens: Int, val elapsedMs: Long) : Generation
}

enum class AbortCause { TIMEOUT, TOKEN_LIMIT, WHITESPACE_LOOP, CHAR_LIMIT, RUNNER_ERROR }

/**
 * 러너를 워치독 아래에서 돌린다. 생성은 전용 스레드에서, 시간 한도는 호출 스레드가 지킨다.
 * 중간에 멈춘 출력은 버린다(잘린 JSON을 검증기에 넘기지 않음, #3854).
 */
class GuardedGenerator(
    private val runner: LlmRunner,
    private val limits: WatchdogLimits = WatchdogLimits(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    // 취소를 무시하는 러너가 스레드를 붙잡아도 다음 생성이 그 뒤에 줄 서지 않도록 캐시 풀을 쓴다.
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "llm-generate").apply { isDaemon = true } }

    fun generate(request: LlmRequest): Generation {
        val req = request.copy(maxOutputTokens = minOf(request.maxOutputTokens, limits.maxTokens))
        val started = nowMs()
        val out = StringBuilder()
        val tokens = AtomicInteger()
        val abort = AtomicReference<AbortCause?>(null)

        val task = pool.submit {
            var wsRun = 0
            runner.generate(req) { chunk ->
                if (abort.get() != null) return@generate false
                val n = tokens.incrementAndGet()
                out.append(chunk)
                for (c in chunk) wsRun = if (c.isWhitespace() || c == '\u2581') wsRun + 1 else 0
                val cause = when {
                    wsRun >= limits.maxWhitespaceRun -> AbortCause.WHITESPACE_LOOP
                    n > limits.maxTokens -> AbortCause.TOKEN_LIMIT
                    out.length > limits.maxChars -> AbortCause.CHAR_LIMIT
                    else -> null
                }
                cause == null || !abort.compareAndSet(null, cause)
            }
        }
        try {
            task.get(limits.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            abort.compareAndSet(null, AbortCause.TIMEOUT)
            runner.cancel()
            task.cancel(true)
        } catch (_: ExecutionException) {
            abort.compareAndSet(null, AbortCause.RUNNER_ERROR)
        } catch (e: InterruptedException) {
            runner.cancel()
            Thread.currentThread().interrupt()
            throw e
        }
        val elapsed = nowMs() - started
        // 시간 초과 뒤 out은 작업 스레드가 아직 쓰고 있을 수 있으므로 정상 종료일 때만 읽는다(get이 happens-before를 준다).
        return abort.get()?.let { Generation.Aborted(it, tokens.get(), elapsed) }
            ?: Generation.Completed(out.toString(), tokens.get(), elapsed)
    }

    override fun close() {
        pool.shutdownNow()
    }
}
