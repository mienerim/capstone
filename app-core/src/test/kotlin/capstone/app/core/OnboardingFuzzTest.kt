package capstone.app.core

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 온보딩 퍼즈(9단계 테스트 보강). OnboardingTest는 정해진 순서만 본다. 여기서는 MainActivity가 그리는 버튼과
 * Activity 수명 주기(onResume·권한 결과·프로세스 재시작)를 아무 순서로 일으키고, 그 사이 사용자가 앱 밖에서 바꾸는
 * 기기 상태를 섞는다: 알림 권한·진행 채널 끄기(D69 ⑤), 접근성 켜기·끄기, Advanced Protection 켜기·끄기·불명(D68·D71).
 * 권한 대화상자는 Android처럼 모델링한다 — 이미 허용이면 묻지 않고 허용, 두 번 거부하면 묻지 않고 거부.
 *
 * 불변식(화면이 바뀐 순간의 기기 상태로 본다):
 *  - 버튼·수명 주기 호출에서 예외가 없다,
 *  - AP 켜짐이 확인된 기기에서는 설정 단계(알림·접근성·제한된 설정·분기 A·완료 준비)로 들어가지 않는다(D71 '안내 후 중단'),
 *  - '켜졌어요'(Ready)로 들어갈 때는 동의(현 고지 버전)·알림(D69 ⑤ 정의)·접근성이 모두 갖춰져 있다,
 *  - 끝으로 기기를 모두 갖춘 뒤 착한 사용자처럼 누르면 12번 안에 완료된다(갇히는 화면 없음).
 * 시드는 고정이라 실패하면 seed와 동작 기록으로 재현된다.
 */
class OnboardingFuzzTest {
    private class Device : DeviceChecks {
        var permission = false
        var channelOn = true
        var deniedTimes = 0
        var accessibility = false
        var ap: Boolean? = null
        override fun notificationsGranted() = permission && channelOn
        override fun accessibilityEnabled() = accessibility
        override fun advancedProtection() = ap
    }

    private val setupScreens = setOf(
        OnboardingScreen.Notifications::class,
        OnboardingScreen.Accessibility::class,
        OnboardingScreen.RestrictedSettings::class,
        OnboardingScreen.AskFamily::class,
        OnboardingScreen.Ready::class,
    )

    private class World(seed: Long) {
        val rnd = Random(seed)
        val store = MemoryStore()
        val device = Device()
        var onboarding = Onboarding(store, device, nowMs = { 42 })

        /** MainActivity.wentToSettings */
        var wentToSettings = false
        val trace = mutableListOf<String>()
        val violations = mutableListOf<String>()
    }

    /** 사용자가 앱 밖(설정·알림 창·다른 앱)에서 바꿀 수 있는 것. */
    private fun World.outside() {
        when (rnd.nextInt(6)) {
            0 -> device.accessibility = !device.accessibility
            1 -> device.ap = listOf(true, false, null).random(rnd)
            2 -> device.permission = !device.permission
            3 -> device.channelOn = !device.channelOn
            else -> Unit
        }
        trace += "outside a11y=${device.accessibility} ap=${device.ap} perm=${device.permission} ch=${device.channelOn}"
    }

    /** MainActivity.onResume과 같은 분기. */
    private fun World.resume() {
        if (wentToSettings) {
            wentToSettings = false
            act("onReturnedFromSettings") { onReturnedFromSettings() }
        } else {
            act("onResumed") { onResumed() }
        }
    }

    /** '알림 허용하기' → requestPermissions → onRequestPermissionsResult. */
    private fun World.requestNotifications() {
        val granted = when {
            device.permission -> true
            device.deniedTimes >= 2 -> false
            else -> rnd.nextBoolean().also { if (it) device.permission = true else device.deniedTimes++ }
        }
        if (onboarding.screen is OnboardingScreen.Notifications) act("onNotificationResult($granted)") { onNotificationResult(granted) }
    }

    private fun World.openSettings(changeOutside: Boolean = true) {
        wentToSettings = true
        trace += "openAccessibilitySettings"
        if (changeOutside && rnd.nextInt(3) > 0) outside()
        resume()
    }

    /** 지금 화면에 그려진 버튼 하나를 누른다(MainActivity.onboardingViews와 같은 목록). */
    private fun World.tap() {
        when (onboarding.screen) {
            OnboardingScreen.Greeting -> act("next") { next() }
            is OnboardingScreen.Disclosure -> if (rnd.nextInt(4) == 0) act("decline") { decline() } else act("accept") { accept() }
            OnboardingScreen.Family -> act("chooseHelp") { chooseHelp(HelpMode.entries.random(rnd)) }
            is OnboardingScreen.Notifications -> requestNotifications()
            is OnboardingScreen.Accessibility -> if (rnd.nextBoolean()) openSettings() else act("switchBlocked") { switchBlocked() }
            OnboardingScreen.RestrictedSettings -> if (rnd.nextBoolean()) openSettings() else act("retry") { retry() }
            is OnboardingScreen.AskFamily -> when (rnd.nextInt(3)) {
                0 -> trace += "shareGuide"
                1 -> act("retry") { retry() }
                else -> act("switchBlocked") { switchBlocked() }
            }
            OnboardingScreen.Ready -> act("finish") { finish() }
            OnboardingScreen.Protected -> trace += "shareGuide"
            OnboardingScreen.Done -> Unit
        }
    }

    private fun World.act(name: String, body: Onboarding.() -> Unit) {
        val before = onboarding.screen
        trace += "$name @ $before"
        try {
            onboarding.body()
        } catch (t: Throwable) {
            violations += "exception:${t::class.simpleName}:$name"
            return
        }
        val after = onboarding.screen
        if (after != before) checkEntered(after, name)
    }

    private fun World.checkEntered(s: OnboardingScreen, cause: String) {
        if (device.ap == true && s::class in setupScreens) violations += "ap_setup:${s::class.simpleName}:$cause"
        if (s == OnboardingScreen.Ready) {
            val unmet = buildList {
                if (ConsentManager(store).required) add("consent")
                if (!device.notificationsGranted()) add("notifications")
                if (!device.accessibilityEnabled()) add("accessibility")
            }
            if (unmet.isNotEmpty()) violations += "ready_unmet:$unmet:$cause"
        }
    }

    /** 기기를 모두 갖춘 뒤 착한 사용자. 완료되지 않으면 갇힌 것이다. */
    private fun World.liveness(): Boolean {
        device.permission = true; device.channelOn = true; device.accessibility = true; device.ap = listOf(false, null).random(rnd)
        trace += "-- liveness ap=${device.ap}"
        resume()
        repeat(12) {
            if (onboarding.isDone) return true
            when (onboarding.screen) {
                is OnboardingScreen.Disclosure -> act("accept") { accept() }
                is OnboardingScreen.AskFamily, OnboardingScreen.RestrictedSettings, is OnboardingScreen.Accessibility -> openSettings(changeOutside = false)
                OnboardingScreen.Protected -> resume()
                else -> tap()
            }
        }
        return onboarding.isDone
    }

    private fun runOnce(seed: Long, steps: Int = 60): World {
        val w = World(seed)
        with(w) {
            repeat(steps) {
                when (rnd.nextInt(10)) {
                    in 0..5 -> tap()
                    6, 7 -> { outside(); resume() }
                    8 -> { outside(); trace += "process restart"; onboarding = Onboarding(store, device, nowMs = { 42 }); checkEntered(onboarding.screen, "init") }
                    else -> resume()
                }
            }
            if (!liveness()) violations += "stuck:${onboarding.screen}"
        }
        return w
    }

    @Test
    fun `온보딩 퍼즈 - AP 켜짐이면 설정 단계로 가지 않고, 완료 준비는 전제조건이 갖춰졌을 때만, 갇히는 화면 없음`() {
        val seeds = 150000L until 152000L
        val byKind = sortedMapOf<String, Int>()
        val first = mutableListOf<String>()
        for (seed in seeds) {
            val w = runOnce(seed)
            for (v in w.violations) byKind.merge(v.substringBefore(':') + ":" + v.split(':').getOrElse(1) { "" }, 1, Int::plus)
            if (w.violations.isNotEmpty() && first.size < 3) {
                first += "seed=$seed ${w.violations.first()}\n" + w.trace.takeLast(12).joinToString("\n")
            }
        }
        if (byKind.isNotEmpty()) fail("위반 ${byKind.values.sum()}건 $byKind\n\n${first.joinToString("\n\n")}")
    }

    @Test
    fun `설정에서 접근성을 켜고 돌아와도 그 사이 AP가 켜졌으면 분기 B(D71)`() {
        val d = Device().apply { permission = true }
        val o = Onboarding(MemoryStore(), d)
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        assertEquals(OnboardingScreen.Accessibility(), o.screen)
        d.accessibility = true
        d.ap = true
        o.onReturnedFromSettings()
        assertEquals(OnboardingScreen.Protected, o.screen)
    }

    @Test
    fun `권한은 허용인데 진행 채널이 꺼져 있으면 알림 단계에 남는다(D69 ⑤)`() {
        val d = Device().apply { channelOn = false; accessibility = true }
        val o = Onboarding(MemoryStore(), d)
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        d.permission = true
        o.onNotificationResult(true)
        assertEquals(OnboardingScreen.Notifications(denied = true), o.screen)
        d.channelOn = true
        o.onResumed()
        assertEquals(OnboardingScreen.Ready, o.screen, "설정에서 채널을 켜고 돌아오면 이어 간다")
    }

    @Test
    fun `완료 준비 화면에서 앱 밖에서 알림을 끄고 돌아오면 알림 단계로 돌아간다`() {
        val d = Device().apply { permission = true; accessibility = true }
        val o = Onboarding(MemoryStore(), d)
        o.next(); o.accept(); o.chooseHelp(HelpMode.ALONE)
        assertEquals(OnboardingScreen.Ready, o.screen)
        d.channelOn = false
        o.onResumed()
        assertEquals(OnboardingScreen.Notifications(), o.screen)
        assertTrue(o.startBlocker() != null)
    }
}
