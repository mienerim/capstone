package capstone.gate

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.Flushable
import java.io.OutputStreamWriter
import java.io.RandomAccessFile

/**
 * 증명 로그 한 줄 = 결정 하나(D36 4.6). 입력 텍스트(SetText 값)는 담지 않는다.
 * M7 = 확정 노드 ALLOW 0건 + BLOCK 후 실행 0건을 이 로그로 보인다.
 */
data class DecisionEntry(
    val v: Int = 1,
    val ts: Long,
    val seq: Long,
    val phase: String,
    val pkg: String?,
    val windows: List<String>,
    val screen: String?,
    val decision: String,
    val reason: BlockReason?,
    val signals: List<String>,
    val action: String,
    val target: String?,
    val treeHash: String?,
    val nodeCount: Int,
    val interactiveCount: Int,
) {
    fun toJsonLine(): String = buildString {
        append('{')
        field("v", v); field("ts", ts); field("seq", seq); field("phase", phase); field("pkg", pkg)
        append("\"windows\":").append(windows.joinToString(",", "[", "]") { quote(it) }).append(',')
        field("screen", screen); field("decision", decision); field("reason", reason?.name)
        append("\"signals\":").append(signals.joinToString(",", "[", "]") { quote(it) }).append(',')
        field("action", action); field("target", target); field("treeHash", treeHash)
        field("nodeCount", nodeCount)
        append("\"interactiveCount\":").append(interactiveCount)
        append('}')
    }

    private fun StringBuilder.field(name: String, value: Any?) {
        append('"').append(name).append("\":")
        when (value) {
            null -> append("null")
            is Number -> append(value)
            else -> append(quote(value.toString()))
        }
        append(',')
    }

    private companion object {
        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}

fun interface DecisionSink {
    fun record(entry: DecisionEntry)

    companion object {
        val NONE: DecisionSink = DecisionSink { }
    }
}

/**
 * JSONL로 쓰는 기본 구현. 파일 회전은 호출자(service/app)가 맡는다.
 *
 * - write-ahead: 한 줄(개행 포함)을 한 번에 쓰고 [Flushable]이면 바로 flush한다. 그래서 쓰기 오류가
 *   [record] 안에서 드러나 gate가 막고(Gate.guarded), raw 실행 전에 그 판정이 디스크에 있다.
 *   FileWriter 버퍼에만 두면 실행 뒤 프로세스가 죽을 때 증명이 사라졌다(ProofLogFuzzTest·I-98).
 * - 쓰기가 한 번 실패하면 줄 중간에서 끊겼을 수 있어 뒤 줄이 거기 붙는다. 그래서 그 뒤 기록은 모두 거부한다
 *   → 이 실행의 나머지 판정은 전부 차단(fail-closed). 다음 실행은 [appendingTo]가 새 줄에서 시작한다.
 *
 * @param atLineStart false면 앞 내용이 줄 중간에서 끝났다는 뜻 — 첫 기록 앞에 개행을 넣는다.
 */
class JsonlDecisionSink(private val out: Appendable, atLineStart: Boolean = true) : DecisionSink, Closeable {
    private var needsNewline = !atLineStart
    private var broken: Throwable? = null

    @Synchronized
    override fun record(entry: DecisionEntry) {
        broken?.let { throw IllegalStateException("proof log broken", it) }
        val line = (if (needsNewline) "\n" else "") + entry.toJsonLine() + "\n"
        try {
            out.append(line)
            (out as? Flushable)?.flush()
            needsNewline = false
        } catch (t: Throwable) {
            broken = t
            throw t
        }
    }

    override fun close() {
        (out as? Closeable)?.close()
    }

    companion object {
        /** 기기 안 증명 로그 파일에 이어 쓴다. 지난 실행이 줄 중간에서 끝났으면(쓰기 실패·강제 종료) 새 줄에서 시작한다. */
        fun appendingTo(file: File): JsonlDecisionSink {
            val midLine = file.length() > 0 && RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() } != '\n'.code
            return JsonlDecisionSink(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), atLineStart = !midLine)
        }
    }
}

/** 테스트·재생기용 메모리 수집기. */
class RecordingDecisionSink : DecisionSink {
    private val _entries = mutableListOf<DecisionEntry>()
    val entries: List<DecisionEntry> get() = synchronized(_entries) { _entries.toList() }

    override fun record(entry: DecisionEntry) {
        synchronized(_entries) { _entries += entry }
    }
}
