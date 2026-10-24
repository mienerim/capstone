package capstone.gate.exec

import capstone.gate.ActionExecutor
import capstone.gate.ActionRequest
import capstone.gate.Decision
import capstone.gate.ExecResult
import capstone.gate.Gate
import capstone.gate.NodeRef
import capstone.gate.Snapshot

/**
 * service가 구현하는 raw 실행기(D36). performAction·dispatchGesture를 그대로 부를 뿐 판단하지 않는다.
 * 이 인터페이스를 가진 쪽(service)은 agent-core에서 보이지 않는다.
 */
interface RawActuator {
    /** ACTION_CLICK. 노드가 지원하지 않거나 실패하면 false. */
    fun click(ref: NodeRef): Boolean
    fun setText(ref: NodeRef, text: String): Boolean
    fun scroll(ref: NodeRef, forward: Boolean): Boolean
    fun imeEnter(ref: NodeRef): Boolean
    /** 노드 bounds 중심 1회 탭(dispatchGesture). 좌표만 받는 API는 두지 않는다(D2). */
    fun tapCenter(ref: NodeRef): Boolean
    fun back(): Boolean
}

/** 실행 직후 화면을 다시 읽는다. 구현은 디바운스(300~500ms 가설) 후 안정된 스냅샷을 준다. */
fun interface SnapshotSource {
    fun capture(): Snapshot
}

/**
 * 유일한 [ActionExecutor] 구현. check → raw 실행 → checkAfter.
 * CLICK이 실패하면 같은 노드의 중심 탭을 1회만, 다시 gate를 거쳐 시도한다(D2 폴백도 gate 안).
 */
class GatedExecutor(
    private val gate: Gate,
    private val actuator: RawActuator,
    private val snapshots: SnapshotSource,
) : ActionExecutor {

    override fun execute(before: Snapshot, request: ActionRequest): ExecResult {
        val pre = gate.check(before, request)
        if (pre is Decision.Block) return ExecResult.Blocked(pre)

        var executed = request
        var ok = runRaw(request)
        if (!ok && request is ActionRequest.Click) {
            val fallback = ActionRequest.TapNodeCenter(request.target, request.expectedPackage, request.expectedScreen)
            val fb = gate.check(before, fallback)
            if (fb is Decision.Block) return ExecResult.Blocked(fb)
            executed = fallback
            ok = runRaw(fallback)
        }
        if (!ok) return ExecResult.Failed("raw ${executed.kind} failed")

        val after = gate.checkAfter(before, snapshots.capture(), executed)
        return if (after is Decision.Block) ExecResult.Blocked(after) else ExecResult.Done
    }

    private fun runRaw(req: ActionRequest): Boolean = try {
        when (req) {
            is ActionRequest.Click -> actuator.click(req.target)
            is ActionRequest.SetText -> actuator.setText(req.target, req.text)
            is ActionRequest.Scroll -> actuator.scroll(req.target, req.forward)
            is ActionRequest.ImeEnter -> actuator.imeEnter(req.target)
            is ActionRequest.TapNodeCenter -> actuator.tapCenter(req.target)
            is ActionRequest.GlobalBack -> actuator.back()
        }
    } catch (_: RuntimeException) {
        false
    }
}
