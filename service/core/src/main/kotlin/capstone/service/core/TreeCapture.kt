package capstone.service.core

import capstone.gate.NodeRef
import capstone.gate.Snapshot
import capstone.gate.UiNode
import capstone.gate.UiWindow

/**
 * 한 번의 캡처 결과: gate·agent-core가 보는 [snapshot]과, NodeRef 키 → 살아 있는 노드 손잡이.
 * 손잡이는 service 밖으로 나가지 않는다(D36: 밖으로는 NodeRef만).
 */
class Frame internal constructor(
    val snapshot: Snapshot,
    private val handles: Map<Int, Map<String, A11yNode>>,
    /** 한도 초과·순회 중 변경으로 루트를 비운 창 id(I-41). */
    val droppedWindows: Set<Int>,
) {
    /** treeHash가 다르면 낡은 참조라 null(D18 ④ 행동 재바인딩). */
    fun resolve(ref: NodeRef): Pair<UiNode, A11yNode>? {
        val window = snapshot.window(ref.windowId) ?: return null
        if (window.treeHash != ref.treeHash) return null
        val node = window.nodes[ref.key] ?: return null
        val handle = handles[ref.windowId]?.get(ref.key) ?: return null
        return node to handle
    }
}

/**
 * 접근성 창 목록 → [Snapshot]. 트리를 조용히 자르지 않는다: 한도를 넘거나 순회 중 자식이 사라지면
 * 그 창의 루트를 통째로 비워 gate가 빈 트리(fail-closed)로 막게 한다. 비밀번호 칸의 text는 읽지 않는다.
 */
class TreeCapture(
    private val maxNodesPerWindow: Int = 3000,
    private val maxDepth: Int = 128,
) {
    fun capture(windows: List<A11yWindow>, timestampMs: Long, screenshotError: Int? = null): Frame {
        val uiWindows = ArrayList<UiWindow>(windows.size)
        val handles = HashMap<Int, Map<String, A11yNode>>()
        val dropped = HashSet<Int>()
        for (w in windows) {
            val root = try {
                w.root()
            } catch (_: RuntimeException) {
                null
            }
            val walked = root?.let { walk(it) }
            if (root != null && walked == null) dropped += w.id
            uiWindows += UiWindow(
                id = w.id,
                type = w.type,
                packageName = root?.packageName,
                active = w.active,
                focused = w.focused,
                root = walked?.first,
            )
            walked?.second?.let { handles[w.id] = it }
        }
        return Frame(Snapshot(timestampMs, uiWindows, screen = null, screenshotError = screenshotError), handles, dropped)
    }

    private class Overflow : RuntimeException()

    /** (트리, 키 → 손잡이). 실패하면 null. 키는 UiWindow.nodes와 같은 인덱스 경로다. */
    private fun walk(root: A11yNode): Pair<UiNode, Map<String, A11yNode>>? {
        val handles = LinkedHashMap<String, A11yNode>()
        fun build(n: A11yNode, key: String, depth: Int): UiNode {
            if (depth > maxDepth || handles.size >= maxNodesPerWindow) throw Overflow()
            handles[key] = n
            val children = (0 until n.childCount).map { i ->
                val c = n.child(i) ?: throw Overflow()
                build(c, "$key/$i", depth + 1)
            }
            return shallow(n).copy(children = children)
        }
        return try {
            build(root, "0", 0) to handles
        } catch (_: Overflow) {
            null
        } catch (_: RuntimeException) {
            // 실기기에서 순회 중 노드가 재활용되면 IllegalStateException이 날 수 있다.
            null
        }
    }

    companion object {
        /** 자식 없는 UiNode. [FrameActuator]가 실행 직전 같은 노드인지 비교할 때도 쓴다. */
        fun shallow(n: A11yNode): UiNode = UiNode(
            className = n.className,
            text = if (n.password) null else n.text,
            hint = n.hint,
            contentDescription = n.contentDescription,
            stateDescription = n.stateDescription,
            paneTitle = n.paneTitle,
            viewId = n.viewId,
            bounds = n.bounds,
            clickable = n.clickable,
            longClickable = n.longClickable,
            editable = n.editable,
            scrollable = n.scrollable,
            enabled = n.enabled,
            visible = n.visibleToUser,
            dataSensitive = n.dataSensitive,
        )
    }
}
