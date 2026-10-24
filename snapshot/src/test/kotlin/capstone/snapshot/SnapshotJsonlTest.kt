package capstone.snapshot

import capstone.gate.Bounds
import capstone.gate.WindowType
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 목업 픽스처는 tools/fixtures에 하나만 둔다(build.gradle.kts가 경로를 넘긴다).
private val mockJsonl = File(System.getProperty("capstone.fixtures") ?: "../tools/fixtures", "mock/mock-korail.jsonl")

class SnapshotJsonlTest {

    @Test
    fun `목업 픽스처 5장을 읽는다`() {
        val recs = SnapshotJsonl.read(mockJsonl)
        assertEquals(
            listOf("mock_s1_popup", "mock_s1_search", "mock_s2_results", "mock_s3_seat", "mock_s4_payment"),
            recs.map { it.snapId },
        )
        for (r in recs) {
            assertEquals("capstone.mock", r.snapshot.foregroundAppWindow?.packageName, r.snapId)
            assertNull(r.snapshot.screen, "screen은 분류기 몫이라 로더가 채우지 않는다")
        }
        val s2 = recs.first { it.snapId == "mock_s2_results" }
        assertEquals("RESULT_LIST", s2.meta.screenLabel)
        assertTrue(s2.snapshot.foregroundAppWindow!!.nodeCount >= 20, "급감 기준선(n0>=20)이 동작할 크기")
    }

    @Test
    fun `인코드-디코드 왕복 후 트리와 treeHash가 같다`() {
        for (r in SnapshotJsonl.read(mockJsonl)) {
            val back = SnapshotJsonl.decodeLine(SnapshotJsonl.encodeLine(r))
            assertEquals(r, back, r.snapId)
            assertEquals(
                r.snapshot.windows.map { it.treeHash },
                back.snapshot.windows.map { it.treeHash },
            )
        }
    }

    @Test
    fun `없는 필드는 기본값, 모르는 필드는 무시`() {
        val line = """{"v":0,"snap_id":"x","future_field":{"a":1},"windows":[{"id":3,"type":"APPLICATION","active":true,
            "nodes":[{"id":0,"parent":-1,"pkg":"com.korail.talk","text":"조회","clickable":true,"password":false,"actions":["ACTION_CLICK"]}]}]}"""
            .replace("\n", "")
        val r = SnapshotJsonl.decodeLine(line)
        val w = r.snapshot.windows.single()
        assertEquals(WindowType.APPLICATION, w.type)
        assertEquals("com.korail.talk", w.packageName, "창 pkg가 없으면 루트 노드 pkg")
        assertFalse(w.focused)
        val n = w.root!!
        assertTrue(n.clickable && n.enabled && n.visible)
        assertFalse(n.editable)
        assertEquals(Bounds.EMPTY, n.bounds)
        assertEquals(0L, r.snapshot.timestampMs)
    }

    @Test
    fun `children이 없으면 parent 역참조를 목록 순서대로 쓴다`() {
        val line = """{"v":0,"snap_id":"p","windows":[{"id":1,"type":"APPLICATION","root":0,"nodes":[
            {"id":0,"parent":-1},{"id":5,"parent":0,"text":"a"},{"id":2,"parent":0,"text":"b"},{"id":9,"parent":2,"text":"c"}]}]}"""
            .replace("\n", "")
        val root = SnapshotJsonl.decodeLine(line).snapshot.windows.single().root!!
        assertEquals(listOf("a", "b"), root.children.map { it.text })
        assertEquals("c", root.children[1].children.single().text)
    }

    @Test
    fun `알 수 없는 창 타입은 UNKNOWN, root -1이면 빈 창`() {
        val r = SnapshotJsonl.decodeLine("""{"v":0,"snap_id":"e","windows":[{"id":1,"type":"WINDOW_CONTROL","root":-1,"nodes":[]}]}""")
        val w = r.snapshot.windows.single()
        assertEquals(WindowType.UNKNOWN, w.type)
        assertNull(w.root)
    }

    @Test
    fun `주석 줄과 빈 줄은 건너뛴다`() {
        val lines = sequenceOf("// 설명", "", """{"v":0,"snap_id":"a","windows":[]}""", "   ")
        assertEquals(listOf("a"), SnapshotJsonl.decodeLines(lines).map { it.snapId })
    }

    @Test
    fun `구조 오류는 조용히 넘기지 않는다`() {
        fun bad(nodes: String, extra: String = "") =
            """{"v":0,"snap_id":"b"$extra,"windows":[{"id":1,"type":"APPLICATION","root":0,"nodes":[$nodes]}]}"""
        val cases = mapOf(
            "없는 자식" to bad("""{"id":0,"children":[7]}"""),
            "중복 id" to bad("""{"id":0},{"id":0}"""),
            "순환" to bad("""{"id":0,"children":[1]},{"id":1,"children":[0]}"""),
            "고아 노드" to bad("""{"id":0},{"id":1,"parent":42}"""),
            "bounds 길이" to bad("""{"id":0,"bounds":[1,2,3]}"""),
            "없는 루트" to """{"v":0,"snap_id":"b","windows":[{"id":1,"root":3,"nodes":[{"id":0}]}]}""",
            "창 id 중복" to """{"v":0,"snap_id":"b","windows":[{"id":1,"nodes":[]},{"id":1,"nodes":[]}]}""",
            "버전" to """{"v":1,"snap_id":"b","windows":[]}""",
            "snap_id 없음" to """{"v":0,"windows":[]}""",
            "JSON 아님" to "{not json",
        )
        for ((name, line) in cases) {
            assertThrows(ReplayFormatException::class.java, { SnapshotJsonl.decodeLine(line, 1) }, name)
        }
    }
}
