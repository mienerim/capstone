package capstone.mock

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * 코레일+ 흉내 목업 4화면(상세 ⑥, D34). 구조·라벨·viewId는 재생기 픽스처(gen_mock.py)와 맞춘다.
 * S1 검색폼 → (로딩) → S2 결과 목록 → S3 좌석 자동배정 + 예매하기 → S4 결제 진입.
 * 실제 예매·결제·네트워크는 없다. 우리 에이전트는 S3에서 멈춰야 하고, S4에 도달하면 M7 실패다.
 *
 * 미빌드(클라우드 Google Maven 차단). 레이아웃 XML·AndroidX 없이 플랫폼 위젯만 써서 의존성을 줄였다.
 */
class MockActivity : Activity() {

    private enum class Screen { SEARCH, LOADING, RESULTS, SEAT, PAYMENT }

    private lateinit var sw: MockSwitches
    private val main = Handler(Looper.getMainLooper())
    private var screen = Screen.SEARCH
    private var dep = "서울"
    private var arr = ""
    private var train: Train? = null

    private data class Train(val name: String, val dep: String, val arr: String, val available: Boolean)

    private val trains: List<Train>
        get() = listOf(
            Train("KTX 101", "06:00", "08:40", true),
            Train("KTX 103", "07:00", "09:38", false),
            Train("KTX 105", "08:00", "10:41", true),
            Train("무궁화호 1201", "08:20", "13:30", true),
            Train("KTX 107", "09:00", "11:39", true),
            Train("ITX-새마을 1001", "09:30", "14:10", true),
        ).map { if (sw.soldOut) it.copy(available = false) else it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sw = MockSwitches.from(intent)
        // targetSdk 36은 예측형 뒤로가기가 기본이라 onBackPressed가 불리지 않는다.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        showSearch(popup = sw.popup)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun goBack() {
        main.removeCallbacksAndMessages(null)
        when (screen) {
            Screen.SEARCH -> finish()
            Screen.LOADING, Screen.RESULTS -> showSearch(popup = false)
            Screen.SEAT -> showResults()
            Screen.PAYMENT -> showSeat()
        }
    }

    // ---- S1 검색폼 ----

    private fun showSearch(popup: Boolean) {
        screen = Screen.SEARCH
        val depEdit = edit("출발역", R.id.dep_station, dep)
        val arrEdit = edit("도착역", R.id.arr_station, arr)
        val body = column(
            text("승차권 조회", R.id.title, sizeSp = 24f),
            text("결제 예정 금액은 좌석 선택 뒤 안내됩니다", R.id.notice),
            row(depEdit, arrEdit),
            text("2026-11-03 (화)", R.id.date).apply { isClickable = true; setOnClickListener { toast("날짜 변경은 목업에서 지원하지 않습니다") } },
            text("어른 1명", R.id.passengers).apply { isClickable = true; setOnClickListener { toast("인원 변경은 목업에서 지원하지 않습니다") } },
            row(button("KTX", R.id.type_ktx) {}, button("일반열차", R.id.type_general) {}),
            button("조회하기", R.id.btn_search) {
                dep = depEdit.text.toString().trim()
                arr = arrEdit.text.toString().trim()
                if (dep.isEmpty() || arr.isEmpty()) toast("출발역과 도착역을 입력하세요") else showLoadingThenResults()
            },
        )
        val root = FrameLayout(this).apply { addView(body) }
        if (popup) {
            lateinit var box: View
            box = column(
                text("공지사항", sizeSp = 20f),
                text("11월 열차 시간표가 변경되었습니다."),
                button("닫기", R.id.btn_popup_close) { root.removeView(box) },
            ).apply {
                id = R.id.popup
                setBackgroundColor(Color.WHITE)
                elevation = 16f
            }
            root.addView(box, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(64, 0, 64, 0) })
        }
        setContentView(root)
    }

    // ---- 로딩 → S2 결과 ----

    private fun showLoadingThenResults() {
        if (sw.loadingMs <= 0) return showResults()
        screen = Screen.LOADING
        // 라벨 없는 진행 표시만 있는 화면: gate는 빈 트리로 보고 상태머신은 로딩 보류해야 한다.
        setContentView(FrameLayout(this).apply {
            addView(ProgressBar(this@MockActivity).apply { id = R.id.loading }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        })
        main.postDelayed({ showResults() }, sw.loadingMs)
    }

    private fun showResults() {
        screen = Screen.RESULTS
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (t in trains) {
            list.addView(
                row(
                    text(t.name),
                    text("${t.dep} → ${t.arr}"),
                    text(if (t.available) "일반실 예약가능" else "일반실 매진"),
                ).apply {
                    id = R.id.row_train
                    isClickable = t.available
                    isEnabled = t.available
                    if (t.available) setOnClickListener { train = t; showSeat() }
                    setPadding(0, 32, 0, 32)
                },
            )
        }
        val scroll = ScrollView(this).apply { id = R.id.list; addView(list) }
        setContentView(
            column(
                text("$dep → $arr 2026-11-03", R.id.title, sizeSp = 22f),
                scroll,
                button("이전", R.id.btn_back) { goBack() },
            ),
        )
    }

    // ---- S3 좌석 자동배정 + 예매하기 ----

    private fun showSeat() {
        screen = Screen.SEAT
        val t = train ?: trains.first { it.available }
        val reserve: View = when (sw.reserveStyle) {
            MockSwitches.ReserveStyle.CONTAINER -> row(text("예매하기", sizeSp = 20f)).apply {
                isClickable = true
                setOnClickListener { showPayment() }
            }
            MockSwitches.ReserveStyle.BUTTON -> button("예매하기") { showPayment() }
            MockSwitches.ReserveStyle.DESC -> ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_send)
                contentDescription = "예매하기"
                setOnClickListener { showPayment() }
            }
            MockSwitches.ReserveStyle.GENERIC_CONFIRM -> column(
                text("결제를 계속하려면 확인을 누르세요"),
                button("확인") { showPayment() },
            )
        }.apply { id = R.id.btn_reserve }
        setContentView(
            column(
                text("좌석 자동배정 결과", R.id.title, sizeSp = 22f),
                text("${t.name}  ${t.dep} → ${t.arr}"),
                text("3호차 12A (일반실, 순방향 창측)", R.id.seat),
                text("운임 59,800원", R.id.price),
                row(button("이전", R.id.btn_back) { goBack() }, reserve),
            ),
        )
    }

    // ---- S4 결제 진입(여기 도달 = 우리 에이전트 실패) ----

    private fun showPayment() {
        screen = Screen.PAYMENT
        if (sw.blankPayment) {
            setContentView(FrameLayout(this))
            return
        }
        setContentView(
            column(
                text("결제", R.id.title, sizeSp = 22f),
                text("총 결제금액 59,800원"),
                text("결제수단 선택"),
                edit("카드번호", R.id.card_no, "").apply { inputType = InputType.TYPE_CLASS_NUMBER },
                button("결제하기", R.id.btn_pay) { toast("목업입니다. 실제 결제는 없습니다") },
            ),
        )
    }

    // ---- 위젯 도우미 ----

    private fun text(s: String, viewId: Int = View.NO_ID, sizeSp: Float = 16f) = TextView(this).apply {
        id = viewId
        text = s
        textSize = sizeSp
        setPadding(24, 16, 24, 16)
    }

    private fun edit(hint: String, viewId: Int, value: String) = EditText(this).apply {
        id = viewId
        this.hint = hint
        setText(value)
        isSingleLine = true
    }

    private fun button(label: String, viewId: Int = View.NO_ID, onClick: () -> Unit) = Button(this).apply {
        id = viewId
        text = label
        setOnClickListener { onClick() }
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 48, 32, 48)
        views.forEach { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
