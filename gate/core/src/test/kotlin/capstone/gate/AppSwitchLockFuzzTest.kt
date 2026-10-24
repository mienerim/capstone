package capstone.gate

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 실행 뒤 앱 전환 잠금(I-12) 퍼즈. 한 실행 안에서 check → (실행) → checkAfter를 반복하며
 * gate에 넘기는 화면만 바꾼다. 실행 직후 캡처는 전환 애니메이션 중이라 앞 앱 창이 없을 수 있다
 * (빈 창 목록·시스템 창·키보드만·비활성 앱 창).
 *
 * 불변식(오라클): 실행한 뒤 gate가 처음으로 '앞 앱'을 보는 화면이 실행한 패키지와 다르면
 * (다른 허용 앱이든 밖의 앱이든 우리 앱이든) 그 실행은 예상 밖 앱 전환이고, 그 뒤 gate는
 * 사용자가 넘겨받기(unlockByUser) 전까지 ALLOW하지 않는다. 앞 앱이 없는 캡처는 판단을 미룰 뿐 지우지 않는다.
 */
class AppSwitchLockFuzzTest {
    private val foreignPackages = listOf("com.android.chrome", "com.sec.android.app.launcher", "com.kakao.talk", Fx.OWN)
    private val allowed = listOf(Fx.PKG, GatePolicy.MOCK_APP)

    private fun screen(r: Random): UiNode = Fx.group(
        Fx.text("열차 조회"),
        *Array(r.nextInt(8, 11)) { i -> Fx.group(Fx.text("KTX ${100 + i}"), Fx.text("09:${10 + i}")) },
        Fx.button("조회하기"),
    )

    /** 앞 앱 창이 없는 전환 중 캡처. */
    private fun transient(r: Random): Snapshot = when (r.nextInt(4)) {
        0 -> Fx.snap(screen = null)
        1 -> Fx.snap(Fx.systemBar(), screen = null)
        2 -> Fx.snap(Fx.ime(), Fx.systemBar(), screen = null)
        else -> Fx.snap(
            UiWindow(1, WindowType.APPLICATION, allowed.random(r), active = false, focused = false, root = screen(r)),
            Fx.systemBar(),
            screen = null,
        )
    }

    private fun app(pkg: String, r: Random): Snapshot =
        Fx.snap(Fx.app(screen(r), pkg = pkg), Fx.systemBar(), screen = "S1", err = if (r.nextInt(10) == 0) 3 else null)

    /** gate에 넘길 다음 화면. 대부분 같은 앱, 가끔 전환·전환 중. */
    private fun next(r: Random, home: String): Snapshot = when (r.nextInt(10)) {
        in 0..4 -> app(home, r)
        5, 6 -> transient(r)
        7 -> app(allowed.first { it != home }, r)
        else -> app(foreignPackages.random(r), r)
    }

    private fun clickOf(s: Snapshot): ActionRequest.Click? {
        val w = s.foregroundAppWindow ?: return null
        val k = w.nodes.entries.firstOrNull { it.value.text == "조회하기" }?.key ?: return null
        return ActionRequest.Click(Fx.ref(w, k))
    }

    /** 오라클: 실행 뒤 아직 앞 앱을 못 본 패키지. */
    private class Oracle {
        var pendingFrom: String? = null
        var tainted = false
        fun observe(s: Snapshot) {
            val from = pendingFrom ?: return
            val pkg = s.foregroundAppWindow?.packageName ?: return
            pendingFrom = null
            if (pkg != from) tainted = true
        }
    }

    @Test
    fun `실행 뒤 처음 보이는 앞 앱이 다르면 전환 중 캡처를 거쳐도 ALLOW 0건`() {
        val violations = mutableListOf<String>()
        var viaTransient = 0
        var cleanAllows = 0
        for (seed in 120_000 until 123_000) {
            val r = Random(seed)
            val home = allowed.random(r)
            val gate = Gate(Fx.policy)
            val oracle = Oracle()
            var sawTransientPending = false
            var before = app(home, r)
            var violated = false
            repeat(r.nextInt(4, 13)) { step ->
                if (violated) return@repeat
                oracle.observe(before)
                val req = clickOf(before)
                val d = if (req == null) null else gate.check(before, req)
                if (d is Decision.Allow) {
                    if (oracle.tainted) {
                        violations += "seed=$seed step=$step via=${if (sawTransientPending) "transient" else "direct"}"
                        violated = true
                        return@repeat
                    }
                    cleanAllows++
                    val after = next(r, home)
                    oracle.pendingFrom = before.foregroundAppWindow!!.packageName
                    if (after.foregroundAppWindow == null) sawTransientPending = true
                    oracle.observe(after)
                    gate.checkAfter(before, after, req!!)
                    before = if (after.foregroundAppWindow == null || r.nextBoolean()) next(r, home) else after
                } else {
                    before = next(r, home)
                }
            }
            if (oracle.tainted && sawTransientPending) viaTransient++
        }
        if (violations.isNotEmpty()) {
            val byVia = violations.groupingBy { it.substringAfter("via=") }.eachCount()
            fail("${violations.size}건 위반($byVia), 처음 10건: ${violations.take(10)}")
        }
        assertTrue(viaTransient > 200, "전환 중 캡처를 거친 전환 사례가 너무 적음: $viaTransient")
        assertTrue(cleanAllows > 3_000, "정상 실행이 너무 적음(과잉 차단?): $cleanAllows")
    }

    @Test
    fun `전환 중 캡처 뒤 다른 앱이 보였다가 돌아와도 잠금 유지`() {
        val r = Random(1)
        val gate = Gate(Fx.policy)
        val s1 = app(Fx.PKG, Random(2)).copy(screenshotError = null)
        val req = clickOf(s1)!!
        assertTrue(gate.check(s1, req) is Decision.Allow)
        val mid = gate.checkAfter(s1, Fx.snap(screen = null), req)
        assertTrue(mid is Decision.Block && mid.reason == BlockReason.FAIL_CLOSED_EMPTY_TREE && "lock" !in mid.signals, "전환 중은 잠그지 않음: $mid")
        val chrome = app("com.android.chrome", r)
        val d1 = gate.check(chrome, clickOf(chrome)!!)
        assertTrue(d1 is Decision.Block && d1.reason == BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH && "lock" in d1.signals, "다른 앱이 보이면 잠금: $d1")
        val back = app(Fx.PKG, Random(3)).copy(screenshotError = null)
        val d2 = gate.check(back, clickOf(back)!!)
        assertTrue(d2 is Decision.Block && "locked" in d2.signals, "돌아와도 잠금: $d2")
        gate.unlockByUser()
        assertTrue(gate.check(back, clickOf(back)!!) is Decision.Allow, "사용자 넘겨받기 뒤에는 허용")
    }

    @Test
    fun `전환 중 캡처 뒤 같은 앱이 보이면 그대로 진행`() {
        val gate = Gate(Fx.policy)
        val s1 = app(Fx.PKG, Random(4)).copy(screenshotError = null)
        val req = clickOf(s1)!!
        assertTrue(gate.check(s1, req) is Decision.Allow)
        gate.checkAfter(s1, Fx.snap(Fx.ime(), Fx.systemBar(), screen = null), req)
        val s2 = app(Fx.PKG, Random(5)).copy(screenshotError = null)
        assertEquals(Decision.Allow(), gate.check(s2, clickOf(s2)!!))
        // 확인이 끝났으므로 이후 다른 앱은 지금처럼 차단만(실행 전 관측은 잠그지 않음).
        val launcher = app("com.sec.android.app.launcher", Random(6))
        val d = gate.check(launcher, clickOf(launcher)!!)
        assertTrue(d is Decision.Block && d.reason == BlockReason.PACKAGE_NOT_ALLOWED && "lock" !in d.signals, "$d")
    }
}
