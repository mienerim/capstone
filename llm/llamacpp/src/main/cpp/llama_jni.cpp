// llama.cpp JNI(실험, 10-30). 모델 하나, 한 스레드에서만 부른다. 매 호출마다 KV를 비우고 새 대화로 시작한다.
#include <android/log.h>
#include <jni.h>

#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int N_BATCH = 512;

static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static llama_sampler * g_smpl = nullptr;
static common_chat_templates_ptr g_tmpls;

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

static std::string to_str(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static void free_all() {
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    g_tmpls.reset();
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_capstone_llm_llamacpp_LlamaNative_init(JNIEnv *, jobject) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
}

extern "C" JNIEXPORT jstring JNICALL
Java_capstone_llm_llamacpp_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_capstone_llm_llamacpp_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jboolean repack) {
    free_all();
    const std::string path = to_str(env, jpath);

    llama_model_params mp = llama_model_default_params();
    // repack은 가중치를 CPU용 배치로 복사한다(빠르지만 메모리 추가).
    mp.use_extra_bufts = repack;
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) {
        LOGE("load failed: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = N_BATCH;
    cp.n_ubatch = N_BATCH;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("context init failed");
        free_all();
        return JNI_FALSE;
    }

    g_tmpls = common_chat_templates_init(g_model, "");
    // temperature 0과 같다(PC·PocketPal 측정 조건).
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());
    return JNI_TRUE;
}

// 반환: 첫 줄 "n_prompt prompt_us first_us n_gen gen_us", 그다음 줄부터 생성 글자(UTF-8 바이트).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_capstone_llm_llamacpp_LlamaNative_run(JNIEnv * env, jobject, jstring jsystem, jstring juser, jint max_tokens) {
    if (!g_ctx) return nullptr;

    common_chat_msg sys;
    sys.role = "system";
    sys.content = to_str(env, jsystem);
    common_chat_msg usr;
    usr.role = "user";
    usr.content = to_str(env, juser);

    common_chat_templates_inputs in;
    in.messages = {sys, usr};
    in.use_jinja = true;
    in.enable_thinking = false;
    in.add_generation_prompt = true;
    const std::string prompt = common_chat_templates_apply(g_tmpls.get(), in).prompt.str();

    const std::vector<llama_token> tokens = common_tokenize(g_ctx, prompt, true, true);
    const int n_prompt = (int) tokens.size();
    if (n_prompt + max_tokens > (int) llama_n_ctx(g_ctx)) {
        LOGE("prompt too long: %d tokens", n_prompt);
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_smpl);
    common_batch batch(g_ctx);

    const int64_t t0 = ggml_time_us();
    for (int i = 0; i < n_prompt; i += N_BATCH) {
        batch.clear();
        const int n = std::min(N_BATCH, n_prompt - i);
        for (int j = 0; j < n; j++) {
            batch.add(tokens[i + j], i + j, 0, i + j == n_prompt - 1);
        }
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("prompt decode failed at %d", i);
            return nullptr;
        }
    }
    const int64_t t_prompt = ggml_time_us();

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::string out;
    int n_gen = 0;
    int64_t t_first = 0;
    llama_pos pos = n_prompt;
    while (n_gen < max_tokens) {
        const llama_token id = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (n_gen == 0) t_first = ggml_time_us();
        n_gen++;
        if (llama_vocab_is_eog(vocab, id)) break;
        out += common_token_to_piece(g_ctx, id);

        batch.clear();
        batch.add(id, pos++, 0, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("decode failed at gen %d", n_gen);
            break;
        }
    }
    const int64_t t_end = ggml_time_us();

    const std::string head = std::to_string(n_prompt) + " " + std::to_string(t_prompt - t0) + " " +
                             std::to_string(t_first - t0) + " " + std::to_string(n_gen) + " " +
                             std::to_string(t_end - t_first) + "\n";
    const std::string all = head + out;
    jbyteArray arr = env->NewByteArray((jsize) all.size());
    env->SetByteArrayRegion(arr, 0, (jsize) all.size(), (const jbyte *) all.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_capstone_llm_llamacpp_LlamaNative_free(JNIEnv *, jobject) {
    free_all();
}
