package capstone.llm.litertlm

import capstone.llm.ContextBudget
import capstone.llm.LlmRequest
import capstone.llm.LlmRunner
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * LiteRT-LM 0.18.0 어댑터(D11·D38·D40·D65). **미빌드**: 클라우드에서 Google Maven이 막혀 컴파일하지 못했다.
 * 10회차에 클래스·인자 이름을 v0.18.0 태그의 Kotlin 원본(Config.kt·Engine.kt·Conversation.kt·Message.kt·ResponseFormat.kt)과
 * 대조해 맞췄다(I-62). 10-26 팀 PC에서 AAR로 컴파일해 최종 확인한다.
 *
 * 설정: CPU 기본, temperature 0·topK 1(결정적), thinking 끔(D65: Gemma에는 영향 없고 Qwen3 W13 비교에서 필요),
 * 추측 디코딩은 기본값(꺼짐) 그대로, 요청마다 새 Conversation(KV가 대화 단위로 쌓이므로 이력을 남기지 않는다, D13·D65).
 * 8GB 기기는 JSON 확정 뒤 [close]로 엔진을 내린다(D31).
 *
 * @param modelFile getExternalFilesDir 아래 adb push한 .litertlm(D40). 모델은 저장소에 넣지 않는다.
 * @param maxNumTokens 입력+출력 KV 한도 **요청값**. 전 기기 [ContextBudget.MAX_NUM_TOKENS](D77). W03 상향 판정 때만 다른 값을 넣는다.
 *   정적 .litertlm은 파일 KV보다 큰 요청을 조용히 파일 값으로 낮추므로 실제 예산은 [budget]이다(D79).
 */
class LiteRtLmRunner(
    private val modelFile: File,
    private val cacheDir: File,
    private val maxNumTokens: Int = ContextBudget.MAX_NUM_TOKENS,
    private val backend: Backend = Backend.CPU(),
) : LlmRunner {
    private var engine: Engine? = null

    /**
     * 실효 예산 = min(요청, 파일 KV)(D79). 지금은 파일명 표지(`_ekv1280`)만 읽는다.
     * 10-26: AAR에 `ModelInfo.from(path).maxContextTokens()`가 보이면 그 값으로 바꾸고, 없으면 litert-lm-peek 값을 상수로 둔다.
     * 실험 CSV에는 [ContextBudget.Budget.csv]를 필수 열로 적는다(기기·모델 간 조건이 달라지므로).
     */
    val budget: ContextBudget.Budget = ContextBudget.effective(ContextBudget.fileKvFromName(modelFile.name), maxNumTokens)

    @Volatile private var job: Job? = null

    @Volatile private var current: Conversation? = null

    /**
     * 콜드 로드는 수 초 걸린다(공식 '최대 약 10초'). 메인 스레드에서 부르지 않는다.
     * D13 정정: 8GB 기기는 STT 종료·언로드 직후에 처음 부른다(발화 중 선로드 금지, W03에서 동시 피크 PSS·LMK 0회가
     * 확인된 기기만 예외). 콜드 로드 구간은 앱의 대기 피드백(D73)이 메운다.
     */
    @Synchronized
    fun ensureLoaded(): Engine = engine ?: Engine(
        // maxNumTokens = KV 캐시 크기라 8GB 기기 메모리에 직접 걸린다(W04). 파일 KV보다 크면 엔진이 WARNING "…Using model limit."
        // 후 낮춘다(D79, 첫날 logcat으로 확인). 입력 초과는 SlotParser 단계 사전 검사가 먼저 막는다.
        EngineConfig(modelPath = modelFile.absolutePath, backend = backend, maxNumTokens = maxNumTokens, cacheDir = cacheDir.absolutePath),
    ).also {
        it.initialize()
        engine = it
    }

    override fun generate(request: LlmRequest, sink: (String) -> Boolean) {
        val conversation = ensureLoaded().createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(request.systemInstruction),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = SEED),
                // 요청 1회의 생성 상한(thinking 포함). 닿으면 에러 없이 끝나므로 잘림은 검증기가 잡는다(D65).
                maxOutputToken = request.maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = request.responseSchema != null,
            ),
        )
        current = conversation
        var stoppedEarly = true
        try {
            runBlocking {
                job = coroutineContext[Job]
                val format = request.responseSchema?.let { ResponseFormat.json(it) }
                var accepted = true
                conversation.sendMessageAsync(request.userText, responseFormat = format)
                    .takeWhile { msg -> sink(msg.toString()).also { accepted = it } }
                    .collect {}
                stoppedEarly = !accepted
            }
        } finally {
            // sink가 멈추라고 했거나(워치독) 예외로 나가면 수집만 끝나고 네이티브 생성은 계속 돈다(awaitClose가 비어 있음).
            // 다음 요청이 같은 엔진에 겹치지 않도록 대화를 닫기 전에 생성을 먼저 멈춘다(I-95).
            if (stoppedEarly) runCatching { conversation.cancelProcess() }
            job = null
            current = null
            conversation.close()
        }
    }

    /**
     * 워치독 시간 초과. 네이티브 생성은 코루틴 취소만으로 멈추지 않으므로(callbackFlow의 awaitClose가 비어 있음)
     * [Conversation.cancelProcess]로 엔진을 먼저 멈춘 뒤 수집 코루틴을 취소한다.
     */
    override fun cancel() {
        current?.let { runCatching { it.cancelProcess() } }
        job?.cancel()
    }

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val SEED = 7
    }
}
