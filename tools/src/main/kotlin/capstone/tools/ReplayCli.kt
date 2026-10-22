package capstone.tools

import capstone.tools.replay.ReplayFormatException
import capstone.tools.replay.Scenario
import capstone.tools.replay.SnapshotJsonl
import capstone.tools.replay.SnapshotPool
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """사용법:
  validate <dump.jsonl>...     덤프 형식 검사 + 스냅샷별 창·노드 요약 (10-27 실기기 덤프 확인용)
  scenario <scenario.json>...  시나리오가 참조하는 스냅샷이 모두 있는지 검사
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
