package capstone.gate.exec

import capstone.gate.ActionRequest
import capstone.gate.BlockReason
import capstone.gate.Bounds
import capstone.gate.ConfirmMatcher
import capstone.gate.Decision
import capstone.gate.ExecResult
import capstone.gate.Gate
import capstone.gate.GatePolicy
import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.WindowType
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 거짓말하는 raw 실행기 퍼즈. 실기기의 performAction·dispatchGesture는 false를 돌려주고도 실제로는
 * 동작했거나(앱이 클릭을 처리하고 false 반환), 효과를 낸 뒤 바인더 예외를 던질 수 있다.
 * GatedExecutor는 raw 결과를 믿지 않아야 한다.
 *
 * 불변식(오라클은 기기의 실제 화면만 본다):
 *  ① raw 호출 순간 기기의 앞 창이 gate가 승인한 그 트리(treeHash)여야 한다 — 바뀐 화면에 폴백 탭 0건.
 *  ② 실제로 일어난 실행이 앞 앱을 다른 패키지로 바꿨으면 unlockByUser 전까지 ALLOW 0건(I-12·I-88).
 */
class LyingActuatorFuzzTest {
    private val home = GatePolicy.KORAIL_PLUS
    private val policy = GatePolicy(setOf(home, GatePolicy.MOCK_APP), ownPackage = "capstone.app")
    private val foreign = listOf("com.android.chrome", "com.sec.android.app.launcher", "com.kakao.talk", "capstone.app")

    private fun text(t: String, y: Int) = UiNode(className = "android.widget.TextView", text = t, bounds = Bounds(0, y, 1000, y + 80))
    private fun button(t: String, b: Bounds) = UiNode(className = "android.widget.Button", text = t, clickable = true, bounds = b)
    private val spot = Bounds(300, 2000, 800, 2150)

    private fun app(pkg: String, version: Int, confirmAtSpot: Boolean): Snapshot {
        val rows = (0 until 24).map { text("KTX ${100 + it + version} 09:${10 + it}", 200 + it * 70) }
        val action = if (confirmAtSpot) button("예매하기", spot) else button("조회하기", spot)
        val root = UiNode(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2400), children = rows + action)
        val sys = UiWindow(9, WindowType.SYSTEM, "com.android.systemui", root = text("12:00", 0))
        return Snapshot(0, listOf(UiWindow(1, WindowType.APPLICATION, pkg, active = true, focused = true, root = root), sys), screen = "S")
    }

    private val transient = Snapshot(0, emptyList(), screen = null)

    /** 실행이 실제로 일어났을 때 기기가 넘어가는 화면. */
    private fun effect(r: Random, cur: Snapshot): Snapshot = when (r.nextInt(10)) {
        in 0..2 -> app(home, r.nextInt(1, 1000), confirmAtSpot = false) // 목록 갱신(같은 앱, 다른 트리)
        3, 4 -> app(home, r.nextInt(1, 1000), confirmAtSpot = true) // 다음 화면: 같은 자리에 '예매하기'
        5 -> app(GatePolicy.MOCK_APP, r.nextInt(1, 1000), confirmAtSpot = false) // 다른 허용 앱
        6, 7 -> app(foreign.random(r), r.nextInt(1, 1000), confirmAtSpot = false)
        8 -> transient
        else -> cur // 효과는 있었지만 화면 변화 없음
    }

    private enum class Mode { HONEST_OK, HONEST_FAIL, LIE_FAIL, THROW_AFTER, THROW_BEFORE }

    private inner class Device(private val r: Random, var screen: Snapshot) : RawActuator, SnapshotSource {
        val violations = mutableListOf<String>()
        /** 실제 효과 뒤 아직 앞 앱을 못 본 실행 패키지, 그리고 전환 여부. */
        var pendingFrom: String? = null
        var tainted = false
        var lies = 0

        fun observe(s: Snapshot) {
            val from = pendingFrom ?: return
            val pkg = s.foregroundAppWindow?.packageName ?: return
            pendingFrom = null
            if (pkg != from) tainted = true
        }

        private fun act(kind: String, ref: NodeRef?): Boolean {
            if (ref != null) {
                val w = screen.window(ref.windowId)
                if (w == null || w.treeHash != ref.treeHash) violations += "$kind on changed screen"
                else w.nodes[ref.key]?.let { n -> if (ConfirmMatcher.hardReason(n) != null) violations += "$kind on confirm" }
            }
            val mode = Mode.entries[r.nextInt(Mode.entries.size)]
            if (mode == Mode.THROW_BEFORE) throw IllegalStateException("binder died")
            if (mode == Mode.HONEST_FAIL) return false
            val from = screen.foregroundAppWindow?.packageName
            screen = effect(r, screen)
            if (from != null && pendingFrom == null) pendingFrom = from
            observe(screen)
            if (mode != Mode.HONEST_OK) lies++
            if (mode == Mode.THROW_AFTER) throw IllegalStateException("DeadObjectException")
            return mode == Mode.HONEST_OK
        }

        override fun click(ref: NodeRef) = act("click", ref)
        override fun setText(ref: NodeRef, text: String) = act("setText", ref)
        override fun scroll(ref: NodeRef, forward: Boolean) = act("scroll", ref)
        override fun imeEnter(ref: NodeRef) = act("imeEnter", ref)
        override fun tapCenter(ref: NodeRef) = act("tap", ref)
        override fun back() = act("back", null)
        override fun capture(): Snapshot = screen
    }

    private fun clickOf(s: Snapshot): ActionRequest.Click? {
        val w = s.foregroundAppWindow ?: return null
        val k = w.nodes.entries.firstOrNull { it.value.text == "조회하기" }?.key ?: return null
        return ActionRequest.Click(NodeRef.of(w, k))
    }

    @Test
    fun `raw 결과를 믿지 않는다 - 바뀐 화면 실행 0건, 실제 전환 뒤 ALLOW 0건`() {
        val stale = mutableListOf<String>()
        val unlocked = mutableListOf<String>()
        var allows = 0
        var lies = 0
        for (seed in 270_000 until 273_000) {
            val r = Random(seed)
            val device = Device(r, app(home, 0, confirmAtSpot = false))
            val gate = Gate(policy)
            val exec = GatedExecutor(gate, device, device)
            var violated = false
            repeat(r.nextInt(4, 12)) { step ->
                if (violated) return@repeat
                val before = device.capture()
                device.observe(before)
                val req = clickOf(before)
                if (req == null) {
                    // 사용자가 돌아가거나 앱이 스스로 복귀: 실행이 아니므로 전환 판단에 넣지 않는다.
                    device.screen = app(if (r.nextBoolean()) home else GatePolicy.MOCK_APP, r.nextInt(1, 1000), confirmAtSpot = false)
                    return@repeat
                }
                val wasTainted = device.tainted
                val probe = gate.check(before, req)
                if (probe is Decision.Allow && wasTainted) {
                    unlocked += "seed=$seed step=$step"
                    violated = true
                    return@repeat
                }
                if (probe is Decision.Allow) allows++
                val n = device.violations.size
                runCatching { exec.execute(before, req) }.onFailure { fail("execute 예외 seed=$seed: $it") }
                if (device.violations.size > n) {
                    stale += "seed=$seed step=$step ${device.violations.drop(n)}"
                    violated = true
                }
            }
            lies += device.lies
        }
        if (stale.isNotEmpty() || unlocked.isNotEmpty()) {
            fail("바뀐 화면 실행 ${stale.size}건 ${stale.take(5)} / 전환 뒤 ALLOW ${unlocked.size}건 ${unlocked.take(5)}")
        }
        assertTrue(lies > 3_000, "거짓 보고 사례가 너무 적음: $lies")
        assertTrue(allows > 3_000, "정상 실행이 너무 적음(과잉 차단?): $allows")
    }

    @Test
    fun `CLICK이 false를 내고도 화면을 바꿨으면 폴백 탭하지 않는다`() {
        val s1 = app(home, 0, confirmAtSpot = false)
        val s2 = app(home, 1, confirmAtSpot = true)
        val taps = mutableListOf<String>()
        var screen = s1
        val act = object : RawActuator {
            override fun click(ref: NodeRef): Boolean { screen = s2; return false }
            override fun setText(ref: NodeRef, text: String) = false
            override fun scroll(ref: NodeRef, forward: Boolean) = false
            override fun imeEnter(ref: NodeRef) = false
            override fun tapCenter(ref: NodeRef): Boolean { taps += ref.key; return true }
            override fun back() = false
        }
        val r = GatedExecutor(Gate(policy), act) { screen }.execute(s1, clickOf(s1)!!)
        assertEquals(emptyList(), taps)
        assertIs<ExecResult.Blocked>(r)
        assertEquals(BlockReason.FAIL_CLOSED_SCREEN_SIGNATURE, r.decision.reason)
    }

    @Test
    fun `예외를 던졌어도 다른 앱으로 넘어갔으면 잠근다`() {
        val s1 = app(home, 0, confirmAtSpot = false)
        var screen = s1
        val act = object : RawActuator {
            override fun click(ref: NodeRef): Boolean { screen = app("com.android.chrome", 1, false); throw IllegalStateException("DeadObject") }
            override fun setText(ref: NodeRef, text: String) = false
            override fun scroll(ref: NodeRef, forward: Boolean) = false
            override fun imeEnter(ref: NodeRef) = false
            override fun tapCenter(ref: NodeRef) = false
            override fun back() = false
        }
        val gate = Gate(policy)
        val r = GatedExecutor(gate, act) { screen }.execute(s1, clickOf(s1)!!)
        assertIs<ExecResult.Blocked>(r)
        assertEquals(BlockReason.FAIL_CLOSED_PACKAGE_MISMATCH, gate.lockedReason)
        val back = app(home, 2, confirmAtSpot = false)
        assertIs<Decision.Block>(gate.check(back, clickOf(back)!!))
    }
}
