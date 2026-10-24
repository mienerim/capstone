package capstone.gate

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

/** JSONL로 쓰는 기본 구현. 파일 회전은 호출자(service/app)가 맡는다. */
class JsonlDecisionSink(private val out: Appendable) : DecisionSink {
    override fun record(entry: DecisionEntry) {
        out.append(entry.toJsonLine()).append('\n')
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
