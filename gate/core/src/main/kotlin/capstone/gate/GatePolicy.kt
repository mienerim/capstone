package capstone.gate

/**
 * gate 설정. 수치는 모두 D36 '가설'이며 10-27 덤프로 보정한다.
 * 우리 앱 패키지는 허용목록에 넣을 수 없다(D39: PAUSED_FOR_USER 중 우리 Activity에서는 조작 금지).
 */
data class GatePolicy(
    val allowedPackages: Set<String>,
    val ownPackage: String,
    /** 직전 기준선 대비 노드·상호작용 노드가 모두 이 비율 미만이면 급감. */
    val collapseRatio: Double = 0.4,
    /** 기준선 노드 수가 이보다 작으면 급감 판정을 하지 않는다(n0<20 절대 하한). */
    val collapseMinBaseline: Int = 20,
    /** 기준선 중앙값을 낼 최근 스냅샷 수. */
    val baselineWindow: Int = 5,
    /** 좌표 탭 겹침 검사 여유(px). 대략 8dp × 3x 밀도. */
    val touchSlopPx: Int = 24,
    /** 같은 화면에서 상위 몇 단계 조상까지 일반 긍정 버튼의 문맥을 볼지(T2). */
    val contextAncestorDepth: Int = 3,
) {
    init {
        require(ownPackage !in allowedPackages) { "자기 패키지는 gate 허용목록에 넣을 수 없다(D39)" }
        require(collapseRatio in 0.0..1.0)
    }

    companion object {
        const val KORAIL_PLUS: String = "com.korail.talk"
        const val MOCK_APP: String = "capstone.mock"
    }
}
