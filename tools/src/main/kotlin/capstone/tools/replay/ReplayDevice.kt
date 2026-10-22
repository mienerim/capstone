package capstone.tools.replay

import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.exec.RawActuator
import capstone.gate.exec.SnapshotSource
import capstone.snapshot.RecordedSnapshot
import capstone.snapshot.ReplayFormatException
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 재생기가 받은 raw 호출 한 건(D34 '나간 액션 로그'). M7 판정은 이 로그로 한다:
 * gate를 지나 실제 장치까지 닿은 호출만 여기에 남는다. 입력 텍스트는 길이만 남긴다(D36).
 */
data class ReplayLogEntry(
    val step: Int,
    val ts: Long,
    val snap: String,
    val action: String,
    val key: String?,
    val label: String?,
    val ok: Boolean,
    val to: String?,
    val notes: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("step", step); put("ts", ts); put("snap", snap); put("action", action)
        key?.let { put("key", it) }
        label?.let { put("label", it) }
        put("ok", ok)
        to?.let { put("to", it) }
        if (notes.isNotEmpty()) put("notes", kotlinx.serialization.json.JsonArray(notes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}

data class ReplayReport(
    val scenario: String,
    val steps: Int,
    val reached: List<String>,
    val missingMustReach: List<String>,
    val neverReachHit: List<String>,
    /** never_click 선택자에 맞는 노드에 raw 클릭이 닿은 기록. 1건이라도 있으면 M7 실패. */
    val violations: List<ReplayLogEntry>,
) {
    val passed: Boolean get() = missingMustReach.isEmpty() && neverReachHit.isEmpty() && violations.isEmpty()
}

/**
 * 저장 트리 재생 장치(D34). service 대신 [RawActuator]·[SnapshotSource]를 구현해
 * GatedExecutor 아래에 그대로 끼운다. 실행 경로가 실기기와 같아서 gate를 우회할 수 없다.
 *
 * 화면 전이는 시나리오의 전이 테이블로만 일어난다. 테이블에 없는 클릭은 화면을 바꾸지 않고 성공으로 친다
 * (눌러도 반응 없는 노드). 시간은 액션마다 [stepMs]씩 흐르는 가상 시계다.
 *
 * @param oracleScreens true면 capture가 정답 라벨(screen_label)을 [Snapshot.screen]에 넣는다.
 *   화면 분류기(④) 없이 gate·실행 경로만 시험할 때 쓴다(DECISIONS-IMPL I-21).
 */
class ReplayDevice(
    private val scenario: Scenario,
    private val pool: SnapshotPool,
    private val oracleScreens: Boolean = false,
    private val stepMs: Long = 500,
) : RawActuator, SnapshotSource {

    private val random = Random(scenario.seed)
    private val faultBudget = scenario.faults.associateWith { it.times }.toMutableMap()
    private var current: RecordedSnapshot = pool[scenario.start]
    private var clock = 0L
    private var step = 0
    private var pendingCapture: ((Snapshot) -> Snapshot)? = null
    private var pendingLeft = 0

    private val _log = mutableListOf<ReplayLogEntry>()
    private val _reached = linkedSetOf(scenario.start)

    val log: List<ReplayLogEntry> get() = _log.toList()
    val currentSnapId: String get() = current.snapId

    init {
        val missing = scenario.missingSnapshots(pool)
        if (missing.isNotEmpty()) throw ReplayFormatException("${scenario.name}: 풀에 없는 스냅샷 $missing")
    }

    // ---- SnapshotSource ----

    override fun capture(): Snapshot {
        val base = view(current)
        val fault = pendingCapture?.takeIf { pendingLeft > 0 } ?: return base
        if (--pendingLeft == 0) pendingCapture = null
        return fault(base)
    }

    private fun view(rec: RecordedSnapshot): Snapshot =
        rec.snapshot.copy(timestampMs = clock, screen = if (oracleScreens) rec.meta.screenLabel else null)

    // ---- RawActuator ----

    override fun click(ref: NodeRef): Boolean = act(ReplayAction.CLICK, "click", ref)
    override fun tapCenter(ref: NodeRef): Boolean = act(ReplayAction.CLICK, "tap_center", ref)
    override fun setText(ref: NodeRef, text: String): Boolean = act(ReplayAction.SET_TEXT, "set_text", ref, text)
    override fun scroll(ref: NodeRef, forward: Boolean): Boolean = act(ReplayAction.SCROLL, if (forward) "scroll_fwd" else "scroll_back", ref)
    override fun imeEnter(ref: NodeRef): Boolean = act(ReplayAction.IME_ENTER, "ime_enter", ref)
    override fun back(): Boolean = act(ReplayAction.BACK, "back", null)

    fun report(): ReplayReport = ReplayReport(
        scenario = scenario.name,
        steps = step,
        reached = _reached.toList(),
        missingMustReach = scenario.expect.mustReach.filter { it !in _reached },
        neverReachHit = scenario.expect.neverReach.filter { it in _reached },
        violations = _log.filter { "violation:never_click" in it.notes },
    )

    private fun act(action: ReplayAction, wire: String, ref: NodeRef?, text: String? = null): Boolean {
        step++
        clock += stepMs
        val from = current
        val notes = mutableListOf<String>()
        // 일시 결함은 '액션 직후'에만 건다. 다음 액션이 오면 남은 것은 버린다.
        pendingCapture = null
        pendingLeft = 0

        var window: UiWindow? = null
        var key: String? = null
        if (ref != null) {
            window = from.snapshot.window(ref.windowId)
            key = ref.key
            if (window == null || window.treeHash != ref.treeHash || ref.key !in window.nodes) {
                return logged(from, wire, ref.key, null, false, null, notes + "stale_ref")
            }
        }
        val label = if (window != null && key != null) labelOf(window, key) else null

        // gate를 지나 장치까지 닿았다는 사실 자체가 위반이므로, 클릭이 실패해도 기록한다.
        if (action == ReplayAction.CLICK && window != null && key != null &&
            scenario.expect.neverClick.any { it.matches(window, key) }
        ) {
            notes += "violation:never_click"
        }

        if (action == ReplayAction.CLICK && consume<Fault.ClickFails>(from.snapId)) {
            return logged(from, wire, key, label, false, null, notes + "fault:click_fails")
        }

        if (action == ReplayAction.SET_TEXT) {
            val node = window!!.nodes.getValue(key!!)
            if (!node.editable || !node.enabled) return logged(from, wire, key, label, false, null, notes + "not_editable")
            current = from.withText(window.id, key, text.orEmpty())
            notes += "text_len:${text.orEmpty().length}"
        }

        val t = scenario.transitions.firstOrNull { tr ->
            tr.from == from.snapId && tr.action == action &&
                (tr.selector == null || (window != null && key != null && tr.selector.matches(window, key)))
        }
        if (t != null) {
            current = pool[t.to]
            _reached += t.to
        } else if (action != ReplayAction.SET_TEXT) {
            notes += "no_transition"
        }

        armCaptureFaults(from, notes)
        return logged(from, wire, key, label, true, t?.to, notes)
    }

    private fun armCaptureFaults(from: RecordedSnapshot, notes: MutableList<String>) {
        val f = scenario.faults.filterIsInstance<Fault.CaptureFault>().firstOrNull { tryConsume(it, from.snapId) } ?: return
        notes += if (f is Fault.ScreenshotError) "fault:${f.kind}:${f.code}" else "fault:${f.kind}"
        pendingLeft = f.captures
        pendingCapture = when (f) {
            is Fault.EmptyAfterAction -> { s -> s.mapForeground { it.copy(root = null) } }
            is Fault.NodeDrop -> {
                // 난수는 결함을 걸 때 한 번만 뽑는다: 같은 결함 동안 매 capture가 같은 잘린 트리를 보인다.
                val drop = mutableMapOf<Int, UiWindow>()
                ({ s -> s.mapForeground { w -> drop.getOrPut(w.id) { w.dropNodes(f.fraction, random) } } })
            }
            is Fault.Skeleton -> { s -> s.mapForeground { w -> w.copy(root = w.root?.map(::skeleton)) } }
            is Fault.PackageChange -> { s -> s.mapForeground { it.copy(packageName = f.pkg) } }
            is Fault.SignatureDamage -> { s -> s.mapForeground { w -> w.copy(root = w.root?.map { damage(it, f.text) }) } }
            is Fault.ScreenshotError -> { s -> s.copy(screenshotError = f.code) }
            is Fault.StaleCapture -> { _ -> view(from) }
        }
    }

    private inline fun <reified F : Fault> consume(snap: String): Boolean =
        scenario.faults.filterIsInstance<F>().any { tryConsume(it, snap) }

    private fun tryConsume(f: Fault, snap: String): Boolean {
        val left = faultBudget.getValue(f)
        if (left <= 0 || (f.onSnap != null && f.onSnap != snap)) return false
        // 확률 1이면 난수를 소비하지 않는다: 결정론적 결함만 쓰는 시나리오는 seed와 무관하게 같은 결과.
        if (f.probability < 1.0 && random.nextDouble() >= f.probability) return false
        faultBudget[f] = left - 1
        return true
    }

    private fun logged(from: RecordedSnapshot, action: String, key: String?, label: String?, ok: Boolean, to: String?, notes: List<String>): Boolean {
        _log += ReplayLogEntry(step, clock, from.snapId, action, key, label, ok, to, notes)
        return ok
    }

    private fun labelOf(w: UiWindow, key: String): String? {
        val n = w.nodes.getValue(key)
        fun lbl(x: UiNode) = x.text?.takeIf { it.isNotBlank() } ?: x.contentDescription?.takeIf { it.isNotBlank() }
        return lbl(n) ?: w.descendantsOf(key).firstNotNullOfOrNull { lbl(w.nodes.getValue(it)) } ?: n.viewId
    }
}

/** key(인덱스 경로) 노드의 text를 바꾼 사본. 트리가 바뀌므로 treeHash도 바뀐다. */
internal fun RecordedSnapshot.withText(windowId: Int, key: String, text: String): RecordedSnapshot {
    val path = key.split('/').drop(1).map { it.toInt() }
    fun replace(n: UiNode, rest: List<Int>): UiNode =
        if (rest.isEmpty()) n.copy(text = text)
        else n.copy(children = n.children.mapIndexed { i, c -> if (i == rest[0]) replace(c, rest.drop(1)) else c })
    return copy(snapshot = snapshot.copy(windows = snapshot.windows.map { w ->
        if (w.id == windowId && w.root != null) w.copy(root = replace(w.root!!, path)) else w
    }))
}

private fun Snapshot.mapForeground(f: (UiWindow) -> UiWindow): Snapshot {
    val fg = foregroundAppWindow ?: return this
    return copy(windows = windows.map { if (it.id == fg.id) f(it) else it })
}

/** 노드와 모든 자손에 [f]를 적용한 사본(자식은 f 적용 전 원본에서 가져온다). */
internal fun UiNode.map(f: (UiNode) -> UiNode): UiNode = f(this).copy(children = children.map { it.map(f) })

/** 루트 외 노드를 각각 [fraction] 확률로 서브트리째 지운다. 루트가 남아야 '노드 0'과 구분된다. */
internal fun UiWindow.dropNodes(fraction: Double, random: Random): UiWindow {
    fun prune(n: UiNode): UiNode = n.copy(children = n.children.filter { random.nextDouble() >= fraction }.map(::prune))
    return copy(root = root?.let(::prune))
}

private fun skeleton(n: UiNode): UiNode = n.copy(text = null, contentDescription = null, hint = null, stateDescription = null, paneTitle = null)

/** viewId 이름을 난독화 이름(x + 해시)으로 바꾼다. 같은 이름은 같은 결과라 트리 안 관계는 유지된다. */
private fun damage(n: UiNode, text: String?): UiNode = n.copy(
    viewId = n.viewId?.let { id ->
        val name = id.substringAfter(":id/")
        id.removeSuffix(name) + "x" + (name.hashCode() and 0xffff).toString(16)
    },
    text = if (text != null && n.text == text) null else n.text,
)
