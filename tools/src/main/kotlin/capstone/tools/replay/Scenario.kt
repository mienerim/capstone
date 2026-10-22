package capstone.tools.replay

import capstone.gate.UiNode
import capstone.gate.UiWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 노드 선택자. 지정한 필드가 모두 맞아야 한다(AND). 대상 노드 또는 그 서브트리 중 하나가 맞으면 일치로 본다:
 * 클릭 가능한 컨테이너 안에 '예매하기' 텍스트가 따로 있는 구조(G5)를 같은 선택자로 잡기 위해서다.
 */
data class Selector(
    val text: String? = null,
    val textContains: String? = null,
    val desc: String? = null,
    val id: String? = null,
) {
    init {
        require(text != null || textContains != null || desc != null || id != null) { "빈 선택자" }
    }

    fun matchesNode(n: UiNode): Boolean =
        (text == null || n.text == text) &&
            (textContains == null || n.text?.contains(textContains) == true || n.contentDescription?.contains(textContains) == true) &&
            (desc == null || n.contentDescription == desc) &&
            (id == null || n.viewId == id || n.viewId?.endsWith(":id/$id") == true)

    /** key 노드 자신 또는 그 자손 중 하나라도 맞는가. */
    fun matches(window: UiWindow, key: String): Boolean {
        val self = window.nodes[key] ?: return false
        return matchesNode(self) || window.descendantsOf(key).any { matchesNode(window.nodes.getValue(it)) }
    }

    override fun toString(): String =
        listOfNotNull(text?.let { "text=$it" }, textContains?.let { "text~$it" }, desc?.let { "desc=$it" }, id?.let { "id=$it" })
            .joinToString(",", "[", "]")

    companion object {
        fun parse(o: JsonObject): Selector = Selector(o.str("text"), o.str("text_contains"), o.str("desc"), o.str("id"))
    }
}

/** 재생 액션 종류. tap_center는 click 전이를 공유한다(같은 노드를 누른 것). */
enum class ReplayAction(val wire: String) {
    CLICK("click"), SET_TEXT("set_text"), SCROLL("scroll"), IME_ENTER("ime_enter"), BACK("back");

    companion object {
        fun parse(s: String): ReplayAction =
            entries.firstOrNull { it.wire == s } ?: throw ReplayFormatException("알 수 없는 액션 '$s'")
    }
}

/** 전이 테이블 한 줄: (from 스냅샷, 액션, 선택자) → to 스냅샷. back은 선택자가 없다. */
data class Transition(val from: String, val action: ReplayAction, val selector: Selector?, val to: String)

/**
 * 결정론적 결함(D34 '전이 + 결함 주입'). [probability] < 1이면 시나리오 seed의 난수로 정한다.
 * ④에서 7종으로 늘린다. 지금은 재생기 자체를 검증할 최소 3종.
 */
sealed interface Fault {
    val onSnap: String?
    val times: Int
    val probability: Double

    /** raw click(중심 탭 포함)이 false를 돌려준다: 앱이 클릭을 안 받는 경우. */
    data class ClickFails(override val onSnap: String? = null, override val times: Int = 1, override val probability: Double = 1.0) : Fault

    /** 액션 직후 capture가 루트 없는 창을 준다: 로딩 중 빈 트리(T9·D35). */
    data class EmptyAfterAction(override val onSnap: String? = null, override val times: Int = 1, override val probability: Double = 1.0) : Fault

    /** 액션 직후 capture에 스크린샷 오류 코드를 붙인다(D35, 6=SECURE_WINDOW). */
    data class ScreenshotError(val code: Int, override val onSnap: String? = null, override val times: Int = 1, override val probability: Double = 1.0) : Fault
}

data class Expectations(
    /** 재생 장치에 raw로 닿으면 안 되는 노드(M7). 닿으면 위반으로 기록. */
    val neverClick: List<Selector> = emptyList(),
    /** 실행 중 한 번은 도달해야 하는 스냅샷. */
    val mustReach: List<String> = emptyList(),
    /** 도달하면 실패인 스냅샷(S4 결제 화면 등). */
    val neverReach: List<String> = emptyList(),
)

data class Scenario(
    val name: String,
    val snapshotFiles: List<String>,
    val start: String,
    val seed: Long,
    val transitions: List<Transition>,
    val faults: List<Fault>,
    val expect: Expectations,
) {
    companion object {
        private val json = Json

        fun read(file: File): Scenario = parse(file.readText(), file.name)

        fun parse(text: String, where: String = "scenario"): Scenario {
            val o = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ReplayFormatException("$where: JSON 객체가 아님 (${e.message?.take(80)})")
            }
            val v = o.int("v")
            if (v != SnapshotJsonl.VERSION) throw ReplayFormatException("$where: 지원하지 않는 시나리오 버전 v=$v")
            val transitions = o.arr("transitions").orEmpty().mapIndexed { i, t ->
                val to = t as? JsonObject ?: throw ReplayFormatException("$where: transitions[$i] 객체 아님")
                val on = to.obj("on") ?: throw ReplayFormatException("$where: transitions[$i].on 없음")
                val action = ReplayAction.parse(on.str("action") ?: throw ReplayFormatException("$where: transitions[$i].on.action 없음"))
                val sel = on.obj("selector")?.let(Selector::parse)
                if (sel == null && action != ReplayAction.BACK) throw ReplayFormatException("$where: transitions[$i] 선택자 없음")
                Transition(
                    from = to.str("from") ?: throw ReplayFormatException("$where: transitions[$i].from 없음"),
                    action = action,
                    selector = sel,
                    to = to.str("to") ?: throw ReplayFormatException("$where: transitions[$i].to 없음"),
                )
            }
            val faults = o.arr("faults").orEmpty().mapIndexed { i, f ->
                val fo = f as? JsonObject ?: throw ReplayFormatException("$where: faults[$i] 객체 아님")
                val snap = fo.str("on_snap")
                val times = fo.int("times") ?: 1
                val p = (fo["p"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                if (p !in 0.0..1.0) throw ReplayFormatException("$where: faults[$i].p 범위 밖")
                when (val kind = fo.str("kind")) {
                    "click_fails" -> Fault.ClickFails(snap, times, p)
                    "empty_after_action" -> Fault.EmptyAfterAction(snap, times, p)
                    "screenshot_error" -> Fault.ScreenshotError(fo.int("code") ?: 6, snap, times, p)
                    else -> throw ReplayFormatException("$where: 알 수 없는 결함 '$kind'")
                }
            }
            val e = o.obj("expect")
            fun sels(key: String) = e?.arr(key).orEmpty().map { Selector.parse(it as? JsonObject ?: throw ReplayFormatException("$where: expect.$key 원소 객체 아님")) }
            fun ids(key: String) = e?.arr(key).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: throw ReplayFormatException("$where: expect.$key 원소 문자열 아님") }
            return Scenario(
                name = o.str("name") ?: where,
                snapshotFiles = o.arr("snapshots").orEmpty().map { (it as JsonPrimitive).content },
                start = o.str("start") ?: throw ReplayFormatException("$where: start 없음"),
                seed = o.long("seed") ?: 0L,
                transitions = transitions,
                faults = faults,
                expect = Expectations(sels("never_click"), ids("must_reach"), ids("never_reach")),
            )
        }

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }

    /** 시나리오가 참조하는 스냅샷 id가 모두 풀에 있는지. 없는 id 목록을 돌려준다. */
    fun missingSnapshots(pool: SnapshotPool): List<String> =
        (listOf(start) + transitions.flatMap { listOf(it.from, it.to) } + expect.mustReach + expect.neverReach + faults.mapNotNull { it.onSnap })
            .distinct().filter { it !in pool }
}

/** snap_id → 스냅샷. 같은 id가 두 번 나오면 오류(전이 대상이 모호해짐). */
class SnapshotPool(recs: List<RecordedSnapshot>) {
    private val byId: Map<String, RecordedSnapshot> = LinkedHashMap<String, RecordedSnapshot>().also { m ->
        for (r in recs) if (m.put(r.snapId, r) != null) throw ReplayFormatException("snap_id 중복: ${r.snapId}")
    }

    val ids: Set<String> get() = byId.keys
    operator fun get(id: String): RecordedSnapshot = byId[id] ?: throw ReplayFormatException("스냅샷 없음: $id")
    operator fun contains(id: String): Boolean = id in byId

    companion object {
        fun load(files: List<File>): SnapshotPool = SnapshotPool(files.flatMap { SnapshotJsonl.read(it) })

        /** 시나리오 파일 위치 기준으로 snapshots 경로를 푼다. */
        fun forScenario(scenarioFile: File, scenario: Scenario): SnapshotPool =
            load(scenario.snapshotFiles.map { File(scenarioFile.parentFile, it) })
    }
}
