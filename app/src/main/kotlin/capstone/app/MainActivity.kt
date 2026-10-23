package capstone.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
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
import capstone.app.core.AppScreen
import capstone.app.core.AppSession
import capstone.app.core.Disclosure
import capstone.app.core.Scenario
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
    private lateinit var host: AgentHost
    private lateinit var notice: ProgressNotice
    private val main = Handler(Looper.getMainLooper())
    private val llmThread = HandlerThread("llm").also { it.start() }
    private val llm = Handler(llmThread.looper)
    private var parser: SlotParser? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notice = ProgressNotice(this)
        host = AgentHost(filesDir) { st -> onRunState(st) }
        session = AppSession(
            PrefsStore(this),
            scenarioFor = { s -> if (s.intent == SlotIntent.TRAIN_BOOK) Scenario(MockKorailFlow.flow, liveService = false) else null },
            startRunner = host::start,
        )
        handlePause(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePause(intent)
        render()
    }

    override fun onDestroy() {
        host.close()
        notice.hide()
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

    private fun onRunState(st: RunState) {
        session.onRunState(st)
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
        val content: List<View> = when (val s = session.screen) {
            is AppScreen.Consent -> consent(s)
            is AppScreen.Home -> home(s)
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

    private fun consent(s: AppScreen.Consent): List<View> = buildList {
        add(title(Disclosure.title))
        Disclosure.sections.forEach { add(body("■ ${it.title}\n${it.body}")) }
        if (s.declined) add(body("동의하지 않으시면 이 앱을 쓸 수 없어요. 앱을 닫으셔도 됩니다."))
        add(big(Disclosure.ACCEPT) {
            session.acceptConsent()
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
            render()
        })
        add(big(Disclosure.DECLINE) { session.declineConsent(); render() })
    }

    private fun home(s: AppScreen.Home): List<View> = buildList {
        add(title("어디 가는 기차를 찾을까요?"))
        s.message?.let { add(body(it)) }
        if (!host.ready) add(big("먼저 접근성 켜기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val input = EditText(this@MainActivity).apply {
            hint = "예: 내일 부산 가는 KTX 예매해 줘"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
        add(input)
        add(big(if (busy) "알아듣는 중…" else "이렇게 찾아 주세요") { if (!busy) parse(input.text.toString()) })
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
        if (!host.ready) {
            session.reset("먼저 '접근성 켜기'를 눌러 이 앱을 켜 주세요.")
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

    private fun bringToFront() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** LLM은 작업 스레드에서(콜드 로드 수 초). 모델이 없으면 다시 말해 달라는 쪽으로 간다(→ 2회 뒤 버튼). */
    private fun parse(text: String) {
        if (!session.acceptsRequest) return
        busy = true
        render()
        llm.post {
            val r = try {
                (parser ?: newParser())?.parse(text) ?: ParseResult.AskAgain(1, "no_model")
            } catch (e: RuntimeException) {
                ParseResult.AskAgain(1, "runner_error")
            }
            main.post {
                busy = false
                if (session.acceptsRequest) session.onParsed(r)
                render()
            }
        }
    }

    private fun newParser(): SlotParser? {
        val model = File(getExternalFilesDir(null), MODEL_FILE).takeIf { it.isFile }
            ?: File(filesDir, MODEL_FILE).takeIf { it.isFile }
            ?: return null
        return SlotParser(GuardedGenerator(LiteRtLmRunner(model, cacheDir))).also { parser = it }
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

        /** D40: adb push 위치(getExternalFilesDir) 또는 run-as 폴백(filesDir). */
        const val MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm"
    }
}
