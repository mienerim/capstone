package capstone.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import capstone.gate.GatePolicy
import capstone.gate.exec.RawActuator
import capstone.gate.exec.SnapshotSource
import capstone.service.core.A11yEventKind
import capstone.service.core.DeviceInfo
import capstone.service.core.DeviceOps
import capstone.service.core.DumpRecorder
import capstone.service.core.EventFilter
import capstone.service.core.FrameActuator
import capstone.service.core.FrameHolder
import capstone.service.core.TreeCapture
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 접근성 서비스(첫날 hello-world + 실행 경로). 두 가지만 한다:
 * ① 덤프: 허용 앱 화면이 바뀌면 마스킹한 스냅샷을 filesDir/dump.jsonl에 한 줄씩(10-27 덤프 절차)
 * ② 실행 경로 제공: [actuator]·[snapshots]를 app이 GatedExecutor에 끼운다. 판단·누를 대상 선택은 하지 않는다.
 *
 * 미빌드(클라우드 Google Maven 차단). 10-26 팀 PC에서 컴파일·실기기 확인.
 */
class DumperService : AccessibilityService() {

    // 허용목록 밖 앱 창은 순회하지 않는다(D63 ②). 실행 경로(frames)와 덤프가 같은 캡처를 쓴다.
    private val capture = TreeCapture(targetPackages = TARGET_PACKAGES)
    private val events = EventFilter(TARGET_PACKAGES)
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var writer: FileWriter? = null
    private var recorder: DumpRecorder? = null

    /** 마지막 캡처를 들고 NodeRef를 해석한다. capture()는 백그라운드 스레드에서만 부른다. */
    val snapshots: SnapshotSource get() = frames
    val actuator: RawActuator get() = frameActuator

    private val frames = FrameHolder {
        // 화면 전환 직후 트리가 흔들리는 시간(300~500ms 가설, I-11). 실측으로 보정.
        SystemClock.sleep(SETTLE_MS)
        capture.capture(windows.map(::AndroidA11yWindow), System.currentTimeMillis())
    }
    private val frameActuator by lazy { FrameActuator(frames, GestureOps()) }

    override fun onServiceConnected() {
        worker = HandlerThread("a11y-dump").also { it.start() }
        handler = Handler(worker.looper)
        val file = File(filesDir, DUMP_FILE)
        writer = FileWriter(file, true)
        val session = "s" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recorder = DumpRecorder(writer!!, TARGET_PACKAGES, deviceInfo(), session)
        instance = this
        Log.i(TAG, "connected, dump → ${file.absolutePath} (session $session)")
        scheduleDump()
    }

    /** 이벤트는 '다시 볼 때'라는 신호로만 쓴다. 내용은 읽지 않고, 판단은 캡처한 창 목록으로 한다(D63 ②). */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> A11yEventKind.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> A11yEventKind.WINDOW_CONTENT_CHANGED
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> A11yEventKind.WINDOWS_CHANGED
            else -> A11yEventKind.OTHER
        }
        if (events.shouldCapture(kind, event.packageName?.toString())) scheduleDump()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        if (::worker.isInitialized) worker.quitSafely()
        writer?.close()
        super.onDestroy()
    }

    /** 이벤트가 몰려도 마지막 것 뒤 한 번만 덤프한다(디바운스). */
    private fun scheduleDump() {
        if (!::handler.isInitialized) return
        handler.removeCallbacks(dumpTask)
        handler.postDelayed(dumpTask, DEBOUNCE_MS)
    }

    private val dumpTask = Runnable {
        val rec = recorder ?: return@Runnable
        try {
            val snap = capture.capture(windows.map(::AndroidA11yWindow), System.currentTimeMillis()).snapshot
            val r = rec.record(snap)
            writer?.flush()
            if (r is DumpRecorder.Result.Written) Log.i(TAG, "dump ${r.snapId}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "dump failed", e)
        }
    }

    private fun deviceInfo(): DeviceInfo = DeviceInfo(
        sdk = Build.VERSION.SDK_INT,
        // One UI 버전은 공개 API가 없다. 측정 시트에 사람이 적는다(I-43).
        oneUi = null,
        appVersion = { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0).versionName
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        },
    )

    /** 좌표 탭은 FrameActuator가 노드 bounds 중심으로만 부른다. 결과를 기다리므로 메인 스레드에서 부르지 않는다. */
    private inner class GestureOps : DeviceOps {
        override fun tap(x: Int, y: Int): Boolean {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build()
            val done = CountDownLatch(1)
            var ok = false
            val sent = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        ok = true
                        done.countDown()
                    }
                    override fun onCancelled(g: GestureDescription?) = done.countDown()
                },
                null,
            )
            return sent && done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok
        }

        override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    }

    companion object {
        private const val TAG = "DumperService"
        const val DUMP_FILE: String = "dump.jsonl"
        private const val DEBOUNCE_MS = 800L
        private const val SETTLE_MS = 400L
        private const val TAP_MS = 50L
        private const val GESTURE_TIMEOUT_MS = 1_000L

        /**
         * 서비스가 노드를 읽는 앱(덤프·캡처 순회 허용목록). 시나리오 `Flow.packages`는 이 안에 있어야 한다.
         * 병원 앱 패키지는 10-28 드라이런 때 추가(상세 ⑥).
         */
        val TARGET_PACKAGES: Set<String> = setOf(GatePolicy.KORAIL_PLUS, GatePolicy.MOCK_APP)

        /** app이 실행 경로를 조립할 때 쓴다. 서비스가 꺼져 있으면 null. */
        @Volatile
        var instance: DumperService? = null
            private set
    }
}
