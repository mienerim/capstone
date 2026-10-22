package capstone.llm.litertlm

import capstone.llm.LlmRequest
import capstone.llm.LlmRunner
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * LiteRT-LM 0.18.0 어댑터(D11·D38·D40). **미빌드**: 클라우드에서 Google Maven이 막혀 컴파일하지 못했다.
 * 클래스·인자 이름은 LiteRT-LM Kotlin 가이드와 이슈(#1662·#3944)의 예시를 따른 것이라, 10-26 팀 PC에서
 * 0.18.0 AAR로 컴파일해 맞춘다(docs/DECISIONS-IMPL I-39).
 *
 * 설정은 D11 그대로: CPU 기본, temperature 0·topK 1(결정적), thinking·추측 디코딩 끔(기본값이 꺼짐이라 따로 켜지 않는다),
 * 요청마다 새 Conversation(enableResponseFormat은 대화 단위). 8GB 기기는 JSON 확정 뒤 [close]로 엔진을 내린다(D31).
 *
 * @param modelFile getExternalFilesDir 아래 adb push한 .litertlm(D40). 모델은 저장소에 넣지 않는다.
 */
class LiteRtLmRunner(
    private val modelFile: File,
    private val cacheDir: File,
    private val backend: Backend = Backend.CPU,
) : LlmRunner {
    private var engine: Engine? = null

    @Volatile private var job: Job? = null

    /** 콜드 로드는 수 초 걸린다(공식 '최대 약 10초'). 메인 스레드에서 부르지 않는다. */
    @Synchronized
    fun ensureLoaded(): Engine = engine ?: Engine(
        EngineConfig(modelPath = modelFile.absolutePath, backend = backend, cacheDir = cacheDir.absolutePath),
    ).also {
        it.initialize()
        engine = it
    }

    override fun generate(request: LlmRequest, sink: (String) -> Boolean) {
        val conversation = ensureLoaded().createConversation(
            ConversationConfig(
                systemInstruction = request.systemInstruction,
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = SEED),
                maxOutputToken = request.maxOutputTokens,
                enableResponseFormat = request.responseSchema != null,
            ),
        )
        try {
            runBlocking {
                job = coroutineContext[Job]
                val format = request.responseSchema?.let { ResponseFormat.json(it) }
                conversation.sendMessageAsync(request.userText, responseFormat = format)
                    .takeWhile { msg -> sink(msg.toString()) }
                    .collect {}
            }
        } finally {
            job = null
            conversation.close()
        }
    }

    /** 워치독 시간 초과. 수집 중인 코루틴을 취소해 생성 스레드를 풀어 준다. */
    override fun cancel() {
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
