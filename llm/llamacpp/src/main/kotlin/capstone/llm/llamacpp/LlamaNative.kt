package capstone.llm.llamacpp

/** llama.cpp JNI(실험, 10-30). 모델 하나만 들고, 한 스레드에서만 부른다. */
internal object LlamaNative {
    init {
        System.loadLibrary("capstone_llama")
    }

    external fun init()
    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int, repack: Boolean): Boolean

    /** 첫 줄 "n_prompt prompt_us first_us n_gen gen_us", 나머지는 생성 글자(UTF-8). 실패하면 null. */
    external fun run(system: String, user: String, maxTokens: Int): ByteArray?
    external fun free()
}
