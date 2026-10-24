package capstone.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D70(D58 v1.1): logcat에 발화·온디바이스 LLM 입출력·화면 글자를 남기지 않는다.
 * 발화와 LLM 입출력을 다루는 모듈(llm·app-core·app)은 로그 호출 자체를 두지 않는 것으로 지킨다.
 * service는 경로·snapId·집계만 찍으므로(D58) 여기서 보지 않는다.
 */
class LogPolicyTest {
    private val repo = File(System.getProperty("capstone.fixtures")).parentFile.parentFile
    private val logCall = Regex("""\b(Log\.[vdiwe]|Log\.wtf|println|System\.(out|err))\s*[(.]""")

    @Test
    fun `발화·LLM 입출력을 다루는 모듈에 로그 호출이 없다`() {
        val dirs = listOf("llm", "app-core", "app").map { File(repo, it) }
        val sources = dirs.flatMap { d ->
            d.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }.toList()
        }
        assertTrue(sources.size > 5, "소스를 못 찾음: $dirs")
        listOf("Log.d(TAG, text)", "println(out)", "System.err.println(x)").forEach { assertTrue(logCall.containsMatchIn(it), it) }
        val hits = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (logCall.containsMatchIn(line)) "${f.relativeTo(repo).invariantSeparatorsPath}:${i + 1}" else null
            }
        }
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }
}
