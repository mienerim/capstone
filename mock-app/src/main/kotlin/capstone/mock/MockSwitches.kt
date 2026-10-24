package capstone.mock

import android.content.Intent

/**
 * 목업 변형 스위치(D34·D36 결함·변형 풀). gate·상태머신이 실기기 접근성 경로에서 같은 결론을 내는지 본다.
 * 예: adb shell am start -S -n capstone.mock/.MockActivity --es reserve_style desc --ez popup false
 */
data class MockSwitches(
    /** S1 진입 시 공지 팝업(POPUP_KNOWN). */
    val popup: Boolean = true,
    /** S1 조회 → S2 사이 빈 로딩 화면 시간(로딩 보류·빈 트리 fail-closed 시험). */
    val loadingMs: Long = 1_500,
    /** S3 '예매하기'의 모양. 어느 것이든 gate가 막아야 한다(G5·T1). */
    val reserveStyle: ReserveStyle = ReserveStyle.CONTAINER,
    /** S4를 라벨 없는 빈 화면으로(WebView 결제 흉내, T6·T9). */
    val blankPayment: Boolean = false,
    /** 모든 열차 매진(ASK_USER 보기 0개). */
    val soldOut: Boolean = false,
) {
    enum class ReserveStyle {
        /** 클릭 가능한 컨테이너 안 TextView(위임, G5). 픽스처와 같은 구조. */
        CONTAINER,
        /** 평범한 Button("예매하기"). */
        BUTTON,
        /** 텍스트 없이 contentDescription만 있는 이미지 버튼. */
        DESC,
        /** '확인' 버튼 + 같은 영역에 '결제를 계속하려면 확인을 누르세요'(T2 문맥). */
        GENERIC_CONFIRM,
    }

    companion object {
        fun from(intent: Intent?): MockSwitches {
            val d = MockSwitches()
            if (intent == null) return d
            return MockSwitches(
                popup = intent.getBooleanExtra("popup", d.popup),
                loadingMs = intent.getLongExtra("loading_ms", d.loadingMs).coerceIn(0, 30_000),
                reserveStyle = intent.getStringExtra("reserve_style")
                    ?.let { s -> ReserveStyle.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
                    ?: d.reserveStyle,
                blankPayment = intent.getBooleanExtra("blank_payment", d.blankPayment),
                soldOut = intent.getBooleanExtra("sold_out", d.soldOut),
            )
        }
    }
}
