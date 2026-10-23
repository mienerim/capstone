package capstone.agent

import capstone.gate.UiWindow

/** 슬롯 값(D30 v1 이름: origin·destination·date·train_type 등). 검증된 값만 들어온다(⑤ llm 검증기). */
typealias Slots = Map<String, String>

/**
 * 최소 DSL 한 단계(상세 ① 4절: click / type / wait_for / back / expect). 복잡한 분기는 시나리오 Kotlin 코드로 쓴다.
 * 대상 노드는 슬롯에 따라 달라질 수 있어 람다로 받는다(예: 사용자가 고른 열차 이름).
 */
sealed interface Step {
    /** 이 단계를 시작할 수 있는 화면(pre-check). null이면 어느 화면에서든 시작한다(wait_for·expect). */
    val on: String?

    /**
     * 노드를 누르고 [then] 화면이 될 때까지 기다린다.
     * [query]는 대상 앱 서버에 조회를 보내는 클릭(예: '조회하기')이다. D17 한도(1건당 3회·간격 3초)를 센다.
     */
    class Click(
        override val on: String,
        val then: String,
        val timeoutMs: Long?,
        val query: Boolean = false,
        val target: (Slots) -> NodeMatcher,
    ) : Step

    /** 입력칸에 값을 넣는다. 이미 같은 값이면 건너뛴다(재탐색 뒤 되돌아와도 안전하게). */
    class Type(
        override val on: String,
        val target: (Slots) -> NodeMatcher,
        val text: (Slots) -> String,
    ) : Step

    class Back(override val on: String, val then: String, val timeoutMs: Long?) : Step

    /** [screen]이 될 때까지 최대 [timeoutMs] 기다린다. expect는 timeoutMs = 0(로딩 보류만). */
    class WaitFor(val screen: String, val timeoutMs: Long) : Step {
        override val on: String? get() = null
    }

    /**
     * 사용자에게 큰 버튼으로 묻는다(ASK_USER). [slot]이 이미 채워져 있으면 묻지 않는다.
     * 보기는 슬롯과 현재 화면에서 만든다. 화면 텍스트는 사용자에게 보여 줄 뿐 LLM에는 넣지 않는다(D32).
     */
    class Ask(
        override val on: String,
        val slot: String,
        val question: String,
        val options: (Slots, UiWindow) -> List<String>,
    ) : Step

    /** 마지막 단계. 이 화면에서 멈추고 요약을 낸다. 확정 버튼은 사용자가 직접 누른다. */
    class Finish(override val on: String, val summary: (Slots) -> String) : Step
}

/** 알려진 팝업 사전(복구 사다리 2단). [dismiss]를 누르면 팝업이 닫힌다. */
data class PopupRule(val dismiss: NodeMatcher, val screen: String = Screens.POPUP_KNOWN)

/** 사전 정의 시나리오 하나(Play 방어 2: 사람이 정의한 앱별 시나리오만 실행). */
class Flow(
    val name: String,
    val packages: Set<String>,
    val steps: List<Step>,
    val popups: List<PopupRule>,
) {
    init {
        require(steps.isNotEmpty()) { "$name: 빈 시나리오" }
        require(steps.last() is Step.Finish && steps.count { it is Step.Finish } == 1) {
            "$name: Finish는 마지막에 정확히 한 번(정지 지점이 없는 시나리오는 만들 수 없다)"
        }
    }

    /**
     * 재탐색(D39 복귀 불일치·HANDOFF 복귀): [screen]에서 시작할 수 있는 가장 앞 단계.
     * 앞 단계로 가도 Type·Ask는 이미 된 일이면 건너뛰므로 다시 하지 않는다(DECISIONS-IMPL I-29).
     */
    fun relocate(screen: String?): Int? = screen?.let { s -> steps.indexOfFirst { it.on == s }.takeIf { it >= 0 } }
}

fun flow(name: String, packages: Set<String>, block: FlowBuilder.() -> Unit): Flow =
    FlowBuilder().apply(block).let { Flow(name, packages, it.steps.toList(), it.popups.toList()) }

class FlowBuilder internal constructor() {
    internal val steps = mutableListOf<Step>()
    internal val popups = mutableListOf<PopupRule>()

    fun popup(dismiss: NodeMatcher, screen: String = Screens.POPUP_KNOWN) {
        popups += PopupRule(dismiss, screen)
    }

    fun click(on: String, then: String, target: NodeMatcher, timeoutMs: Long? = null, query: Boolean = false) {
        steps += Step.Click(on, then, timeoutMs, query) { target }
    }

    fun click(on: String, then: String, timeoutMs: Long? = null, query: Boolean = false, target: (Slots) -> NodeMatcher) {
        steps += Step.Click(on, then, timeoutMs, query, target)
    }

    fun type(on: String, target: NodeMatcher, text: (Slots) -> String) {
        steps += Step.Type(on, { target }, text)
    }

    fun back(on: String, then: String, timeoutMs: Long? = null) {
        steps += Step.Back(on, then, timeoutMs)
    }

    fun waitFor(screen: String, timeoutMs: Long) {
        steps += Step.WaitFor(screen, timeoutMs)
    }

    fun expect(screen: String) {
        steps += Step.WaitFor(screen, 0)
    }

    fun ask(on: String, slot: String, question: String, options: (Slots, UiWindow) -> List<String>) {
        steps += Step.Ask(on, slot, question, options)
    }

    fun finish(on: String, summary: (Slots) -> String) {
        steps += Step.Finish(on, summary)
    }
}
