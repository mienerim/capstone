package capstone.service.core

import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow
import capstone.gate.exec.RawActuator
import capstone.gate.exec.SnapshotSource

/** 마지막 캡처를 들고 있다가 NodeRef를 해석한다. 캡처는 [SnapshotSource]로만 나간다. */
class FrameHolder(private val capture: () -> Frame) : SnapshotSource {
    @Volatile
    var last: Frame? = null
        private set

    override fun capture(): Snapshot = captureFrame().snapshot

    internal fun captureFrame(): Frame {
        val f = capture.invoke()
        last = f
        return f
    }
}

/**
 * [RawActuator] 구현(D36). 판단은 하지 않지만, 누르는 대상이 gate가 본 그 화면·그 노드인지만은 확인한다:
 * ① ref의 treeHash가 마지막 캡처와 같고 ② refresh 후 노드 필드가 캡처 때와 같아야 한다.
 * 클릭·IME 확인은 서브트리까지 같아야 하고(자식 라벨이 곧 버튼 라벨, G5), 중심 탭·IME 확인은 화면 전체를
 * 다시 캡처해 모든 창이 그대로일 때만 한다(탭은 좌표라 위를 덮은 다른 노드·창이 받는다, I-86).
 * 어긋나면 실행하지 않고 false(낡은 참조) — 상태머신이 다시 관측해서 판단한다.
 */
class FrameActuator(
    private val frames: FrameHolder,
    private val device: DeviceOps,
) : RawActuator {

    override fun click(ref: NodeRef): Boolean = onNode(ref, deep = true) { it.perform(NodeOp.Click) }
    override fun setText(ref: NodeRef, text: String): Boolean = onNode(ref, deep = false) { it.perform(NodeOp.SetText(text)) }
    override fun scroll(ref: NodeRef, forward: Boolean): Boolean = onNode(ref, deep = false) { it.perform(NodeOp.Scroll(forward)) }

    // IME 확인은 화면의 어느 확정 버튼으로도 제출될 수 있어 gate가 화면 전체를 본다 — 실행도 화면 전체가 그대로일 때만.
    override fun imeEnter(ref: NodeRef): Boolean = onSameScreen(ref) { it.perform(NodeOp.ImeEnter) }

    override fun tapCenter(ref: NodeRef): Boolean = onSameScreen(ref) { live ->
        val b = live.bounds
        !b.isEmpty && device.tap(b.centerX, b.centerY)
    }

    override fun back(): Boolean = device.back()

    /**
     * 화면 전체를 다시 캡처해 모든 창이 그대로일 때만 [act]. 탭·IME 확인은 드물어서 재캡처 비용보다
     * 덮인 확정 버튼을 누르는 위험이 크다. 재캡처와 실행 사이의 아주 짧은 틈은 남는다 — checkAfter가 잠금으로 받는다.
     */
    private inline fun onSameScreen(ref: NodeRef, act: (A11yNode) -> Boolean): Boolean {
        val seen = frames.last ?: return false
        if (seen.resolve(ref) == null) return false
        val now = try {
            frames.captureFrame()
        } catch (_: RuntimeException) {
            return false
        }
        if (!sameScreen(seen.snapshot.windows, now.snapshot.windows)) return false
        return onNode(ref, deep = true, act)
    }

    private inline fun onNode(ref: NodeRef, deep: Boolean, act: (A11yNode) -> Boolean): Boolean {
        val (seen, live) = frames.last?.resolve(ref) ?: return false
        if (!live.refresh()) return false
        val same = try {
            if (deep) sameSubtree(seen, live) else sameNode(seen, live)
        } catch (_: RuntimeException) {
            false // 비교 중 노드가 재활용됨 = 화면이 바뀌는 중
        }
        if (!same) return false
        return act(live)
    }

    // 입력·스크롤은 자식을 비교하지 않는다. 목록 아래쪽이 늘어나는 정도로는 같은 노드로 본다.
    private fun sameNode(seen: UiNode, live: A11yNode): Boolean =
        seen.copy(children = emptyList()) == TreeCapture.shallow(live)

    private fun sameSubtree(seen: UiNode, live: A11yNode): Boolean {
        if (!sameNode(seen, live) || live.childCount != seen.children.size) return false
        return seen.children.withIndex().all { (i, c) -> live.child(i)?.let { sameSubtree(c, it) } == true }
    }

    private fun sameScreen(a: List<UiWindow>, b: List<UiWindow>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.id == y.id && x.active == y.active && x.focused == y.focused && x.treeHash == y.treeHash
        }
}
