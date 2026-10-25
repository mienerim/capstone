package capstone.service

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import capstone.gate.Bounds
import capstone.gate.WindowType
import capstone.service.core.A11yNode
import capstone.service.core.A11yWindow
import capstone.service.core.NodeOp

/** AccessibilityNodeInfo를 감싸기만 한다. 판단은 하지 않는다(미빌드: 10-26 팀 PC에서 컴파일 확인). */
internal class AndroidA11yNode(private val info: AccessibilityNodeInfo) : A11yNode {
    override val packageName: String? get() = info.packageName?.toString()
    override val className: String? get() = info.className?.toString()
    override val text: String? get() = info.text?.toString()
    override val hint: String? get() = info.hintText?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val stateDescription: String? get() = info.stateDescription?.toString()
    override val paneTitle: String? get() = info.paneTitle?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val bounds: Bounds
        get() = Rect().also(info::getBoundsInScreen).let { Bounds(it.left, it.top, it.right, it.bottom) }
    override val clickable: Boolean get() = info.isClickable
    override val longClickable: Boolean get() = info.isLongClickable
    override val editable: Boolean get() = info.isEditable
    override val scrollable: Boolean get() = info.isScrollable
    override val enabled: Boolean get() = info.isEnabled
    override val visibleToUser: Boolean get() = info.isVisibleToUser
    override val password: Boolean get() = info.isPassword
    override val dataSensitive: Boolean get() = info.isAccessibilityDataSensitive
    override val childCount: Int get() = info.childCount

    override fun child(index: Int): A11yNode? = info.getChild(index)?.let(::AndroidA11yNode)

    override fun refresh(): Boolean = info.refresh()

    override fun perform(op: NodeOp): Boolean = when (op) {
        NodeOp.Click -> info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        is NodeOp.SetText -> info.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, op.text) },
        )
        is NodeOp.Scroll -> info.performAction(
            if (op.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        )
        NodeOp.ImeEnter -> info.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }
}

internal class AndroidA11yWindow(private val info: AccessibilityWindowInfo) : A11yWindow {
    override val id: Int get() = info.id
    override val type: WindowType
        get() = when (info.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_SCREEN_DIVIDER
            AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> WindowType.MAGNIFICATION_OVERLAY
            else -> WindowType.UNKNOWN
        }
    override val active: Boolean get() = info.isActive
    override val focused: Boolean get() = info.isFocused
    override fun root(): A11yNode? = info.root?.let(::AndroidA11yNode)
}
