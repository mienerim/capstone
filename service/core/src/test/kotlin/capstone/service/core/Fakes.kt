package capstone.service.core

import capstone.gate.Bounds
import capstone.gate.WindowType

/** 실기기 노드 흉내. 필드는 var라 테스트가 '실행 직전 화면이 바뀐' 상황을 만들 수 있다. */
class FakeNode(
    override var packageName: String? = "capstone.mock",
    override var className: String? = "android.widget.TextView",
    override var text: String? = null,
    override var hint: String? = null,
    override var contentDescription: String? = null,
    override var stateDescription: String? = null,
    override var paneTitle: String? = null,
    override var viewId: String? = null,
    override var bounds: Bounds = Bounds(0, 0, 100, 50),
    override var clickable: Boolean = false,
    override var longClickable: Boolean = false,
    override var editable: Boolean = false,
    override var scrollable: Boolean = false,
    override var enabled: Boolean = true,
    override var visibleToUser: Boolean = true,
    override var password: Boolean = false,
    override var dataSensitive: Boolean = false,
    val kids: MutableList<FakeNode?> = mutableListOf(),
) : A11yNode {
    var alive: Boolean = true
    val performed: MutableList<NodeOp> = mutableListOf()
    /** false면 performAction이 실패한 것처럼 돌려준다(CLICK 실패 → 중심 탭 폴백 경로). */
    var performResult: Boolean = true

    override val childCount: Int get() = kids.size
    override fun child(index: Int): A11yNode? = kids[index]
    override fun refresh(): Boolean = alive
    override fun perform(op: NodeOp): Boolean {
        performed += op
        return performResult
    }
}

class FakeWindow(
    override val id: Int,
    private val rootNode: FakeNode?,
    override val type: WindowType = WindowType.APPLICATION,
    override val active: Boolean = true,
    override val focused: Boolean = true,
) : A11yWindow {
    override fun root(): A11yNode? = rootNode
}

class FakeDevice : DeviceOps {
    val taps = mutableListOf<Pair<Int, Int>>()
    var backs = 0
    override fun tap(x: Int, y: Int): Boolean {
        taps += x to y
        return true
    }
    override fun back(): Boolean {
        backs++
        return true
    }
}

/** 목업 S1 흉내: 루트(프레임) → [제목, 출발 입력칸, 조회하기 버튼(안에 텍스트)]. */
fun searchScreen(): FakeNode {
    val label = FakeNode(text = "조회하기", bounds = Bounds(10, 410, 290, 490))
    return FakeNode(
        className = "android.widget.FrameLayout",
        bounds = Bounds(0, 0, 1080, 2400),
        kids = mutableListOf(
            FakeNode(text = "승차권 예매"),
            FakeNode(className = "android.widget.EditText", viewId = "capstone.mock:id/dep_station", text = "서울", editable = true, clickable = true),
            FakeNode(className = "android.widget.Button", viewId = "capstone.mock:id/search", clickable = true, bounds = Bounds(0, 400, 300, 500), kids = mutableListOf(label)),
        ),
    )
}
