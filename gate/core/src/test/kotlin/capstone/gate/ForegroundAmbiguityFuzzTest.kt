package capstone.gate

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 앞 앱 창(foregroundAppWindow)이 애매한 캡처 퍼즈. 전환 순간이나 멀티 윈도우(분할 화면·팝업 보기)에서는
 * 활성·포커스 표시가 둘 이상의 APPLICATION 창에 붙거나, 창 id가 겹친 캡처가 올 수 있다. 창 목록 순서는
 * getWindows가 정하므로 gate가 기댈 수 없다.
 *
 * 불변식(오라클):
 * ① 같은 창들을 어떤 순서로 넘겨도 check·checkAfter의 판정(ALLOW/BLOCK, 잠금 여부)이 같다.
 * ② 앞 앱 후보(활성+포커스 APPLICATION 창, 없으면 활성 APPLICATION 창)가 정확히 하나이고 창 id가 겹치지 않을 때만 ALLOW.
 *    애매하면 어느 창이 앞인지 모르므로 막는다(D35 fail-closed). 잠그지는 않는다 — 다음 캡처가 정한다(I-88).
 */
class ForegroundAmbiguityFuzzTest {
    private val pkgs = listOf(Fx.PKG, GatePolicy.MOCK_APP, "com.kakao.talk", "com.android.chrome", Fx.OWN)

    private fun screen(r: Random): UiNode = Fx.group(
        Fx.text("열차 조회"),
        *Array(r.nextInt(3, 7)) { i -> Fx.group(Fx.text("KTX ${100 + i}"), Fx.text("09:${10 + i}")) },
        Fx.button("조회하기", b = Bounds(0, 1800, 1080, 1950)),
    )

    private data class Flags(val active: Boolean, val focused: Boolean)

    private fun flags(r: Random): Flags = when (r.nextInt(4)) {
        0 -> Flags(true, true)
        1 -> Flags(true, false)
        2 -> Flags(false, true)
        else -> Flags(false, false)
    }

    private fun appWindow(id: Int, pkg: String, f: Flags, root: UiNode) =
        UiWindow(id, WindowType.APPLICATION, pkg, active = f.active, focused = f.focused, root = root)

    /** 대상 창(허용 앱) + 0~2개의 다른 APPLICATION 창 + 시스템·키보드. 다른 창 id는 20% 확률로 대상 창과 겹친다. */
    private fun windows(r: Random, targetFlags: Flags = flags(r)): Pair<List<UiWindow>, UiWindow> {
        val main = appWindow(1, Fx.PKG, targetFlags, screen(r))
        val others = List(r.nextInt(0, 3)) { i ->
            val id = if (r.nextInt(5) == 0) 1 else 30 + i
            appWindow(id, pkgs.random(r), flags(r), screen(r))
        }
        val extras = listOfNotNull(Fx.systemBar(), if (r.nextBoolean()) Fx.ime() else null)
        return (listOf(main) + others + extras) to main
    }

    private fun candidates(ws: List<UiWindow>): List<UiWindow> {
        val apps = ws.filter { it.type == WindowType.APPLICATION }
        return apps.filter { it.active && it.focused }.ifEmpty { apps.filter { it.active } }
    }

    private fun unambiguous(ws: List<UiWindow>, main: UiWindow): Boolean =
        ws.map { it.id }.toSet().size == ws.size && candidates(ws).singleOrNull() === main

    private fun outcome(g: Gate, d: Decision) = (if (d.allowed) "ALLOW" else "BLOCK") + "|lock=" + (g.lockedReason != null)

    @Test
    fun `check 판정은 창 순서와 무관하고 앞 앱이 하나로 정해질 때만 허용한다`() {
        val violations = mutableListOf<String>()
        for (seed in 500_000 until 503_000) {
            val r = Random(seed)
            val (ws, main) = windows(r)
            val req = Fx.click(main, Fx.keyOfText(main, "조회하기"))
            val results = List(6) { ws.shuffled(r) }.map { order ->
                val g = Fx.gate()
                outcome(g, g.check(Snapshot(0, order, screen = "S1"), req))
            }
            val allowedSome = results.any { it.startsWith("ALLOW") }
            when {
                results.toSet().size > 1 -> violations += "seed=$seed order_dependent=${results.toSet()}"
                allowedSome && !unambiguous(ws, main) -> violations += "seed=$seed allow_ambiguous=${ws.map { "${it.id}:${it.packageName}:${it.active}/${it.focused}" }}"
            }
        }
        if (violations.isNotEmpty()) fail("${violations.size}건 위반\n" + violations.take(15).joinToString("\n"))
    }

    @Test
    fun `checkAfter 판정·잠금은 창 순서와 무관하고 애매한 캡처는 잠그지 않고 막는다`() {
        val violations = mutableListOf<String>()
        for (seed in 503_000 until 506_000) {
            val r = Random(seed)
            val before = Fx.app(screen(r))
            val beforeSnap = Fx.snap(before, Fx.systemBar(), screen = "S1")
            val req = Fx.click(before, Fx.keyOfText(before, "조회하기"))
            val (ws, _) = windows(r)
            val results = List(6) { ws.shuffled(r) }.map { order ->
                val g = Fx.gate()
                check(g.check(beforeSnap, req).allowed)
                outcome(g, g.checkAfter(beforeSnap, Snapshot(1, order, screen = "S2"), req))
            }
            val ambiguous = candidates(ws).size != 1 || ws.map { it.id }.toSet().size != ws.size
            when {
                results.toSet().size > 1 -> violations += "seed=$seed order_dependent=${results.toSet()}"
                ambiguous && results.first() != "BLOCK|lock=false" -> violations += "seed=$seed ambiguous=${results.first()}"
            }
        }
        if (violations.isNotEmpty()) fail("${violations.size}건 위반\n" + violations.take(15).joinToString("\n"))
    }

    @Test
    fun `애매한 캡처 뒤 처음 하나로 정해진 앞 앱이 다른 앱이면 잠근다`() {
        val main = Fx.app(screen(Random(1)))
        val beforeSnap = Fx.snap(main, screen = "S1")
        val req = Fx.click(main, Fx.keyOfText(main, "조회하기"))
        val g = Fx.gate()
        assertTrue(g.check(beforeSnap, req).allowed)
        val foreign = appWindow(2, "com.kakao.talk", Flags(true, true), screen(Random(2)))
        val ambiguous = Snapshot(1, listOf(main, foreign), screen = "S1")
        val d = g.checkAfter(beforeSnap, ambiguous, req)
        assertEquals(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, (d as Decision.Block).reason)
        assertNull(g.lockedReason)
        // 다음 캡처에서 앞 앱이 카카오톡 하나로 정해지면 실행 뒤 전환이므로 잠근다.
        g.check(Snapshot(2, listOf(foreign), screen = "S1"), req)
        assertEquals(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, g.lockedReason)
    }

    @Test
    fun `창 id가 겹친 캡처는 대상이 앞 앱 창이어도 막는다`() {
        val main = Fx.app(screen(Random(3)))
        val twin = appWindow(1, "com.kakao.talk", Flags(false, false), screen(Random(4)))
        val req = Fx.click(main, Fx.keyOfText(main, "조회하기"))
        for (order in listOf(listOf(main, twin), listOf(twin, main))) {
            val d = Fx.gate().check(Snapshot(0, order, screen = "S1"), req)
            assertEquals(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, (d as Decision.Block).reason, "$order")
        }
    }
}
