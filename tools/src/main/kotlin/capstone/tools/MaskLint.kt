package capstone.tools

import java.io.File

/**
 * 커밋 전 마스킹 린트(D58 '커밋 전 CI 정규식 린트: 휴대폰·주민번호형·생년월일·실병원명 사전').
 * 저장소의 데이터 파일(덤프·시나리오·측정 시트)에 치환 안 된 개인정보가 남았는지 찾는다.
 *
 * - 대상은 데이터 확장자만(.jsonl·.json·.csv·.tsv·.txt·.log). 코드·문서(.kt·.md)는 규칙 설명에 예시가 들어가므로 보지 않는다.
 * - 결과에는 값 자체를 찍지 않는다(CI 로그도 공개 저장소에 남는다). 종류·파일·줄·칸만.
 * - 패턴 린트라 이름·주소는 못 잡는다. 사람 검토를 대신하지 않는다(DumpMasker와 같은 한계, I-44).
 */
object MaskLint {
    enum class Kind { PHONE, RRN, CARD, EMAIL, BIRTHDATE, HOSPITAL }

    data class Finding(val path: String, val line: Int, val column: Int, val kind: Kind) {
        override fun toString(): String = "$path:$line:$column ${kind.name}"
    }

    val DATA_EXTENSIONS: Set<String> = setOf("jsonl", "json", "csv", "tsv", "txt", "log")
    private val SKIP_DIRS = setOf(".git", ".gradle", "build", ".idea", ".kotlin", "node_modules")

    /** D58 치환 규칙이 쓰는 가짜 이름 접두어('테스트병원A'). 이것으로 시작하는 기관명은 통과. */
    private val FAKE_PREFIXES = listOf("테스트", "목업", "가짜", "예시", "샘플")
    private val SAFE_EMAIL_DOMAINS = setOf("example.com", "example.org", "example.net", "test", "invalid", "localhost")

    // 숫자열 안의 일부(타임스탬프·해시)를 잡지 않도록 앞뒤가 숫자가 아니어야 한다.
    private val patterns: List<Pair<Kind, Regex>> = listOf(
        Kind.RRN to Regex("""(?<!\d)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\s*-\s*[1-8]\d{6}(?!\d)"""),
        Kind.CARD to Regex("""(?<!\d)\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{3,4}(?!\d)"""),
        Kind.PHONE to Regex("""(?<!\d)01[016789][ .-]?\d{3,4}[ .-]?\d{4}(?!\d)"""),
        Kind.EMAIL to Regex("""[\w.+-]+@([\w-]+(?:\.[\w-]+)+)"""),
        // 열차 날짜(2026-…)는 두고, 사람 생년으로 보이는 1900~2009년 날짜만.
        Kind.BIRTHDATE to Regex("""(?<!\d)(19\d{2}|200\d)\s?[.\-/년]\s?(0?[1-9]|1[0-2])\s?[.\-/월]\s?(0?[1-9]|[12]\d|3[01])(?!\d)"""),
        Kind.HOSPITAL to Regex("""[가-힣A-Za-z0-9]{2,}(?:한의원|병원|의원|치과|요양원|클리닉)"""),
    )

    fun scanLine(line: String): List<Pair<Kind, Int>> = buildList {
        val taken = mutableListOf<IntRange>()
        for ((kind, re) in patterns) {
            for (m in re.findAll(line)) {
                // 주민번호가 카드·전화로 또 잡히지 않게, 먼저 잡힌 구간과 겹치면 건너뛴다.
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                if (!isReal(kind, m)) continue
                taken += m.range
                add(kind to m.range.first + 1)
            }
        }
    }.sortedBy { it.second }

    private fun isReal(kind: Kind, m: MatchResult): Boolean = when (kind) {
        Kind.EMAIL -> m.groupValues[1].lowercase().let { d -> SAFE_EMAIL_DOMAINS.none { d == it || d.endsWith(".$it") } }
        Kind.HOSPITAL -> FAKE_PREFIXES.none { m.value.startsWith(it) }
        else -> true
    }

    fun scanText(path: String, text: String): List<Finding> =
        text.lineSequence().flatMapIndexed { i, line ->
            scanLine(line).map { (kind, col) -> Finding(path, i + 1, col, kind) }
        }.toList()

    /** [root] 아래(또는 파일 하나)를 훑는다. 경로는 [root] 기준 상대 경로로 보고한다. */
    fun scan(root: File): List<Finding> {
        val files = if (root.isFile) {
            sequenceOf(root)
        } else {
            root.walkTopDown()
                .onEnter { it == root || it.name !in SKIP_DIRS }
                .filter { it.isFile && it.extension.lowercase() in DATA_EXTENSIONS }
        }
        return files.sortedBy { it.path }.flatMap { f ->
            val rel = if (root.isFile) f.name else f.relativeTo(root).invariantSeparatorsPath
            scanText(rel, f.readText())
        }.toList()
    }
}
