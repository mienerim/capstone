package capstone.agent

import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import capstone.gate.exec.GatedExecutor
import capstone.gate.exec.RawActuator

const val PKG = "capstone.mock"

/** 상태머신 단위 테스트용 가짜 대상 앱. 재생기(tools)와 달리 시간 지연·영구 결함을 코드로 바로 만든다. */
class FakeApp(private val clock: VirtualClock, start: String) : RawActuator, ScreenSource {
    var current: String = start
        private set
    /** (화면, 누른 라벨) → (다음 화면, 지연 ms). 지연 동안은 빈 트리(로딩)를 보인다. */
    private val transitions = mutableMapOf<Pair<String, String>, Pair<String, Long>>()
    private val backs = mutableMapOf<String, String>()
    private val typed = mutableMapOf<String, String>()
    private var pending: Pair<String, Long>? = null

    /** 장치까지 닿은 raw 호출. M7 판정과 같은 의미. */
    val raw = mutableListOf<String>()
    /** capture 결과를 바꾼다(스크린샷 오류·민감 노드 등). */
    var overlay: ((Snapshot) -> Snapshot)? = null
    /** 첫 N번의 클릭은 반응 없음(성공을 돌려주지만 화면이 안 바뀜). */
    var ignoreClicks = 0
    var onRaw: (() -> Unit)? = null

    fun on(screen: String, label: String, to: String, delayMs: Long = 0) {
        transitions[screen to label] = to to delayMs
    }

    fun back(from: String, to: String) {
        backs[from] = to
    }

    fun goTo(screen: String) {
        current = screen
        pending = null
    }

    fun window(): UiWindow {
        pending?.let { (to, at) -> if (clock.now >= at) { current = to; pending = null } }
        val root = if (pending != null) null else SCREENS.getValue(current).withTyped()
        return UiWindow(1, WindowType.APPLICATION, if (current == FOREIGN) "com.other" else PKG, active = true, focused = true, root = root)
    }

    override fun capture(): Snapshot {
        val s = Snapshot(clock.now, listOf(window(), UiWindow(2, WindowType.SYSTEM, "com.android.systemui", root = UiNode(text = "12:00"))))
        return overlay?.invoke(s) ?: s
    }

    private fun UiNode.withTyped(): UiNode {
        val id = viewId?.substringAfter(":id/")
        val t = id?.let(typed::get)
        return copy(text = t ?: text, children = children.map { it.withTyped() })
    }

    private fun resolve(ref: NodeRef): Pair<UiWindow, String>? {
        val w = window()
        return if (w.treeHash == ref.treeHash && ref.key in w.nodes) w to ref.key else null
    }

    private fun labelAt(w: UiWindow, key: String): String =
        (listOf(key) + w.descendantsOf(key)).firstNotNullOfOrNull { w.nodes.getValue(it).text } ?: "?"

    override fun click(ref: NodeRef): Boolean {
        val (w, key) = resolve(ref) ?: return false
        val label = labelAt(w, key)
        raw += "click:$label"
        onRaw?.invoke()
        if (ignoreClicks > 0) {
            ignoreClicks--
            return true
        }
        transitions[current to label]?.let { (to, delay) ->
            if (delay == 0L) current = to else pending = to to clock.now + delay
        }
        return true
    }

    override fun setText(ref: NodeRef, text: String): Boolean {
        val (w, key) = resolve(ref) ?: return false
        val id = w.nodes.getValue(key).viewId?.substringAfter(":id/") ?: return false
        raw += "set_text:$id"
        onRaw?.invoke()
        typed[id] = text
        return true
    }

    override fun scroll(ref: NodeRef, forward: Boolean): Boolean = false
    override fun imeEnter(ref: NodeRef): Boolean = false
    override fun tapCenter(ref: NodeRef): Boolean = click(ref)

    override fun back(): Boolean {
        raw += "back"
        onRaw?.invoke()
        backs[current]?.let { current = it }
        return true
    }

    companion object {
        const val FOREIGN = "FOREIGN"
        const val ODD = "ODD"

        private fun text(t: String) = UiNode(className = "TextView", text = t)
        private fun button(t: String, id: String? = null) = UiNode(className = "Button", text = t, clickable = true, viewId = id?.let { "$PKG:id/$it" })
        private fun edit(id: String, hint: String, sensitive: Boolean = false) =
            UiNode(className = "EditText", hint = hint, viewId = "$PKG:id/$id", editable = true, clickable = true, dataSensitive = sensitive)
        private fun row(train: String, status: String) =
            UiNode(className = "LinearLayout", viewId = "$PKG:id/row_train", clickable = true, children = listOf(text(train), text(status)))
        private fun frame(vararg c: UiNode) = UiNode(className = "FrameLayout", children = c.toList())

        private val searchNodes = arrayOf(text("승차권 조회"), edit("dep_station", "출발역"), edit("arr_station", "도착역"), button("조회하기", "btn_search"))

        val SCREENS: Map<String, UiNode> = mapOf(
            Screens.SEARCH_FORM to frame(*searchNodes),
            Screens.POPUP_KNOWN to frame(*searchNodes, UiNode(viewId = "$PKG:id/popup", children = listOf(text("공지사항"), button("닫기")))),
            Screens.RESULT_LIST to frame(
                UiNode(viewId = "$PKG:id/list", children = listOf(row("KTX 101", "매진"), row("KTX 105", "예약가능"), row("KTX 107", "예약가능"))),
                button("이전"),
            ),
            Screens.DETAIL to frame(
                text("좌석 자동배정 결과"), text("3호차 12A"), button("이전"),
                UiNode(className = "LinearLayout", viewId = "$PKG:id/btn_main", clickable = true, children = listOf(text("예매하기"))),
                // I-8 문맥 규칙 확인용: '확인'은 단독으론 안전하지만 결제 문맥 안에 있다.
                UiNode(className = "LinearLayout", children = listOf(text("결제를 계속하려면"), UiNode(className = "LinearLayout", children = listOf(button("확인"))))),
            ),
            Screens.LOGIN to frame(text("로그인"), edit("pw", "비밀번호"), button("로그인 하기")),
            FOREIGN to frame(text("다른 앱")),
            ODD to frame(text("알 수 없는 화면"), button("무언가")),
        )

        val classifier = ScreenClassifier(
            listOf(
                ScreenSignature(Screens.POPUP_KNOWN, setOf(PKG), listOf(NodeMatcher(id = "popup"), NodeMatcher(text = "공지사항"))),
                ScreenSignature(
                    Screens.SEARCH_FORM, setOf(PKG),
                    mustHave = listOf(NodeMatcher(text = "조회하기", clickable = true), NodeMatcher(id = "dep_station")),
                    mustNotHave = listOf(NodeMatcher(id = "popup")),
                ),
                ScreenSignature(Screens.RESULT_LIST, setOf(PKG), listOf(NodeMatcher(id = "list"), NodeMatcher(id = "row_train"))),
                ScreenSignature(Screens.DETAIL, setOf(PKG), listOf(NodeMatcher(text = "좌석 자동배정 결과"))),
                ScreenSignature(Screens.LOGIN, setOf(PKG), listOf(NodeMatcher(text = "로그인"), NodeMatcher(id = "pw"))),
            ),
        )

        /** 대표 시나리오: 팝업 → 출발·도착 입력 → 조회 → 열차 묻기 → 열차 선택 → 좌석 화면에서 정지. */
        val trainFlow: Flow = flow("fake_train", setOf(PKG)) {
            popup(NodeMatcher(text = "닫기", clickable = true))
            type(Screens.SEARCH_FORM, NodeMatcher(id = "dep_station")) { it.getValue("origin") }
            type(Screens.SEARCH_FORM, NodeMatcher(id = "arr_station")) { it.getValue("destination") }
            click(Screens.SEARCH_FORM, then = Screens.RESULT_LIST, timeoutMs = 15_000) { NodeMatcher(text = "조회하기") }
            ask(Screens.RESULT_LIST, slot = "train", question = "어느 열차로 할까요?") { _, w ->
                w.nodes.values.filter { it.viewId?.endsWith("row_train") == true && it.children.any { c -> c.text == "예약가능" } }
                    .mapNotNull { it.children.first().text }
            }
            click(Screens.RESULT_LIST, then = Screens.DETAIL) { NodeMatcher(text = it.getValue("train")) }
            finish(Screens.DETAIL) { "${it["origin"]}→${it["destination"]} ${it["train"]}, '예매하기'부터는 직접 눌러 주세요" }
        }
    }
}

/** 실제 Gate + GatedExecutor 아래에 [FakeApp]을 끼운 조립. */
class Harness(
    start: String = Screens.POPUP_KNOWN,
    val flow: Flow = FakeApp.trainFlow,
    slots: Slots = mapOf("origin" to "서울", "destination" to "부산"),
    config: RunnerConfig = RunnerConfig(),
) {
    val clock = VirtualClock()
    val app = FakeApp(clock, start)
    val gate = Gate(GatePolicy(allowedPackages = setOf(PKG), ownPackage = "capstone.app"))
    private val executor = GatedExecutor(gate, app) { FakeApp.classifier.label(app.capture()) }
    val runner = ScenarioRunner(flow, slots, FakeApp.classifier, executor, app, clock, config)

    init {
        app.on(Screens.POPUP_KNOWN, "닫기", Screens.SEARCH_FORM)
        app.on(Screens.SEARCH_FORM, "조회하기", Screens.RESULT_LIST, delayMs = 1_000)
        app.on(Screens.RESULT_LIST, "KTX 105", Screens.DETAIL)
        app.on(Screens.RESULT_LIST, "KTX 107", Screens.DETAIL)
        app.on(Screens.DETAIL, "예매하기", "PAYMENT")
        app.back(Screens.RESULT_LIST, Screens.SEARCH_FORM)
        app.back(Screens.DETAIL, Screens.RESULT_LIST)
    }

    val kinds: List<String> get() = runner.events.map { it.kind }
}
