package capstone.app.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 첫 실행 온보딩 v1(D66). 기기 상태는 가짜로 바꿔 가며 본다. */
class OnboardingTest {
    private class FakeDevice(
        var notifications: Boolean = false,
        var accessibility: Boolean = false,
        var ap: Boolean? = null,
    ) : DeviceChecks {
        override fun notificationsGranted() = notifications
        override fun accessibilityEnabled() = accessibility
        override fun advancedProtection() = ap
    }

    private val store = MemoryStore()
    private val device = FakeDevice()
    private fun onboarding() = Onboarding(store, device, nowMs = { 42 })

    @Test
    fun `정상 경로 - 인사 → 고지·동의 → 가족 → 알림 → 접근성 → 완료`() {
        val o = onboarding()
        assertEquals(OnboardingScreen.Greeting, o.screen)
        o.next()
        assertEquals(OnboardingScreen.Disclosure(), o.screen)
        o.accept()
        assertEquals(ConsentRecord(Disclosure.VERSION, 42), ConsentManager(store).record)
        assertEquals(OnboardingScreen.Family, o.screen)
        o.chooseHelp(HelpMode.WITH_FAMILY)
        assertEquals(HelpMode.WITH_FAMILY, o.helpMode)
        assertEquals(OnboardingScreen.Notifications(), o.screen)
        device.notifications = true
        o.onNotificationResult(true)
        assertEquals(OnboardingScreen.Accessibility(), o.screen)
        device.accessibility = true
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.Ready, o.screen)
        assertFalse(o.isDone)
        o.finish()
        assertTrue(o.isDone)
        assertEquals(OnboardingScreen.Done, onboarding().screen) // 다시 열어도 끝난 상태
    }

    @Test
    fun `동의하지 않으면 다음 단계로 가지 않고 동의 기록도 없다`() {
        val o = onboarding()
        o.next()
        o.decline()
        assertEquals(OnboardingScreen.Disclosure(declined = true), o.screen)
        assertNull(ConsentManager(store).record)
        assertThrows<IllegalStateException> { o.chooseHelp(HelpMode.ALONE) }
        o.accept() // 마음을 바꾸면 이어 간다
        assertEquals(OnboardingScreen.Family, o.screen)
    }

    @Test
    fun `알림을 거부하면 알림 단계에 남고 대행도 시작하지 않는다(D62·D69 ⑤)`() {
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        o.onNotificationResult(false)
        assertEquals(OnboardingScreen.Notifications(denied = true), o.screen)
        device.accessibility = true
        assertEquals(Onboarding.NOTIFICATIONS_OFF_TEXT, o.startBlocker())
        device.notifications = true
        assertNull(o.startBlocker())
    }

    @Test
    fun `이미 된 단계는 건너뛴다`() {
        device.notifications = true
        device.accessibility = true
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        assertEquals(OnboardingScreen.Ready, o.screen)
    }

    @Test
    fun `접근성 2번 실패 → 가족에게 안내(분기 A), 다시 시도 가능`() {
        device.notifications = true
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.Accessibility(failures = 1), o.screen)
        device.ap = false
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.AskFamily(protectionUnknown = false), o.screen)
        o.retry()
        assertEquals(OnboardingScreen.Accessibility(), o.screen)
    }

    @Test
    fun `스위치가 안 눌리면 제한된 설정 안내 → 돌아와서 켜졌으면 완료, 아니면 분기 A`() {
        device.notifications = true
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        o.switchBlocked()
        assertEquals(OnboardingScreen.RestrictedSettings, o.screen)
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.AskFamily(protectionUnknown = true), o.screen)
        o.switchBlocked()
        device.accessibility = true
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.Ready, o.screen)
    }

    @Test
    fun `Advanced Protection 켜짐 확인 + 접근성 꺼짐이면 안내만 하고 진행하지 않는다(분기 B, D68)`() {
        device.ap = true
        val o = onboarding()
        assertEquals(OnboardingScreen.Protected, o.screen)
        assertThrows<IllegalStateException> { o.next() }
        assertEquals(Onboarding.PROTECTED_TEXT, o.startBlocker())
    }

    @Test
    fun `설정에 다녀오는 사이 Advanced Protection이 켜지고 접근성이 안 켜졌으면 분기 B`() {
        device.notifications = true
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        device.ap = true
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.Protected, o.screen)
    }

    @Test
    fun `API 34·35처럼 AP를 감지할 수 없으면(null) 막지 않는다`() {
        device.ap = null
        assertEquals(OnboardingScreen.Greeting, onboarding().screen)
    }

    @Test
    fun `AP를 모르는 기기에서 접근성 2번 실패하면 분기 A에 보조 문구 표시(분기 B로 보내지 않음, D68 ②)`() {
        device.notifications = true
        device.ap = null
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        o.onReturnedFromSettings(); o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.AskFamily(protectionUnknown = true), o.screen)
        assertEquals("먼저 접근성에서 이 앱을 켜 주세요.", o.startBlocker())
    }

    @Test
    fun `AP가 켜져 있어도 접근성 서비스가 살아 있으면 막지 않는다(D68 ②)`() {
        device.ap = true
        device.notifications = true
        device.accessibility = true
        val o = onboarding()
        assertEquals(OnboardingScreen.Greeting, o.screen)
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        assertEquals(OnboardingScreen.Ready, o.screen)
        assertNull(o.startBlocker())
        device.accessibility = false // 사용 중 회수되면 그때부터 분기 B 문구
        assertEquals(Onboarding.PROTECTED_TEXT, o.startBlocker())
    }

    @Test
    fun `분기 B 문구는 보호 기능을 끄라고 하지 않고 보호자 확인을 권한다(D68 ②)`() {
        val t = Onboarding.PROTECTED_TEXT
        assertTrue("보호자" in t || "가족" in t, t)
        assertTrue(listOf("끄세요", "꺼 주세요", "해제하세요", "해제해 주세요").none { it in t }, t)
    }

    @Test
    fun `분기 B에서 상태가 바뀌어 돌아오면 멈췄던 단계부터 이어 간다`() {
        device.ap = true
        val o = onboarding()
        assertEquals(OnboardingScreen.Protected, o.screen)
        o.recheckProtection()
        assertEquals(OnboardingScreen.Protected, o.screen, "그대로면 남는다")
        device.ap = false
        o.recheckProtection()
        assertEquals(OnboardingScreen.Greeting, o.screen)

        // 동의·가족 단계 뒤 설정에서 돌아오다 분기 B로 간 경우 → 알림 단계부터
        device.ap = null
        val p = onboarding()
        p.next(); p.accept(); p.chooseHelp(HelpMode.ALONE)
        device.notifications = true
        p.onNotificationResult(true)
        device.ap = true
        p.onReturnedFromSettings()
        assertEquals(OnboardingScreen.Protected, p.screen)
        device.accessibility = true
        p.recheckProtection()
        assertEquals(OnboardingScreen.Ready, p.screen)
    }

    @Test
    fun `고지 문구 버전이 바뀌면 동의만 다시 묻고 나머지 단계는 건너뛴다`() {
        device.notifications = true
        device.accessibility = true
        val o = onboarding()
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE); o.finish()
        store.put("consent.version", (Disclosure.VERSION - 1).toString())
        val again = onboarding()
        assertFalse(again.isDone)
        assertEquals(OnboardingScreen.Disclosure(), again.screen)
        again.accept()
        assertEquals(OnboardingScreen.Ready, again.screen)
    }

    @Test
    fun `마이크 권한 단계가 없다(첫 음성 사용 때 묻는다, D66 수정 ①)`() {
        val names = OnboardingScreen::class.java.declaredClasses.map { it.simpleName.lowercase() }
        assertTrue(names.none { "audio" in it || "mic" in it || "voice" in it }, names.toString())
    }
}
