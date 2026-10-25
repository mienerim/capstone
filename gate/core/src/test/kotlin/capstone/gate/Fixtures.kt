package capstone.gate

/** 테스트용 트리 빌더. 화면 구성은 코레일+ 추정 구조이며 실기기 덤프(10-27)로 교체한다. */
object Fx {
    const val PKG = GatePolicy.KORAIL_PLUS
    const val OWN = "capstone.app"
    val policy = GatePolicy(allowedPackages = setOf(PKG, GatePolicy.MOCK_APP), ownPackage = OWN)

    fun text(t: String, b: Bounds = Bounds.EMPTY) = UiNode(className = "android.widget.TextView", text = t, bounds = b)
    fun button(t: String? = null, desc: String? = null, id: String? = null, b: Bounds = Bounds.EMPTY, children: List<UiNode> = emptyList()) =
        UiNode(className = "android.widget.Button", text = t, contentDescription = desc, viewId = id, bounds = b, clickable = true, children = children)
    fun group(vararg c: UiNode, clickable: Boolean = false, b: Bounds = Bounds.EMPTY) =
        UiNode(className = "android.widget.LinearLayout", clickable = clickable, bounds = b, children = c.toList())

    fun app(root: UiNode?, pkg: String = PKG, id: Int = 1) =
        UiWindow(id = id, type = WindowType.APPLICATION, packageName = pkg, active = true, focused = true, root = root)
    fun ime(id: Int = 9) = UiWindow(id, WindowType.INPUT_METHOD, "com.samsung.android.honeyboard", root = button("확인"))
    fun systemBar(id: Int = 8) = UiWindow(id, WindowType.SYSTEM, "com.android.systemui", root = text("12:00"))

    fun snap(vararg w: UiWindow, screen: String? = "S3", ts: Long = 0, err: Int? = null) =
        Snapshot(timestampMs = ts, windows = w.toList(), screen = screen, screenshotError = err)

    fun key(w: UiWindow, pred: (UiNode) -> Boolean): String = w.nodes.entries.first { pred(it.value) }.key
    fun keyOfText(w: UiWindow, t: String) = key(w) { it.text == t || it.contentDescription == t }
    fun ref(w: UiWindow, k: String) = NodeRef.of(w, k)
    fun click(w: UiWindow, k: String, screen: String? = null) = ActionRequest.Click(ref(w, k), expectedScreen = screen)

    /** 행 n개짜리 목록 화면(급감 기준선용). */
    fun listScreen(rows: Int): UiNode = group(
        text("열차 조회"),
        *Array(rows) { i -> group(text("KTX ${100 + i}"), text("09:${10 + i % 50}"), button("선택"), clickable = false) },
    )

    /** S3 좌석 화면: '이전' 버튼, 자동배정 안내, 클릭 가능한 컨테이너 안의 '예매하기' 텍스트. */
    fun seatScreen(): UiNode = group(
        text("좌석 자동배정 결과"),
        button("이전", b = Bounds(0, 2000, 500, 2200)),
        group(text("예매하기"), clickable = true, b = Bounds(540, 2000, 1080, 2200)),
    )

    fun gate(sink: DecisionSink = DecisionSink.NONE) = Gate(policy, sink)
}
