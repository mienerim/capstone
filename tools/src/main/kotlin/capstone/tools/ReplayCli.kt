package capstone.tools

import capstone.gate.GatePolicy
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import capstone.tools.replay.ReplayFormatException
import capstone.tools.replay.Outcome
import capstone.tools.replay.ReplayHarness
import capstone.tools.replay.Scenario
import capstone.tools.replay.SnapshotJsonl
import capstone.tools.replay.SnapshotPool
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """사용법:
  validate <dump.jsonl>...     덤프 형식 검사 + 스냅샷별 창·노드 요약 (10-27 실기기 덤프 확인용)
  scenario <scenario.json>...  시나리오가 참조하는 스냅샷이 모두 있는지 검사
  harness <scenario.json> [n] [첫 seed]
                               목업 흐름을 seed만 바꿔 n회(기본 20) 실행: 완료율·M7 위반·Wilson 구간
예: ./gradlew :tools:run --args="validate /abs/path/dump.jsonl\""""

/** 재생기 CLI. 실패하면 종료 코드 1이라 스크립트·CI에서 그대로 쓸 수 있다. */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val ok = when (args[0]) {
        "validate" -> args.drop(1).all { validate(File(it)) }
        "scenario" -> args.drop(1).all { checkScenario(File(it)) }
        "harness" -> harness(File(args[1]), args.getOrNull(2)?.toIntOrNull() ?: 20, args.getOrNull(3)?.toLongOrNull())
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
    exitProcess(if (ok) 0 else 1)
}

internal fun validate(file: File, out: Appendable = System.out): Boolean = report(file, out) {
    val recs = SnapshotJsonl.read(file)
    out.appendLine("${file.name}: 스냅샷 ${recs.size}개")
    for (r in recs) {
        val fg = r.snapshot.foregroundAppWindow
        val wins = r.snapshot.windows.joinToString(" ") { "${it.type.name.lowercase()}#${it.id}:${it.nodeCount}" }
        out.appendLine(
            "  ${r.snapId} label=${r.meta.screenLabel ?: "-"} fg=${fg?.packageName ?: "없음"} " +
                "nodes=${fg?.nodeCount ?: 0} interactive=${fg?.interactiveCount ?: 0} hash=${fg?.treeHash ?: "-"} [$wins]",
        )
    }
}

internal fun checkScenario(file: File, out: Appendable = System.out): Boolean = report(file, out) {
    val sc = Scenario.read(file)
    val pool = SnapshotPool.forScenario(file, sc)
    val missing = sc.missingSnapshots(pool)
    if (missing.isNotEmpty()) throw ReplayFormatException("없는 스냅샷 $missing")
    out.appendLine("${file.name}: ${sc.name} 전이 ${sc.transitions.size}개, 결함 ${sc.faults.size}개, 스냅샷 ${pool.ids.size}개 OK")
}

/**
 * 지금은 목업 흐름(MockKorailFlow)만 돌린다. 코레일+ 흐름은 10-27 덤프 뒤 app-scenarios에 생기면 인자로 고른다.
 * 위반이 1건이라도 있으면 실패(종료 코드 1): M7은 0건이 기준이다(D28).
 */
internal fun harness(file: File, n: Int, firstSeed: Long?, out: Appendable = System.out): Boolean {
    var violations = -1
    val ok = report(file, out) {
        val sc = Scenario.read(file)
        // 목업 전이 테이블은 KTX 105 행만 좌석 화면으로 이어지므로 열차 슬롯을 미리 채운다(ASK_USER 생략).
        val slots = mapOf(
            MockKorailFlow.SLOT_ORIGIN to "서울", MockKorailFlow.SLOT_DESTINATION to "부산", MockKorailFlow.SLOT_TRAIN to "KTX 105",
        )
        val policy = GatePolicy(allowedPackages = setOf(MockKorail.PKG), ownPackage = "capstone.app")
        val h = ReplayHarness(sc, SnapshotPool.forScenario(file, sc), MockKorailFlow.flow, MockKorail.classifier(), slots, policy)
        val r = h.run(n, firstSeed ?: sc.seed)
        out.appendLine(r.summary())
        violations = r.violations
        if (violations > 0) out.appendLine("  M7 위반 seed: ${r.runs.filter { it.outcome == Outcome.VIOLATION }.map { it.seed }}")
    }
    return ok && violations == 0
}

private inline fun report(file: File, out: Appendable, body: () -> Unit): Boolean = try {
    body()
    true
} catch (e: ReplayFormatException) {
    out.appendLine("${file.name}: 형식 오류 - ${e.message}")
    false
} catch (e: java.io.IOException) {
    out.appendLine("${file.name}: 읽기 실패 - ${e.message}")
    false
}
