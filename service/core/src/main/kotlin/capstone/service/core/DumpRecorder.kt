package capstone.service.core

import capstone.gate.Snapshot
import capstone.snapshot.RecordedSnapshot
import capstone.snapshot.SnapshotJsonl
import capstone.snapshot.SnapshotMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 덤프 줄에 붙일 기기·앱 정보(측정 시트 A의 앱버전·기기/OS 칸). */
data class DeviceInfo(
    val sdk: Int,
    val oneUi: String?,
    /** 패키지 → versionName. 모르면 null. */
    val appVersion: (String) -> String?,
)

/**
 * 화면이 바뀔 때마다 마스킹한 스냅샷을 JSONL v0 한 줄로 쓴다(D34, 10-27 덤프 절차).
 * - [packages]에 든 앱이 포그라운드일 때만 쓴다(메신저·은행 등 다른 앱 화면은 저장하지 않는다).
 * - 포그라운드 창 내용(마스킹 후 treeHash)이 직전과 같으면 건너뛴다.
 * - 세션당 [maxLines]까지만 쓴다(저장 공간·실수 방지).
 * - [healthPackages](병원·약국 앱)는 글자를 모두 지우고 구조만 쓴다(D70 ①). 패키지명은 10-28 C 드라이런 뒤 채운다.
 * screen_label은 비워 둔다: 정답 라벨은 사람이 붙인다(I-20).
 */
class DumpRecorder(
    private val out: Appendable,
    private val packages: Set<String>,
    private val device: DeviceInfo,
    private val sessionId: String,
    private val maxLines: Int = 500,
    private val healthPackages: Set<String> = emptySet(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    var written: Int = 0
        private set
    private var lastHash: String? = null

    sealed interface Result {
        data class Written(val snapId: String) : Result
        data class Skipped(val why: String) : Result
    }

    @Synchronized
    fun record(snapshot: Snapshot): Result {
        val fg = snapshot.foregroundAppWindow ?: return Result.Skipped("no_foreground")
        val pkg = fg.packageName ?: return Result.Skipped("no_package")
        if (pkg !in packages) return Result.Skipped("package_not_listed")
        if (written >= maxLines) return Result.Skipped("max_lines")
        val masked = DumpMasker.mask(snapshot, healthPackages)
        val hash = masked.foregroundAppWindow?.treeHash
        if (hash == lastHash) return Result.Skipped("unchanged")

        val snapId = "%s_%04d".format(sessionId, written + 1)
        val meta = SnapshotMeta(
            snapId = snapId,
            app = pkg,
            appVersion = device.appVersion(pkg),
            oneUi = device.oneUi,
            sdk = device.sdk,
            captured = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(snapshot.timestampMs).atZone(zone)),
            a11yToolFlag = false,
            screenLabel = null,
        )
        out.append(SnapshotJsonl.encodeLine(RecordedSnapshot(meta, masked))).append('\n')
        written++
        lastHash = hash
        return Result.Written(snapId)
    }
}
