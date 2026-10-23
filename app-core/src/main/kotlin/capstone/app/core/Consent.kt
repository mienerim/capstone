package capstone.app.core

/**
 * 첫 실행 접근성 고지(D25, Play prominent disclosure 수준을 Play 비배포에서도 자발적으로 따른다).
 * 필수 요소(상세 ⑤·D66 ②): 읽는 데이터 · 사용 방식(결제 직전 정지) · 외부 전송 범위 · 이유 · 끄는 법 · 명시적 동의,
 * 알림 권한, 비tool 알림 미리 안내. 온보딩([Onboarding]) ② 단계에서 보여 준다.
 * 문구를 바꾸면 [VERSION]을 올린다 → 이전 동의는 무효가 되고 다시 묻는다.
 */
object Disclosure {
    /** 2: D66 — 외부 전송 문구를 '이 앱은 화면 내용을' 범위로 한정, 끄는 법 추가, 버튼 '동의합니다'. */
    const val VERSION: Int = 2

    data class Section(val title: String, val body: String)

    val title = "시작하기 전에 꼭 읽어 주세요"

    val sections: List<Section> = listOf(
        Section(
            "무엇을 읽나요",
            "이 앱은 '접근성' 권한으로 코레일+ 화면에 보이는 글자와 버튼 위치를 읽습니다. " +
                "비밀번호나 카드 번호는 저장하지 않습니다.",
        ),
        Section(
            "어떻게 쓰나요",
            "말씀하신 열차를 찾으려고, 미리 정해 둔 순서대로만 코레일+ 버튼을 대신 누릅니다. " +
                "'예매하기'·결제 버튼은 절대 누르지 않고 바로 앞에서 멈춥니다. 마지막 버튼은 직접 누르셔야 합니다.",
        ),
        Section(
            // 플랫폼 음성 인식·읽어 주기는 다른 앱이라 네트워크를 쓸 수 있다 → 기내모드 실측(D31) 통과 전에는 범위를 좁혀 말한다(D66 ②).
            "어디로 보내나요",
            "이 앱은 화면 내용을 밖으로 보내지 않아요. 이 앱에는 인터넷 권한이 없고, 화면을 읽고 판단하는 일은 이 휴대폰 안에서 끝납니다. " +
                "코레일+ 같은 앱이 스스로 하는 통신은 이 앱과 별개입니다.",
        ),
        Section(
            "왜 필요한가요",
            "작은 글씨와 여러 단계를 대신 처리해 드리기 위해서입니다. 로그인·본인인증은 직접 하시도록 넘겨 드립니다.",
        ),
        Section(
            "알림",
            "진행 상황은 알림 글자로 알려 드립니다. 알림을 눌러 언제든 멈출 수 있습니다. " +
                "접근성을 켜고 하루쯤 지나면 휴대폰이 '이 앱이 화면을 볼 수 있다'는 알림을 보낼 수 있는데, 정상입니다.",
        ),
        Section(
            "끄는 법",
            "알림의 '그만'을 누르면 바로 멈춥니다. 완전히 끄려면 설정 > 접근성 > 설치된 앱에서 이 앱을 끄세요.",
        ),
    )

    const val ACCEPT = "동의합니다"
    const val DECLINE = "동의하지 않음"
}

/** 동의 기록. 철회하면 지운다. */
data class ConsentRecord(val version: Int, val acceptedAtMs: Long)

class ConsentManager(private val store: KeyValueStore) {

    val record: ConsentRecord?
        get() {
            val v = store.get(KEY_VERSION)?.toIntOrNull() ?: return null
            val at = store.get(KEY_AT)?.toLongOrNull() ?: return null
            return ConsentRecord(v, at)
        }

    /** 동의가 없거나 예전 문구에 대한 동의면 고지 화면을 먼저 보여야 한다. */
    val required: Boolean get() = record?.version != Disclosure.VERSION

    /** 사용자가 [Disclosure.ACCEPT] 버튼을 직접 눌렀을 때만 부른다(기본값 동의·자동 동의 없음). */
    fun accept(nowMs: Long) {
        store.put(KEY_VERSION, Disclosure.VERSION.toString())
        store.put(KEY_AT, nowMs.toString())
    }

    fun withdraw() {
        store.put(KEY_VERSION, null)
        store.put(KEY_AT, null)
    }

    private companion object {
        const val KEY_VERSION = "consent.version"
        const val KEY_AT = "consent.accepted_at"
    }
}
