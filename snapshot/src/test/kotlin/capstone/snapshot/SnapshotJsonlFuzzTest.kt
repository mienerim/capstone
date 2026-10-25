package capstone.snapshot

import capstone.gate.Bounds
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 저장 트리 JSONL v0 코덱 퍼즈(I-89). 재생기의 M7 판정(never_click)은 디코드한 트리로 하므로,
 * 덤프에 있던 '예매하기' 노드가 디코드에서 조용히 빠지면 실기기에서는 막혔을 클릭이 재생기에서는
 * 위반 0건으로 통과한다. 코덱 계약(SnapshotJsonl KDoc): 구조 오류는 [ReplayFormatException], 조용한 잘림 없음.
 *
 * ① 왕복: 임의 스냅샷 → encodeLine → decodeLine(그리고 파일 쓰기·읽기)이 원본·treeHash와 같다.
 * ② 구조 변형: 정상 줄의 JSON에 구조 변형 1~2개를 넣는다. 결과는 [ReplayFormatException]이거나,
 *    입력 창의 nodes 배열에 있던 노드 객체 수와 디코드된 노드 수가 같고 '예매하기' 표지 노드가 남아 있어야 한다.
 *    그 밖의 예외(StackOverflowError·NoSuchElementException 등)는 크래시로 센다.
 */
class SnapshotJsonlFuzzTest {
    private val labels = listOf(
        "예매하기", "조회하기", "서울", "부산", "KTX 101", "", " ", "확인", "결제", "\"따옴표\"", "역\\슬래시",
        "줄\n바꿈", "탭\t", "\u0000널", "이모지🚄", "결합 ㅇㅖ", "​제로폭", "//주석처럼", "{\"json\":1}",
    )
    private val packages = listOf("com.korail.talk", "capstone.mock", "com.android.systemui", null)

    private fun str(r: Random): String? = if (r.nextInt(3) == 0) null else labels.random(r)

    private fun bounds(r: Random): Bounds = when (r.nextInt(5)) {
        0 -> Bounds.EMPTY
        1 -> Bounds(r.nextInt(-50, 50), r.nextInt(-50, 50), r.nextInt(-50, 50), r.nextInt(-50, 50))
        2 -> Bounds(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1)
        else -> r.nextInt(0, 1000).let { x -> r.nextInt(0, 2000).let { y -> Bounds(x, y, x + r.nextInt(1, 300), y + r.nextInt(1, 200)) } }
    }

    private fun node(r: Random, depth: Int): UiNode = UiNode(
        className = str(r), text = str(r), hint = str(r), contentDescription = str(r),
        stateDescription = str(r), paneTitle = str(r), viewId = str(r), bounds = bounds(r),
        clickable = r.nextBoolean(), longClickable = r.nextInt(5) == 0, editable = r.nextInt(5) == 0,
        scrollable = r.nextInt(5) == 0, enabled = r.nextInt(6) != 0, visible = r.nextInt(6) != 0,
        dataSensitive = r.nextInt(8) == 0,
        children = if (depth >= 5) emptyList() else List(r.nextInt(0, if (depth == 0) 6 else 4)) { node(r, depth + 1) },
    )

    private fun snapshot(r: Random): RecordedSnapshot {
        val windows = List(r.nextInt(0, 4)) { i ->
            UiWindow(
                id = i * 7 + r.nextInt(7), type = WindowType.entries.random(r), packageName = packages.random(r),
                active = r.nextBoolean(), focused = r.nextBoolean(),
                root = if (r.nextInt(6) == 0) null else node(r, 0),
            )
        }
        val meta = SnapshotMeta(
            snapId = "fz_${r.nextInt()}", app = packages.random(r), appVersion = str(r), oneUi = str(r),
            sdk = if (r.nextBoolean()) r.nextInt(30, 38) else null, captured = str(r),
            a11yToolFlag = if (r.nextBoolean()) r.nextBoolean() else null, screenLabel = str(r),
        )
        return RecordedSnapshot(meta, Snapshot(r.nextLong(), windows, screen = null, screenshotError = if (r.nextInt(5) == 0) r.nextInt(0, 10) else null))
    }

    @Test
    fun `임의 스냅샷 인코드-디코드 왕복이 원본과 같다`() {
        val tmp = createTempFile("fuzz", ".jsonl").toFile().apply { deleteOnExit() }
        for (seed in 130_000 until 132_000) {
            val rec = snapshot(Random(seed))
            val back = SnapshotJsonl.decodeLine(SnapshotJsonl.encodeLine(rec))
            assertEquals(rec, back, "seed $seed")
            assertEquals(rec.snapshot.windows.map { it.treeHash }, back.snapshot.windows.map { it.treeHash }, "seed $seed")
            if (seed % 10 == 0) {
                SnapshotJsonl.write(tmp, listOf(rec))
                assertEquals(listOf(rec), SnapshotJsonl.read(tmp), "seed $seed (파일)")
            }
        }
    }

    // ---- 구조 변형 ----

    private val marker = "예매하기"

    /** 표지 노드가 반드시 하나 있는 정상 스냅샷. */
    private fun withMarker(r: Random): RecordedSnapshot {
        val rec = snapshot(r)
        val target = UiNode(text = marker, clickable = true, bounds = Bounds(100, 1800, 980, 1900))
        val windows = rec.snapshot.windows.ifEmpty { listOf(UiWindow(1, WindowType.APPLICATION, "com.korail.talk", true, true, null)) }
        val i = r.nextInt(windows.size)
        val w = windows[i]
        val root = w.root?.let { it.copy(children = it.children + target) } ?: UiNode(children = listOf(target))
        return rec.copy(snapshot = rec.snapshot.copy(windows = windows.toMutableList().also { it[i] = w.copy(root = root) }))
    }

    private fun JsonObject.with(key: String, v: JsonElement?): JsonObject =
        JsonObject(toMutableMap().also { if (v == null) it.remove(key) else it[key] = v })

    private fun JsonArray.with(i: Int, v: JsonElement?): JsonArray =
        JsonArray(toMutableList().also { if (v == null) it.removeAt(i) else it[i] = v })

    private val junk: List<JsonElement> = listOf(
        JsonNull, JsonPrimitive("0"), JsonPrimitive(1.5), JsonPrimitive(true), JsonObject(emptyMap()),
        JsonPrimitive(-1), JsonPrimitive(99_999), JsonPrimitive(4_294_967_296L), JsonArray(emptyList()),
    )

    private fun mutateNode(r: Random, n: JsonObject, ids: List<Int>): JsonObject = when (r.nextInt(8)) {
        0 -> n.with("children", if (r.nextBoolean()) null else junk.random(r))
        1 -> (n["children"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { c ->
            when (r.nextInt(3)) {
                0 -> n.with("children", c.with(r.nextInt(c.size), null))
                1 -> n.with("children", JsonArray(c + c[r.nextInt(c.size)]))
                else -> n.with("children", c.with(r.nextInt(c.size), JsonPrimitive(ids.random(r))))
            }
        } ?: n.with("children", JsonArray(listOf(JsonPrimitive(ids.random(r)))))
        2 -> n.with("parent", if (r.nextBoolean()) null else JsonPrimitive(ids.random(r)))
        3 -> n.with("id", if (r.nextBoolean()) JsonPrimitive(ids.random(r)) else junk.random(r))
        4 -> n.with("bounds", junk.random(r))
        5 -> n.with("bounds", JsonArray(List(r.nextInt(0, 6)) { junk.random(r) }))
        6 -> n.with("text", junk.random(r))
        else -> n.with("parent", junk.random(r))
    }

    private fun mutateWindow(r: Random, w: JsonObject, allWindowIds: List<Int>): JsonObject {
        val nodes = w["nodes"] as? JsonArray ?: JsonArray(emptyList())
        val ids = nodes.mapNotNull { (it as? JsonObject)?.get("id")?.let { e -> (e as? JsonPrimitive)?.intOrNull } }.ifEmpty { listOf(0) }
        return when (r.nextInt(9)) {
            0 -> w.with("root", JsonPrimitive(-1))
            1 -> w.with("root", if (r.nextBoolean()) null else junk.random(r))
            2 -> w.with("root", JsonPrimitive(ids.random(r)))
            3 -> w.with("nodes", if (r.nextBoolean()) null else junk.random(r))
            4 -> if (nodes.isEmpty()) w else w.with("nodes", nodes.with(r.nextInt(nodes.size), null))
            5 -> if (nodes.isEmpty()) w else w.with("nodes", JsonArray(nodes.shuffled(r)))
            6 -> w.with("id", if (r.nextBoolean()) JsonPrimitive(allWindowIds.random(r)) else junk.random(r))
            else -> if (nodes.isEmpty()) w else r.nextInt(nodes.size).let { i ->
                val n = nodes[i] as? JsonObject ?: return w
                w.with("nodes", nodes.with(i, mutateNode(r, n, ids)))
            }
        }
    }

    private fun mutate(r: Random, obj: JsonObject): JsonObject {
        val windows = obj["windows"] as? JsonArray ?: return obj
        if (windows.isEmpty() || r.nextInt(25) == 0) return obj.with("windows", junk.random(r))
        val ids = windows.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.intOrNull }
        val i = r.nextInt(windows.size)
        val w = windows[i] as? JsonObject ?: return obj
        return obj.with("windows", windows.with(i, mutateWindow(r, w, ids)))
    }

    /** 입력 창(id별)의 nodes 배열에 든 노드 객체 수와 문자열 '예매하기' 표지 수. */
    private fun inputCounts(obj: JsonObject): Map<Int, Pair<Int, Int>> {
        val out = HashMap<Int, Pair<Int, Int>>()
        for (w in (obj["windows"] as? JsonArray).orEmpty()) {
            val wo = w as? JsonObject ?: continue
            val id = (wo["id"] as? JsonPrimitive)?.intOrNull ?: continue
            val nodes = (wo["nodes"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val markers = nodes.count { n -> (n["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == marker }
            val prev = out[id] ?: (0 to 0)
            out[id] = (prev.first + nodes.size) to (prev.second + markers)
        }
        return out
    }

    @Test
    fun `구조 변형은 예외로 거부되거나 노드를 하나도 잃지 않는다`() {
        val crashes = mutableListOf<String>()
        val silentDrops = mutableListOf<String>()
        var rejected = 0
        var accepted = 0
        for (seed in 140_000 until 144_000) {
            val r = Random(seed)
            var obj = SnapshotJsonl.encode(withMarker(r))
            repeat(r.nextInt(1, 3)) { obj = mutate(r, obj) }
            val line = obj.toString()
            val decoded = try {
                SnapshotJsonl.decodeLine(line, 1)
            } catch (e: ReplayFormatException) {
                rejected++
                continue
            } catch (t: Throwable) {
                crashes += "seed $seed: ${t::class.simpleName}"
                continue
            }
            accepted++
            val want = inputCounts(obj)
            for (w in decoded.snapshot.windows) {
                val (nodes, markers) = want[w.id] ?: continue
                val gotMarkers = w.nodes.values.count { it.text == marker }
                if (w.nodeCount != nodes || gotMarkers != markers) {
                    silentDrops += "seed $seed: window ${w.id} nodes $nodes->${w.nodeCount} markers $markers->$gotMarkers"
                }
            }
        }
        println("JSONL 구조 변형 4,000개: 거부 $rejected, 통과 $accepted, 조용한 잘림 ${silentDrops.size}, 크래시 ${crashes.size}")
        if (crashes.isNotEmpty() || silentDrops.isNotEmpty()) {
            fail("크래시 ${crashes.size}건 ${crashes.take(5)} / 조용한 잘림 ${silentDrops.size}건 ${silentDrops.take(5)}")
        }
        assertTrue(rejected > 500 && accepted > 500, "변형이 한쪽으로 쏠림: 거부 $rejected, 통과 $accepted")
    }

    @Test
    fun `노드가 있는데 root가 -1이거나 nodes가 배열이 아니면 거부한다`() {
        val node = """{"id":0,"parent":-1,"text":"예매하기","clickable":true}"""
        val cases = mapOf(
            "root -1 + 노드" to """{"v":0,"snap_id":"r","windows":[{"id":1,"type":"SYSTEM","root":-1,"nodes":[$node]}]}""",
            "nodes 객체" to """{"v":0,"snap_id":"r","windows":[{"id":1,"type":"SYSTEM","nodes":{"0":$node}}]}""",
            "nodes 문자열" to """{"v":0,"snap_id":"r","windows":[{"id":1,"type":"SYSTEM","nodes":"[]"}]}""",
            "root 문자열" to """{"v":0,"snap_id":"r","windows":[{"id":1,"type":"SYSTEM","root":"0","nodes":[$node]}]}""",
        )
        for ((name, line) in cases) {
            assertThrows(ReplayFormatException::class.java, { SnapshotJsonl.decodeLine(line, 1) }, name)
        }
        // 빈 창 표기는 그대로 받는다: root -1 + 빈 nodes, nodes 생략.
        for (line in listOf(
            """{"v":0,"snap_id":"e","windows":[{"id":1,"root":-1,"nodes":[]}]}""",
            """{"v":0,"snap_id":"e","windows":[{"id":1}]}""",
            """{"v":0,"snap_id":"e","windows":[{"id":1,"nodes":null}]}""",
        )) {
            assertEquals(null, SnapshotJsonl.decodeLine(line, 1).snapshot.windows.single().root, line)
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
