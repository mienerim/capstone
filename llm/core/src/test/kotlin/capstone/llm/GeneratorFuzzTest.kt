package capstone.llm

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 워치독 퍼즈(D11 '앱 쪽 생성 워치독' 보강, I-95). 실기기 엔진이 할 수 있는 이상한 짓 — 취소를 늦게 받기·무시하기,
 * sink의 false를 무시하고 계속 내기, 내다가 예외 — 을 섞은 러너로 [GuardedGenerator]를 연달아 부른다.
 *
 * 불변식(러너가 실제로 한 일로 판정):
 * ① 러너 generate는 동시에 둘 이상 돌지 않는다(엔진·대화 동시 사용 0)
 * ② generate는 시간 한도 + 여유 안에 돌아온다
 * ③ Completed면 이번 호출이 시작한 러너 호출이 예외 없이 끝났고, 글자는 그 호출이 sink에 넘긴 조각을 이은 것과 같다
 */
class GeneratorFuzzTest {
    private enum class Kind { TEXT, SLOW_TEXT, SPACES, ENDLESS, HANG, HANG_LATE, ZOMBIE, DEAF, CRASH_EARLY, CRASH_LATE }

    private class Call(val kind: Kind) {
        val accepted = StringBuilder()
        @Volatile var returnedNormally = false
        @Volatile var threw = false
        val cancelled = CountDownLatch(1)
    }

    private data class Plan(val call: Call, val extraMs: Long, val text: String, val deaf: Int)

    /** 호출마다 행동을 시드에서 뽑는다. 활성 호출 수와 최댓값을 센다. */
    private class ChaosRunner(private val r: Random, private val timeoutMs: Long) : LlmRunner {
        val calls = mutableListOf<Call>()
        private val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val overlaps = AtomicInteger()

        override fun generate(request: LlmRequest, sink: (String) -> Boolean) {
            // 겹친 호출이 같은 Random을 쓰지 않도록 필요한 값을 한 번에 뽑는다(시드 재현).
            val (call, extraMs, text, deaf) = synchronized(this) {
                val k = Kind.entries[r.nextInt(Kind.entries.size)]
                Plan(Call(k).also { calls += it }, r.nextLong(0, timeoutMs * 3), randomText(r), r.nextInt(1, 50))
            }
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now, ::maxOf)
            if (now > 1) overlaps.incrementAndGet()
            try {
                fun emit(s: String) = sink(s).also { if (it) call.accepted.append(s) }
                when (call.kind) {
                    Kind.TEXT -> text.chunked(3).forEach { if (!emit(it)) return }
                    Kind.SLOW_TEXT -> text.chunked(3).forEach {
                        Thread.sleep(extraMs / 8)
                        if (!emit(it)) return
                    }
                    Kind.SPACES -> while (emit(" ")) Unit
                    Kind.ENDLESS -> while (emit("가")) Unit
                    Kind.HANG -> call.cancelled.await(5, TimeUnit.SECONDS)
                    // 취소를 받고도 엔진이 한참 뒤에 멈춘다(cancelProcess가 늦게 듣는 경우).
                    Kind.HANG_LATE -> {
                        call.cancelled.await(5, TimeUnit.SECONDS)
                        sleepUninterruptibly(extraMs)
                    }
                    // 취소·인터럽트를 모두 무시하고 정해진 시간 뒤에 끝난다(네이티브 생성이 콜백 밖에서 도는 경우).
                    Kind.ZOMBIE -> {
                        sleepUninterruptibly(timeoutMs + extraMs)
                        emit(text)
                    }
                    // sink가 false여도 계속 낸다.
                    Kind.DEAF -> repeat(deaf) { emit(if (it % 2 == 0) " " else "x") }
                    Kind.CRASH_EARLY -> throw IllegalStateException("엔진 오류")
                    Kind.CRASH_LATE -> {
                        text.chunked(3).forEach { if (!emit(it)) return }
                        throw IllegalStateException("엔진 오류")
                    }
                }
                call.returnedNormally = true
            } catch (e: RuntimeException) {
                call.threw = true
                throw e
            } finally {
                active.decrementAndGet()
            }
        }

        override fun cancel() {
            synchronized(this) { calls.toList() }.forEach { it.cancelled.countDown() }
        }

        private fun sleepUninterruptibly(ms: Long) {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
            var interrupted = false
            while (true) {
                val left = end - System.nanoTime()
                if (left <= 0) break
                try {
                    TimeUnit.NANOSECONDS.sleep(left)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun awaitIdle() {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (active.get() > 0 && System.nanoTime() < end) Thread.sleep(5)
        }

        companion object {
            fun randomText(r: Random): String = when (r.nextInt(3)) {
                0 -> Outputs.TRAIN
                1 -> Outputs.SEOUL_BUSAN
                else -> List(r.nextInt(0, 40)) { "{}\"서울 ,:x"[r.nextInt(10)] }.joinToString("")
            }
        }
    }

    private val req = LlmRequest("sys", "서울 부산", null, 64)

    @Test
    fun `이상한 러너를 연달아 불러도 엔진 동시 사용 0·시간 한도 지킴·Completed는 그 호출의 출력`() {
        val timeoutMs = 30L
        val limits = WatchdogLimits(timeoutMs = timeoutMs, maxTokens = 64, maxChars = 256)
        val violations = mutableListOf<String>()
        var busy = 0
        for (seed in 400_000 until 400_120) {
            val r = Random(seed)
            val runner = ChaosRunner(r, timeoutMs)
            GuardedGenerator(runner, limits).use { g ->
                repeat(6) { i ->
                    val before = synchronized(runner) { runner.calls.size }
                    val t0 = System.nanoTime()
                    val res = g.generate(req)
                    val wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    if (wallMs > timeoutMs + SLACK_MS) violations += "seed=$seed#$i 시간 ${wallMs}ms"
                    if (res is Generation.Aborted && res.cause == AbortCause.RUNNER_BUSY) busy++
                    if (res is Generation.Completed) {
                        val mine = synchronized(runner) { runner.calls.getOrNull(before) }
                        when {
                            mine == null -> violations += "seed=$seed#$i Completed인데 러너 호출 없음"
                            !mine.returnedNormally || mine.threw -> violations += "seed=$seed#$i Completed인데 ${mine.kind} 미종료·예외"
                            mine.accepted.toString() != res.text -> violations += "seed=$seed#$i 글자 불일치 ${mine.kind}"
                        }
                    }
                }
                runner.awaitIdle()
            }
            if (runner.overlaps.get() > 0) violations += "seed=$seed 동시 실행 ${runner.overlaps.get()}회(최대 ${runner.maxActive.get()})"
        }
        if (violations.isNotEmpty()) fail("${violations.size}건 위반, 앞 10건: ${violations.take(10)}")
        // 좀비가 한도 안에 안 끝나 기다리다 그만둔 경우가 실제로 있었는지(퍼즈가 그 경로를 지났는지).
        assertTrue(busy > 0, "RUNNER_BUSY 경로 미도달")
    }

    @Test
    fun `취소를 무시한 앞 생성이 끝나기 전에는 다음 생성을 시작하지 않는다`() {
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val calls = AtomicInteger()
        val runner = object : LlmRunner {
            override fun generate(request: LlmRequest, sink: (String) -> Boolean) {
                maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    if (calls.incrementAndGet() == 1) {
                        // 인터럽트·cancel을 무시하고 release까지 버틴다.
                        while (release.count > 0) runCatching { release.await() }
                    } else {
                        Outputs.TRAIN.chunked(4).forEach { if (!sink(it)) return }
                    }
                } finally {
                    active.decrementAndGet()
                }
            }

            override fun cancel() {}
        }
        GuardedGenerator(runner, WatchdogLimits(timeoutMs = 50)).use { g ->
            assertEquals(AbortCause.TIMEOUT, (g.generate(req) as Generation.Aborted).cause)
            assertEquals(AbortCause.RUNNER_BUSY, (g.generate(req) as Generation.Aborted).cause)
            assertEquals(1, calls.get(), "바쁠 때는 러너를 부르지 않는다")
            release.countDown()
            val ok = assertIs<Generation.Completed>(g.generate(req))
            assertEquals(Outputs.TRAIN, ok.text)
        }
        assertEquals(1, maxActive.get())
    }

    @Test
    fun `파서는 RUNNER_BUSY를 되묻기로 끝낸다`() {
        val release = CountDownLatch(1)
        val runner = object : LlmRunner {
            override fun generate(request: LlmRequest, sink: (String) -> Boolean) {
                while (release.count > 0) runCatching { release.await() }
            }

            override fun cancel() {}
        }
        val p = SlotParser(GuardedGenerator(runner, WatchdogLimits(timeoutMs = 30)))
        assertIs<ParseResult.AskAgain>(p.parse("서울 부산"))
        assertEquals(listOf("watchdog:timeout", "watchdog:runner_busy"), p.attempts.map { it.outcome })
        release.countDown()
    }

    private companion object {
        /** 스레드 시작·스케줄링 여유. CI 러너가 느려도 넘지 않을 만큼. */
        const val SLACK_MS = 400L
    }
}
