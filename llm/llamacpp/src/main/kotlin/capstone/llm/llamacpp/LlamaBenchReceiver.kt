package capstone.llm.llamacpp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 실험(10-30): llama.cpp 엔진 벤치(개발 계획 1·2번). 앱 전용 외부 폴더의 문항 파일을 차례로 돌려
 * 결과를 jsonl과 logcat(LlamaBench)에 남긴다. 접근성 서비스가 같은 프로세스를 살려 두므로 스레드로 돌린다.
 *
 *   adb shell am broadcast -n capstone.app/capstone.llm.llamacpp.LlamaBenchReceiver \
 *     [--es model 파일명] [--es cases llama_cases.json] [--es out llama_results.jsonl] [--ei threads 6] [--ei ctx 4096]
 *     [--ez repack false]
 *
 * 문항 파일: [{"id":..., "system":..., "user":..., "max_tokens":256}, ...]
 */
class LlamaBenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val dir = context.getExternalFilesDir(null) ?: return
        val model = File(dir, intent.getStringExtra("model") ?: "Qwen3.5-4B-Q4_K_M.gguf")
        val cases = File(dir, intent.getStringExtra("cases") ?: "llama_cases.json")
        val out = File(dir, intent.getStringExtra("out") ?: "llama_results.jsonl")
        val threads = intent.getIntExtra("threads", 6)
        val nCtx = intent.getIntExtra("ctx", 4096)
        val repack = intent.getBooleanExtra("repack", true)
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "already running")
            return
        }
        thread(name = "llama-bench") {
            try {
                bench(model, cases, out, threads, nCtx, repack)
            } catch (e: Throwable) {
                Log.e(TAG, "bench failed", e)
            } finally {
                running.set(false)
            }
        }
    }

    private fun bench(model: File, casesFile: File, out: File, threads: Int, nCtx: Int, repack: Boolean) {
        if (!initialized) {
            LlamaNative.init()
            initialized = true
            Log.i(TAG, "system: ${LlamaNative.systemInfo()}")
        }
        val pss0 = Debug.getPss()
        val t0 = SystemClock.elapsedRealtime()
        if (!LlamaNative.load(model.path, nCtx, threads, repack)) {
            Log.e(TAG, "load failed: ${model.path}")
            return
        }
        val loadMs = SystemClock.elapsedRealtime() - t0
        val pss1 = Debug.getPss()
        Log.i(TAG, "load ${model.name} ${loadMs}ms threads=$threads ctx=$nCtx repack=$repack pss ${pss0 / 1024}MB -> ${pss1 / 1024}MB")

        val cases = JSONArray(casesFile.readText())
        out.bufferedWriter().use { w ->
            w.write(
                JSONObject().put(
                    "_meta",
                    JSONObject().put("model", model.name).put("load_ms", loadMs).put("threads", threads)
                        .put("ctx", nCtx).put("repack", repack).put("pss_before_kb", pss0).put("pss_after_kb", pss1),
                ).toString(),
            )
            w.newLine()
            for (i in 0 until cases.length()) {
                val c = cases.getJSONObject(i)
                val id = c.getString("id")
                val bytes = LlamaNative.run(c.getString("system"), c.getString("user"), c.optInt("max_tokens", 256))
                if (bytes == null) {
                    Log.e(TAG, "$id failed")
                    continue
                }
                val text = String(bytes, Charsets.UTF_8)
                val head = text.substringBefore('\n').split(' ').map { it.toLong() }
                val answer = text.substringAfter('\n')
                val (nPrompt, promptUs, firstUs, nGen, genUs) = head
                val row = JSONObject()
                    .put("id", id)
                    .put("n_prompt", nPrompt)
                    .put("prompt_ms", promptUs / 1000)
                    .put("first_ms", firstUs / 1000)
                    .put("n_gen", nGen)
                    .put("gen_ms", genUs / 1000)
                    .put("pp_tps", if (promptUs > 0) nPrompt * 1e6 / promptUs else 0.0)
                    .put("tg_tps", if (genUs > 0 && nGen > 1) (nGen - 1) * 1e6 / genUs else 0.0)
                    .put("answer", answer)
                w.write(row.toString())
                w.newLine()
                w.flush()
                Log.i(
                    TAG,
                    "$id prompt=$nPrompt first=${firstUs / 1000}ms gen=$nGen " +
                        "pp=%.1f tg=%.1f tok/s | %s".format(row.getDouble("pp_tps"), row.getDouble("tg_tps"), answer.replace('\n', ' ').take(120)),
                )
            }
        }
        LlamaNative.free()
        Log.i(TAG, "done -> ${out.path}")
    }

    companion object {
        private const val TAG = "LlamaBench"
        private val running = AtomicBoolean(false)

        @Volatile
        private var initialized = false
    }
}
