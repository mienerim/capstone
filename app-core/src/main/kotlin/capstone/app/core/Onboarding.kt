package capstone.app.core

/**
 * 기기 상태 질의(Android 쪽이 구현). 온보딩은 사용자의 말이 아니라 이 값으로만 다음 단계로 간다.
 */
interface DeviceChecks {
    fun notificationsGranted(): Boolean
    fun accessibilityEnabled(): Boolean

    /** Advanced Protection 켜짐 여부. API 34·35는 감지할 수 없어 null(D66 수정 ③) → 활성 검사 실패로 분기 A에 흡수된다. */
    fun advancedProtection(): Boolean?
}

/** 첫 실행 온보딩 화면(D66 v1). Activity가 그대로 그린다. */
sealed interface OnboardingScreen {
    /** ① 인사. */
    data object Greeting : OnboardingScreen

    /** ② 고지·동의(D25). 동의 전에는 어떤 기능도 쓰지 않는다. */
    data class Disclosure(val declined: Boolean = false) : OnboardingScreen

    /** ③ 혼자 / 가족과 함께. 상태만 저장한다. */
    data object Family : OnboardingScreen

    /** ④ 알림 허용(D62 필수). [denied]면 서비스를 시작하지 않고 이 화면에 남는다. */
    data class Notifications(val denied: Boolean = false) : OnboardingScreen

    /** ⑤ 접근성 켜기(그림 안내 + 설정으로 가기). [failures]는 설정에서 켜지 않고 돌아온 횟수. */
    data class Accessibility(val failures: Int = 0) : OnboardingScreen

    /** ⑥ 스위치가 안 눌릴 때만: 제한된 설정 허용 안내. */
    data object RestrictedSettings : OnboardingScreen

    /** ⑦ 켜졌어요 + 결제 없는 연습 1회. */
    data object Ready : OnboardingScreen

    /** 분기 A: 2번 실패 → 가족에게 안내 보내기(다시 시도도 가능). */
    data object AskFamily : OnboardingScreen

    /** 분기 B: Advanced Protection 켜짐 → 쓸 수 없다는 안내만(우회·tool 위장 금지). */
    data object Protected : OnboardingScreen

    data object Done : OnboardingScreen
}

enum class HelpMode(val wire: String) { ALONE("alone"), WITH_FAMILY("family") }

/**
 * 첫 실행 온보딩 v1(D66, 문구 가안). ① 인사 → ② 고지·동의 → ③ 가족 도움 → ④ 알림 → ⑤ 접근성 → (⑥ 제한된 설정) → ⑦ 완료.
 *
 * - 마이크(RECORD_AUDIO)는 여기서 묻지 않는다: 첫 음성 사용 때 우리 Activity에서 묻는다(D66 수정 ①).
 * - 이미 된 단계(알림 허용·접근성 켜짐)는 건너뛴다. 끝나면 [isDone]이 저장되고, 고지 문구 버전이 바뀌면 ②부터 다시 묻는다.
 * - Advanced Protection이 켜져 있으면 어느 단계에서든 [OnboardingScreen.Protected]로 간다(분기 B).
 * - Android에서는 메인 스레드에서만 부른다고 가정한다(AppSession과 같음).
 */
class Onboarding(
    private val store: KeyValueStore,
    private val device: DeviceChecks,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val consent = ConsentManager(store)

    val isDone: Boolean get() = store.get(KEY_DONE) == DONE_VERSION && !consent.required

    val helpMode: HelpMode? get() = store.get(KEY_HELP)?.let { w -> HelpMode.entries.firstOrNull { it.wire == w } }

    var screen: OnboardingScreen = when {
        isDone -> OnboardingScreen.Done
        store.get(KEY_DONE) == DONE_VERSION -> OnboardingScreen.Disclosure() // 문구가 바뀌어 동의만 다시
        else -> OnboardingScreen.Greeting
    }
        private set

    init {
        if (device.advancedProtection() == true) screen = OnboardingScreen.Protected
    }

    // ① 인사
    fun next() {
        check(screen == OnboardingScreen.Greeting)
        screen = OnboardingScreen.Disclosure()
    }

    // ② 고지·동의
    fun accept() {
        check(screen is OnboardingScreen.Disclosure)
        consent.accept(nowMs())
        screen = if (store.get(KEY_DONE) == DONE_VERSION) afterFamily() else OnboardingScreen.Family
    }

    fun decline() {
        check(screen is OnboardingScreen.Disclosure)
        screen = OnboardingScreen.Disclosure(declined = true)
    }

    // ③ 가족 도움
    fun chooseHelp(mode: HelpMode) {
        check(screen == OnboardingScreen.Family)
        store.put(KEY_HELP, mode.wire)
        screen = afterFamily()
    }

    // ④ 알림: 앱이 권한 요청 결과를 넘긴다.
    fun onNotificationResult(granted: Boolean) {
        check(screen is OnboardingScreen.Notifications)
        screen = if (granted) afterNotifications() else OnboardingScreen.Notifications(denied = true)
    }

    // ⑤ 접근성: 설정 화면에서 돌아왔을 때(onResume) 부른다.
    fun onReturnedFromSettings() {
        val failures = when (val s = screen) {
            is OnboardingScreen.Accessibility -> s.failures
            OnboardingScreen.RestrictedSettings, OnboardingScreen.AskFamily -> MAX_FAILURES - 1
            else -> return
        }
        screen = when {
            device.advancedProtection() == true -> OnboardingScreen.Protected
            device.accessibilityEnabled() -> OnboardingScreen.Ready
            failures + 1 >= MAX_FAILURES -> OnboardingScreen.AskFamily
            else -> OnboardingScreen.Accessibility(failures + 1)
        }
    }

    /** ⑥ '스위치가 안 눌려요'. */
    fun switchBlocked() {
        check(screen is OnboardingScreen.Accessibility || screen == OnboardingScreen.AskFamily)
        screen = OnboardingScreen.RestrictedSettings
    }

    /** 분기 A에서 '다시 해 볼게요'. 실패 횟수는 그대로 두지 않고 처음부터 센다. */
    fun retry() {
        check(screen == OnboardingScreen.AskFamily || screen == OnboardingScreen.RestrictedSettings)
        screen = OnboardingScreen.Accessibility()
    }

    // ⑦ 완료: 연습을 하든 건너뛰든 여기서 끝낸다(연습은 목업 시나리오로 AppSession이 돌린다).
    fun finish() {
        check(screen == OnboardingScreen.Ready)
        store.put(KEY_DONE, DONE_VERSION)
        screen = OnboardingScreen.Done
    }

    /**
     * 실행 직전 확인(D62: 알림이 없으면 서비스를 시작하지 않는다). 사용 중에 권한을 끈 경우도 여기서 잡는다.
     * null이면 시작해도 된다.
     */
    fun startBlocker(): String? = when {
        device.advancedProtection() == true -> PROTECTED_TEXT
        !device.notificationsGranted() -> "알림을 허용해야 시작할 수 있어요. 설정에서 알림을 켜 주세요."
        !device.accessibilityEnabled() -> "먼저 접근성에서 이 앱을 켜 주세요."
        else -> null
    }

    private fun afterFamily(): OnboardingScreen =
        if (device.notificationsGranted()) afterNotifications() else OnboardingScreen.Notifications()

    private fun afterNotifications(): OnboardingScreen =
        if (device.accessibilityEnabled()) OnboardingScreen.Ready else OnboardingScreen.Accessibility()

    companion object {
        /** 분기 A로 가는 실패 횟수(D66 '미허용 2회'). */
        const val MAX_FAILURES: Int = 2

        const val GREETING_TEXT = "말씀만 하세요. 기차표 예매 화면을 채워 드려요. 돈이 나가는 결제는 꼭 직접 누르세요."
        const val PROTECTED_TEXT = "휴대폰의 '안전 보호 모드'가 켜져 있어 이 앱을 쓸 수 없어요."

        private const val KEY_DONE = "onboarding.done"
        private const val KEY_HELP = "onboarding.help"

        /** 온보딩 단계가 바뀌면 올린다 → 다음 실행 때 처음부터. */
        private const val DONE_VERSION = "1"
    }
}
