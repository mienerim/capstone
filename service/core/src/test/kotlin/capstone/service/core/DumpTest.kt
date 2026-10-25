package capstone.service.core

import capstone.snapshot.SnapshotJsonl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DumpMaskerTest {

    @Test
    fun `패턴 개인정보를 지운다`() {
        assertEquals("연락처 [MASK]", DumpMasker.maskText("연락처 010-1234-5678"))
        assertEquals("[MASK]", DumpMasker.maskText("01012345678"))
        assertEquals("[MASK]", DumpMasker.maskText("900101-1234567"))
        assertEquals("카드 [MASK]", DumpMasker.maskText("카드 1234-5678-9012-3456"))
        assertEquals("[MASK] 님", DumpMasker.maskText("kim.test@example.com 님"))
        assertEquals("회원번호 [MASK]", DumpMasker.maskText("회원번호 1234567890"))
    }

    @Test
    fun `분류에 필요한 짧은 값은 남긴다`() {
        for (s in listOf("서울 → 부산", "KTX 101 08:00", "59,800원", "5호차 12A", "2026.10.27(화)", "조회하기")) {
            assertEquals(s, DumpMasker.maskText(s))
        }
    }

    @Test
    fun `입력칸과 data-sensitive 노드는 값 전체를 지운다`() {
        val root = searchScreen().apply {
            kids += FakeNode(text = "김철수", dataSensitive = true)
        }
        val s = TreeCapture().capture(listOf(FakeWindow(1, root)), 0).snapshot
        val w = DumpMasker.mask(s).window(1)!!
        assertEquals("[MASK]", w.nodes.getValue("0/1").text, "입력칸 값")
        assertEquals("[MASK]", w.nodes.getValue("0/3").text, "data-sensitive")
        assertEquals("조회하기", w.nodes.getValue("0/2/0").text)
        assertEquals("capstone.mock:id/dep_station", w.nodes.getValue("0/1").viewId, "viewId는 서명용으로 남긴다")
    }

    @Test
    fun `병원·약국 이름과 진료과는 어느 앱에서든 지운다(D70)`() {
        assertEquals("[MASK] 10:30", DumpMasker.maskText("서울튼튼정형외과의원 10:30"))
        assertEquals("[MASK] 진료", DumpMasker.maskText("정형외과 진료"))
        assertEquals("[MASK] 대기", DumpMasker.maskText("튼튼내과 대기"))
        assertEquals("[MASK]에서 받기", DumpMasker.maskText("행복약국에서 받기"))
        assertEquals("[MASK] 3층", DumpMasker.maskText("이비인후과 3층"))
    }

    @Test
    fun `진료과처럼 보여도 다른 낱말이면 남긴다`() {
        for (s in listOf("예매 안내과정", "열차 안내", "제외과목 없음", "과천 → 부산")) {
            assertEquals(s, DumpMasker.maskText(s))
        }
    }

    @Test
    fun `병원 앱 창은 글자를 모두 지우고 구조만 남긴다(D70)`() {
        val root = FakeNode(
            packageName = "com.example.clinic", className = "android.widget.FrameLayout", viewId = "clinic:id/root",
            kids = mutableListOf<FakeNode?>(
                FakeNode(packageName = "com.example.clinic", text = "김의사 원장", viewId = "clinic:id/doctor"),
                FakeNode(packageName = "com.example.clinic", text = "10월 27일 오후 2시 예약", clickable = true),
                FakeNode(packageName = "com.example.clinic", contentDescription = "기침이 나요", hint = "증상을 적어 주세요"),
            ),
        )
        val s = TreeCapture().capture(listOf(FakeWindow(1, root)), 0).snapshot
        val w = DumpMasker.mask(s, healthPackages = setOf("com.example.clinic")).window(1)!!
        val texts = w.nodes.values.flatMap { listOf(it.text, it.contentDescription, it.hint) }.filterNotNull()
        assertTrue(texts.all { it == DumpMasker.MASK }, texts.toString())
        assertEquals("clinic:id/doctor", w.nodes.getValue("0/0").viewId, "viewId·구조는 남는다")
        assertTrue(w.nodes.getValue("0/1").clickable)
        // 목록에 없는 앱은 평소 규칙대로
        assertEquals("김의사 원장", DumpMasker.mask(s).window(1)!!.nodes.getValue("0/0").text)
    }
}

class DumpRecorderTest {

    private val device = DeviceInfo(sdk = 36, oneUi = "8.0", appVersion = { if (it == "capstone.mock") "0.1.0" else null })

    @Test
    fun `목록에 든 앱 화면만 바뀔 때마다 한 줄씩 쓴다`() {
        val out = StringBuilder()
        val rec = DumpRecorder(out, setOf("capstone.mock"), device, sessionId = "s20261027a")
        val root = searchScreen()
        val cap = { TreeCapture().capture(listOf(FakeWindow(1, root)), 1_761_500_000_000).snapshot }

        assertEquals(DumpRecorder.Result.Written("s20261027a_0001"), rec.record(cap()))
        assertEquals(DumpRecorder.Result.Skipped("unchanged"), rec.record(cap()))
        (root.kids[0] as FakeNode).text = "열차 조회 결과"
        assertIs<DumpRecorder.Result.Written>(rec.record(cap()))

        val other = TreeCapture().capture(listOf(FakeWindow(2, FakeNode(packageName = "com.kakao.talk", text = "대화"))), 0).snapshot
        assertEquals(DumpRecorder.Result.Skipped("package_not_listed"), rec.record(other))
        assertEquals(2, rec.written)

        // 재생기가 그대로 읽을 수 있어야 한다(10-27: tools validate).
        val back = SnapshotJsonl.decodeLines(out.lineSequence())
        assertEquals(listOf("s20261027a_0001", "s20261027a_0002"), back.map { it.snapId })
        val m = back[0].meta
        assertEquals("capstone.mock", m.app)
        assertEquals("0.1.0", m.appVersion)
        assertEquals(36, m.sdk)
        assertEquals(false, m.a11yToolFlag)
        assertNull(m.screenLabel, "정답 라벨은 사람이 붙인다")
        assertTrue(m.captured!!.endsWith("+09:00"))
        assertEquals("[MASK]", back[0].snapshot.foregroundAppWindow!!.nodes.getValue("0/1").text)
        assertFalse(out.contains("서울"), "입력칸 값은 파일에 남지 않는다")
    }

    @Test
    fun `세션 한도를 넘기면 쓰지 않는다`() {
        val out = StringBuilder()
        val rec = DumpRecorder(out, setOf("capstone.mock"), device, "s", maxLines = 1)
        val root = searchScreen()
        rec.record(TreeCapture().capture(listOf(FakeWindow(1, root)), 0).snapshot)
        (root.kids[0] as FakeNode).text = "다른 화면"
        assertEquals(DumpRecorder.Result.Skipped("max_lines"), rec.record(TreeCapture().capture(listOf(FakeWindow(1, root)), 0).snapshot))
    }

    @Test
    fun `포그라운드 앱 창이 없으면 건너뛴다`() {
        val rec = DumpRecorder(StringBuilder(), setOf("capstone.mock"), device, "s")
        val s = TreeCapture().capture(listOf(FakeWindow(1, searchScreen(), active = false, focused = false)), 0).snapshot
        assertEquals(DumpRecorder.Result.Skipped("no_foreground"), rec.record(s))
    }
}
