package capstone.gate

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 트리 급감 기준선(D35 ②·I-11) 퍼즈. 한 실행 안에서 check → (실행) → checkAfter를 반복하며, 같은 화면 라벨인데
 * 노드가 대부분 사라진 '숨은 화면'(오버레이·WebView 덮임·로딩 깜빡임)을 섞는다. 실행 뒤 캡처는 분류된 같은 화면,
 * 다른 화면, 미분류(null)가 섞인다.
 *
 * 불변식(오라클): 오라클은 I-11대로 '허용된 트리만, (패키지, 화면)별 최근 5개'의 이상적 기준선을 따로 든다.
 * 그 중앙값이 정상 트리면 같은 (패키지, 화면)의 급감 트리는 check든 checkAfter든 ALLOW 0(미분류 실행 뒤 캡처는
 * 직전 트리와 비교). 급감 트리가 막히지 않고 기준선에 들어가 중앙값을 끌어내리면 숨은 화면이 정상처럼 통과한다.
 * 기준선이 아직 없을 때 처음 보는 숨은 화면은 판단할 근거가 없어 허용된다(오라클도 같다).
 */
class CollapseBaselineFuzzTest {
    private fun tree(rows: Int): UiNode = Fx.group(
        Fx.text("열차 조회"),
        *Array(rows) { i -> Fx.group(Fx.text("KTX ${100 + i}"), Fx.text("09:${10 + i}"), Fx.button("선택")) },
        Fx.button("조회하기"),
    )

    /** 정상: 8~12행(노드 35~51·상호작용 9~13). 숨은 화면: 0~2행(노드 3~11·상호작용 1~3) — 어느 정상과 비교해도 0.4배 미만. */
    private fun normal(r: Random, screen: String?) = Fx.snap(Fx.app(tree(r.nextInt(8, 13))), Fx.systemBar(), screen = screen)
    private fun hidden(r: Random, screen: String?) = Fx.snap(Fx.app(tree(r.nextInt(0, 3))), Fx.systemBar(), screen = screen)
    private fun isHidden(s: Snapshot) = s.foregroundAppWindow!!.nodeCount < 12

    private fun clickOf(s: Snapshot): ActionRequest.Click {
        val w = s.foregroundAppWindow!!
        return ActionRequest.Click(Fx.ref(w, Fx.keyOfText(w, "조회하기")))
    }

    private fun after(r: Random, beforeScreen: String): Snapshot {
        val screen = when (r.nextInt(10)) {
            in 0..5 -> beforeScreen
            in 6..7 -> null
            else -> if (beforeScreen == "S1") "S2" else "S1"
        }
        return if (r.nextInt(10) < 4) hidden(r, screen) else normal(r, screen)
    }

    @Test
    fun `정상 트리를 본 화면의 급감 트리는 check·checkAfter 모두 ALLOW 0건`() {
        val violations = mutableListOf<String>()
        var normalAllows = 0
        var hiddenSeen = 0
        for (seed in 320_000 until 323_000) {
            val r = Random(seed)
            val gate = Gate(Fx.policy)
            val ideal = HashMap<String, ArrayDeque<Boolean>>() // 화면 → 허용된 트리가 숨은 화면이었나
            fun refIsNormal(key: String): Boolean {
                val h = ideal[key]?.takeIf { it.isNotEmpty() } ?: return false
                return !h.sortedBy { if (it) 0 else 1 }[h.size / 2] // Gate.median과 같은 위쪽 중앙값
            }
            fun remember(key: String, hiddenTree: Boolean) {
                val q = ideal.getOrPut(key) { ArrayDeque() }
                q.addLast(hiddenTree)
                while (q.size > Fx.policy.baselineWindow) q.removeFirst()
            }
            var before = normal(r, "S1")
            for (step in 0 until r.nextInt(6, 16)) {
                val screen = before.screen!!
                val req = clickOf(before)
                val d = gate.check(before, req)
                if (isHidden(before)) {
                    hiddenSeen++
                    if (d is Decision.Allow && refIsNormal(screen)) {
                        violations += "seed=$seed step=$step phase=check screen=$screen"
                    }
                }
                if (d !is Decision.Allow) {
                    // 호출자는 다시 캡처한다: 숨은 화면이 그대로이거나 돌아온다.
                    before = if (r.nextBoolean()) hidden(r, screen) else normal(r, screen)
                    continue
                }
                if (!(isHidden(before) && refIsNormal(screen))) remember(screen, isHidden(before))
                if (!isHidden(before)) normalAllows++
                val a = after(r, screen)
                val da = gate.checkAfter(before, a, req)
                val known = if (a.screen == null) !isHidden(before) else refIsNormal(a.screen!!)
                if (isHidden(a) && known && da is Decision.Allow) {
                    violations += "seed=$seed step=$step phase=after screen=${a.screen}"
                }
                // 이상적 gate라면 막았을 트리는 이상적 기준선에 넣지 않는다.
                if (da is Decision.Allow && a.screen != null && !(isHidden(a) && known)) remember(a.screen!!, isHidden(a))
                before = if (a.screen == null) {
                    if (r.nextBoolean()) normal(r, screen) else hidden(r, screen)
                } else {
                    a
                }
            }
        }
        if (violations.isNotEmpty()) {
            val byPhase = violations.groupingBy { it.substringAfter("phase=").substringBefore(' ') }.eachCount()
            fail("${violations.size}건 위반($byPhase), 처음 10건: ${violations.take(10)}")
        }
        assertTrue(hiddenSeen > 2_000, "숨은 화면 사례가 너무 적음: $hiddenSeen")
        assertTrue(normalAllows > 8_000, "정상 실행이 너무 적음(과잉 차단?): $normalAllows")
    }

    @Test
    fun `같은 화면으로 분류된 급감 실행 뒤 캡처는 막고 기준선에 넣지 않음`() {
        val gate = Gate(Fx.policy)
        val r = Random(7)
        val s1 = normal(r, "S1")
        val req = clickOf(s1)
        assertEquals(Decision.Allow(), gate.check(s1, req))
        // 숨은 화면이 같은 화면으로 분류된 채 세 번 연달아 와도(기준선 5칸 중 과반) 기준선이 끌려 내려가지 않아야 한다.
        var prev = s1
        repeat(3) {
            val ok = normal(r, "S1")
            assertEquals(Decision.Allow(), gate.check(ok, clickOf(ok)))
            val h = hidden(r, "S1")
            val d = gate.checkAfter(ok, h, clickOf(ok))
            assertTrue(d is Decision.Block && d.reason == BlockReason.FAIL_CLOSED_TREE_COLLAPSE && "lock" !in d.signals, "급감은 막되 잠그지 않음: $d")
            prev = h
        }
        val d = gate.check(prev, clickOf(prev))
        assertTrue(d is Decision.Block && d.reason == BlockReason.FAIL_CLOSED_TREE_COLLAPSE, "숨은 화면은 여전히 급감: $d")
    }

    @Test
    fun `처음 보는 화면으로 넘어간 실행 뒤 캡처는 급감으로 보지 않음`() {
        val gate = Gate(Fx.policy)
        val r = Random(8)
        val s1 = normal(r, "S1")
        assertEquals(Decision.Allow(), gate.check(s1, clickOf(s1)))
        // 노드가 적은 새 화면(S2)은 기준선이 없으므로 정상 전환이다.
        assertEquals(Decision.Allow(), gate.checkAfter(s1, hidden(r, "S2"), clickOf(s1)))
    }
}
