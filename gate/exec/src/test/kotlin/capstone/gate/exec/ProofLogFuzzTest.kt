package capstone.gate.exec

import capstone.gate.ActionRequest
import capstone.gate.Bounds
import capstone.gate.DecisionEntry
import capstone.gate.DecisionSink
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.JsonlDecisionSink
import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.Writer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 증명 로그(M7) 퍼즈. 기기 안 파일 쓰기는 버퍼를 거치고, 저장 공간 부족·I/O 오류로 중간에 끊길 수 있으며,
 * 앱 프로세스는 언제든 죽는다(버퍼 소실). 같은 파일에 다음 실행이 이어 쓴다(AgentHost).
 *
 * 불변식(오라클은 '디스크'에 남은 글자만 본다):
 *  ① write-ahead: raw 실행 순간, 그 실행을 허가한 check ALLOW 줄이 이미 디스크에 온전한 JSON 한 줄로 있다.
 *  ② gate가 판정을 그대로 돌려준 기록(싱크가 성공한 기록)은 세션이 어떻게 끝나든 디스크에서 온전한 줄로 읽힌다
 *     — 앞 기록의 실패가 뒤 줄을 붙여 깨뜨리지 않는다.
 */
class ProofLogFuzzTest {
    private val home = GatePolicy.KORAIL_PLUS
    private val policy = GatePolicy(setOf(home), ownPackage = "capstone.app")

    private fun text(t: String, y: Int) = UiNode(className = "android.widget.TextView", text = t, bounds = Bounds(0, y, 1000, y + 80))

    private fun app(version: Int): Snapshot {
        val rows = (0 until 16).map { text("KTX ${100 + it + version} 09:${10 + it}", 200 + it * 70) }
        val search = UiNode(className = "android.widget.Button", text = "조회하기", clickable = true, bounds = Bounds(300, 2000, 800, 2150))
        val root = UiNode(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400), children = rows + search)
        return Snapshot(0, listOf(UiWindow(1, WindowType.APPLICATION, home, active = true, focused = true, root = root)), screen = "S")
    }

    /**
     * 기기 파일 모사: FileWriter처럼 8KB 버퍼에 쌓았다가 flush·버퍼 넘침 때 디스크로 간다.
     * 쓰기·flush마다 [faultRate] 확률로 실패하고, 실패는 앞부분만 쓰고 끊길 수 있다.
     */
    private class FlakyFile(private val r: Random, private val faultRate: Double) {
        val disk = StringBuilder()
        var faults = 0

        fun open(): Writer = object : Writer() {
            private val buf = StringBuilder()

            override fun write(cbuf: CharArray, off: Int, len: Int) {
                if (r.nextDouble() < faultRate) {
                    faults++
                    if (r.nextBoolean()) buf.appendRange(cbuf, off, off + r.nextInt(len + 1))
                    throw IOException("ENOSPC")
                }
                buf.appendRange(cbuf, off, off + len)
                if (buf.length >= 8192) flush()
            }

            override fun flush() {
                if (buf.isEmpty()) return
                if (r.nextDouble() < faultRate) {
                    faults++
                    val k = r.nextInt(buf.length + 1)
                    disk.append(buf, 0, k)
                    buf.delete(0, k)
                    throw IOException("EIO")
                }
                disk.append(buf)
                buf.setLength(0)
            }

            override fun close() = flush()
        }

        /** 프로세스가 죽으면 버퍼는 사라지고 디스크만 남는다. */
        val endsMidLine: Boolean get() = disk.isNotEmpty() && disk.last() != '\n'
    }

    /** (ts, seq) → 디스크에서 온전히 읽힌 줄. */
    private fun parsedLines(disk: CharSequence): Map<Pair<Long, Long>, Map<String, Any?>> {
        val out = HashMap<Pair<Long, Long>, Map<String, Any?>>()
        val whole = disk.toString()
        val complete = whole.substring(0, whole.lastIndexOf('\n') + 1)
        for (line in complete.split('\n')) {
            val o = MiniJson.parseObject(line) ?: continue
            val ts = (o["ts"] as? Long) ?: continue
            val seq = (o["seq"] as? Long) ?: continue
            out[ts to seq] = o
        }
        return out
    }

    private fun matches(o: Map<String, Any?>?, e: DecisionEntry) =
        o != null && o["phase"] == e.phase && o["decision"] == e.decision && o["action"] == e.action

    @Test
    fun `증명 로그는 실행 전에 디스크에 있고, 실패한 기록이 뒤 줄을 깨뜨리지 않는다`() {
        val notDurable = mutableListOf<String>()
        val corrupted = mutableListOf<String>()
        var raws = 0
        var faults = 0
        for (seed in 330_000 until 333_000) {
            val r = Random(seed)
            val file = FlakyFile(r, faultRate = if (r.nextInt(4) == 0) 0.0 else 0.03)
            var clock = 0L
            var screen = app(0)
            fun capture() = screen.copy(timestampMs = ++clock)
            val succeeded = mutableListOf<DecisionEntry>()
            var lastAllowCheck: DecisionEntry? = null

            repeat(r.nextInt(1, 4)) { session ->
                val writer = file.open()
                val jsonl = JsonlDecisionSink(writer, atLineStart = !file.endsMidLine)
                // 싱크가 성공한 기록만 gate가 판정을 그대로 돌려준다(Gate.guarded).
                val sink = DecisionSink { e ->
                    jsonl.record(e)
                    succeeded += e
                    if (e.phase == "check" && e.decision == "ALLOW") lastAllowCheck = e
                }
                val act = object : RawActuator {
                    private fun raw(kind: String, change: Boolean, ok: Boolean): Boolean {
                        raws++
                        val proof = lastAllowCheck
                        val line = proof?.let { parsedLines(file.disk)[it.ts to it.seq] }
                        if (proof == null || proof.action != kind || !matches(line, proof)) {
                            notDurable += "seed=$seed session=$session $kind"
                        }
                        if (change) screen = app(r.nextInt(1, 1000))
                        return ok
                    }
                    override fun click(ref: NodeRef): Boolean = when (r.nextInt(10)) {
                        in 0..6 -> raw("click", change = true, ok = true)
                        in 7..8 -> raw("click", change = false, ok = false) // 폴백 탭 경로
                        else -> raw("click", change = false, ok = true)
                    }
                    override fun tapCenter(ref: NodeRef) = raw("tap_center", change = r.nextBoolean(), ok = true)
                    override fun setText(ref: NodeRef, text: String) = raw("set_text", false, true)
                    override fun scroll(ref: NodeRef, forward: Boolean) = raw("scroll", false, true)
                    override fun imeEnter(ref: NodeRef) = raw("ime_enter", false, true)
                    override fun back() = raw("back", false, true)
                }
                val exec = GatedExecutor(Gate(policy, sink), act) { capture() }
                repeat(r.nextInt(4, 13)) {
                    val before = capture()
                    val w = before.foregroundAppWindow!!
                    val k = w.nodes.entries.first { it.value.text == "조회하기" }.key
                    lastAllowCheck = null
                    runCatching { exec.execute(before, ActionRequest.Click(NodeRef.of(w, k))) }
                        .onFailure { fail("execute 예외 seed=$seed: $it") }
                }
                // 세션 끝: 절반은 정상 종료(close), 절반은 프로세스 종료(버퍼 소실).
                if (r.nextBoolean()) runCatching { writer.close() }
            }

            val lines = parsedLines(file.disk)
            val lost = succeeded.filter { !matches(lines[it.ts to it.seq], it) }
            if (lost.isNotEmpty()) corrupted += "seed=$seed ${lost.size}건 faults=${file.faults}"
            faults += file.faults
        }
        if (notDurable.isNotEmpty() || corrupted.isNotEmpty()) {
            fail(
                "실행 전 디스크에 증명 없음 ${notDurable.size}건 ${notDurable.take(3)} / " +
                    "기록 성공인데 디스크에서 못 읽음 ${corrupted.size}회 ${corrupted.take(3)}",
            )
        }
        assertTrue(raws > 15_000, "실행이 너무 적음(과잉 차단?): $raws")
        assertTrue(faults > 1_000, "쓰기 실패 사례가 너무 적음: $faults")
    }

    @Test
    fun `줄 끝 개행 쓰기가 실패하면 그 뒤 기록은 거부되어 실행하지 않는다`() {
        val disk = StringBuilder()
        var failNext = false
        val out = object : Appendable {
            override fun append(csq: CharSequence?): Appendable = apply {
                val s = csq.toString()
                // 줄 끝 개행이 디스크에 못 간 상황(개행을 따로 쓰든 줄과 함께 쓰든).
                if (failNext && s.endsWith('\n')) {
                    failNext = false
                    disk.append(s.dropLast(1))
                    throw IOException("ENOSPC")
                }
                disk.append(s)
            }
            override fun append(csq: CharSequence?, start: Int, end: Int) = append(csq?.subSequence(start, end))
            override fun append(c: Char) = append(c.toString())
        }
        var screen = app(0)
        val raw = mutableListOf<String>()
        val act = object : RawActuator {
            override fun click(ref: NodeRef) = true.also { raw += "click"; screen = app(raw.size) }
            override fun tapCenter(ref: NodeRef) = true
            override fun setText(ref: NodeRef, text: String) = true
            override fun scroll(ref: NodeRef, forward: Boolean) = true
            override fun imeEnter(ref: NodeRef) = true
            override fun back() = true
        }
        val exec = GatedExecutor(Gate(policy, JsonlDecisionSink(out)), act) { screen }
        fun click() = screen.foregroundAppWindow!!.let { w ->
            exec.execute(screen, ActionRequest.Click(NodeRef.of(w, w.nodes.entries.first { it.value.text == "조회하기" }.key)))
        }
        click()
        failNext = true
        click()
        click()
        assertEquals(listOf("click"), raw)
        val complete = disk.substring(0, disk.lastIndexOf("\n") + 1).split('\n').filter { it.isNotEmpty() }
        assertTrue(complete.all { MiniJson.parseObject(it) != null }, "깨진 줄: $complete")
    }
}

/** 테스트용 엄격 JSON 파서(gate-core는 외부 의존성이 없다). 객체 하나가 아니면 null. */
internal object MiniJson {
    fun parseObject(s: String): Map<String, Any?>? = runCatching {
        val p = P(s)
        val v = p.value()
        p.ws()
        require(p.i == s.length)
        @Suppress("UNCHECKED_CAST")
        v as Map<String, Any?>
    }.getOrNull()

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun expect(c: Char) { ws(); require(i < s.length && s[i] == c); i++ }
        fun value(): Any? {
            ws()
            require(i < s.length)
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                'n' -> lit("null", null)
                't' -> lit("true", true)
                'f' -> lit("false", false)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)); i += w.length; return v }
        fun num(): Long {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            require(i > st)
            return s.substring(st, i).toLong()
        }
        fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                require(i < s.length)
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            '"', '\\', '/' -> b.append(e)
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("escape")
                        }
                    }
                    c < ' ' -> error("control")
                    else -> b.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                if (s[i++] == ']') return out
                require(s[i - 1] == ',')
            }
        }
        fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val k = str()
                require(k !in out)
                expect(':')
                out[k] = value()
                ws()
                if (s[i++] == '}') return out
                require(s[i - 1] == ',')
            }
        }
    }
}
