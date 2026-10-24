package capstone.app.core

/**
 * 기기 상태 질의(Android 쪽이 구현). 온보딩은 사용자의 말이 아니라 이 값으로만 다음 단계로 간다.
 */
interface DeviceChecks {
    /** 앱 알림과 진행 알림 채널이 모두 켜져 있는가(D69 ⑤: areNotificationsEnabled + 채널 중요도). */
    fun notificationsGranted(): Boolean
    fun accessibilityEnabled(): Boolean

    /**
     * Advanced Protection 켜짐 여부. API 36 미만·권한 없음·플래그 꺼짐·예외는 '불명' = null(D68 ①·D71).
     * 켜짐(true)은 지원 대상 밖(D33 '고급 보호 꺼짐' 조건, D71) — 서비스가 살아 있어도 분기 B로 안내 후 중단한다.
     */
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

    /** ④ 알림 허용(D62·D69 ⑤). [denied]면 대행을 시작하지 않고 이 화면에서 허용 안내를 다시 보인다. */
    data class Notifications(val denied: Boolean = false) : OnboardingScreen

    /** ⑤ 접근성 켜기(그림 안내 + 설정으로 가기). [failures]는 설정에서 켜지 않고 돌아온 횟수. */
    data class Accessibility(val failures: Int = 0) : OnboardingScreen

    /** ⑥ 스위치가 안 눌릴 때만: 제한된 설정 허용 안내. */
    data object RestrictedSettings : OnboardingScreen

    /** ⑦ 켜졌어요 + 24시간 경고 사전 안내(D74 ③) + 결제 없는 연습 1회. */
    data object Ready : OnboardingScreen

    /**
     * 분기 A: 2번 실패 → 가족에게 안내 보내기(다시 시도도 가능).
     * [protectionUnknown]이면 AP를 확인할 수 없는 기기라 [Onboarding.AP_UNKNOWN_HINT]를 덧붙인다(D68 ②).
     */
    data class AskFamily(val protectionUnknown: Boolean = false) : OnboardingScreen

    /**
     * 분기 B: Advanced Protection 켜짐 확인(D71: 감지가 아니라 가용성 전제조건). 안내 후 중단 — 설명 위주,
     * 보호 기능을 끄라고 하지 않고 보호자 확인을 권한다(우회·tool 위장 금지). AP가 꺼진 것이 확인되거나 불명이 되어야 빠져나온다.
     */
    data object Protected : OnboardingScreen

    data object Done : OnboardingScreen
}

enum class HelpMode(val wire: String) { ALONE("alone"), WITH_FAMILY("family") }

/**
 * 첫 실행 온보딩 v1(D66, 문구 가안). ① 인사 → ② 고지·동의 → ③ 가족 도움 → ④ 알림 → ⑤ 접근성 → (⑥ 제한된 설정) → ⑦ 완료.
 *
 * - 마이크(RECORD_AUDIO)는 여기서 묻지 않는다: 첫 음성 사용 때 우리 Activity에서 묻는다(D66 수정 ①).
 * - 이미 된 단계(알림 허용·접근성 켜짐)는 건너뛴다. 끝나면 [isDone]이 저장되고, 고지 문구 버전이 바뀌면 ②부터 다시 묻는다.
 * - Advanced Protection 켜짐이 확인되면 접근성 상태와 관계없이 [OnboardingScreen.Protected]로 간다(분기 B, D71 '안내 후 중단').
 *   AP를 확인할 수 없으면(null) 막지 않고, 접근성 2회 실패 시 분기 A에 보조 문구를 붙인다(D68 ②).
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
        if (blockedByProtection()) screen = OnboardingScreen.Protected
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
            OnboardingScreen.RestrictedSettings, is OnboardingScreen.AskFamily -> MAX_FAILURES - 1
            else -> return
        }
        screen = when {
            device.accessibilityEnabled() -> OnboardingScreen.Ready
            blockedByProtection() -> OnboardingScreen.Protected
            failures + 1 >= MAX_FAILURES -> OnboardingScreen.AskFamily(protectionUnknown = device.advancedProtection() == null)
            else -> OnboardingScreen.Accessibility(failures + 1)
        }
    }

    /**
     * 분기 B 화면에서 앱으로 돌아왔을 때(onResume). 사용자·보호자가 AP를 직접 바꿨으면 이어 간다.
     * 우리 쪽에서 AP를 끄라고 하지 않으므로, 이 경로는 상태가 바뀐 경우에만 쓰인다.
     */
    fun recheckProtection() {
        if (screen != OnboardingScreen.Protected || blockedByProtection()) return
        screen = when {
            isDone -> OnboardingScreen.Done
            consent.required -> if (store.get(KEY_DONE) == DONE_VERSION) OnboardingScreen.Disclosure() else OnboardingScreen.Greeting
            helpMode == null && store.get(KEY_DONE) != DONE_VERSION -> OnboardingScreen.Family
            else -> afterFamily()
        }
    }

    /** ⑥ '스위치가 안 눌려요'. */
    fun switchBlocked() {
        check(screen is OnboardingScreen.Accessibility || screen is OnboardingScreen.AskFamily)
        screen = OnboardingScreen.RestrictedSettings
    }

    /** 분기 A에서 '다시 해 볼게요'. 실패 횟수는 그대로 두지 않고 처음부터 센다. */
    fun retry() {
        check(screen is OnboardingScreen.AskFamily || screen == OnboardingScreen.RestrictedSettings)
        screen = OnboardingScreen.Accessibility()
    }

    // ⑦ 완료: 연습을 하든 건너뛰든 여기서 끝낸다(연습은 목업 시나리오로 AppSession이 돌린다).
    fun finish() {
        check(screen == OnboardingScreen.Ready)
        store.put(KEY_DONE, DONE_VERSION)
        screen = OnboardingScreen.Done
    }

    /**
     * 대행 시작 직전 확인. null이면 시작해도 된다. 사용 중에 알림·접근성을 끈 경우도 여기서 잡는다.
     * 접근성 서비스는 시스템이 bind하므로 우리가 '서비스 시작'을 막을 수는 없다 — 막는 것은 대행 동작이다(D69 ⑤ 검증 수정).
     */
    fun startBlocker(): String? = when {
        blockedByProtection() -> PROTECTED_TEXT
        !device.notificationsGranted() -> NOTIFICATIONS_OFF_TEXT
        !device.accessibilityEnabled() -> "먼저 접근성에서 이 앱을 켜 주세요."
        else -> null
    }

    /**
     * D71: 켜짐이 '확인'되면 서비스가 아직 살아 있어도 막는다(AP 기기는 지원 대상 밖, 회수 시점은 W09 실측 전).
     * 불명(null)은 막지 않고 분기 A 쪽(보조 문구)으로 간다(D68 가드 유지).
     */
    private fun blockedByProtection(): Boolean = device.advancedProtection() == true

    private fun afterFamily(): OnboardingScreen =
        if (device.notificationsGranted()) afterNotifications() else OnboardingScreen.Notifications()

    private fun afterNotifications(): OnboardingScreen =
        if (device.accessibilityEnabled()) OnboardingScreen.Ready else OnboardingScreen.Accessibility()

    companion object {
        /** 분기 A로 가는 실패 횟수(D66 '미허용 2회'). */
        const val MAX_FAILURES: Int = 2

        const val GREETING_TEXT = "말씀만 하세요. 기차표 예매 화면을 채워 드려요. 돈이 나가는 결제는 꼭 직접 누르세요."
        /**
         * 분기 B(D68 ②·D71): 설명 위주, 해제 강요 없음, 보호자 확인 권고. 회수 여부는 아직 확인 중이라 조건부로 말한다(D71).
         * 문구는 W09 캡처 뒤 확정.
         */
        const val PROTECTED_TEXT =
            "휴대폰의 '고급 보호' 기능이 켜져 있어요. 고급 보호가 켜져 있으면 이 앱의 화면 도움이 막히거나 꺼질 수 있어서, 여기서 멈출게요. " +
                "휴대폰을 지키는 기능이니 혼자 끄지 마시고, 가족이나 보호자와 함께 확인해 주세요."

        /**
         * ⑦ 활성 확인 뒤 사전 안내(D74 ③, 가안). 비tool 접근성 서비스는 바인드 24시간 뒤 시스템 경고가 1회 뜬다(AOSP
         * PolicyWarningUIController). 사실만 전하고 '닫으세요'처럼 지시하지 않는다. 실제 문구는 W09 캡처로 교체.
         */
        const val WARNING_NOTICE_TEXT =
            "하루쯤 뒤 '이 앱이 화면을 읽고 누른다'는 알림이 한 번 올 수 있어요. 직접 켠 기능이 맞다면 그대로 두셔도 됩니다."

        /** D74 ③: 경고를 눌러 설정 화면으로 갔을 때. 스위치를 끄면 경고 기록이 지워져 다시 뜬다(D74 ②). 그림은 W09 캡처 뒤. */
        const val WARNING_SWITCH_TEXT = "그 알림을 눌러 설정 화면이 나오면, 스위치는 켜진 그대로 두고 뒤로 가기를 누르세요."

        /** 분기 A 보조 문구(D68 ②): AP를 확인할 수 없는 기기(API 34·35, 권한·예외). */
        const val AP_UNKNOWN_HINT = "휴대폰의 '고급 보호' 기능이 켜져 있으면 이 앱이 제한될 수 있어요."

        /** D69 ⑤: 앱·채널 알림이 꺼져 있으면 대행을 시작하지 않고 앱 화면에서 허용을 안내한다. */
        const val NOTIFICATIONS_OFF_TEXT = "알림이 꺼져 있어서 시작하지 않았어요. 진행 상황과 '그만' 버튼이 알림으로 나와요. 설정에서 이 앱의 알림을 켜 주세요."

        private const val KEY_DONE = "onboarding.done"
        private const val KEY_HELP = "onboarding.help"

        /** 온보딩 단계가 바뀌면 올린다 → 다음 실행 때 처음부터. */
        private const val DONE_VERSION = "1"
    }
}
