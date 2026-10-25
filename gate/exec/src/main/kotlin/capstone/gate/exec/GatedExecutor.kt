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
 *
 * raw의 false·예외는 '실행 안 됨'을 보장하지 않는다(앱이 클릭을 처리하고 false, 효과 뒤 바인더 예외).
 * 그래서 시도했으면 결과와 관계없이 checkAfter를 거치고, 폴백 탭은 다시 캡처한 화면으로 판정한다(I-92).
 */
class GatedExecutor(
    private val gate: Gate,
    private val actuator: RawActuator,
    private val snapshots: SnapshotSource,
) : ActionExecutor {

    override fun execute(before: Snapshot, request: ActionRequest): ExecResult {
        val pre = gate.check(before, request)
        if (pre is Decision.Block) return ExecResult.Blocked(pre)

        var from = before
        var executed = request
        var ok = runRaw(request)
        if (!ok && request is ActionRequest.Click) {
            val seen = snapshots.capture()
            val after = gate.checkAfter(before, seen, request)
            if (after is Decision.Block) return ExecResult.Blocked(after)
            // 화면이 바뀌었으면 treeHash가 달라 gate가 막는다: 다음 화면의 같은 자리를 탭하지 않는다.
            val fallback = ActionRequest.TapNodeCenter(request.target, request.expectedPackage, request.expectedScreen)
            val fb = gate.check(seen, fallback)
            if (fb is Decision.Block) return ExecResult.Blocked(fb)
            from = seen
            executed = fallback
            ok = runRaw(fallback)
        }

        val after = gate.checkAfter(from, snapshots.capture(), executed)
        return when {
            after is Decision.Block -> ExecResult.Blocked(after)
            ok -> ExecResult.Done
            else -> ExecResult.Failed("raw ${executed.kind} failed")
        }
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
