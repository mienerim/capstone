package capstone.service.core

import capstone.gate.Bounds
import capstone.gate.NodeRef
import capstone.gate.UiNode
import capstone.gate.WindowType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 캡처 → 덤프 파이프라인 퍼즈(26회차, I-91). 실기기 접근성 트리가 할 수 있는 이상한 짓을 섞는다:
 * 필드 읽기 예외(재활용된 노드의 IllegalStateException), 자식 null·예외, 조상을 돌려주는 순환, 아주 깊거나 넓은 트리,
 * root() 예외·null, 비밀번호 칸, 허용목록 밖 앱 창(메신저), 병원 앱 창, 입력칸 값.
 *
 * 불변식:
 * ① capture는 예외를 던지지 않는다(접근성 서비스 스레드 크래시 = 실행·덤프 모두 멈춤)
 * ② 순회한 창은 정답 트리와 같거나, 루트를 비우고 droppedWindows에 든다(조용한 잘림 0)
 * ③ 비밀번호 칸 text는 스냅샷에 없다
 * ④ 허용목록 밖 창은 루트 패키지 말고는 읽지 않는다(D63 ②) — 자식·글자 필드 접근 0
 * ⑤ 덤프 줄에 밖 앱 글자·병원 앱 글자·입력칸 값·비밀번호가 없다
 * ⑥ 스냅샷의 모든 노드 키가 Frame.resolve로 같은 노드를 가리킨다
 */
class CapturePipelineFuzzTest {

    private val target = "capstone.mock"
    private val clinic = "com.example.clinic"
    private val messenger = "com.kakao.talk"
    private val keyboard = "com.samsung.android.honeyboard"
    private val targets = setOf(target, clinic)

    /** 필드 접근을 세고, 정해진 필드에서 예외를 던질 수 있는 가짜 노드. 고장은 매번 같아 정답 계산이 가능하다. */
    private class FuzzNode(
        val pkg: String?,
        val data: UiNode,
        val isPw: Boolean = false,
        val secret: String? = null,
    ) : A11yNode {
        val kids = ArrayList<FuzzNode?>()
        /** 이 자식 위치에서 예외를 던진다. */
        val throwingChild = HashSet<Int>()
        /** 이 이름의 필드를 읽으면 예외. */
        var throwOn: String? = null
        val reads = ArrayList<String>()

        private fun <T> read(name: String, v: T): T {
            reads += name
            if (throwOn == name) throw IllegalStateException("recycled")
            return v
        }

        override val packageName get() = read("packageName", pkg)
        override val className get() = read("className", data.className)
        override val text get() = read("text", if (isPw) secret else data.text)
        override val hint get() = read("hint", data.hint)
        override val contentDescription get() = read("contentDescription", data.contentDescription)
        override val stateDescription get() = read("stateDescription", data.stateDescription)
        override val paneTitle get() = read("paneTitle", data.paneTitle)
        override val viewId get() = read("viewId", data.viewId)
        override val bounds get() = read("bounds", data.bounds)
        override val clickable get() = read("clickable", data.clickable)
        override val longClickable get() = read("longClickable", data.longClickable)
        override val editable get() = read("editable", data.editable)
        override val scrollable get() = read("scrollable", data.scrollable)
        override val enabled get() = read("enabled", data.enabled)
        override val visibleToUser get() = read("visibleToUser", data.visible)
        override val dataSensitive get() = read("dataSensitive", data.dataSensitive)
        override val childCount get() = read("childCount", kids.size)

        override fun child(index: Int): A11yNode? {
            reads += "child"
            if (index in throwingChild) throw IllegalStateException("recycled child")
            return kids[index]
        }

        override val password: Boolean get() = read("password", isPw)
        override fun refresh(): Boolean = true
        override fun perform(op: NodeOp): Boolean = fail("캡처·덤프는 노드를 조작하지 않는다")
    }

    private class FuzzWindow(
        override val id: Int,
        val node: FuzzNode?,
        override val type: WindowType,
        override val active: Boolean,
        override val focused: Boolean,
        val rootThrows: Boolean = false,
    ) : A11yWindow {
        override fun root(): A11yNode? = if (rootThrows) throw IllegalStateException("window gone") else node
    }

    private val fields = listOf(
        "packageName", "className", "text", "hint", "contentDescription", "stateDescription", "paneTitle",
        "viewId", "bounds", "clickable", "editable", "enabled", "visibleToUser", "password", "dataSensitive", "childCount",
    )

    private class Gen(val rnd: Random, val secrets: MutableList<String>) {
        var n = 0
        fun secret(kind: String) = "$kind-SECRET-${n++}".also { secrets += it }
    }

    /** 창 하나의 트리. 반환: 루트. [faulty]면 고장을 하나 이상 심는다. */
    private fun tree(g: Gen, pkg: String, kind: String, faulty: Boolean): FuzzNode {
        val rnd = g.rnd
        val all = ArrayList<FuzzNode>()
        fun node(depth: Int): FuzzNode {
            val r = rnd.nextInt(10)
            val n = when {
                kind == "app" && r == 0 -> FuzzNode(pkg, UiNode(className = "android.widget.EditText", editable = true), isPw = true, secret = g.secret("PW"))
                kind == "app" && r == 1 -> FuzzNode(pkg, UiNode(className = "android.widget.EditText", text = g.secret("INPUT"), editable = true, clickable = true))
                kind == "other" || kind == "health" -> FuzzNode(pkg, UiNode(text = g.secret(kind.uppercase()), contentDescription = g.secret(kind.uppercase()), clickable = rnd.nextBoolean()))
                else -> FuzzNode(
                    pkg,
                    UiNode(
                        className = listOf("android.widget.TextView", "android.widget.Button", "android.view.ViewGroup").random(rnd),
                        text = listOf(null, "조회하기", "서울", "KTX 101 08:00", "예매하기", "").random(rnd),
                        viewId = if (rnd.nextBoolean()) "$pkg:id/v$depth" else null,
                        bounds = Bounds(rnd.nextInt(500), rnd.nextInt(1000), 500 + rnd.nextInt(580), 1000 + rnd.nextInt(1400)),
                        clickable = rnd.nextBoolean(),
                        visible = rnd.nextInt(8) != 0,
                    ),
                )
            }
            all += n
            if (depth < 6) repeat(if (depth == 0) 2 + rnd.nextInt(4) else rnd.nextInt(4)) { n.kids += node(depth + 1) }
            return n
        }
        val root = node(0)
        if (faulty) {
            val victim = all.random(rnd)
            when (rnd.nextInt(6)) {
                0 -> victim.throwOn = fields.random(rnd)
                1 -> root.throwOn = "packageName"
                2 -> if (victim.kids.isNotEmpty()) victim.kids[rnd.nextInt(victim.kids.size)] = null else victim.throwOn = "text"
                3 -> if (victim.kids.isNotEmpty()) victim.throwingChild += rnd.nextInt(victim.kids.size) else victim.throwOn = "bounds"
                4 -> victim.kids += root // 순환: 조상을 자식으로 돌려줌
                else -> { // 한도 초과: 아주 넓은 자식
                    repeat(3100) { victim.kids += FuzzNode(pkg, UiNode(text = "행 $it")) }
                }
            }
        }
        return root
    }

    /** 정답: 고장 없이 끝까지 읽히면 그 트리, 하나라도 고장·한도 초과면 null. 인터페이스가 아니라 원래 데이터로 계산한다. */
    private fun truth(root: FuzzNode, maxNodes: Int = 3000, maxDepth: Int = 128): UiNode? {
        var count = 0
        fun go(n: FuzzNode, depth: Int): UiNode? {
            if (depth > maxDepth || count >= maxNodes) return null
            count++
            // 비밀번호 칸의 text, 루트 아닌 노드의 패키지는 읽지 않으므로 거기 심은 고장은 드러나지 않는다.
            val hidden = (n.isPw && n.throwOn == "text") || (depth > 0 && n.throwOn == "packageName")
            if (n.throwOn != null && !hidden) return null
            val children = ArrayList<UiNode>()
            for (i in n.kids.indices) {
                if (i in n.throwingChild) return null
                val c = n.kids[i] ?: return null
                children += go(c, depth + 1) ?: return null
            }
            return n.data.copy(text = if (n.isPw) null else n.data.text, children = children)
        }
        return go(root, 0)
    }

    @Test
    fun `캡처·덤프 퍼즈 3000회 — 크래시·조용한 잘림·개인정보 누출 0`() {
        val violations = ArrayList<String>()
        var dropped = 0
        var kept = 0
        for (seed in 160000 until 163000) {
            val rnd = Random(seed)
            val secrets = ArrayList<String>()
            val g = Gen(rnd, secrets)
            val windows = ArrayList<FuzzWindow>()
            val kinds = HashMap<Int, String>()
            var id = 1
            fun add(kind: String, pkg: String, type: WindowType, active: Boolean, faulty: Boolean) {
                val root = tree(g, pkg, kind, faulty)
                val rootThrows = faulty && rnd.nextInt(10) == 0
                windows += FuzzWindow(id, if (rnd.nextInt(30) == 0) null else root, type, active, active, rootThrows)
                kinds[id++] = kind
            }
            val faultRate = 0.4
            add("app", target, WindowType.APPLICATION, true, rnd.nextDouble() < faultRate)
            if (rnd.nextBoolean()) add("other", messenger, WindowType.APPLICATION, false, rnd.nextDouble() < faultRate)
            if (rnd.nextInt(3) == 0) add("other", keyboard, WindowType.INPUT_METHOD, false, rnd.nextDouble() < faultRate)
            if (rnd.nextInt(3) == 0) add("health", clinic, WindowType.APPLICATION, false, rnd.nextDouble() < faultRate)
            windows.shuffle(rnd)

            val frame = try {
                TreeCapture(targetPackages = targets).capture(windows, seed.toLong())
            } catch (e: Throwable) {
                violations += "seed $seed: capture 예외 ${e::class.simpleName}"
                continue
            }
            val snap = frame.snapshot
            for (w in windows) {
                val uw = snap.window(w.id) ?: run { violations += "seed $seed: 창 ${w.id} 사라짐"; null } ?: continue
                val kind = kinds.getValue(w.id)
                val node = w.node
                if (w.rootThrows || node == null) {
                    if (uw.root != null) violations += "seed $seed: 루트 없는 창에 트리"
                    continue
                }
                if (kind == "other") {
                    if (uw.root != null) violations += "seed $seed: 밖 앱 창 순회"
                    val extra = node.reads.filter { it != "packageName" } + node.kids.filterNotNull().filter { it !== node }.flatMap { it.reads }
                    if (extra.isNotEmpty()) violations += "seed $seed: 밖 앱 창 필드 읽음 ${extra.distinct()}"
                    continue
                }
                val expected = if (node.throwOn == "packageName") null else truth(node)
                if (expected == null) {
                    if (uw.root != null || w.id !in frame.droppedWindows) violations += "seed $seed: 고장 난 창이 비워지지 않음(${kind} root=${uw.root != null} throwOn=${node.throwOn})"
                    else dropped++
                } else if (uw.root != expected) {
                    violations += "seed $seed: 트리 불일치(${kind})"
                } else {
                    kept++
                    for ((key, n) in uw.nodes) {
                        val r = frame.resolve(NodeRef(uw.id, key, uw.treeHash))
                        if (r == null || r.first != n) violations += "seed $seed: resolve 실패 $key"
                    }
                }
            }
            val pw = secrets.filter { it.startsWith("PW-") }
            val snapText = snap.windows.flatMap { it.nodes.values }.joinToString("\n") { it.toString() }
            pw.filter { it in snapText }.forEach { violations += "seed $seed: 비밀번호 스냅샷에 남음" }

            val out = StringBuilder()
            val rec = DumpRecorder(out, targets, DeviceInfo(35, null) { null }, "fz", healthPackages = setOf(clinic))
            try {
                rec.record(snap)
            } catch (e: Throwable) {
                violations += "seed $seed: 덤프 예외 ${e::class.simpleName}"
            }
            secrets.filter { it in out }.forEach { violations += "seed $seed: 덤프 누출 ${it.substringBefore("-SECRET")}" }
        }
        assertTrue(violations.isEmpty(), "${violations.size}건: " + violations.groupingBy { it.substringAfter(": ").substringBefore(" ") }.eachCount() + "\n" + violations.distinctBy { it.substringAfter(": ").take(12) }.take(15).joinToString("\n"))
        assertTrue(dropped > 300 && kept > 1000, "dropped=$dropped kept=$kept")
    }

    @Test
    fun `루트 패키지를 못 읽으면 크래시 대신 패키지 모름·빈 트리`() {
        val root = FuzzNode(target, UiNode(text = "조회하기")).apply { throwOn = "packageName" }
        val frame = TreeCapture(targetPackages = targets).capture(listOf(FuzzWindow(1, root, WindowType.APPLICATION, true, true)), 0)
        val w = frame.snapshot.window(1)!!
        assertNull(w.packageName)
        assertNull(w.root)
        assertEquals(setOf(1), frame.droppedWindows)
    }
}
