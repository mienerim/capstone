package capstone.gate

/**
 * gate가 허용하는 액션 전체(D36 sealed). 좌표만 있는 탭은 없다: 탭도 노드 근거(target)가 있어야 한다(D2).
 * expectedPackage·expectedScreen은 매 행동 직전 재검증(D18 ④)에 쓴다.
 */
sealed interface ActionRequest {
    val expectedPackage: String?
    val expectedScreen: String?

    /** 대상 노드가 있는 액션. */
    sealed interface Targeted : ActionRequest {
        val target: NodeRef
    }

    data class Click(
        override val target: NodeRef,
        override val expectedPackage: String? = null,
        override val expectedScreen: String? = null,
    ) : Targeted

    /** 입력 텍스트는 증명 로그에 기록하지 않는다(D36). */
    data class SetText(
        override val target: NodeRef,
        val text: String,
        override val expectedPackage: String? = null,
        override val expectedScreen: String? = null,
    ) : Targeted {
        override fun toString(): String = "SetText(target=$target, text=<${text.length} chars>)"
    }

    data class Scroll(
        override val target: NodeRef,
        val forward: Boolean,
        override val expectedPackage: String? = null,
        override val expectedScreen: String? = null,
    ) : Targeted

    /** CLICK 실패 시 1회 허용되는 노드 bounds 중심 탭(D2). gate-exec 폴백이 만든다. */
    data class TapNodeCenter(
        override val target: NodeRef,
        override val expectedPackage: String? = null,
        override val expectedScreen: String? = null,
    ) : Targeted

    /** IME 엔터도 확정 버튼을 누를 수 있다(D36). */
    data class ImeEnter(
        override val target: NodeRef,
        override val expectedPackage: String? = null,
        override val expectedScreen: String? = null,
    ) : Targeted

    data class GlobalBack(
        override val expectedPackage: String? = null,
        override val expectedScreen: String? = null,
    ) : ActionRequest

    val kind: String
        get() = when (this) {
            is Click -> "click"
            is SetText -> "set_text"
            is Scroll -> "scroll"
            is TapNodeCenter -> "tap_center"
            is ImeEnter -> "ime_enter"
            is GlobalBack -> "back"
        }
}

/** D36 BlockReason enum. 이름은 증명 로그·보고서에 그대로 쓰이므로 바꾸지 않는다. */
enum class BlockReason {
    CONFIRM_NODE_TEXT,
    CONFIRM_NODE_DESC,
    CONFIRM_NODE_ID,
    CONFIRM_ANCESTOR_DELEGATE,
    CONFIRM_GESTURE_OVERLAP,
    PACKAGE_NOT_ALLOWED,
    FAIL_CLOSED_EMPTY_TREE,
    FAIL_CLOSED_TREE_COLLAPSE,
    FAIL_CLOSED_PACKAGE_MISMATCH,
    FAIL_CLOSED_SCREEN_SIGNATURE,
    FAIL_CLOSED_SCREENSHOT_ERROR,
    FAIL_CLOSED_INTERNAL_EXCEPTION,
    ;

    val isConfirm: Boolean get() = name.startsWith("CONFIRM_")
}

sealed interface Decision {
    /** 판단 근거·참고 신호. 부분 일치처럼 차단하지 않는 신호도 여기에 남는다. */
    val signals: List<String>

    data class Allow(override val signals: List<String> = emptyList()) : Decision

    data class Block(val reason: BlockReason, override val signals: List<String> = emptyList()) : Decision

    val allowed: Boolean get() = this is Allow
}

/** 실행 결과. 실행 자체는 gate-exec만 한다. */
sealed interface ExecResult {
    data object Done : ExecResult
    data class Blocked(val decision: Decision.Block) : ExecResult
    data class Failed(val message: String) : ExecResult
}

/**
 * agent-core가 볼 수 있는 유일한 실행 통로(D36). 구현은 gate-exec 하나뿐이다.
 * 호출자는 최신 스냅샷을 넘기고, 구현은 check → 실행 → checkAfter 순서를 지킨다.
 */
interface ActionExecutor {
    fun execute(before: Snapshot, request: ActionRequest): ExecResult
}
