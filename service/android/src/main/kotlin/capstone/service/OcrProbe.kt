package capstone.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 실험(10-30): 코레일톡 열차 목록 카드는 접근성에 글자 없는 Button으로만 나온다(uiautomator로도 동일).
 * 화면을 찍어 그 카드 영역을 기기 안 OCR(ML Kit 한국어, 번들 모델)로 읽을 수 있는지와 걸리는 시간을 잰다.
 *
 * adb로만 실행한다(DUMP 권한 = 셸·시스템만 보낼 수 있음). 이미지는 저장하지 않고 결과는 logcat(OcrProbe)에만.
 *   adb shell am broadcast -a capstone.service.OCR_PROBE
 * A: 카드마다 잘라서 OCR / B: 화면 전체를 한 번 OCR하고 줄을 카드 bounds에 배정.
 */
class OcrProbe(private val service: AccessibilityService, private val handler: Handler) {

    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post { runCatching { probe() }.onFailure { Log.w(TAG, "probe failed", it) } }
        }
    }

    fun register() {
        service.registerReceiver(
            receiver, IntentFilter(ACTION), android.Manifest.permission.DUMP, handler, Context.RECEIVER_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { service.unregisterReceiver(receiver) }
        runCatching { recognizer.close() }
    }

    private fun probe() {
        val cards = unlabeledClickables()
        Log.i(TAG, "cards=${cards.size} ${cards.joinToString(" ") { it.toShortString() }}")
        if (cards.isEmpty()) return

        val t0 = SystemClock.elapsedRealtime()
        val shot = screenshot() ?: run {
            Log.w(TAG, "screenshot failed")
            return
        }
        val tShot = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "screenshot ${shot.width}x${shot.height} ${tShot}ms")

        // 모델 첫 로드 시간이 A에 섞이지 않게 작은 이미지로 한 번 데운다.
        val tw = SystemClock.elapsedRealtime()
        Tasks.await(recognizer.process(InputImage.fromBitmap(crop(shot, cards[0]), 0)), 30, TimeUnit.SECONDS)
        Log.i(TAG, "warmup ${SystemClock.elapsedRealtime() - tw}ms")

        // A: 카드별
        val ta = SystemClock.elapsedRealtime()
        cards.forEachIndexed { i, r ->
            val t = SystemClock.elapsedRealtime()
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(crop(shot, r), 0)), 30, TimeUnit.SECONDS)
            Log.i(TAG, "A#$i ${r.toShortString()} ${SystemClock.elapsedRealtime() - t}ms | ${oneLine(text.text)}")
        }
        Log.i(TAG, "A total ${SystemClock.elapsedRealtime() - ta}ms")

        // B: 전체 한 번
        val tb = SystemClock.elapsedRealtime()
        val full = Tasks.await(recognizer.process(InputImage.fromBitmap(shot, 0)), 30, TimeUnit.SECONDS)
        val perCard = Array(cards.size) { StringBuilder() }
        full.textBlocks.flatMap { it.lines }.forEach { line ->
            val box = line.boundingBox ?: return@forEach
            val i = cards.indexOfFirst { it.contains(box.centerX(), box.centerY()) }
            if (i >= 0) perCard[i].append(line.text).append(" / ")
        }
        Log.i(TAG, "B total ${SystemClock.elapsedRealtime() - tb}ms")
        perCard.forEachIndexed { i, s -> Log.i(TAG, "B#$i ${cards[i].toShortString()} | $s") }
        shot.recycle()
    }

    /** 대상 앱 창에서 글자(자신·자손 모두)가 없는 누를 수 있는 노드의 화면 좌표. */
    private fun unlabeledClickables(): List<Rect> {
        val out = mutableListOf<Rect>()
        service.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() in DumperService.TARGET_PACKAGES }
            .forEach { walk(it, out) }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Rect>) {
        if (n.isClickable && n.isVisibleToUser && !hasText(n)) {
            out += Rect().also(n::getBoundsInScreen)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, out) }
    }

    private fun hasText(n: AccessibilityNodeInfo): Boolean {
        if (!n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()) return true
        for (i in 0 until n.childCount) if (n.getChild(i)?.let(::hasText) == true) return true
        return false
    }

    private fun screenshot(): Bitmap? {
        var bmp: Bitmap? = null
        val done = CountDownLatch(1)
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    result.hardwareBuffer.use { hb ->
                        bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    done.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot error $errorCode")
                    done.countDown()
                }
            },
        )
        done.await(5, TimeUnit.SECONDS)
        return bmp
    }

    private fun crop(src: Bitmap, r: Rect): Bitmap {
        val c = Rect(r).apply { intersect(0, 0, src.width, src.height) }
        return Bitmap.createBitmap(src, c.left, c.top, c.width().coerceAtLeast(1), c.height().coerceAtLeast(1))
    }

    private fun oneLine(s: String) = s.replace('\n', '/')

    companion object {
        private const val TAG = "OcrProbe"
        const val ACTION = "capstone.service.OCR_PROBE"
    }
}
