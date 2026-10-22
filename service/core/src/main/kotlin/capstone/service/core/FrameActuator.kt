package capstone.service.core

import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.exec.RawActuator
import capstone.gate.exec.SnapshotSource

/** 마지막 캡처를 들고 있다가 NodeRef를 해석한다. 캡처는 [SnapshotSource]로만 나간다. */
class FrameHolder(private val capture: () -> Frame) : SnapshotSource {
    @Volatile
    var last: Frame? = null
        private set

    override fun capture(): Snapshot {
        val f = capture.invoke()
        last = f
        return f.snapshot
    }
}

/**
 * [RawActuator] 구현(D36). 판단은 하지 않지만, 누르는 대상이 gate가 본 그 노드인지만은 확인한다:
 * ① ref의 treeHash가 마지막 캡처와 같고 ② refresh 후 노드 필드가 캡처 때와 같아야 한다.
 * 둘 중 하나라도 어긋나면 실행하지 않고 false(낡은 참조) — 상태머신이 다시 관측해서 판단한다.
 */
class FrameActuator(
    private val frames: FrameHolder,
    private val device: DeviceOps,
) : RawActuator {

    override fun click(ref: NodeRef): Boolean = onNode(ref) { it.perform(NodeOp.Click) }
    override fun setText(ref: NodeRef, text: String): Boolean = onNode(ref) { it.perform(NodeOp.SetText(text)) }
    override fun scroll(ref: NodeRef, forward: Boolean): Boolean = onNode(ref) { it.perform(NodeOp.Scroll(forward)) }
    override fun imeEnter(ref: NodeRef): Boolean = onNode(ref) { it.perform(NodeOp.ImeEnter) }

    override fun tapCenter(ref: NodeRef): Boolean = onNode(ref) { live ->
        val b = live.bounds
        !b.isEmpty && device.tap(b.centerX, b.centerY)
    }

    override fun back(): Boolean = device.back()

    private inline fun onNode(ref: NodeRef, act: (A11yNode) -> Boolean): Boolean {
        val (seen, live) = frames.last?.resolve(ref) ?: return false
        if (!live.refresh()) return false
        if (!sameNode(seen, TreeCapture.shallow(live))) return false
        return act(live)
    }

    // 자식은 비교하지 않는다(shallow). 목록 아래쪽이 늘어나는 정도로는 같은 노드로 본다.
    private fun sameNode(seen: UiNode, now: UiNode): Boolean = seen.copy(children = emptyList()) == now
}
