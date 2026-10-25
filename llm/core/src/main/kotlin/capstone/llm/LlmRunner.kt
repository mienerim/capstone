package capstone.llm

import java.util.concurrent.CountDownLatch
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
 * 워치독 한도(D11 '앱 쪽 생성 워치독(시간·토큰)'). 수치는 W12 팀 임의값(10초·공백 연속 8개)과 D65 maxOutputToken 384.
 * 토큰 수는 sink 호출 수로 센다(런타임이 조각 단위로 주므로 실제 토큰 수의 근사).
 * 엔진은 maxOutputToken에 닿으면 에러 없이 끝낸다(D65) → 잘림은 [SlotValidator]의 닫힘 검사(`truncated`)가 잡는다.
 */
data class WatchdogLimits(
    val maxTokens: Int = 384,
    val timeoutMs: Long = 10_000,
    /** 공백 문자(스페이스·탭·개행·U+2581)가 연달아 이만큼 나오면 #3510 무한 공백으로 보고 멈춘다. */
    val maxWhitespaceRun: Int = 8,
    val maxChars: Int = 2_048,
)

sealed interface Generation {
    data class Completed(val text: String, val tokens: Int, val elapsedMs: Long) : Generation
    data class Aborted(val cause: AbortCause, val tokens: Int, val elapsedMs: Long) : Generation
}

enum class AbortCause {
    TIMEOUT, TOKEN_LIMIT, WHITESPACE_LOOP, CHAR_LIMIT, RUNNER_ERROR,

    /** 앞 생성이 취소 뒤에도 끝나지 않아 이번 생성을 시작하지 않았다(엔진 동시 사용 금지, I-95). 러너는 부르지 않았다. */
    RUNNER_BUSY,
}

/**
 * 러너를 워치독 아래에서 돌린다. 생성은 전용 스레드에서, 시간 한도는 호출 스레드가 지킨다.
 * 중간에 멈춘 출력은 버린다(잘린 JSON을 검증기에 넘기지 않음, #3854).
 *
 * 러너의 generate는 한 번에 하나만 돈다(I-95): 시간 초과로 버린 생성이 취소를 늦게 받거나 무시해 아직 엔진 안에 있으면,
 * 다음 생성은 그것이 끝나기를 이번 시간 한도 안에서 기다리고, 그래도 안 끝나면 러너를 부르지 않고 [AbortCause.RUNNER_BUSY]로 끝낸다.
 * LiteRT-LM 엔진·대화는 동시 호출을 전제로 하지 않고, 어댑터의 cancel 대상(current)도 하나뿐이기 때문이다.
 * [generate]는 한 스레드(앱의 llm 스레드)에서만 부른다.
 */
class GuardedGenerator(
    private val runner: LlmRunner,
    private val limits: WatchdogLimits = WatchdogLimits(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    // 취소를 무시하는 러너가 스레드를 붙잡아도 다음 생성이 그 뒤에 줄 서지 않도록 캐시 풀을 쓴다.
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "llm-generate").apply { isDaemon = true } }

    /** 앞 생성의 러너 호출이 끝나면 열린다. 처음엔 열려 있다. */
    @Volatile private var previous = CountDownLatch(0)

    fun generate(request: LlmRequest): Generation {
        val req = request.copy(maxOutputTokens = minOf(request.maxOutputTokens, limits.maxTokens))
        val started = nowMs()
        val waitStart = System.nanoTime()
        if (!previous.await(limits.timeoutMs, TimeUnit.MILLISECONDS)) {
            runner.cancel()
            return Generation.Aborted(AbortCause.RUNNER_BUSY, 0, nowMs() - started)
        }
        val remainingMs = (limits.timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart)).coerceAtLeast(1)
        val out = StringBuilder()
        val tokens = AtomicInteger()
        val abort = AtomicReference<AbortCause?>(null)
        val done = CountDownLatch(1)
        previous = done
        // 0 대기 → 1 실행 중 → 2 시작 전 취소. 시작 전에 취소된 작업은 본문이 돌지 않으므로 호출 스레드가 done을 연다.
        val state = AtomicInteger(0)

        val task = pool.submit {
            if (!state.compareAndSet(0, 1)) return@submit
            try {
                generateGuarded(req, out, tokens, abort)
            } finally {
                done.countDown()
            }
        }
        try {
            task.get(remainingMs, TimeUnit.MILLISECONDS)
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
        } finally {
            if (state.compareAndSet(0, 2)) done.countDown()
        }
        val elapsed = nowMs() - started
        // 시간 초과 뒤 out은 작업 스레드가 아직 쓰고 있을 수 있으므로 정상 종료일 때만 읽는다(get이 happens-before를 준다).
        return abort.get()?.let { Generation.Aborted(it, tokens.get(), elapsed) }
            ?: Generation.Completed(out.toString(), tokens.get(), elapsed)
    }

    private fun generateGuarded(req: LlmRequest, out: StringBuilder, tokens: AtomicInteger, abort: AtomicReference<AbortCause?>) {
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

    override fun close() {
        pool.shutdownNow()
    }
}
