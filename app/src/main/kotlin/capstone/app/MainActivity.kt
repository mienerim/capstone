package capstone.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import capstone.agent.RunState
import capstone.agent.ScenarioRunner
import capstone.app.core.AppScreen
import capstone.app.core.AppSession
import capstone.app.core.Disclosure
import capstone.app.core.HelpMode
import capstone.app.core.Onboarding
import capstone.app.core.OnboardingScreen
import capstone.app.core.Scenario
import capstone.app.core.WaitCue
import capstone.app.core.WaitFeedback
import capstone.llm.ContextBudget
import capstone.llm.GuardedGenerator
import capstone.llm.Intent as SlotIntent
import capstone.llm.ParseResult
import capstone.llm.SlotParser
import capstone.llm.litertlm.LiteRtLmRunner
import capstone.scenarios.MockKorail
import capstone.scenarios.MockKorailFlow
import java.io.File

/**
 * 데모 앱 화면(⑧). [AppSession]이 정한 화면을 큰 글씨·큰 버튼으로 그리기만 한다.
 * 실행 대상은 지금 목업 앱뿐이다(코레일+ 시나리오는 10-27 덤프 뒤). 음성 입력(STT)은 10-28 W03 실측 뒤 붙이고,
 * 그 전에는 글자 입력과 버튼으로 같은 흐름을 탄다.
 *
 * 미빌드(클라우드 Google Maven 차단). 레이아웃 XML·AndroidX 없이 플랫폼 위젯만 쓴다(mock-app과 같은 이유).
 */
class MainActivity : Activity() {

    private lateinit var session: AppSession
    private lateinit var onboarding: Onboarding
    private lateinit var host: AgentHost
    private lateinit var notice: ProgressNotice
    private val main = Handler(Looper.getMainLooper())
    private val llmThread = HandlerThread("llm").also { it.start() }
    private val llm = Handler(llmThread.looper)
    private var parser: SlotParser? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
    private var wentToSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notice = ProgressNotice(this)
        host = AgentHost(filesDir) { r, st -> onRunState(r, st) }
        val store = PrefsStore(this)
        onboarding = Onboarding(store, AndroidDeviceChecks(this))
        session = AppSession(
            store,
            scenarioFor = { s -> if (s.intent == SlotIntent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = false) else null },
            startRunner = host::start,
        )
        handlePause(intent)
        render()
    }

    /** ⑤ 접근성 설정에서 돌아오면 켜졌는지 확인한다(D66 ⑦ 활성 자동 검사). */
    override fun onResume() {
        super.onResume()
        if (wentToSettings) {
            wentToSettings = false
            onboarding.onReturnedFromSettings()
            render()
        } else if (!onboarding.isDone) {
            // 분기 B(D68 ③·D71): AP 상태는 매번 다시 본다. 우리가 AP를 끄라고 하지는 않는다.
            // 알림·켜졌어요 화면은 앱 밖에서 바뀐 알림·접근성도 다시 본다(I-90). 권한 결과는 onResume보다 먼저 온다.
            onboarding.onResumed()
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && onboarding.screen is OnboardingScreen.Notifications) {
            onboarding.onNotificationResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            render()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePause(intent)
        render()
    }

    override fun onDestroy() {
        host.close()
        notice.hide()
        main.removeCallbacks(waitTick)
        tone.release()
        llm.post { parser = null }
        llmThread.quitSafely()
        super.onDestroy()
    }

    /** 알림의 '멈춰'(D39): 우리 Activity가 앞에 오면 gate가 우리 패키지를 막고, 러너도 다음 제출 전에 멈춘다. */
    private fun handlePause(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PAUSE, false) == true) {
            intent.removeExtra(EXTRA_PAUSE)
            session.pause()
        }
    }

    private fun onRunState(from: ScenarioRunner, st: RunState) {
        if (!session.onRunState(st, from)) return
        when (st) {
            is RunState.Done, is RunState.Stopped -> {
                notice.hide()
                bringToFront()
            }
            // ASK_USER는 우리 화면 큰 버튼으로 묻는다(오버레이는 service 쪽 작업, 10-28).
            is RunState.AskUser, is RunState.PausedForUser -> bringToFront()
            else -> notice.show(capstone.app.core.Readback.runState(st))
        }
        render()
    }

    // ---- 그리기 ----

    private fun render() {
        val content: List<View> = if (!onboarding.isDone) onboardingViews(onboarding.screen) else when (val s = session.screen) {
            is AppScreen.Consent -> consent(s)
            is AppScreen.Home -> home(s)
            is AppScreen.Waiting -> buildList {
                add(title(s.text))
                if (s.asking) {
                    add(big(WaitFeedback.KEEP) { session.answerWait(true); render() })
                    add(big(WaitFeedback.CANCEL) { session.answerWait(false); render() })
                }
            }
            is AppScreen.AskSlot -> listOf(title(s.question)) + s.options.map { o -> big(o) { session.choose(o); render() } }
            is AppScreen.Confirm -> listOf(
                title(s.text),
                big("네, 찾아 주세요") { startRun() },
                big("아니요") { session.confirm(false); render() },
            )
            is AppScreen.Running -> running(s)
            is AppScreen.Finished -> listOf(
                title(s.text),
                big("처음으로") { session.reset(); render() },
            )
        }
        setContentView(ScrollView(this).apply { addView(column(content)) })
    }

    /** 온보딩 이후에는 나오지 않는다(온보딩 ②가 같은 동의를 기록). 남겨 둔 것은 동의가 지워진 경우의 방어선. */
    private fun consent(s: AppScreen.Consent): List<View> = buildList {
        add(title(Disclosure.title))
        Disclosure.sections.forEach { add(body("■ ${it.title}\n${it.body}")) }
        if (s.declined) add(body("동의하지 않으시면 이 앱을 쓸 수 없어요. 앱을 닫으셔도 됩니다."))
        add(big(Disclosure.ACCEPT) { session.acceptConsent(); render() })
        add(big(Disclosure.DECLINE) { session.declineConsent(); render() })
    }

    /** 첫 실행 온보딩 v1(D66). 문구는 가안, 접근성 메뉴 그림은 실기기 캡처 3장으로 교체 예정. */
    private fun onboardingViews(s: OnboardingScreen): List<View> = when (s) {
        OnboardingScreen.Greeting -> listOf(title(Onboarding.GREETING_TEXT), big("다음") { onboarding.next(); render() })
        is OnboardingScreen.Disclosure -> buildList {
            add(title(Disclosure.title))
            Disclosure.sections.forEach { add(body("■ ${it.title}\n${it.body}")) }
            if (s.declined) add(body("동의하지 않으시면 이 앱을 쓸 수 없어요. 앱을 닫으셔도 됩니다."))
            add(big(Disclosure.ACCEPT) { onboarding.accept(); render() })
            add(big(Disclosure.DECLINE) { onboarding.decline(); render() })
        }
        OnboardingScreen.Family -> listOf(
            title("누구와 함께 설정하시나요?"),
            big("혼자 할게요") { onboarding.chooseHelp(HelpMode.ALONE); render() },
            big("가족과 함께 할게요") { onboarding.chooseHelp(HelpMode.WITH_FAMILY); render() },
        )
        is OnboardingScreen.Notifications -> buildList {
            add(title("진행 상황을 알림으로 알려 드려요. 알림의 '그만'으로 언제든 멈출 수 있어요."))
            if (s.denied) add(body("알림이 꺼져 있으면 찾기를 시작하지 않아요. 다시 눌러 허용해 주세요. 안 되면 설정 > 알림에서 켜 주세요."))
            add(big("알림 허용하기") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS) })
        }
        is OnboardingScreen.Accessibility -> buildList {
            add(title("이제 '접근성'에서 이 앱을 켜 주세요."))
            add(body("① 설정으로 가기를 누르세요\n② '설치된 앱'을 누르세요\n③ 이 앱 이름을 누르세요\n④ 스위치를 켜세요\n⑤ '허용'을 누르세요\n⑥ 뒤로 가기를 눌러 돌아오세요"))
            if (s.failures > 0) add(body("아직 꺼져 있어요. 한 번 더 해 볼까요?"))
            add(big("설정으로 가기") { openAccessibilitySettings() })
            add(big("스위치가 안 눌려요") { onboarding.switchBlocked(); render() })
        }
        OnboardingScreen.RestrictedSettings -> listOf(
            title("스위치가 안 눌리면 먼저 이렇게 해 주세요."),
            body("설정 > 애플리케이션 > 이 앱 > 오른쪽 위 ⋮ > '제한된 설정 허용'을 누른 뒤, 다시 접근성에서 켜 주세요."),
            big("설정으로 가기") { openAccessibilitySettings() },
            big("처음 안내로") { onboarding.retry(); render() },
        )
        is OnboardingScreen.AskFamily -> listOfNotNull(
            title("설정이 어려우시면 가족에게 도움을 청해 보세요."),
            if (s.protectionUnknown) body(Onboarding.AP_UNKNOWN_HINT) else null,
            big("가족에게 안내 보내기") { shareGuide() },
            big("다시 해 볼게요") { onboarding.retry(); render() },
            big("스위치가 안 눌려요") { onboarding.switchBlocked(); render() },
        )
        OnboardingScreen.Ready -> listOf(
            title("켜졌어요! 결제 없이 연습을 한 번 해 볼까요?"),
            body(Onboarding.WARNING_NOTICE_TEXT),
            body(Onboarding.WARNING_SWITCH_TEXT),
            big("연습해 보기") { onboarding.finish(); session.reset(); session.startWithButtons(); render() },
            big("나중에 할게요") { onboarding.finish(); session.reset(); render() },
        )
        OnboardingScreen.Protected -> listOf(
            title(Onboarding.PROTECTED_TEXT),
            big("가족에게 안내 보내기") { shareGuide() },
        )
        OnboardingScreen.Done -> emptyList()
    }

    private fun openAccessibilitySettings() {
        wentToSettings = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** 분기 A: 안내 글을 가족에게(공유 시트). 이미지는 메뉴 캡처가 생기면 붙인다. 이 앱이 직접 보내지 않는다. */
    private fun shareGuide() {
        val text = "부모님 휴대폰에서 '말로 기차표 찾기' 앱의 접근성을 켜 주세요: 설정 > 접근성 > 설치된 앱 > 이 앱 > 켜기 > 허용. " +
            "스위치가 안 눌리면 설정 > 애플리케이션 > 이 앱 > ⋮ > '제한된 설정 허용' 뒤 다시 켜기."
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "안내 보내기"))
    }

    private fun home(s: AppScreen.Home): List<View> = buildList {
        add(title("어디 가는 기차를 찾을까요?"))
        s.message?.let { add(body(it)) }
        if (!host.ready) add(big("먼저 접근성 켜기") { openAccessibilitySettings() })
        val input = EditText(this@MainActivity).apply {
            hint = "예: 내일 부산 가는 KTX 예매해 줘"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
        add(input)
        add(big("이렇게 찾아 주세요") { parse(input.text.toString()) })
        add(big("버튼으로 찾기") { session.startWithButtons(); render() })
    }

    private fun running(s: AppScreen.Running): List<View> = buildList {
        add(title(s.text))
        when (val st = s.run) {
            is RunState.AskUser -> st.options.forEach { o -> add(big(o) { session.answer(o); resumeTarget() }) }
            is RunState.PausedForUser -> {
                add(big("계속") { session.resume(); resumeTarget() })
                add(big("그만하기") { session.reset(); notice.hide(); render() })
            }
            else -> add(big("멈춰") { session.pause() })
        }
    }

    // ---- 동작 ----

    private fun startRun() {
        // D62·D69 ⑤: 알림·접근성이 없거나 AP 켜짐(D71)이면 대행을 시작하지 않고 이 화면에서 안내한다.
        onboarding.startBlocker()?.let {
            session.reset(it)
            render()
            return
        }
        session.confirm(true)
        if (session.runner != null) resumeTarget() else render()
    }

    /** 대상 앱을 앞으로 띄우고 상태머신을 이어 돌린다. 우리 화면이 앞에 있으면 gate가 모든 액션을 막는다(D39). */
    private fun resumeTarget() {
        packageManager.getLaunchIntentForPackage(MockKorail.PKG)?.let { startActivity(it) }
        host.kick()
        render()
    }

    // Android 16: BAL_ALLOW_TOKEN 추정·NEW_TASK 필수 / Android 17: 변경 언급 없음, 실측 필요 (D72)
    // 기본 복귀 경로는 진행 알림·앱 아이콘(D69)이고, 이 호출이 막혀도 그 경로로 돌아올 수 있다.
    private fun bringToFront() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /**
     * LLM은 작업 스레드에서(콜드 로드 수 초). 모델이 없으면 다시 말해 달라는 쪽으로 간다(→ 2회 뒤 버튼).
     * 기다리는 동안 D73 대기 피드백(확인음·'생각 중'·필러·'계속 기다릴까요?'·25초 안전 중단)을 낸다.
     * 필러는 지금 글자만 — 음성(TTS)은 STT와 함께 W03 뒤에 붙인다.
     * D13 정정: STT를 붙이면 8GB 기기는 STT 종료·언로드 직후에 이 파싱(=엔진 initialize)을 시작한다. 선로드 금지.
     */
    private fun parse(text: String) {
        if (!session.acceptsRequest) return
        val ticket = session.beginParsing()
        onWaitTick()
        llm.post {
            val r = try {
                (parser ?: newParser())?.parse(text) ?: ParseResult.AskAgain(1, "no_model")
            } catch (e: RuntimeException) {
                ParseResult.AskAgain(1, "runner_error")
            }
            main.post {
                // 안전 중단·'그만' 뒤에 온 결과는 AppSession이 번호로 버린다.
                session.onParsed(r, ticket)
                main.removeCallbacks(waitTick)
                render()
            }
        }
    }

    private val waitTick = Runnable { onWaitTick() }

    private fun onWaitTick() {
        val cues = session.onWaitTick()
        if (WaitCue.Thinking in cues) tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        if (cues.isNotEmpty()) render()
        if (session.screen is AppScreen.Waiting) main.postDelayed(waitTick, WAIT_TICK_MS)
    }

    private fun newParser(): SlotParser? {
        val model = File(getExternalFilesDir(null), MODEL_FILE).takeIf { it.isFile }
            ?: File(filesDir, MODEL_FILE).takeIf { it.isFile }
            ?: return null
        // D77: KV 요청값은 전 기기 2048 하나(LLM 입력에 UI 트리가 없으므로 RAM으로 가르지 않는다).
        // D79: 실효 예산은 min(2048, 파일 KV) — 파서가 단계마다 '프롬프트 + 384 ≤ 실효 예산'을 먼저 검사한다.
        val runner = LiteRtLmRunner(model, cacheDir, maxNumTokens = ContextBudget.MAX_NUM_TOKENS)
        return SlotParser(GuardedGenerator(runner), budget = runner.budget).also { parser = it }
    }

    // ---- 위젯 ----

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 16, 0, 32)
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(0, 8, 0, 24)
    }

    private fun big(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        minHeight = 160
        setOnClickListener { onClick() }
    }

    private fun column(views: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 64, 40, 64)
        views.forEach {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })
        }
    }

    companion object {
        const val EXTRA_PAUSE = "pause"
        private const val REQ_NOTIFICATIONS = 1
        private const val WAIT_TICK_MS = 500L

        /** D40: adb push 위치(getExternalFilesDir) 또는 run-as 폴백(filesDir). */
        const val MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm"
    }
}
