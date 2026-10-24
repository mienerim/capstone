package capstone.gate

import java.security.MessageDigest

/**
 * 접근성 트리 스냅샷 모델(D34). service가 실기기에서 만들고, 재생기(tools)는 JSONL에서 복원한다.
 * gate·agent-core가 같은 모델을 보므로 gate-core에 둔다(docs/DECISIONS-IMPL.md I-2).
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(other: Bounds): Boolean =
        !isEmpty && !other.isEmpty &&
            left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun inflate(px: Int): Bounds = Bounds(left - px, top - px, right + px, bottom + px)

    companion object {
        val EMPTY: Bounds = Bounds(0, 0, 0, 0)
        fun point(x: Int, y: Int): Bounds = Bounds(x, y, x + 1, y + 1)
    }
}

data class UiNode(
    val className: String? = null,
    val text: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val stateDescription: String? = null,
    val paneTitle: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /** API 34+ isAccessibilityDataSensitive. false 서비스에는 보통 노드 자체가 안 보인다. */
    val dataSensitive: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    val interactive: Boolean get() = clickable || longClickable || editable || scrollable

    /** 사람이 읽을 수 있는 라벨 신호가 하나라도 있는가. */
    val hasLabel: Boolean
        get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank() ||
            !hint.isNullOrBlank() || !viewId.isNullOrBlank()
}

enum class WindowType { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, SPLIT_SCREEN_DIVIDER, MAGNIFICATION_OVERLAY, UNKNOWN }

data class UiWindow(
    val id: Int,
    val type: WindowType,
    val packageName: String?,
    val active: Boolean = false,
    val focused: Boolean = false,
    val root: UiNode?,
) {
    /** 인덱스 경로("0", "0/2/1")를 안정 키로 쓴다. 같은 트리면 같은 키. */
    val nodes: Map<String, UiNode> by lazy {
        val out = LinkedHashMap<String, UiNode>()
        fun walk(n: UiNode, key: String) {
            out[key] = n
            n.children.forEachIndexed { i, c -> walk(c, "$key/$i") }
        }
        root?.let { walk(it, "0") }
        out
    }

    val nodeCount: Int get() = nodes.size
    val interactiveCount: Int get() = nodes.values.count { it.interactive }

    /** 행동 재바인딩(D18 ④)을 막기 위한 내용 해시. 시각·순서 외 모든 노드 필드를 반영한다. */
    val treeHash: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$id|$type|$packageName\n".toByteArray())
        for ((k, n) in nodes) {
            md.update(
                listOf(
                    k, n.className, n.text, n.hint, n.contentDescription, n.stateDescription, n.paneTitle,
                    n.viewId, n.bounds, n.clickable, n.longClickable, n.editable, n.scrollable, n.enabled,
                    n.visible, n.dataSensitive,
                ).joinToString("\u0001", postfix = "\n").toByteArray(),
            )
        }
        md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    fun parentKey(key: String): String? = key.substringBeforeLast('/', "").ifEmpty { null }

    /** key 자신 포함, 루트 방향 조상 키 목록. */
    fun ancestorsOf(key: String): List<String> =
        generateSequence(key) { parentKey(it) }.toList()

    fun descendantsOf(key: String): List<String> =
        nodes.keys.filter { it.startsWith("$key/") }
}

/**
 * service가 밖으로 내보내는 유일한 노드 참조(D36). (windowId, 안정 키, treeHash) 값 객체라
 * 재생기에서 그대로 재현되고, 트리가 바뀌면 treeHash가 달라져 낡은 참조가 드러난다.
 */
data class NodeRef(val windowId: Int, val key: String, val treeHash: String) {
    companion object {
        fun of(window: UiWindow, key: String): NodeRef = NodeRef(window.id, key, window.treeHash)
    }
}

data class Snapshot(
    val timestampMs: Long,
    val windows: List<UiWindow>,
    /** 화면 분류기 결과(agent-core). null이면 미분류. "UNKNOWN"이면 정지 대상(D18). */
    val screen: String? = null,
    /** takeScreenshotOfWindow 오류 코드(API 34). null = 오류 없음 또는 미측정. */
    val screenshotError: Int? = null,
) {
    /** 패키지 허용목록 판정 대상: 활성·포커스 TYPE_APPLICATION 창(D36). 없으면 활성 APPLICATION 창. */
    val foregroundAppWindow: UiWindow?
        get() = windows.firstOrNull { it.type == WindowType.APPLICATION && it.active && it.focused }
            ?: windows.firstOrNull { it.type == WindowType.APPLICATION && it.active }

    fun window(id: Int): UiWindow? = windows.firstOrNull { it.id == id }

    companion object {
        const val SCREEN_UNKNOWN: String = "UNKNOWN"
    }
}
