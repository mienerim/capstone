package capstone.tools.replay

import capstone.gate.Bounds
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 스냅샷 메타데이터(D34 포맷 v0 최상위 필드). [screenLabel]은 사람이 붙인 정답 라벨이라
 * 분류기 결과인 [Snapshot.screen]과 구분한다(DECISIONS-IMPL I-20).
 */
data class SnapshotMeta(
    val snapId: String,
    val app: String? = null,
    val appVersion: String? = null,
    val oneUi: String? = null,
    val sdk: Int? = null,
    val captured: String? = null,
    val a11yToolFlag: Boolean? = null,
    val screenLabel: String? = null,
)

data class RecordedSnapshot(val meta: SnapshotMeta, val snapshot: Snapshot) {
    val snapId: String get() = meta.snapId
}

class ReplayFormatException(message: String) : IllegalArgumentException(message)

/**
 * 저장 트리 JSONL v0 코덱(D34). 한 줄 = 한 스냅샷, 창마다 평탄한 노드 목록(id·parent·children).
 * 필드 구성은 android_env proto(Apache-2.0)의 의미를 참고했고 코드는 직접 작성했다(NOTICE).
 *
 * - 읽기는 관대하게: 없는 불리언은 false(enabled·visible은 true), 없는 문자열은 null, 모르는 필드는 무시.
 *   UiNode에 없는 필드(password·checked·actions 등)도 덤프에 있어도 된다.
 * - 구조 오류는 엄격하게: 없는 자식 id·중복 id·순환·루트에서 닿지 않는 노드는 [ReplayFormatException].
 *   트리가 조용히 잘리면 gate 판정 근거가 사라지기 때문이다.
 */
object SnapshotJsonl {
    const val VERSION: Int = 0

    private val json = Json { prettyPrint = false }

    // ---- 읽기 ----

    fun decodeLine(line: String, lineNo: Int = 0): RecordedSnapshot {
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            throw ReplayFormatException("line $lineNo: JSON 객체가 아님 (${e.message?.take(80)})")
        }
        return decode(obj, "line $lineNo")
    }

    fun decode(obj: JsonObject, where: String = "snapshot"): RecordedSnapshot {
        val v = obj.int("v") ?: throw ReplayFormatException("$where: v(포맷 버전) 없음")
        if (v != VERSION) throw ReplayFormatException("$where: 지원하지 않는 포맷 버전 v=$v")
        val snapId = obj.str("snap_id") ?: throw ReplayFormatException("$where: snap_id 없음")
        val at = "$where($snapId)"
        val windows = (obj["windows"] as? JsonArray ?: throw ReplayFormatException("$at: windows 배열 없음"))
            .mapIndexed { i, w -> decodeWindow(w as? JsonObject ?: throw ReplayFormatException("$at: windows[$i] 객체 아님"), "$at.windows[$i]") }
        val ids = windows.map { it.id }
        if (ids.size != ids.toSet().size) throw ReplayFormatException("$at: 창 id 중복 $ids")
        val meta = SnapshotMeta(
            snapId = snapId,
            app = obj.str("app"),
            appVersion = obj.str("app_version"),
            oneUi = obj.str("one_ui"),
            sdk = obj.int("sdk"),
            captured = obj.str("captured"),
            a11yToolFlag = obj.bool("a11y_tool_flag"),
            screenLabel = obj.str("screen_label"),
        )
        val snapshot = Snapshot(
            timestampMs = obj.long("ts") ?: 0L,
            windows = windows,
            screen = null,
            screenshotError = obj.int("screenshot_error"),
        )
        return RecordedSnapshot(meta, snapshot)
    }

    private fun decodeWindow(w: JsonObject, at: String): UiWindow {
        val id = w.int("id") ?: throw ReplayFormatException("$at: id 없음")
        val type = w.str("type")?.let { t -> WindowType.entries.firstOrNull { it.name == t } ?: WindowType.UNKNOWN }
            ?: WindowType.UNKNOWN
        val nodesArr = (w["nodes"] as? JsonArray) ?: JsonArray(emptyList())
        val raw = LinkedHashMap<Int, JsonObject>()
        for ((i, n) in nodesArr.withIndex()) {
            val o = n as? JsonObject ?: throw ReplayFormatException("$at: nodes[$i] 객체 아님")
            val nid = o.int("id") ?: throw ReplayFormatException("$at: nodes[$i].id 없음")
            if (raw.put(nid, o) != null) throw ReplayFormatException("$at: 노드 id 중복 $nid")
        }
        val rootId = w.int("root") ?: raw.keys.firstOrNull() ?: -1
        val root = if (rootId < 0 || raw.isEmpty()) {
            null
        } else {
            if (rootId !in raw) throw ReplayFormatException("$at: root=$rootId 노드 없음")
            buildTree(rootId, raw, at)
        }
        // 창 패키지가 없으면 루트 노드 pkg를 쓴다(android_env는 노드에만 pkg가 있음).
        val pkg = w.str("pkg") ?: raw[rootId]?.str("pkg")
        return UiWindow(
            id = id,
            type = type,
            packageName = pkg,
            active = w.bool("active") ?: false,
            focused = w.bool("focused") ?: false,
            root = root,
        )
    }

    private fun buildTree(rootId: Int, raw: Map<Int, JsonObject>, at: String): UiNode {
        // children이 있으면 그 순서, 없으면 parent 역참조를 목록 순서대로 쓴다.
        val byParent = HashMap<Int, MutableList<Int>>()
        for ((nid, o) in raw) o.int("parent")?.takeIf { it >= 0 }?.let { byParent.getOrPut(it) { mutableListOf() } += nid }
        val visited = HashSet<Int>()

        fun build(nid: Int, depth: Int): UiNode {
            if (!visited.add(nid)) throw ReplayFormatException("$at: 노드 $nid 가 두 번 이상 나옴(순환 또는 공유)")
            if (depth > 512) throw ReplayFormatException("$at: 트리 깊이 512 초과")
            val o = raw.getValue(nid)
            val childIds = (o["children"] as? JsonArray)?.map {
                (it as? JsonPrimitive)?.intOrNull ?: throw ReplayFormatException("$at: 노드 $nid children 원소가 정수 아님")
            } ?: byParent[nid].orEmpty()
            val children = childIds.map { c ->
                if (c !in raw) throw ReplayFormatException("$at: 노드 $nid 의 자식 $c 없음")
                build(c, depth + 1)
            }
            return decodeNode(o, children, "$at.node[$nid]")
        }

        val root = build(rootId, 0)
        val orphan = raw.keys - visited
        if (orphan.isNotEmpty()) throw ReplayFormatException("$at: 루트에서 닿지 않는 노드 ${orphan.take(5)}")
        return root
    }

    private fun decodeNode(o: JsonObject, children: List<UiNode>, at: String): UiNode {
        val b = (o["bounds"] as? JsonArray)?.let { arr ->
            if (arr.size != 4) throw ReplayFormatException("$at: bounds는 [l,t,r,b] 4개")
            val v = arr.map { (it as? JsonPrimitive)?.intOrNull ?: throw ReplayFormatException("$at: bounds 원소가 정수 아님") }
            Bounds(v[0], v[1], v[2], v[3])
        } ?: Bounds.EMPTY
        return UiNode(
            className = o.str("cls"),
            text = o.str("text"),
            hint = o.str("hint"),
            contentDescription = o.str("desc"),
            stateDescription = o.str("state_desc"),
            paneTitle = o.str("pane_title"),
            viewId = o.str("rid"),
            bounds = b,
            clickable = o.bool("clickable") ?: false,
            longClickable = o.bool("long_clickable") ?: false,
            editable = o.bool("editable") ?: false,
            scrollable = o.bool("scrollable") ?: false,
            enabled = o.bool("enabled") ?: true,
            visible = o.bool("visible") ?: true,
            dataSensitive = o.bool("a11y_data_sensitive") ?: false,
            children = children,
        )
    }

    fun read(file: File): List<RecordedSnapshot> = file.useLines { lines -> decodeLines(lines) }

    fun decodeLines(lines: Sequence<String>): List<RecordedSnapshot> =
        lines.withIndex()
            .filter { (_, l) -> l.isNotBlank() && !l.trimStart().startsWith("//") }
            .map { (i, l) -> decodeLine(l, i + 1) }
            .toList()

    // ---- 쓰기 ----

    /** 덤퍼(service)·변형 도구가 쓰는 인코더. 빈 문자열과 기본값 불리언은 생략해 diff를 작게 한다. */
    fun encode(rec: RecordedSnapshot): JsonObject = buildJsonObject {
        val m = rec.meta
        put("v", VERSION)
        put("snap_id", m.snapId)
        m.app?.let { put("app", it) }
        m.appVersion?.let { put("app_version", it) }
        m.oneUi?.let { put("one_ui", it) }
        m.sdk?.let { put("sdk", it) }
        m.captured?.let { put("captured", it) }
        m.a11yToolFlag?.let { put("a11y_tool_flag", it) }
        m.screenLabel?.let { put("screen_label", it) }
        put("ts", rec.snapshot.timestampMs)
        rec.snapshot.screenshotError?.let { put("screenshot_error", it) }
        put("windows", buildJsonArray { rec.snapshot.windows.forEach { add(encodeWindow(it)) } })
    }

    fun encodeLine(rec: RecordedSnapshot): String = json.encodeToString(JsonObject.serializer(), encode(rec))

    fun write(file: File, recs: List<RecordedSnapshot>) {
        file.bufferedWriter().use { w -> recs.forEach { w.write(encodeLine(it)); w.newLine() } }
    }

    private fun encodeWindow(w: UiWindow): JsonObject = buildJsonObject {
        put("id", w.id)
        put("type", w.type.name)
        w.packageName?.let { put("pkg", it) }
        if (w.active) put("active", true)
        if (w.focused) put("focused", true)
        // 노드 id는 nodes 맵(전위 순회) 순번. NodeRef 키(인덱스 경로)와 별개다.
        val idOf = HashMap<String, Int>()
        w.nodes.keys.forEachIndexed { i, k -> idOf[k] = i }
        put("root", if (w.root == null) -1 else 0)
        put("nodes", buildJsonArray {
            for ((k, n) in w.nodes) {
                add(buildJsonObject {
                    put("id", idOf.getValue(k))
                    put("parent", w.parentKey(k)?.let { idOf.getValue(it) } ?: -1)
                    if (n.children.isNotEmpty()) {
                        put("children", buildJsonArray { n.children.indices.forEach { add(JsonPrimitive(idOf.getValue("$k/$it"))) } })
                    }
                    putStr("cls", n.className)
                    putStr("rid", n.viewId)
                    putStr("text", n.text)
                    putStr("desc", n.contentDescription)
                    putStr("hint", n.hint)
                    putStr("state_desc", n.stateDescription)
                    putStr("pane_title", n.paneTitle)
                    if (n.bounds != Bounds.EMPTY) {
                        put("bounds", buildJsonArray { listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom).forEach { add(JsonPrimitive(it)) } })
                    }
                    if (!n.visible) put("visible", false)
                    if (!n.enabled) put("enabled", false)
                    if (n.clickable) put("clickable", true)
                    if (n.longClickable) put("long_clickable", true)
                    if (n.editable) put("editable", true)
                    if (n.scrollable) put("scrollable", true)
                    if (n.dataSensitive) put("a11y_data_sensitive", true)
                })
            }
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStr(key: String, value: String?) {
        if (value != null) put(key, value)
    }
}

// ---- JsonObject 접근 도우미(형식이 틀리면 null → 호출자가 기본값 또는 오류) ----

internal fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
internal fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.int(key: String): Int? = prim(key)?.takeUnless { it.isString }?.intOrNull
internal fun JsonObject.long(key: String): Long? = prim(key)?.takeUnless { it.isString }?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeUnless { it.isString }?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
