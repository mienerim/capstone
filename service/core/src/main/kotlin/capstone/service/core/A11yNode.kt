package capstone.service.core

import capstone.gate.Bounds
import capstone.gate.WindowType

/**
 * 살아 있는 접근성 노드의 보기. Android에서는 AccessibilityNodeInfo를 감싸고, 테스트에서는 가짜를 쓴다.
 * 읽기는 캡처 시점 값이고, [refresh]를 부르면 실기기 상태로 다시 읽는다.
 */
interface A11yNode {
    val packageName: String?
    val className: String?
    val text: String?
    val hint: String?
    val contentDescription: String?
    val stateDescription: String?
    val paneTitle: String?
    val viewId: String?
    val bounds: Bounds
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val enabled: Boolean
    val visibleToUser: Boolean
    val password: Boolean
    val dataSensitive: Boolean
    val childCount: Int

    /** null = 순회 중 노드가 사라짐(트리가 바뀌는 중). */
    fun child(index: Int): A11yNode?

    /** 실기기 노드를 다시 읽는다. 노드가 사라졌으면 false. */
    fun refresh(): Boolean

    /** performAction 하나. 판단하지 않고 그대로 부른다(판단은 gate). */
    fun perform(op: NodeOp): Boolean
}

sealed interface NodeOp {
    data object Click : NodeOp
    data class SetText(val text: String) : NodeOp {
        override fun toString(): String = "SetText(<${text.length} chars>)"
    }
    data class Scroll(val forward: Boolean) : NodeOp
    data object ImeEnter : NodeOp
}

interface A11yWindow {
    val id: Int
    val type: WindowType
    val active: Boolean
    val focused: Boolean

    /** null = 루트를 얻지 못함(권한·로딩). */
    fun root(): A11yNode?
}

/** 노드에 묶이지 않는 기기 동작. 좌표 탭은 [FrameActuator]가 노드 bounds 중심으로만 부른다(D2). */
interface DeviceOps {
    fun tap(x: Int, y: Int): Boolean
    fun back(): Boolean
}
