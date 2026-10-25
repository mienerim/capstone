package capstone.gate

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * IME 엔터(D36 'IME 엔터도 확정 버튼을 누를 수 있다') 퍼즈. 엔터는 노드가 아니라 앱의 입력 처리기(onEditorAction)가
 * 받으므로, 어떤 버튼이 눌릴지는 트리로 알 수 없다. 그래서 대상 앱 창 어디에든 확정 라벨이 보이면 실행하지 않는다.
 *
 * 불변식(오라클): 대상 창과 같은 패키지의 창 어딘가에 확정 신호(text·hint·desc·state·viewId)를 가진 노드가
 * 있으면 ALLOW 0. 클릭 가능 여부는 보지 않는다 — 텍스트는 자식, 클릭은 부모(G5, S3 '예매하기' 구조)이거나
 * WebView처럼 클릭 가능 표시가 없는 제출 버튼도 엔터 한 번에 눌린다(I-93).
 *
 * 함정 종류:
 *  0 없음 / 1 같은 창 클릭 가능 확정 버튼 / 2 같은 창 클릭 가능 컨테이너 + 클릭 불가 확정 글자 자식(G5)
 *  3 같은 창 클릭 표시 없는 확정 글자(WebView 제출) / 4 같은 앱 다른 창(바텀시트) 확정 버튼
 *  5 클릭 가능 컨테이너의 desc·viewId만 확정 / 6 시스템·키보드 창 확정 글자(다른 패키지 → 허용)
 *  7 부분 일치만('예매' 탭, '카드') → 허용
 */
class ImeEnterFuzzTest {
    private val confirmLabels = listOf("예매하기", "결제하기", "구매 확정", "카카오페이", "Pay", "접수하기", "좌석선택완료", "예 매 하 기")
    private val safeLabels = listOf("조회하기", "출발역", "도착역", "KTX 101", "09:00", "일반실", "어른 1명", "이전", "검색", null)
    private val partialLabels = listOf("예매", "카드", "예약 내역", "결제 안내")

    private data class Case(val snap: Snapshot, val target: UiWindow, val targetKey: String, val trap: Int)

    private fun filler(r: Random): UiNode =
        UiNode(className = "android.widget.TextView", text = safeLabels.random(r), clickable = r.nextInt(4) == 0)

    /** 노드를 무작위 깊이(0~3단 그룹)로 감싼다. 감싸는 그룹은 클릭 불가 — 클릭 가능 컨테이너는 함정 2·5가 따로 만든다. */
    private fun nest(r: Random, n: UiNode): UiNode {
        var out = n
        repeat(r.nextInt(0, 4)) { out = UiNode(className = "android.widget.LinearLayout", children = listOf(filler(r), out).shuffled(r)) }
        return out
    }

    private fun build(seed: Int): Case {
        val r = Random(seed)
        val trap = r.nextInt(0, 8)
        val field = UiNode(className = "android.widget.EditText", hint = listOf("출발역", "도착역", "역 이름")[r.nextInt(3)], editable = true, clickable = r.nextBoolean())
        val siblings = MutableList(r.nextInt(0, 5)) { nest(r, filler(r)) }
        when (trap) {
            1 -> siblings += nest(r, UiNode(className = "android.widget.Button", text = confirmLabels.random(r), clickable = true))
            2 -> siblings += nest(r, UiNode(className = "android.widget.FrameLayout", clickable = true, children = listOf(nest(r, UiNode(text = confirmLabels.random(r))))))
            3 -> siblings += nest(r, UiNode(className = "android.view.View", text = confirmLabels.random(r)))
            5 -> siblings += nest(
                r,
                if (r.nextBoolean()) {
                    UiNode(className = "android.widget.FrameLayout", contentDescription = confirmLabels.random(r), clickable = true, children = listOf(UiNode(text = "다음 단계")))
                } else {
                    UiNode(className = "android.widget.FrameLayout", viewId = "${Fx.PKG}:id/btn_reserve", clickable = true)
                },
            )
            7 -> siblings += nest(r, UiNode(className = "android.widget.TextView", text = partialLabels.random(r), clickable = r.nextBoolean()))
        }
        siblings.shuffle(r)
        val at = r.nextInt(0, siblings.size + 1)
        siblings.add(at, field)
        val main = Fx.app(UiNode(className = "android.widget.LinearLayout", children = siblings))
        val extra = when (trap) {
            4 -> UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = nest(r, Fx.button(confirmLabels.random(r))))
            6 -> if (r.nextBoolean()) {
                UiWindow(3, WindowType.SYSTEM, "com.android.systemui", root = UiNode(text = "카카오페이 결제 완료"))
            } else {
                UiWindow(9, WindowType.INPUT_METHOD, "com.samsung.android.honeyboard", root = Fx.button("결제하기"))
            }
            else -> null
        }
        val windows = listOfNotNull(extra, main, Fx.systemBar()).shuffled(r)
        return Case(Fx.snap(*windows.toTypedArray()), main, "0/$at", trap)
    }

    private fun hazardous(c: Case): Boolean = c.snap.windows
        .filter { it.packageName == c.target.packageName }
        .any { w -> w.nodes.values.any { ConfirmMatcher.hardReason(it) != null } }

    @Test
    fun `대상 앱 창에 확정 신호가 보이면 IME 엔터 ALLOW 0건`() {
        val violations = mutableListOf<String>()
        val hazardByTrap = IntArray(8)
        var safe = 0
        var safeAllowed = 0
        for (seed in 230_000 until 233_000) {
            val c = build(seed)
            val d = Gate(Fx.policy).check(c.snap, ActionRequest.ImeEnter(NodeRef.of(c.target, c.targetKey)))
            if (hazardous(c)) {
                hazardByTrap[c.trap]++
                if (d is Decision.Allow) violations += "seed=$seed trap=${c.trap}"
            } else {
                safe++
                if (d is Decision.Allow) safeAllowed++
            }
        }
        if (violations.isNotEmpty()) {
            val byTrap = violations.groupingBy { it.substringAfter("trap=") }.eachCount().toSortedMap()
            fail("${violations.size}건 위반(함정별 $byTrap), 처음 10건: ${violations.take(10)}")
        }
        for (t in 1..5) assertTrue(hazardByTrap[t] > 200, "함정 $t 위험 사례가 너무 적음: ${hazardByTrap[t]}")
        // 과잉 차단 확인: 다른 패키지 창의 확정 글자·부분 일치만 있는 화면은 실행돼야 한다.
        assertTrue(safe > 900, "안전 사례가 너무 적음: $safe")
        assertEquals(safe, safeAllowed, "위험 없는 IME 엔터가 막힘")
    }

    @Test
    fun `S3 좌석 화면 구조 - 클릭 가능 컨테이너 안 '예매하기' 글자가 있으면 IME 엔터 차단`() {
        val field = UiNode(className = "android.widget.EditText", hint = "메모", editable = true)
        val w = Fx.app(Fx.group(field, *Fx.seatScreen().children.toTypedArray()))
        val d = Gate(Fx.policy).check(Fx.snap(w), ActionRequest.ImeEnter(Fx.ref(w, Fx.key(w) { it.editable })))
        assertTrue(d is Decision.Block && d.reason == BlockReason.CONFIRM_ANCESTOR_DELEGATE, "S3 구조: $d")
    }

    @Test
    fun `같은 앱 바텀시트 창의 결제 버튼도 IME 엔터 차단, 키보드 창의 글자는 무시`() {
        val field = UiNode(className = "android.widget.EditText", hint = "출발역", editable = true)
        val w = Fx.app(Fx.group(field, Fx.button("조회하기")))
        val k = Fx.key(w) { it.editable }
        val sheet = UiWindow(2, WindowType.APPLICATION, Fx.PKG, root = Fx.button("결제하기"))
        val blocked = Gate(Fx.policy).check(Fx.snap(sheet, w), ActionRequest.ImeEnter(Fx.ref(w, k)))
        assertTrue(blocked is Decision.Block && blocked.reason == BlockReason.CONFIRM_ANCESTOR_DELEGATE, "바텀시트: $blocked")
        val keyboard = UiWindow(9, WindowType.INPUT_METHOD, "com.samsung.android.honeyboard", root = Fx.button("결제하기"))
        assertTrue(Gate(Fx.policy).check(Fx.snap(keyboard, w), ActionRequest.ImeEnter(Fx.ref(w, k))) is Decision.Allow)
    }
}
